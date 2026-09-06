package app.shackcq.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

private const val FLEX_MIC_RATE = 24_000
private const val FLEX_MIC_FRAME_SAMPLES = 240
private const val FLEX_MIC_CHANNELS = 2

internal fun buildFlexOpusTxPacket(streamId: Long, sequence: Int, opus: ByteArray): ByteArray? {
    if (streamId == 0L || opus.isEmpty() || opus.size > 1_400) return null
    val packetBytes = 28 + opus.size
    val sizeWords = (packetBytes + 3) / 4
    val header = (3 shl 28) or (1 shl 27) or (3 shl 22) or (1 shl 20) or
        ((sequence and 0xf) shl 16) or sizeWords
    return ByteBuffer.allocate(packetBytes).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(header)
        putInt(streamId.toInt())
        putInt(FLEX_VITA_OUI)
        putInt((0x534C shl 16) or FLEX_OPUS_CLASS)
        putInt(0)
        putInt(0)
        putInt(0)
        put(opus)
    }.array()
}

class FlexMicTxEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendDatagram: (ByteArray) -> Boolean,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var worker: Job? = null
    private val active = AtomicBoolean(false)
    private var sequence = 0
    private var timestampUs = 0L
    private val queue = ArrayDeque<ByteArray>()
    var droppedPackets = 0L
        private set
    var error: String? = null
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(streamId: Long): Boolean {
        if (active.get() || streamId == 0L) return false
        val minimum = AudioRecord.getMinBufferSize(FLEX_MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val resources = runCatching {
            val codec = createEncoder()
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                FLEX_MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimum.coerceAtLeast(FLEX_MIC_FRAME_SAMPLES * 8),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("24 kHz microphone capture is unavailable")
            codec to record
        }.onFailure { error = it.message ?: "Flex microphone path could not start" }.getOrNull() ?: return false
        encoder = resources.first
        recorder = resources.second
        sequence = 0
        timestampUs = 0
        queue.clear()
        droppedPackets = 0
        active.set(true)
        resources.second.startRecording()
        worker = scope.launch(Dispatchers.IO) { encodeLoop(streamId, resources.second, resources.first) }
        return true
    }

    @Synchronized
    fun startVoiceMacro(streamId: Long, pcm: CanonicalVoicePcm): Boolean {
        if (active.get() || streamId == 0L || pcm.samples.isEmpty()) return false
        val codec = runCatching { createEncoder() }
            .onFailure { error = it.message ?: "Flex voice-macro encoder could not start" }
            .getOrNull() ?: return false
        encoder = codec
        sequence = 0
        timestampUs = 0
        queue.clear()
        droppedPackets = 0
        active.set(true)
        worker = scope.launch(Dispatchers.IO) {
            try {
                val lead = ShortArray(VOICE_SAMPLE_RATE / 10)
                encodeVoiceSamples(streamId, codec, lead + pcm.samples + lead)
            } catch (failure: Throwable) {
                if (active.get()) {
                    error = failure.message ?: "Flex voice macro stopped"
                    onFailure(error!!)
                }
            } finally {
                active.set(false)
            }
        }
        return true
    }

    private fun createEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, FLEX_MIC_RATE, FLEX_MIC_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLEX_MIC_FRAME_SAMPLES * FLEX_MIC_CHANNELS * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    }

    private suspend fun encodeVoiceSamples(streamId: Long, codec: MediaCodec, input48k: ShortArray) {
        var offset = 0
        while (active.get() && offset < input48k.size) {
            val mono24 = ShortArray(FLEX_MIC_FRAME_SAMPLES) { index ->
                val first = input48k.getOrElse(offset + index * 2) { 0 }.toInt()
                val second = input48k.getOrElse(offset + index * 2 + 1) { first.toShort() }.toInt()
                ((first + second) / 2).toShort()
            }
            queueInput(codec, mono24)
            drain(streamId, codec)
            flushQueue()
            offset += FLEX_MIC_FRAME_SAMPLES * 2
            delay(10)
        }
        repeat(4) {
            delay(5)
            drain(streamId, codec)
            flushQueue()
        }
    }

    private fun encodeLoop(streamId: Long, record: AudioRecord, codec: MediaCodec) {
        val mono = ShortArray(FLEX_MIC_FRAME_SAMPLES)
        try {
            while (active.get()) {
                var received = 0
                while (received < mono.size && active.get()) {
                    val count = record.read(mono, received, mono.size - received, AudioRecord.READ_BLOCKING)
                    if (count <= 0) throw IllegalStateException("Microphone read failed: $count")
                    received += count
                }
                val inputIndex = codec.dequeueInputBuffer(20_000)
                if (inputIndex >= 0) queueInput(codec, mono, inputIndex)
                drain(streamId, codec)
                flushQueue()
            }
        } catch (failure: Throwable) {
            if (active.get()) {
                error = failure.message ?: "Flex microphone TX stopped"
                onFailure(error!!)
            }
        } finally {
            active.set(false)
        }
    }

    private fun queueInput(codec: MediaCodec, mono: ShortArray, suppliedIndex: Int? = null) {
        val inputIndex = suppliedIndex ?: codec.dequeueInputBuffer(20_000)
        if (inputIndex < 0) return
        codec.getInputBuffer(inputIndex)?.apply {
            clear()
            order(ByteOrder.LITTLE_ENDIAN)
            mono.forEach { sample -> putShort(sample); putShort(sample) }
        }
        codec.queueInputBuffer(inputIndex, 0, mono.size * FLEX_MIC_CHANNELS * 2, timestampUs, 0)
        timestampUs += 10_000
    }

    private fun drain(streamId: Long, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        repeat(4) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val output = codec.getOutputBuffer(index) ?: return@repeat
                output.position(info.offset)
                output.limit(info.offset + info.size)
                val opus = ByteArray(info.size)
                output.get(opus)
                buildFlexOpusTxPacket(streamId, sequence++, opus)?.let { packet ->
                    if (queue.size >= 20) {
                        queue.removeFirst()
                        droppedPackets++
                    }
                    queue.addLast(packet)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun flushQueue() {
        while (queue.isNotEmpty()) {
            if (!sendDatagram(queue.removeFirst())) {
                queue.clear()
                throw IllegalStateException("Flex microphone UDP send failed")
            }
        }
    }

    val running get() = active.get()

    @Synchronized
    override fun close() {
        active.set(false)
        worker?.cancel()
        worker = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        queue.clear()
    }
}
