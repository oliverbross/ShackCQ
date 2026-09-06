package app.shackcq.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class RadioProfileId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9][a-z0-9._:-]{1,95}"))) }
    override fun toString(): String = value
}

@JvmInline
value class RadioModelId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
    override fun toString(): String = value
}

enum class RadioBackendKind {
    NATIVE_ELECRAFT,
    NATIVE_FLEX,
    NATIVE_QMX,
    NATIVE_RGO_ONE,
    HAMLIB_EMBEDDED,
    HAMLIB_NETWORK,
    NATIVE_TCI,
    REMOTE_STATION,
}

enum class RadioTransportType { USB_SERIAL, LAN, RIGCTLD, FLRIG, TCI, REMOTE_STATION }
enum class RadioActionClass { READ_ONLY, SAFE_SET, EDGE_TRIGGERED, TRANSMIT, TUNE, MEMORY_WRITE, EMERGENCY_RECEIVE }
enum class RadioAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class RadioCapabilitySet(
    val frequency: RadioAvailability = RadioAvailability.UNKNOWN,
    val vfoB: RadioAvailability = RadioAvailability.UNKNOWN,
    val mode: RadioAvailability = RadioAvailability.UNKNOWN,
    val filter: RadioAvailability = RadioAvailability.UNKNOWN,
    val split: RadioAvailability = RadioAvailability.UNKNOWN,
    val ritXit: RadioAvailability = RadioAvailability.UNKNOWN,
    val meters: RadioAvailability = RadioAvailability.UNKNOWN,
    val gains: RadioAvailability = RadioAvailability.UNKNOWN,
    val panadapter: RadioAvailability = RadioAvailability.UNAVAILABLE,
    val iqAudio: RadioAvailability = RadioAvailability.UNAVAILABLE,
    val ptt: RadioAvailability = RadioAvailability.UNKNOWN,
    val tune: RadioAvailability = RadioAvailability.UNKNOWN,
    val memoryWrite: RadioAvailability = RadioAvailability.UNKNOWN,
)

data class AvailableRadioValue<T>(
    val availability: RadioAvailability,
    val value: T? = null,
) {
    init { require(availability == RadioAvailability.AVAILABLE || value == null) }
}

data class RadioConnectionProfile(
    val id: RadioProfileId,
    val name: String,
    val backendKind: RadioBackendKind,
    val modelId: RadioModelId,
    val manufacturer: String,
    val model: String,
    val transport: RadioTransportType,
    val stableSerialIdentityHash: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val hamlibModelId: Int? = null,
    val baud: Int = 38_400,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: String = "N",
    val readOnly: Boolean = false,
    val pollCadenceMillis: Long = 500,
    val automaticSafeReconnect: Boolean = false,
    val secureWebSocket: Boolean = false,
    val preferredIqSampleRate: Int = 96_000,
    val preferredInitialReceiver: Int = 0,
    val rxAudioRoute: String = "SYSTEM",
    val tciAcceptance: TciAcceptanceState = TciAcceptanceState.UNVERIFIED,
    val tciAcceptanceIdentity: String? = null,
    val tciTxSettings: TciTxSettings = TciTxSettings(),
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require(manufacturer.length <= 80 && model.isNotBlank() && model.length <= 96)
        require(stableSerialIdentityHash == null || stableSerialIdentityHash.matches(Regex("[a-fA-F0-9]{16,128}")))
        require(host == null || (host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' }))
        require(port == null || port in 1..65_535)
        require(baud in 300..3_000_000 && dataBits in 5..8 && stopBits in 1..2 && parity in setOf("N", "E", "O"))
        require(pollCadenceMillis in 100..60_000)
        require(preferredIqSampleRate in setOf(48_000, 96_000, 192_000, 240_000, 384_000))
        require(preferredInitialReceiver in 0..7)
        require(rxAudioRoute in setOf("SYSTEM", "RECEIVER_0", "RECEIVER_1", "STEREO_SPLIT", "BALANCED_MIX"))
        require(tciAcceptanceIdentity == null || tciAcceptanceIdentity.length in 8..512)
        require((backendKind == RadioBackendKind.HAMLIB_EMBEDDED || backendKind == RadioBackendKind.HAMLIB_NETWORK) == (hamlibModelId != null))
        require(transport == RadioTransportType.USB_SERIAL || host != null)
    }

    val physicalIdentity: String? get() = stableSerialIdentityHash?.let { "serial:$it" }
        ?: host?.let { "network:${it.lowercase()}:$port" }
}

data class RadioRuntimeSnapshot(
    val generation: Long = 0,
    val profileId: RadioProfileId? = null,
    val backendKind: RadioBackendKind? = null,
    val modelId: RadioModelId? = null,
    val connected: Boolean = false,
    val sourceAgeMillis: Long? = null,
    val vfoAHz: AvailableRadioValue<Long> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val vfoBHz: AvailableRadioValue<Long> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val receiveVfo: AvailableRadioValue<String> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val transmitVfo: AvailableRadioValue<String> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val mode: AvailableRadioValue<String> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val submode: AvailableRadioValue<String> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val passbandHz: AvailableRadioValue<Int> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val split: AvailableRadioValue<Boolean> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val ritHz: AvailableRadioValue<Int> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val xitHz: AvailableRadioValue<Int> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val sMeter: AvailableRadioValue<Double> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val powerWatts: AvailableRadioValue<Double> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val swr: AvailableRadioValue<Double> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val alc: AvailableRadioValue<Double> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val transmitting: AvailableRadioValue<Boolean> = AvailableRadioValue(RadioAvailability.UNKNOWN),
    val capabilities: RadioCapabilitySet = RadioCapabilitySet(),
    val firmware: String? = null,
    val lastSanitizedError: String? = null,
)

data class RadioPlatformAction(
    val actionClass: RadioActionClass,
    val name: String,
    val longValue: Long? = null,
    val textValue: String? = null,
    val targetReceiver: Int? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 64 && (textValue == null || textValue.length <= 128))
        require(targetReceiver == null || targetReceiver in 0..7)
    }
}

