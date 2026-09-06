// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

enum class DebugTciTxScenario {
    PTT_SUCCESS, PTT_DELAY, PTT_FAILURE, TUNE, HIGH_SWR, HIGH_ALC,
    DISCONNECT_DURING_TX, RX_RECOVERY_SUCCESS, RX_RECOVERY_FAILURE, SPLIT,
    FT8, FT4, VOICE, CW_KEYER, GLOBAL_STOP,
}

class DebugTciTransmitter(private val authority: TciTransmitAuthority) {
    val evidenceLabel: String = "DEMO · NO RADIO"
    private val adapter = FakeAdapter()
    var scenario: DebugTciTxScenario = DebugTciTxScenario.PTT_SUCCESS; private set

    fun start() {
        check(BuildConfig.DEBUG)
        adapter.reset()
        authority.attach("debug.tci.v5", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED,
            null, adapter, TciTxSettings(diagnostics = true, monitorEnabled = true, maxTuneDurationMillis = 1_000))
    }

    fun stop() { authority.globalStop("DEBUG_LAB_STOP"); authority.detach("DEBUG_LAB_STOP") }

    fun select(value: DebugTciTxScenario) { check(BuildConfig.DEBUG); scenario = value; adapter.reset() }

    suspend fun transmit(): Boolean {
        val mode = when (scenario) {
            DebugTciTxScenario.FT4 -> "FT4"
            DebugTciTxScenario.VOICE -> "USB"
            DebugTciTxScenario.CW_KEYER -> "CW"
            else -> "FT8"
        }
        val source = when (scenario) {
            DebugTciTxScenario.VOICE -> TciTxSource.VOICE_MACRO
            DebugTciTxScenario.CW_KEYER -> TciTxSource.CW_AUDIO_KEYER
            else -> TciTxSource.DEBUG_BENCH
        }
        val samples = FloatArray(4_800) { index -> (sin(2.0 * PI * 1_000.0 * index / 48_000.0) * .35).toFloat() }
        return authority.transmit(TciTxIntent("Debug Lab", source, mode, samples, 48_000, 0,
            expectedFrequencyHz = if (scenario == DebugTciTxScenario.SPLIT) 14_076_000 else 14_074_000))
    }

    suspend fun tune(): Boolean = authority.tune("Debug Lab", 0)

    private inner class FakeAdapter : TciTransmitAdapter {
        override val deviceIdentity = "debug:no-radio|fixture-v5"
        override val isDemoNoRadio = true
        private val sequence = AtomicLong()
        private var connected = true
        private var transmitting = false
        private var tuning = false
        private var delayedPttAt = 0L
        private var binaryFrames = 0L

        fun reset() {
            connected = true; transmitting = false; tuning = false; delayedPttAt = 0; binaryFrames = 0
            authority.publishReadback(readback())
        }

        override fun readback(): TciTxReadback {
            if (delayedPttAt > 0 && System.currentTimeMillis() >= delayedPttAt) { transmitting = true; delayedPttAt = 0 }
            val active = transmitting || tuning
            return TciTxReadback(
                connected = connected, transmitting = transmitting, tuning = tuning, txEnabled = true,
                receiver = 0, rxFrequencyHz = 14_074_000,
                txFrequencyHz = if (scenario == DebugTciTxScenario.SPLIT) 14_076_000 else 14_074_000,
                txVfo = if (scenario == DebugTciTxScenario.SPLIT) "B" else "A",
                split = scenario == DebugTciTxScenario.SPLIT, xitEnabled = false, xitOffsetHz = 0,
                mode = when (scenario) { DebugTciTxScenario.VOICE -> "USB"; DebugTciTxScenario.CW_KEYER -> "CW"; else -> "DIGU" },
                drivePercent = 20, tuneDrivePercent = 5,
                forwardPowerWatts = if (active) 12.0 else 0.0, peakPowerWatts = if (active) 13.0 else 0.0,
                reflectedPowerWatts = if (scenario == DebugTciTxScenario.HIGH_SWR && active) 5.0 else 0.2,
                swr = if (scenario == DebugTciTxScenario.HIGH_SWR && active) 5.2 else 1.2,
                alc = if (scenario == DebugTciTxScenario.HIGH_ALC && active) 1.0 else .25,
                txAudioRate = 48_000, monitorEnabled = true, sourceAgeMillis = 0,
                sequence = sequence.incrementAndGet(),
            )
        }

        override fun sendText(command: String): Boolean {
            if (!connected) return false
            if (command.contains("trx:0,true")) when (scenario) {
                DebugTciTxScenario.PTT_FAILURE -> Unit
                DebugTciTxScenario.PTT_DELAY -> delayedPttAt = System.currentTimeMillis() + 500
                else -> transmitting = true
            }
            if (command.contains("tune:0,true")) tuning = scenario != DebugTciTxScenario.PTT_FAILURE
            if (command.contains("trx:0,false")) {
                delayedPttAt = 0
                if (scenario != DebugTciTxScenario.RX_RECOVERY_FAILURE) transmitting = false
            }
            if (command.contains("tune:0,false") && scenario != DebugTciTxScenario.RX_RECOVERY_FAILURE) tuning = false
            return true
        }

        override fun sendBinary(frame: ByteArray): Boolean {
            if (!connected || frame.size !in 72..65_600) return false
            val header = ByteBuffer.wrap(frame, 0, 64).order(ByteOrder.LITTLE_ENDIAN)
            if (header.getInt(0) != 0 || header.getInt(4) != 48_000 || header.getInt(8) != 3 ||
                header.getInt(24) != NativeTci.DATA_TX_AUDIO || header.getInt(28) != 2 ||
                header.getInt(20) * 4 + 64 != frame.size) return false
            binaryFrames++
            if (scenario == DebugTciTxScenario.DISCONNECT_DURING_TX && binaryFrames >= 2) connected = false
            return connected
        }
    }
}
