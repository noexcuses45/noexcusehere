package com.noexcusehere.mashwave.automash

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** Decodes any song the phone can play into the mixer's working format (44.1 kHz stereo, 16-bit). */
object PcmDecoder {
    /**
     * Decodes [uri] from the very beginning (never seeking, so positions are exact for every
     * file type) into [out] as raw audio, and lets [collector] listen along.
     * Returns the number of frames written.
     */
    fun decode(
        context: Context,
        uri: Uri,
        out: File,
        collector: FeatureCollector,
        maxSeconds: Int,
        keepGoing: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val stream = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        var written = 0L
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio in this file")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio format")
            extractor.selectTrack(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val limitUs = min(if (durationUs > 0) durationUs else Long.MAX_VALUE, maxSeconds * 1_000_000L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            val maxFrames = maxSeconds.toLong() * MIX_RATE
            val bytes = ByteBuffer.allocate(8192 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val converter = ToMixFormat { stereo, frames ->
                val take = min(frames.toLong(), maxFrames - written).toInt()
                if (take > 0) {
                    collector.add(stereo, take)
                    bytes.clear()
                    bytes.asShortBuffer().put(stereo, 0, take * 2)
                    stream.write(bytes.array(), 0, take * 4)
                    written += take
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var left = FloatArray(4096)
            var right = FloatArray(4096)
            while (!outputDone && written < maxFrames) {
                if (!keepGoing()) throw InterruptedException()
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
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 && channels > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val frames: Int
                            if (isFloat) {
                                val floats = buffer.asFloatBuffer()
                                frames = floats.remaining() / channels
                                if (left.size < frames) {
                                    left = FloatArray(frames)
                                    right = FloatArray(frames)
                                }
                                for (i in 0 until frames) {
                                    val l = floats.get()
                                    val r = if (channels > 1) floats.get() else l
                                    for (extra in 2 until channels) floats.get()
                                    left[i] = l
                                    right[i] = r
                                }
                            } else {
                                val shorts = buffer.asShortBuffer()
                                frames = shorts.remaining() / channels
                                if (left.size < frames) {
                                    left = FloatArray(frames)
                                    right = FloatArray(frames)
                                }
                                for (i in 0 until frames) {
                                    val l = shorts.get()
                                    val r = if (channels > 1) shorts.get() else l
                                    for (extra in 2 until channels) shorts.get()
                                    left[i] = l / 32768f
                                    right[i] = r / 32768f
                                }
                            }
                            converter.feed(left, right, frames, rate)
                            if (limitUs in 1 until Long.MAX_VALUE) {
                                onProgress((info.presentationTimeUs.toFloat() / limitUs).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            converter.flush()
            return written
        } finally {
            runCatching { stream.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Brings decoded audio to 44.1 kHz stereo 16-bit, whatever rate it arrived at. */
    private class ToMixFormat(private val sink: (ShortArray, Int) -> Unit) {
        private val out = ShortArray(8192 * 2)
        private var filled = 0
        private var position = 0.0
        private var lastL = 0f
        private var lastR = 0f

        fun feed(left: FloatArray, right: FloatArray, frames: Int, rate: Int) {
            if (frames == 0) return
            if (rate == MIX_RATE) {
                for (i in 0 until frames) put(left[i], right[i])
            } else {
                // Straight-line interpolation between neighbouring samples.
                val step = rate.toDouble() / MIX_RATE
                while (position < frames - 1) {
                    val i = floor(position).toInt()
                    val t = (position - i).toFloat()
                    val l0 = if (i < 0) lastL else left[i]
                    val r0 = if (i < 0) lastR else right[i]
                    put(l0 + (left[i + 1] - l0) * t, r0 + (right[i + 1] - r0) * t)
                    position += step
                }
                position -= frames
            }
            lastL = left[frames - 1]
            lastR = right[frames - 1]
        }

        private fun put(l: Float, r: Float) {
            out[filled * 2] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[filled * 2 + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            if (++filled == out.size / 2) flush()
        }

        fun flush() {
            if (filled > 0) sink(out, filled)
            filled = 0
        }
    }
}

/** Saves the finished mix as an .m4a (AAC) file, the same format phones record and share in. */
class AacSink(file: File) : PcmSink {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var framesIn = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MIX_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(stereo: ShortArray, frames: Int) {
        var offset = 0
        val total = frames * 2
        var waits = 0
        while (offset < total) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: throw IOException("Encoder gave no buffer")
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val count = min((buffer.capacity() / 2) and 1.inv(), total - offset)
                buffer.asShortBuffer().put(stereo, offset, count)
                codec.queueInputBuffer(index, 0, count * 2, framesIn * 1_000_000L / MIX_RATE, 0)
                framesIn += count / 2
                offset += count
                waits = 0
            } else if (++waits > 500) {
                throw IOException("Encoder stopped accepting audio")
            }
            drain(false)
        }
    }

    private fun drain(untilEnd: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd || ++idle > 500) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    idle = 0
                    val data = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (data != null && info.size > 0 && started) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finishes the file. Call exactly once; [abandon] when the render was cancelled or failed. */
    fun close(abandon: Boolean = false) {
        try {
            if (!abandon) {
                var waits = 0
                while (waits < 500) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, framesIn * 1_000_000L / MIX_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                    drain(false)
                    waits++
                }
                drain(true)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
