package app.shackcq.mobile

import kotlin.math.abs
import kotlin.math.roundToInt

enum class FtExchangeRole { CQ_RUNNER, SEARCH_AND_POUNCE }
enum class FtExchangeState {
    IDLE,
    CQ_TX_PENDING, WAIT_CALLER,
    REPORT_TX_PENDING, WAIT_R_REPORT,
    RR73_TX_PENDING, WAIT_FINAL_73,
    CALL_TX_PENDING, WAIT_REPORT,
    R_REPORT_TX_PENDING, WAIT_RR73,
    FINAL_73_TX_PENDING,
    COMPLETE, FAILED, STOPPED,
}
enum class FtTxMessageKind { CQ, GRID, REPORT, R_REPORT, RR73, FINAL_73 }
enum class FtEngineActionKind { NONE, QUEUE_MESSAGE, COMPLETE_DRAFT, RETRY, RETURN_TO_CQ, FAIL }
enum class DigiTxFailure {
    ENCODER_REJECTED,
    ROUTE_MISSING,
    AUDIO_OWNERSHIP_REFUSED,
    AUDIO_INITIALIZATION_FAILED,
    AUDIO_ROUTE_BIND_FAILED,
    DATA_MODE_REFUSED,
    PTT_REFUSED,
    PTT_UNCONFIRMED,
    AUDIO_WRITE_FAILED,
    RX_UNCONFIRMED,
    FLEX_INTERLOCK_REFUSED,
    FLEX_PTT_UNCONFIRMED,
    TCI_INTERLOCK_REFUSED,
    CLOCK_JUMP,
    CONTEXT_CHANGED,
    CANCELLED,
}

data class DigiTxOutcome(
    val encoded: Boolean,
    val pttConfirmed: Boolean,
    val audioCompleted: Boolean,
    val rxConfirmed: Boolean,
    val failure: DigiTxFailure? = null,
    val detail: String = "",
    val slotStartMillis: Long = 0,
    val pttAttempted: Boolean = pttConfirmed,
) {
    val successful: Boolean
        get() = encoded && pttConfirmed && audioCompleted && rxConfirmed && failure == null

    companion object {
        fun success(slotStartMillis: Long = 0) = DigiTxOutcome(true, true, true, true, slotStartMillis = slotStartMillis)
        fun failed(failure: DigiTxFailure, detail: String = "", encoded: Boolean = true,
            pttConfirmed: Boolean = false, audioCompleted: Boolean = false, rxConfirmed: Boolean = false,
            slotStartMillis: Long = 0, pttAttempted: Boolean = pttConfirmed) = DigiTxOutcome(
            encoded, pttConfirmed, audioCompleted, rxConfirmed, failure, detail, slotStartMillis, pttAttempted,
        )
    }
}

data class FtDecodeInput(
    val message: DigiFtMessage,
    val snr: Float,
    val slotStartMillis: Long,
    val mode: String,
    val dialFrequencyHz: Long,
    val sessionId: String,
)

data class FtExchangeSnapshot(
    val state: FtExchangeState = FtExchangeState.IDLE,
    val role: FtExchangeRole? = null,
    val lockedDisplayCall: String = "",
    val lockedBaseCall: String = "",
    val remoteGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val selectedRxSlotMillis: Long = 0,
    val selectedRxParity: Int = -1,
    val operatorTxParity: Int = 0,
    val lastTransmittedKind: FtTxMessageKind? = null,
    val expectedIncoming: Set<FtMessageKind> = emptySet(),
    val retryCount: Int = 0,
    val retryLimit: Int = 3,
    val lastActivityEpoch: Long = 0,
    val startedEpoch: Long = 0,
    val completionReason: String = "",
    val mode: String = "",
    val dialFrequencyHz: Long = 0,
    val sessionId: String = "",
    val lastTxSlotMillis: Long = 0,
    val cqTransmissions: Int = 0,
    val autoCq: Boolean = false,
    val autoCqLimit: Int = 3,
) {
    val displayCall: String get() = lockedDisplayCall
    val lockedCall: String get() = lockedBaseCall
    val retries: Int get() = retryCount
    val completeDraftEligible: Boolean
        get() = state == FtExchangeState.COMPLETE && lockedBaseCall.isNotBlank() &&
            sentReport.isNotBlank() && receivedReport.isNotBlank() && mode.isNotBlank() &&
            dialFrequencyHz > 0 && startedEpoch > 0
}

