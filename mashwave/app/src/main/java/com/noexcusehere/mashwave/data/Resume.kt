package com.noexcusehere.mashwave.data

import kotlinx.serialization.Serializable

/** Where a video was stopped, so it can carry on from there next time. */
@Serializable
data class ResumePoint(
    val positionMs: Long,
    val durationMs: Long = 0,
    /** When it was saved; the oldest are forgotten first. */
    val at: Long = 0,
)

/** The rules for remembering a video's place. Plain functions so they can be unit-tested. */
object ResumeRules {
    /** Clips shorter than this simply start from the beginning. */
    const val MIN_LENGTH_MS = 60_000L

    /** A video that was only just started is not worth resuming. */
    const val MIN_WATCHED_MS = 10_000L

    /** How many videos' places are remembered at most. */
    const val MAX_KEPT = 300

    /** False when the video was barely started, or is as good as finished (credits, last seconds). */
    fun worthKeeping(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs < MIN_LENGTH_MS || positionMs < MIN_WATCHED_MS) return false
        val tail = maxOf(15_000L, durationMs / 30)
        return positionMs < durationMs - tail
    }

    /** True in the closing seconds of a video, when it counts as watched. */
    fun nearEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_LENGTH_MS && positionMs >= durationMs - maxOf(15_000L, durationMs / 30)

    /** Start a couple of seconds early, so the viewer gets their bearings. */
    fun startFrom(point: ResumePoint?): Long = if (point == null) 0 else (point.positionMs - 2000).coerceAtLeast(0)

    /** The map after saving [uri]'s place - or forgetting it, when there is nothing worth keeping. */
    fun updated(
        all: Map<String, ResumePoint>,
        uri: String,
        positionMs: Long,
        durationMs: Long,
        now: Long,
    ): Map<String, ResumePoint> {
        if (!worthKeeping(positionMs, durationMs)) return if (uri in all) all - uri else all
        val next = all + (uri to ResumePoint(positionMs, durationMs, now))
        if (next.size <= MAX_KEPT) return next
        val oldest = next.entries.sortedBy { it.value.at }.take(next.size - MAX_KEPT).map { it.key }.toSet()
        return next - oldest
    }

    /** How far through the video the saved place is, 0..1 (0 when there is none). */
    fun fraction(point: ResumePoint?): Float =
        if (point == null || point.durationMs <= 0) 0f else (point.positionMs.toFloat() / point.durationMs).coerceIn(0f, 1f)
}
