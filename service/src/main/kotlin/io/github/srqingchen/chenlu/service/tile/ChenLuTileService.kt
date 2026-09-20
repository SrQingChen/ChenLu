package io.github.srqingchen.chenlu.service.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.srqingchen.chenlu.service.AutomationController
import io.github.srqingchen.chenlu.service.AutomationService

/** 快捷设置磁贴：一键启停连点任务（三路控制入口之一：岛 / 通知 / 磁贴）。 */
class ChenLuTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val willRun = !AutomationController.state.value.running
        AutomationService.toggle(applicationContext)
        qsTile?.let { tile ->
            tile.state = if (willRun) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    private fun syncTileState() {
        qsTile?.let { tile ->
            tile.state = if (AutomationController.state.value.running) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }
}
