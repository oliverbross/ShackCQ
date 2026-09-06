package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogNativeModelsTest {
    @Test fun canonicalizationIsStableAndExcludesSyncBookkeeping() {
        val first = WavelogCanonicalizer.fromAdif(
            "<CALL:5>om0rx<MODE:2>cw<APP_EXAMPLE:3>abc<APP_SHACKCQ_SYNC_STATE:5>local<EOR>")
        val second = WavelogCanonicalizer.fromAdif(
            "<APP_EXAMPLE:3>abc<MODE:2>CW<CALL:5>OM0RX<EOR>")
        assertEquals(first.hash, second.hash)
        assertEquals("OM0RX", first.fields["CALL"])
        assertEquals("abc", first.fields["APP_EXAMPLE"])
        assertFalse(first.fields.containsKey("APP_SHACKCQ_SYNC_STATE"))
    }

    @Test fun canonicalizationDistinguishesContentChanges() {
        val base = WavelogCanonicalizer.fromAdif("<CALL:5>OM0RX<NOTES:3>one<EOR>")
        val changed = WavelogCanonicalizer.fromAdif("<CALL:5>OM0RX<NOTES:3>two<EOR>")
        assertNotEquals(base.hash, changed.hash)
        assertEquals(setOf("NOTES"), base.changedFields(changed))
    }

    @Test fun canonicalRoundTripPreservesUnicodeMultilineColonsEmptyAndUnknownFields() {
        val notes = "  first: line\nžluťoučký 🛰️\n"
        val adif = "<CALL:5>om0rx<NOTES:${notes.toByteArray(Charsets.UTF_8).size}>$notes" +
            "<COMMENT:0><APP_VENDOR_UNKNOWN:5>a:b:c<EOR>"
        val canonical = WavelogCanonicalizer.fromAdif(adif)
        assertEquals(notes, canonical.fields["NOTES"])
        assertEquals("", canonical.fields["COMMENT"])
        assertEquals("a:b:c", canonical.fields["APP_VENDOR_UNKNOWN"])
        assertEquals(canonical, CanonicalQso.decode(canonical.encoded))
        assertEquals(canonical, WavelogCanonicalizer.fromAdif(canonical.asAdif()))
    }

    @Test fun decodesLegacyLengthPrefixedCanonicalPayloadByUtf8Bytes() {
        val legacy = "NOTES:6:žluť\nCALL:5:OM0RX\n"
        val decoded = CanonicalQso.decode(legacy)
        assertEquals("žluť", decoded.fields["NOTES"])
        assertEquals("OM0RX", decoded.fields["CALL"])
    }

    @Test fun threeWayMergeHandlesEveryConflictMatrixBranch() {
        fun q(vararg values: Pair<String, String>) = CanonicalQso(mapOf(*values))
        val base = q("CALL" to "OM0RX", "NOTES" to "base", "QTH" to "Darwin")
        val local = q("CALL" to "OM0RX", "NOTES" to "local", "QTH" to "Darwin")
        val remote = q("CALL" to "OM0RX", "NOTES" to "base", "QTH" to "Bratislava")
        assertEquals("UNCHANGED", WavelogCanonicalizer.merge(base, base, base).disposition)
        assertEquals("PUSH_LOCAL", WavelogCanonicalizer.merge(base, local, base).disposition)
        assertEquals("PULL_REMOTE", WavelogCanonicalizer.merge(base, base, remote).disposition)
        val merged = WavelogCanonicalizer.merge(base, local, remote)
        assertEquals("SAFE_MERGE", merged.disposition)
        assertEquals("local", merged.merged?.fields?.get("NOTES"))
        assertEquals("Bratislava", merged.merged?.fields?.get("QTH"))
        val conflict = WavelogCanonicalizer.merge(base, local, q("CALL" to "OM0RX", "NOTES" to "remote", "QTH" to "Darwin"))
        assertEquals("CONFLICT", conflict.disposition)
        assertEquals(setOf("NOTES"), conflict.conflictingFields)
        assertTrue(CanonicalQso.decode(local.encoded).hash == local.hash)
    }
}
