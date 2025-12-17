package com.kunk.singbox.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = SingBoxService.isRunning
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
                    startForegroundService(intent)
                }
            }
        }
        // Small delay to let service update state
        serviceScope.launch {
            kotlinx.coroutines.delay(1000)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = SingBoxService.isRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "SingBox"
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
