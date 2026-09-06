// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmxProtocolTest {
    @Test fun boundedParserRejectsOverflowAndMalformedFrames() {
        val decoder = QmxProtocolDecoder(32)
        val responses = decoder.feed(("X".repeat(40) + ";").toByteArray())
        assertTrue(responses.any { it is QmxResponse.Malformed })
        assertTrue(decoder.decodeFrame("FA00014074000") is QmxResponse.Malformed)
    }

    @Test fun frequencyModeAndFilterDecodeExactly() {
        val decoder = QmxProtocolDecoder()
        assertEquals(14_074_000L, (decoder.decodeFrame("FA00014074000;") as QmxResponse.Frequency).hertz)
        assertEquals(QmxMode.DIGI, (decoder.decodeFrame("MD6;") as QmxResponse.Mode).mode)
        assertEquals(3_200, (decoder.decodeFrame("FW3200;") as QmxResponse.Filter).hertz)
        assertTrue(decoder.decodeFrame("FA0014074000;") is QmxResponse.Unrecognised)
    }

    @Test fun firmwareComparisonIsNumericAndSuffixSafe() {
        val old = QmxFirmwareVersion.parse("1_03_002QMX")!!
        val current = QmxFirmwareVersion.parse("1_04_002QMX")!!
        assertTrue(current > old)
        assertEquals("1_04_002QMX", current.toString())
        assertEquals(null, QmxFirmwareVersion.parse("1.04.002"))
    }

    @Test fun capabilityGatingRequiresFirmwareReadbackAndProof() {
        val unknown = QmxCapabilityResolver.resolve(QmxCapabilityEvidence(firmware = QmxFirmwareVersion(1, 4, 2)))
        assertEquals(QmxCapabilityState.UNKNOWN, unknown.amMode)
        assertEquals(QmxCapabilityState.UNKNOWN, unknown.directToneTx)
        val proven = QmxCapabilityResolver.resolve(QmxCapabilityEvidence(
            firmware = QmxFirmwareVersion(1, 4, 2),
            successfulReadbacks = setOf(QmxReadback.MD, QmxReadback.TX_STATE),
            cdcInterfaceCount = 2,
            directToneTxProvenByIntegration = true,
        ))
        assertEquals(QmxCapabilityState.SUPPORTED, proven.amMode)
        assertEquals(QmxCapabilityState.SUPPORTED, proven.swrTune)
        assertEquals(QmxCapabilityState.SUPPORTED, proven.directToneTx)
        assertEquals(QmxCapabilityState.SUPPORTED, proven.menuTerminal)
    }

    @Test fun unsupportedReadbackRemainsUnavailable() {
        val capabilities = QmxCapabilityResolver.resolve(QmxCapabilityEvidence(
            unsupportedReadbacks = setOf(QmxReadback.RG), cdcInterfaceCount = 1,
        ))
        assertEquals(QmxCapabilityState.UNSUPPORTED, capabilities.rfGain)
        assertEquals(QmxCapabilityState.UNSUPPORTED, capabilities.menuTerminal)
        assertEquals(QmxCapabilityState.UNKNOWN, capabilities.afGain)
    }

    @Test fun afAndRfGainUnitsRemainDistinct() {
        val decoder = QmxProtocolDecoder()
        val af = decoder.decodeFrame("AG0091;") as QmxResponse.AfGain
        val rf = decoder.decodeFrame("RG063;") as QmxResponse.RfGain
        assertEquals(22.75, af.decibels, 0.0001)
        assertEquals(91, af.quarterDbSteps)
        assertEquals(63, rf.decibels)
    }

    @Test fun commandBuilderUsesReviewedShapesAndRitClearFirst() {
        assertEquals("FA00014074000;", QmxCommandBuilder.frequency(14_074_000).text)
        assertEquals("AG0091;", QmxCommandBuilder.afGain(91).text)
        assertEquals("RG063;", QmxCommandBuilder.rfGain(63).text)
        assertEquals(listOf("RC;", "RD125;", "RT1;"), QmxCommandBuilder.rit(-125).map(QmxCommand::text))
        assertFalse(QmxCommandBuilder.transmit().mayRetry)
        assertTrue(QmxCommandBuilder.query("FA", QmxReadback.FA).mayRetry)
    }
}
