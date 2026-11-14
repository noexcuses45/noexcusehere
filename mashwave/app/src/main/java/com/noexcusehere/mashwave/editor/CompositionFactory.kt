package com.noexcusehere.mashwave.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.noexcusehere.mashwave.R
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns an [EditProject] into the description the video engine understands. The same
 * description drives both the live preview and the final export, so what you see is what
 * gets saved.
 */
object CompositionFactory {

    /** Pixel size of the finished video when its shorter side is [shortSide]. Always even numbers. */
    fun outputSize(project: EditProject, shortSide: Int): Size {
        val aspect = project.aspect.coerceIn(0.3f, 3.4f)
        val w: Int
        val h: Int
        if (aspect >= 1f) {
            h = shortSide
            w = (shortSide * aspect).roundToInt()
        } else {
            w = shortSide
            h = (shortSide / aspect).roundToInt()
        }
        return Size(w - w % 2, h - h % 2)
    }

    /**
     * [forExport] picks how speed changes are expressed: the live player and the file writer
     * each need their own form (the writer's built-in one misplaces every clip after the first).
     */
    fun build(context: Context, project: EditProject, shortSide: Int, forExport: Boolean): Composition {
        val size = outputSize(project, shortSide)
        val canvasAspect = size.width.toFloat() / size.height

        val items = project.clips.map { clip -> clipItem(clip, size, canvasAspect, project.background == "BLUR", forExport) }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items)

        project.music?.let { music -> musicSequence(music, project.durationMs, forExport)?.let { sequences += it } }

        val builder = Composition.Builder(sequences)
            // Phone cameras often record HDR. Bringing everything to normal colour lets HDR and
            // ordinary clips sit in one video, and keeps text and filters looking the same on both.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (project.texts.isNotEmpty()) {
            val typeface = captionTypeface(context)
            val overlays: List<TextureOverlay> = project.texts.map { TimedTextOverlay(it, typeface) }
            builder.setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays))))
        }
        return builder.build()
    }

    private var captionFace: Typeface? = null

    /** The app's own lettering in a bold weight, so captions read clearly over moving pictures. */
    private fun captionTypeface(context: Context): Typeface {
        captionFace?.let { return it }
        val face = runCatching {
            // The font file holds every weight; picking one needs a real file to load it from.
            val file = File(context.cacheDir, "outfit-caption.ttf")
            if (file.length() == 0L) {
                context.resources.openRawResource(R.font.outfit).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            Typeface.Builder(file).setFontVariationSettings("'wght' 700").build()
        }.getOrNull() ?: Typeface.DEFAULT_BOLD
        captionFace = face
        return face
    }

    /**
     * The background song as its own sound-only track, exactly as long as the video: it starts
     * at the chosen point, starts over from the beginning each time it runs out, and the last
     * round is cut where the video ends.
     */
    private fun musicSequence(music: EditMusic, videoMs: Long, forExport: Boolean): EditedMediaItemSequence? {
        if (videoMs <= 0) return null
        val uri = Uri.parse(music.entry.uri)
        val songMs = music.entry.durationMs
        fun piece(startMs: Long, endMs: Long?): EditedMediaItem {
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
            if (endMs != null) clipping.setEndPositionMs(endMs)
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping.build()).build())
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volume(music.volume)), emptyList()))
            // The live player has to be told how long every file is before it starts.
            if (!forExport) item.setDurationUs(songMs * 1000)
            return item.build()
        }
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        if (songMs < 1000) {
            // Length unknown: the saved video repeats the song for as long as there is picture;
            // the live preview cannot play such a file and stays without music.
            if (!forExport) return null
            return builder.addItem(piece(music.offsetMs.coerceAtLeast(0), null)).setIsLooping(true).build()
        }
        var from = music.offsetMs.coerceIn(0, songMs - 500)
        var left = videoMs
        var rounds = 0
        while (left > 0 && rounds < 500) {
            val take = minOf(songMs - from, left)
            // Leaving the end open on a full round avoids asking for more than the file holds.
            builder.addItem(piece(from, if (take < songMs - from) from + take else null))
            left -= take
            from = 0
            rounds++
        }
        return builder.build()
    }

    private fun clipItem(clip: EditClip, size: Size, canvasAspect: Float, blurBackground: Boolean, forExport: Boolean): EditedMediaItem {
        val video = ArrayList<Effect>()
        val audio = ArrayList<AudioProcessor>()
        val speed = clip.speed.coerceIn(0.1f, 10f)
        val changesSpeed = !clip.isImage && abs(speed - 1f) > 0.001f
        if (changesSpeed && forExport) {
            // Re-times the frames starting from the clip's own first frame, and stretches the
            // sound to match without changing its pitch.
            video += SpeedChangeEffect(speed)
            audio += SpeedChangingAudioProcessor(ConstantSpeed(speed))
        }
        if (clip.rotation % 360 != 0 || clip.mirrored) {
            video += ScaleAndRotateTransformation.Builder()
                // The engine turns anticlockwise for positive angles.
                .setRotationDegrees(-clip.rotation.toFloat())
                .setScale(if (clip.mirrored) -1f else 1f, 1f)
                .build()
        }
        video += colourEffects(clip)

        // Fit the clip into the frame of the finished video.
        val clipAspect = clip.aspect
        val sameShape = abs(clipAspect / canvasAspect - 1f) < 0.02f
        if (blurBackground && !sameShape) {
            // Stretch the picture over the whole frame and blur it, then lay the real picture,
            // squeezed back to its true shape, on top.
            video += Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_STRETCH_TO_FIT)
            val scaleX = if (clipAspect < canvasAspect) clipAspect / canvasAspect else 1f
            val scaleY = if (clipAspect > canvasAspect) canvasAspect / clipAspect else 1f
            video += GaussianBlurWithFrameOverlaid(size.height / 45f, scaleX, scaleY)
        } else {
            video += Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
        }

        val builder: EditedMediaItem.Builder
        if (clip.isImage) {
            val item = MediaItem.Builder().setUri(Uri.parse(clip.uri)).setImageDurationMs(clip.endMs).build()
            builder = EditedMediaItem.Builder(item)
                .setDurationUs(clip.endMs * 1000)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), video))
        } else {
            val item = MediaItem.Builder()
                .setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build(),
                )
                .build()
            audio += volume(clip.volume)
            builder = EditedMediaItem.Builder(item)
                .setEffects(Effects(audio, video))
                .setRemoveAudio(clip.volume <= 0.001f)
            if (!forExport) {
                // The live player has to be told how long every file is before it starts.
                builder.setDurationUs(maxOf(clip.sourceDurationMs, clip.endMs) * 1000)
                if (changesSpeed) builder.setSpeed(ConstantSpeed(speed))
            }
        }
        return builder.build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /** Turns sound up or down, and makes everything stereo so different clips can be mixed. */
    private fun volume(level: Float): AudioProcessor {
        val gain = level.coerceIn(0f, 2f)
        val processor = ChannelMixingAudioProcessor()
        for (channels in 1..8) {
            val matrix = runCatching { ChannelMixingMatrix.create(channels, 2) }.getOrNull() ?: run {
                // Unusual layouts: keep the front left and right speakers.
                val coefficients = FloatArray(channels * 2)
                coefficients[0] = 1f
                coefficients[channels + 1] = 1f
                ChannelMixingMatrix(channels, 2, coefficients)
            }
            processor.putChannelMixingMatrix(matrix.scaleBy(gain))
        }
        return processor
    }

    private fun colourEffects(clip: EditClip): List<Effect> {
        val out = ArrayList<Effect>()
        var brightness = clip.brightness * 0.35f
        var contrast = clip.contrast * 0.6f
        var saturation = clip.saturation * 70f
        when (runCatching { ClipFilter.valueOf(clip.filter) }.getOrDefault(ClipFilter.NONE)) {
            ClipFilter.NONE -> Unit
            ClipFilter.VIVID -> {
                saturation += 35f
                contrast += 0.12f
            }
            ClipFilter.WARM -> out += RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.02f).setBlueScale(0.86f).build()
            ClipFilter.COOL -> out += RgbAdjustment.Builder().setRedScale(0.88f).setGreenScale(1f).setBlueScale(1.14f).build()
            ClipFilter.FADE -> {
                saturation -= 25f
                contrast -= 0.2f
                brightness += 0.05f
            }
            ClipFilter.MONO -> out += RgbFilter.createGrayscaleFilter()
            ClipFilter.NOIR -> {
                out += RgbFilter.createGrayscaleFilter()
                contrast += 0.35f
            }
            ClipFilter.SEPIA -> out += Sepia
        }
        if (abs(saturation) > 0.5f) out += HslAdjustment.Builder().adjustSaturation(saturation.coerceIn(-100f, 100f)).build()
        if (abs(contrast) > 0.005f) out += Contrast(contrast.coerceIn(-0.95f, 0.95f))
        if (abs(brightness) > 0.005f) out += Brightness(brightness.coerceIn(-0.9f, 0.9f))
        return out
    }

    private object Sepia : RgbMatrix {
        // Column-major 4x4: each output colour is a mix of the input red, green and blue.
        private val matrix = floatArrayOf(
            0.393f, 0.349f, 0.272f, 0f,
            0.769f, 0.686f, 0.534f, 0f,
            0.189f, 0.168f, 0.131f, 0f,
            0f, 0f, 0f, 1f,
        )

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }
}

