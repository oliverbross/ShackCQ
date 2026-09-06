package app.shackcq.mobile.rotator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RotatorQueueAndDriverTest {
    @Test fun tcpResponseBoundIsEnforcedByCodecs() {
        assertThrows(IllegalArgumentException::class.java) { RotctldProtocolCodec.parse(ByteArray(MAX_ROTATOR_RESPONSE_BYTES + 1)) }
    }
    @Test fun serialTimeoutSettingsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { SerialSettings("0123456789abcdef", readTimeoutMs = 99) }
    }
    @Test fun commandQueueSerializesInOrder() {
        val queue = RotatorCommandQueue(); queue.enqueue(QueuedRotatorCommand(1, 1, RotatorAction.CONNECT, physicalMotion = false)); queue.enqueue(QueuedRotatorCommand(2, 1, RotatorAction.PARK, physicalMotion = true))
        assertEquals(1L, queue.next()?.id); assertEquals(2L, queue.next()?.id)
    }
    @Test fun stopBypassesQueuedMove() {
        val queue = RotatorCommandQueue(); queue.enqueue(QueuedRotatorCommand(1, 1, RotatorAction.MOVE_ABSOLUTE, 10.0, physicalMotion = true)); queue.enqueue(QueuedRotatorCommand(2, 1, RotatorAction.STOP, physicalMotion = true))
        assertEquals(RotatorAction.STOP, queue.next()?.action); assertNull(queue.next())
    }
    @Test fun physicalMoveIsNotBlindRetried() = runBlocking {
        val transport = CountingTransport(failMotion = true); val driver = driver(transport)
        try { driver.connect(); driver.move(10.0, null, 2) } catch (_: Exception) { }
        assertEquals(2, transport.transactions) // one position query, one movement attempt
    }
    @Test fun staleResponseCannotPublish() = runBlocking {
        val transport = CountingTransport(); val driver = driver(transport); driver.connect()
        val fresh = driver.poll(10); val stale = driver.poll(5)
        assertEquals(fresh.generation, stale.generation)
        driver.close(); driver.close(); assertFalse(transport.connected)
    }

    private fun driver(transport: CountingTransport) = NativeRotatorDriver(
        RotatorDeviceProfile("11111111-1111-1111-1111-111111111111", "Fake", RotatorBackend.NATIVE, RotatorProtocolKind.GS232,
            RotatorTransportKind.TCP, tcp = TcpSettings("localhost", 1, lanOptIn = true)), Gs232Protocol(false), { transport },
        RotatorCapabilitySnapshot(mapOf(RotatorCapability.ABSOLUTE_MOVE to CapabilitySupport.SUPPORTED)), { Instant.EPOCH })

    private class CountingTransport(private val failMotion: Boolean = false) : RotatorTransport {
        override var connected = false; var transactions = 0
        override suspend fun open() { connected = true }
        override suspend fun transact(command: RotatorWireCommand, responseRule: RotatorResponseRule): ByteArray {
            transactions++; if (command.physicalMotion && failMotion) error("fail once")
            return if (command.expectsResponse) "010\r".toByteArray() else ByteArray(0)
        }
        override fun close() { connected = false }
    }
}
