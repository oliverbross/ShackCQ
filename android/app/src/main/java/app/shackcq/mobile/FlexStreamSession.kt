package app.shackcq.mobile

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

enum class FlexUdpMode { LAN, SMARTLINK }

class FlexStreamSession(
    private val command: (String) -> Boolean,
    private val event: (FlexVitaEvent) -> Unit,
    private val failure: (String) -> Unit,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    val engine = FlexVitaEngine()
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var registrationJob: Job? = null
    private val firstFlexVita = AtomicBoolean(false)
    @Volatile private var peer: SocketAddress? = null
    var mode: FlexUdpMode? = null
        private set
    var localPort: Int = 0
        private set
    var expectedHost: String = ""
        private set

    @Synchronized
    fun start(mode: FlexUdpMode, handle: Long, expectedHost: String): Boolean {
        stop()
        if (handle == 0L) return false
        val udp = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 4 * 1024 * 1024
                soTimeout = 400
                bind(InetSocketAddress(0))
            }
        }.getOrElse {
            failure(it.message ?: "Unable to create Flex UDP socket")
            return false
        }
        this.mode = mode
        this.socket = udp
        this.localPort = udp.localPort
        this.expectedHost = expectedHost
        firstFlexVita.set(false)
        peer = null
        receiveJob = scope.launch { receive(udp, expectedHost, mode) }
        registrationJob = scope.launch {
            if (mode == FlexUdpMode.LAN) {
                if (!command("client udpport ${udp.localPort}")) failure("Flex LAN UDP registration command failed")
                return@launch
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (isActive && !firstFlexVita.get() && SystemClock.elapsedRealtime() < deadline) {
                command("client udp_register handle=0x${handle.toString(16).uppercase()}")
                delay(50)
            }
            if (!firstFlexVita.get()) {
                failure("SmartLink UDP registration timed out after 30 seconds")
                return@launch
            }
            while (isActive) {
                command("client ping handle=0x${handle.toString(16).uppercase()}")
                delay(5_000)
            }
        }
        return true
    }

    fun register(streamId: Long, kind: FlexStreamKind): Boolean = engine.register(streamId, kind)
    fun unregister(streamId: Long) = engine.unregister(streamId)

    private suspend fun receive(udp: DatagramSocket, expectedHost: String, mode: FlexUdpMode) {
        val expectedAddress = if (expectedHost.isBlank()) null else runCatching { InetAddress.getByName(expectedHost) }.getOrNull()
        val buffer = ByteArray(65_536)
        try {
            while (scope.isActive && !udp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (mode == FlexUdpMode.LAN && expectedAddress != null && packet.address != expectedAddress) continue
                val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                FlexVitaPacket.parse(datagram) ?: continue
                peer = packet.socketAddress
                firstFlexVita.compareAndSet(false, true)
                engine.feed(datagram)?.let(event)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            if (!udp.isClosed) failure(error.message ?: "Flex VITA receiver stopped")
        }
    }

    @Synchronized
    fun stop() {
        registrationJob?.cancel()
        receiveJob?.cancel()
        registrationJob = null
        receiveJob = null
        runCatching { socket?.close() }
        socket = null
        firstFlexVita.set(false)
        peer = null
        engine.clear()
        mode = null
        localPort = 0
        expectedHost = ""
    }

    @Synchronized
    fun send(datagram: ByteArray): Boolean {
        if (datagram.isEmpty() || datagram.size > 65_536) return false
        val destination = peer ?: if (mode == FlexUdpMode.LAN && expectedHost.isNotBlank()) {
            InetSocketAddress(expectedHost, 4991)
        } else return false
        val udp = socket ?: return false
        return runCatching {
            udp.send(DatagramPacket(datagram, datagram.size, destination))
            true
        }.getOrDefault(false)
    }

    override fun close() {
        stop()
        scope.cancel()
    }
}

internal class FlexStatusFramer {
    private val pending = StringBuilder()

    fun feed(bytes: ByteArray): List<String> {
        val output = mutableListOf<String>()
        bytes.forEach { byte ->
            when (byte.toInt().toChar()) {
                '\r', '\n' -> if (pending.isNotEmpty()) {
                    output += pending.toString()
                    pending.clear()
                }
                else -> if (pending.length < 16 * 1024) pending.append(byte.toInt().toChar()) else pending.clear()
            }
        }
        return output
    }
}

internal fun parseFlexNumber(value: String?): Long? {
    val text = value?.trim()?.trim('"') ?: return null
    return if (text.startsWith("0x", true)) text.substring(2).toLongOrNull(16)
    else text.toLongOrNull() ?: text.toLongOrNull(16)
}

internal fun flexFields(body: String): Map<String, String> = body.split(Regex("\\s+"))
    .mapNotNull { word -> word.split('=', limit = 2).takeIf { it.size == 2 } }
    .associate { it[0] to it[1].trim('"') }

internal fun streamFromStatus(body: String): Pair<Long, FlexStreamKind>? {
    val fields = flexFields(body)
    val streamId = parseFlexNumber(fields["stream_id"] ?: fields["stream"]) ?: return null
    val lower = body.lowercase()
    val kind = when {
        lower.startsWith("display pan ") -> FlexStreamKind.PANADAPTER
        lower.startsWith("display waterfall ") -> FlexStreamKind.WATERFALL
        lower.startsWith("meter ") -> FlexStreamKind.METER
        "type=remote_audio_rx" in lower && "compression=opus" in lower -> FlexStreamKind.OPUS_AUDIO
        "type=remote_audio_rx" in lower -> FlexStreamKind.REMOTE_AUDIO
        "type=dax_rx" in lower -> FlexStreamKind.REDUCED_AUDIO
        else -> return null
    }
    return streamId to kind
}
