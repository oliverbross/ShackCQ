package app.shackcq.mobile.radio.rgoone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RgoOneProtocolTest {
    @Test fun boundedParserDiscardsOversizeAndRecoversAtTerminator() {
        val decoder = RgoOneProtocolDecoder(16)
        assertTrue(decoder.accept(("FA" + "1".repeat(30) + ";").toByteArray()).isEmpty())
        assertEquals(listOf("FB00007074000;"), decoder.accept("FB00007074000;".toByteArray()))
    }

    @Test fun terminatorFramingProducesOnlyCompletePrintableFrames() {
        val decoder = RgoOneProtocolDecoder()
        assertEquals(listOf("FA00014074000;", "MD2;"), decoder.accept("FA00014074000;MD2;partial".toByteArray()))
        assertTrue(decoder.accept(byteArrayOf(1, ';'.code.toByte())).isEmpty())
    }

    @Test fun frequencyABBuildersAndParsersUseElevenDigits() {
        assertEquals("FA00014074000;", RgoOneCommandBuilder.frequency(RgoOneVfo.A, 14_074_000))
        assertEquals("FB00007074000;", RgoOneCommandBuilder.frequency(RgoOneVfo.B, 7_074_000))
        assertEquals(14_074_000L, (RgoOneProtocolParser.parse("FA00014074000;") as RgoOneProtocolResponse.Frequency).frequencyHz)
    }

    @Test fun modeValuesAreExactAndUnknownValuesDoNotBecomeModes() {
        assertEquals("MD6;", RgoOneCommandBuilder.mode(RgoOneMode.DATA))
        val next = RgoOneSnapshotReducer.apply(RgoOneRadioSnapshot(), RgoOneProtocolParser.parse("MD7;"), 1)
        assertEquals(RgoOneMode.CW_REVERSE, next.mode)
        assertTrue(RgoOneProtocolParser.parse("MD9;") is RgoOneProtocolResponse.Selection)
        assertEquals(null, RgoOneSnapshotReducer.apply(next, RgoOneProtocolParser.parse("MD9;"), 2).mode)
    }

    @Test fun rxAndTxVfoResponsesDeriveSplitWithoutAInventedCommand() {
        val rx = RgoOneSnapshotReducer.apply(RgoOneRadioSnapshot(), RgoOneProtocolParser.parse("FR0;"), 1)
        val split = RgoOneSnapshotReducer.apply(rx, RgoOneProtocolParser.parse("FT1;"), 2)
        assertEquals(true, split.split)
        assertEquals("FT0;", RgoOneCommandBuilder.txVfo(RgoOneVfo.A))
    }

    @Test fun ritXitUseDocumentedTogglesAndBoundedEdgeCommands() {
        assertEquals("RT1;", RgoOneCommandBuilder.toggle("RT", true))
        assertEquals("XT0;", RgoOneCommandBuilder.toggle("XT", false))
        assertEquals("RC;", RgoOneCommandBuilder.forAction(RgoOneAction.ClearRit))
        assertEquals("RU;", RgoOneCommandBuilder.forAction(RgoOneAction.NudgeRit(true)))
    }

    @Test fun firmwareAndModelIdentityAreStrictlyParsed() {
        assertEquals("1.09", (RgoOneProtocolParser.parse("FW0109;") as RgoOneProtocolResponse.Firmware).version.toString())
        assertEquals("006", (RgoOneProtocolParser.parse("ID006;") as RgoOneProtocolResponse.ModelId).value)
        assertTrue(RgoOneProtocolParser.parse("FW109;") is RgoOneProtocolResponse.Malformed)
    }

    @Test fun serialNumberIsHashedAtTheParserBoundary() {
        val response = RgoOneProtocolParser.parse("ID20303656 32435012 0030002B;", "SN") as RgoOneProtocolResponse.SerialDigest
        assertTrue(response.sha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(response.toString().contains("20303656"))
    }
}