interface ManagedRadioBackend {
    val profile: RadioConnectionProfile
    val snapshot: RadioRuntimeSnapshot
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun requestReceive(): Boolean
    suspend fun execute(action: RadioPlatformAction): Boolean
    fun close()
}

fun interface RadioBackendFactory {
    suspend fun create(profile: RadioConnectionProfile): ManagedRadioBackend
}

class PhysicalDeviceAuthority {
    private val owners = mutableMapOf<String, String>()

    @Synchronized
    fun acquire(identity: String, owner: String): Boolean {
        val current = owners[identity]
        if (current != null && current != owner) return false
        owners[identity] = owner
        return true
    }

    @Synchronized
    fun release(identity: String?, owner: String) {
        if (identity != null && owners[identity] == owner) owners.remove(identity)
    }

    @Synchronized
    fun owner(identity: String): String? = owners[identity]
}

class RadioPlatformController(
    private val factories: Map<RadioBackendKind, RadioBackendFactory>,
    private val devices: PhysicalDeviceAuthority = PhysicalDeviceAuthority(),
    private val disarmTransmitWorkflows: () -> Unit = {},
) {
    private val lifecycle = Mutex()
    private var active: ManagedRadioBackend? = null
    private var selected: RadioConnectionProfile? = null
    private var generation = 0L

    val selectedProfile: RadioConnectionProfile? get() = selected
    val snapshot: RadioRuntimeSnapshot get() = active?.snapshot ?: RadioRuntimeSnapshot(
        generation = generation,
        profileId = selected?.id,
        backendKind = selected?.backendKind,
        modelId = selected?.modelId,
    )

    suspend fun select(profile: RadioConnectionProfile?, connectAfterSelection: Boolean = false): Boolean = lifecycle.withLock {
        stopActiveLocked()
        disarmTransmitWorkflows()
        selected = profile
        generation++
        if (profile == null || !connectAfterSelection) return@withLock true
        connectLocked(profile)
    }

    suspend fun connectSelected(): Boolean = lifecycle.withLock {
        val profile = selected ?: return@withLock false
        if (active != null) return@withLock active?.snapshot?.connected == true
        connectLocked(profile)
    }

    suspend fun disconnect() = lifecycle.withLock {
        stopActiveLocked()
        disarmTransmitWorkflows()
        generation++
    }

    suspend fun dispatch(action: RadioPlatformAction, operatorConfirmed: Boolean = false): Boolean = lifecycle.withLock {
        val backend = active ?: return@withLock false
        if (action.actionClass == RadioActionClass.EMERGENCY_RECEIVE) return@withLock backend.requestReceive()
        if (backend.profile.readOnly && action.actionClass != RadioActionClass.READ_ONLY) return@withLock false
        if (action.actionClass in setOf(RadioActionClass.TRANSMIT, RadioActionClass.TUNE, RadioActionClass.MEMORY_WRITE) && !operatorConfirmed)
            return@withLock false
        backend.execute(action)
    }

    suspend fun stopAndDisarm(): Boolean = lifecycle.withLock {
        disarmTransmitWorkflows()
        active?.requestReceive() ?: true
    }

    suspend fun close() = lifecycle.withLock {
        stopActiveLocked()
        selected = null
        disarmTransmitWorkflows()
        generation++
    }

    private suspend fun connectLocked(profile: RadioConnectionProfile): Boolean {
        val factory = factories[profile.backendKind] ?: return false
        val owner = "radio:${profile.id.value}"
        if (profile.physicalIdentity?.let { devices.acquire(it, owner) } == false) return false
        val backend = runCatching { factory.create(profile) }.getOrElse {
            devices.release(profile.physicalIdentity, owner)
            return false
        }
        active = backend
        val connected = runCatching { backend.connect() }.getOrDefault(false)
        if (!connected) {
            runCatching { backend.disconnect() }
            backend.close()
            active = null
            devices.release(profile.physicalIdentity, owner)
        }
        return connected
    }

    private suspend fun stopActiveLocked() {
        val backend = active ?: return
        runCatching { backend.requestReceive() }
        runCatching { backend.disconnect() }
        backend.close()
        devices.release(backend.profile.physicalIdentity, "radio:${backend.profile.id.value}")
        active = null
    }
}

