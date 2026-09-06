// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TciConnectionState { DISCONNECTED, CONNECTING, HANDSHAKE, READY, ERROR }

data class TciReceiverSnapshot(
    val id: String,
    val backendIndex: Int,
    val label: String = "RX",
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val active: Boolean = false,
    val listening: Boolean = false,
    val vfoAHz: Long = 0,
    val vfoBHz: Long = 0,
    val selectedChannel: Int = 0,
    val ifOffsetHz: Long? = null,
    val mode: String = "UNKNOWN",
    val passbandHz: Int? = null,
    val split: Boolean? = null,
    val volumeDb: Int? = null,
    val forwardPowerWatts: Double? = null,
    val swr: Double? = null,
    val drivePercent: Int? = null,
    val tuneDrivePercent: Int? = null,
    val transmitting: Boolean? = null,
    val tuning: Boolean? = null,
    val txEnabled: Boolean? = null,
    val txFrequencyHz: Long? = null,
    val txVfo: String? = null,
    val xitEnabled: Boolean? = null,
    val xitOffsetHz: Long? = null,
    val peakPowerWatts: Double? = null,
    val reflectedPowerWatts: Double? = null,
    val alc: Double? = null,
    val txAudioSampleRate: Int? = null,
    val monitorEnabled: Boolean? = null,
    val sampleRate: Int = 0,
    val iqSampleRate: Int = 0,
    val rxAudioSampleRate: Int = 0,
    val iqRunning: Boolean = false,
    val audioRunning: Boolean = false,
    val meterDbm: Double? = null,
    val sourceAgeMillis: Long? = null,
    val droppedFrames: Long = 0,
    val lastError: String? = null,
) {
    val effectiveRxHz: Long get() = (if (selectedChannel == 1) vfoBHz else vfoAHz) + (ifOffsetHz ?: 0L)
}

data class TciRuntimeSnapshot(
    val generation: Long = 0,
    val state: TciConnectionState = TciConnectionState.DISCONNECTED,
    val protocol: String = "UNKNOWN",
    val protocolVersion: String = "UNKNOWN",
    val device: String = "UNKNOWN",
    val receiveOnly: Boolean = true,
    val declaredReceiverCount: Int = 0,
    val channelsCount: Int = 0,
    val activeReceiverId: String? = null,
    val listeningReceiverId: String? = null,
    val receivers: List<TciReceiverSnapshot> = emptyList(),
    val unknownCommands: Long = 0,
    val malformedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val blockedTxFrames: Long = 0,
    val pendingReadbacks: Set<String> = emptySet(),
    val confirmedReadbacks: Long = 0,
    val failedWrites: Long = 0,
    val staleFrames: Long = 0,
    val duplicateStatus: Long = 0,
    val capabilityChanges: Long = 0,
    val streamAttachmentRequired: Boolean = true,
    val reconnectState: String = "OFF · EXPLICIT CONNECT REQUIRED",
    val lastError: String? = null,
) {
    val ready: Boolean get() = state == TciConnectionState.READY
}

class TciRuntimeState {
    private val main = Handler(Looper.getMainLooper())
    var snapshot by mutableStateOf(TciRuntimeSnapshot()); private set
    @Volatile var iqSink: (Int, Int, FloatArray) -> Unit = { _, _, _ -> }
    @Volatile var rxAudioSink: (Int, Int, Int, FloatArray) -> Unit = { _, _, _, _ -> }

    internal fun publish(value: TciRuntimeSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) snapshot = value else main.post { snapshot = value }
    }
}

internal class LatestTciWriteQueue {
    data class Pending(val key: String, val generation: Long, val sequence: Long, val command: String)
    private val rows = mutableMapOf<String, Pending>()
    private var sequence = 0L

    @Synchronized fun offer(key: String, generation: Long, command: String): Pending =
        Pending(key, generation, ++sequence, command).also { rows[key] = it }