data class FtEngineAction(
    val kind: FtEngineActionKind = FtEngineActionKind.NONE,
    val messageKind: FtTxMessageKind? = null,
    val message: String = "",
    val reason: String = "",
)

fun formatFtReport(snr: Float): String = "%+03d".format(snr.roundToInt().coerceIn(-50, 49))

class DigiFtExchangeEngine(
    private val myCall: () -> String,
    private val myGrid: () -> String,
) {
    var snapshot = FtExchangeSnapshot()
        private set
    private val acceptedDecodeKeys = mutableSetOf<String>()

    fun operatorStartCq(
        mode: String,
        dialFrequencyHz: Long,
        sessionId: String,
        nowEpoch: Long,
        txParity: Int,
        retryLimit: Int,
        autoCq: Boolean,
        autoCqLimit: Int,
    ): FtEngineAction {
        acceptedDecodeKeys.clear()
        snapshot = FtExchangeSnapshot(
            state = FtExchangeState.CQ_TX_PENDING,
            role = FtExchangeRole.CQ_RUNNER,
            operatorTxParity = txParity.coerceIn(0, 1),
            retryLimit = retryLimit.coerceIn(0, 10),
            lastActivityEpoch = nowEpoch,
            startedEpoch = nowEpoch,
            mode = mode,
            dialFrequencyHz = dialFrequencyHz,
            sessionId = sessionId,
            autoCq = autoCq,
            autoCqLimit = autoCqLimit.coerceIn(1, 20),
        )
        return action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.CQ)
    }

    fun operatorCallSelected(
        displayCall: String,
        remoteGrid: String,
        decodedSnr: Float,
        selectedSlotStartMillis: Long,
        periodMillis: Long,
        mode: String,
        dialFrequencyHz: Long,
        sessionId: String,
        nowEpoch: Long,
        retryLimit: Int,
    ): FtEngineAction {
        acceptedDecodeKeys.clear()
        val rxParity = slotParity(selectedSlotStartMillis, periodMillis)
        snapshot = FtExchangeSnapshot(
            state = FtExchangeState.CALL_TX_PENDING,
            role = FtExchangeRole.SEARCH_AND_POUNCE,
            lockedDisplayCall = displayCall,
            lockedBaseCall = DigiFtParser.baseCall(displayCall),
            remoteGrid = remoteGrid,
            sentReport = formatFtReport(decodedSnr),
            selectedRxSlotMillis = selectedSlotStartMillis,
            selectedRxParity = rxParity,
            operatorTxParity = 1 - rxParity,
            retryLimit = retryLimit.coerceIn(0, 10),
            lastActivityEpoch = nowEpoch,
            startedEpoch = nowEpoch,
            mode = mode,
            dialFrequencyHz = dialFrequencyHz,
            sessionId = sessionId,
        )
        return action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.GRID)
    }

    fun decoded(input: FtDecodeInput, nowEpoch: Long): FtEngineAction {
        if (!contextMatches(input)) return FtEngineAction()
        val key = "${input.slotStartMillis}|${input.message.raw.trim().uppercase()}"
        if (!acceptedDecodeKeys.add(key)) return FtEngineAction()
        if (slotParity(input.slotStartMillis, periodForMode()) == snapshot.operatorTxParity) return FtEngineAction()
        if (snapshot.lastTxSlotMillis > 0 && input.slotStartMillis <= snapshot.lastTxSlotMillis) return FtEngineAction()

        val message = input.message
        val mine = DigiFtParser.baseCall(myCall())
        val remote = DigiFtParser.baseCall(message.from)
        if (DigiFtParser.baseCall(message.to) != mine || remote.isBlank()) return FtEngineAction()

        return when (snapshot.state) {
            FtExchangeState.WAIT_CALLER -> {
                if (message.kind !in setOf(FtMessageKind.GRID, FtMessageKind.REPORT)) return FtEngineAction()
                snapshot = snapshot.copy(
                    state = FtExchangeState.REPORT_TX_PENDING,
                    lockedDisplayCall = message.from,
                    lockedBaseCall = remote,
                    remoteGrid = message.grid.ifBlank { snapshot.remoteGrid },
                    sentReport = formatFtReport(input.snr),
                    receivedReport = message.report.ifBlank { snapshot.receivedReport },
                    selectedRxSlotMillis = input.slotStartMillis,
                    selectedRxParity = slotParity(input.slotStartMillis, periodForMode()),
                    expectedIncoming = emptySet(),
                    retryCount = 0,
                    lastActivityEpoch = nowEpoch,
                )
                action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.REPORT)
            }
            FtExchangeState.WAIT_REPORT -> {
                if (!lockedRemote(remote, message) || message.kind != FtMessageKind.REPORT) return FtEngineAction()
                snapshot = snapshot.copy(
                    state = FtExchangeState.R_REPORT_TX_PENDING,
                    remoteGrid = message.grid.ifBlank { snapshot.remoteGrid },
                    receivedReport = message.report,
                    expectedIncoming = emptySet(),
                    retryCount = 0,
                    lastActivityEpoch = nowEpoch,
                )
                action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.R_REPORT)
            }
            FtExchangeState.WAIT_R_REPORT -> {
                if (!lockedRemote(remote, message) || message.kind != FtMessageKind.R_REPORT) return FtEngineAction()
                snapshot = snapshot.copy(
                    state = FtExchangeState.RR73_TX_PENDING,
                    receivedReport = message.report.removePrefix("R"),
                    expectedIncoming = emptySet(),
                    retryCount = 0,
                    lastActivityEpoch = nowEpoch,
                )
                action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.RR73)
            }
            FtExchangeState.WAIT_RR73 -> {
                if (!lockedRemote(remote, message) || message.kind !in setOf(FtMessageKind.RR73, FtMessageKind.RRR)) return FtEngineAction()
                snapshot = snapshot.copy(
                    state = FtExchangeState.FINAL_73_TX_PENDING,
                    expectedIncoming = emptySet(),
                    retryCount = 0,
                    lastActivityEpoch = nowEpoch,
                )
                action(FtEngineActionKind.QUEUE_MESSAGE, FtTxMessageKind.FINAL_73)
            }
            FtExchangeState.WAIT_FINAL_73 -> {
                if (!lockedRemote(remote, message) || message.kind != FtMessageKind.FINAL_73) return FtEngineAction()
                snapshot = snapshot.copy(
                    state = FtExchangeState.COMPLETE,
                    completionReason = "Standard exchange complete",
                    expectedIncoming = emptySet(),
                    retryCount = 0,
                    lastActivityEpoch = nowEpoch,
                )
                FtEngineAction(FtEngineActionKind.COMPLETE_DRAFT)
            }
            else -> FtEngineAction()
        }
    }

    fun txOutcome(outcome: DigiTxOutcome, nowEpoch: Long): FtEngineAction {
        if (!outcome.successful) {
            snapshot = snapshot.copy(
                state = FtExchangeState.FAILED,
                completionReason = outcome.detail.ifBlank { outcome.failure?.name.orEmpty() },
                expectedIncoming = emptySet(),
                lastActivityEpoch = nowEpoch,
            )
            return FtEngineAction(FtEngineActionKind.FAIL, reason = snapshot.completionReason)
        }
        val kind = pendingKind() ?: return FtEngineAction()
        val next = when (snapshot.state) {
            FtExchangeState.CQ_TX_PENDING -> FtExchangeState.WAIT_CALLER
            FtExchangeState.CALL_TX_PENDING -> FtExchangeState.WAIT_REPORT
            FtExchangeState.REPORT_TX_PENDING -> FtExchangeState.WAIT_R_REPORT
            FtExchangeState.R_REPORT_TX_PENDING -> FtExchangeState.WAIT_RR73
            FtExchangeState.RR73_TX_PENDING -> FtExchangeState.WAIT_FINAL_73
            FtExchangeState.FINAL_73_TX_PENDING -> FtExchangeState.COMPLETE
            else -> return FtEngineAction()
        }
        snapshot = snapshot.copy(
            state = next,
            lastTransmittedKind = kind,
            expectedIncoming = expectedFor(next),
            lastActivityEpoch = nowEpoch,
            lastTxSlotMillis = outcome.slotStartMillis,
            cqTransmissions = snapshot.cqTransmissions + if (kind == FtTxMessageKind.CQ) 1 else 0,
        )
        return if (next == FtExchangeState.COMPLETE) {
            snapshot = snapshot.copy(completionReason = "Standard exchange complete")
            FtEngineAction(FtEngineActionKind.COMPLETE_DRAFT)
        } else FtEngineAction()
    }

    fun timeout(nowEpoch: Long): FtEngineAction {
        if (snapshot.state == FtExchangeState.WAIT_CALLER) {
            if (snapshot.autoCq && snapshot.cqTransmissions < snapshot.autoCqLimit) {
                snapshot = snapshot.copy(state = FtExchangeState.CQ_TX_PENDING, lastActivityEpoch = nowEpoch)
                return action(FtEngineActionKind.RETRY, FtTxMessageKind.CQ)
            }
            snapshot = snapshot.copy(state = FtExchangeState.STOPPED, completionReason = "Unanswered CQ limit reached",
                expectedIncoming = emptySet(), lastActivityEpoch = nowEpoch)
            return FtEngineAction(FtEngineActionKind.FAIL, reason = snapshot.completionReason)
        }
        val kind = snapshot.lastTransmittedKind ?: return FtEngineAction()
        if (snapshot.state !in setOf(FtExchangeState.WAIT_REPORT, FtExchangeState.WAIT_R_REPORT,
                FtExchangeState.WAIT_RR73, FtExchangeState.WAIT_FINAL_73)) return FtEngineAction()
        if (snapshot.retryCount >= snapshot.retryLimit) {
            snapshot = snapshot.copy(state = FtExchangeState.FAILED, completionReason = "Retry limit reached",
                expectedIncoming = emptySet(), lastActivityEpoch = nowEpoch)
            return FtEngineAction(FtEngineActionKind.FAIL, reason = snapshot.completionReason)
        }
        snapshot = snapshot.copy(state = pendingState(kind), retryCount = snapshot.retryCount + 1,
            expectedIncoming = emptySet(), lastActivityEpoch = nowEpoch)
        return action(FtEngineActionKind.RETRY, kind)
    }

    fun returnToCqAfterCompletion(nowEpoch: Long): FtEngineAction {
        if (snapshot.state != FtExchangeState.COMPLETE || snapshot.role != FtExchangeRole.CQ_RUNNER || !snapshot.autoCq) {
            return FtEngineAction()
        }
        acceptedDecodeKeys.clear()
        snapshot = snapshot.copy(
            state = FtExchangeState.CQ_TX_PENDING,
            lockedDisplayCall = "",
            lockedBaseCall = "",
            remoteGrid = "",
            sentReport = "",
            receivedReport = "",
            selectedRxSlotMillis = 0,
            selectedRxParity = -1,
            lastTransmittedKind = null,
            expectedIncoming = emptySet(),
            retryCount = 0,
            lastActivityEpoch = nowEpoch,
            startedEpoch = nowEpoch,
            completionReason = "",
            lastTxSlotMillis = 0,
            cqTransmissions = 0,
        )
        return action(FtEngineActionKind.RETURN_TO_CQ, FtTxMessageKind.CQ)
    }

    fun stop(reason: String = "Stopped by operator") {
        snapshot = snapshot.copy(state = FtExchangeState.STOPPED, completionReason = reason, expectedIncoming = emptySet())
    }

    private fun contextMatches(input: FtDecodeInput): Boolean =
        input.mode == snapshot.mode && input.dialFrequencyHz == snapshot.dialFrequencyHz &&
            input.sessionId == snapshot.sessionId && input.slotStartMillis > 0

    private fun lockedRemote(remote: String, message: DigiFtMessage): Boolean =
        snapshot.lockedBaseCall.isNotBlank() && remote == snapshot.lockedBaseCall &&
            DigiFtParser.baseCall(message.to) == DigiFtParser.baseCall(myCall())

    private fun action(kind: FtEngineActionKind, messageKind: FtTxMessageKind): FtEngineAction =
        FtEngineAction(kind, messageKind, messageFor(messageKind))

    private fun messageFor(kind: FtTxMessageKind): String {
        val mine = myCall().trim().uppercase()
        val grid = myGrid().trim().uppercase().take(4)
        val target = snapshot.lockedDisplayCall.ifBlank { snapshot.lockedBaseCall }
        return when (kind) {
            FtTxMessageKind.CQ -> listOf("CQ", mine, grid).filter(String::isNotBlank).joinToString(" ")
            FtTxMessageKind.GRID -> listOf(target, mine, grid).filter(String::isNotBlank).joinToString(" ")
            FtTxMessageKind.REPORT -> "$target $mine ${snapshot.sentReport}"
            FtTxMessageKind.R_REPORT -> "$target $mine R${snapshot.sentReport}"
            FtTxMessageKind.RR73 -> "$target $mine RR73"
            FtTxMessageKind.FINAL_73 -> "$target $mine 73"
        }.trim()
    }

    private fun pendingKind(): FtTxMessageKind? = when (snapshot.state) {
        FtExchangeState.CQ_TX_PENDING -> FtTxMessageKind.CQ
        FtExchangeState.CALL_TX_PENDING -> FtTxMessageKind.GRID
        FtExchangeState.REPORT_TX_PENDING -> FtTxMessageKind.REPORT
        FtExchangeState.R_REPORT_TX_PENDING -> FtTxMessageKind.R_REPORT
        FtExchangeState.RR73_TX_PENDING -> FtTxMessageKind.RR73
        FtExchangeState.FINAL_73_TX_PENDING -> FtTxMessageKind.FINAL_73
        else -> null
    }

    private fun pendingState(kind: FtTxMessageKind): FtExchangeState = when (kind) {
        FtTxMessageKind.CQ -> FtExchangeState.CQ_TX_PENDING
        FtTxMessageKind.GRID -> FtExchangeState.CALL_TX_PENDING
        FtTxMessageKind.REPORT -> FtExchangeState.REPORT_TX_PENDING
        FtTxMessageKind.R_REPORT -> FtExchangeState.R_REPORT_TX_PENDING
        FtTxMessageKind.RR73 -> FtExchangeState.RR73_TX_PENDING
        FtTxMessageKind.FINAL_73 -> FtExchangeState.FINAL_73_TX_PENDING
    }

    private fun expectedFor(state: FtExchangeState): Set<FtMessageKind> = when (state) {
        FtExchangeState.WAIT_CALLER -> setOf(FtMessageKind.GRID, FtMessageKind.REPORT)
        FtExchangeState.WAIT_REPORT -> setOf(FtMessageKind.REPORT)
        FtExchangeState.WAIT_R_REPORT -> setOf(FtMessageKind.R_REPORT)
        FtExchangeState.WAIT_RR73 -> setOf(FtMessageKind.RRR, FtMessageKind.RR73)
        FtExchangeState.WAIT_FINAL_73 -> setOf(FtMessageKind.FINAL_73)
        else -> emptySet()
    }

    private fun periodForMode(): Long = when (snapshot.mode) {
        DigiMode.FT4.name -> 7_500L
        else -> 15_000L
    }
}

