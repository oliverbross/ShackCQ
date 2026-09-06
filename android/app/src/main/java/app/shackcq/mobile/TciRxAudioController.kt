// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class RxDspSettings(
    val noiseBlanker: Boolean = true,
    val automaticNotch: Boolean = false,
    val noiseReduction: Float = .28f,
    val agc: Boolean = true,
    val agcHangMillis: Int = 250,
    val squelchDb: Float = -105f,
    val outputGain: Float = .8f,
    val stereoMix: Float = 0f,
)

enum class RxMixerMode { RECEIVER_A, RECEIVER_B, STEREO_SPLIT, MIX }

data class ReceiverMixSettings(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val pan: Float = 0f,
) {
    fun validated() = copy(gain = gain.coerceIn(0f, 2f), pan = pan.coerceIn(-1f, 1f))
}

data class RxMixerSettings(
    val mode: RxMixerMode = RxMixerMode.RECEIVER_A,
    val receiverA: ReceiverMixSettings = ReceiverMixSettings(pan = -1f),
    val receiverB: ReceiverMixSettings = ReceiverMixSettings(pan = 1f),
    val master: Float = .8f,
    val crossfade: Float = 0f,
) {
    fun validated() = copy(receiverA = receiverA.validated(), receiverB = receiverB.validated(),
        master = master.coerceIn(0f, 1.5f), crossfade = crossfade.coerceIn(-1f, 1f))
}

internal fun mixTciStereo(a: FloatArray?, b: FloatArray?, settings: RxMixerSettings): FloatArray {
    val value = settings.validated()
    val count = maxOf(a?.size ?: 0, b?.size ?: 0)
    if (count == 0) return FloatArray(0)
    val soloA = value.receiverA.solo && !value.receiverA.muted
    val soloB = value.receiverB.solo && !value.receiverB.muted
    fun enabled(receiver: Int): Boolean = when (value.mode) {
        RxMixerMode.RECEIVER_A -> receiver == 0
        RxMixerMode.RECEIVER_B -> receiver == 1
        else -> true
    } && when (receiver) {
        0 -> !value.receiverA.muted && (!soloB || soloA)
        else -> !value.receiverB.muted && (!soloA || soloB)
    }
    val fadeA = if (value.mode == RxMixerMode.RECEIVER_A) 1f else (1f - value.crossfade) * .5f
    val fadeB = if (value.mode == RxMixerMode.RECEIVER_B) 1f else (1f + value.crossfade) * .5f
    val output = FloatArray(count * 2)
    repeat(count) { index ->
        val av = if (enabled(0)) (a?.getOrElse(index) { 0f } ?: 0f) * value.receiverA.gain * fadeA else 0f
        val bv = if (enabled(1)) (b?.getOrElse(index) { 0f } ?: 0f) * value.receiverB.gain * fadeB else 0f
        val aLeft = if (value.mode == RxMixerMode.STEREO_SPLIT) av else av * (1f - value.receiverA.pan) * .5f
        val aRight = if (value.mode == RxMixerMode.STEREO_SPLIT) 0f else av * (1f + value.receiverA.pan) * .5f
        val bLeft = if (value.mode == RxMixerMode.STEREO_SPLIT) 0f else bv * (1f - value.receiverB.pan) * .5f
        val bRight = if (value.mode == RxMixerMode.STEREO_SPLIT) bv else bv * (1f + value.receiverB.pan) * .5f
        output[index * 2] = ((aLeft + bLeft) * value.master).coerceIn(-1f, 1f)
        output[index * 2 + 1] = ((aRight + bRight) * value.master).coerceIn(-1f, 1f)
    }
    return output
}

