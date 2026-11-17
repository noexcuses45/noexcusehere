package com.noexcusehere.mashwave.ui

/** Rules for the small floating video window (Android's "picture in picture"). */
object FloatingWindow {
    /** Android refuses windows more stretched than about 2.39 to 1, either way. */
    private const val WIDEST = 2.39f

    /**
     * The shape of the window for a video [width] by [height]: the video's own shape, pulled
     * back to the most stretched shape Android accepts when the video is wider or taller than
     * that. Returns width and height parts of the ratio, or null when the size is not known yet.
     */
    fun shape(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height
        return when {
            ratio > WIDEST -> 239 to 100
            ratio < 1f / WIDEST -> 100 to 239
            else -> width to height
        }
    }
}
