package app.shackcq.mobile.radio.rgoone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RgoOneLifecycleTest {
    private class FakeSerial : RgoOneSerialPort {
        override var stableIdentity = "usb:rgo-one:port-1"
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val writes = Collections.synchronizedList(mutableListOf<String>())
        var openCount = 0
        var closeCount = 0
        var writeSucceeds = true
        var exchangeDelayMillis = 0L
        private val inFlight = AtomicInteger()
        val maximumInFlight = AtomicInteger()

        override fun open(config: RgoOneSerialConfig): Boolean { openCount++; return true }
        override fun write(command: ByteArray): Boolean { writes += command.toString(Charsets.US_ASCII); return writeSucceeds }
        override fun exchange(command: ByteArray, maximumResponseBytes: Int, timeoutMillis: Long): ByteArray {
            val text = command.toString(Charsets.US_ASCII)
            commands += text
            val active = inFlight.incrementAndGet()
            maximumInFlight.updateAndGet { maxOf(it, active) }
            if (exchangeDelayMillis > 0) Thread.sleep(exchangeDelayMillis)
            inFlight.decrementAndGet()
            val response = when {
                text == "ID;" -> "ID006;"
                text == "FW;" -> "FW0109;"
                text == "FA;" -> "FA00014074000;"
                text == "FB;" -> "FB00007074000;"
                text == "FR;" -> "FR0;"
                text == "FT;" -> "FT0;"
                text == "MD;" -> "MD2;"
                text == "FS;" -> "FS0;"
                text == "SM0;" -> "SM00005;"
                text == "GT;" -> "GT001;"
                text == "RT;" -> "RT0;"
                text == "XT;" -> "XT0;"
                text == "RG;" -> "RG097;"
                text == "PC;" -> "PC025;"
                text == "KS;" -> "KS020;"
                text == "MG;" -> "MG005;"
                text == "PA;" -> "PA00;"
                text == "RA;" -> "RA0000;"
                text == "NB;" -> "NB0;"
                text == "AC;" -> "AC100;"
                text == "EX0420000;" -> "EX04200000;"
                text.startsWith("FA") -> text
                text.startsWith("FB") -> text
                text.startsWith("FR") -> text
                text.startsWith("FT") -> text
                text.startsWith("MD") -> text
                else -> text
            }
            return response.toByteArray().take(maximumResponseBytes).toByteArray()
        }
        override fun close() { closeCount++ }
    }

    private fun connected(serial: FakeSerial, memory: Boolean = false): RgoOneConnectionController {
        val controller = RgoOneConnectionController(serial, safety = RgoOneSafetyPort { _, _ -> RgoOneSafetyDecision.ALLOW_ONCE })
        assertTrue(controller.connect(RgoOneSettingsDocument(generation = RgoOneGeneration.V6, writesConfirmed = true,
            memoryWriteEnabled = memory, fastPollMillis = 60_000, mediumPollMillis = 60_000, slowPollMillis = 60_000)))
        return controller
    }

    @Test fun pollCadencesShareOneSerialOwnerAndSuppressDuplicateCadence() {
        val serial = FakeSerial().apply { exchangeDelayMillis = 4 }
        val controller = connected(serial)
        serial.commands.clear()
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = List(2) { Thread { start.await(); results += controller.pollOnce(RgoOnePollCadence.FAST) }.apply { start() } }
        start.countDown(); threads.forEach(Thread::join)
        assertTrue(results.count { it } == 1)
        assertEquals(1, serial.maximumInFlight.get())
        controller.close()
    }

    @Test fun safeSetterCoalescesWhenSnapshotAlreadyMatches() {
        val serial = FakeSerial(); val controller = connected(serial)
        serial.commands.clear()
        assertEquals(RgoOneDispatchResult.SENT, controller.dispatch(RgoOneAction.SetFrequency(RgoOneVfo.A, 7_074_000)))
        assertEquals(RgoOneDispatchResult.SENT, controller.dispatch(RgoOneAction.SetFrequency(RgoOneVfo.A, 7_074_000)))
        assertEquals(1, serial.commands.count { it == "FA00007074000;" })
        controller.close()
    }

    @Test fun edgeCommandFailureIsAttemptedOnceWithoutRetry() {
        val serial = FakeSerial().apply { writeSucceeds = false }; val controller = connected(serial)
        assertEquals(RgoOneDispatchResult.INVALID, controller.dispatch(RgoOneAction.ClearRit))
        assertEquals(listOf("RC;"), serial.writes)
        controller.close()
    }

    @Test fun reconnectUsesExactStableIdentityAndResetsStaleTruth() {
        val serial = FakeSerial(); val controller = connected(serial)
        assertTrue(controller.reconnect())
        assertEquals(2, serial.openCount)
        assertEquals(1, serial.closeCount)
        assertTrue(controller.snapshot().connected)
        assertFalse(controller.snapshot().stale)
        controller.close()
    }

    @Test fun unknownGenerationOpensWithoutSendingV6Probe() {
        val serial = FakeSerial(); val controller = RgoOneConnectionController(serial)
        assertTrue(controller.connect(RgoOneSettingsDocument()))
        assertTrue(serial.commands.isEmpty())
        assertTrue(controller.snapshot().status.contains("read-only"))
        controller.close()
    }

    @Test fun ttlConnectionStopsWhenOfficialFramingIsUnavailable() {
        val serial = FakeSerial(); val controller = RgoOneConnectionController(serial)
        assertFalse(controller.connect(RgoOneSettingsDocument(generation = RgoOneGeneration.V6, transport = RgoOneTransportType.TTL_SERIAL)))
        assertEquals(0, serial.openCount)
        assertTrue(controller.snapshot().status.contains("framing"))
        controller.close()
    }

    @Test fun closeIsIdempotentAndClosesOneOpenTransportOnce() {
        val serial = FakeSerial(); val controller = connected(serial)
        controller.close(); controller.close()
        assertEquals(1, serial.closeCount)
        assertEquals(RgoOneConnectionState.CLOSED, controller.snapshot().connectionState)
    }

    @Test fun memoryWriteIsDisabledByDefaultEvenOnFirmware109() {
        val serial = FakeSerial(); val controller = connected(serial)
        val record = RgoOneMemoryRecord(false, 1, 14_074_000, RgoOneMode.DATA, 1, false, false, false, RgoOneAgc.FAST, false)
        assertEquals(RgoOneDispatchResult.DENIED, controller.dispatch(RgoOneAction.WriteMemory(record)))
        assertTrue(serial.writes.isEmpty())
        controller.close()
    }
}
