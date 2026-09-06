package app.shackcq.mobile.rotator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

sealed interface RotatorResponseRule {
    data object None : RotatorResponseRule
    data class Line(val terminator: Byte = '\n'.code.toByte()) : RotatorResponseRule
    data class UntilRprt(val terminator: Byte = '\n'.code.toByte()) : RotatorResponseRule
    data class Fixed(val size: Int) : RotatorResponseRule { init { require(size in 1..MAX_ROTATOR_RESPONSE_BYTES) } }
}

interface RotatorTransport : Closeable {
    val connected: Boolean
    suspend fun open()
    suspend fun transact(command: RotatorWireCommand, responseRule: RotatorResponseRule): ByteArray
}

interface RotatorSerialTransportPort {
    suspend fun open(profileId: String, settings: SerialSettings): RotatorTransport
}

class RotatorTcpTransport(private val settings: TcpSettings) : RotatorTransport {
    private val transactionMutex = Mutex()
    private var socket: Socket? = null
    override val connected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        require(settings.lanOptIn) { "LAN control requires explicit opt-in" }
        val candidate = Socket()
        try {
            candidate.connect(InetSocketAddress(settings.host, settings.port), settings.connectTimeoutMs)
            candidate.soTimeout = settings.readTimeoutMs
            candidate.tcpNoDelay = true
            socket = candidate
        } catch (failure: Exception) {
            runCatching { candidate.close() }
            throw IllegalStateException("rotator TCP connection failed", failure)
        }
    }

    override suspend fun transact(command: RotatorWireCommand, responseRule: RotatorResponseRule): ByteArray =
        transactionMutex.withLock {
            withContext(Dispatchers.IO) {
                val active = socket?.takeIf { connected } ?: throw IllegalStateException("rotator transport disconnected")
                try {
                    active.getOutputStream().write(command.bytes)
                    active.getOutputStream().flush()
                    if (!command.expectsResponse || responseRule == RotatorResponseRule.None) return@withContext ByteArray(0)
                    readBounded(active, responseRule)
                } catch (failure: Exception) {
                    close()
                    throw IllegalStateException("rotator transaction failed", failure)
                }
            }
        }

    private fun readBounded(active: Socket, rule: RotatorResponseRule): ByteArray {
        val input = active.getInputStream()
        val output = ArrayList<Byte>()
        fun append(value: Int) {
            if (value < 0) throw IllegalStateException("rotator closed connection")
            if (output.size >= MAX_ROTATOR_RESPONSE_BYTES) throw IllegalStateException("rotator response exceeds 4096 bytes")
            output.add(value.toByte())
        }
        when (rule) {
            is RotatorResponseRule.Fixed -> repeat(rule.size) { append(input.read()) }
            is RotatorResponseRule.Line -> while (true) { val value = input.read(); append(value); if (value.toByte() == rule.terminator) break }
            is RotatorResponseRule.UntilRprt -> {
                val line = ArrayList<Byte>()
                while (true) {
                    val value = input.read(); append(value); line.add(value.toByte())
                    if (value.toByte() == rule.terminator) {
                        val text = line.toByteArray().toString(Charsets.US_ASCII).trim()
                        if (text.startsWith("RPRT ")) break
                        line.clear()
                    }
                }
            }
            RotatorResponseRule.None -> Unit
        }
        return output.toByteArray()
    }

    override fun close() {
        val active = socket
        socket = null
        runCatching { active?.close() }
    }
}

data class QueuedRotatorCommand(
    val id: Long,
    val generation: Long,
    val action: RotatorAction,
    val azimuthDeg: Double? = null,
    val elevationDeg: Double? = null,
    val physicalMotion: Boolean,
)

class RotatorCommandQueue {
    private val pending = ArrayDeque<QueuedRotatorCommand>()
    private var acknowledgedMotion = false

    @Synchronized fun enqueue(command: QueuedRotatorCommand) {
        if (command.action == RotatorAction.STOP) {
            pending.removeAll { it.action != RotatorAction.STOP }
            pending.addFirst(command)
            return
        }
        if (command.action == RotatorAction.MOVE_ABSOLUTE && !acknowledgedMotion) {
            pending.removeAll { it.action == RotatorAction.MOVE_ABSOLUTE }
        }
        pending.addLast(command)
    }

    @Synchronized fun next(): QueuedRotatorCommand? = pending.removeFirstOrNull()
    @Synchronized fun markAcknowledged(command: QueuedRotatorCommand) { if (command.physicalMotion) acknowledgedMotion = true }
    @Synchronized fun movementConfirmed() { acknowledgedMotion = false }
    @Synchronized fun cancelGeneration(generation: Long) { pending.removeAll { it.generation != generation || it.physicalMotion } }
    @Synchronized fun clear() { pending.clear(); acknowledgedMotion = false }
    @Synchronized fun size(): Int = pending.size
}
