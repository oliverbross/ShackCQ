// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TciTransmitAuthorityTest {
    private class Clock { var now = 1_000L; suspend fun pause(ms: Long) { now += ms } }

    private class Adapter(
        override val isDemoNoRadio: Boolean = false,
        var pttReadback: Boolean = true,
        var recover: Boolean = true,
        var swr: Double = 1.2,
        var alc: Double? = null,
    ) : TciTransmitAdapter {
        override val deviceIdentity = if (isDemoNoRadio) "debug:no-radio|test" else "network:radio.test:50001|ExpertSDR"
        var connected = true
        var transmitting = false
        var tuning = false
        var frames = 0
        var stopWrites = 0
        var onFrame: (() -> Unit)? = null

        override fun readback() = TciTxReadback(
            connected = connected, transmitting = transmitting, tuning = tuning, txEnabled = true,
            receiver = 0, rxFrequencyHz = 14_074_000, txFrequencyHz = 14_074_000,
            txVfo = "A", split = false, xitEnabled = false, xitOffsetHz = 0,
            mode = "DIGU", drivePercent = 20, tuneDrivePercent = 5,
            forwardPowerWatts = if (transmitting || tuning) 10.0 else 0.0,
            peakPowerWatts = if (transmitting || tuning) 11.0 else 0.0,
            swr = swr, alc = alc, txAudioRate = 48_000, sourceAgeMillis = 0,
        )

        override fun sendText(command: String): Boolean {
            if (!connected) return false
            if (command.contains("trx:0,true") && pttReadback) transmitting = true
            if (command.contains("tune:0,true")) tuning = true
            if (command.contains("trx:0,false")) { stopWrites++; if (recover) transmitting = false }
            if (command.contains("tune:0,false") && recover) tuning = false
            return true
        }

        override fun sendBinary(frame: ByteArray): Boolean {
            frames++; onFrame?.invoke(); return connected && frame.isNotEmpty()
        }
    }

    private fun authority(clock: Clock) = TciTransmitAuthority(
        frameBuilder = TciAudioFrameBuilder { _, _, _, _, _, requested, _ -> ByteArray(64 + requested * 4) },
        commandBuilder = TciCommandBuilder { kind, receiver, _, number, _ -> when (kind) {
            NativeTci.TRX -> "trx:$receiver,${if (number != 0L) "true,tci" else "false"};"
            NativeTci.TUNE -> "tune:$receiver,${number != 0L};"
            NativeTci.SAFE_STOP -> "trx:$receiver,false;tune:$receiver,false;"
            else -> "command:$kind,$receiver,$number;"
        } },
        nowMillis = { clock.now }, pause = clock::pause,
    )

    private fun intent() = TciTxIntent("Digi:FT8", TciTxSource.DIGI, "FT8",
        FloatArray(480) { .1F }, 48_000, 0, 14_074_000)

    @Test fun productionAcceptanceAndIdentityAreFailClosed() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter()
        authority.attach("profile", adapter.deviceIdentity, TciAcceptanceState.PTT_ACCEPTED, "different identity",
            adapter, TciTxSettings())
        assertFalse(authority.transmit(intent()))
        assertEquals(TciAcceptanceState.UNVERIFIED, authority.snapshot.acceptance)
        assertEquals("PTT_NOT_ACCEPTED", authority.snapshot.interlock)
    }

    @Test fun demoAudioPttAndRxRecoveryCompleteThroughOneAuthority() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter, TciTxSettings())
        assertTrue(authority.transmit(intent()))
        assertEquals(TciTxMachineState.RX_IDLE, authority.snapshot.state)
        assertTrue(authority.snapshot.demoNoRadio)
        assertTrue(adapter.frames > 0 && adapter.stopWrites > 0)
    }

    @Test fun pttTimeoutNeverAdvancesToAudio() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true, pttReadback = false)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter, TciTxSettings())
        assertFalse(authority.transmit(intent()))
        assertEquals(0, adapter.frames)
        assertEquals("PTT_READBACK_TIMEOUT", authority.snapshot.interlock)
        assertEquals(TciTxMachineState.RX_IDLE, authority.snapshot.state)
    }

    @Test fun swrAndAlcAbortWithoutInventingUnknownValues() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true, swr = 4.5, alc = 1.0)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter,
            TciTxSettings(swrAbort = 3.0, alcAbort = .9))
        assertFalse(authority.transmit(intent()))
        assertEquals("SWR_ABORT", authority.snapshot.interlock)
        assertEquals(4.5, authority.snapshot.swr!!, .001)
        assertEquals(1.0, authority.snapshot.alc!!, .001)
    }

    @Test fun ambiguousPostTxLatchesRxUnconfirmedAndBlocksNextPtt() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true, recover = false)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter, TciTxSettings())
        assertFalse(authority.transmit(intent()))
        assertEquals(TciTxMachineState.RX_UNCONFIRMED, authority.snapshot.state)
        assertFalse(authority.transmit(intent()))
        assertEquals("RX_UNCONFIRMED", authority.snapshot.interlock)
    }

    @Test fun boundedTuneAlwaysEndsWithRxRecovery() = runBlocking {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter,
            TciTxSettings(maxTuneDurationMillis = 500))
        assertFalse(authority.tune("UI", 0))
        assertEquals(TciTxMachineState.RX_IDLE, authority.snapshot.state)
        assertEquals("TUNE_WATCHDOG", authority.snapshot.interlock)
        assertTrue(adapter.stopWrites > 0)
    }

    @Test fun malformedChronoFaultsAndGlobalStopIsImmediate() {
        val clock = Clock(); val authority = authority(clock); val adapter = Adapter(isDemoNoRadio = true)
        authority.attach("debug", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter, TciTxSettings())
        authority.onChrono(7)
        assertEquals("MALFORMED_TX_CHRONO", authority.snapshot.interlock)
        assertTrue(adapter.stopWrites > 0)
    }
}
