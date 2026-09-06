package app.shackcq.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val VOICE_MACRO_COUNT = 6
const val VOICE_MACRO_LABEL_MAX = CW_MACRO_LABEL_MAX
const val VOICE_SAMPLE_RATE = 48_000
const val VOICE_MAX_SECONDS = 30
const val VOICE_FORMAT_VERSION = 1

private val defaultVoiceLabels = listOf("CQ", "CALL", "EXCH", "AGAIN", "TU", "73")

fun requireVoiceMacroSlot(index: Int): Int = index.also {
    require(it in 0 until VOICE_MACRO_COUNT) { "Voice macro slot must be 0 through ${VOICE_MACRO_COUNT - 1}" }
}

fun defaultVoiceMacroLabel(index: Int): String = defaultVoiceLabels[requireVoiceMacroSlot(index)]

fun sanitizeVoiceMacroLabel(value: String, index: Int): String {
    requireVoiceMacroSlot(index)
    val clean = value.trim().filterNot(Char::isISOControl)
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '/' }
        .take(VOICE_MACRO_LABEL_MAX)
    return clean.ifBlank { "M${index + 1}" }
}

fun isVoiceMacroMode(mode: String): Boolean = mode.trim().uppercase() in setOf("USB", "LSB")

data class CanonicalVoicePcm(val samples: ShortArray, val sampleRate: Int = VOICE_SAMPLE_RATE) {
    val durationMillis: Long get() = samples.size * 1_000L / sampleRate
}

fun composeVoicePlan(clips: List<CanonicalVoicePcm>, interClipSilenceMillis: Int, maximumSeconds: Int = 45): CanonicalVoicePcm {
    require(clips.isNotEmpty() && clips.size <= 12) { "A voice plan requires 1 through 12 validated clips" }
    require(interClipSilenceMillis in 0..500) { "Inter-clip silence is out of range" }
    require(clips.all { it.sampleRate == VOICE_SAMPLE_RATE && it.samples.isNotEmpty() }) { "Voice plan clip is invalid" }
    val silenceSamples = VOICE_SAMPLE_RATE * interClipSilenceMillis / 1_000
    val totalSamples = clips.sumOf { it.samples.size.toLong() } + silenceSamples.toLong() * (clips.size - 1)
    require(totalSamples <= VOICE_SAMPLE_RATE * maximumSeconds.toLong()) { "Voice plan exceeds the $maximumSeconds second limit" }
    val combined = ShortArray(totalSamples.toInt())
    var destination = 0
    clips.forEachIndexed { index, clip ->
        clip.samples.copyInto(combined, destination); destination += clip.samples.size
        if (index != clips.lastIndex) destination += silenceSamples
    }
    return CanonicalVoicePcm(combined)
}

data class VoiceMacroSlot(
    val index: Int,
    val label: String,
    val exists: Boolean,
    val durationMillis: Long = 0,
    val formatVersion: Int = VOICE_FORMAT_VERSION,
    val waveform: List<Float> = emptyList(),
)

data class ParsedPcmWave(val samples: ShortArray, val sampleRate: Int, val channels: Int)

