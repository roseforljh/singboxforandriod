package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.OsConstants
import android.util.Log
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.kunk.singbox.MainActivity
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.VpnAppMode
import com.kunk.singbox.model.VpnRouteMode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SingBoxService : VpnService() {

    data class ConnectionOwnerStatsSnapshot(
        val calls: Long,
        val invalidArgs: Long,
        val uidResolved: Long,
        val securityDenied: Long,
        val otherException: Long,
        val lastUid: Int,
        val lastEvent: String
    )

    companion object {
        private const val TAG = "SingBoxService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "singbox_vpn"
        
        const val ACTION_START = "com.kunk.singbox.START"
        const val ACTION_STOP = "com.kunk.singbox.STOP"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
        const val ACTION_SERVICE = "com.kunk.singbox.SERVICE"
        const val EXTRA_CONFIG_PATH = "config_path"
        
        @Volatile
        var isRunning = false
            private set(value) {
                field = value
                _isRunningFlow.value = value
            }

        private val _isRunningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isRunningFlow = _isRunningFlow.asStateFlow()

        private val _isStartingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isStartingFlow = _isStartingFlow.asStateFlow()

        private val _lastErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val lastErrorFlow = _lastErrorFlow.asStateFlow()

        @Volatile
        var isStarting = false
            private set(value) {
                field = value
                _isStartingFlow.value = value
            }

        @Volatile
        var isManuallyStopped = false
            private set
        
        private var lastConfigPath: String? = null

        private fun setLastError(message: String?) {
            _lastErrorFlow.value = message
            if (!message.isNullOrBlank()) {
                try {
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("ERROR SingBoxService: $message")
                } catch (_: Exception) {
                }
            }
        }

        private val connectionOwnerCalls = AtomicLong(0)
        private val connectionOwnerInvalidArgs = AtomicLong(0)
        private val connectionOwnerUidResolved = AtomicLong(0)
        private val connectionOwnerSecurityDenied = AtomicLong(0)
        private val connectionOwnerOtherException = AtomicLong(0)

        @Volatile private var connectionOwnerLastUid: Int = 0
        @Volatile private var connectionOwnerLastEvent: String = ""

        fun getConnectionOwnerStatsSnapshot(): ConnectionOwnerStatsSnapshot {
            return ConnectionOwnerStatsSnapshot(
                calls = connectionOwnerCalls.get(),
                invalidArgs = connectionOwnerInvalidArgs.get(),
                uidResolved = connectionOwnerUidResolved.get(),
                securityDenied = connectionOwnerSecurityDenied.get(),
                otherException = connectionOwnerOtherException.get(),
                lastUid = connectionOwnerLastUid,
                lastEvent = connectionOwnerLastEvent
            )
        }

        fun resetConnectionOwnerStats() {
            connectionOwnerCalls.set(0)
            connectionOwnerInvalidArgs.set(0)
            connectionOwnerUidResolved.set(0)
            connectionOwnerSecurityDenied.set(0)
            connectionOwnerOtherException.set(0)
            connectionOwnerLastUid = 0
            connectionOwnerLastEvent = ""
        }
    }
    
    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    interface StateCallback {
        fun onStateChanged(state: ServiceState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateCallbacks = CopyOnWriteArrayList<StateCallback>()
    @Volatile private var serviceState: ServiceState = ServiceState.STOPPED

    private val localBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SingBoxService = this@SingBoxService
    }

    override fun onBind(intent: Intent): IBinder? {
        return if (intent.action == ACTION_SERVICE) {
            localBinder
        } else {
            super.onBind(intent)
        }
    }

    fun getCurrentState(): ServiceState = serviceState

    fun registerStateCallback(callback: StateCallback) {
        stateCallbacks.addIfAbsent(callback)
        mainHandler.post { callback.onStateChanged(serviceState) }
    }

    fun unregisterStateCallback(callback: StateCallback) {
        stateCallbacks.remove(callback)
    }

    private fun updateServiceState(state: ServiceState) {
        if (serviceState == state) return
        serviceState = state
        mainHandler.post {
            for (cb in stateCallbacks) {
                cb.onStateChanged(state)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var boxService: BoxService? = null
    private var currentSettings: AppSettings? = null
    private val serviceSupervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceSupervisorJob)
    private val cleanupSupervisorJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupSupervisorJob)
    @Volatile private var isStopping: Boolean = false
    @Volatile private var stopSelfRequested: Boolean = false
    @Volatile private var pendingStartConfigPath: String? = null
    @Volatile private var connectionOwnerPermissionDeniedLogged = false
    @Volatile private var startVpnJob: Job? = null
    @Volatile private var realTimeNodeName: String? = null
    @Volatile private var nodePollingJob: Job? = null

    @Volatile private var lastRuleSetCheckMs: Long = 0L
    private val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    private val uidToPackageCache = ConcurrentHashMap<Int, String>()
    @Volatile private var uidToPackageCacheReady: Boolean = false
    private fun ensureUidToPackageCache() {
        if (uidToPackageCacheReady) return
        synchronized(uidToPackageCache) {
            if (uidToPackageCacheReady) return
            try {
                val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in apps) {
                    val uid = app.uid
                    if (uid > 0 && !uidToPackageCache.containsKey(uid)) {
                        uidToPackageCache[uid] = app.packageName
                    }
                }
                uidToPackageCacheReady = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build uid->package cache", e)
            }
        }
    }

    // Auto reconnect
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var nativeUrlTestWarmupJob: Job? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName: String = ""
    private var lastKnownNetwork: Network? = null
    private var vpnHealthJob: Job? = null
    
    // Auto reconnect states
    private var autoReconnectEnabled: Boolean = false
    private var lastAutoReconnectAttemptMs: Long = 0L
    private val autoReconnectDebounceMs: Long = 10000L
    private var autoReconnectJob: Job? = null
    
    // 网络就绪标志：确保 Libbox 启动前网络回调已完成初始采样
    @Volatile private var networkCallbackReady: Boolean = false
    @Volatile private var noPhysicalNetworkWarningLogged: Boolean = false
    @Volatile private var postTunRebindJob: Job? = null
    
    // Platform interface implementation
    private val platformInterface = object : PlatformInterface {
        override fun usePlatformInterfaceGetter(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            val result = protect(fd)
            // Log.v(TAG, "autoDetectInterfaceControl: $fd, protect result: $result")
        }
        
        override fun openTun(options: TunOptions?): Int {
            Log.v(TAG, "openTun called")
            if (options == null) return -1
            
            // Close existing interface if any to prevent fd leaks and "zombie" states
            synchronized(this@SingBoxService) {
                vpnInterface?.let {
                    Log.w(TAG, "Closing stale vpnInterface before establishing new one")
                    try { it.close() } catch (_: Exception) {}
                    vpnInterface = null
                }
            }

            val settings = currentSettings
            val builder = Builder()
                .setSession("SingBox VPN")
                .setMtu(if (options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500))
            
            // 添加地址
            builder.addAddress("172.19.0.1", 30)
            builder.addAddress("fd00::1", 126)
            
            // 添加路由
            val routeMode = settings?.vpnRouteMode ?: VpnRouteMode.GLOBAL
            val cidrText = settings?.vpnRouteIncludeCidrs.orEmpty()
            val cidrs = cidrText
                .split("\n", "\r", ",", ";", " ", "\t")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            fun addCidrRoute(cidr: String): Boolean {
                val parts = cidr.split("/")
                if (parts.size != 2) return false
                val ip = parts[0].trim()
                val prefix = parts[1].trim().toIntOrNull() ?: return false
                return try {
                    val addr = InetAddress.getByName(ip)
                    builder.addRoute(addr, prefix)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            val usedCustomRoutes = if (routeMode == VpnRouteMode.CUSTOM) {
                var okCount = 0
                cidrs.forEach { if (addCidrRoute(it)) okCount++ }
                okCount > 0
            } else {
                false
            }

            if (!usedCustomRoutes) {
                // fallback: 全局接管
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
            
            // 添加 DNS (优先使用设置中的 DNS)
            val dnsServers = mutableListOf<String>()
            if (settings != null) {
                if (isNumericAddress(settings.remoteDns)) dnsServers.add(settings.remoteDns)
                if (isNumericAddress(settings.localDns)) dnsServers.add(settings.localDns)
            }
            
            if (dnsServers.isEmpty()) {
                dnsServers.add("8.8.8.8")
                dnsServers.add("8.8.4.4")
                dnsServers.add("223.5.5.5")
            }
            
            dnsServers.distinct().forEach {
                try {
                    builder.addDnsServer(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add DNS server: $it", e)
                }
            }
            
            // 分应用
            fun parsePackageList(raw: String): List<String> {
                return raw
                    .split("\n", "\r", ",", ";", " ", "\t")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }

            val appMode = settings?.vpnAppMode ?: VpnAppMode.ALL
            val allowPkgs = parsePackageList(settings?.vpnAllowlist.orEmpty())
            val blockPkgs = parsePackageList(settings?.vpnBlocklist.orEmpty())

            try {
                when (appMode) {
                    VpnAppMode.ALL -> {
                        builder.addDisallowedApplication(packageName)
                    }
                    VpnAppMode.ALLOWLIST -> {
                        if (allowPkgs.isEmpty()) {
                            Log.w(TAG, "Allowlist is empty, falling back to ALL mode (excluding self)")
                            builder.addDisallowedApplication(packageName)
                        } else {
                            var addedCount = 0
                            allowPkgs.forEach { pkg ->
                                if (pkg == packageName) return@forEach
                                try {
                                    builder.addAllowedApplication(pkg)
                                    addedCount++
                                } catch (e: PackageManager.NameNotFoundException) {
                                    Log.w(TAG, "Allowed app not found: $pkg")
                                }
                            }
                            if (addedCount == 0) {
                                Log.w(TAG, "No valid apps in allowlist, falling back to ALL mode")
                                builder.addDisallowedApplication(packageName)
                            }
                        }
                    }
                    VpnAppMode.BLOCKLIST -> {
                        blockPkgs.forEach { pkg ->
                            try {
                                builder.addDisallowedApplication(pkg)
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.w(TAG, "Disallowed app not found: $pkg")
                            }
                        }
                        builder.addDisallowedApplication(packageName)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply per-app VPN settings")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                
                // 追加 HTTP 代理至 VPN
                if (settings?.appendHttpProxy == true && settings.proxyPort > 0) {
                    try {
                        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", settings.proxyPort))
                        Log.i(TAG, "HTTP Proxy appended to VPN: 127.0.0.1:${settings.proxyPort}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set HTTP proxy for VPN", e)
                    }
                }
            }
            
            // 设置底层网络 - 关键！让 VPN 流量可以通过物理网络出去
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val activePhysicalNetwork = findBestPhysicalNetwork()
                
                if (activePhysicalNetwork != null) {
                    val caps = connectivityManager?.getNetworkCapabilities(activePhysicalNetwork)
                    val capsStr = buildString {
                        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) append("INTERNET ")
                        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) append("NOT_VPN ")
                        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) append("VALIDATED ")
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) append("WIFI ")
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) append("CELLULAR ")
                    }
                    builder.setUnderlyingNetworks(arrayOf(activePhysicalNetwork))
                    Log.i(TAG, "Set underlying network: $activePhysicalNetwork (caps: $capsStr)")
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("INFO openTun: underlying network = $activePhysicalNetwork ($capsStr)")
                } else {
                    // 无物理网络，记录一次性警告
                    if (!noPhysicalNetworkWarningLogged) {
                        noPhysicalNetworkWarningLogged = true
                        Log.w(TAG, "No physical network found for underlying networks - VPN may not work correctly!")
                        com.kunk.singbox.repository.LogRepository.getInstance()
                            .addLog("WARN openTun: No physical network found - traffic may be blackholed!")
                    }
                    builder.setUnderlyingNetworks(null) // Let system decide
                    schedulePostTunRebind("openTun_no_physical")
                }
            }

            val alwaysOnPkg = runCatching {
                Settings.Secure.getString(contentResolver, "always_on_vpn_app")
            }.getOrNull() ?: runCatching {
                Settings.Global.getString(contentResolver, "always_on_vpn_app")
            }.getOrNull()

            val lockdownValueSecure = runCatching {
                Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0)
            }.getOrDefault(0)
            val lockdownValueGlobal = runCatching {
                Settings.Global.getInt(contentResolver, "always_on_vpn_lockdown", 0)
            }.getOrDefault(0)
            val lockdown = lockdownValueSecure != 0 || lockdownValueGlobal != 0

            if (!alwaysOnPkg.isNullOrBlank() || lockdown) {
                Log.i(TAG, "Always-on VPN status: pkg=$alwaysOnPkg lockdown=$lockdown")
            }

            if (lockdown && !alwaysOnPkg.isNullOrBlank() && alwaysOnPkg != packageName) {
                throw IllegalStateException("VPN lockdown enabled by $alwaysOnPkg")
            }

            val backoffMs = longArrayOf(0L, 250L, 250L, 500L, 500L, 1000L, 1000L, 2000L, 2000L, 2000L)
            var lastFd = -1
            var attempt = 0
            for (sleepMs in backoffMs) {
                if (isStopping) {
                    throw IllegalStateException("VPN stopping")
                }
                if (sleepMs > 0) {
                    SystemClock.sleep(sleepMs)
                }
                attempt++
                vpnInterface = builder.establish()
                lastFd = vpnInterface?.fd ?: -1
                if (vpnInterface != null && lastFd >= 0) {
                    break
                }
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null
            }

            val fd = lastFd
            if (vpnInterface == null || fd < 0) {
                val cm = connectivityManager
                val otherVpnActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                    runCatching {
                        cm.allNetworks.any { network ->
                            val caps = cm.getNetworkCapabilities(network) ?: return@any false
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        }
                    }.getOrDefault(false)
                } else {
                    false
                }
                val reason = "VPN interface establish failed (fd=$fd, alwaysOn=$alwaysOnPkg, lockdown=$lockdown, otherVpn=$otherVpnActive)"
                Log.e(TAG, reason)
                throw IllegalStateException(reason)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val bestNetwork = findBestPhysicalNetwork()
                if (bestNetwork != null) {
                    try {
                        setUnderlyingNetworks(arrayOf(bestNetwork))
                        lastKnownNetwork = bestNetwork
                    } catch (_: Exception) {
                    }
                }
            }

            Log.i(TAG, "TUN interface established with fd: $fd")
            return fd
        }
        
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun usePlatformDefaultInterfaceMonitor(): Boolean = true

        override fun useProcFS(): Boolean {
            val procPaths = listOf(
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6"
            )

            fun hasUidHeader(path: String): Boolean {
                return try {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) return false
                    val header = file.bufferedReader().use { it.readLine() } ?: return false
                    header.contains("uid")
                } catch (_: Exception) {
                    false
                }
            }

            val readable = procPaths.all { path -> hasUidHeader(path) }

            if (!readable) {
                connectionOwnerLastEvent = "procfs_unreadable_or_no_uid -> force findConnectionOwner"
            }

            return readable
        }
         
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): Int {
            connectionOwnerCalls.incrementAndGet()

            fun findUidFromProcFsBySourcePort(protocol: Int, srcPort: Int): Int {
                if (srcPort <= 0) return 0

                val procFiles = when (protocol) {
                    OsConstants.IPPROTO_TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
                    OsConstants.IPPROTO_UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
                    else -> emptyList()
                }
                if (procFiles.isEmpty()) return 0

                val targetPortHex = srcPort.toString(16).uppercase().padStart(4, '0')

                fun parseUidFromLine(parts: List<String>): Int {
                    if (parts.size < 9) return 0
                    val uidStr = parts.getOrNull(7) ?: return 0
                    return uidStr.toIntOrNull() ?: 0
                }

                for (path in procFiles) {
                    try {
                        val file = File(path)
                        if (!file.exists() || !file.canRead()) continue
                        var resultUid = 0
                        file.bufferedReader().useLines { lines ->
                            for (line in lines.drop(1)) {
                                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                val local = parts.getOrNull(1) ?: continue
                                val portHex = local.substringAfter(':', "").uppercase()
                                if (portHex == targetPortHex) {
                                    val uid = parseUidFromLine(parts)
                                    if (uid > 0) {
                                        resultUid = uid
                                        break
                                    }
                                }
                            }
                        }
                        if (resultUid > 0) return resultUid
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to read proc file: $path", e)
                    }
                }
                return 0
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent = "api<29"
                return 0
            }

            fun parseAddress(value: String?): InetAddress? {
                if (value.isNullOrBlank()) return null
                // Remove brackets for IPv6 [::1] -> ::1 and remove scope ID
                val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
                return try {
                    InetAddress.getByName(cleaned)
                } catch (_: Exception) {
                    null
                }
            }

            val sourceIp = parseAddress(sourceAddress)
            val destinationIp = parseAddress(destinationAddress)

            val protocol = when (ipProtocol) {
                OsConstants.IPPROTO_TCP -> OsConstants.IPPROTO_TCP
                OsConstants.IPPROTO_UDP -> OsConstants.IPPROTO_UDP
                else -> ipProtocol
            }

            if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) {
                val uid = findUidFromProcFsBySourcePort(protocol, sourcePort)
                if (uid > 0) {
                    connectionOwnerUidResolved.incrementAndGet()
                    connectionOwnerLastUid = uid
                    connectionOwnerLastEvent =
                        "procfs_fallback uid=$uid proto=$protocol src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort"
                    return uid
                }

                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent =
                    "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
                return 0
            }

            return try {
                val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java) ?: return 0
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(sourceIp, sourcePort),
                    InetSocketAddress(destinationIp, destinationPort)
                )
                if (uid > 0) {
                    connectionOwnerUidResolved.incrementAndGet()
                    connectionOwnerLastUid = uid
                    connectionOwnerLastEvent =
                        "resolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    uid
                } else {
                    connectionOwnerLastEvent =
                        "unresolved uid=$uid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                    0
                }
            } catch (e: SecurityException) {
                connectionOwnerSecurityDenied.incrementAndGet()
                connectionOwnerLastEvent =
                    "SecurityException getConnectionOwnerUid proto=$protocol $sourceIp:$sourcePort->$destinationIp:$destinationPort"
                if (!connectionOwnerPermissionDeniedLogged) {
                    connectionOwnerPermissionDeniedLogged = true
                    Log.w(TAG, "getConnectionOwnerUid permission denied; app routing may not work on this ROM", e)
                    com.kunk.singbox.repository.LogRepository.getInstance()
                        .addLog("WARN SingBoxService: getConnectionOwnerUid permission denied; per-app routing (package_name) disabled on this ROM")
                }
                0
            } catch (e: Exception) {
                connectionOwnerOtherException.incrementAndGet()
                connectionOwnerLastEvent = "Exception ${e.javaClass.simpleName}: ${e.message}"
                0
            }
        }
        
        override fun packageNameByUid(uid: Int): String {
            if (uid <= 0) return ""
            return try {
                val pkgs = packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    pkgs[0]
                } else {
                    ensureUidToPackageCache()
                    uidToPackageCache[uid] ?: ""
                }
            } catch (_: Exception) {
                ensureUidToPackageCache()
                uidToPackageCache[uid] ?: ""
            }
        }
        
        override fun uidByPackageName(packageName: String?): Int {
            if (packageName.isNullOrBlank()) return 0
            return try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val uid = appInfo.uid
                if (uid > 0) uid else 0
            } catch (_: Exception) {
                0
            }
        }
        
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.v(TAG, "startDefaultInterfaceMonitor")
            currentInterfaceListener = listener
            
            connectivityManager = getSystemService(ConnectivityManager::class.java)
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = connectivityManager?.getNetworkCapabilities(network)
                    val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    Log.i(TAG, "Network available: $network (isVpn=$isVpn)")
                    if (!isVpn) {
                        networkCallbackReady = true
                        updateDefaultInterface(network)
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.i(TAG, "Network lost: $network")
                    if (network == lastKnownNetwork) {
                        lastKnownNetwork = null
                        currentInterfaceListener?.updateDefaultInterface("", 0)
                    }
                }
                
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    Log.v(TAG, "Network capabilities changed: $network (isVpn=$isVpn)")
                    if (!isVpn) {
                        networkCallbackReady = true
                        updateDefaultInterface(network)
                    }
                }
            }
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)

            // VPN Health Monitor with enhanced rebind logic
            vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!isRunning) return
                    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (isValidated) {
                        if (vpnHealthJob?.isActive == true) {
                            Log.i(TAG, "VPN link validated, cancelling recovery job")
                            vpnHealthJob?.cancel()
                        }
                        // One-time warmup for native URLTest to avoid cold-start -1 on Nodes page
                        if (nativeUrlTestWarmupJob?.isActive != true) {
                            nativeUrlTestWarmupJob = serviceScope.launch {
                                try {
                                    // Slightly delay after validation to avoid cold DNS/route window
                                    delay(800)
                                    val repo = com.kunk.singbox.repository.ConfigRepository.getInstance(this@SingBoxService)
                                    val activeNodeId = repo.activeNodeId.value
                                    if (activeNodeId.isNullOrBlank()) {
                                        Log.i(TAG, "Native URLTest warmup skipped: no active node")
                                        return@launch
                                    }
                                    val nodeName = repo.nodes.value.find { it.id == activeNodeId }?.name
                                    val r1 = withTimeoutOrNull(3000L) { repo.testNodeLatency(activeNodeId) } ?: -1L
                                    Log.i(TAG, "Native URLTest warmup done(validated): ${nodeName ?: activeNodeId} -> $r1 ms")
                                    if (r1 < 0) {
                                        // Second-chance warmup for devices that still need a bit more time
                                        delay(1000)
                                        val r2 = withTimeoutOrNull(3000L) { repo.testNodeLatency(activeNodeId) } ?: -1L
                                        Log.i(TAG, "Native URLTest warmup done(validated-2nd): ${nodeName ?: activeNodeId} -> $r2 ms")
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) {
                                        return@launch
                                    }
                                    Log.w(TAG, "Native URLTest warmup failed", e)
                                }
                            }
                        }
                    } else {
                        // Start a delayed recovery if not already running
                        if (vpnHealthJob?.isActive != true) {
                            Log.w(TAG, "VPN link not validated, scheduling recovery in 5s")
                            vpnHealthJob = serviceScope.launch {
                                delay(5000)
                                if (isRunning && !isStarting && !isManuallyStopped && lastConfigPath != null) {
                                    Log.w(TAG, "VPN link still not validated after 5s, attempting rebind and reset")
                                    try {
                                        // 尝试重新绑定底层网络
                                        val bestNetwork = findBestPhysicalNetwork()
                                        if (bestNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                            setUnderlyingNetworks(arrayOf(bestNetwork))
                                            lastKnownNetwork = bestNetwork
                                            Log.i(TAG, "Rebound underlying network to $bestNetwork during health recovery")
                                            com.kunk.singbox.repository.LogRepository.getInstance()
                                                .addLog("INFO VPN health recovery: rebound to $bestNetwork")
                                        }
                                        boxService?.resetNetwork()
                                        Log.d(TAG, "Core network stack reset triggered during health recovery")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to reset network stack during health recovery", e)
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    vpnHealthJob?.cancel()
                    nativeUrlTestWarmupJob?.cancel()
                }
            }

            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            try {
                connectivityManager?.registerNetworkCallback(vpnRequest, vpnNetworkCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register VPN network callback", e)
            }
            
            // Get current default interface - 立即采样一次以初始化 lastKnownNetwork
            val activeNet = connectivityManager?.activeNetwork
            if (activeNet != null) {
                val caps = connectivityManager?.getNetworkCapabilities(activeNet)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (!isVpn && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    networkCallbackReady = true
                    updateDefaultInterface(activeNet)
                }
            }
        }
        
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.v(TAG, "closeDefaultInterfaceMonitor")
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
            vpnNetworkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }
            vpnHealthJob?.cancel()
            postTunRebindJob?.cancel()
            postTunRebindJob = null
            vpnNetworkCallback = null
            networkCallback = null
            currentInterfaceListener = null
            networkCallbackReady = false
        }
        
        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()
                    
                    override fun hasNext(): Boolean = iterator.hasNext()
                    
                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu
                            
                            // type = ... (Field removed in v1.10)
                            
                            // Flags
                            var flagsStr = 0
                            if (iface.isUp) flagsStr = flagsStr or 1
                            if (iface.isLoopback) flagsStr = flagsStr or 4
                            if (iface.isPointToPoint) flagsStr = flagsStr or 8
                            if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                            flags = flagsStr
                            
                            // Addresses
                            val addrList = ArrayList<String>()
                            for (addr in iface.interfaceAddresses) {
                                val ip = addr.address.hostAddress
                                // Remove %interface suffix if present (IPv6)
                                val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                                if (cleanIp != null) {
                                    addrList.add("$cleanIp/${addr.networkPrefixLength}")
                                }
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get interfaces", e)
                null
            }
        }
        
        override fun underNetworkExtension(): Boolean = false
        
        override fun includeAllNetworks(): Boolean = false
        
        override fun readWIFIState(): WIFIState? = null
        
        override fun clearDNSCache() {}
        
        override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {}
        
        // override fun localDNSTransport(): LocalDNSTransport? = null
        
        // override fun systemCertificates(): StringIterator? = null
        
        override fun writeLog(message: String?) {
            if (message.isNullOrBlank()) return
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "libbox: $message")
            }
            com.kunk.singbox.repository.LogRepository.getInstance().addLog(message)
        }
    }
    
    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }
    
    /**
     * 查找最佳物理网络（非 VPN、有 Internet 能力，优先 VALIDATED）
     */
    private fun findBestPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null
        
        // 优先使用已缓存的 lastKnownNetwork（如果仍然有效）
        lastKnownNetwork?.let { cached ->
            val caps = cm.getNetworkCapabilities(cached)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return cached
            }
        }
        
        // 遍历所有网络，筛选物理网络
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val allNetworks = cm.allNetworks
            var bestNetwork: Network? = null
            var bestValidated = false
            
            for (net in allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (hasInternet && notVpn) {
                    if (validated) {
                        // 找到一个 VALIDATED 的，直接返回
                        return net
                    }
                    if (bestNetwork == null || !bestValidated) {
                        bestNetwork = net
                        bestValidated = validated
                    }
                }
            }
            
            if (bestNetwork != null) return bestNetwork
        }
        
        // fallback: 使用 activeNetwork
        return cm.activeNetwork?.takeIf {
            val caps = cm.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
    }
    
    private fun updateDefaultInterface(network: Network) {
        try {
            // 验证网络是否为有效的物理网络
            val caps = connectivityManager?.getNetworkCapabilities(network)
            val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                  caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            
            if (!isValidPhysical) {
                Log.v(TAG, "updateDefaultInterface: network $network is not a valid physical network, skipping")
                return
            }
            
            val linkProperties = connectivityManager?.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName ?: ""
            val upstreamChanged = interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && (network != lastKnownNetwork || upstreamChanged)) {
                setUnderlyingNetworks(arrayOf(network))
                lastKnownNetwork = network
                noPhysicalNetworkWarningLogged = false // 重置警告标志
                postTunRebindJob?.cancel()
                postTunRebindJob = null
                Log.i(TAG, "Switched underlying network to $network (upstream=$interfaceName)")

                // Notify the core to reset its network stack and re-bind sockets
                serviceScope.launch {
                    try {
                        boxService?.resetNetwork()
                        Log.d(TAG, "Core network stack reset triggered")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset core network stack", e)
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (
                autoReconnectEnabled &&
                !isRunning &&
                !isStarting &&
                !isManuallyStopped &&
                lastConfigPath != null &&
                now - lastAutoReconnectAttemptMs >= autoReconnectDebounceMs
            ) {
                lastAutoReconnectAttemptMs = now
                autoReconnectJob?.cancel()
                autoReconnectJob = serviceScope.launch {
                    delay(800)
                    if (!isRunning && !isStarting && !isManuallyStopped && lastConfigPath != null) {
                        Log.i(TAG, "Auto-reconnecting on network available: $interfaceName")
                        startVpn(lastConfigPath!!)
                    }
                }
            }

            if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
                defaultInterfaceName = interfaceName
                val index = try {
                    NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (e: Exception) { 0 }
                val caps = connectivityManager?.getNetworkCapabilities(network)
                Log.i(TAG, "Default interface updated: $interfaceName (index: $index)")
                currentInterfaceListener?.updateDefaultInterface(interfaceName, index)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update default interface", e)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 初始化 ConnectivityManager
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        serviceScope.launch {
            SettingsRepository.getInstance(this@SingBoxService)
                .settings
                .map { it.autoReconnect }
                .distinctUntilChanged()
                .collect { enabled ->
                    autoReconnectEnabled = enabled
                }
        }
        
        // 监听活动节点变化，更新通知
        serviceScope.launch {
            ConfigRepository.getInstance(this@SingBoxService).activeNodeId.collect { activeNodeId ->
                if (isRunning) {
                    updateNotification()
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                isManuallyStopped = false
                VpnTileService.persistVpnPending(applicationContext, "starting")
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    updateServiceState(ServiceState.STARTING)
                    synchronized(this) {
                        if (isStarting) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            return START_NOT_STICKY
                        }
                        if (isStopping) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            return START_NOT_STICKY
                        }
                        // If already running, do a clean restart to avoid half-broken tunnel state
                        if (isRunning) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                        }
                    }
                    if (isRunning) {
                        stopVpn(stopService = false)
                    } else {
                        startVpn(configPath)
                    }
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP (manual) -> stopping VPN")
                isManuallyStopped = true
                VpnTileService.persistVpnPending(applicationContext, "stopping")
                updateServiceState(ServiceState.STOPPING)
                synchronized(this) {
                    pendingStartConfigPath = null
                }
                stopVpn(stopService = true)
            }
            ACTION_SWITCH_NODE -> {
                Log.i(TAG, "Received ACTION_SWITCH_NODE -> switching node")
                switchNextNode()
            }
        }
        // Do not restart automatically with null intents; explicit start/stop is required.
        return START_NOT_STICKY
    }
    
    private fun switchNextNode() {
        serviceScope.launch {
            val configRepository = ConfigRepository.getInstance(this@SingBoxService)
            val nodes = configRepository.nodes.value
            if (nodes.isEmpty()) return@launch
            
            val activeNodeId = configRepository.activeNodeId.value
            val currentIndex = nodes.indexOfFirst { it.id == activeNodeId }
            val nextIndex = (currentIndex + 1) % nodes.size
            val nextNode = nodes[nextIndex]
            
            val success = configRepository.setActiveNode(nextNode.id)
            if (success) {
                updateNotification()
            }
        }
    }
    
    private fun startVpn(configPath: String) {
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "VPN already running, ignore start request")
                return
            }
            if (isStarting) {
                Log.w(TAG, "VPN is already in starting process, ignore start request")
                return
            }
            if (isStopping) {
                Log.w(TAG, "VPN is stopping, queue start request")
                pendingStartConfigPath = configPath
                stopSelfRequested = false
                lastConfigPath = configPath
                return
            }
            isStarting = true
            realTimeNodeName = null
        }

        updateServiceState(ServiceState.STARTING)
        setLastError(null)
        
        lastConfigPath = configPath
        Log.d(TAG, "Attempting to start foreground service with ID: $NOTIFICATION_ID")
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
        }
        
        startVpnJob?.cancel()
        startVpnJob = serviceScope.launch {
            try {
                val prepareIntent = VpnService.prepare(this@SingBoxService)
                if (prepareIntent != null) {
                    val msg = "需要授予 VPN 权限，请在系统弹窗中允许（如果已开启其他 VPN，系统可能会要求再次确认）"
                    Log.w(TAG, msg)
                    setLastError(msg)
                    VpnTileService.persistVpnState(applicationContext, false)
                    VpnTileService.persistVpnPending(applicationContext, "")
                    updateServiceState(ServiceState.STOPPED)
                    updateTileState()

                    runCatching {
                        prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(prepareIntent)
                    }.onFailure {
                        runCatching {
                            val manager = getSystemService(NotificationManager::class.java)
                            val pi = PendingIntent.getActivity(
                                this@SingBoxService,
                                2002,
                                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            val notification = Notification.Builder(this@SingBoxService, CHANNEL_ID)
                                .setContentTitle("需要 VPN 权限")
                                .setContentText("点此授予 VPN 权限后再启动")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentIntent(pi)
                                .setAutoCancel(true)
                                .build()
                            manager.notify(NOTIFICATION_ID + 3, notification)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } catch (_: Exception) {
                        }
                        stopSelf()
                    }
                    return@launch
                }

                // 0. 预先注册网络回调，确保 lastKnownNetwork 就绪
                // 这一步必须在 Libbox 启动前完成，避免 openTun() 时没有有效的底层网络
                ensureNetworkCallbackReadyWithTimeout()
                
                // 1. 确保规则集就绪（预下载）
                // 如果本地缓存不存在，允许网络下载；如果下载失败也继续启动
                try {
                    val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxService)
                    val now = System.currentTimeMillis()
                    val shouldForceUpdate = now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs
                    if (shouldForceUpdate) {
                        lastRuleSetCheckMs = now
                    }
                    // Always allow network download if rule sets are missing
                    val allReady = ruleSetRepo.ensureRuleSetsReady(
                        forceUpdate = shouldForceUpdate,
                        allowNetwork = true
                    ) { progress ->
                        Log.v(TAG, "Rule set update: $progress")
                    }
                    if (!allReady) {
                        Log.w(TAG, "Some rule sets are not ready, proceeding with available cache")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update rule sets", e)
                }

                // 加载最新设置
                currentSettings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                Log.v(TAG, "Settings loaded: tunEnabled=${currentSettings?.tunEnabled}")

                // 读取配置文件
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    Log.e(TAG, "Config file not found: $configPath")
                    setLastError("Config file not found: $configPath")
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@launch
                }
                val configContent = configFile.readText()
                Log.v(TAG, "Config loaded, length: ${configContent.length}")
                
                try {
                    SingBoxCore.ensureLibboxSetup(this@SingBoxService)
                } catch (e: Exception) {
                    Log.w(TAG, "Libbox setup warning: ${e.message}")
                }
                
                // 创建并启动 BoxService
                boxService = Libbox.newService(configContent, platformInterface)
                boxService?.start()
                Log.i(TAG, "BoxService started")
                
                isRunning = true
                setLastError(null)
                Log.i(TAG, "SingBox VPN started successfully")
                VpnTileService.persistVpnState(applicationContext, true)
                VpnTileService.persistVpnPending(applicationContext, "")
                updateServiceState(ServiceState.RUNNING)
                updateTileState()

                // Start node polling to observe real-time outbound (e.g. for split tunneling/url-test)
                nodePollingJob?.cancel()
                nodePollingJob = serviceScope.launch {
                    val configRepo = ConfigRepository.getInstance(this@SingBoxService)
                    while (isActive && isRunning) {
                        try {
                            val box = boxService
                            if (box != null) {
                                // Try to find getOutboundGroup or similar method via reflection to avoid compile error
                                // while supporting different libbox versions
                                val boxClass = box.javaClass
                                var selected: String? = null
                                
                                // Attempt 1: getOutboundGroup (newer sing-box/libbox versions)
                                val getOutboundGroupMethod = boxClass.methods.find { 
                                    it.name == "getOutboundGroup" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java 
                                }
                                if (getOutboundGroupMethod != null) {
                                    val group = getOutboundGroupMethod.invoke(box, "PROXY")
                                    if (group != null) {
                                        val selectedField = group.javaClass.methods.find { it.name == "getSelected" || it.name == "selected" }
                                        selected = selectedField?.invoke(group) as? String
                                    }
                                }
                                
                                // Attempt 2: getTracker (NB4A style)
                                if (selected.isNullOrBlank()) {
                                    val getTrackerMethod = boxClass.methods.find { it.name == "getTracker" }
                                    val tracker = getTrackerMethod?.invoke(box)
                                    if (tracker != null) {
                                        val getOutboundGroupOnTracker = tracker.javaClass.methods.find {
                                            it.name == "getOutboundGroup" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
                                        }
                                        val group = getOutboundGroupOnTracker?.invoke(tracker, "PROXY")
                                        if (group != null) {
                                            val selectedField = group.javaClass.methods.find { it.name == "getSelected" || it.name == "selected" }
                                            selected = selectedField?.invoke(group) as? String
                                        }
                                    }
                                }
                                
                                if (!selected.isNullOrBlank() && selected != realTimeNodeName) {
                                    realTimeNodeName = selected
                                    Log.i(TAG, "Observed outbound change: $selected")
                                    // Sync back to ConfigRepository to update UI in app
                                    configRepo.syncActiveNodeFromProxySelection(selected)
                                    updateNotification()
                                }
                            }
                        } catch (e: Exception) {
                            // If API is not available or fails, we stay with the static name from ConfigRepository
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "Failed to poll real-time node: ${e.message}")
                            }
                        }
                        delay(3000)
                    }
                }

                serviceScope.launch postStart@{
                    val delays = listOf(800L, 2000L, 5000L)
                    val settings = currentSettings
                    val proxyPort = settings?.proxyPort ?: 0
                    val canProbeProxy = settings?.appendHttpProxy == true && proxyPort > 0

                    suspend fun probeOnce(): Boolean {
                        if (!canProbeProxy) return false
                        return withContext(Dispatchers.IO) {
                            try {
                                val client = OkHttpClient.Builder()
                                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
                                    .connectTimeout(2500, TimeUnit.MILLISECONDS)
                                    .readTimeout(2500, TimeUnit.MILLISECONDS)
                                    .writeTimeout(2500, TimeUnit.MILLISECONDS)
                                    .build()
                                val req = Request.Builder()
                                    .url("https://cp.cloudflare.com/generate_204")
                                    .get()
                                    .build()
                                client.newCall(req).execute().use { resp ->
                                    resp.code in 200..399
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }

                    for (d in delays) {
                        delay(d)
                        if (!isRunning || isStopping) return@postStart

                        if (probeOnce()) {
                            return@postStart
                        }

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                val bestNetwork = findBestPhysicalNetwork()
                                if (bestNetwork != null) {
                                    try {
                                        setUnderlyingNetworks(arrayOf(bestNetwork))
                                        lastKnownNetwork = bestNetwork
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            boxService?.resetNetwork()
                            Log.d(TAG, "Core network stack reset triggered (post-start)")
                        } catch (_: Exception) {
                        }
                    }
                }
                
            } catch (e: CancellationException) {
                Log.i(TAG, "startVpn cancelled")
                // Do not treat cancellation as failure. stopVpn() is already responsible for cleanup.
                return@launch
            } catch (e: Exception) {
                var reason = "Failed to start VPN: ${e.javaClass.simpleName}: ${e.message}"

                val msg = e.message.orEmpty()
                val isTunEstablishFail = msg.contains("VPN interface establish failed", ignoreCase = true) ||
                    msg.contains("configure tun interface", ignoreCase = true) ||
                    msg.contains("fd=-1", ignoreCase = true)

                val isLockdown = msg.contains("VPN lockdown enabled by", ignoreCase = true)

                // 更友好的错误提示
                if (isLockdown) {
                    val lockedBy = msg.substringAfter("VPN lockdown enabled by ").trim().ifBlank { "unknown" }
                    reason = "启动失败：系统启用了锁定/始终开启 VPN（$lockedBy）。请先在系统 VPN 设置里关闭锁定，或把始终开启改为本应用。"
                    isManuallyStopped = true
                } else if (isTunEstablishFail) {
                    reason = "启动失败：无法建立 VPN 接口（fd=-1）。如果 NekoBox 开了“始终开启/锁定 VPN”，本应用无法接管。请在系统 VPN 设置里关闭锁定后重试。"
                    isManuallyStopped = true
                } else if (e is NullPointerException && e.message?.contains("establish") == true) {
                    reason = "启动失败：系统拒绝创建 VPN 接口。可能原因：VPN 权限未授予或被系统限制/与其他 VPN 冲突。"
                    isManuallyStopped = true
                }

                Log.e(TAG, reason, e)
                setLastError(reason)
                VpnTileService.persistVpnPending(applicationContext, "")

                if (isLockdown || isTunEstablishFail) {
                    runCatching {
                        val manager = getSystemService(NotificationManager::class.java)
                        val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pi = PendingIntent.getActivity(
                            this@SingBoxService,
                            2001,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notification = Notification.Builder(this@SingBoxService, CHANNEL_ID)
                            .setContentTitle("VPN 启动失败")
                            .setContentText("可能被其他应用的锁定/始终开启 VPN 阻止，点此打开系统 VPN 设置")
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build()
                        manager.notify(NOTIFICATION_ID + 2, notification)
                    }
                }
                withContext(Dispatchers.Main) {
                    isRunning = false
                    updateServiceState(ServiceState.STOPPED)
                    stopVpn(stopService = true)
                }
                // 启动失败后，尝试重试一次（如果是自动重连触发的，可能因为网络刚切换还不稳定）
                if (lastConfigPath != null && !isManuallyStopped) {
                    Log.i(TAG, "Retrying start VPN in 2 seconds...")
                    delay(2000)
                    if (!isRunning && !isManuallyStopped) {
                        startVpn(lastConfigPath!!)
                    }
                }
            } finally {
                isStarting = false
                startVpnJob = null
            }
        }
    }

    private fun isAnyVpnActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = try {
            getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return false

        return runCatching {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(false)
    }
    
    private fun stopVpn(stopService: Boolean) {
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }
        updateServiceState(ServiceState.STOPPING)

        val jobToJoin = startVpnJob
        startVpnJob = null
        jobToJoin?.cancel()

        vpnHealthJob?.cancel()
        vpnHealthJob = null
        nodePollingJob?.cancel()
        nodePollingJob = null
        nativeUrlTestWarmupJob?.cancel()
        nativeUrlTestWarmupJob = null

        // Reset cached network state to avoid stale underlying network binding across restarts
        networkCallbackReady = false
        lastKnownNetwork = null
        noPhysicalNetworkWarningLogged = false
        defaultInterfaceName = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            runCatching { setUnderlyingNetworks(null) }
        }

        Log.i(TAG, "stopVpn(stopService=$stopService) isManuallyStopped=$isManuallyStopped")

        autoReconnectJob?.cancel()
        autoReconnectJob = null
        postTunRebindJob?.cancel()
        postTunRebindJob = null

        realTimeNodeName = null
        isRunning = false

        val listener = currentInterfaceListener
        val serviceToClose = boxService
        boxService = null

        val interfaceToClose = vpnInterface
        vpnInterface = null

        cleanupScope.launch(NonCancellable) {
            try {
                jobToJoin?.join()
            } catch (_: Exception) {
            }

            try {
                platformInterface.closeDefaultInterfaceMonitor(listener)
            } catch (_: Exception) {
            }

            try {
                // 优先关闭服务并注销运行中引用
                try { serviceToClose?.close() } catch (_: Exception) {}
                try { interfaceToClose?.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }

            withContext(Dispatchers.Main) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping foreground", e)
                }
                if (stopSelfRequested) {
                    stopSelf()
                }
                Log.i(TAG, "VPN stopped")
                VpnTileService.persistVpnState(applicationContext, false)
                VpnTileService.persistVpnPending(applicationContext, "")
                updateServiceState(ServiceState.STOPPED)
                updateTileState()
            }

            val startAfterStop = synchronized(this@SingBoxService) {
                isStopping = false
                val pending = pendingStartConfigPath
                pendingStartConfigPath = null
                val shouldStart = !pending.isNullOrBlank()
                stopSelfRequested = false
                pending?.takeIf { shouldStart }
            }

            if (!startAfterStop.isNullOrBlank()) {
                // Avoid restarting while system VPN transport is still tearing down.
                waitForSystemVpnDown(timeoutMs = 1500L)
                withContext(Dispatchers.Main) {
                    startVpn(startAfterStop)
                }
            }
        }
    }

    private suspend fun waitForSystemVpnDown(timeoutMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = try {
            getSystemService(ConnectivityManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return

        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val hasVpn = runCatching {
                cm.allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }.getOrDefault(false)

            if (!hasVpn) return
            delay(50)
        }
    }

    private fun updateTileState() {
        try {
            TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tile state", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channel: $CHANNEL_ID")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SingBox VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN 服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }
    
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val switchIntent = Intent(this, SingBoxService::class.java).apply {
            action = ACTION_SWITCH_NODE
        }
        val switchPendingIntent = PendingIntent.getService(
            this, 1, switchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, SingBoxService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val configRepository = ConfigRepository.getInstance(this)
        val activeNodeId = configRepository.activeNodeId.value
        val activeNodeName = realTimeNodeName ?: configRepository.nodes.value.find { it.id == activeNodeId }?.name ?: "已连接"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("SingBox VPN")
            setContentText("当前节点: $activeNodeName")
            setSmallIcon(android.R.drawable.ic_lock_lock)
            setContentIntent(pendingIntent)
            setOngoing(true)
            
            // 添加切换节点按钮
            addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    "切换节点",
                    switchPendingIntent
                ).build()
            )
            
            // 添加断开按钮
            addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "断开",
                    stopPendingIntent
                ).build()
            )
        }.build()
    }
    
    override fun onDestroy() {
        Log.i(TAG, "onDestroy called -> stopVpn(stopService=false)")
        val shouldStop = runCatching {
            synchronized(this@SingBoxService) {
                isRunning || isStopping || boxService != null || vpnInterface != null
            }
        }.getOrDefault(false)

        if (shouldStop) {
            stopVpn(stopService = false)
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            VpnTileService.persistVpnState(applicationContext, false)
            updateServiceState(ServiceState.STOPPED)
            updateTileState()
        }
        serviceSupervisorJob.cancel()
        // cleanupSupervisorJob.cancel() // Allow cleanup to finish naturally
        super.onDestroy()
    }
     
    override fun onRevoke() {
        Log.i(TAG, "onRevoke called -> stopVpn(stopService=true)")
        isManuallyStopped = true
        VpnTileService.persistVpnPending(applicationContext, "stopping")
        setLastError("VPN revoked by system (another VPN may have started)")
        
        // 记录日志，告知用户原因
        com.kunk.singbox.repository.LogRepository.getInstance()
            .addLog("WARN: VPN permission revoked by system (possibly another VPN app started)")
            
        // 发送通知提醒用户
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN 已断开")
                .setContentText("检测到 VPN 权限被撤销，可能是其他 VPN 应用已启动。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID + 1, notification)
        }
        
        // 停止服务
        stopVpn(stopService = true)
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If the user swiped away the app, we might want to keep the VPN running 
        // as a foreground service, but some users expect it to stop.
        // Usually, a foreground service continues running.
        // However, if we want to ensure no "zombie" states, we can at least log or check health.
        Log.d(TAG, "onTaskRemoved called")
    }

    private fun isNumericAddress(address: String): Boolean {
        if (address.isBlank()) return false
        // IPv4 regex
        val ipv4Pattern = "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$"
        if (address.matches(Regex(ipv4Pattern))) return true
        
        // IPv6 simple check: contains colon and no path separators
        if (address.contains(":") && !address.contains("/")) {
            return try {
                // If it can be parsed as an InetAddress and it's not a hostname (doesn't require lookup)
                // In Android, InetAddress.getByName(numeric) is fast.
                val inetAddress = InetAddress.getByName(address)
                inetAddress.hostAddress == address || address.contains("[")
            } catch (_: Exception) {
                false
            }
        }
        return false
    }
    
    /**
     * 确保网络回调就绪，最多等待指定超时时间
     * 如果超时仍未就绪，尝试主动采样当前活跃网络
     */
    private suspend fun ensureNetworkCallbackReadyWithTimeout(timeoutMs: Long = 2000L) {
        if (networkCallbackReady && lastKnownNetwork != null) {
            Log.v(TAG, "Network callback already ready, lastKnownNetwork=$lastKnownNetwork")
            return
        }
        
        // 先尝试主动采样
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        
        val activeNet = cm?.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val notVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            
            if (!isVpn && hasInternet && notVpn) {
                lastKnownNetwork = activeNet
                networkCallbackReady = true
                Log.i(TAG, "Pre-sampled physical network: $activeNet")
                return
            }
        }
        
        // 如果主动采样失败，等待回调就绪（带超时）
        val startTime = System.currentTimeMillis()
        while (!networkCallbackReady && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }
        
        if (networkCallbackReady) {
            Log.i(TAG, "Network callback ready after waiting, lastKnownNetwork=$lastKnownNetwork")
        } else {
            // 超时后再次尝试查找最佳物理网络
            val bestNetwork = findBestPhysicalNetwork()
            if (bestNetwork != null) {
                lastKnownNetwork = bestNetwork
                networkCallbackReady = true
                Log.i(TAG, "Found physical network after timeout: $bestNetwork")
            } else {
                Log.w(TAG, "Network callback not ready after ${timeoutMs}ms timeout, proceeding without guaranteed physical network")
                com.kunk.singbox.repository.LogRepository.getInstance()
                    .addLog("WARN startVpn: No physical network found after ${timeoutMs}ms - VPN may not work correctly")
            }
        }
    }
    
    /**
     * 如果 openTun 时未找到物理网络，短时间内快速重试绑定，避免等待 5s 健康检查
     */
    private fun schedulePostTunRebind(reason: String) {
        if (postTunRebindJob?.isActive == true) return
        
        postTunRebindJob = serviceScope.launch rebind@{
            val delays = listOf(300L, 800L, 1500L)
            for (d in delays) {
                delay(d)
                if (isStopping) return@rebind
                
                val bestNetwork = findBestPhysicalNetwork()
                if (bestNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    try {
                        setUnderlyingNetworks(arrayOf(bestNetwork))
                        lastKnownNetwork = bestNetwork
                        noPhysicalNetworkWarningLogged = false
                        Log.i(TAG, "Post-TUN rebind success ($reason): $bestNetwork")
                        com.kunk.singbox.repository.LogRepository.getInstance()
                            .addLog("INFO postTunRebind: $bestNetwork (reason=$reason)")
                        boxService?.resetNetwork()
                    } catch (e: Exception) {
                        Log.w(TAG, "Post-TUN rebind failed ($reason): ${e.message}")
                    }
                    return@rebind
                }
            }
            Log.w(TAG, "Post-TUN rebind failed after retries ($reason)")
        }
    }
}
