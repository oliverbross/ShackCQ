package app.shackcq.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VoiceMacroRulesTest {
    @Test fun voiceModeAndLabelsAreStrict() {
        listOf("USB", "usb", "LSB").forEach { assertTrue(isVoiceMacroMode(it)) }
        listOf("CW", "CW-R", "SSB", "AM", "FM", "DATA", "RTTY", "").forEach { assertFalse(isVoiceMacroMode(it)) }
        assertEquals("CQ TEST/1", sanitizeVoiceMacroLabel("  CQ\u0000 TEST/1  ", 0))
        assertEquals("M3", sanitizeVoiceMacroLabel("\n\r", 2))
        assertEquals(VOICE_MACRO_LABEL_MAX, sanitizeVoiceMacroLabel("A".repeat(30), 0).length)
        assertEquals(6, VOICE_MACRO_COUNT)
        assertTrue(runCatching { sanitizeVoiceMacroLabel("bad", 6) }.isFailure)
    }

    @Test fun canonicalWaveRoundTripsAndSkipsUnknownChunks() {
        val samples = shortArrayOf(-32_000, -1, 0, 1, 32_000)
        val canonical = writeCanonicalWave(CanonicalVoicePcm(samples))
        assertArrayEquals(samples, parsePcmWave(canonical).samples)
        val withJunk = canonical.copyOfRange(0, 36) + "JUNK".toByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array() + byteArrayOf(1, 2, 3, 0) +
            canonical.copyOfRange(36, canonical.size)
        ByteBuffer.wrap(withJunk).order(ByteOrder.LITTLE_ENDIAN).putInt(4, withJunk.size - 8)
        assertArrayEquals(samples, parsePcmWave(withJunk).samples)
    }

    @Test fun stereoDownmixAndResampleAreDeterministic() {
        val stereo = pcmWave(8_000, 2, shortArrayOf(10_000, -10_000, 12_000, 4_000, -6_000, -2_000))
        val parsed = parsePcmWave(stereo)
        assertArrayEquals(shortArrayOf(0, 8_000, -4_000), parsed.samples)
        val resampled = resampleLinear(parsed.samples, 8_000, 48_000)
        assertEquals(18, resampled.size)
        assertEquals(0, resampled.first().toInt())
    }

    @Test fun preparationRejectsSilenceTrimsPadsFadesAndLimitsDuration() {
        runCatching { prepareVoicePcm(ShortArray(48_000), 48_000) }.onSuccess { error("silence accepted") }
        val source = ShortArray(48_000 * 2)
        for (index in 24_000 until 72_000) source[index] = if (index % 2 == 0) 20_000 else -20_000
        val prepared = prepareVoicePcm(source, 48_000)
        assertTrue(prepared.durationMillis in 1_200..1_260)
        assertEquals(0, prepared.samples.first().toInt())
        assertEquals(0, prepared.samples.last().toInt())
        assertTrue(prepared.samples.maxOf { abs(it.toInt()) } <= (Short.MAX_VALUE * 0.502).toInt())
        val long = ShortArray(48_000 * 31) { if (it % 2 == 0) 10_000 else -10_000 }
        assertEquals(48_000 * 30, prepareVoicePcm(long, 48_000).samples.size)
    }

    @Test fun transmitStereoUsesLeftOnlyAndClippingSafeScale() {
        assertArrayEquals(shortArrayOf(5_000, 0, -5_000, 0, 16_384, 0),
            stereoLeftOnly(shortArrayOf(10_000, -10_000, Short.MAX_VALUE), 0.5f))
    }

    @Test fun stableSelectionNeverGuesses() {
        data class Candidate(val key: String, val id: Int)
        val one = listOf(Candidate("a", 1))
        assertEquals(1, chooseStableCandidate(one, null, Candidate::key).selected?.id)
        val candidates = listOf(Candidate("a", 1), Candidate("b", 2))
        assertEquals(2, chooseStableCandidate(candidates, "b", Candidate::key).selected?.id)
        assertTrue(chooseStableCandidate(candidates, null, Candidate::key).selectionRequired)
        assertTrue(chooseStableCandidate(listOf(Candidate("a", 1), Candidate("a", 2)), "a", Candidate::key).selectionRequired)
        assertNull(chooseStableCandidate(candidates, "stale", Candidate::key).selected)
    }

    @Test fun serialAndAudioSignaturesUseTheSameNoGuessPolicy() {
        val serial = listOf(
            SerialDeviceDescriptor("1:0", "cp|a", "Cp21xx", "Silicon Labs", "CP210x", "10C4:EA60", "A", 0, "/dev/1"),
            SerialDeviceDescriptor("2:0", "cp|b", "Cp21xx", "Silicon Labs", "CP210x", "10C4:EA60", "B", 0, "/dev/2"),
        )
        assertEquals("2:0", chooseStableCandidate(serial, "cp|b", SerialDeviceDescriptor::stableKey).selected?.sessionKey)
        assertTrue(chooseStableCandidate(serial, null, SerialDeviceDescriptor::stableKey).selectionRequired)
        val audio = listOf(
            AudioRouteDescriptor(8, "usb|left", "DigiRig", 11, "card=1", false, true, listOf(2), listOf(48_000)),
            AudioRouteDescriptor(9, "usb|other", "StarTech", 11, "card=2", false, true, listOf(2), listOf(48_000)),
        )
        assertEquals(8, chooseStableCandidate(audio, "usb|left", AudioRouteDescriptor::stableKey).selected?.sessionId)
        assertTrue(chooseStableCandidate(audio, "stale", AudioRouteDescriptor::stableKey).selectionRequired)
    }

    @Test fun catResponseRejectsQueryEchoAndAcceptsStructuredElecraftFrames() {
        assertFalse(containsElecraftCatResponse("K3;OM;ID;FA;".toByteArray()))
        assertTrue(containsElecraftCatResponse("K30;OM A-F-------02;".toByteArray()))
        assertTrue(containsElecraftCatResponse("FA00014074000;".toByteArray()))
    }

    @Test fun parsesOnlyFreshCompleteTqFrames() {
        assertEquals(false, parseFreshTq("FA000;TQ0;".toByteArray()))
        assertEquals(true, parseFreshTq("TQ0;IFfoo;TQ1;".toByteArray()))
        assertEquals(true, parseFreshTq(("T" + "Q1;").toByteArray()))
        assertNull(parseFreshTq("TQ1".toByteArray()))
    }

    @Test fun txSequenceOrdersRouteKeySpeechAndRelease() = runBlocking {
        val io = FakeTxIo()
        val result = executeVoiceTxSequence(io)
        assertTrue(result.success)
        assertEquals(listOf("lease", "query", "route", "tx", "confirm-tx", "lead", "speech", "tail", "halt", "rx", "confirm-rx", "release"), io.events)
    }

    @Test fun preflightExistingTxIsNotSeizedOrReleased() = runBlocking {
        val io = FakeTxIo(preflight = true)
        val result = executeVoiceTxSequence(io)
        assertFalse(result.success)
        assertEquals(listOf("lease", "query", "release"), io.events)
    }

    @Test fun failedAudioLeaseSendsNoPttAndDoesNotReleaseAnotherOwner() = runBlocking {
        val io = FakeTxIo(leaseFailure = "PANADAPTER owns the shared audio route")
        val result = executeVoiceTxSequence(io)
        assertFalse(result.success)
        assertEquals(listOf("lease"), io.events)
        assertFalse(io.events.contains("tx"))
    }

    @Test fun failuresAfterTxAlwaysAttemptAtMostTwoDefensiveReleases() = runBlocking {
        listOf("confirm-tx", "speech", "tail", "halt", "confirm-rx").forEach { phase ->
            val io = FakeTxIo(failAt = phase, neverConfirmRx = phase == "confirm-rx")
            val result = executeVoiceTxSequence(io)
            assertFalse("$phase should fail", result.success)
            assertTrue("$phase did not request RX", io.events.count { it == "rx" } in 1..2)
            assertEquals("$phase did not release its lease exactly once", 1, io.events.count { it == "release" })
        }
    }

    @Test fun stopRouteLossBackgroundWatchdogAndWriteErrorsShareSafeRxCleanup() = runBlocking {
        listOf("user STOP", "USB route loss", "app background", "watchdog", "audio write exception").forEach { reason ->
            val io = FakeTxIo(failAt = "speech", failureMessage = reason)
            val result = executeVoiceTxSequence(io)
            assertFalse(result.success)
            assertTrue(result.message.contains(reason))
            assertTrue(io.events.contains("rx"))
            assertFalse(result.radioMayStillBeTx)
        }
    }

    @Test fun routeFailureOccursBeforeTxAndNeedsNoRx() = runBlocking {
        val io = FakeTxIo(failAt = "route")
        val result = executeVoiceTxSequence(io)
        assertFalse(result.success)
        assertFalse(io.events.contains("tx"))
        assertFalse(io.events.contains("rx"))
        assertEquals(1, io.events.count { it == "release" })
    }

    private class FakeTxIo(
        private val preflight: Boolean? = false,
        private val failAt: String? = null,
        private val neverConfirmRx: Boolean = false,
        private val failureMessage: String = "failed",
        private val leaseFailure: String? = null,
    ) : VoiceTxSequenceIo {
        val events = mutableListOf<String>()
        private fun event(name: String) { events += name; if (failAt == name) error("$failureMessage at $name") }
        override fun acquireAudio(): String? { event("lease"); return leaseFailure }
        override fun releaseAudio() = event("release")
        override suspend fun queryTq(): Boolean? { event("query"); return preflight }
        override suspend fun prepareAndVerifyRoute() = event("route")
        override suspend fun sendTx() = event("tx")
        override suspend fun confirmTq(transmitting: Boolean): Boolean {
            event(if (transmitting) "confirm-tx" else "confirm-rx")
            return if (transmitting) true else !neverConfirmRx
        }
        override suspend fun writeLeadSilence() = event("lead")
        override suspend fun writeSpeech() = event("speech")
        override suspend fun writeTailSilence() = event("tail")
        override suspend fun haltAudio() = event("halt")
        override suspend fun sendRx() = event("rx")
    }

    private fun pcmWave(rate: Int, channels: Int, interleaved: ShortArray): ByteArray {
        val dataSize = interleaved.size * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            put("data".toByteArray()).putInt(dataSize); interleaved.forEach(::putShort)
        }.array()
    }
}
