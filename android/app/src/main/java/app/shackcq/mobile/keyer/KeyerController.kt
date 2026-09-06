package app.shackcq.mobile.keyer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class KeyerController(
    private val profiles: KeyerProfileStore,
    private val currentContext: () -> KeyerContextSnapshot,
    private val execute: (KeyerMessageTemplate, KeyerContextSnapshot) -> KeyerDispatchResult,
    private val stopExecution: (KeyerStopReason) -> Unit,
    private val queue: KeyerQueueController = KeyerQueueController(),
) : KeyerDispatchPort {
    override fun availability(context: KeyerContextSnapshot): KeyerAvailability = when {
        !context.foreground -> KeyerAvailability(false, KeyerFailureReason.AppNotForeground, "ShackCQ is not foreground")
        !context.connected -> KeyerAvailability(false, KeyerFailureReason.RadioDisconnected, "Radio is disconnected")
        !context.modeSupported -> KeyerAvailability(false, KeyerFailureReason.ModeUnsupported, "Radio mode is not CW, USB or LSB")
        context.profileId != profiles.activeProfileId -> KeyerAvailability(false, KeyerFailureReason.ProfileChanged, "Active profile changed")
        else -> KeyerAvailability(true)
    }

    override fun submit(action: KeyerAction, context: KeyerContextSnapshot): KeyerDispatchResult {
        if (action is KeyerAction.Stop) { stop(); return KeyerDispatchResult.Accepted(false) }
        availability(context).takeIf { !it.available }?.let { return KeyerDispatchResult.Rejected(it.reason!!, it.detail) }
        val messageId = action.messageId ?: return KeyerDispatchResult.Rejected(KeyerFailureReason.BackendUnavailable, "WPM control is unavailable on the verified backend")
        val message = profiles.resolveMessage(messageId)
            ?: return KeyerDispatchResult.Rejected(KeyerFailureReason.NoBinding, "Message is not in the active profile")
        if (message.mode != context.mode) return KeyerDispatchResult.Rejected(KeyerFailureReason.ModeUnsupported, "Message mode does not match the radio")
        val queued = queue.submit(action, message.label, context)
        if (queued == KeyerDispatchResult.Accepted(false)) {
            val result = execute(message, context)
            if (result is KeyerDispatchResult.Rejected) queue.fail(result.detail, rxVerified = true)
            return result
        }
        return queued
    }

    fun onExecutionComplete() {
        val next = queue.complete(currentContext()) ?: return
        val message = profiles.resolveMessage(next.messageId ?: return) ?: return
        val result = execute(message, next.context)
        if (result is KeyerDispatchResult.Rejected) queue.fail(result.detail, rxVerified = true)
    }

    fun invalidate() { if (queue.invalidate(currentContext())) stopExecution(KeyerStopReason.ContextChanged) }
    override fun stop(reason: KeyerStopReason) { queue.stop(reason); stopExecution(reason) }
    override fun snapshot(): KeyerQueueSnapshot = queue.snapshot()
}

data class RepeatCqLimits(val intervalSeconds: Int = 10, val maximumCycles: Int = 10, val maximumElapsedMinutes: Int = 10) {
    init { require(intervalSeconds in 2..600); require(maximumCycles in 1..50); require(maximumElapsedMinutes in 1..30) }
}

class RepeatCqController(private val now: () -> Long = System::currentTimeMillis) {
    data class State(val active: Boolean = false, val messageId: String = "", val cycle: Int = 0, val startedAt: Long = 0, val nextAt: Long = 0)
    var state by mutableStateOf(State()); private set
    fun start(messageId: String, limits: RepeatCqLimits): Boolean {
        if (messageId.isBlank() || state.active) return false
        state = State(true, messageId, 0, now(), now() + limits.intervalSeconds * 1_000L); return true
    }
    fun due(limits: RepeatCqLimits, keyerIdle: Boolean): Boolean {
        if (!state.active) return false
        if (state.cycle >= limits.maximumCycles || now() - state.startedAt >= limits.maximumElapsedMinutes * 60_000L) { stop(); return false }
        if (now() < state.nextAt) return false
        state = state.copy(cycle = state.cycle + 1, nextAt = now() + limits.intervalSeconds * 1_000L)
        return keyerIdle
    }
    fun stop() { state = State() }
}
