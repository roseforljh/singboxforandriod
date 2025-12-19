package com.kunk.singbox.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build
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
                .apply()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
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
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile

        val persistedState = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_ACTIVE, false)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasSystemVpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } else {
            true
        }

        // If system has no VPN but persisted says active, this is almost certainly a stale tile after force-stop.
        // First click should only clear persisted state + refresh the tile, never start VPN.
        if (!hasSystemVpn && persistedState && !SingBoxService.isRunning) {
            persistVpnState(this, false)
            updateTile()
            return
        }

        // If another VPN is active, don't attempt to start ours from the tile.
        if (hasSystemVpn && !persistedState && !SingBoxService.isRunning) {
            updateTile()
            return
        }

        val displayedActive = tile?.state == Tile.STATE_ACTIVE
        val isRunning = isOurVpnActive()

        // If QS tile UI is stale after force-stop, avoid toggling the VPN unexpectedly.
        // 1) Tile shows ACTIVE but our VPN is not active: first click should only refresh to INACTIVE.
        // 2) Tile shows INACTIVE but our VPN is active: first click should only refresh to ACTIVE.
        if (displayedActive && !isRunning) {
            persistVpnState(this, false)
            updateTile()
            return
        }
        if (!displayedActive && isRunning) {
            updateTile()
            return
        }
        
        // Update tile state immediately for responsive feel
        if (tile != null) {
            tile.state = if (isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }

        if (isRunning) {
            val intent = Intent(this, SingBoxService::class.java).apply {
                action = SingBoxService.ACTION_STOP
            }
            startService(intent)
        } else {
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
                    // Revert tile state if start fails
                    updateTile()
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = isOurVpnActive()
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile)
        } catch (e: Exception) {
            // Fallback to manifest icon if something goes wrong
        }
        tile.updateTile()
    }

    /**
     * 检测我们的 VPN 是否活跃
     *
     * 使用三重验证：
     * 1. 检查系统是否有活跃的 VPN 网络
     * 2. 检查进程内的 SingBoxService.isRunning 状态
     * 3. 检查持久化的 VPN 状态（用于进程重启后的状态恢复）
     *
     * 只有当系统确实有 VPN 网络时，才可能返回 true
     */
    private fun isOurVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 首先检查系统是否有任何 VPN 网络
        val hasSystemVpn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } else {
            // Android M 以下，无法可靠检测，回退到进程内状态
            true
        }

        // 如果系统没有 VPN 网络，一定不活跃
        if (!hasSystemVpn) {
            return false
        }

        // 系统有 VPN 网络，检查是否是我们的
        // 优先使用进程内状态（最准确）
        if (SingBoxService.isRunning) {
            return true
        }

        // 进程内状态为 false，但系统有 VPN 网络
        // 这可能是其他 VPN 应用，或者我们的 VPN 刚刚关闭但网络还没断开
        // 此时以系统网络状态为准，但需要持久化状态辅助判断
        val persistedState = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_ACTIVE, false)
        
        // 如果持久化状态也是 false，说明不是我们的 VPN
        if (!persistedState) {
            return false
        }

        // Persisted says active and system has VPN, but our in-process flag is false.
        // This can happen due to process restart or stale prefs; we conservatively treat as active
        // for tile display, but clicks will still self-correct via onClick stale handling.
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
