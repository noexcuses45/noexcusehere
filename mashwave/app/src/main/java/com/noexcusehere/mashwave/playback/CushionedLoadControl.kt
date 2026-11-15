package com.noexcusehere.mashwave.playback

import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import com.noexcusehere.mashwave.iptv.LiveCushion

/**
 * The player's usual rules for when there is enough in hand to start, except that a live TV
 * channel waits for the reserve the user chose. Songs, videos and movies are left exactly as
 * they were. [cushion] returns null whenever what is playing is not a live channel.
 */
class CushionedLoadControl(private val cushion: () -> LiveCushion?) : DefaultLoadControl() {
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val ready = super.shouldStartPlayback(parameters)
        val chosen = cushion() ?: return ready
        val bufferedMs = Util.usToMs(Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed))
        val liveWindowMs = if (parameters.targetLiveOffsetUs == C.TIME_UNSET) -1L else Util.usToMs(parameters.targetLiveOffsetUs)
        return chosen.mayStart(ready, bufferedMs, parameters.rebuffering, liveWindowMs)
    }
}
