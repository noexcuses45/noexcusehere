package com.noexcusehere.mashwave.desktop.video

import com.noexcusehere.mashwave.editor.ClipFilter
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditProject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A caption drawn ready as a picture file, where its top left corner goes, and when it shows. */
class TextPicture(val file: String, val x: Int, val y: Int, val startMs: Long, val endMs: Long)

/** The words to start the video engine with, and how long the video they make is. */
class RenderJob(val args: List<String>, val totalMs: Long)

/** The sums behind a finished video that need no engine: its size, and what a clip's colour settings do to a dot. */
object VideoMaths {
    /** Width and height of the finished video when its shorter side is [shortSide]. Always even numbers. */
    fun outputSize(project: EditProject, shortSide: Int): Pair<Int, Int> {
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
        return (w - w % 2) to (h - h % 2)
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** [second] done after [first]; each is three rows of "red, green, blue, add". */
    private fun then(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..2) {
                out[row * 4 + col] = second[row * 4] * first[col] + second[row * 4 + 1] * first[4 + col] + second[row * 4 + 2] * first[8 + col]
            }
            out[row * 4 + 3] = second[row * 4] * first[3] + second[row * 4 + 1] * first[7] + second[row * 4 + 2] * first[11] + second[row * 4 + 3]
        }
        return out
    }

    private fun scale(r: Float, g: Float, b: Float) = floatArrayOf(r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, 0f)

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /** [amount] 0 is grey, 1 leaves the colours alone, more than 1 deepens them. */
    private fun saturation(amount: Float): FloatArray {
        val a = amount
        return floatArrayOf(
            LR + (1 - LR) * a, LG * (1 - a), LB * (1 - a), 0f,
            LR * (1 - a), LG + (1 - LG) * a, LB * (1 - a), 0f,
            LR * (1 - a), LG * (1 - a), LB + (1 - LB) * a, 0f,
        )
    }

    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f,
        0.349f, 0.686f, 0.168f, 0f,
        0.272f, 0.534f, 0.131f, 0f,
    )

    /**
     * What a clip's look and its brightness, contrast and colour settings do to a dot, as twelve
     * numbers: for each of red, green and blue, how much of the old red, green and blue goes in,
     * and how much is added (colours run from 0 to 1). The amounts follow the phone app's.
     */
    fun colour(clip: EditClip): FloatArray {
        var brightness = clip.brightness * 0.35f
        var contrast = clip.contrast * 0.6f
        var deeper = clip.saturation * 70f
        var m = IDENTITY
        when (runCatching { ClipFilter.valueOf(clip.filter) }.getOrDefault(ClipFilter.NONE)) {
            ClipFilter.NONE -> Unit
            ClipFilter.VIVID -> {
                deeper += 35f
                contrast += 0.12f
            }
            ClipFilter.WARM -> m = then(m, scale(1.12f, 1.02f, 0.86f))
            ClipFilter.COOL -> m = then(m, scale(0.88f, 1f, 1.14f))
            ClipFilter.FADE -> {
                deeper -= 25f
                contrast -= 0.2f
                brightness += 0.05f
            }
            ClipFilter.MONO -> m = then(m, saturation(0f))
            ClipFilter.NOIR -> {
                m = then(m, saturation(0f))
                contrast += 0.35f
            }
            ClipFilter.SEPIA -> m = then(m, SEPIA)
        }
        if (abs(deeper) > 0.5f) m = then(m, saturation(1f + deeper.coerceIn(-100f, 100f) / 100f))
        if (abs(contrast) > 0.005f) {
            val c = contrast.coerceIn(-0.95f, 0.95f)
            val factor = (1f + c) / (1.0001f - c)
            val add = 0.5f * (1f - factor)
            m = then(m, floatArrayOf(factor, 0f, 0f, add, 0f, factor, 0f, add, 0f, 0f, factor, add))
        }
        if (abs(brightness) > 0.005f) {
            val b = brightness.coerceIn(-0.9f, 0.9f)
            m = then(m, floatArrayOf(1f, 0f, 0f, b, 0f, 1f, 0f, b, 0f, 0f, 1f, b))
        }
        return m
    }

    fun changesNothing(m: FloatArray): Boolean = m.indices.all { abs(m[it] - IDENTITY[it]) < 0.0005f }
}

