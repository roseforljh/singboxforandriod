package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kunk.singbox.MainActivity
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.repository.SettingsRepository
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.NetworkInterface

class SingBoxService : VpnService() {
    
    companion object {
        private const val TAG = "SingBoxService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "singbox_vpn"
        
        const val ACTION_START = "com.kunk.singbox.START"
        const val ACTION_STOP = "com.kunk.singbox.STOP"
        const val ACTION_SWITCH_NODE = "com.kunk.singbox.SWITCH_NODE"
        const val EXTRA_CONFIG_PATH = "config_path"
        
        // Clash API 配置
        const val CLASH_API_PORT = 9090
        const val CLASH_API_SECRET = ""
        
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isStarting = false
            private set
        
        @Volatile
        var clashApiPort = CLASH_API_PORT
            private set

        private var lastConfigPath: String? = null
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var boxService: BoxService? = null
    private var currentSettings: AppSettings? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Network monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentInterfaceListener: InterfaceUpdateListener? = null
    private var defaultInterfaceName: String = ""
    
    // Platform interface implementation
    private val platformInterface = object : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            val result = protect(fd)
            Log.d(TAG, "autoDetectInterfaceControl: $fd, protect result: $result")
        }
        
        override fun openTun(options: TunOptions?): Int {
            Log.d(TAG, "openTun called")
            if (options == null) return -1
            
            val settings = currentSettings
            val builder = Builder()
                .setSession("SingBox VPN")
                .setMtu(if (options.mtu > 0) options.mtu else (settings?.tunMtu ?: 1500))
            
            // 添加地址
            builder.addAddress("172.19.0.1", 30)
            
            // 添加路由 - 全局代理
            builder.addRoute("0.0.0.0", 0)
            
            // 添加 DNS (优先使用设置中的 DNS)
            if (settings != null) {
                builder.addDnsServer(settings.remoteDns)
                builder.addDnsServer(settings.localDns)
            } else {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
            }
            
            // 排除自己
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exclude self from VPN")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            // 设置底层网络 - 关键！让 VPN 流量可以通过物理网络出去
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val activeNetwork = connectivityManager?.activeNetwork
                if (activeNetwork != null) {
                    builder.setUnderlyingNetworks(arrayOf(activeNetwork))
                    Log.d(TAG, "Set underlying network: $activeNetwork")
                }
            }
            
            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: -1
            Log.d(TAG, "TUN interface established with fd: $fd")
            return fd
        }
        
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        
        override fun useProcFS(): Boolean = false
        
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): Int = 0
        
        override fun packageNameByUid(uid: Int): String = ""
        
        override fun uidByPackageName(packageName: String?): Int = 0
        
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            Log.d(TAG, "startDefaultInterfaceMonitor")
            currentInterfaceListener = listener
            
            connectivityManager = getSystemService(ConnectivityManager::class.java)
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateDefaultInterface(network)
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost")
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
            Log.d(TAG, "closeDefaultInterfaceMonitor")
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
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
            Log.d(TAG, "libbox: $message")
            message?.let { 
                com.kunk.singbox.repository.LogRepository.getInstance().addLog(it)
            }
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
            
            // Auto Reconnect 逻辑：当网络可用且开启了自动重连，如果当前未运行但有上次的配置，则尝试重连
            serviceScope.launch {
                val settings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                if (settings.autoReconnect && !isRunning && lastConfigPath != null) {
                    Log.d(TAG, "Auto-reconnecting on network available: $interfaceName")
                    startVpn(lastConfigPath!!)
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
                Log.d(TAG, "Default interface updated: $interfaceName (index: $index, expensive: $isExpensive)")
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
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    startVpn(configPath)
                }
            }
            ACTION_STOP -> {
                stopVpn()
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
            if (isRunning || isStarting) {
                Log.w(TAG, "VPN already running or starting")
                return
            }
            isStarting = true
        }
        
        lastConfigPath = configPath
        startForeground(NOTIFICATION_ID, createNotification())
        
        serviceScope.launch {
            try {
                // 加载最新设置
                currentSettings = SettingsRepository.getInstance(this@SingBoxService).settings.first()
                Log.d(TAG, "Settings loaded: tunEnabled=${currentSettings?.tunEnabled}")

                // 读取配置文件
                val configFile = File(configPath)
                if (!configFile.exists()) {
                    Log.e(TAG, "Config file not found: $configPath")
                    withContext(Dispatchers.Main) { stopSelf() }
                    return@launch
                }
                val configContent = configFile.readText()
                Log.d(TAG, "Config loaded, length: ${configContent.length}")
                
                // 初始化 libbox
                val workDir = File(filesDir, "singbox_work")
                val tempDir = File(cacheDir, "singbox_temp")
                workDir.mkdirs()
                tempDir.mkdirs()
                
                val setupOptions = SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = workDir.absolutePath
                    this.tempPath = tempDir.absolutePath
                }
                
                try {
                    Libbox.setup(setupOptions)
                    Log.d(TAG, "Libbox setup completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Libbox setup warning: ${e.message}")
                }
                
                // 创建并启动 BoxService
                boxService = Libbox.newService(configContent, platformInterface)
                boxService?.start()
                Log.d(TAG, "BoxService started")
                
                isRunning = true
                Log.i(TAG, "SingBox VPN started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                withContext(Dispatchers.Main) { stopVpn() }
            } finally {
                isStarting = false
            }
        }
    }
    
    private fun stopVpn() {
        try {
            boxService?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BoxService", e)
        }
        boxService = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        isRunning = false
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.i(TAG, "VPN stopped")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SingBox VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN 服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
        }.build()
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        stopVpn()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
