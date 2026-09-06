package app.shackcq.mobile.radio.hamlib

import app.shackcq.mobile.NativeHandleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class HamlibActionDanger { NORMAL, EDGE, TRANSMIT }

sealed interface HamlibAction {
    val coalescingKey: String? get() = null
    val danger: HamlibActionDanger get() = HamlibActionDanger.NORMAL

    data class SetFrequency(val hz: Long, val vfo: String = "VFOA") : HamlibAction {
        override val coalescingKey = "frequency:$vfo"
    }
    data class SetVfo(val vfo: String) : HamlibAction
    data class SetMode(val mode: String, val passbandHz: Int = 0, val vfo: String = "VFOA") : HamlibAction
    data class SetSplit(val enabled: Boolean, val txVfo: String = "VFOB") : HamlibAction {
        override val danger = HamlibActionDanger.EDGE
    }
    data class SetRit(val hz: Int) : HamlibAction { override val coalescingKey = "rit" }
    data class SetXit(val hz: Int) : HamlibAction { override val coalescingKey = "xit" }
    data class SetLevel(val name: String, val value: Double) : HamlibAction {
        override val coalescingKey = "level:$name"
    }
    data class SetFunction(val name: String, val enabled: Boolean) : HamlibAction {
        override val danger = HamlibActionDanger.EDGE
    }
    data class SetParameter(val name: String, val value: Double) : HamlibAction {
        override val coalescingKey = "parameter:$name"
    }
    data class SetPtt(val enabled: Boolean) : HamlibAction {
        override val danger = HamlibActionDanger.TRANSMIT
    }
    data object Tune : HamlibAction { override val danger = HamlibActionDanger.TRANSMIT }
}

internal class HamlibCommandQueue(
    scope: CoroutineScope,
    private val execute: suspend (HamlibAction, Long) -> Unit,
) {
    private data class Entry(val action: HamlibAction, val generation: Long)
    private val channel = Channel<Entry>(Channel.UNLIMITED)
    private val coalesced = linkedMapOf<String, Entry>()
    private val mutex = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            for (entry in channel) {
                val key = entry.action.coalescingKey
                val next = if (key == null) entry else mutex.withLock { coalesced.remove(key) }
                if (next != null) execute(next.action, next.generation)
            }
        }
    }

    suspend fun submit(action: HamlibAction, generation: Long) {
        val key = action.coalescingKey
        if (key == null) channel.send(Entry(action, generation))
        else {
            val shouldWake = mutex.withLock { coalesced.put(key, Entry(action, generation)) == null }
            if (shouldWake) channel.send(Entry(action, generation))
        }
    }
}

internal class HamlibSession(
    modelId: Int,
    private val api: HamlibNativeApi,
) : Closeable {
    private val handle = NativeHandleOwner(
        api.create(modelId).also { check(it != 0L) { "Hamlib refused model $modelId" } },
        api::destroy,
    )
    private var portClosed = false

    fun setReadOnly(value: Boolean) = withRequiredHandle { checked(api.readOnly(it, value)) }
    fun configure(profile: HamlibSerialProfile) = withRequiredHandle { checked(api.configureSerial(it, profile)) }
    fun configure(profile: HamlibNetworkProfile) = withRequiredHandle { checked(api.configureNetwork(it, profile)) }
    fun open() = withRequiredHandle { checked(api.open(it)) }
    fun snapshot() = withRequiredHandle { HamlibModelRegistry.parseSnapshot(api.snapshot(it)) }
    fun apply(action: HamlibAction) = withRequiredHandle { checked(api.apply(it, action)) }
    fun <T> withHandle(block: (Long) -> T): T? = handle.withHandle(block)

    fun closePort() {
        if (portClosed) return
        portClosed = true
        handle.withHandle { api.close(it) }
    }

    override fun close() {
        closePort()
        handle.close()
    }

    private fun <T> withRequiredHandle(block: (Long) -> T): T =
        checkNotNull(handle.withHandle(block)) { "Hamlib session is closed" }

    private fun checked(status: Int): Int {
        check(status == 0) { api.error(status) }
        return status
    }
}