    @Synchronized fun takeIfLatest(pending: Pending, generation: Long): String? {
        if (pending.generation != generation || rows[pending.key] != pending) return null
        rows.remove(pending.key)
        return pending.command
    }

    @Synchronized fun clear() = rows.clear()
    @Synchronized fun size(): Int = rows.size
}

class AndroidTciBackendFactory(
    private val runtime: TciRuntimeState,
    private val transmitAuthority: TciTransmitAuthority = TciTransmitAuthority(),
) : RadioBackendFactory {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    override suspend fun create(profile: RadioConnectionProfile): ManagedRadioBackend {
        require(profile.backendKind == RadioBackendKind.NATIVE_TCI)
        return AndroidTciBackend(profile, client, runtime, transmitAuthority)
    }
}

private class AndroidTciBackend(
    override val profile: RadioConnectionProfile,
    private val client: OkHttpClient,
    private val runtime: TciRuntimeState,
    private val transmitAuthority: TciTransmitAuthority,
) : ManagedRadioBackend {
    private val lock = Any()
    private val receivers = linkedMapOf<Int, TciReceiverSnapshot>()
    private val generation = AtomicLong(System.nanoTime())
    private val closed = AtomicBoolean(false)
    private val decodeExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8), { task -> Thread(task, "ShackCQ-TCI-Decode").apply { isDaemon = true } }) { _, _ ->
        synchronized(lock) { publishLocked(current.copy(droppedFrames = current.droppedFrames + 1)) }
    }
    private val writeExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ShackCQ-TCI-Safe-Set").apply { isDaemon = true }
    }
    private val coalescedWrites = LatestTciWriteQueue()
    private val sentReadbacks = ConcurrentHashMap<String, Long>()
    private val readbackSequence = AtomicLong(0)
    @Volatile private var current = TciRuntimeSnapshot(generation = generation.get())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    private var fragment = ""
    private var lastStatusRow = ""
    private val txAdapter = object : TciTransmitAdapter {
        override val deviceIdentity: String get() = "${profile.physicalIdentity ?: "tci:unknown"}|${current.device}"
        override fun readback(): TciTxReadback = synchronized(lock) { txReadbackLocked() }
        override fun sendText(command: String): Boolean = send(command)
        override fun sendBinary(frame: ByteArray): Boolean = frame.isNotEmpty() && socket?.send(frame.toByteString()) == true
    }

    override val snapshot: RadioRuntimeSnapshot
        get() = synchronized(lock) {
            val receiver = receivers.values.firstOrNull { it.id == current.activeReceiverId } ?: receivers.values.firstOrNull()
            RadioRuntimeSnapshot(
                generation = current.generation,
                profileId = profile.id,
                backendKind = RadioBackendKind.NATIVE_TCI,
                modelId = profile.modelId,
                connected = current.ready,
                sourceAgeMillis = receiver?.sourceAgeMillis,
                vfoAHz = available(receiver?.vfoAHz?.takeIf { it > 0 }),
                vfoBHz = available(receiver?.vfoBHz?.takeIf { it > 0 }),
                receiveVfo = available(receiver?.selectedChannel?.let { if (it == 1) "B" else "A" }),
                transmitVfo = receiver?.txVfo?.let(::available) ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
                mode = available(receiver?.mode?.takeUnless { it == "UNKNOWN" }),
                passbandHz = available(receiver?.passbandHz),
                split = receiver?.split?.let(::available) ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
                ritHz = AvailableRadioValue(RadioAvailability.UNAVAILABLE),
                xitHz = receiver?.xitOffsetHz?.toInt()?.let(::available) ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
                sMeter = available(receiver?.meterDbm),
                powerWatts = available(receiver?.forwardPowerWatts),
                swr = available(receiver?.swr),
                alc = receiver?.alc?.let(::available) ?: AvailableRadioValue(RadioAvailability.UNAVAILABLE),
                transmitting = receiver?.transmitting?.let(::available) ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
                capabilities = RadioCapabilitySet(
                    frequency = availability(receiver?.vfoAHz?.takeIf { it > 0 }),
                    vfoB = availability(receiver?.vfoBHz?.takeIf { it > 0 }),
                    mode = availability(receiver?.mode?.takeUnless { it == "UNKNOWN" }),
                    filter = availability(receiver?.passbandHz),
                    split = availability(receiver?.split),
                    ritXit = RadioAvailability.UNAVAILABLE,
                    meters = availability(receiver?.meterDbm),
                    gains = RadioAvailability.UNKNOWN,
                    panadapter = if (receiver?.iqRunning == true) RadioAvailability.AVAILABLE else RadioAvailability.UNKNOWN,
                    iqAudio = RadioAvailability.AVAILABLE,
                    ptt = if (profile.tciAcceptance.permits(TciAcceptanceState.PTT_ACCEPTED)) RadioAvailability.AVAILABLE else RadioAvailability.UNKNOWN,
                    tune = if (profile.tciAcceptance.permits(TciAcceptanceState.TUNE_ACCEPTED)) RadioAvailability.AVAILABLE else RadioAvailability.UNKNOWN,
                    memoryWrite = RadioAvailability.UNAVAILABLE,
                ),
                firmware = "${current.protocol} ${current.protocolVersion}".trim(),
                lastSanitizedError = current.lastError,
            )
        }

    override suspend fun connect(): Boolean {
        if (closed.get()) return false
        if (socket != null || current.state !in setOf(TciConnectionState.DISCONNECTED, TciConnectionState.ERROR)) return false
        synchronized(lock) {
            receivers.clear()
            current = TciRuntimeSnapshot(generation = generation.incrementAndGet(), state = TciConnectionState.CONNECTING)
            coalescedWrites.clear()
            sentReadbacks.clear()
            publishLocked(current)
            ready = CompletableDeferred()
            fragment = ""
            lastStatusRow = ""
        }
        val scheme = if (profile.secureWebSocket) "wss" else "ws"
        val request = runCatching { Request.Builder().url("$scheme://${profile.host}:${profile.port}").build() }.getOrElse {
            fail("Invalid TCI endpoint")
            return false
        }
        val connectionGeneration = current.generation
        socket = client.newWebSocket(request, Listener(connectionGeneration))
        return withTimeoutOrNull(8_000L) { ready.await() } ?: run {
            fail("TCI ready timeout")
            socket?.cancel()
            socket = null
            false
        }
    }

    override suspend fun disconnect() {
        transmitAuthority.globalStop("TCI_DISCONNECT")
        val ws = socket
        synchronized(lock) {
            receivers.values.filter { it.iqRunning }.forEach { receiver -> send(command(NativeTci.IQ_STOP, receiver.backendIndex)) }
            receivers.values.filter { it.audioRunning }.forEach { receiver -> send(command(NativeTci.AUDIO_STOP, receiver.backendIndex)) }
        }
        ws?.close(1000, "Operator disconnect")
        socket = null
        coalescedWrites.clear()
        sentReadbacks.clear()
        disconnected()
    }

    override suspend fun requestReceive(): Boolean {
        val ws = socket ?: return true
        transmitAuthority.globalStop("REQUEST_RECEIVE")
        val value = synchronized(lock) { receivers.values.joinToString("") { command(NativeTci.SAFE_STOP, it.backendIndex) } }
        return value.isBlank() || ws.send(value)
    }

    override suspend fun execute(action: RadioPlatformAction): Boolean {
        if (action.actionClass in setOf(RadioActionClass.TRANSMIT, RadioActionClass.TUNE, RadioActionClass.MEMORY_WRITE)) return false
        if (action.actionClass != RadioActionClass.READ_ONLY &&
            !profile.tciAcceptance.permits(TciAcceptanceState.SAFE_SETTERS_ACCEPTED)) return false
        val active = synchronized(lock) { action.targetReceiver?.let(receivers::get)
            ?: receivers.values.firstOrNull { it.id == current.activeReceiverId } ?: receivers.values.firstOrNull() }
            ?: return false
        return when (action.name.lowercase(Locale.US)) {
            "frequency" -> coalesce("${active.backendIndex}:frequency",
                command(NativeTci.VFO, active.backendIndex, active.selectedChannel, action.longValue ?: return false))
            "mode" -> coalesce("${active.backendIndex}:mode",
                command(NativeTci.MODE, active.backendIndex, text = action.textValue ?: return false))
            "if_offset" -> coalesce("${active.backendIndex}:if",
                command(NativeTci.IF_OFFSET, active.backendIndex, active.selectedChannel, action.longValue ?: return false))
            "split" -> sendTracked("${active.backendIndex}:split",
                command(NativeTci.SPLIT, active.backendIndex, number = if (action.longValue == 1L) 1 else 0))
            "volume" -> coalesce("global:volume", command(NativeTci.VOLUME,
                number = (action.longValue ?: return false).coerceIn(-60, 0)))
            "select_receiver" -> selectReceiver(action.longValue?.toInt() ?: return false, listening = false)
            "listen_receiver" -> selectReceiver(action.longValue?.toInt() ?: return false, listening = true)
            "iq_start" -> stream(active.backendIndex, iq = true, start = true)
            "iq_stop" -> stream(active.backendIndex, iq = true, start = false)
            "audio_start" -> stream(active.backendIndex, iq = false, start = true)
            "audio_stop" -> stream(active.backendIndex, iq = false, start = false)
            "mute" -> sendTracked("${active.backendIndex}:mute",
                command(NativeTci.MUTE, active.backendIndex, number = if (action.longValue == 1L) 1 else 0))
            "rx_enable" -> sendTracked("${active.backendIndex}:enabled",
                command(NativeTci.RX_ENABLE, active.backendIndex, number = if (action.longValue == 1L) 1 else 0))
            else -> false
        }
    }

    override fun close() {
        transmitAuthority.globalStop("TCI_BACKEND_CLOSE")
        closed.set(true)
        socket?.cancel()
        socket = null
        decodeExecutor.shutdownNow()
        writeExecutor.shutdownNow()
        coalescedWrites.clear()
        sentReadbacks.clear()
        disconnected()
    }

    private inner class Listener(private val connectionGeneration: Long) : WebSocketListener() {
        private fun currentConnection(): Boolean = current.generation == connectionGeneration && !closed.get() &&
            current.state in setOf(TciConnectionState.CONNECTING, TciConnectionState.HANDSHAKE, TciConnectionState.READY)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!currentConnection()) { webSocket.cancel(); return }
            synchronized(lock) { publishLocked(current.copy(state = TciConnectionState.HANDSHAKE, lastError = null)) }
            webSocket.send("start;")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                if (!currentConnection()) { publishLocked(current.copy(staleFrames = current.staleFrames + 1)); return }
                fragment = (fragment + text).takeLast(65_536)
                val end = fragment.lastIndexOf(';')
                if (end < 0) return
                val complete = fragment.substring(0, end + 1)
                fragment = fragment.substring(end + 1)
                NativeTci.parseStatus(complete).forEach(::applyStatusLocked)
                publishReceiversLocked()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!currentConnection()) { synchronized(lock) { publishLocked(current.copy(staleFrames = current.staleFrames + 1)) }; return }
            if (bytes.size > 8 * 1024 * 1024) {
                synchronized(lock) { publishLocked(current.copy(malformedFrames = current.malformedFrames + 1, lastError = "TCI frame exceeds 8 MiB")) }
                return
            }
            decodeExecutor.execute {
                if (!currentConnection()) { synchronized(lock) { publishLocked(current.copy(staleFrames = current.staleFrames + 1)) }; return@execute }
                val metadata = IntArray(7)
                val samples = NativeTci.decodeBinary(bytes.toByteArray(), metadata)
                if (metadata[6] != 0) {
                    synchronized(lock) { publishLocked(current.copy(malformedFrames = current.malformedFrames + 1, lastError = "Malformed TCI binary frame (${metadata[6]})")) }
                    return@execute
                }
                val receiver = metadata[0]
                val rateAccepted = synchronized(lock) {
                    val previous = receivers[receiver]
                    val attached = when (metadata[3]) {
                        NativeTci.DATA_IQ -> previous?.iqRunning == true
                        NativeTci.DATA_RX_AUDIO -> previous?.audioRunning == true
                        else -> true
                    }
                    if (!attached) {
                        publishLocked(current.copy(staleFrames = current.staleFrames + 1,
                            lastError = "Unattached TCI receive stream ignored"))
                        false
                    } else if (previous != null && metadata[3] == NativeTci.DATA_IQ &&
                        previous.iqSampleRate > 0 && previous.iqSampleRate != metadata[1]) {
                        receivers[receiver] = previous.copy(iqRunning = false, lastError = "I/Q sample rate changed · explicit stream reattachment required")
                        publishLocked(current.copy(capabilityChanges = current.capabilityChanges + 1, streamAttachmentRequired = true,
                            lastError = "TCI I/Q capability changed; stream stopped"))
                        false
                    } else true
                }
                if (!rateAccepted) return@execute
                when (metadata[3]) {
                    NativeTci.DATA_IQ -> runtime.iqSink(receiver, metadata[1], samples)
                    NativeTci.DATA_RX_AUDIO -> runtime.rxAudioSink(receiver, metadata[1], metadata[4].coerceAtLeast(1), samples)
                    NativeTci.DATA_TX_CHRONO -> transmitAuthority.onChrono(metadata[5])
                    NativeTci.DATA_TX_AUDIO -> synchronized(lock) {
                        publishLocked(current.copy(blockedTxFrames = current.blockedTxFrames + 1,
                            lastError = "Unexpected server TX_AUDIO ignored"))
                    }
                }
                synchronized(lock) {
                    receivers[receiver]?.let { row -> receivers[receiver] = when (metadata[3]) {
                        NativeTci.DATA_IQ -> row.copy(sampleRate = metadata[1], iqSampleRate = metadata[1], sourceAgeMillis = 0)
                        NativeTci.DATA_RX_AUDIO -> row.copy(sampleRate = metadata[1], rxAudioSampleRate = metadata[1], sourceAgeMillis = 0)
                        else -> row.copy(sourceAgeMillis = 0)
                    } }
                    publishReceiversLocked()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (currentConnection()) disconnected() }
        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (currentConnection()) fail(error.message?.replace(Regex("(wss?://)[^/\\s]+"), "$1<endpoint>")?.take(160) ?: "TCI transport failed")
        }
    }

    private fun applyStatusLocked(row: String) {
        if (row == lastStatusRow) {
            publishLocked(current.copy(duplicateStatus = current.duplicateStatus + 1))
            return
        }
        lastStatusRow = row
        val name = row.substringBefore('|')
        val args = row.substringAfter('|', "")
        val fields = args.split(',').map(String::trim)
        fun receiver(): Pair<Int, TciReceiverSnapshot>? {
            val index = fields.firstOrNull()?.toIntOrNull()?.takeIf { it in 0..7 } ?: return null
            return index to receivers.getOrPut(index) { TciReceiverSnapshot("tci:$index", index, "RX ${index + 1}") }
        }
        when (name) {
            "protocol" -> publishLocked(current.copy(protocol = fields.getOrNull(0).orEmpty().ifBlank { "TCI" }, protocolVersion = fields.getOrNull(1).orEmpty().ifBlank { "UNKNOWN" }))
            "device" -> publishLocked(current.copy(device = args.take(96).ifBlank { "UNKNOWN" }))
            "receive_only" -> publishLocked(current.copy(receiveOnly = fields.firstOrNull()?.equals("true", true) != false))
            "trx_count" -> {
                val count = fields.firstOrNull()?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                val changed = current.declaredReceiverCount > 0 && current.declaredReceiverCount != count
                if (changed) {
                    receivers.replaceAll { _, value -> value.copy(iqRunning = false, audioRunning = false,
                        lastError = "Receiver capability changed · explicit stream reattachment required") }
                    receivers.keys.filter { it >= count }.forEach(receivers::remove)
                }
                publishLocked(current.copy(declaredReceiverCount = count,
                    capabilityChanges = current.capabilityChanges + if (changed) 1 else 0,
                    streamAttachmentRequired = current.streamAttachmentRequired || changed))
            }
            "channels_count" -> {
                val count = fields.lastOrNull()?.toIntOrNull()?.coerceIn(0, 2) ?: 0
                val changed = current.channelsCount > 0 && current.channelsCount != count
                if (changed) receivers.replaceAll { _, value -> value.copy(iqRunning = false, audioRunning = false,
                    lastError = "Channel capability changed · explicit stream reattachment required") }
                publishLocked(current.copy(channelsCount = count,
                    capabilityChanges = current.capabilityChanges + if (changed) 1 else 0,
                    streamAttachmentRequired = current.streamAttachmentRequired || changed))
            }
            "vfo" -> receiver()?.let { (index, value) ->
                val channel = fields.getOrNull(1)?.toIntOrNull() ?: 0
                val hz = fields.getOrNull(2)?.toLongOrNull()?.takeIf { it in 100_000L..10_500_000_000L } ?: return@let
                receivers[index] = if (channel == 1) value.copy(vfoBHz = hz, selectedChannel = channel, sourceAgeMillis = 0)
                    else value.copy(vfoAHz = hz, selectedChannel = channel, sourceAgeMillis = 0)
                confirmLocked("$index:frequency")
            }
            "if" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(ifOffsetHz = fields.getOrNull(2)?.toLongOrNull(), sourceAgeMillis = 0)
                confirmLocked("$index:if")
            }
            "modulation" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(mode = fields.getOrNull(1)?.uppercase(Locale.US).orEmpty().ifBlank { "UNKNOWN" }, sourceAgeMillis = 0)
                confirmLocked("$index:mode")
            }
            "split_enable" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(split = fields.getOrNull(1)?.equals("true", true))
                confirmLocked("$index:split")
            }
            "xit_enable" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(xitEnabled = fields.getOrNull(1)?.equals("true", true), sourceAgeMillis = 0)
            }
            "xit_offset" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(xitOffsetHz = fields.getOrNull(1)?.toLongOrNull(), sourceAgeMillis = 0)
            }
            "trx" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(transmitting = fields.getOrNull(1)?.equals("true", true), sourceAgeMillis = 0)
            }
            "tune" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(tuning = fields.getOrNull(1)?.equals("true", true), sourceAgeMillis = 0)
            }
            "tx_enable" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(txEnabled = fields.getOrNull(1)?.equals("true", true), sourceAgeMillis = 0)
            }
            "tx_frequency" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(txFrequencyHz = fields.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }, sourceAgeMillis = 0)
            }
            "rx_enable" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(enabled = fields.getOrNull(1)?.equals("true", true) == true)
                confirmLocked("$index:enabled")
            }
            "mute" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(muted = fields.getOrNull(1)?.equals("true", true) == true)
                confirmLocked("$index:mute")
            }
            "volume" -> fields.firstOrNull()?.toIntOrNull()?.coerceIn(-60, 0)?.let { volume ->
                receivers.replaceAll { _, value -> value.copy(volumeDb = volume) }
                confirmLocked("global:volume")
            }
            "drive" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(drivePercent = fields.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)) }
            "tune_drive" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(tuneDrivePercent = fields.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)) }
            "tx_sensors" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(
                forwardPowerWatts = fields.getOrNull(2)?.toDoubleOrNull()?.takeIf { it >= 0.0 },
                peakPowerWatts = fields.getOrNull(3)?.toDoubleOrNull()?.takeIf { it >= 0.0 },
                swr = fields.getOrNull(4)?.toDoubleOrNull()?.takeIf { it >= 1.0 }, sourceAgeMillis = 0) }
            "audio_samplerate" -> fields.lastOrNull()?.toIntOrNull()?.takeIf { it in setOf(8_000, 12_000, 24_000, 48_000) }?.let { rate ->
                receivers.replaceAll { _, value -> value.copy(txAudioSampleRate = rate) }
            }
            "mon_enable" -> receiver()?.let { (index, value) ->
                receivers[index] = value.copy(monitorEnabled = fields.getOrNull(1)?.equals("true", true))
            }
            "iq_start" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(iqRunning = true) }
            "iq_stop" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(iqRunning = false) }
            "audio_start" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(audioRunning = true) }
            "audio_stop" -> receiver()?.let { (index, value) -> receivers[index] = value.copy(audioRunning = false) }
            "ready" -> {
                if (current.state != TciConnectionState.HANDSHAKE || current.protocol == "UNKNOWN") {
                    publishLocked(current.copy(malformedFrames = current.malformedFrames + 1,
                        lastError = "Malformed or stale TCI ready status"))
                    return
                }
                val count = current.declaredReceiverCount.coerceAtLeast(1)
                repeat(count) { receivers.getOrPut(it) { TciReceiverSnapshot("tci:$it", it, "RX ${it + 1}") } }
                val preferred = profile.preferredInitialReceiver.coerceAtMost(count - 1)
                current = current.copy(state = TciConnectionState.READY, activeReceiverId = "tci:$preferred", listeningReceiverId = "tci:$preferred",
                    receiveOnly = !profile.tciAcceptance.permits(TciAcceptanceState.PTT_ACCEPTED), streamAttachmentRequired = true,
                    reconnectState = "CONTROL READY · EXPLICIT STREAM ATTACHMENT REQUIRED")
                transmitAuthority.attach(profile.id.value, txAdapter.deviceIdentity, profile.tciAcceptance,
                    profile.tciAcceptanceIdentity, txAdapter, profile.tciTxSettings)
                ready.complete(true)
            }
            "start" -> Unit
            else -> publishLocked(current.copy(unknownCommands = current.unknownCommands + 1))
        }
    }

    private fun selectReceiver(index: Int, listening: Boolean): Boolean = synchronized(lock) {
        val row = receivers[index] ?: return false
        if (listening) current = current.copy(listeningReceiverId = row.id) else current = current.copy(activeReceiverId = row.id)
        publishReceiversLocked()
        true
    }

    private fun stream(index: Int, iq: Boolean, start: Boolean): Boolean {
        val kind = if (iq) { if (start) NativeTci.IQ_START else NativeTci.IQ_STOP }
            else if (start) NativeTci.AUDIO_START else NativeTci.AUDIO_STOP
        val accepted = send(command(kind, index))
        if (accepted) synchronized(lock) {
            receivers[index]?.let { receivers[index] = if (iq) it.copy(iqRunning = start) else it.copy(audioRunning = start) }
            if (start) publishLocked(current.copy(streamAttachmentRequired = false))
            publishReceiversLocked()
        }
        return accepted
    }

    private fun command(kind: Int, receiver: Int = 0, channel: Int = 0, number: Long = 0, text: String = ""): String =
        NativeTci.buildCommand(kind, receiver, channel, number, text)

    private fun coalesce(key: String, value: String): Boolean {
        if (value.isBlank() || socket == null) return false
        val pending = coalescedWrites.offer(key, current.generation, value)
        writeExecutor.schedule({
            synchronized(lock) {
                val command = coalescedWrites.takeIfLatest(pending, current.generation) ?: return@synchronized
                if (current.ready && send(command)) {
                    trackReadbackLocked(key)
                } else publishLocked(current.copy(failedWrites = current.failedWrites + 1,
                    lastError = "TCI safe-set was not accepted by the live transport"))
            }
        }, 45, TimeUnit.MILLISECONDS)
        return true
    }

    private fun sendTracked(key: String, value: String): Boolean {
        val accepted = send(value)
        if (accepted) synchronized(lock) {
            trackReadbackLocked(key)
        }
        return accepted
    }

    private fun confirmLocked(key: String) {
        if (sentReadbacks.remove(key) != null) current = current.copy(
            pendingReadbacks = sentReadbacks.keys.toSet(), confirmedReadbacks = current.confirmedReadbacks + 1)
    }

    private fun trackReadbackLocked(key: String) {
        val token = readbackSequence.incrementAndGet()
        sentReadbacks[key] = token
        publishLocked(current.copy(pendingReadbacks = sentReadbacks.keys.toSet()))
        writeExecutor.schedule({ synchronized(lock) {
            if (sentReadbacks[key] == token && sentReadbacks.remove(key, token)) {
                publishLocked(current.copy(pendingReadbacks = sentReadbacks.keys.toSet(), failedWrites = current.failedWrites + 1,
                    lastError = "TCI readback timeout · ${key.substringAfter(':')}"))
            }
        } }, 2, TimeUnit.SECONDS)
    }

    private fun send(value: String): Boolean = value.isNotBlank() && socket?.send(value) == true

    private fun publishReceiversLocked() {
        val active = current.activeReceiverId
        val listening = current.listeningReceiverId
        publishLocked(current.copy(receivers = receivers.values.map { it.copy(active = it.id == active, listening = it.id == listening) }))
        transmitAuthority.publishReadback(txReadbackLocked())
    }

    private fun txReadbackLocked(): TciTxReadback {
        val value = receivers.values.firstOrNull { it.id == current.activeReceiverId } ?: receivers.values.firstOrNull()
        return TciTxReadback(
            connected = current.ready, transmitting = value?.transmitting, tuning = value?.tuning,
            txEnabled = value?.txEnabled, receiver = value?.backendIndex,
            rxFrequencyHz = value?.effectiveRxHz?.takeIf { it > 0 }, txFrequencyHz = value?.txFrequencyHz,
            txVfo = value?.txVfo, split = value?.split, xitEnabled = value?.xitEnabled,
            xitOffsetHz = value?.xitOffsetHz, mode = value?.mode?.takeUnless { it == "UNKNOWN" },
            txFilterHz = value?.passbandHz, drivePercent = value?.drivePercent,
            tuneDrivePercent = value?.tuneDrivePercent, forwardPowerWatts = value?.forwardPowerWatts,
            peakPowerWatts = value?.peakPowerWatts, reflectedPowerWatts = value?.reflectedPowerWatts,
            swr = value?.swr, alc = value?.alc, txAudioRate = value?.txAudioSampleRate,
            monitorEnabled = value?.monitorEnabled, sourceAgeMillis = value?.sourceAgeMillis, sequence = current.generation,
        )
    }

    private fun publishLocked(value: TciRuntimeSnapshot) {
        current = value
        runtime.publish(value)
    }

    private fun disconnected() = synchronized(lock) {
        transmitAuthority.detach("TCI_DISCONNECTED")
        socket = null
        coalescedWrites.clear()
        sentReadbacks.clear()
        receivers.replaceAll { _, value -> value.copy(iqRunning = false, audioRunning = false) }
        publishLocked(current.copy(generation = generation.incrementAndGet(), state = TciConnectionState.DISCONNECTED,
            receivers = receivers.values.toList(), pendingReadbacks = emptySet(), streamAttachmentRequired = true,
            reconnectState = "DISCONNECTED · EXPLICIT CONNECT REQUIRED"))
        if (!ready.isCompleted) ready.complete(false)
    }

    private fun fail(message: String) = synchronized(lock) {
        transmitAuthority.detach("TCI_FAILURE")
        socket = null
        publishLocked(current.copy(state = TciConnectionState.ERROR, lastError = message.take(180)))
        if (!ready.isCompleted) ready.complete(false)
    }

    private fun availability(value: Any?): RadioAvailability = if (value == null) RadioAvailability.UNKNOWN else RadioAvailability.AVAILABLE
    private fun <T> available(value: T?): AvailableRadioValue<T> = AvailableRadioValue(availability(value), value)
}
