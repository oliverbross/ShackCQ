package app.shackcq.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.pow

private const val FLEX_AUDIO_RATE = 24_000
private const val FLEX_AUDIO_CHANNELS = 2
private const val FLEX_OPUS_FRAME_US = 10_000L

internal class FlexAudioJitterBuffer<T>(private val maxPackets: Int = 12) {
    private val pending = TreeMap<Long, T>()
    private var expected: Long? = null
    var dropped = 0L
        private set
    var concealed = 0L
        private set

    @Synchronized
    fun offer(sequence: Long, value: T) {
        if (pending.putIfAbsent(sequence, value) != null) return
        while (pending.size > maxPackets) {
            pending.pollFirstEntry()
            dropped++
        }
        if (expected == null) expected = sequence
    }

    @Synchronized
    fun poll(): T? {
        val next = expected ?: return null
        pending.remove(next)?.let {
            expected = next + 1
            return it
        }
        if (pending.size >= 3 && pending.firstKey() > next) {
            expected = next + 1
            concealed++
        }
        return null
    }

    @Synchronized
    fun clear() {
        pending.clear()
        expected = null
        dropped = 0
        concealed = 0
    }
}

class FlexAudioEngine : AutoCloseable {
    private var track: AudioTrack? = null
    private var opus: MediaCodec? = null
    private var opusTimestampUs = 0L
    private var extendedSequence = -1L
    private var lastNibble = -1
    private val opusJitter = FlexAudioJitterBuffer<ByteArray>()
    private var lastPcm = FloatArray(0)
    var pcmObserver: ((samples: FloatArray, sampleRate: Int, channels: Int) -> Unit)? = null
    var gainDb: Float = 0f
    var muted: Boolean = false
    var running: Boolean = false
        private set
    var decoderFault: String? = null
        private set

    @Synchronized
    fun start() {
        if (running) return
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(FLEX_AUDIO_RATE, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
            .coerceAtLeast(FLEX_AUDIO_RATE / 5 * FLEX_AUDIO_CHANNELS * 4)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(FLEX_AUDIO_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(minimum)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        running = true
    }

    @Synchronized
    fun accept(event: FlexVitaEvent) {
        if (!running) return
        when (event) {
            is FlexVitaEvent.FloatAudio -> writeFloat(normalizeChannels(event.samples, event.channels))
            is FlexVitaEvent.OpusAudio -> {
                opusJitter.offer(extend(event.sequence), event.payload)
                repeat(4) {
                    val frame = opusJitter.poll() ?: return@repeat
                    decodeOpus(frame)
                }
                drainOpus()
            }
            else -> Unit
        }
    }

    private fun extend(nibble: Int): Long {
        if (extendedSequence < 0) {
            lastNibble = nibble
            extendedSequence = nibble.toLong()
            return extendedSequence
        }
        val distance = (nibble - lastNibble) and 0xf
        if (distance in 1..8) extendedSequence += distance
        lastNibble = nibble
        return extendedSequence
    }

    private fun normalizeChannels(samples: FloatArray, channels: Int): FloatArray {
        if (channels == FLEX_AUDIO_CHANNELS) return samples
        if (channels != 1) return FloatArray(0)
        return FloatArray(samples.size * 2) { samples[it / 2] }
    }

    private fun ensureOpus(): MediaCodec? {
        opus?.let { return it }
        return runCatching {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, FLEX_AUDIO_RATE, FLEX_AUDIO_CHANNELS)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).flip() as ByteBuffer)
            format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).flip() as ByteBuffer)
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        }.onFailure { decoderFault = it.message ?: "Android Opus decoder unavailable" }.getOrNull()?.also { opus = it }
    }

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII))
        put(1)
        put(FLEX_AUDIO_CHANNELS.toByte())
        putShort(0)
        putInt(FLEX_AUDIO_RATE)
        putShort(0)
        put(0)
    }.array()

    private fun decodeOpus(frame: ByteArray) {
        val codec = ensureOpus() ?: return
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        codec.getInputBuffer(index)?.apply {
            clear()
            if (remaining() < frame.size) return
            put(frame)
        }
        codec.queueInputBuffer(index, 0, frame.size, opusTimestampUs, 0)
        opusTimestampUs += FLEX_OPUS_FRAME_US
    }

    private fun drainOpus() {
        val codec = opus ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) break
            codec.getOutputBuffer(index)?.let { output ->
                output.position(info.offset)
                output.limit(info.offset + info.size)
                val shorts = output.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(shorts.remaining()) { shorts.get() / 32768f }
                writeFloat(samples)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun writeFloat(samples: FloatArray) {
        if (samples.isEmpty()) return
        pcmObserver?.invoke(samples, FLEX_AUDIO_RATE, FLEX_AUDIO_CHANNELS)
        val scalar = if (muted) 0f else 10f.pow(gainDb.coerceIn(-60f, 12f) / 20f)
        val rendered = FloatArray(samples.size) { (samples[it] * scalar).coerceIn(-1f, 1f) }
        track?.write(rendered, 0, rendered.size, AudioTrack.WRITE_NON_BLOCKING)
        lastPcm = rendered
    }

    @Synchronized
    fun concealOneFrame() {
        if (!running) return
        val source = lastPcm.takeIf { it.isNotEmpty() } ?: FloatArray(240 * FLEX_AUDIO_CHANNELS)
        val concealed = FloatArray(source.size) { index -> source[index] * (1f - index.toFloat() / source.size) }
        writeFloat(concealed)
    }

    @Synchronized
    override fun close() {
        running = false
        opusJitter.clear()
        runCatching { opus?.stop() }
        runCatching { opus?.release() }
        opus = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        pcmObserver = null
        lastPcm = FloatArray(0)
        extendedSequence = -1
        lastNibble = -1
        opusTimestampUs = 0
    }
}
