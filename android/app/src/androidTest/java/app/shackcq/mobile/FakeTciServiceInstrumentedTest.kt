package app.shackcq.mobile

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeTciServiceInstrumentedTest {
    @Test fun deterministicLocalServiceCompletesTwoReceiverHandshakeAndKeepsTxBlocked() {
        FakeTciService().use { service ->
            val runtime = TciRuntimeState()
            val profile = RadioConnectionProfile(
                id = RadioProfileId("test.tci"),
                name = "Deterministic TCI",
                backendKind = RadioBackendKind.NATIVE_TCI,
                modelId = RadioModelId("TCI:TEST"),
                manufacturer = "TEST",
                model = "FAKE",
                transport = RadioTransportType.TCI,
                host = "127.0.0.1",
                port = service.port,
                readOnly = false,
                automaticSafeReconnect = false,
            )
            val backend = runBlocking { AndroidTciBackendFactory(runtime).create(profile) }
            try {
                assertTrue(runBlocking { backend.connect() })
                assertTrue(service.connected.await(2, TimeUnit.SECONDS))
                assertTrue(runtime.snapshot.ready)
                assertEquals(2, runtime.snapshot.receivers.size)
                assertEquals(14_074_000L, runtime.snapshot.receivers[0].effectiveRxHz)
                assertEquals(7_074_000L, runtime.snapshot.receivers[1].effectiveRxHz)
                assertFalse(runBlocking {
                    backend.execute(RadioPlatformAction(RadioActionClass.TRANSMIT, "ptt", longValue = 1))
                })
            } finally {
                runBlocking { backend.disconnect() }
                backend.close()
            }
        }
    }

    @Test fun repeatedReconnectRequiresExplicitStreamAttachmentAndDoesNotRestoreTransmit() {
        FakeTciService(100).use { service ->
            val runtime = TciRuntimeState()
            val profile = RadioConnectionProfile(
                id = RadioProfileId("test.tci.reconnect"), name = "TCI reconnect stress",
                backendKind = RadioBackendKind.NATIVE_TCI, modelId = RadioModelId("TCI:TEST"),
                manufacturer = "TEST", model = "FAKE", transport = RadioTransportType.TCI,
                host = "127.0.0.1", port = service.port, readOnly = false, automaticSafeReconnect = false,
            )
            val backend = runBlocking { AndroidTciBackendFactory(runtime).create(profile) }
            try {
                repeat(100) {
                    assertTrue(runBlocking { backend.connect() })
                    assertTrue(runtime.snapshot.receiveOnly)
                    assertTrue(runtime.snapshot.streamAttachmentRequired)
                    assertFalse(runtime.snapshot.receivers.any { receiver -> receiver.iqRunning || receiver.audioRunning })
                    runBlocking { backend.disconnect() }
                }
                assertTrue(service.connected.await(10, TimeUnit.SECONDS))
            } finally { backend.close() }
        }
    }
}

private class FakeTciService(private val connectionCount: Int = 1) : AutoCloseable {
    private val server = ServerSocket(0, 1)
    val port: Int = server.localPort
    val connected = CountDownLatch(connectionCount)
    private val worker = Thread({
        runCatching {
            repeat(connectionCount) {
                server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                var key = ""
                while (true) {
                    val line = reader.readLine() ?: return@use
                    if (line.isBlank()) break
                    if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
                }
                val accept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII),
                    ),
                    Base64.NO_WRAP,
                )
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                val status = "protocol:TCI,1.9;device:ShackCQ Fake;receive_only:true;trx_count:2;channels_count:2;" +
                    "vfo:0,0,14074000;modulation:0,USB;vfo:1,0,7074000;modulation:1,DIGU;ready;"
                val bytes = status.toByteArray(Charsets.UTF_8)
                if (bytes.size < 126) output.write(byteArrayOf(0x81.toByte(), bytes.size.toByte()))
                else output.write(byteArrayOf(0x81.toByte(), 126, (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                output.write(bytes)
                output.flush()
                connected.countDown()
                Thread.sleep(10)
                }
            }
        }
    }, "ShackCQ-Fake-TCI").apply { isDaemon = true; start() }

    override fun close() {
        runCatching { server.close() }
        worker.interrupt()
        worker.join(1_000)
    }
}
