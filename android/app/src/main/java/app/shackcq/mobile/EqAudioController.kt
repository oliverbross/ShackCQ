package app.shackcq.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

sealed interface EqAudioState {
    data object Idle : EqAudioState
    data class Capturing(val seconds: Float) : EqAudioState
    data class Playing(val after: Boolean) : EqAudioState
    data class Processing(val label: String) : EqAudioState
}

class EqAudioController(private val context: Context, private val routes: AudioMonitorController) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var focus: AudioFocusRequest? = null

    var state by mutableStateOf<EqAudioState>(EqAudioState.Idle); private set
    var status by mutableStateOf("No EQ sample captured"); private set
    var level by mutableFloatStateOf(0f); private set
    var capture by mutableStateOf<EqCapture?>(null); private set
    var preview by mutableStateOf<EqPreview?>(null); private set
    var source by mutableStateOf(EqCaptureSource.RAW_REFERENCE)
    var useBuiltInReference by mutableStateOf(false)
    var loudnessMatched by mutableStateOf(true)
    var blind by mutableStateOf(false)
    var blindAfterIsA by mutableStateOf(false); private set
    val routeOwner: String get() = routes.audioOwner

    fun startCapture(snapshot: EqSnapshot?, pauseMonitor: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is required"; return
        }
        if (source.hardwareBaseline && snapshot == null) { status = "Read and verify the radio curve before a hardware-baseline capture"; return }
        if (!routes.acquireAudio("EQ", pauseMonitor)) {
            status = if (routes.enabled) "Audio monitor is using this input · choose PAUSE AND USE FOR EQ" else "Another audio operation owns this device"
            return
        }
        scope.launch { captureLocked(snapshot) }
    }

    fun stop() { active.set(false); runCatching { player?.pause() } }

    fun rebuildPreview(draft: EqCurve) {
        val clip = capture ?: return
        state = EqAudioState.Processing("Building approximate KX3-band preview")
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { buildEqPreview(clip, draft, loudnessMatched) } }
                .onSuccess { preview = it; status = "Preview ready · approximate KX3-band response" }
                .onFailure { status = "Preview failed: ${it.message}" }
            state = EqAudioState.Idle
        }
    }

    fun play(after: Boolean) {
        val item = preview ?: return
        scope.launch { playPcm(if (after) item.after else item.before, capture?.sampleRate ?: 48_000, after) }
    }

    fun toggleBlind() {
        blind = !blind
        if (blind) blindAfterIsA = kotlin.random.Random.nextBoolean()
    }

    fun clear() { stop(); capture = null; preview = null; blind = false; status = "No EQ sample captured" }

    fun close() { clear(); releasePlayback(); routes.releaseAudio("EQ"); scope.cancel() }

    private suspend fun captureLocked(snapshot: EqSnapshot?) {
        active.set(true); state = EqAudioState.Capturing(0f); preview = null
        val input = if (useBuiltInReference) routes.builtInMicDevice() else routes.selectedRxDevice()
        if (input == null) { status = if (useBuiltInReference) "No unique built-in reference microphone" else "Select a USB audio input"; finishCapture(); return }
        val sourceKind = when {
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true" -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val rate = listOf(48_000, 44_100).firstOrNull { AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0 }
            ?: run { status = "No supported 48/44.1 kHz capture path"; finishCapture(); return }
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is required for EQ capture"; finishCapture(); return
        }
        val record = runCatching { AudioRecord.Builder().setAudioSource(sourceKind)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, rate / 5 * 2)).build() }.getOrElse {
            status = "Selected input could not initialize: ${it.message}"; finishCapture(); return
        }
        if (!record.setPreferredDevice(input)) { record.release(); status = "Android refused the selected EQ input"; finishCapture(); return }
        val effects = listOfNotNull(
            AutomaticGainControl.create(record.audioSessionId), NoiseSuppressor.create(record.audioSessionId),
            AcousticEchoCanceler.create(record.audioSessionId))
        var disabled = 0
        effects.forEach { effect -> runCatching { effect.enabled = false; if (!effect.enabled) disabled++ } }
        val processing = when {
            effects.isEmpty() -> EqInputProcessing.UNKNOWN
            disabled == effects.size -> EqInputProcessing.OFF
            else -> EqInputProcessing.PARTIAL
        }
        recorder = record; val samples = ShortArray(rate * 15); var count = 0
        status = "Capturing ${source.label} · tap STOP when the sample is complete"
        try {
            record.startRecording()
            while (active.get() && count < samples.size) {
                val amount = record.read(samples, count, minOf(rate / 20, samples.size - count), AudioRecord.READ_BLOCKING)
                if (amount <= 0) error("Audio input read failed ($amount)")
                if (record.routedDevice?.id != input.id) error("Selected input disappeared during capture")
                count += amount
                var energy = 0.0; for (i in count - amount until count) energy += samples[i].toDouble() * samples[i]
                level = (sqrt(energy / amount) / Short.MAX_VALUE * 4).toFloat().coerceIn(0f, 1f)
                state = EqAudioState.Capturing(count.toFloat() / rate)
            }
            active.set(false); runCatching { record.stop() }
            val exact = samples.copyOf(count)
            val metrics = withContext(Dispatchers.Default) { analyzeEqCapture(exact, rate) }
            capture = EqCapture(exact, rate, source, snapshot?.context ?: EqContext.UNKNOWN,
                snapshot.takeIf { source.hardwareBaseline }, input.productName?.toString().orEmpty(), "MONO", Instant.now(), processing, metrics)
            status = "Captured ${"%.1f".format(count.toFloat() / rate)} s · ${metrics.qualityLabel} · ${processing.label}"
        } catch (error: Exception) { status = "Capture failed: ${error.message}" }
        finally { effects.forEach { it.release() }; record.release(); recorder = null; finishCapture() }
    }

    private fun finishCapture() { active.set(false); level = 0f; state = EqAudioState.Idle; routes.releaseAudio("EQ") }

    private suspend fun playPcm(samples: ShortArray, rate: Int, after: Boolean) {
        stop(); releasePlayback()
        if (!routes.acquireAudio("EQ", true)) { status = "Another audio operation owns the playback route"; return }
        val output = routes.selectedTxDevice() ?: routes.speakerDevice()
        if (output == null) { status = "No playback output is available"; routes.releaseAudio("EQ"); return }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, rate / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (!track.setPreferredDevice(output)) { track.release(); status = "Android refused the selected playback route"; routes.releaseAudio("EQ"); return }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if (it <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }.build()
        audioManager.requestAudioFocus(request); focus = request; player = track; active.set(true); state = EqAudioState.Playing(after)
        try {
            track.play()
            val fade = (rate * .02).toInt().coerceAtLeast(1)
            val copy = samples.copyOf()
            for (i in 0 until minOf(fade, copy.size / 2)) {
                copy[i] = (copy[i] * i / fade).toInt().toShort()
                val tail = copy.lastIndex - i; copy[tail] = (copy[tail] * i / fade).toInt().toShort()
            }
            var offset = 0
            while (active.get() && offset < copy.size) {
                val written = track.write(copy, offset, minOf(2_400, copy.size - offset), AudioTrack.WRITE_BLOCKING)
                if (written <= 0) error("Playback write failed ($written)"); offset += written
            }
            status = if (active.get()) "${if (after) "AFTER" else "BEFORE"} playback complete" else "Playback stopped"
        } catch (error: Exception) { status = "Playback failed: ${error.message}" }
        finally { active.set(false); releasePlayback(); state = EqAudioState.Idle; routes.releaseAudio("EQ") }
    }

    private fun releasePlayback() {
        player?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        focus?.let(audioManager::abandonAudioFocusRequest); player = null; focus = null
    }
}
