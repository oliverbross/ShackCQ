// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class QmxFtProtocol(
    val symbolCount: Int,
    val symbolPeriodNanos: Long,
    val maximumTone: Int,
    val slotPeriodMillis: Long,
) {
    FT8(79, 160_000_000L, 7, 15_000L),
    FT4(105, 48_000_000L, 3, 7_500L);

    val toneSpacingHz: Double get() = 1_000_000_000.0 / symbolPeriodNanos
    fun slotIdentity(epochMillis: Long): Long = epochMillis / slotPeriodMillis
}

data class QmxTonePlan(
    val protocol: QmxFtProtocol,
    val slotStartEpochMillis: Long,
    val baseToneHz: Double,
    val symbols: List<Int>,
    val expectedContextGeneration: Long,
    val expectedDeviceDigest: String,
) {
    init {
        require(symbols.size == protocol.symbolCount)
        require(symbols.all { it in 0..protocol.maximumTone })
        require(baseToneHz in 200.0..2_800.0)
        require(expectedDeviceDigest.length >= 16)
    }

    fun digest(): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(protocol.name, slotStartEpochMillis, baseToneHz, symbols.joinToString(","), expectedContextGeneration, expectedDeviceDigest)
            .joinToString("|").toByteArray(),
    ).joinToString("") { "%02x".format(it) }
}

data class QmxDigiAuthorization(
    val txEnabled: Boolean,
    val armed: Boolean,
    val operatorInitiated: Boolean,
    val authorizedPlanDigest: String,
)
data class QmxCommandReceipt(val accepted: Boolean, val txState: QmxTxState = QmxTxState.UNKNOWN)
data class QmxPowerSwrSample(val powerWatts: Double?, val swr: Double?)

interface QmxDigiToneTxPort {
    fun authorization(plan: QmxTonePlan): QmxDigiAuthorization
    fun execute(command: QmxCommand): QmxCommandReceipt
    /** Must return immediately from cached/async meter state; it may not block symbol cadence. */
    fun samplePowerSWR(): QmxPowerSwrSample?
}

enum class QmxCleanupOperation { TONE_OFF, RECEIVE }
interface QmxSafetyPort {
    fun currentContextGeneration(): Long
    fun currentDeviceDigest(): String?
    fun abortRequested(): Boolean
    fun allowCleanupRetry(operation: QmxCleanupOperation): Boolean
}

enum class QmxToneTxResult {
    COMPLETED,
    CAPABILITY_UNAVAILABLE,
    AUTHORIZATION_DENIED,
    SLOT_IDENTITY_MISMATCH,
    TX_UNCONFIRMED,
    ALREADY_TRANSMITTING,
    CONTEXT_CHANGED,
    ROUTE_CHANGED,
    OPERATOR_ABORTED,
    SYMBOL_DEADLINE_MISSED,
    COMMAND_FAILED,
    SWR_TRIPPED,
    RX_UNCONFIRMED,
}

data class QmxToneTxOutcome(
    val result: QmxToneTxResult,
    val symbolsSent: Int,
    val maximumDeadlineSlipNanos: Long,
    val lastPowerWatts: Double? = null,
    val lastSwr: Double? = null,
)

