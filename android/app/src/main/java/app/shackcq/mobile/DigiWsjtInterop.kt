package app.shackcq.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DigiInteropState(
    val enabled: Boolean = false, val bind: String = "127.0.0.1:2237", val target: String = "127.0.0.1:2237",
    val lastHeartbeatEpoch: Long = 0, val lastReceivedEpoch: Long = 0, val accepted: Long = 0, val rejected: Long = 0,
    val lastError: String = "", val companionMode: Boolean = false,
)

object WsjtDatagram {
    private const val MAGIC = 0xadbccbda.toInt()
    private const val SCHEMA = 3
    private fun packet(type: Int, id: String, body: DataOutputStream.() -> Unit = {}): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out -> out.writeInt(MAGIC); out.writeInt(SCHEMA); out.writeInt(type); out.qString(id); out.body() }
        bytes.toByteArray()
    }
    private fun DataOutputStream.qString(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    fun heartbeat(id: String, version: String, revision: String = "ShackCQ") = packet(0, id) { writeInt(3); qString(version); qString(revision) }
    fun status(id: String, frequencyHz: Long, mode: String, dxCall: String, report: String, txMode: String,
        txEnabled: Boolean, transmitting: Boolean, decoding: Boolean, rxHz: Int, txHz: Int, stationCall: String, stationGrid: String) = packet(1, id) {
        writeLong(frequencyHz); qString(mode); qString(dxCall); qString(report); qString(txMode)
        writeBoolean(txEnabled); writeBoolean(transmitting); writeBoolean(decoding); writeInt(rxHz); writeInt(txHz)
        qString(stationCall); qString(stationGrid); qString(""); writeBoolean(false); qString(""); writeBoolean(false); writeByte(0); qString(""); qString("")
    }
    fun decode(id: String, isNew: Boolean, milliseconds: Int, snr: Int, dt: Double, deltaHz: Int, mode: String, message: String, lowConfidence: Boolean = false, offAir: Boolean = false) = packet(2, id) {
        writeBoolean(isNew); writeInt(milliseconds); writeInt(snr); writeDouble(dt); writeInt(deltaHz); qString(mode); qString(message); writeBoolean(lowConfidence); writeBoolean(offAir)
    }
    fun qsoLogged(id: String, draft: DigiQsoDraft) = packet(5, id) {
        val startedMs = draft.startEpoch * 1_000L; val endedMs = draft.endEpoch * 1_000L
        writeLong(startedMs); qString(draft.callsign); qString(draft.grid); writeLong(draft.dialFrequencyHz)
        qString(draft.mode); qString(draft.sentReport); qString(draft.receivedReport); qString(""); qString("")
        writeLong(endedMs); qString(""); qString(""); qString(""); qString(""); qString(draft.operatorCallsign)
        qString(draft.stationCallsign); qString(draft.stationGrid); qString(draft.comment)
    }
    internal fun control(id: String, type: Int): ByteArray {
        require(type in setOf(3, 7, 8))
        return packet(type, id)
    }
    fun headerType(bytes: ByteArray): Int? {
        if (bytes.size < 12) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return if (input.int == MAGIC && input.int in 2..3) input.int else null
    }
}

class DigiWsjtInterop(
    private val onHaltTx: () -> Unit,
    private val onClear: () -> Unit,
    private val onReplay: () -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var target: InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 2237)
    var state = DigiInteropState(); private set
    val localModemAllowed get() = !state.companionMode

    fun start(host: String, port: Int, companionMode: Boolean, allowLan: Boolean = false): Boolean {
        stop()
        val address = runCatching { InetAddress.getByName(host) }.getOrElse { state = state.copy(lastError = "Invalid UDP address"); return false }
        if (!allowLan && !address.isLoopbackAddress) { state = state.copy(lastError = "LAN UDP requires advanced opt-in"); return false }
        return runCatching {
            target = InetSocketAddress(address, port)
            val opened = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)); soTimeout = 1_000 }
            socket = opened
            state = DigiInteropState(true, "127.0.0.1:$port", "$host:$port", companionMode = companionMode)
            receiveJob = scope.launch { receiveLoop(opened) }
            heartbeatJob = scope.launch { while (isActive) { send(WsjtDatagram.heartbeat("ShackCQ", NativeCore.version())); state = state.copy(lastHeartbeatEpoch = System.currentTimeMillis() / 1_000); delay(15_000) } }
            true
        }.getOrElse { state = state.copy(lastError = it.message.orEmpty()); false }
    }

    fun send(bytes: ByteArray) {
        val opened = socket ?: return
        runCatching { opened.send(DatagramPacket(bytes, bytes.size, target)) }
            .onFailure { state = state.copy(lastError = it.message.orEmpty()) }
    }

    private fun receiveLoop(opened: DatagramSocket) {
        val buffer = ByteArray(65_507)
        while (!opened.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { opened.receive(packet); packet.data.copyOf(packet.length) }.getOrNull() ?: continue
            val accepted = handleIncoming(received)
            state = state.copy(lastReceivedEpoch = System.currentTimeMillis() / 1_000,
                accepted = state.accepted + if (accepted) 1 else 0, rejected = state.rejected + if (accepted) 0 else 1)
        }
    }

    internal fun handleIncoming(received: ByteArray): Boolean = when (WsjtDatagram.headerType(received)) {
        8 -> { onHaltTx(); true }
        3 -> { onClear(); true }
        7 -> { onReplay(); true }
        else -> false
    }

    fun stop() {
        receiveJob?.cancel(); heartbeatJob?.cancel(); receiveJob = null; heartbeatJob = null
        socket?.close(); socket = null
        state = state.copy(enabled = false)
    }
    override fun close() { stop(); scope.cancel() }
}
