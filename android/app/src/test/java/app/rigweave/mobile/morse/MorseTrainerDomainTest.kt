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
        assertTrue(session.submit(first)!!.correct)
        assertFalse(session.submit("ZZ")!!.correct)
        assertFalse(session.submit("ZZ")!!.correct)
        assertFalse(session.submit("ZZ")!!.correct)
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

    @Test fun txPracticeKeepsWrongItemForThreeAttemptsAndReportsFailure() {
        val session = TxPracticeSession(listOf("CQ", "TEST"), maxAttempts = 3, startedAtMillis = 1_000)
        assertFalse(session.submit("QQ", 100)!!.advanced)
        assertFalse(session.submit("CC", 200)!!.advanced)
        val third = session.submit("ZZ", 300)!!
        assertTrue(third.advanced)
        assertEquals("TEST", session.current!!.expected)
        assertEquals(1, session.failed)
        assertEquals(0, session.eventuallyCorrect)
        assertTrue(session.characterErrors > 0)
    }

    @Test fun txPracticeTracksAttemptBreakdownAndSkip() {
        val session = TxPracticeSession(listOf("CQ", "DE", "OM"), maxAttempts = 3)
        assertTrue(session.submit("CQ")!!.correct)
        assertFalse(session.submit("DX")!!.correct)
        assertTrue(session.submit("DE")!!.correct)
        assertEquals("OM", session.skip())
        assertTrue(session.isComplete)
        assertEquals(1, session.firstTryCorrect)
        assertEquals(1, session.secondTryCorrect)
        assertEquals(1, session.failed)
    }

    @Test fun callsignDifficultyFiltersRealCorpusShapes() {
        assertTrue(callsignMatchesDifficulty("K1A", CallsignDifficulty.THREE))
        assertFalse(callsignMatchesDifficulty("K1A/P", CallsignDifficulty.THREE))
        assertTrue(callsignMatchesDifficulty("OM0RX/P", CallsignDifficulty.PORTABLE))
        assertTrue(callsignMatchesDifficulty("VK9ABC", CallsignDifficulty.SIX_PLUS))
    }

    @Test fun callsignSessionTracksResponseAndCharacterDifferences() {
        val session = CallsignSession(listOf("OM0RX", "K1A"), speedWpm = 28, startedAtMillis = 1_000)
        assertFalse(session.submit("OM0RY", 900)!!)
        assertTrue(session.submit("K1A", 1_100)!!)
        assertEquals(50, session.accuracyPercent)
        assertEquals(1_400, session.score)
        assertEquals(1, session.attempts.first().differences.size)
        assertEquals(1_000, session.averageResponseMillis)
    }

    @Test fun morseMachineConfusableModeRestrictsPromptsToPair() {
        val settings = MorseTrainerSettings(machineDrillMode = MachineDrillMode.CONFUSABLES, machineConfusablePair = "U/D")
        val session = MorseMachineSession(settings, random = Random(3))
        repeat(30) {
            assertTrue(session.current in setOf('U', 'D'))
            session.answer(session.current)
        }
    }

    @Test fun morseMachineLessonPromotesAfterMastery() {
        val settings = MorseTrainerSettings(machineSet = MachineCharacterSet.KOCH, machineTrainingMode = MachineTrainingMode.LESSON, machineLesson = 1)
        val session = MorseMachineSession(settings, random = Random(4))
        val initial = session.activeCharacters.size
        repeat(80) { session.answer(session.current) }
        assertTrue(session.activeCharacters.size > initial)
        assertTrue(session.lesson > 1)
    }

    @Test fun japaneseMachineCharactersMapToDocumentedLatinMorse() {
        assertEquals(MorseCode.pattern('I'), MorseCode.pattern('い'))
        assertEquals(MorseCode.pattern('Z'), MorseCode.pattern('チ'))
    }

    @Test fun m32JsonStreamExtractionHandlesNoiseAndNestedObjects() {
        val values = extractJsonObjects("boot\n{\"ok\":{\"content\":\"ready\"}}{\"menus\":[{\"content\":\"CW Keyer\"}]}")
        assertEquals(2, values.size)
        assertTrue(values[1].contains("CW Keyer"))
    }

    @Test fun m32BleWritesAreSplitAtDefaultGattPayloadBoundary() {
        val chunks = m32BleWriteChunks(ByteArray(45) { it.toByte() })
        assertEquals(listOf(20, 20, 5), chunks.map(ByteArray::size))
        assertEquals((0 until 45).map(Int::toByte), chunks.flatMap { it.asList() })
    }

    @Test fun m32BleHandshakeWaitsPastConfirmationForDeviceDecision() {
        val confirmation = "{\"message\":{\"content\":\"CONFIRM ON DEVICE\"}}"
        assertFalse(m32BleResponseComplete("put device/protocol/on", confirmation))
        assertTrue(m32BleResponseComplete("put device/protocol/on", confirmation + "{\"device\":{\"protocol\":\"1.4\"}}"))
        assertTrue(m32BleResponseComplete("put device/protocol/on", "{\"error\":{\"content\":\"DEVICE BUSY\"}}"))
    }
}