class QmxToneTxBackend(
    private val port: QmxDigiToneTxPort,
    private val safety: QmxSafetyPort,
    private val clock: QmxClock,
    private val swrTripRatio: Double = 3.0,
    private val maximumSymbolSlipNanos: Long = 30_000_000L,
) {
    private val runLock = ReentrantLock()
    @Volatile private var active = false
    @Volatile var swrFault = false
        private set

    init {
        require(swrTripRatio in 1.1..10.0)
        require(maximumSymbolSlipNanos in 1_000_000L..100_000_000L)
    }

    fun execute(plan: QmxTonePlan, capabilities: QmxCapabilities): QmxToneTxOutcome {
        if (!runLock.tryLock()) return outcome(QmxToneTxResult.ALREADY_TRANSMITTING, 0, 0)
        try {
            if (active) return outcome(QmxToneTxResult.ALREADY_TRANSMITTING, 0, 0)
            active = true
            if (capabilities.directToneTx != QmxCapabilityState.SUPPORTED || swrFault)
                return outcome(QmxToneTxResult.CAPABILITY_UNAVAILABLE, 0, 0)
            val authorization = port.authorization(plan)
            if (!authorization.txEnabled || !authorization.armed || !authorization.operatorInitiated || authorization.authorizedPlanDigest != plan.digest())
                return outcome(QmxToneTxResult.AUTHORIZATION_DENIED, 0, 0)
            if (plan.protocol.slotIdentity(clock.wallTimeMillis()) != plan.protocol.slotIdentity(plan.slotStartEpochMillis))
                return outcome(QmxToneTxResult.SLOT_IDENTITY_MISMATCH, 0, 0)
            preflight(plan)?.let { return outcome(it, 0, 0) }

            var result = QmxToneTxResult.COMPLETED
            var sent = 0
            var maximumSlip = 0L
            var lastMeters: QmxPowerSwrSample? = null
            val tx = port.execute(QmxCommandBuilder.transmit())
            if (!tx.accepted || tx.txState != QmxTxState.TX) result = QmxToneTxResult.TX_UNCONFIRMED
            val startNanos = clock.monotonicNanos()
            try {
                if (result == QmxToneTxResult.COMPLETED) {
                    for ((index, symbol) in plan.symbols.withIndex()) {
                        preflight(plan)?.let { result = it; break }
                        val deadline = startNanos + index * plan.protocol.symbolPeriodNanos
                        clock.sleepUntilMonotonic(deadline)
                        val slip = (clock.monotonicNanos() - deadline).coerceAtLeast(0)
                        maximumSlip = maxOf(maximumSlip, slip)
                        if (slip > maximumSymbolSlipNanos) { result = QmxToneTxResult.SYMBOL_DEADLINE_MISSED; break }
                        val frequency = plan.baseToneHz + symbol * plan.protocol.toneSpacingHz
                        if (!port.execute(QmxCommandBuilder.tone(frequency)).accepted) { result = QmxToneTxResult.COMMAND_FAILED; break }
                        sent++
                        if (index % 8 == 0) {
                            lastMeters = port.samplePowerSWR() ?: lastMeters
                            if ((lastMeters?.swr ?: 0.0) >= swrTripRatio) {
                                swrFault = true
                                result = QmxToneTxResult.SWR_TRIPPED
                                break
                            }
                        }
                    }
                    if (result == QmxToneTxResult.COMPLETED) {
                        clock.sleepUntilMonotonic(startNanos + plan.symbols.size * plan.protocol.symbolPeriodNanos)
                    }
                }
            } finally {
                val toneOff = cleanup(QmxCleanupOperation.TONE_OFF, QmxCommandBuilder.toneOff())
                clock.sleepUntilMonotonic(clock.monotonicNanos() + 5_000_000L)
                val receive = cleanup(QmxCleanupOperation.RECEIVE, QmxCommandBuilder.receive())
                if (!toneOff.accepted || !receive.accepted || receive.txState != QmxTxState.RX) result = QmxToneTxResult.RX_UNCONFIRMED
            }
            return QmxToneTxOutcome(result, sent, maximumSlip, lastMeters?.powerWatts, lastMeters?.swr)
        } finally {
            active = false
            runLock.unlock()
        }
    }

    private fun preflight(plan: QmxTonePlan): QmxToneTxResult? = when {
        safety.abortRequested() -> QmxToneTxResult.OPERATOR_ABORTED
        safety.currentContextGeneration() != plan.expectedContextGeneration -> QmxToneTxResult.CONTEXT_CHANGED
        safety.currentDeviceDigest() != plan.expectedDeviceDigest -> QmxToneTxResult.ROUTE_CHANGED
        else -> null
    }

    private fun cleanup(operation: QmxCleanupOperation, command: QmxCommand): QmxCommandReceipt {
        var receipt = port.execute(command)
        if ((!receipt.accepted || (operation == QmxCleanupOperation.RECEIVE && receipt.txState != QmxTxState.RX)) && safety.allowCleanupRetry(operation)) {
            receipt = port.execute(command)
        }
        return receipt
    }

    fun clearSwrFault(operatorConfirmed: Boolean): Boolean {
        if (!operatorConfirmed) return false
        swrFault = false
        return true
    }

    private fun outcome(result: QmxToneTxResult, sent: Int, slip: Long) = QmxToneTxOutcome(result, sent, slip)
}
