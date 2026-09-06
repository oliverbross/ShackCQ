package app.shackcq.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FlexCompleteRulesTest {
    private fun vita(packetClass: Int, streamId: Long, sequence: Int, payload: ByteArray): ByteArray {
        val bytes = 28 + payload.size
        val words = (bytes + 3) / 4
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt((3 shl 28) or (1 shl 27) or (3 shl 22) or (1 shl 20) or ((sequence and 0xf) shl 16) or words)
            putInt(streamId.toInt())
            putInt(FLEX_VITA_OUI)
            putInt((0x534C shl 16) or packetClass)
            putInt(0)
            putInt(0)
            putInt(0)
            put(payload)
        }.array()
    }

    private fun fft(first: Int, total: Int, frame: Int, bins: IntArray): ByteArray =
        ByteBuffer.allocate(12 + bins.size * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(first.toShort())
            putShort(bins.size.toShort())
            putShort(2)
            putShort(total.toShort())
            putInt(frame)
            bins.forEach { putShort(it.toShort()) }
        }.array()

    @Test
    fun vitaParserHonoursOuiClassDeclaredLengthAndExactByteOpus() {
        val exact = buildFlexOpusTxPacket(0x6000_0000, 5, byteArrayOf(1, 2, 3, 4, 5))!!
        val parsed = FlexVitaPacket.parse(exact)
        assertNotNull(parsed)
        assertEquals(FLEX_OPUS_CLASS, parsed!!.packetClass)
        assertEquals(5, parsed.payload.size)
        assertNull(FlexVitaPacket.parse(exact.copyOf(25)))
        val foreign = exact.clone()
        foreign[11] = 0
        assertNull(FlexVitaPacket.parse(foreign))
    }

    @Test
    fun fftReassemblyIsCoverageBasedAndUsesRadioYPixels() {
        val engine = FlexVitaEngine()
        engine.register(7, FlexStreamKind.PANADAPTER)
        assertNull(engine.feed(vita(FLEX_FFT_CLASS, 7, 1, fft(2, 4, 11, intArrayOf(50, 99)))))
        assertNull(engine.feed(vita(FLEX_FFT_CLASS, 7, 1, fft(2, 4, 11, intArrayOf(50, 99)))))
        val result = engine.feed(vita(FLEX_FFT_CLASS, 7, 2, fft(0, 4, 11, intArrayOf(0, 25))), yPixels = 100)
        val frame = (result as FlexVitaEvent.Spectrum).value
        assertEquals(4, frame.binsDbm.size)
        assertEquals(-30f, frame.binsDbm.first(), 0.001f)
        assertEquals(-130f, frame.binsDbm.last(), 0.001f)
        assertEquals(1, engine.duplicatePackets)
    }

    @Test
    fun streamRegistryAndSequenceTrackingFailClosed() {
        val engine = FlexVitaEngine()
        engine.register(9, FlexStreamKind.REDUCED_AUDIO)
        val payload = byteArrayOf(0, 1, 0, 2)
        assertNull(engine.feed(vita(FLEX_REDUCED_AUDIO_CLASS, 10, 1, payload)))
        assertNotNull(engine.feed(vita(FLEX_REDUCED_AUDIO_CLASS, 9, 1, payload)))
        assertNotNull(engine.feed(vita(FLEX_REDUCED_AUDIO_CLASS, 9, 4, payload)))
        assertEquals(2, engine.sequenceGaps)
    }

    @Test
    fun ownedObjectRemovalCannotTargetForeignObjects() {
        val owned = FlexOwnedObjects()
        owned.ownPan(0x4000)
        owned.ownSlice(2)
        owned.ownStream(0x6000)
        assertNotNull(FlexCommands.removePan(0x4000, owned))
        assertNull(FlexCommands.removePan(0x4001, owned))
        assertNotNull(FlexCommands.removeSlice(2, owned))
        assertNull(FlexCommands.removeSlice(3, owned))
        assertNotNull(FlexCommands.removeStream(0x6000, owned))
        assertNull(FlexCommands.removeStream(0x6001, owned))
    }

    @Test
    fun commandBuildersRejectInjectionAndOutOfRangeValues() {
        assertNull(FlexCommands.createSlice(0, 14_074_000))
        assertNull(FlexCommands.frequency(0, 0))
        assertNull(FlexCommands.mode(0, "TX"))
        assertNull(FlexCommands.rxAntenna(0, "ANT1\nxmit 1"))
        assertNull(FlexCommands.loadProfile("global", "bad\nprofile"))
        assertNull(FlexCommands.cwx("CQ\nxmit 1"))
        assertEquals("xmit 0", FlexCommands.mox(false))
        assertEquals("transmit tune 0", FlexCommands.tune(false))
    }

    @Test
    fun txGateIsSessionOnlySerializedAndStopsToRxOnce() = runBlocking {
        val commands = mutableListOf<String>()
        var releases = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tx = FlexTxController(scope, { commands += it; true }, { releases++ })
        tx.updateEligibility(FlexTxEligibility(true, "OM0RX", 1, 14_200_000, "USB", 5, "ANT1", true))
        assertFalse(tx.enableForSession("yes"))
        assertTrue(tx.enableForSession("ENABLE FLEX TRANSMIT FOR THIS SESSION"))
        assertTrue(tx.arm())
        assertTrue(tx.startMox())
        assertEquals(FlexTxState.KEYING, tx.state)
        tx.observedTransmit(true)
        assertEquals(FlexTxState.TRANSMITTING, tx.state)
        tx.stop("test")
        assertEquals(1, commands.count { it == "xmit 1" })
        assertEquals(1, commands.count { it == "xmit 0" })
        assertTrue(releases >= 1)
        assertEquals(FlexTxState.STOPPING, tx.state)
        tx.observedTransmit(false)
        assertEquals(FlexTxState.READY, tx.state)
        tx.clearGate()
        assertEquals(FlexTxState.DISABLED, tx.state)
        scope.cancel()
    }
}