data class FtSlotPlan(
    val periodMillis: Long,
    val desiredParity: Int,
    val createdWallMillis: Long,
    val createdMonotonicMillis: Long,
    val targetWallSlotStartMillis: Long,
    val monotonicDelayMillis: Long,
    val slotIndex: Long,
    val parity: Int,
    val lateStart: Boolean,
    val allowedLateStartMillis: Long,
) {
    val targetMonotonicMillis: Long get() = createdMonotonicMillis + monotonicDelayMillis

    fun remainsValid(wallMillis: Long, monotonicMillis: Long, clockJumpToleranceMillis: Long = 250): Boolean {
        val expectedWall = createdWallMillis + (monotonicMillis - createdMonotonicMillis)
        val clockStable = abs(wallMillis - expectedWall) <= clockJumpToleranceMillis
        val stillInWindow = wallMillis <= targetWallSlotStartMillis + allowedLateStartMillis
        return clockStable && stillInWindow && parity == desiredParity &&
            slotParity(targetWallSlotStartMillis, periodMillis) == desiredParity
    }
}

object FtRuntimeWait {
    fun remainingMillis(plan: FtSlotPlan, monotonicMillis: Long): Long =
        (plan.targetMonotonicMillis - monotonicMillis).coerceAtLeast(0)

