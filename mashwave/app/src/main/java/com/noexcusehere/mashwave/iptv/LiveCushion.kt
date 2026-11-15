package com.noexcusehere.mashwave.iptv

import kotlin.math.min

/**
 * How much of a live channel is held in reserve before the picture starts, and before it
 * restarts after an interruption. A live stream arrives exactly as fast as it plays, so whatever
 * was in hand at the start is all the slack there is: with two seconds in reserve, a three-second
 * hiccup on the line stops the picture; with ten, nobody notices. The price is that a channel
 * takes a little longer to appear and runs that much behind the broadcast.
 */
enum class LiveCushion(val label: String, val note: String, val startMs: Int, val afterStallMs: Int) {
    QUICK("Quick", "Channels start fastest, but any hiccup on the line shows straight away.", 2_500, 5_000),
    SMOOTHER("Smoother", "Keeps about 5 seconds in reserve. A good balance for most connections.", 5_000, 10_000),
    SMOOTHEST("Smoothest", "Keeps about 10 seconds in reserve. Channels take longer to start; best when a channel keeps stopping.", 10_000, 15_000);

    /**
     * Whether a live channel may start showing now.
     *
     * [readyAnyway] is the player's own verdict, which this can only make stricter. [bufferedMs]
     * is how much is in hand, [afterStall] whether the picture had stopped to wait, and
     * [liveWindowMs] how far behind the broadcast this kind of stream plays (-1 when it has no
     * such limit): there is never more than that to collect, so half of it has to do.
     */
    fun mayStart(readyAnyway: Boolean, bufferedMs: Long, afterStall: Boolean, liveWindowMs: Long = -1): Boolean {
        if (!readyAnyway) return false
        val usual = if (afterStall) QUICK.afterStallMs else QUICK.startMs
        // The player says "ready" with less than it normally asks for: memory is full or the
        // stream's own window is short. Waiting for more would be waiting for ever.
        if (bufferedMs < usual) return true
        var wanted = (if (afterStall) afterStallMs else startMs).toLong()
        if (liveWindowMs >= 0) wanted = min(wanted, liveWindowMs / 2)
        return bufferedMs >= wanted
    }

    companion object {
        fun of(name: String): LiveCushion = entries.firstOrNull { it.name == name } ?: SMOOTHER
    }
}