class HamlibConnectionController internal constructor(
    private val api: HamlibNativeApi = NativeHamlib,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val lifecycle = Mutex()
    private var session: HamlibSession? = null
    private var bridge: HamlibTransportBridge? = null
    private var pollJob: Job? = null
    private val _snapshot = MutableStateFlow<HamlibRadioSnapshot?>(null)
    private val _diagnostics = MutableStateFlow(HamlibDiagnostics())
    val snapshot: StateFlow<HamlibRadioSnapshot?> = _snapshot.asStateFlow()
    val diagnostics: StateFlow<HamlibDiagnostics> = _diagnostics.asStateFlow()
    val registry: HamlibModelRegistry by lazy { HamlibModelRegistry.parse(api.libraryInfo(), api.models()) }
    private val queue = HamlibCommandQueue(scope) { action, requestedGeneration ->
        lifecycle.withLock {
            if (!closed.get() && requestedGeneration == generation.get()) {
                runCatching { session?.apply(action) }.onFailure(::recordFailure)
            }
        }
    }

    suspend fun connectSerial(
        modelId: Int,
        profile: HamlibSerialProfile,
        port: HamlibSerialTransportPort,
        readOnly: Boolean = true,
        pollIntervalMs: Long = 500,
    ) = lifecycle.withLock {
        check(!closed.get()) { "Hamlib controller is closed" }
        disconnectLocked()
        val currentGeneration = generation.incrementAndGet()
        val newSession = HamlibSession(modelId, api)
        var newBridge: HamlibTransportBridge? = null
        try {
            newSession.setReadOnly(readOnly)
            newSession.configure(profile)
            newBridge = HamlibTransportBridge(api, newSession, port)
            newBridge.start(scope, profile)
            newSession.open()
            session = newSession
            bridge = newBridge
            connected(modelId, "serial", currentGeneration, pollIntervalMs)
        } catch (error: Throwable) {
            newSession.closePort()
            newBridge?.stop()
            newSession.close()
            recordFailure(error)
            throw error
        }
    }

    suspend fun connectNetwork(
        modelId: Int,
        profile: HamlibNetworkProfile,
        readOnly: Boolean = true,
        pollIntervalMs: Long = 500,
    ) = lifecycle.withLock {
        check(!closed.get()) { "Hamlib controller is closed" }
        require(profile.enabled) { "Network profile must be explicitly enabled" }
        disconnectLocked()
        val currentGeneration = generation.incrementAndGet()
        val newSession = HamlibSession(modelId, api)
        try {
            newSession.setReadOnly(readOnly)
            newSession.configure(profile)
            newSession.open()
            session = newSession
            connected(modelId, "network", currentGeneration, pollIntervalMs)
        } catch (error: Throwable) {
            newSession.close()
            recordFailure(error)
            throw error
        }
    }

    suspend fun submit(action: HamlibAction) {
        if (!closed.get()) queue.submit(action, generation.get())
    }

    suspend fun disconnect() = lifecycle.withLock { disconnectLocked() }

    private fun connected(modelId: Int, transport: String, currentGeneration: Long, pollIntervalMs: Long) {
        val interval = pollIntervalMs.coerceIn(100, 60_000)
        _diagnostics.value = HamlibDiagnostics(true, currentGeneration, modelId, transport)
        pollJob = scope.launch {
            while (isActive && generation.get() == currentGeneration) {
                val result = runCatching { session?.snapshot() ?: error("Disconnected") }
                result.onSuccess {
                    _snapshot.value = it
                    _diagnostics.value = _diagnostics.value.copy(pollCount = _diagnostics.value.pollCount + 1)
                }.onFailure(::recordFailure)
                delay(interval)
            }
        }
    }

    private suspend fun disconnectLocked() {
        generation.incrementAndGet()
        pollJob?.cancel(); pollJob?.join(); pollJob = null
        session?.closePort()
        bridge?.stop(); bridge = null
        session?.close(); session = null
        _snapshot.value = null
        _diagnostics.value = HamlibDiagnostics(generation = generation.get())
    }

    private fun recordFailure(error: Throwable) {
        _diagnostics.value = _diagnostics.value.copy(lastStatus = -1,
            lastError = error.message?.take(256) ?: error.javaClass.simpleName)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            lifecycle.withLock { disconnectLocked() }
            scope.cancel()
        }
    }
}