/**
 * Turns a video project into the instructions for the video engine (FFmpeg): which files to
 * read from where to where, what to do to each clip's picture and sound, how to join them, where
 * the captions and the music go, and how to pack the result.
 */
object RenderPlan {
    const val FPS = 30
    private const val RATE = 44100

    private fun n(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun n(value: Float): String = n(value.toDouble())
    private fun seconds(ms: Long): String = n(ms / 1000.0)

    private const val SOUND_FORM = "aresample=$RATE,aformat=sample_fmts=fltp:channel_layouts=stereo"

    /** Speeding sound up or down without changing its pitch; the engine does at most half speed in one go. */
    private fun tempo(speed: Float): String {
        var left = speed.coerceIn(0.1f, 10f).toDouble()
        val steps = ArrayList<String>()
        while (left < 0.5) {
            steps += "atempo=0.5"
            left /= 0.5
        }
        if (abs(left - 1.0) > 0.001) steps += "atempo=${n(left)}"
        return steps.joinToString(",")
    }

    /** Turning, mirroring and colouring one clip's picture: the same whether one frame or the whole clip is made. */
    private fun look(clip: EditClip): List<String> {
        val steps = ArrayList<String>()
        // Mirrored first, then turned, as on the phone.
        if (clip.mirrored) steps += "hflip"
        when (((clip.rotation % 360) + 360) % 360) {
            90 -> steps += "transpose=1"
            180 -> {
                steps += "hflip"
                steps += "vflip"
            }
            270 -> steps += "transpose=2"
        }
        val m = VideoMaths.colour(clip)
        steps += "format=rgba"
        if (!VideoMaths.changesNothing(m)) {
            fun c(i: Int) = n(m[i].coerceIn(-2f, 2f))
            // The fourth number of each row is added whatever the dot: the engine takes it from the
            // dot's "solidness", which is always 1 here.
            steps += "colorchannelmixer=rr=${c(0)}:rg=${c(1)}:rb=${c(2)}:ra=${c(3)}:gr=${c(4)}:gg=${c(5)}:gb=${c(6)}:ga=${c(7)}:br=${c(8)}:bg=${c(9)}:bb=${c(10)}:ba=${c(11)}"
        }
        return steps
    }

    /**
     * Fits a clip's picture [from] into the frame, [w] by [h]: bars of black around it, or - with
     * [blur] - a soft, stretched copy of itself behind it. Ends in the label [to].
     */
    private fun fit(from: String, to: String, tag: String, w: Int, h: Int, blur: Boolean, sameShape: Boolean): String {
        if (!blur || sameShape) {
            return "$from" + "scale=$w:$h:force_original_aspect_ratio=decrease:flags=bicubic,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1$to"
        }
        // The soft copy is made small first: far quicker, and a blur hides the difference.
        val smallW = (w / 4).coerceAtLeast(2) / 2 * 2
        val smallH = (h / 4).coerceAtLeast(2) / 2 * 2
        val soft = n((h / 45.0 / 4.0).coerceAtLeast(1.0))
        return "$from" + "split=2[fg$tag][bg$tag];" +
            "[bg$tag]scale=$smallW:$smallH,gblur=sigma=$soft,scale=$w:$h:flags=bilinear,setsar=1[bk$tag];" +
            "[fg$tag]scale=$w:$h:force_original_aspect_ratio=decrease:flags=bicubic,setsar=1[ft$tag];" +
            "[bk$tag][ft$tag]overlay=(W-w)/2:(H-h)/2,setsar=1$to"
    }

    private fun sameShape(clip: EditClip, w: Int, h: Int): Boolean = abs(clip.aspect / (w.toFloat() / h) - 1f) < 0.02f

    /** How the picture is packed, for the encoder called [encoder]. */
    fun videoPacking(encoder: String, w: Int, h: Int, draft: Boolean): List<String> {
        val bits = (w.toLong() * h * FPS * (if (draft) 0.06 else 0.13)).toLong().coerceAtLeast(400_000)
        return when (encoder) {
            "libx264" -> listOf("-c:v", "libx264", "-preset", if (draft) "ultrafast" else "veryfast", "-crf", if (draft) "30" else "20")
            "libopenh264" -> listOf("-c:v", "libopenh264", "-b:v", bits.toString())
            // A computer without a chip for this (some are, and every rented one is) may do it the slow way instead.
            "h264_videotoolbox" -> listOf("-c:v", "h264_videotoolbox", "-b:v", bits.toString(), "-allow_sw", "1")
            "h264_mf" -> listOf("-c:v", "h264_mf", "-b:v", bits.toString())
            else -> listOf("-c:v", "mpeg4", "-q:v", if (draft) "8" else "4")
        }
    }

    /**
     * The whole video, [w] by [h], written to [out]. [silent] holds the addresses of clips that
     * have no sound of their own; [musicMs] is the length of the song, when there is one.
     */
    fun whole(
        project: EditProject,
        w: Int,
        h: Int,
        texts: List<TextPicture>,
        encoder: String,
        draft: Boolean,
        out: String,
        silent: Set<String>,
        musicMs: Long = project.music?.entry?.durationMs ?: 0,
    ): RenderJob {
        require(project.clips.isNotEmpty()) { "a video needs at least one clip" }
        val args = ArrayList<String>()
        args += listOf("-hide_banner", "-nostdin", "-loglevel", "error", "-nostats", "-progress", "pipe:1")
        val graph = ArrayList<String>()
        val blur = project.background == "BLUR"
        val total = project.durationMs

        project.clips.forEachIndexed { i, clip ->
            val lasts = seconds(clip.durationMs)
            val speed = if (clip.isImage) 1f else clip.speed.coerceIn(0.1f, 10f)
            if (clip.isImage) {
                // A photo is read, turned, coloured and fitted once; that one finished frame is then held for as long as it shows.
                args += listOf("-i", clip.uri)
                graph += fit("[$i:v]" + look(clip).joinToString(",") + ",", "[fit$i]", i.toString(), w, h, blur, sameShape(clip, w, h))
                graph += "[fit$i]loop=loop=-1:size=1:start=0,setpts=N/($FPS*TB),fps=$FPS,trim=duration=$lasts,setpts=PTS-STARTPTS[v$i]"
            } else {
                args += listOf("-ss", seconds(clip.startMs), "-t", seconds(clip.endMs - clip.startMs), "-i", clip.uri)
                val steps = ArrayList<String>()
                steps += if (abs(speed - 1f) > 0.001f) "setpts=(PTS-STARTPTS)/${n(speed)}" else "setpts=PTS-STARTPTS"
                steps += "fps=$FPS"
                steps += look(clip)
                graph += fit("[$i:v]" + steps.joinToString(",") + ",", "[fit$i]", i.toString(), w, h, blur, sameShape(clip, w, h))
                graph += "[fit$i]trim=duration=$lasts,setpts=PTS-STARTPTS[v$i]"
            }

            val quiet = clip.isImage || clip.volume <= 0.001f || clip.uri in silent
            graph += if (quiet) {
                "anullsrc=channel_layout=stereo:sample_rate=$RATE,atrim=duration=$lasts,$SOUND_FORM[a$i]"
            } else {
                val sound = ArrayList<String>()
                sound += "asetpts=PTS-STARTPTS"
                tempo(speed).takeIf { it.isNotEmpty() }?.let { sound += it }
                sound += "volume=${n(clip.volume.coerceIn(0f, 2f))}"
                sound += SOUND_FORM
                // Sound shorter than the picture is filled out with silence, longer is cut: the two stay in step.
                sound += "apad"
                sound += "atrim=duration=$lasts"
                "[$i:a]" + sound.joinToString(",") + "[a$i]"
            }
        }
        val count = project.clips.size
        graph += project.clips.indices.joinToString("") { "[v$it][a$it]" } + "concat=n=$count:v=1:a=1[vjoin][ajoin]"

        // Captions: each a small picture laid on for its own stretch of time.
        var picture = "[vjoin]"
        var next = count
        texts.forEachIndexed { k, text ->
            args += listOf("-i", text.file)
            graph += "$picture[$next:v]overlay=x=${text.x}:y=${text.y}:enable='between(t,${seconds(text.startMs)},${seconds(text.endMs)})'[vt$k]"
            picture = "[vt$k]"
            next++
        }
        graph += "${picture}format=yuv420p[vout]"

        // The song runs under everything: from the chosen point, round again from its start while the video lasts.
        val music = project.music
        if (music != null && total > 0) {
            val from = if (musicMs >= 1000) music.offsetMs.coerceIn(0, musicMs - 500) else music.offsetMs.coerceAtLeast(0)
            args += listOf("-ss", seconds(from), "-i", music.entry.uri)
            args += listOf("-stream_loop", "-1", "-i", music.entry.uri)
            graph += "[$next:a]$SOUND_FORM[m1];[${next + 1}:a]$SOUND_FORM[m2]"
            graph += "[m1][m2]concat=n=2:v=0:a=1,volume=${n(music.volume.coerceIn(0f, 2f))},atrim=duration=${seconds(total)}[song]"
            graph += "[ajoin][song]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]"
        } else {
            graph += "[ajoin]anull[aout]"
        }

        args += listOf("-filter_complex", graph.joinToString(";"))
        args += listOf("-map", "[vout]", "-map", "[aout]", "-r", FPS.toString())
        args += videoPacking(encoder, w, h, draft)
        args += listOf("-c:a", "aac", "-b:a", if (draft) "128k" else "192k", "-ar", RATE.toString())
        args += listOf("-movflags", "+faststart", "-t", seconds(total), "-y", out)
        return RenderJob(args, total)
    }

    /**
     * One frame of the finished video as it looks at [timeMs], [w] by [h], sent out as a JPEG
     * picture. Only captions showing at that moment should be in [texts].
     */
    fun still(project: EditProject, timeMs: Long, w: Int, h: Int, texts: List<TextPicture>): List<String>? {
        val (index, into) = project.clipAt(timeMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))) ?: return null
        val clip = project.clips[index]
        val args = ArrayList<String>()
        args += listOf("-hide_banner", "-nostdin", "-loglevel", "error")
        if (clip.isImage) {
            args += listOf("-i", clip.uri)
        } else {
            val speed = clip.speed.coerceIn(0.1f, 10f)
            // Not so close to the end of the kept part that there is no frame left to show.
            val at = (clip.startMs + (into * speed).toLong()).coerceIn(clip.startMs, (clip.endMs - 60).coerceAtLeast(clip.startMs))
            args += listOf("-ss", seconds(at), "-i", clip.uri)
        }
        val graph = ArrayList<String>()
        graph += fit("[0:v]" + look(clip).joinToString(",") + ",", "[fit]", "0", w, h, project.background == "BLUR", sameShape(clip, w, h))
        var picture = "[fit]"
        texts.forEachIndexed { k, text ->
            args += listOf("-i", text.file)
            graph += "$picture[${k + 1}:v]overlay=x=${text.x}:y=${text.y}[vt$k]"
            picture = "[vt$k]"
        }
        graph += "${picture}format=yuvj420p[vout]"
        args += listOf("-filter_complex", graph.joinToString(";"), "-map", "[vout]", "-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "pipe:1")
        return args
    }
}
