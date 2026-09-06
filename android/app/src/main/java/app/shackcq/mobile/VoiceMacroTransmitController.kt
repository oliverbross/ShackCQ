package app.shackcq.mobile

import app.shackcq.mobile.keyer.VoiceMacroPlan
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

sealed interface VoiceTransmitState {
    data object Idle : VoiceTransmitState
    data object Preflighting : VoiceTransmitState
    data object Keying : VoiceTransmitState
    data class Playing(val slot: Int, val elapsedMillis: Long, val durationMillis: Long) : VoiceTransmitState
    data object Releasing : VoiceTransmitState
    data class Failed(val message: String, val radioMayStillBeTx: Boolean) : VoiceTransmitState
}

class VoiceMacroTransmitController(
    context: Context,
    private val transport: UsbRadioTransport,
    private val routes: AudioMonitorController,
    private val store: VoiceMacroStore,
    private val app: AppController,
    private val radioState: () -> RadioState,
    private val foreground: () -> Boolean,
    private val audioOperationIdle: () -> Boolean,
    private val onFrames: (ByteArray) -> Unit,
    private val tciAuthority: TciTransmitAuthority? = null,
    private val tciSelected: () -> Boolean = { false },
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val active = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var selectedRouteId: Int? = null

    var state by mutableStateOf<VoiceTransmitState>(VoiceTransmitState.Idle); private set
    var status by mutableStateOf("Voice TX idle"); private set
    var progress by mutableFloatStateOf(0f); private set
    var diagnostics by mutableStateOf(emptyList<String>()); private set
    val isBusy: Boolean get() = state !is VoiceTransmitState.Idle && state !is VoiceTransmitState.Failed

    init { routes.onTxRouteInvalidated = { stop("Selected voice TX route changed or disappeared") } }

    fun send(slot: Int) {
        sendPlan(VoiceMacroPlan(listOf(slot)))
    }

    fun sendPlan(plan: VoiceMacroPlan) {
        if (isBusy) return
        scope.launch { mutex.withLock { runPlan(plan) } }
    }

    fun stop(reason: String = "Voice macro stopped by operator") {
        active.set(false); app.updateVoiceMacrosArmed(false); status = reason
        runCatching { track?.pause() }; runCatching { track?.flush() }
        if (tciSelected()) tciAuthority?.globalStop("VOICE_MACRO_STOP")
    }

    fun forceRx() {
        stop("Force RX requested")
        scope.launch(NonCancellable) {
            if (tciSelected()) {
                tciAuthority?.requestRxAndRecheck()
                return@launch
            }
            runCatching { onFrames(transport.sendFast("RX;")) }
            val confirmed = runCatching { transport.confirmTq(false) }.getOrNull()
            confirmed?.frames?.let(onFrames)
            if (confirmed?.transmitting != false) {
                runCatching { onFrames(transport.sendFast("RX;")) }
                runCatching { transport.confirmTq(false) }.getOrNull()?.frames?.let(onFrames)
            }
        }
    }

    fun clearFailure() { if (state is VoiceTransmitState.Failed) { state = VoiceTransmitState.Idle; status = "Voice TX idle" } }

    fun close() {
        stop("Voice TX controller closed"); releaseAudio(); routes.onTxRouteInvalidated = null; scope.cancel()
    }

    private suspend fun runPlan(plan: VoiceMacroPlan) {
        val radio = radioState()
        val invalid = when {
            plan.slotIds.any { it !in 0 until VOICE_MACRO_COUNT || !store.hasRecording(it) } -> "Voice plan contains a missing recording"
            !app.voiceMacrosArmed -> "Voice macros are not armed"
            !foreground() -> "ShackCQ is not in the foreground"
            !audioOperationIdle() -> "Recording or preview is active"
            !radio.connected || (!tciSelected() && !transport.isConnected) -> "Radio control is disconnected"
            !isVoiceMacroMode(radio.mode) -> "Voice macros require exact USB or LSB mode"
            !tciSelected() && routes.selectedTxDevice() == null -> "Select one unambiguous voice TX USB output"
            else -> null
        }
        if (invalid != null) { fail(invalid, false); return }
        val clips = runCatching { plan.slotIds.map(store::read) }
            .getOrElse { fail("Voice plan validation failed: ${it.message}", false); return }
        val pcm = runCatching { composeVoicePlan(clips, plan.interClipSilenceMillis) }
            .getOrElse { fail(it.message ?: "Voice plan is invalid", false); return }
        val displaySlot = plan.slotIds.first()
        active.set(true); progress = 0f; state = VoiceTransmitState.Preflighting; log("Preflight started")
        if (tciSelected()) {
            val authority = tciAuthority
            val samples = FloatArray(pcm.samples.size) { pcm.samples[it] / 32768.0F }
            val completed = authority?.transmit(TciTxIntent(
                owner = "VoiceMacro:$displaySlot", source = TciTxSource.VOICE_MACRO,
                mode = radio.mode, mono = samples, sampleRate = VOICE_SAMPLE_RATE, receiver = 0,
                expectedFrequencyHz = radio.frequencyHz,
                foregroundValid = { active.get() && foreground() && tciSelected() && isVoiceMacroMode(radioState().mode) },
            )) == true
            active.set(false); progress = 0f
            if (completed) { state = VoiceTransmitState.Idle; status = "TCI voice macro complete · RX confirmed" }
            else fail("TCI voice TX blocked · ${authority?.snapshot?.interlock ?: "authority unavailable"}",
                authority?.snapshot?.state == TciTxMachineState.RX_UNCONFIRMED)
            return
        }
        transport.beginVoiceOperation()
        val io = AndroidVoiceTxIo(displaySlot, pcm)
        val result = try { withContext(NonCancellable) { executeVoiceTxSequence(io) } }
        finally {
            active.set(false); releaseAudio(); transport.endVoiceOperation()
        }
        if (result.success) {
            state = VoiceTransmitState.Idle; progress = 0f; status = result.message
        } else {
            app.updateVoiceMacrosArmed(false); fail(result.message, result.radioMayStillBeTx)
        }
    }

    private inner class AndroidVoiceTxIo(private val slot: Int, private val pcm: CanonicalVoicePcm) : VoiceTxSequenceIo {
        override fun acquireAudio(): String? = if (routes.acquireAudio(AudioOwners.VOICE_TX, pauseMonitor = true)) null
            else "${routes.audioOwner} owns the shared audio route; voice TX blocked before PTT"

        override fun releaseAudio() {
            this@VoiceMacroTransmitController.releaseAudio()
            routes.releaseAudio(AudioOwners.VOICE_TX)
        }

        override suspend fun queryTq(): Boolean? {
            val response = transport.queryTqFresh(); onFrames(response.frames)
            log(if (response.transmitting == false) "TQ0 preflight" else "Preflight TQ unavailable or TX")
            return response.transmitting
        }

        override suspend fun prepareAndVerifyRoute() {
            require(active.get() && foreground() && isVoiceMacroMode(radioState().mode)) { "Voice TX preflight was cancelled" }
            val device = routes.selectedTxDevice() ?: error("Selected voice TX USB output disappeared")
            selectedRouteId = device.id
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val minimum = AudioTrack.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val player = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(VOICE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, VOICE_SAMPLE_RATE / 2 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
            if (!player.setPreferredDevice(device)) { player.release(); error("Android refused the selected voice TX USB route") }
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop("Audio focus lost during voice macro") }.build()
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                player.release(); error("Exclusive audio focus was not granted")
            }
            focusRequest = focus; track = player; player.play()
            writeStereo(ShortArray(VOICE_SAMPLE_RATE / 10 * 2))
            if (player.routedDevice?.id != device.id) error("Voice TX route could not be verified before CAT PTT")
            log("USB TX route verified · ${routes.selectedTx?.name.orEmpty()}")
        }

        override suspend fun sendTx() {
            state = VoiceTransmitState.Keying
            onFrames(transport.sendFast("TX;")); log("TX sent")
        }

        override suspend fun confirmTq(transmitting: Boolean): Boolean {
            val response = transport.confirmTq(transmitting); onFrames(response.frames)
            if (response.transmitting == transmitting) log(if (transmitting) "TQ1 confirmed" else "TQ0 confirmed")
            return response.transmitting == transmitting
        }

        override suspend fun writeLeadSilence() {
            writeStereo(ShortArray(VOICE_SAMPLE_RATE * 175 / 1_000 * 2)); log("Audio lead-in complete")
        }

        override suspend fun writeSpeech() = withTimeout(pcm.durationMillis + 2_000) {
            val player = track ?: error("Voice TX audio track is unavailable")
            var offset = 0
            while (offset < pcm.samples.size) {
                require(active.get() && foreground() && transport.isConnected && isVoiceMacroMode(radioState().mode)) { "Voice TX was aborted by safety state" }
                require(player.routedDevice?.id == selectedRouteId) { "Selected voice TX USB route was lost" }
                val count = minOf(2_400, pcm.samples.size - offset)
                val stereo = stereoLeftOnly(pcm.samples.copyOfRange(offset, offset + count), app.voiceTxLevel)
                writeStereo(stereo)
                offset += count
                progress = offset.toFloat() / pcm.samples.size
                state = VoiceTransmitState.Playing(slot, offset * 1_000L / VOICE_SAMPLE_RATE, pcm.durationMillis)
            }
            log("Speech audio completed")
        }

        override suspend fun writeTailSilence() {
            state = VoiceTransmitState.Releasing
            writeStereo(ShortArray(VOICE_SAMPLE_RATE * 125 / 1_000 * 2)); log("Trailing silence complete")
        }

        override suspend fun haltAudio() {
            withContext(Dispatchers.IO) {
                track?.let { runCatching { it.pause() }; runCatching { it.flush() } }
            }
        }

        override suspend fun sendRx() {
            state = VoiceTransmitState.Releasing
            onFrames(transport.sendFast("RX;")); log("RX sent")
        }
    }

    private suspend fun writeStereo(samples: ShortArray) = withContext(Dispatchers.IO) {
        val player = track ?: error("Voice TX audio track is unavailable")
        var offset = 0
        while (offset < samples.size) {
            require(active.get()) { "Voice TX stopped" }
            val written = player.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) error("Voice TX audio write failed ($written)")
            offset += written
        }
    }

    private fun releaseAudio() {
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        track = null; focusRequest = null; selectedRouteId = null
    }

    private fun log(message: String) {
        diagnostics = (diagnostics + message).takeLast(16); status = message
    }

    private fun fail(message: String, mayStillTx: Boolean) {
        state = VoiceTransmitState.Failed(message, mayStillTx)
        status = if (mayStillTx) "$message · RX UNCONFIRMED: press KX3 RX/XMIT or remove PTT" else message
        log(status)
    }
}
