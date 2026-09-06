package app.shackcq.mobile

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

internal fun digiPcm16Block(samples: FloatArray, count: Int = samples.size): ByteArray {
    require(count in 0..samples.size)
    val bytes = ByteArray(count * 2)
    repeat(count) { index ->
        val sample = samples[index].coerceIn(-1f, 1f)
        val pcm = if (sample <= -1f) Short.MIN_VALUE.toInt() else (sample * Short.MAX_VALUE).roundToInt()
        bytes[index * 2] = (pcm and 0xff).toByte()
        bytes[index * 2 + 1] = ((pcm ushr 8) and 0xff).toByte()
    }
    return bytes
}

internal class DigiRecordingQueue(capacity: Int) {
    private val blocks = ArrayBlockingQueue<ByteArray>(capacity)
    private val dropped = AtomicLong(0)
    val droppedFrames get() = dropped.get()

    fun offer(block: ByteArray, frames: Int): Boolean = blocks.offer(block).also { accepted ->
        if (!accepted) dropped.addAndGet(frames.toLong())
    }
    fun countDropped(frames: Int) { dropped.addAndGet(frames.toLong()) }
    fun take(): ByteArray = blocks.take()
    fun finish() = blocks.put(end)
    fun isEnd(block: ByteArray): Boolean = block === end

    private companion object { val end = ByteArray(0) }
}

internal class DigiRawRecorder(context: Context) : AutoCloseable {
    private val directory = File(context.filesDir, "digi/recordings")
    private var output: RandomAccessFile? = null
    private var temporary: File? = null
    private var finalFile: File? = null
    private var writer: Thread? = null
    private var queue: DigiRecordingQueue? = null
    private var accepting = false
    private var stopping = false
    private var samplesObserved = 0L
    @Volatile private var samplesWritten = 0L
    @Volatile private var writerFailed = false
    private val sampleRate = 12_000
    private val maximumSamples = sampleRate * 60L * 10L

    val active get() = synchronized(this) { accepting }
    val droppedFrames get() = synchronized(this) { queue?.droppedFrames ?: 0L }

    @Synchronized fun start(): Boolean {
        if (accepting) return true
        if (stopping) return false
        prune()
        directory.mkdirs()
        val stem = "digi-${Instant.now().epochSecond}"
        temporary = File(directory, "$stem.part")
        finalFile = File(directory, "$stem.wav")
        samplesObserved = 0
        samplesWritten = 0
        writerFailed = false
        val opened = runCatching { RandomAccessFile(temporary, "rw").also { it.write(ByteArray(WAV_HEADER_BYTES)) } }.getOrNull()
            ?: return false
        val pending = DigiRecordingQueue(QUEUE_BLOCKS)
        output = opened
        queue = pending
        accepting = true
        writer = Thread({
            while (true) {
                val block = pending.take()
                if (pending.isEnd(block)) break
                if (runCatching { opened.write(block) }.isSuccess) samplesWritten += block.size / 2
                else { writerFailed = true; pending.countDropped(block.size / 2) }
            }
        }, "ShackCQ-DigiRecorder").apply { isDaemon = true; start() }
        return true
    }

    fun append(samples: FloatArray): Boolean {
        val reachedLimit: Boolean
        synchronized(this) {
            if (!accepting) return false
            val remaining = (maximumSamples - samplesObserved).coerceAtLeast(0).toInt()
            val count = samples.size.coerceAtMost(remaining)
            if (count > 0) queue?.offer(digiPcm16Block(samples, count), count)
            samplesObserved += count
            if (count < samples.size) queue?.countDropped(samples.size - count)
            reachedLimit = count < samples.size || samplesObserved >= maximumSamples
        }
        if (reachedLimit) { stop(); return false }
        return true
    }

    fun stop(): File? {
        val file: RandomAccessFile
        val pending: DigiRecordingQueue
        val worker: Thread?
        synchronized(this) {
            file = output ?: return null
            pending = queue ?: return null
            if (stopping) return null
            stopping = true
            accepting = false
            worker = writer
        }
        pending.finish()
        worker?.join()
        val dataBytes = samplesWritten * 2
        runCatching {
            file.seek(0)
            fun ascii(value: String) = file.write(value.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) { file.writeByte(value and 0xff); file.writeByte((value ushr 8) and 0xff) }
            fun le32(value: Long) { repeat(4) { shift -> file.writeByte(((value ushr (shift * 8)) and 0xff).toInt()) } }
            ascii("RIFF"); le32(36 + dataBytes); ascii("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(sampleRate.toLong()); le32(sampleRate * 2L); le16(2); le16(16); ascii("data"); le32(dataBytes)
            file.fd.sync()
        }.onFailure { writerFailed = true }
        runCatching { file.close() }
        val source: File?
        val target: File?
        synchronized(this) {
            output = null; writer = null
            source = temporary; target = finalFile
            temporary = null; finalFile = null
            stopping = false
        }
        val completed = if (!writerFailed && source != null && target != null && source.renameTo(target)) target else null
        if (completed == null) source?.delete()
        prune()
        return completed
    }

    private fun prune() {
        if (!directory.isDirectory) return
        val cutoff = System.currentTimeMillis() - 7L * 86_400_000L
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff || it.extension == "part" }.forEach(File::delete)
        val files = directory.listFiles { file -> file.extension == "wav" }.orEmpty().sortedByDescending(File::lastModified)
        var total = 0L
        files.forEach { file -> total += file.length(); if (total > 100L * 1024L * 1024L) file.delete() }
    }

    override fun close() { stop() }

    private companion object { const val WAV_HEADER_BYTES = 44; const val QUEUE_BLOCKS = 32 }
}
