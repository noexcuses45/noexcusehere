package com.noexcusehere.mashwave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app running while it saves a video or sends media to a TV, so the work carries on
 * when the screen is off or another app is in front. Shows what is going on in the notification.
 */
class KeepAliveService : Service() {
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_EXPORT -> app.exporter.cancel()
            ACTION_STOP_CAST -> app.cast.disconnect()
        }
        // Always enter the foreground first: Android requires it after startForegroundService().
        show(snapshot())
        if (watcher == null) {
            watcher = app.scope.launch {
                combine(app.exporter.state, app.cast.state) { export, cast ->
                    Status(
                        exporting = export.running,
                        exportName = export.name,
                        exportPercent = (export.progress * 100).toInt(),
                        castingTo = cast.device?.name,
                        castTitle = cast.entry?.title,
                    )
                }.distinctUntilChanged().collect { status ->
                    if (!status.exporting && status.castingTo == null) {
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        show(status)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private data class Status(
        val exporting: Boolean,
        val exportName: String,
        val exportPercent: Int,
        val castingTo: String?,
        val castTitle: String?,
    )

    private fun snapshot(): Status {
        val export = app.exporter.state.value
        val cast = app.cast.state.value
        return Status(export.running, export.name, (export.progress * 100).toInt(), cast.device?.name, cast.entry?.title)
    }

    private fun show(status: Status) {
        holdLocks(status)
        val notification = build(status)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = 0
                if (status.exporting) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (status.castingTo != null || type == 0) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w("UltimateBeats", "Could not show the progress notification", it) }
    }

    private fun holdLocks(status: Status) {
        if (status.exporting && wakeLock == null) {
            wakeLock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultimatebeats:export")
                    .apply { acquire(2 * 60 * 60 * 1000L) }
            }.getOrNull()
        } else if (!status.exporting) {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
        }
        if (status.castingTo != null && wifiLock == null) {
            // The TV pulls the file from this phone over Wi-Fi, so Wi-Fi must not doze off.
            wifiLock = runCatching {
                @Suppress("DEPRECATION")
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ultimatebeats:cast")
                    .apply { acquire() }
            }.getOrNull()
        } else if (status.castingTo == null) {
            wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
            wifiLock = null
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    private fun build(status: Status): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Saving videos and casting", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a video is being saved or something is playing on a TV"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).setAction(if (status.exporting) MainActivity.ACTION_SHOW_EDITOR else MainActivity.ACTION_SHOW_CAST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mashwave)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_mashwave)
        if (status.exporting) {
            builder.setContentTitle("Saving “${status.exportName}”")
                .setContentText("${status.exportPercent}% done")
                .setProgress(100, status.exportPercent, false)
                .addAction(Notification.Action.Builder(icon, "Cancel", action(ACTION_CANCEL_EXPORT, 11)).build())
        } else {
            builder.setContentTitle("Playing on ${status.castingTo}")
                .setContentText(status.castTitle ?: "Connected")
                .addAction(Notification.Action.Builder(icon, "Stop casting", action(ACTION_STOP_CAST, 12)).build())
        }
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, KeepAliveService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android limits how long this kind of background work may run; stop cleanly when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL_EXPORT = "com.noexcusehere.mashwave.work.CANCEL_EXPORT"
        const val ACTION_STOP_CAST = "com.noexcusehere.mashwave.work.STOP_CAST"
        private const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 4301

        /** Call whenever saving or casting starts; the service stops by itself when both are over. */
        fun refresh(context: Context) {
            val app = context.app
            val needed = app.exporter.state.value.running || app.cast.state.value.device != null
            if (!needed) return
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                // Saving and casting always begin while the app is on screen, where a plain start
                // is allowed. Unlike startForegroundService() it carries no "show a notification
                // within five seconds or be killed" deadline, which a busy phone could miss.
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // The app is in the background after all: the stricter call is the only one allowed.
                runCatching { context.startForegroundService(intent) }
                    .onFailure { Log.w("UltimateBeats", "Could not start the keep-alive service", it) }
            } catch (e: Exception) {
                Log.w("UltimateBeats", "Could not start the keep-alive service", e)
            }
        }
    }
}
