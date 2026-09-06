package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CwDecodeBufferTest {
    @Test fun mergesOverlappingDbWindowsAndNormalizesSlashedZero() {
        val decoder = CwDecodeBuffer()
        assertTrue(decoder.feed("DB  CQ OMcRX;".toByteArray()))
        assertTrue(decoder.feed("DBOMcRX DE;".toByteArray()))
        assertEquals("CQ OM0RX DE", decoder.text)
    }

    @Test fun ignoresVfoBDisplayAndRepeatedWindow() {
        listOf("DB14.074.0;", "DB028008650RX FA00028008650RX;", "DBk28.1;DBdb28.100.20;",
            "DB RX THR1;", "DB TX THR3;", "DB AF 60;", "DB 60 AF;", "DB RF 190;",
            "DB TR26;", "DB WPM 28;", "DB WPMEC .72;", "DB FC .71;", "DB0.0W;",
            "DB 0.0 W;", "DBDB 7.1000;", "DBDB 14.074.00;", "DBDB 0.0W;",
            "DBDB AF 60;", "DBDB 60 AF;", "DBDB RX THR1;", "DBDB;", "DBRTQ0;",
            "DBRDBRX THR1;", "DBRDB 7.1000;").forEach { frame ->
            assertFalse(CwDecodeBuffer().feed(frame.toByteArray()))
        }
        val decoder = CwDecodeBuffer()
        assertTrue(decoder.feed("DB  TEST;".toByteArray()))
        assertFalse(decoder.feed("DB  TEST;".toByteArray()))
        assertEquals("TEST", decoder.text)
    }

    @Test fun mutesTrailingControlDisplayFragments() {
        val decoder = CwDecodeBuffer()
        assertTrue(decoder.feed("DB CQ OM0RX;".toByteArray()))
        assertFalse(decoder.feed("DB AF 60;".toByteArray()))
        repeat(30) { assertFalse(decoder.feed("DB .72;".toByteArray())) }
        assertEquals("CQ OM0RX", decoder.text)
        assertTrue(decoder.feed("DB CQ OM0RX DE;".toByteArray()))
        assertEquals("CQ OM0RX DE", decoder.text)

        decoder.clear()
        assertTrue(decoder.feed("DB CQ TEST;".toByteArray()))
        assertFalse(decoder.feed("DB 60 AF;".toByteArray()))
        assertTrue(decoder.feed("DB CQ TEST DE;".toByteArray()))
        assertEquals("CQ TEST DE", decoder.text)
    }

    @Test fun acceptsSplitFramesKeepsNewestHistoryAndClears() {
        val decoder = CwDecodeBuffer(historyLimit = 8)
        assertFalse(decoder.feed("DB  ABC".toByteArray()))
        assertTrue(decoder.feed("DEFGHIJ;".toByteArray()))
        assertEquals("CDEFGHIJ", decoder.text)
        decoder.clear()
        assertEquals("", decoder.text)
        assertFalse(decoder.feed("DB OLD".toByteArray()))
        decoder.clear()
        assertFalse(decoder.feed(" TEXT;".toByteArray()))
        assertEquals("", decoder.text)
    }

    @Test fun suppressesDecoderNoiseWithoutDamagingCallsignZeros() {
        val decoder = CwDecodeBuffer()
        assertFalse(decoder.feed("DB EEEEEEE;".toByteArray()))
        assertFalse(decoder.feed("DB TTT 0;".toByteArray()))
        assertTrue(decoder.feed("DB OM0RX TEST;".toByteArray()))
        assertEquals("OM0RX TEST", decoder.text)
    }

    @Test fun suppressedNoiseNeverChangesHistoryOrOverlapState() {
        val zeroDecoder = CwDecodeBuffer()
        assertTrue(zeroDecoder.feed("DBTEST;".toByteArray()))
        assertFalse(zeroDecoder.feed("DB0;".toByteArray()))
        assertEquals("TEST", zeroDecoder.text)

        val eDecoder = CwDecodeBuffer()
        assertFalse(eDecoder.feed("DBEEEEEEE;".toByteArray()))
        assertTrue(eDecoder.feed("DBEA1ABC;".toByteArray()))
        assertEquals("EA1ABC", eDecoder.text)
    }

    @Test fun validCallsignsThatResembleControlOrNoiseTextArePreserved() {
        listOf("K1TTT", "K1WPM", "TR8CA").forEach { callsign ->
            val decoder = CwDecodeBuffer()
            assertTrue(decoder.feed("DB$callsign;".toByteArray()))
            assertEquals(callsign, decoder.text)
        }
    }

    @Test fun partialDbResponseCannotMergeWithTheNextVfoDisplay() {
        val decoder = CwDecodeBuffer()
        assertTrue(decoder.feed("DB CQ TEST;".toByteArray()))
        assertFalse(decoder.feed("DB2DB21.148.73;".toByteArray()))
        assertEquals("CQ TEST", decoder.text)

        val callsign = CwDecodeBuffer()
        assertTrue(callsign.feed("DBDB1ABC;".toByteArray()))
        assertEquals("DB1ABC", callsign.text)

        val signoff = CwDecodeBuffer()
        assertTrue(signoff.feed("DB73 DB1ABC;".toByteArray()))
        assertEquals("73 DB1ABC", signoff.text)
    }
}
