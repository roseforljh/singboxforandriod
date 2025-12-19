package com.kunk.singbox.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kunk.singbox.R
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null
    @Volatile private var lastServiceState: SingBoxService.ServiceState = SingBoxService.ServiceState.STOPPED
    private var boundService: SingBoxService? = null
    private var serviceBound = false
    private var tapPending = false
    private val stateCallback = object : SingBoxService.StateCallback {
        override fun onStateChanged(state: SingBoxService.ServiceState) {
            lastServiceState = state
            updateTile()
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SingBoxService.LocalBinder ?: return
            boundService = binder.getService()
            boundService?.registerStateCallback(stateCallback)
            serviceBound = true
            lastServiceState = boundService?.getCurrentState() ?: SingBoxService.ServiceState.STOPPED
            updateTile()
            if (tapPending) {
                tapPending = false
                toggle()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.unregisterStateCallback(stateCallback)
            boundService = null
            serviceBound = false
            lastServiceState = SingBoxService.ServiceState.STOPPED
            updateTile()
        }
    }

    companion object {
        private const val PREFS_NAME = "vpn_state"
        private const val KEY_VPN_ACTIVE = "vpn_active"
        
        /**
         * 持久化 VPN 状态到 SharedPreferences
         * 在 SingBoxService 启动/停止时调用
         */
        fun persistVpnState(context: Context, isActive: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VPN_ACTIVE, isActive)
                .commit()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        bindService()
        updateTile()
        
        // 订阅VPN状态变化，确保磁贴状态与实际VPN状态同步
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            while (true) {
                updateTile()
                delay(1000)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        // 停止监听时取消订阅
        stateObserverJob?.cancel()
        stateObserverJob = null
        unbindService()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
            return
        }
        toggle()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (lastServiceState) {
            SingBoxService.ServiceState.STARTING,
            SingBoxService.ServiceState.RUNNING -> {
                tile.state = Tile.STATE_ACTIVE
            }
            SingBoxService.ServiceState.STOPPING -> {
                tile.state = Tile.STATE_UNAVAILABLE
            }
            SingBoxService.ServiceState.STOPPED -> {
                tile.state = Tile.STATE_INACTIVE
            }
        }
        tile.label = getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (_: Exception) {
        }
        tile.updateTile()
    }

    private fun toggle() {
        if (!serviceBound || boundService == null) {
            tapPending = true
            bindService()
            return
        }
        lastServiceState = boundService?.getCurrentState() ?: lastServiceState
        when (lastServiceState) {
            SingBoxService.ServiceState.RUNNING,
            SingBoxService.ServiceState.STARTING -> {
                persistVpnState(this, false)
                val intent = Intent(this, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                }
                startService(intent)
            }
            SingBoxService.ServiceState.STOPPED -> {
                persistVpnState(this, true)
                serviceScope.launch {
                    val configRepository = ConfigRepository.getInstance(applicationContext)
                    val configPath = configRepository.generateConfigFile()
                    if (configPath != null) {
                        val intent = Intent(this@VpnTileService, SingBoxService::class.java).apply {
                            action = SingBoxService.ACTION_START
                            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configPath)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        persistVpnState(this@VpnTileService, false)
                        updateTile()
                    }
                }
            }
            SingBoxService.ServiceState.STOPPING -> {
                updateTile()
            }
        }
    }

    private fun bindService() {
        if (serviceBound) return
        val intent = Intent(this, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_SERVICE
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindService() {
        if (!serviceBound) return
        boundService?.unregisterStateCallback(stateCallback)
        runCatching { unbindService(serviceConnection) }
        boundService = null
        serviceBound = false
    }

    override fun onDestroy() {
        unbindService()
        serviceScope.cancel()
        super.onDestroy()
    }
}
