package app.shackcq.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusDigiSafetyRulesTest {
    @Test fun settingsRestoreIgnoresTransientTransmitAuthority() {
        val restored = DigiSettingsDocument.parse("""{
            "selectedMode":"FT8","txArmed":true,"txActive":true,"ptt":true,"sequencer":"transmitting"
        }""")
        assertEquals(DigiMode.FT8.name, restored.selectedMode)
        assertEquals(DigiSettingsDocument(selectedMode = DigiMode.FT8.name), restored)
    }

    @Test fun inboundInteropCommandsOnlyReachBoundedReceiveSideCallbacks() {
        var halt = 0
        var clear = 0
        var replay = 0
        DigiWsjtInterop({ halt++ }, { clear++ }, { replay++ }).use { interop ->
            assertTrue(interop.handleIncoming(WsjtDatagram.control("test", 8)))
            assertTrue(interop.handleIncoming(WsjtDatagram.control("test", 3)))
            assertTrue(interop.handleIncoming(WsjtDatagram.control("test", 7)))
            assertFalse(interop.handleIncoming(WsjtDatagram.heartbeat("test", "1")))
        }
        assertEquals(1, halt)
        assertEquals(1, clear)
        assertEquals(1, replay)
    }

    @Test fun referenceAndCompanionDecodesNeverAcquireAutomaticTransmitEligibility() {
        fun event(source: DigiDecodeSource) = DigiDecodeEvent(
            id = "1", sessionId = "session", epoch = 1, mode = "FT8", slotStartMillis = 15_000,
            decodeSource = source, exactSlotTiming = true, dialFrequencyHz = 14_074_000,
            snr = -10f, dt = 0f, audioHz = 1_000f, text = "CQ K1ABC FN31", callsign = "K1ABC",
        )
        assertTrue(event(DigiDecodeSource.LIVE_CAPTURE).automaticFtEligible("FT8", 14_074_000, "session", 15_000))
        assertFalse(event(DigiDecodeSource.REFERENCE_RECORDING).automaticFtEligible("FT8", 14_074_000, "session", 15_000))
        assertFalse(event(DigiDecodeSource.COMPANION).automaticFtEligible("FT8", 14_074_000, "session", 15_000))
    }

    @Test fun rawRecorderEncodesWholeBlocksAndCountsBackpressureDrops() {
        assertArrayEquals(byteArrayOf(0, -128, 0, 0, -1, 127), digiPcm16Block(floatArrayOf(-1f, 0f, 1f)))
        val queue = DigiRecordingQueue(1)
        assertTrue(queue.offer(byteArrayOf(1, 2), 1))
        assertFalse(queue.offer(byteArrayOf(3, 4, 5, 6), 2))
        assertEquals(2, queue.droppedFrames)
        assertArrayEquals(byteArrayOf(1, 2), queue.take())
    }
}
