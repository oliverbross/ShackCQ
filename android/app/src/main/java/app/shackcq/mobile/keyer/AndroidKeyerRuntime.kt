package app.shackcq.mobile.keyer

import app.shackcq.mobile.AppController
import app.shackcq.mobile.NativeCore
import app.shackcq.mobile.OperatingContextSnapshot
import app.shackcq.mobile.RadioState
import app.shackcq.mobile.TciTransmitAuthority
import app.shackcq.mobile.TciTxIntent
import app.shackcq.mobile.TciTxSource
import app.shackcq.mobile.VoiceMacroTransmitController
import app.shackcq.mobile.VoiceTransmitState
import app.shackcq.mobile.cwMacroCommand
import app.shackcq.mobile.isCwMacroMode
import app.shackcq.mobile.isVoiceMacroMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AndroidKeyerRuntime(
    private val profiles: KeyerProfileStore,
    private val app: AppController,
    private val voiceTx: VoiceMacroTransmitController,
    private val radioState: () -> RadioState,
    private val operatingContext: () -> OperatingContextSnapshot,
    private val foreground: () -> Boolean,
    private val foregroundGeneration: () -> Long,
    private val direct: (String) -> Unit,
    private val scope: CoroutineScope,
    private val tciAuthority: TciTransmitAuthority? = null,
    private val tciSelected: () -> Boolean = { false },
) {
    val controller: KeyerController = KeyerController(
        profiles = profiles,
        currentContext = ::context,
        execute = ::execute,
        stopExecution = {
            app.updateCwMacrosArmed(false); app.updateVoiceMacrosArmed(false)
            if (tciSelected()) tciAuthority?.globalStop("KEYER_STOP")
            else if (voiceTx.isBusy) voiceTx.forceRx() else direct("RX;")
        },
    )

    fun context(): KeyerContextSnapshot {
        val profile = profiles.activeProfile()
        val radio = radioState()
        val snapshot = operatingContext()
        val mode = when {
            isCwMacroMode(radio.mode) -> KeyerMode.CW
            isVoiceMacroMode(radio.mode) -> KeyerMode.VOICE
            else -> profile.mode
        }
        return KeyerContextSnapshot(
            operatingGeneration = snapshot.generation,
            foregroundGeneration = foregroundGeneration(),
            radioIdentity = radio.identity,
            connected = radio.connected,
            foreground = foreground(),
            mode = mode,
            profileId = profile.id,
            modeSupported = isCwMacroMode(radio.mode) || isVoiceMacroMode(radio.mode),
            role = profile.role,
            myCall = snapshot.stationCallsign.value,
            grid = snapshot.stationGrid.value,
            reference = snapshot.activationReference.value,
            band = snapshot.band.value,
        )
    }

    fun onVoiceStateChanged(state: VoiceTransmitState) {
        if (state is VoiceTransmitState.Idle && controller.snapshot().active?.let { active ->
                active.messageId?.let(profiles::resolveMessage)?.mode == KeyerMode.VOICE
            } == true) controller.onExecutionComplete()
        if (state is VoiceTransmitState.Failed) controller.stop(
            if (state.radioMayStillBeTx) KeyerStopReason.UnsafeState else KeyerStopReason.RouteLost
        )
    }

    private fun execute(message: KeyerMessageTemplate, keyerContext: KeyerContextSnapshot): KeyerDispatchResult = when (message.mode) {
        KeyerMode.CW -> {
            if (!app.cwMacrosArmed) KeyerDispatchResult.Rejected(KeyerFailureReason.NotArmed, "CW macros are not armed")
            else KeyerTemplateResolver.resolve(message.template, keyerContext).let { resolved ->
                if (resolved.error != null) KeyerDispatchResult.Rejected(resolved.error, resolved.detail)
                else {
                    if (tciSelected()) scope.launch {
                        val pcm = NativeCore.digiEncodeCw(resolved.text, 20, 700f, 48_000)
                        tciAuthority?.transmit(TciTxIntent(
                            owner = "Keyer:${message.id}", source = TciTxSource.CW_AUDIO_KEYER,
                            mode = "CW", mono = pcm, sampleRate = 48_000, receiver = 0,
                            expectedFrequencyHz = radioState().frequencyHz,
                            foregroundValid = { foreground() && app.cwMacrosArmed && tciSelected() },
                        ))
                        controller.onExecutionComplete()
                    } else {
                        direct(cwMacroCommand(resolved.text)!!)
                        scope.launch { delay(50); controller.onExecutionComplete() }
                    }
                    KeyerDispatchResult.Accepted(false)
                }
            }
        }
        KeyerMode.VOICE -> when {
            !app.voiceMacrosArmed -> KeyerDispatchResult.Rejected(KeyerFailureReason.NotArmed, "Voice macros are not armed")
            voiceTx.isBusy -> KeyerDispatchResult.Rejected(KeyerFailureReason.AudioBusy, "Voice transmit controller is already active")
            message.voicePlan == null -> KeyerDispatchResult.Rejected(KeyerFailureReason.VoiceClipMissing, "Voice plan is empty")
            else -> { voiceTx.sendPlan(message.voicePlan); KeyerDispatchResult.Accepted(false) }
        }
    }
}
