package com.kunk.singbox.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.kunk.singbox.R
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var lastServiceState: SingBoxService.ServiceState = SingBoxService.ServiceState.STOPPED
    private var boundService: SingBoxService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var tapPending = false
    private val stateCallback = object : SingBoxService.StateCallback {
        override fun onStateChanged(state: SingBoxService.ServiceState) {
            lastServiceState = state
            updateTile()
        }
    }

    companion object {
        private const val PREFS_NAME = "vpn_state"
        private const val KEY_VPN_ACTIVE = "vpn_active"
        private const val KEY_VPN_PENDING = "vpn_pending"
        
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

        fun persistVpnPending(context: Context, pending: String?) {
            val value = pending.orEmpty()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VPN_PENDING, value)
                .commit()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        bindService()
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
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
        val persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        if (!serviceBound || boundService == null) {
            lastServiceState = when (pending) {
                "starting" -> SingBoxService.ServiceState.STARTING
                "stopping" -> SingBoxService.ServiceState.STOPPING
                else -> if (persistedActive) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
            }
        }

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
        val activeLabel = if (lastServiceState == SingBoxService.ServiceState.RUNNING ||
            lastServiceState == SingBoxService.ServiceState.STARTING
        ) {
            runCatching {
                val repo = ConfigRepository.getInstance(applicationContext)
                val nodeId = repo.activeNodeId.value
                if (!nodeId.isNullOrBlank()) repo.getNodeById(nodeId)?.name else null
            }.getOrNull()
        } else {
            null
        }

        tile.label = activeLabel ?: getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (_: Exception) {
        }
        tile.updateTile()
    }

    private fun toggle() {
        val persistedActive = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        val effectiveState = if (serviceBound && boundService != null) {
            boundService?.getCurrentState() ?: lastServiceState
        } else {
            when (pending) {
                "starting" -> SingBoxService.ServiceState.STARTING
                "stopping" -> SingBoxService.ServiceState.STOPPING
                else -> if (persistedActive) SingBoxService.ServiceState.RUNNING else SingBoxService.ServiceState.STOPPED
            }
        }

        lastServiceState = effectiveState

        when (effectiveState) {
            SingBoxService.ServiceState.RUNNING,
            SingBoxService.ServiceState.STARTING -> {
                if (!serviceBound || boundService == null) {
                    tapPending = true
                    bindService(force = true)
                    updateTile()
                    return
                }
                persistVpnPending(this, "stopping")
                persistVpnState(this, false)
                updateTile()
                val intent = Intent(this, SingBoxService::class.java).apply {
                    action = SingBoxService.ACTION_STOP
                }
                startService(intent)
            }
            SingBoxService.ServiceState.STOPPED -> {
                persistVpnPending(this, "starting")
                updateTile()
                serviceScope.launch {
                    runCatching {
                        Toast.makeText(this@VpnTileService, "正在切换 VPN...", Toast.LENGTH_SHORT).show()
                    }
                    val configRepository = ConfigRepository.getInstance(applicationContext)
                    val configPath = configRepository.generateConfigFile()
                    if (configPath != null) {
                        persistVpnPending(this@VpnTileService, "starting")
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
                        persistVpnPending(this@VpnTileService, "")
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

    private fun bindService(force: Boolean = false) {
        if (serviceBound || bindRequested) return
        val shouldBind = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VPN_ACTIVE, false)
        }.getOrDefault(false)

        val pending = runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VPN_PENDING, "")
        }.getOrNull().orEmpty()

        val shouldTryBind = force || shouldBind || pending == "starting" || pending == "stopping"
        if (!shouldTryBind) return
        val intent = Intent(this, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_SERVICE
        }
        val ok = runCatching {
            bindService(intent, serviceConnection, 0)
        }.getOrDefault(false)
        bindRequested = ok
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SingBoxService.LocalBinder ?: return
            boundService = binder.getService()
            boundService?.registerStateCallback(stateCallback)
            serviceBound = true
            bindRequested = true
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
            bindRequested = false
            lastServiceState = SingBoxService.ServiceState.STOPPED
            updateTile()
        }
    }

    private fun unbindService() {
        if (!bindRequested) return
        if (serviceBound) {
            boundService?.unregisterStateCallback(stateCallback)
        }
        runCatching { unbindService(serviceConnection) }
        boundService = null
        serviceBound = false
        bindRequested = false
    }

    override fun onDestroy() {
        unbindService()
        serviceScope.cancel()
        super.onDestroy()
    }
}
