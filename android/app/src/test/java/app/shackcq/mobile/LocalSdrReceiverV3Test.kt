// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSdrReceiverV3Test {
    @Test fun modeCapabilityRequiresWideSourceOnlyForWfm() {
        LocalReceiverMode.entries.filterNot { it == LocalReceiverMode.WFM }.forEach { assertTrue(it.supported(48_000)) }
        assertFalse(LocalReceiverMode.WFM.supported(96_000))
        assertTrue(LocalReceiverMode.WFM.supported(192_000))
        assertFalse(LocalReceiverMode.USB.supported(44_100))
    }

    @Test fun modeDefaultsPreserveSidebandDigitalCwAmAndFmIntent() {
        assertEquals(300, localReceiverModeDefaults(LocalReceiverMode.USB).filterLowHz)
        assertEquals(2_700, localReceiverModeDefaults(LocalReceiverMode.LSB).filterHighHz)
        assertEquals(200, localReceiverModeDefaults(LocalReceiverMode.DIGU).filterLowHz)
        assertEquals(600, localReceiverModeDefaults(LocalReceiverMode.CW).cwPitchHz)
        assertEquals(6_000, localReceiverModeDefaults(LocalReceiverMode.SAM).filterHighHz)
        assertEquals(12_500, localReceiverModeDefaults(LocalReceiverMode.NFM).filterHighHz)
        assertEquals(95_000, localReceiverModeDefaults(LocalReceiverMode.WFM).filterHighHz)
        assertEquals(0f, localReceiverModeDefaults(LocalReceiverMode.SPECTRUM).gain)
    }

    @Test fun preferencesClampEveryRealtimeAndAudioBound() {
        val value = LocalReceiverPreferences(filterLowHz = -2, filterHighHz = 200_000, cwPitchHz = 50,
            agcHangMillis = 9_999, squelchDb = -500f, noiseReduction = 3f, gain = 8f, pan = -4f, fmDeemphasisUs = 99).validated()
        assertEquals(0, value.filterLowHz)
        assertEquals(95_000, value.filterHighHz)
        assertEquals(100, value.cwPitchHz)
        assertEquals(2_000, value.agcHangMillis)
        assertEquals(-120f, value.squelchDb)
        assertEquals(1f, value.noiseReduction)
        assertEquals(2f, value.gain)
        assertEquals(-1f, value.pan)
        assertEquals(75, value.fmDeemphasisUs)
    }

    @Test fun frequencyAndSpanTruthNeverImplyPhysicalRetune() {
        val state = LocalReceiverState("local:A", "RX A", "TEST", 0, 14_074_000, 96_000, relativeOffsetHz = 22_000)
        assertEquals(14_096_000, state.frequencyHz)
        assertTrue(state.inSpan)
        assertFalse(state.copy(relativeOffsetHz = 46_000).inSpan)
    }

    @Test fun toneStatesRequireConfidenceAndKeepNormalInvertedTruth() {
        val base = LocalReceiverState("local:A", "RX A", "TEST", 0, 1, 96_000,
            preferences = localReceiverModeDefaults(LocalReceiverMode.NFM))
        assertEquals("CTCSS/DCS SEARCHING", base.toneState)
        assertEquals("CTCSS 88.5 Hz", base.copy(ctcssHz = 88.5f, ctcssConfidence = .8f).toneState)
        assertEquals("DCS 023N", base.copy(dcsCode = 23, dcsConfidence = .8f).toneState)
        assertEquals("DCS 023I", base.copy(dcsCode = 23, dcsInverted = true, dcsConfidence = .8f).toneState)
    }

    @Test fun wavHeaderIsPcm16LittleEndianAndSizeBounded() {
        val header = wavHeader(96_000, 48_000, 1)
        assertEquals(44, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", header.copyOfRange(8, 12).decodeToString())
        assertEquals("data", header.copyOfRange(36, 40).decodeToString())
        assertEquals(96_000, ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test fun safeReceiverStateNeverRestoresListeningOrRecording() {
        val state = LocalReceiverState("local:A", "RX A", "RESTORED SAFE PREFERENCES", 0, 0, 0, enabled = false)
        assertFalse(state.enabled)
        assertFalse(state.listening)
        assertFalse(state.recording)
        assertFalse(state.inSpan)
    }

    @Test fun everyDebugFixtureGeneratesFiniteBoundedStereoIqWithoutRadio() {
        DebugLocalFixture.entries.forEach { fixture ->
            val rate = if (fixture.name.startsWith("WFM")) 192_000 else 96_000
            val values = debugLocalIq(fixture, 0, rate, 2_048, 0.0)
            assertEquals(4_096, values.size)
            assertTrue(values.all(Float::isFinite))
            assertTrue(values.all { it in -1f..1f })
        }
    }
}
