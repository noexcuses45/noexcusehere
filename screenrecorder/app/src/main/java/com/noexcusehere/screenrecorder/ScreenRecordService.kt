package com.noexcusehere.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the MediaProjection + MediaRecorder for one recording.
 * Output goes to MediaStore (Movies/NoExcuseRecorder) so it shows up in the gallery.
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        const val ACTION_START = "com.noexcusehere.screenrecorder.START"
        const val ACTION_STOP = "com.noexcusehere.screenrecorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WITH_AUDIO = "with_audio"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_COUNTDOWN_SECONDS = "countdown"
        const val RELATIVE_DIR = "Movies/NoExcuseRecorder"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        enum class State { IDLE, COUNTDOWN, RECORDING }

        private val _state = MutableLiveData(State.IDLE)
        val state: LiveData<State> get() = _state
        val isActive: Boolean get() = _state.value != State.IDLE

        @Volatile var startedAtMillis: Long = 0L
            private set

        fun stopIntent(context: Context) =
            Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var countdownRunnable: Runnable? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the status bar / system dialog, or the captured app closed.
            finishRecording(showSavedToast = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isActive) startRequested(intent)
            ACTION_STOP -> finishRecording(showSavedToast = true)
            else -> if (!isActive) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRequested(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf(); return
        }
        val withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)
        val quality = runCatching { Quality.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: "") }.getOrDefault(Quality.HIGH)
        val countdown = intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 0).coerceIn(0, 10)

        createChannel()
        // Android 14+ requires the foreground service (with the mediaProjection type) to be
        // running BEFORE getMediaProjection() is called.
        goForeground(buildNotification(if (countdown > 0) getString(R.string.notification_countdown, countdown) else null), withAudio)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e); null
        }
        if (mp == null) {
            fail(getString(R.string.permission_denied)); return
        }
        mp.registerCallback(projectionCallback, handler)
        projection = mp

        if (countdown > 0) {
            _state.value = State.COUNTDOWN
            runCountdown(countdown) { beginRecording(withAudio, quality) }
        } else {
            beginRecording(withAudio, quality)
        }
    }

    private fun runCountdown(seconds: Int, onDone: () -> Unit) {
        var remaining = seconds
        val r = object : Runnable {
            override fun run() {
                if (projection == null) return // stopped during countdown
                if (remaining <= 0) {
                    countdownRunnable = null
                    onDone(); return
                }
                Toast.makeText(this@ScreenRecordService, getString(R.string.status_countdown, remaining), Toast.LENGTH_SHORT).show()
                notify(buildNotification(getString(R.string.notification_countdown, remaining)))
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        countdownRunnable = r
        handler.post(r)
    }

    private fun beginRecording(withAudio: Boolean, quality: Quality) {
        val mp = projection ?: return
        try {
            val (width, height, dpi) = captureSize(quality)
            val uri = createOutputUri() ?: throw IllegalStateException("Could not create output file")
            outputUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Could not open output file")
            outputPfd = pfd

            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd.fileDescriptor)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(quality.bitRate)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                }
                prepare()
            }
            recorder = rec

            virtualDisplay = mp.createVirtualDisplay(
                "NoExcuseRecorder", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, handler
            )
            rec.start()
            startedAtMillis = System.currentTimeMillis()
            _state.value = State.RECORDING
            notify(buildNotification(null))
            Log.i(TAG, "Recording ${width}x${height} @ ${quality.bitRate} bps, audio=$withAudio -> $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            fail(getString(R.string.failed, e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    /** Returns (width, height, dpi) for the capture, scaled down for lower quality settings and made even. */
    private fun captureSize(quality: Quality): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        var w: Int; var h: Int; val dpi: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            w = bounds.width(); h = bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            w = p.x; h = p.y; dpi = metrics.densityDpi
        }
        if (quality.shortSide > 0) {
            val short = minOf(w, h)
            if (short > quality.shortSide) {
                val scale = quality.shortSide.toFloat() / short
                w = (w * scale).toInt(); h = (h * scale).toInt()
            }
        }
        w -= w % 2; h -= h % 2
        return Triple(w, h, dpi)
    }

    private fun createOutputUri(): Uri? {
        val name = "NoExcuse_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return contentResolver.insert(collection, values)
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finishRecording(showSavedToast = false)
    }

    private fun finishRecording(showSavedToast: Boolean) {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null

        val wasRecording = _state.value == State.RECORDING
        var saved = false
        recorder?.let { rec ->
            try {
                if (wasRecording) { rec.stop(); saved = true }
            } catch (e: RuntimeException) {
                Log.w(TAG, "stop() failed, recording probably too short", e)
            }
            runCatching { rec.reset() }
            runCatching { rec.release() }
        }
        recorder = null
        virtualDisplay?.release(); virtualDisplay = null
        projection?.let { mp ->
            runCatching { mp.unregisterCallback(projectionCallback) }
            runCatching { mp.stop() }
        }
        projection = null
        runCatching { outputPfd?.close() }; outputPfd = null

        outputUri?.let { uri ->
            if (saved) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                runCatching { contentResolver.update(uri, values, null, null) }
                if (showSavedToast) Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
        outputUri = null
        startedAtMillis = 0L
        _state.value = State.IDLE

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isActive) finishRecording(showSavedToast = false)
        super.onDestroy()
    }

    // ---- notification helpers ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun goForeground(notification: Notification, withAudio: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withAudio && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text ?: getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
