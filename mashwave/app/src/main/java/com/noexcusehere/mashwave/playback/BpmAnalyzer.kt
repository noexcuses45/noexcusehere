package com.noexcusehere.mashwave.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out a song's tempo (beats per minute) so two decks can be matched.
 *
 * It decodes about a minute of the track, turns it into a "how loud / how punchy" curve,
 * then looks for the beat spacing at which that curve repeats most strongly.
 */
object BpmAnalyzer {
    /** Envelope samples per second. 5 ms steps are fine enough to place beats precisely. */
    const val ENVELOPE_RATE = BpmMaths.ENVELOPE_RATE
    private const val ANALYSE_SECONDS = 60
    private const val TAG = "Mashwave"

    /** Stop after this long if we already have a usable amount of audio ... */
    private const val SOFT_TIME_LIMIT_MS = 20_000L

    /** ... and never spend longer than this, even on a very slow phone. */
    private const val HARD_TIME_LIMIT_MS = 90_000L
    private const val USEFUL_SECONDS = 15

    fun analyze(context: Context, uri: String): Float? {
        if (!uri.startsWith("content://") && !uri.startsWith("file://") && !uri.startsWith("/")) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            // Skip the intro: the middle of a song has the steadiest beat.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 100_000_000L) {
                extractor.seekTo(min(30_000_000L, durationUs / 4), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            var envelope = Envelope(sampleRate)
            val wanted = (ANALYSE_SECONDS * ENVELOPE_RATE).toInt()
            val info = MediaCodec.BufferInfo()
            val started = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false

            while (!outputDone && envelope.size < wanted) {
                if (Thread.interrupted()) return null
                val elapsed = System.currentTimeMillis() - started
                val useful = envelope.size >= USEFUL_SECONDS * envelope.rate
                if (elapsed > HARD_TIME_LIMIT_MS || (useful && elapsed > SOFT_TIME_LIMIT_MS)) break
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (newRate != sampleRate && envelope.size == 0) {
                            sampleRate = newRate
                            envelope = Envelope(sampleRate)
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 && channels > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            if (isFloat) {
                                val floats = buffer.asFloatBuffer()
                                val frames = floats.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += floats.get()
                                    envelope.add(sum / channels)
                                }
                            } else {
                                val shorts = buffer.asShortBuffer()
                                val frames = shorts.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0
                                    for (ch in 0 until channels) sum += shorts.get()
                                    envelope.add(sum / (channels * 32768f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val bpm = estimate(envelope.full(), envelope.low(), envelope.rate)
            Log.d(TAG, "Tempo of $uri: $bpm (from ${(envelope.size / envelope.rate).toInt()} s of audio)")
            return bpm
        } catch (e: Exception) {
            Log.w(TAG, "Could not analyse $uri", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Collects two energy curves from raw audio: the whole signal, and just the bass (kick drum).
     * Values are mean power per 5 ms step.
     */
    class Envelope(sampleRate: Int) {
        private val hop = max(1, (sampleRate / ENVELOPE_RATE).toInt())

        /** The real number of envelope values per second (the hop is a whole number of samples). */
        val rate: Float = sampleRate.toFloat() / hop
        private val alpha = (2 * Math.PI * 150.0 / sampleRate).toFloat().coerceAtMost(1f)
        private var lowPass = 0f
        private var sumFull = 0f
        private var sumLow = 0f
        private var count = 0
        private var fullValues = FloatArray(4096)
        private var lowValues = FloatArray(4096)
        var size = 0
            private set

        fun add(sample: Float) {
            lowPass += alpha * (sample - lowPass)
            sumFull += sample * sample
            sumLow += lowPass * lowPass
            if (++count == hop) {
                if (size == fullValues.size) {
                    fullValues = fullValues.copyOf(size * 2)
                    lowValues = lowValues.copyOf(size * 2)
                }
                fullValues[size] = sumFull / hop
                lowValues[size] = sumLow / hop
                size++
                count = 0
                sumFull = 0f
                sumLow = 0f
            }
        }

        fun full(): FloatArray = fullValues.copyOf(size)
        fun low(): FloatArray = lowValues.copyOf(size)
    }

    // The maths itself is shared with the computer app (see BpmMaths); these keep their old names.
    fun estimate(full: FloatArray, low: FloatArray, rate: Float = ENVELOPE_RATE): Float? = BpmMaths.estimate(full, low, rate)

    internal fun rise(power: FloatArray, rate: Float): FloatArray = BpmMaths.rise(power, rate)
}
