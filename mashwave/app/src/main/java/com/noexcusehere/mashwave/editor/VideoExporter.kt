package com.noexcusehere.mashwave.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/** Renders a project into an MP4 file. Must be used from the main thread. */
class VideoExporter(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var ticker: Runnable? = null

    /**
     * Starts rendering [project] into [output]. [onProgress] gets 0..1 a few times a second;
     * [onDone] gets the result, or the error when something goes wrong.
     */
    fun start(
        project: EditProject,
        shortSide: Int,
        output: File,
        onProgress: (Float) -> Unit,
        onDone: (ExportResult?, Exception?) -> Unit,
    ) {
        cancel()
        val composition = CompositionFactory.build(context, project, shortSide, forExport = true)
        val engine = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setMaxDelayBetweenMuxerSamplesMs(MAX_STALL_MS)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopTicker()
                    transformer = null
                    onProgress(1f)
                    onDone(exportResult, null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopTicker()
                    transformer = null
                    onDone(null, exportException)
                }
            })
            .build()
        transformer = engine
        try {
            engine.start(composition, output.absolutePath)
        } catch (e: Exception) {
            transformer = null
            onDone(null, e)
            return
        }
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                handler.postDelayed(this, 300)
            }
        }
        ticker = tick
        handler.postDelayed(tick, 300)
    }

    private fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    companion object {
        /** How long the engine may go without producing output before giving up. */
        const val MAX_STALL_MS = 60_000L
    }

    fun cancel() {
        stopTicker()
        runCatching { transformer?.cancel() }
        transformer = null
    }
}
