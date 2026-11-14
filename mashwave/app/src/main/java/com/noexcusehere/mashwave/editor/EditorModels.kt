package com.noexcusehere.mashwave.editor

import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.serialization.Serializable

/** One piece of video (or a photo) on the timeline, with everything that was done to it. */
@Serializable
data class EditClip(
    val id: String,
    val uri: String,
    val title: String = "",
    val isImage: Boolean = false,
    /** Full length of the original video; unused for photos. */
    val sourceDurationMs: Long = 0,
    /** The part of the original that is kept. For photos startMs is 0 and endMs is how long it shows. */
    val startMs: Long = 0,
    val endMs: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    /** Quarter turns clockwise: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val filter: String = "NONE",
    /** Fine tuning, each from -1 (less) to +1 (more). */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** How long this clip lasts in the finished video. */
    val durationMs: Long
        get() = ((endMs - startMs) / speed.coerceIn(0.1f, 10f)).toLong().coerceAtLeast(1)

    /** Width divided by height as it appears on screen (after turning). */
    val aspect: Float
        get() {
            if (width <= 0 || height <= 0) return 16f / 9f
            return if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
        }
}

/** Background music laid under the whole video. */
@Serializable
data class EditMusic(
    val entry: MediaEntry,
    /** Where in the song to start. */
    val offsetMs: Long = 0,
    val volume: Float = 0.8f,
)

/** A caption or emoji shown over the picture for part of the video. */
@Serializable
data class EditText(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Centre of the text: -1 is the left / bottom edge, +1 the right / top edge. */
    val x: Float = 0f,
    val y: Float = -0.72f,
    /** Text height as a fraction of the picture height. */
    val size: Float = 0.075f,
    val color: Long = 0xFFFFFFFF,
)

@Serializable
data class EditProject(
    val id: String,
    val name: String = "My video",
    val clips: List<EditClip> = emptyList(),
    val music: EditMusic? = null,
    val texts: List<EditText> = emptyList(),
    /** Shape of the finished video: ORIGINAL, 16:9, 9:16, 1:1 or 4:5. */
    val ratio: String = "ORIGINAL",
    /** What fills the gaps when a clip is a different shape: BLACK or BLUR. */
    val background: String = "BLACK",
    val updatedAt: Long = 0,
) {
    val durationMs: Long
        get() = clips.sumOf { it.durationMs }

    /** Where each clip starts in the finished video. */
    fun clipStarts(): List<Long> {
        var at = 0L
        return clips.map { clip -> at.also { at += clip.durationMs } }
    }

    /** Index of the clip playing at [timeMs], and how far into that clip (finished-video time). */
    fun clipAt(timeMs: Long): Pair<Int, Long>? {
        var at = 0L
        clips.forEachIndexed { index, clip ->
            if (timeMs < at + clip.durationMs || index == clips.lastIndex) return index to (timeMs - at).coerceIn(0, clip.durationMs)
            at += clip.durationMs
        }
        return null
    }

    /** Width divided by height of the finished video. */
    val aspect: Float
        get() = when (ratio) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "1:1" -> 1f
            "4:5" -> 4f / 5f
            else -> clips.firstOrNull()?.aspect ?: (16f / 9f)
        }
}

/** Colour looks that can be put on a clip. */
enum class ClipFilter(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    FADE("Fade"),
    MONO("B&W"),
    NOIR("Noir"),
    SEPIA("Sepia"),
}
