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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.shackcq.mobile.keyer.VoiceMacroPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

sealed interface VoiceAudioState {
    data object Idle : VoiceAudioState
    data class Recording(val slot: Int) : VoiceAudioState
    data class Previewing(val slot: Int) : VoiceAudioState
    data class Importing(val slot: Int) : VoiceAudioState
}

class VoiceMacroAudioController(
    private val context: Context,
    private val routes: AudioMonitorController,
    private val store: VoiceMacroStore,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val active = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    var state by mutableStateOf<VoiceAudioState>(VoiceAudioState.Idle); private set
    var status by mutableStateOf("Voice macros ready"); private set
    var level by mutableFloatStateOf(0f); private set
    var onFailure: (() -> Unit)? = null

    fun startRecording(slot: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required to record a voice macro"); return
        }
        scope.launch { mutex.withLock { record(slot) } }
    }

    fun stopCurrent() {
        active.set(false)
        runCatching { track?.pause() }
    }

    fun preview(slot: Int) = scope.launch { mutex.withLock { previewLocked(slot) } }

    fun previewPlan(plan: VoiceMacroPlan) = scope.launch { mutex.withLock {
        previewPcmLocked(-1, "composite voice plan") {
            composeVoicePlan(plan.slotIds.map(store::read), plan.interClipSilenceMillis)
        }
    } }

    fun importWave(slot: Int, bytes: ByteArray) = scope.launch { mutex.withLock {
        if (!routes.acquireAudio("VOICE", true)) { fail("Another audio operation is active"); return@withLock }
        try {
            stopCurrent(); state = VoiceAudioState.Importing(slot)
            runCatching { withContext(Dispatchers.Default) { store.import(slot, bytes) } }
                .onSuccess { status = "Voice macro ${slot + 1} imported · ${store.slots[slot].durationMillis / 1_000f}s" }
                .onFailure { fail("WAV import failed: ${it.message}") }
            state = VoiceAudioState.Idle
        } finally { routes.releaseAudio("VOICE") }
    } }

    fun delete(slot: Int) {
        stopCurrent(); runCatching { store.delete(slot) }
            .onSuccess { status = "Voice macro ${slot + 1} deleted" }
            .onFailure { fail("Delete failed: ${it.message}") }
    }

    fun close() {
        stopCurrent(); releaseAudio(); routes.releaseAudio("VOICE"); scope.cancel()
    }

    private suspend fun record(slot: Int) {
        require(slot in 0 until VOICE_MACRO_COUNT)
        if (!routes.acquireAudio("VOICE", true)) return failAndIdle("Another audio operation is active")
        stopCurrent(); active.set(true); state = VoiceAudioState.Recording(slot); status = "Recording from built-in tablet microphone"
        val input = routes.builtInMicDevice() ?: return failAndIdle("No unique built-in tablet microphone is available")
        val source = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val rate = listOf(VOICE_SAMPLE_RATE, 44_100, 32_000).firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: return failAndIdle("No supported built-in microphone sample rate")
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return failAndIdle("Microphone permission is required to record a voice macro")
        val record = runCatching {
            AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, rate / 5 * 2)).build()
        }.getOrElse { return failAndIdle("Built-in microphone could not initialize: ${it.message}") }
        if (!record.setPreferredDevice(input)) { record.release(); return failAndIdle("Android refused the built-in microphone route") }
        recorder = record
        val maximum = rate * VOICE_MAX_SECONDS
        val captured = ShortArray(maximum)
        var count = 0
        try {
            record.startRecording()
            while (active.get() && count < maximum) {
                val amount = record.read(captured, count, minOf(rate / 20, maximum - count), AudioRecord.READ_BLOCKING)
                if (amount <= 0) error("Microphone read failed ($amount)")
                count += amount
                if (record.routedDevice?.id != input.id) error("Recording route changed away from the built-in microphone")
                var energy = 0.0
                for (index in count - amount until count) energy += captured[index].toDouble() * captured[index]
                level = (sqrt(energy / amount) / Short.MAX_VALUE * 4).toFloat().coerceIn(0f, 1f)
            }
            active.set(false)
            runCatching { record.stop() }
            val prepared = withContext(Dispatchers.Default) { prepareVoicePcm(captured.copyOf(count), rate) }
            store.save(slot, prepared)
            status = "Voice macro ${slot + 1} saved · ${prepared.durationMillis / 1_000f}s"
        } catch (error: Exception) { fail("Recording failed: ${error.message}") }
        finally { record.release(); recorder = null; level = 0f; state = VoiceAudioState.Idle; routes.releaseAudio("VOICE") }
    }

    private suspend fun previewLocked(slot: Int) = previewPcmLocked(slot, "voice macro ${slot + 1}") { store.read(slot) }

    private suspend fun previewPcmLocked(slot: Int, description: String, load: () -> CanonicalVoicePcm) {
        if (!routes.acquireAudio("VOICE", true)) return failAndIdle("Another audio operation is active")
        stopCurrent(); active.set(true); state = VoiceAudioState.Previewing(slot); status = "Verifying tablet speaker preview route"
        val speaker = routes.speakerDevice() ?: return failAndIdle("No unique built-in tablet speaker is available")
        val pcm = runCatching(load).getOrElse { return failAndIdle("Preview failed: ${it.message}") }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val minimum = AudioTrack.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = runCatching {
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(VOICE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, VOICE_SAMPLE_RATE / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrElse { return failAndIdle("Preview output could not initialize: ${it.message}") }
        if (!player.setPreferredDevice(speaker)) { player.release(); return failAndIdle("Android refused the tablet speaker preview route") }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopCurrent() }.build()
        audioManager.requestAudioFocus(focus); focusRequest = focus; track = player
        try {
            player.play()
            writeAll(player, ShortArray(VOICE_SAMPLE_RATE / 10))
            if (player.routedDevice?.id != speaker.id) error("Preview route could not be verified as the tablet speaker")
            status = "Previewing $description through the built-in tablet speaker"
            var offset = 0
            while (active.get() && offset < pcm.samples.size) {
                val amount = minOf(2_400, pcm.samples.size - offset)
                val written = player.write(pcm.samples, offset, amount, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) error("Preview write failed ($written)")
                if (player.routedDevice?.id != speaker.id) error("Preview route changed away from the tablet speaker")
                offset += written
            }
            status = if (active.get()) "Preview complete · radio PTT was not used" else "Preview stopped"
        } catch (error: Exception) { fail("Preview failed: ${error.message}") }
        finally { active.set(false); releaseAudio(); state = VoiceAudioState.Idle; routes.releaseAudio("VOICE") }
    }

    private fun writeAll(player: AudioTrack, samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val written = player.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) error("Audio write failed ($written)")
            offset += written
        }
    }

    private fun releaseAudio() {
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        track = null; focusRequest = null
    }

    private fun failAndIdle(message: String) {
        fail(message); active.set(false); state = VoiceAudioState.Idle; level = 0f; routes.releaseAudio("VOICE")
    }

    private fun fail(message: String) {
        status = message; onFailure?.invoke()
    }
}
