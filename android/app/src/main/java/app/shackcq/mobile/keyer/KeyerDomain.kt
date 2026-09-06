package app.shackcq.mobile.keyer

@JvmInline value class KeyerProfileId(val value: String) { init { require(value.isNotBlank()) } }

enum class KeyerOperatingRole { GENERAL, PORTABLE_RUN, PORTABLE_SEARCH, CONTEST_RUN, CONTEST_S_AND_P }
enum class KeyerMode { CW, VOICE }
enum class KeyerQueueState { IDLE, VALIDATING, PENDING, ACTIVE, STOPPING, FAILED, COMPLETED }
enum class KeyerStopReason { Operator, ContextChanged, Background, Disconnected, ModeChanged, ProfileChanged, RouteLost, Disarmed, UnsafeState }

data class KeyChord(val function: Int, val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false) {
    init { require(function in 1..12) }
    val label: String get() = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append("F$function")
    }
}

sealed interface KeyerAction {
    val messageId: String?
    data class SendMessage(override val messageId: String) : KeyerAction
    data class IncreaseWpm(val step: Int = 1) : KeyerAction { override val messageId: String? = null }
    data class DecreaseWpm(val step: Int = 1) : KeyerAction { override val messageId: String? = null }
    data class SetWpm(val value: Int) : KeyerAction { override val messageId: String? = null }
    data object Stop : KeyerAction { override val messageId: String? = null }
}

data class KeyerBinding(val chord: KeyChord, val action: KeyerAction)
data class KeyerMessageTemplate(
    val id: String,
    val label: String,
    val mode: KeyerMode,
    val template: String = "",
    val voicePlan: VoiceMacroPlan? = null,
)

data class VoiceMacroPlan(
    val slotIds: List<Int>,
    val interClipSilenceMillis: Int = 80,
) {
    init {
        require(slotIds.isNotEmpty() && slotIds.size <= 12) { "A voice plan requires 1 through 12 slots" }
        require(slotIds.all { it in 0..5 }) { "Voice plan slot is out of range" }
        require(interClipSilenceMillis in 0..500) { "Inter-clip silence must be 0 through 500 ms" }
    }
}

data class KeyerProfile(
    val id: KeyerProfileId,
    val name: String,
    val role: KeyerOperatingRole,
    val mode: KeyerMode,
    val messages: List<KeyerMessageTemplate>,
    val bindings: List<KeyerBinding> = emptyList(),
) { init { require(messages.size <= 12) } }

data class KeyerContextSnapshot(
    val operatingGeneration: Long,
    val foregroundGeneration: Long,
    val radioIdentity: String,
    val connected: Boolean,
    val foreground: Boolean,
    val mode: KeyerMode,
    val profileId: KeyerProfileId,
    val modeSupported: Boolean = true,
    val role: KeyerOperatingRole = KeyerOperatingRole.GENERAL,
    val myCall: String = "",
    val call: String = "",
    val rst: String = "",
    val rstSent: String = "",
    val rstRecv: String = "",
    val serial: String = "",
    val exchange: String = "",
    val grid: String = "",
    val reference: String = "",
    val band: String = "",
)

data class KeyerQueueItem(
    val action: KeyerAction,
    val messageId: String?,
    val label: String,
    val context: KeyerContextSnapshot,
    val submittedAtMillis: Long,
    val expiresAtMillis: Long,
)

data class KeyerQueueSnapshot(
    val state: KeyerQueueState = KeyerQueueState.IDLE,
    val active: KeyerQueueItem? = null,
    val pending: KeyerQueueItem? = null,
    val reason: String = "",
) { val pendingCount: Int get() = if (pending == null) 0 else 1 }

sealed interface KeyerDispatchResult {
    data class Accepted(val queued: Boolean) : KeyerDispatchResult
    data class Rejected(val reason: KeyerFailureReason, val detail: String) : KeyerDispatchResult
}

enum class KeyerFailureReason {
    HotkeysDisabled, NoBinding, TextInputFocused, AppNotForeground, ContextChanged, ProfileChanged,
    ModeUnsupported, RadioDisconnected, NotArmed, AudioBusy, AudioRouteMissing, VoiceClipMissing,
    VoiceClipInvalid, PlanTooLong, CwTextInvalid, BackendCapacityExceeded, BackendUnavailable,
    AlreadyActive, QueueFull, QueueExpired, StoppedByOperator, RxRecoveryFailed, BindingConflict,
}

data class KeyerAvailability(val available: Boolean, val reason: KeyerFailureReason? = null, val detail: String = "")

interface KeyerDispatchPort {
    fun availability(context: KeyerContextSnapshot): KeyerAvailability
    fun submit(action: KeyerAction, context: KeyerContextSnapshot): KeyerDispatchResult
    fun stop(reason: KeyerStopReason = KeyerStopReason.Operator)
    fun snapshot(): KeyerQueueSnapshot
}

data class ContestKeyerIntentAdapterInput(
    val messageId: String,
    val role: KeyerOperatingRole,
    val mode: KeyerMode,
    val contextGeneration: Long,
)

interface CwKeyerBackend { val capability: String }
object UnavailableWinKeyerBackend : CwKeyerBackend { override val capability = "NOT IMPLEMENTED" }

data class SerialFormat(val minimumWidth: Int = 1, val leadingZeroes: Boolean = false, val cutNumbers: Boolean = false) {
    init { require(minimumWidth in 1..12) }
    fun format(value: Long): String {
        require(value >= 0)
        val plain = value.toString().let { if (leadingZeroes) it.padStart(minimumWidth, '0') else it }
        return if (!cutNumbers) plain else plain.map { when (it) { '0' -> 'T'; '1' -> 'A'; '9' -> 'N'; else -> it } }.joinToString("")
    }
}
