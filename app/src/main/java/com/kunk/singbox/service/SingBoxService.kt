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
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.kunk.singbox.MainActivity
import com.kunk.singbox.utils.SecurityUtils
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
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
        const val EXTRA_CONFIG_PATH = "config_path"
        
        const val CLASH_API_PORT = 9090

        fun getClashApiSecret(): String = SecurityUtils.getClashApiSecret()
        
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

        @Volatile
        var isStarting = false
            private set(value) {
                field = value
                _isStartingFlow.value = value
            }

        @Volatile
        var isManuallyStopped = false
            private set
        
        @Volatile
        var clashApiPort = CLASH_API_PORT
            private set

        private var lastConfigPath: String? = null

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

    @Volatile private var lastRuleSetCheckMs: Long = 0L
    private val ruleSetCheckIntervalMs: Long = 6 * 60 * 60 * 1000L

    @Volatile private var autoReconnectEnabled: Boolean = false
    @Volatile private var lastAutoReconnectAttemptMs: Long = 0L
    private var autoReconnectJob: Job? = null
    private val autoReconnectDebounceMs: Long = 3000L
    
    // Network monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName: String = ""
    
    // Platform interface implementation
    private val platformInterface = object : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            val result = protect(fd)
            // Log.v(TAG, "autoDetectInterfaceControl: $fd, protect result: $result")
        }
        
        override fun openTun(options: TunOptions?): Int {
            Log.v(TAG, "openTun called")
            if (options == null) return -1
            
            val settings = currentSettings
            val builder = Builder()
                .setSession("SingBox VPN")
                .setMtu(if (options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500))
            
            // 添加地址
            builder.addAddress("172.19.0.1", 30)
            
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
            }
            
            // 添加 DNS (优先使用设置中的 DNS)
            if (settings != null) {
                builder.addDnsServer(settings.remoteDns)
                builder.addDnsServer(settings.localDns)
            } else {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
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
            }
            
            // 设置底层网络 - 关键！让 VPN 流量可以通过物理网络出去
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val activeNetwork = connectivityManager?.activeNetwork
                if (activeNetwork != null) {
                    builder.setUnderlyingNetworks(arrayOf(activeNetwork))
                    Log.v(TAG, "Set underlying network: $activeNetwork")
                }
            }
            
            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: -1
            Log.i(TAG, "TUN interface established with fd: $fd")
            return fd
        }
        
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun useProcFS(): Boolean {
            val procPaths = listOf(
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6"
            )

            val readable = procPaths.any { path ->
                try {
                    val file = File(path)
                    file.exists() && file.canRead()
                } catch (_: Exception) {
                    false
                }
            }

            if (!readable) {
                connectionOwnerLastEvent = "procfs_unreadable -> force findConnectionOwner"
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

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent = "api<29"
                return 0
            }

            fun parseAddress(value: String?): InetAddress? {
                if (value.isNullOrBlank()) return null
                val cleaned = value.substringBefore("%")
                return try {
                    InetAddress.getByName(cleaned)
                } catch (_: Exception) {
                    null
                }
            }

            val sourceIp = parseAddress(sourceAddress)
            val destinationIp = parseAddress(destinationAddress)
            if (sourceIp == null || destinationIp == null || sourcePort <= 0 || destinationPort <= 0) {
                connectionOwnerInvalidArgs.incrementAndGet()
                connectionOwnerLastEvent =
                    "invalid_args src=$sourceAddress:$sourcePort dst=$destinationAddress:$destinationPort proto=$ipProtocol"
                return 0
            }

            val protocol = when (ipProtocol) {
                OsConstants.IPPROTO_TCP -> OsConstants.IPPROTO_TCP
                OsConstants.IPPROTO_UDP -> OsConstants.IPPROTO_UDP
                else -> ipProtocol
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
                if (!pkgs.isNullOrEmpty()) pkgs[0] else ""
            } catch (_: Exception) {
                ""
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
                    updateDefaultInterface(network)
                }
                
                override fun onLost(network: Network) {
                    Log.i(TAG, "Network lost")
                    currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
                }
                
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    updateDefaultInterface(network)
                }
            }
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            
            // Get current default interface
            connectivityManager?.activeNetwork?.let { updateDefaultInterface(it) }
        }
        
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.v(TAG, "closeDefaultInterfaceMonitor")
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (e: IllegalArgumentException) {
                    Log.v(TAG, "NetworkCallback already unregistered")
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering NetworkCallback", e)
                }
            }
            networkCallback = null
            currentInterfaceListener = null
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
                            
                            // Determine type based on name (heuristics)
                            type = when {
                                iface.name.startsWith("wlan") -> 0 // WIFI
                                iface.name.startsWith("rmnet") || iface.name.startsWith("ccmni") -> 1 // Cellular
                                iface.name.startsWith("eth") -> 2 // Ethernet
                                else -> 3 // Other
                            }
                            
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
        
        override fun localDNSTransport(): LocalDNSTransport? = null
        
        override fun systemCertificates(): StringIterator? = null
        
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
    
    private fun updateDefaultInterface(network: Network) {
        try {
            val linkProperties = connectivityManager?.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName ?: ""

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
                val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
                val isConstrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) != true
                Log.i(TAG, "Default interface updated: $interfaceName (index: $index, expensive: $isExpensive)")
                currentInterfaceListener?.updateDefaultInterface(interfaceName, index, isExpensive, isConstrained)
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
        when (intent?.action) {
            ACTION_START -> {
                isManuallyStopped = false
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    synchronized(this) {
                        if (isStopping) {
                            pendingStartConfigPath = configPath
                            stopSelfRequested = false
                            lastConfigPath = configPath
                            return START_STICKY
                        }
                    }
                    startVpn(configPath)
                }
            }
            ACTION_STOP -> {
                isManuallyStopped = true
                stopVpn(stopService = true)
            }
            ACTION_SWITCH_NODE -> {
                switchNextNode()
            }
        }
        return START_STICKY
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
                Log.w(TAG, "VPN is stopping, ignore start request")
                return
            }
            isStarting = true
        }
        
        lastConfigPath = configPath
        Log.d(TAG, "Attempting to start foreground service with ID: $NOTIFICATION_ID")
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call startForeground", e)
        }
        
        serviceScope.launch {
            try {
                // 1. 确保规则集就绪（预下载）
                // 即使下载失败也继续启动，使用旧缓存或空文件，避免阻塞启动
                try {
                    val now = System.currentTimeMillis()
                    val shouldCheck = now - lastRuleSetCheckMs >= ruleSetCheckIntervalMs
                    if (shouldCheck) {
                        lastRuleSetCheckMs = now
                        val ruleSetRepo = RuleSetRepository.getInstance(this@SingBoxService)
                        val allReady = ruleSetRepo.ensureRuleSetsReady(allowNetwork = false) { progress ->
                            Log.v(TAG, "Rule set update: $progress")
                        }
                        if (!allReady) {
                            Log.w(TAG, "Some rule sets are not ready, proceeding with available cache")
                        }
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
                Log.i(TAG, "SingBox VPN started successfully")
                updateTileState()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isRunning = false
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
            }
        }
    }
    
    private fun stopVpn(stopService: Boolean) {
        synchronized(this) {
            stopSelfRequested = stopSelfRequested || stopService
            if (isStopping) {
                return
            }
            isStopping = true
        }

        autoReconnectJob?.cancel()
        autoReconnectJob = null

        isRunning = false

        val listener = currentInterfaceListener
        val serviceToClose = boxService
        boxService = null

        val interfaceToClose = vpnInterface
        vpnInterface = null

        cleanupScope.launch {
            try {
                platformInterface.closeDefaultInterfaceMonitor(listener)
            } catch (_: Exception) {
            }

            try {
                serviceToClose?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing BoxService", e)
            }

            try {
                interfaceToClose?.close()
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
                withContext(Dispatchers.Main) {
                    startVpn(startAfterStop)
                }
            }
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
        val activeNodeName = configRepository.nodes.value.find { it.id == activeNodeId }?.name ?: "已连接"

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
        stopVpn(stopService = false)
        serviceSupervisorJob.cancel()
        cleanupSupervisorJob.cancel()
        super.onDestroy()
    }
     
    override fun onRevoke() {
        stopVpn(stopService = true)
        super.onRevoke()
    }
}
