package com.noexcusehere.screenrecorder

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to start (opens the app for the capture prompt) or stop a recording. */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenRecordService.isActive) {
            startService(ScreenRecordService.stopIntent(this))
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
        } else {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (ScreenRecordService.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }
}
