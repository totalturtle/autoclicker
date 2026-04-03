package com.autoclicker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 빠른 설정 패널 타일 → 시퀀스 시작/정지 토글 + 시각 상태 반영 */
class AutoClickTileService : TileService() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AutoClickAccessibilityService.ACTION_STARTED -> setTileActive(true)
                AutoClickAccessibilityService.ACTION_STOP    -> setTileActive(false)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val filter = IntentFilter().apply {
            addAction(AutoClickAccessibilityService.ACTION_STARTED)
            addAction(AutoClickAccessibilityService.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        setTileActive(AutoClickAccessibilityService.isRunning)
    }

    override fun onStopListening() {
        super.onStopListening()
        runCatching { unregisterReceiver(stateReceiver) }
    }

    override fun onClick() {
        sendBroadcast(
            Intent(AutoClickAccessibilityService.ACTION_TOGGLE).setPackage(packageName)
        )
    }

    private fun setTileActive(active: Boolean) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