object RadioProfileCatalog {
    val KX3 = native("native.elecraft.kx3", "Elecraft KX3", RadioBackendKind.NATIVE_ELECRAFT, "KX3", 38_400)
    val KX2 = native("native.elecraft.kx2", "Elecraft KX2", RadioBackendKind.NATIVE_ELECRAFT, "KX2", 38_400)
    val FLEX = RadioConnectionProfile(RadioProfileId("native.flexradio"), "FlexRadio", RadioBackendKind.NATIVE_FLEX,
        RadioModelId("FLEXRADIO"), "FlexRadio", "FlexRadio", RadioTransportType.LAN, host = "127.0.0.1", port = 4_992)
    val QMX = native("native.qrplabs.qmx", "QMX", RadioBackendKind.NATIVE_QMX, "QMX", 115_200)
    val QMX_PLUS = native("native.qrplabs.qmxplus", "QMX+", RadioBackendKind.NATIVE_QMX, "QMX_PLUS", 115_200)
    val RGO_V6 = native("native.rgoone.v6", "RGO ONE V6", RadioBackendKind.NATIVE_RGO_ONE, "RGO_ONE_V6", 57_600)
    val RGO_CONSERVATIVE = native("native.rgoone.conservative", "RGO ONE legacy / unknown", RadioBackendKind.NATIVE_RGO_ONE,
        "RGO_ONE_UNKNOWN", 57_600).copy(readOnly = true)
    val UNKNOWN = native("safe.unknown", "Unknown radio profile", RadioBackendKind.NATIVE_ELECRAFT, "UNKNOWN", 38_400).copy(readOnly = true)
    val nativeProfiles = listOf(KX3, KX2, FLEX, QMX, QMX_PLUS, RGO_V6, RGO_CONSERVATIVE)

    fun find(id: RadioProfileId): RadioConnectionProfile? = nativeProfiles.firstOrNull { it.id == id }

    fun migrate(storedProfileId: String?, legacyFamily: String?): RadioProfileId = when {
        storedProfileId == null -> when (legacyFamily) {
            "ELECRAFT_KX2" -> KX2.id
            "FLEXRADIO" -> FLEX.id
            null, "ELECRAFT_KX", "ELECRAFT_KX3" -> KX3.id
            else -> UNKNOWN.id
        }
        nativeProfiles.any { it.id.value == storedProfileId } -> RadioProfileId(storedProfileId)
        else -> UNKNOWN.id
    }

    private fun native(id: String, name: String, backend: RadioBackendKind, model: String, baud: Int) =
        RadioConnectionProfile(RadioProfileId(id), name, backend, RadioModelId(model),
            when (backend) {
                RadioBackendKind.NATIVE_ELECRAFT -> "Elecraft"
                RadioBackendKind.NATIVE_QMX -> "QRP Labs"
                RadioBackendKind.NATIVE_RGO_ONE -> "RGO ONE"
                RadioBackendKind.NATIVE_TCI -> "TCI"
                RadioBackendKind.REMOTE_STATION -> "ShackCQ"
                else -> name
            }, name, RadioTransportType.USB_SERIAL, baud = baud)
}
