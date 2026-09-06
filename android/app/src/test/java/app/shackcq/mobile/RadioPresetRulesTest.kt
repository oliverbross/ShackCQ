package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPresetRulesTest {
    @Test fun acceptsGroupedAndShortenedFrequencyForms() {
        listOf("14.074.000", "14.074.00", "14.074.0", "14.074", "14074000").forEach {
            assertEquals(14_074_000L, parseRadioPresetFrequency(it))
        }
        assertEquals(7_074_000L, parseRadioPresetFrequency("7.074.0"))
        assertEquals(50_100_000L, parseRadioPresetFrequency("50.100.000"))
    }

    @Test fun rejectsMalformedFrequencyForms() {
        listOf("", "14..074", "14.074.0000", "14 MHz", "14074", "14.074.0.0").forEach {
            assertNull(parseRadioPresetFrequency(it))
        }
    }

    @Test fun acceptsOnlyAmateurBandsFrom160Through6Metres() {
        listOf(1_850_000L, 3_650_000L, 5_357_000L, 7_074_000L, 10_120_000L, 14_074_000L,
            18_100_000L, 21_074_000L, 24_915_000L, 28_400_000L, 50_100_000L).forEach {
            assertTrue("Expected an amateur allocation for $it", radioPresetBandName(it) != null)
        }
        listOf(2_500_000L, 6_000_000L, 13_900_000L, 30_000_000L, 144_300_000L).forEach {
            assertNull("Expected $it to be outside 160–6 m allocations", radioPresetBandName(it))
        }
    }

    @Test fun filtersFollowModeAndPresetValidationUsesThoseChoices() {
        assertEquals(listOf(100, 200, 300, 400, 500, 1_000), radioPresetFilterWidths("CW"))
        assertEquals(listOf(3_000, 4_000, 5_000, 6_000, 7_000, 8_000), radioPresetFilterWidths("AM"))
        assertTrue(isValidRadioPreset(14_074_000L, "DATA", 1_000))
        assertFalse(isValidRadioPreset(14_074_000L, "DATA", 2_700))
        assertFalse(isValidRadioPreset(13_900_000L, "CW", 400))
    }
}