    fun wallClockStable(plan: FtSlotPlan, wallMillis: Long, monotonicMillis: Long, toleranceMillis: Long = 250): Boolean {
        val expectedWall = plan.createdWallMillis + (monotonicMillis - plan.createdMonotonicMillis)
        return abs(wallMillis - expectedWall) <= toleranceMillis
    }
}

data class DigiPostTxDecision(val resumeDecoder: Boolean, val latchRxUnconfirmed: Boolean)

fun decidePostTxRecovery(priorRxActive: Boolean, outcome: DigiTxOutcome, pttMayHaveOccurred: Boolean): DigiPostTxDecision {
    val uncertainRadioState = !outcome.rxConfirmed && (outcome.pttAttempted || pttMayHaveOccurred)
    return DigiPostTxDecision(
        resumeDecoder = priorRxActive && (outcome.rxConfirmed || (!outcome.pttAttempted && !pttMayHaveOccurred)),
        latchRxUnconfirmed = uncertainRadioState,
    )
}

fun flexStateConfirmsReceive(state: FlexTxState): Boolean =
    state in setOf(FlexTxState.DISABLED, FlexTxState.READY, FlexTxState.ARMED)

fun flexPostStopOutcome(state: FlexTxState, slotStartMillis: Long): DigiTxOutcome =
    if (flexStateConfirmsReceive(state)) DigiTxOutcome.success(slotStartMillis)
    else DigiTxOutcome.failed(DigiTxFailure.RX_UNCONFIRMED,
        "Flex post-stop state is $state", pttConfirmed = true, audioCompleted = true,
        slotStartMillis = slotStartMillis, pttAttempted = true)

