package app.shackcq.mobile.contest

enum class ContestEsmState { EMPTY, CALL_ENTERED, CALL_VALID, EXCHANGE_ENTERED, READY_TO_LOG, DUPE_REVIEW }
sealed interface ContestEsmEvent {
    data object EnterPressed : ContestEsmEvent
    data object SpacePressed : ContestEsmEvent
    data class CallChanged(val value: String) : ContestEsmEvent
    data class CallValidated(val dupe: ContestDupeState) : ContestEsmEvent
    data class ExchangeChanged(val value: String) : ContestEsmEvent
    data class ExchangeValidated(val truth: ContestTruth) : ContestEsmEvent
    data object QsoLogged : ContestEsmEvent
    data class RoleChanged(val role: ContestOperatingRole) : ContestEsmEvent
    data object EscapePressed : ContestEsmEvent
    data class FocusChanged(val field: String) : ContestEsmEvent
}
enum class ContestKeyerIntentType { CQ, MY_CALL, SEND_EXCHANGE, SEND_REPORT, TU, AGN, NR, CALL_AGAIN, STOP, CUSTOM_FUNCTION_KEY }
data class ContestKeyerIntent(
    val type: ContestKeyerIntentType,
    val sessionId: ContestSessionId,
    val ruleId: ContestDefinitionId,
    val role: ContestOperatingRole,
    val mode: ContestMode,
    val expectedContextGeneration: Long,
    val templateVariables: Map<String, String>,
    val loggingAllowedAfterCompletion: Boolean,
)
sealed interface ContestEsmAction {
    data object FocusCall : ContestEsmAction
    data object FocusExchange : ContestEsmAction
    data object SuggestLog : ContestEsmAction
    data object LogQso : ContestEsmAction
    data class EmitKeyerIntent(val intent: ContestKeyerIntent) : ContestEsmAction
    data class ShowDupe(val reason: String) : ContestEsmAction
    data class ShowValidation(val reason: String) : ContestEsmAction
    data object NoOp : ContestEsmAction
}
data class ContestEsmSnapshot(val state: ContestEsmState = ContestEsmState.EMPTY, val role: ContestOperatingRole = ContestOperatingRole.RUN, val call: String = "", val exchange: String = "")
data class ContestEsmTransition(val snapshot: ContestEsmSnapshot, val actions: List<ContestEsmAction>)

class ContestEsmEngine {
    fun transition(current: ContestEsmSnapshot, event: ContestEsmEvent, session: ContestSession, definition: ContestDefinition, contextGeneration: Long): ContestEsmTransition {
        fun intent(type: ContestKeyerIntentType, logAfter: Boolean = false) = ContestEsmAction.EmitKeyerIntent(ContestKeyerIntent(type, session.id, definition.id, current.role, definition.mode,
            contextGeneration, mapOf("CALL" to current.call, "EXCHANGE" to current.exchange), logAfter))
        return when (event) {
            is ContestEsmEvent.CallChanged -> ContestEsmTransition(current.copy(state=if(event.value.isBlank()) ContestEsmState.EMPTY else ContestEsmState.CALL_ENTERED, call=event.value), emptyList())
            is ContestEsmEvent.CallValidated -> if (event.dupe == ContestDupeState.DUPLICATE)
                ContestEsmTransition(current.copy(state=ContestEsmState.DUPE_REVIEW), listOf(ContestEsmAction.ShowDupe("Duplicate requires explicit review")))
            else ContestEsmTransition(current.copy(state=ContestEsmState.CALL_VALID), listOf(ContestEsmAction.FocusExchange))
            is ContestEsmEvent.ExchangeChanged -> ContestEsmTransition(current.copy(state=ContestEsmState.EXCHANGE_ENTERED, exchange=event.value), emptyList())
            is ContestEsmEvent.ExchangeValidated -> if (event.truth == ContestTruth.VALID) ContestEsmTransition(current.copy(state=ContestEsmState.READY_TO_LOG), listOf(ContestEsmAction.SuggestLog))
                else ContestEsmTransition(current, listOf(ContestEsmAction.ShowValidation("Exchange is not valid")))
            ContestEsmEvent.EnterPressed -> when (current.state) {
                ContestEsmState.EMPTY -> ContestEsmTransition(current, listOf(intent(if (current.role == ContestOperatingRole.RUN) ContestKeyerIntentType.CQ else ContestKeyerIntentType.MY_CALL)))
                ContestEsmState.CALL_VALID -> ContestEsmTransition(current, listOf(intent(ContestKeyerIntentType.SEND_EXCHANGE), ContestEsmAction.FocusExchange))
                ContestEsmState.READY_TO_LOG -> ContestEsmTransition(current, listOf(ContestEsmAction.LogQso))
                ContestEsmState.DUPE_REVIEW -> ContestEsmTransition(current, listOf(ContestEsmAction.ShowDupe("Explicit override is required")))
                else -> ContestEsmTransition(current, listOf(ContestEsmAction.NoOp))
            }
            ContestEsmEvent.SpacePressed -> ContestEsmTransition(current, listOf(if (current.state == ContestEsmState.CALL_VALID) ContestEsmAction.FocusExchange else ContestEsmAction.NoOp))
            ContestEsmEvent.QsoLogged -> ContestEsmTransition(ContestEsmSnapshot(role=current.role), listOf(intent(ContestKeyerIntentType.TU), ContestEsmAction.FocusCall))
            is ContestEsmEvent.RoleChanged -> ContestEsmTransition(ContestEsmSnapshot(role=event.role), listOf(ContestEsmAction.FocusCall))
            ContestEsmEvent.EscapePressed -> ContestEsmTransition(ContestEsmSnapshot(role=current.role), listOf(intent(ContestKeyerIntentType.STOP), ContestEsmAction.FocusCall))
            is ContestEsmEvent.FocusChanged -> ContestEsmTransition(current, listOf(ContestEsmAction.NoOp))
        }
    }
}
