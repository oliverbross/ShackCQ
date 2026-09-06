package app.shackcq.mobile

import org.junit.Assert.*
import org.junit.Test

class VoiceMacroPlanTest {
    @Test fun compositionPreservesClipOrderAndAddsBoundedSilence() {
        val combined = composeVoicePlan(listOf(CanonicalVoicePcm(shortArrayOf(1, 2)), CanonicalVoicePcm(shortArrayOf(3))), 1)
        assertEquals(2 + 48 + 1, combined.samples.size)
        assertArrayEquals(shortArrayOf(1, 2), combined.samples.copyOfRange(0, 2))
        assertEquals(3, combined.samples.last().toInt())
    }

    @Test fun oneSlotAdapterDoesNotCopyExtraAudio() {
        val original = CanonicalVoicePcm(shortArrayOf(4, 5, 6))
        assertArrayEquals(original.samples, composeVoicePlan(listOf(original), 80).samples)
    }

    @Test fun durationAndSegmentBoundsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { composeVoicePlan(List(13) { CanonicalVoicePcm(shortArrayOf(1)) }, 80) }
        assertThrows(IllegalArgumentException::class.java) { composeVoicePlan(listOf(CanonicalVoicePcm(ShortArray(VOICE_SAMPLE_RATE * 46))), 80) }
        assertThrows(IllegalArgumentException::class.java) { composeVoicePlan(listOf(CanonicalVoicePcm(shortArrayOf(1))), 501) }
    }

    @Test fun invalidClipPreventsComposition() {
        assertThrows(IllegalArgumentException::class.java) { composeVoicePlan(listOf(CanonicalVoicePcm(shortArrayOf())), 80) }
    }
}