fun phaseAfterRxRecheck(confirmed: Boolean): DigiTxPhase =
    if (confirmed) DigiTxPhase.SAFE else DigiTxPhase.RX_UNCONFIRMED

fun shouldQueueFtAction(autoSequence: Boolean, txEnabled: Boolean, messageKind: FtTxMessageKind?, message: String): Boolean =
    autoSequence && txEnabled && messageKind != null && message.isNotBlank()

fun effectiveAutoCq(autoSequence: Boolean, requestedAutoCq: Boolean): Boolean = autoSequence && requestedAutoCq

fun wsjtMillisSinceMidnight(slotStartMillis: Long): Int =
    Math.floorMod(slotStartMillis, 86_400_000L).toInt()

object FtSlotScheduler {
    fun plan(
        periodMillis: Long,
        desiredParity: Int,
        wallMillis: Long,
        monotonicMillis: Long,
        allowedLateStartMillis: Long,
    ): FtSlotPlan {
        require(periodMillis > 0)
        val parity = desiredParity.coerceIn(0, 1)
        val currentIndex = Math.floorDiv(wallMillis, periodMillis)
        val currentStart = currentIndex * periodMillis
        val elapsed = wallMillis - currentStart
        val currentMatches = currentIndex.mod(2) == parity
        val targetIndex = if (currentMatches && elapsed <= allowedLateStartMillis) currentIndex else {
            var candidate = currentIndex + 1
            if (candidate.mod(2) != parity) candidate++
            candidate
        }
        val targetStart = targetIndex * periodMillis
        return FtSlotPlan(
            periodMillis, parity, wallMillis, monotonicMillis, targetStart,
            (targetStart - wallMillis).coerceAtLeast(0), targetIndex, targetIndex.mod(2),
            targetIndex == currentIndex && elapsed > 0, allowedLateStartMillis,
        )
    }
}

fun slotParity(slotStartMillis: Long, periodMillis: Long): Int =
    Math.floorDiv(slotStartMillis, periodMillis).mod(2)