internal fun resampleTciAudio(values: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
    if (values.isEmpty() || sourceRate == targetRate) return values
    require(sourceRate in 8_000..384_000 && targetRate in 8_000..384_000)
    val count = (values.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
    return FloatArray(count) { index ->
        val position = index.toDouble() * sourceRate / targetRate
        val low = position.toInt().coerceIn(0, values.lastIndex)
        val high = (low + 1).coerceAtMost(values.lastIndex)
        val fraction = (position - low).toFloat()
        values[low] * (1f - fraction) + values[high] * fraction
    }
}

class TciRxAudioController(
    private val context: Context,
    private val routes: AudioMonitorController,
) {
    private data class Frame(val receiver: Int, val rate: Int, val samples: FloatArray, val settings: RxDspSettings?)
    private val main = Handler(Looper.getMainLooper())
    private val queues = Array(2) { ArrayBlockingQueue<Frame>(8) }
    private val active = AtomicBoolean(false)
    private val lifecycle = LifecycleGeneration()
    private val preferences = context.getSharedPreferences("shackcq-tci-rx-dsp", Context.MODE_PRIVATE)
    private val handles = LongArray(2) { NativeRxDsp.create() }
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var armedReceiver = -1
    private var armedRate = 0
    private var profileKey = "default"
    private var settingsKey = "default|0"

    var settings by mutableStateOf(RxDspSettings()); private set
    var mixer by mutableStateOf(RxMixerSettings()); private set
    var running by mutableStateOf(false); private set
    var status by mutableStateOf("TCI RX audio stopped"); private set
    var inputLevelDb by mutableFloatStateOf(-120f); private set
    var outputLevelDb by mutableFloatStateOf(-120f); private set
    var squelched by mutableStateOf(false); private set
    var clippedFraction by mutableFloatStateOf(0f); private set
    var droppedFrames by mutableStateOf(0L); private set
    var gainReductionDb by mutableFloatStateOf(0f); private set
    var notchFrequencyHz by mutableFloatStateOf(0f); private set
    var blankedImpulses by mutableStateOf(0L); private set
    var processingLatencyMs by mutableFloatStateOf(0f); private set
    var underflowFrames by mutableStateOf(0L); private set
    var overflowByReceiver by mutableStateOf(listOf(0L, 0L)); private set
    var underflowByReceiver by mutableStateOf(listOf(0L, 0L)); private set

    fun update(value: RxDspSettings) {
        settings = value.copy(
            noiseReduction = value.noiseReduction.coerceIn(0f, 1f),
            agcHangMillis = value.agcHangMillis.coerceIn(0, 2_000),
            squelchDb = value.squelchDb.coerceIn(-120f, -20f),
            outputGain = value.outputGain.coerceIn(0f, 4f),
            stereoMix = value.stereoMix.coerceIn(-1f, 1f),
        )
        persist(settingsKey, settings)
    }

    fun updateMixer(value: RxMixerSettings) {
        mixer = value.validated()
        preferences.edit().putString("mixer_v2", JSONObject()
            .put("mode", mixer.mode.name).put("master", mixer.master.toDouble()).put("crossfade", mixer.crossfade.toDouble())
            .put("a_gain", mixer.receiverA.gain.toDouble()).put("a_mute", mixer.receiverA.muted)
            .put("a_solo", mixer.receiverA.solo).put("a_pan", mixer.receiverA.pan.toDouble())
            .put("b_gain", mixer.receiverB.gain.toDouble()).put("b_mute", mixer.receiverB.muted)
            .put("b_solo", mixer.receiverB.solo).put("b_pan", mixer.receiverB.pan.toDouble()).toString()).apply()
    }

    fun selectProfile(value: String) {
        if (active.get()) stop("TCI RX audio profile changed")
        profileKey = value.take(120).ifBlank { "default" }
        settingsKey = "$profileKey|0"
        settings = load(settingsKey)
        mixer = loadMixer()
    }

    @Synchronized
    fun start(receiver: Int, sampleRate: Int): Boolean {
        if (active.get()) {
            if (receiver == armedReceiver && sampleRate == armedRate) return true
            stop("Receiver or sample rate changed")
        }
        if (sampleRate !in 8_000..384_000) {
            status = "Unsupported TCI RX audio sample rate"
            return false
        }
        if (!routes.acquireAudio(AudioOwners.TCI_RX_AUDIO, pauseMonitor = true)) {
            status = "${routes.audioOwner} owns the audio route"
            return false
        }
        val output = routes.speakerDevice()
        if (output == null) {
            routes.releaseAudio(AudioOwners.TCI_RX_AUDIO)
            status = "No tablet speaker output detected"
            return false
        }
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minimum <= 0) {
            routes.releaseAudio(AudioOwners.TCI_RX_AUDIO)
            status = "TCI RX audio format is unavailable"
            return false
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val created = runCatching {
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, sampleRate / 10 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrElse {
            routes.releaseAudio(AudioOwners.TCI_RX_AUDIO)
            status = "Tablet speaker could not initialize"
            return false
        }
        if (!created.setPreferredDevice(output)) {
            created.release()
            routes.releaseAudio(AudioOwners.TCI_RX_AUDIO)
            status = "Android refused the selected TCI RX output route"
            return false
        }
        armedReceiver = receiver
        armedRate = sampleRate
        settingsKey = "$profileKey|$receiver"
        settings = load(settingsKey)
        queues.forEach(ArrayBlockingQueue<Frame>::clear)
        track = created
        val generation = lifecycle.next()
        active.set(true)
        running = true
        status = "TCI RX audio workbench armed · ${mixer.mode} · ${sampleRate / 1000} kHz"
        created.play()
        worker = Thread({ playback(created, output.id, generation) }, "ShackCQ-TCI-RX-Audio").apply { start() }
        return true
    }

    fun push(receiver: Int, sampleRate: Int, channels: Int, values: FloatArray) {
        pushFrame(receiver, sampleRate, channels, values, null)
    }

    fun pushLocal(receiver: Int, sampleRate: Int, channels: Int, values: FloatArray, value: RxDspSettings) {
        pushFrame(receiver, sampleRate, channels, values, value.copy(
            noiseReduction = value.noiseReduction.coerceIn(0f, 1f),
            agcHangMillis = value.agcHangMillis.coerceIn(0, 2_000),
            squelchDb = value.squelchDb.coerceIn(-120f, -20f),
            outputGain = value.outputGain.coerceIn(0f, 4f),
            stereoMix = value.stereoMix.coerceIn(-1f, 1f),
        ))
    }

    private fun pushFrame(receiver: Int, sampleRate: Int, channels: Int, values: FloatArray, override: RxDspSettings?) {
        if (!active.get() || receiver !in 0..1 || values.isEmpty()) return
        val mono = if (channels <= 1) values.copyOf() else FloatArray(values.size / channels) { frame ->
            val left = values[frame * channels]
            val right = values[frame * channels + 1]
            val mix = (override ?: settings).stereoMix
            left * (.5f * (1f - mix)) + right * (.5f * (1f + mix))
        }
        val queue = queues[receiver]
        if (!queue.offer(Frame(receiver, sampleRate, mono, override))) {
            queue.poll(); queue.offer(Frame(receiver, sampleRate, mono, override))
            main.post {
                droppedFrames += 1
                overflowByReceiver = overflowByReceiver.toMutableList().also { it[receiver] += 1 }
            }
        }
    }

    private fun playback(output: AudioTrack, deviceId: Int, generation: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (active.get() && track === output) {
            val mode = mixer.mode
            val frameA = try { if (mode != RxMixerMode.RECEIVER_B) queues[0].poll(120, TimeUnit.MILLISECONDS) else null }
                catch (_: InterruptedException) { return }
            val frameB = try { if (mode != RxMixerMode.RECEIVER_A) queues[1].poll(120, TimeUnit.MILLISECONDS) else null }
                catch (_: InterruptedException) { return }
            if (frameA == null && frameB == null) {
                main.post { if (lifecycle.isCurrent(generation)) {
                    underflowFrames += 1
                    underflowByReceiver = underflowByReceiver.mapIndexed { index, value ->
                        if ((index == 0 && mode != RxMixerMode.RECEIVER_B) || (index == 1 && mode != RxMixerMode.RECEIVER_A)) value + 1 else value
                    }
                } }
                continue
            }
            fun process(frame: Frame?): Pair<FloatArray?, FloatArray?> {
                if (frame == null) return null to null
                val samples = resampleTciAudio(frame.samples, frame.rate, armedRate)
                val value = frame.settings ?: settings
                val metrics = NativeRxDsp.process(handles[frame.receiver], samples, armedRate, value.noiseBlanker,
                    value.automaticNotch, value.noiseReduction, value.agc, value.agcHangMillis,
                    value.squelchDb, value.outputGain)
                return samples to metrics
            }
            val (a, metricsA) = process(frameA)
            val (b, metricsB) = process(frameB)
            val stereo = mixTciStereo(a, b, mixer)
            var written = 0
            while (written < stereo.size && active.get()) {
                val count = output.write(stereo, written, stereo.size - written, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) {
                    fail(generation, "TCI RX audio output failed")
                    return
                }
                written += count
            }
            if (output.routedDevice?.id != deviceId) {
                fail(generation, "TCI RX audio route was lost; output stopped")
                return
            }
            val metrics = metricsA?.takeIf { it.size >= 8 } ?: metricsB
            if (metrics != null && metrics.size >= 8) main.post {
                if (lifecycle.isCurrent(generation)) {
                    inputLevelDb = metrics[0]
                    outputLevelDb = metrics[1]
                    squelched = metrics[2] > .5f
                    clippedFraction = metrics[3]
                    gainReductionDb = metrics[4]
                    notchFrequencyHz = metrics[5]
                    blankedImpulses += metrics[6].toLong()
                    processingLatencyMs = metrics[7]
                    status = "TCI RX audio live · ${mixer.mode} · ${armedRate / 1000} kHz"
                }
            }
        }
    }

    private fun fail(generation: Long, reason: String) = main.post {
        if (lifecycle.isCurrent(generation)) stop(reason)
    }

    @Synchronized
    fun stop(reason: String = "Operator stopped TCI RX audio") {
        lifecycle.retire()
        active.set(false)
        running = false
        worker?.interrupt()
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        track = null
        worker = null
        armedReceiver = -1
        armedRate = 0
        queues.forEach(ArrayBlockingQueue<Frame>::clear)
        routes.releaseAudio(AudioOwners.TCI_RX_AUDIO)
        status = reason
    }

    @Synchronized
    fun close() {
        stop("TCI RX audio closed")
        lifecycle.close()
        handles.indices.forEach { index -> if (handles[index] != 0L) NativeRxDsp.destroy(handles[index]); handles[index] = 0 }
    }

    private fun persist(key: String, value: RxDspSettings) {
        val row = JSONObject().put("blanker", value.noiseBlanker).put("notch", value.automaticNotch)
            .put("nr", value.noiseReduction.toDouble()).put("agc", value.agc).put("hang", value.agcHangMillis)
            .put("squelch", value.squelchDb.toDouble()).put("gain", value.outputGain.toDouble())
            .put("mix", value.stereoMix.toDouble())
        preferences.edit().putString(key, row.toString()).apply()
    }

    private fun load(key: String): RxDspSettings = runCatching {
        val row = JSONObject(preferences.getString(key, "{}"))
        RxDspSettings(row.optBoolean("blanker", true), row.optBoolean("notch"), row.optDouble("nr", .28).toFloat(),
            row.optBoolean("agc", true), row.optInt("hang", 250), row.optDouble("squelch", -105.0).toFloat(),
            row.optDouble("gain", .8).toFloat(), row.optDouble("mix", 0.0).toFloat())
    }.getOrDefault(RxDspSettings())

    private fun loadMixer(): RxMixerSettings = runCatching {
        val row = JSONObject(preferences.getString("mixer_v2", "{}"))
        RxMixerSettings(
            mode = runCatching { RxMixerMode.valueOf(row.optString("mode", RxMixerMode.RECEIVER_A.name)) }.getOrDefault(RxMixerMode.RECEIVER_A),
            receiverA = ReceiverMixSettings(row.optDouble("a_gain", 1.0).toFloat(), row.optBoolean("a_mute"),
                row.optBoolean("a_solo"), row.optDouble("a_pan", -1.0).toFloat()),
            receiverB = ReceiverMixSettings(row.optDouble("b_gain", 1.0).toFloat(), row.optBoolean("b_mute"),
                row.optBoolean("b_solo"), row.optDouble("b_pan", 1.0).toFloat()),
            master = row.optDouble("master", .8).toFloat(), crossfade = row.optDouble("crossfade", 0.0).toFloat(),
        ).validated()
    }.getOrDefault(RxMixerSettings())
}
