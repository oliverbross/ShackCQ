package app.rigweave.mobile.morse

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseTrainerDomainTest {
    @Test fun settingsNormalizeUnsafeValues() {
        val value = MorseTrainerSettings(characterWpm = 99, effectiveWpm = 2, pitchHz = 50,
            volumePercent = 120, machineCharacters = "kKm!", groupLength = 20).normalized()
        assertEquals(60, value.characterWpm)
        assertEquals(5, value.effectiveWpm)
        assertEquals(300, value.pitchHz)
        assertEquals(100, value.volumePercent)
        assertEquals("KM", value.machineCharacters)
        assertEquals(8, value.groupLength)
    }

    @Test fun morseEncodingPreservesWordBoundary() {
        assertEquals(listOf("...", "---", "...", null, ".-"), MorseCode.encode("SOS A"))
    }

    @Test fun txPracticeScoresExactGroupsAndCharacters() {
        val session = TxPracticeSession(MorseTrainerSettings(sessionSize = 5, groupLength = 2, kochCharacters = 2), Random(7))
        val first = session.current!!.expected
        assertTrue(session.submit(first)!!)
        assertFalse(session.submit("ZZ")!!)
        assertEquals(1, session.perfectGroups)
        assertEquals(50, session.accuracyPercent)
    }

    @Test fun callsignFactoryHonoursDifficultyShape() {
        repeat(50) { seed ->
            assertEquals(3, CallsignFactory.generate(CallsignDifficulty.THREE, Random(seed)).length)
            assertEquals(4, CallsignFactory.generate(CallsignDifficulty.FOUR, Random(seed)).length)
            assertEquals(5, CallsignFactory.generate(CallsignDifficulty.FIVE, Random(seed)).length)
            assertTrue(CallsignFactory.generate(CallsignDifficulty.SIX_PLUS, Random(seed)).length >= 6)
            assertTrue('/' in CallsignFactory.generate(CallsignDifficulty.PORTABLE, Random(seed)))
        }
    }

    @Test fun callsignSessionUsesMtpScoreFormula() {
        val session = CallsignSession(MorseTrainerSettings(characterWpm = 28, sessionSize = 5), Random(2))
        val expected = session.current!!
        assertTrue(session.submit(expected, 900)!!)
        assertEquals(100, session.accuracyPercent)
        assertEquals(2_800, session.score)
    }

    @Test fun adaptiveWeightRaisesMissedCharacters() {
        assertEquals(1.0, CharacterProgress().weight, 0.0001)
        assertTrue(CharacterProgress(correct = 1, incorrect = 4).weight > CharacterProgress(correct = 4, incorrect = 1).weight)
    }

    @Test fun m32JsonStreamExtractionHandlesNoiseAndNestedObjects() {
        val values = extractJsonObjects("boot\n{\"ok\":{\"content\":\"ready\"}}{\"menus\":[{\"content\":\"CW Keyer\"}]}")
        assertEquals(2, values.size)
        assertTrue(values[1].contains("CW Keyer"))
    }
}