fun parsePcmWave(bytes: ByteArray): ParsedPcmWave {
    require(bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WAVE") { "Only RIFF/WAVE files are supported" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var format: Triple<Int, Int, Int>? = null
    var blockAlign = 0
    var dataOffset = -1
    var dataSize = 0
    while (offset + 8 <= bytes.size) {
        val id = bytes.copyOfRange(offset, offset + 4).decodeToString()
        val size = buffer.getInt(offset + 4)
        require(size >= 0 && offset + 8L + size <= bytes.size.toLong()) { "Malformed WAV chunk" }
        val payload = offset + 8
        when (id) {
            "fmt " -> {
                require(size >= 16) { "Malformed WAV format chunk" }
                val code = buffer.getShort(payload).toInt() and 0xffff
                val channels = buffer.getShort(payload + 2).toInt() and 0xffff
                val rate = buffer.getInt(payload + 4)
                blockAlign = buffer.getShort(payload + 12).toInt() and 0xffff
                val bits = buffer.getShort(payload + 14).toInt() and 0xffff
                require(code == 1) { "Compressed or floating-point WAV is not supported" }
                require(channels in 1..2) { "WAV must be mono or stereo" }
                require(rate in 8_000..96_000) { "WAV sample rate must be 8–96 kHz" }
                require(bits == 16 && blockAlign == channels * 2) { "WAV must use 16-bit PCM" }
                format = Triple(channels, rate, bits)
            }
            "data" -> if (dataOffset < 0) { dataOffset = payload; dataSize = size }
        }
        offset = payload + size + (size and 1)
    }
    val (channels, rate) = format?.let { it.first to it.second } ?: error("WAV format chunk is missing")
    require(dataOffset >= 0 && dataSize >= blockAlign && dataSize % blockAlign == 0) { "WAV audio data is empty or malformed" }
    require(dataSize <= rate * channels * 2 * 300) { "WAV file is unreasonably large" }
    val frames = dataSize / blockAlign
    val mono = ShortArray(frames)
    repeat(frames) { frame ->
        val base = dataOffset + frame * blockAlign
        val left = buffer.getShort(base).toInt()
        val value = if (channels == 1) left else {
            val right = buffer.getShort(base + 2).toInt()
            ((left.toLong() + right.toLong()) / 2L).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
        }
        mono[frame] = value.toShort()
    }
    return ParsedPcmWave(mono, rate, channels)
}

fun writeCanonicalWave(pcm: CanonicalVoicePcm): ByteArray {
    require(pcm.sampleRate == VOICE_SAMPLE_RATE) { "Canonical voice audio must be 48 kHz" }
    val dataSize = pcm.samples.size * 2
    val output = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
    output.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    output.putInt(VOICE_SAMPLE_RATE).putInt(VOICE_SAMPLE_RATE * 2).putShort(2).putShort(16)
    output.put("data".toByteArray()).putInt(dataSize)
    pcm.samples.forEach { output.putShort(it) }
    return output.array()
}

fun resampleLinear(samples: ShortArray, sourceRate: Int, targetRate: Int = VOICE_SAMPLE_RATE): ShortArray {
    require(sourceRate > 0 && targetRate > 0)
    if (samples.isEmpty() || sourceRate == targetRate) return samples.copyOf()
    val outputSize = max(1, (samples.size.toLong() * targetRate / sourceRate).toInt())
    return ShortArray(outputSize) { index ->
        val source = index.toDouble() * sourceRate / targetRate
        val low = source.toInt().coerceIn(0, samples.lastIndex)
        val high = (low + 1).coerceAtMost(samples.lastIndex)
        val fraction = source - low
        (samples[low] + (samples[high] - samples[low]) * fraction).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

fun prepareVoicePcm(input: ShortArray, sampleRate: Int): CanonicalVoicePcm {
    require(input.isNotEmpty()) { "Voice recording is empty" }
    val source = resampleLinear(input, sampleRate)
    val window = VOICE_SAMPLE_RATE / 100
    val threshold = (Short.MAX_VALUE * 0.008).toDouble()
    fun meaningful(start: Int): Boolean {
        val end = (start + window).coerceAtMost(source.size)
        if (end <= start) return false
        var energy = 0.0
        for (index in start until end) energy += source[index].toDouble() * source[index]
        return sqrt(energy / (end - start)) >= threshold
    }
    val starts = (0 until source.size step window).filter(::meaningful)
    require(starts.isNotEmpty()) { "No meaningful speech was detected" }
    val padding = VOICE_SAMPLE_RATE * 120 / 1_000
    val first = (starts.first() - padding).coerceAtLeast(0)
    val last = (starts.last() + window + padding).coerceAtMost(source.size)
    val maximum = VOICE_SAMPLE_RATE * VOICE_MAX_SECONDS
    val trimmed = source.copyOfRange(first, last.coerceAtMost(first + maximum))
    require(trimmed.isNotEmpty()) { "No meaningful speech was detected" }
    val peak = trimmed.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
    val target = (Short.MAX_VALUE * 0.501187).toInt()
    val multiplier = target.toDouble() / peak
    val fade = (VOICE_SAMPLE_RATE * 5 / 1_000).coerceAtMost(trimmed.size / 2)
    val processed = ShortArray(trimmed.size) { index ->
        val fadeScale = when {
            fade == 0 -> 1.0
            index < fade -> index.toDouble() / fade
            index >= trimmed.size - fade -> (trimmed.size - 1 - index).coerceAtLeast(0).toDouble() / fade
            else -> 1.0
        }
        (trimmed[index] * multiplier * fadeScale).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return CanonicalVoicePcm(processed)
}

fun importVoiceWave(bytes: ByteArray): CanonicalVoicePcm {
    val parsed = parsePcmWave(bytes)
    return prepareVoicePcm(parsed.samples, parsed.sampleRate)
}

fun waveformPeaks(samples: ShortArray, buckets: Int = 48): List<Float> {
    if (samples.isEmpty() || buckets <= 0) return emptyList()
    return List(minOf(buckets, samples.size)) { bucket ->
        val start = bucket * samples.size / minOf(buckets, samples.size)
        val end = (bucket + 1) * samples.size / minOf(buckets, samples.size)
        (start until end).maxOfOrNull { abs(samples[it].toInt()) / Short.MAX_VALUE.toFloat() } ?: 0f
    }
}

fun stereoLeftOnly(samples: ShortArray, level: Float): ShortArray {
    val scale = level.coerceIn(0f, 1f)
    return ShortArray(samples.size * 2) { index ->
        if (index and 1 == 1) 0 else (samples[index / 2] * scale).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

data class StableSelection<T>(val selected: T? = null, val selectionRequired: Boolean = false, val reason: String = "")

fun <T> chooseStableCandidate(candidates: List<T>, savedKey: String?, key: (T) -> String): StableSelection<T> {
    if (candidates.isEmpty()) return StableSelection(reason = "No eligible device detected")
    if (!savedKey.isNullOrBlank()) {
        val matches = candidates.filter { key(it) == savedKey }
        if (matches.size == 1) return StableSelection(selected = matches.single())
        if (matches.size > 1) return StableSelection(selectionRequired = true, reason = "Saved device identity is ambiguous")
    }
    if (candidates.size == 1) return StableSelection(selected = candidates.single())
    return StableSelection(selectionRequired = true, reason = "Multiple eligible devices detected; select one")
}

fun parseFreshTq(frames: ByteArray): Boolean? {
    val text = frames.toString(Charsets.US_ASCII)
    return Regex("(?:^|;)TQ([01]);").findAll(text).lastOrNull()?.groupValues?.get(1)?.let { it == "1" }
        ?: Regex("TQ([01]);").findAll(text).lastOrNull()?.groupValues?.get(1)?.let { it == "1" }
}

interface VoiceTxSequenceIo {
    fun acquireAudio(): String?
    fun releaseAudio()
    suspend fun queryTq(): Boolean?
    suspend fun prepareAndVerifyRoute()
    suspend fun sendTx()
    suspend fun confirmTq(transmitting: Boolean): Boolean
    suspend fun writeLeadSilence()
    suspend fun writeSpeech()
    suspend fun writeTailSilence()
    suspend fun haltAudio()
    suspend fun sendRx()
}

data class VoiceTxSequenceResult(val success: Boolean, val message: String, val radioMayStillBeTx: Boolean = false)

suspend fun executeVoiceTxSequence(io: VoiceTxSequenceIo): VoiceTxSequenceResult {
    val leaseFailure = io.acquireAudio()
    if (leaseFailure != null) return VoiceTxSequenceResult(false, leaseFailure)
    try {
        var ownsTx = false
        var rxConfirmed = false
        var rxAttempts = 0
        when (val preflight = try { io.queryTq() } catch (error: Throwable) {
            return VoiceTxSequenceResult(false, error.message ?: "Fresh TQ0 preflight failed")
        }) {
            false -> Unit
            true -> return VoiceTxSequenceResult(false, "Radio is already transmitting; ShackCQ did not take ownership")
            null -> return VoiceTxSequenceResult(false, "Fresh TQ0 preflight response was not received")
        }
        var result = try {
            io.prepareAndVerifyRoute()
            ownsTx = true
            io.sendTx()
            if (!io.confirmTq(true)) error("TX was not confirmed by fresh TQ1")
            io.writeLeadSilence()
            io.writeSpeech()
            io.writeTailSilence()
            io.haltAudio()
            io.sendRx()
            rxAttempts++
            rxConfirmed = io.confirmTq(false)
            if (!rxConfirmed) error("RX was not confirmed by fresh TQ0")
            VoiceTxSequenceResult(true, "Voice macro sent and RX confirmed")
        } catch (error: Throwable) {
            VoiceTxSequenceResult(false, error.message ?: error.javaClass.simpleName)
        } finally {
            if (ownsTx && !rxConfirmed) {
                runCatching { io.haltAudio() }
                while (!rxConfirmed && rxAttempts < 2) {
                    rxAttempts++
                    runCatching { io.sendRx() }
                    rxConfirmed = runCatching { io.confirmTq(false) }.getOrDefault(false)
                }
            }
        }
        if (!result.success) result = result.copy(radioMayStillBeTx = ownsTx && !rxConfirmed)
        return result
    } finally {
        runCatching { io.releaseAudio() }
    }
}