/** Draws one caption, sized relative to the picture, and only between its start and end times. */
class TimedTextOverlay(private val item: EditText, private val typeface: Typeface) : BitmapOverlay() {
    private var frame = Size(1280, 720)
    private var bitmap: Bitmap? = null

    private val visible: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(item.x.coerceIn(-1f, 1f), item.y.coerceIn(-1f, 1f))
        .build()
    private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize != frame) {
            frame = videoSize
            bitmap = null
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap ?: render().also { bitmap = it }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        return if (ms >= item.startMs && ms < item.endMs) visible else hidden
    }

    private fun render(): Bitmap {
        val textSize = (frame.height * item.size.coerceIn(0.02f, 0.4f)).coerceAtLeast(8f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@TimedTextOverlay.typeface
            this.textSize = textSize
            color = item.color.toInt()
            // A soft dark edge keeps the words readable on any picture.
            setShadowLayer(textSize * 0.12f, 0f, textSize * 0.04f, 0xCC000000.toInt())
        }
        val maxWidth = (frame.width * 0.92f).toInt().coerceAtLeast(16)
        val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
        var widest = 1f
        for (line in 0 until layout.lineCount) widest = maxOf(widest, layout.getLineWidth(line))
        val pad = (textSize * 0.3f).toInt()
        val width = (widest.toInt() + 2 * pad).coerceIn(2, frame.width)
        val height = (layout.height + 2 * pad).coerceAtLeast(2)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate((width - maxWidth) / 2f, pad.toFloat())
        layout.draw(canvas)
        return out
    }
}
