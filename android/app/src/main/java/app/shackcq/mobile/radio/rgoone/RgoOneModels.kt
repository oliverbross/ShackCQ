package app.shackcq.mobile.radio.rgoone

import java.util.Locale

enum class RgoOneGeneration { V6, SERIES_5_5_PLUS, UNKNOWN }

data class RgoOneFirmwareVersion(val major: Int, val minor: Int, val wireValue: String) : Comparable<RgoOneFirmwareVersion> {
    override fun compareTo(other: RgoOneFirmwareVersion): Int =
        compareValuesBy(this, other, RgoOneFirmwareVersion::major, RgoOneFirmwareVersion::minor)

    override fun toString(): String = "$major.${minor.toString().padStart(2, '0')}"

    companion object {
        fun parse(value: String): RgoOneFirmwareVersion? {
            if (value.length != 4 || value.any { !it.isDigit() }) return null
            return RgoOneFirmwareVersion(value.take(2).toInt(), value.takeLast(2).toInt(), value)
        }
    }
}

enum class RgoOneCapabilityState {
    SUPPORTED_PRESENT,
    SUPPORTED_ABSENT,
    SUPPORTED_UNKNOWN,
    UNSUPPORTED_GENERATION,
    ERROR,
}

enum class RgoOneCapability {
    VFO_A,
    VFO_B,
    RX_VFO,
    TX_VFO,
    MODE,
    SPLIT,
    FINE_TUNE,
    FILTER_BANDWIDTH,
    RIT,
    XIT,
    AGC,
    S_METER,
    TX_METERS,
    RF_GAIN,
    TX_POWER,
    PREAMP,
    ATTENUATOR,
    NOISE_BLANKER,
    AUDIO_DSP,
    ANTENNA_TUNER,
    KEYER_SPEED,
    MIC_GAIN,
    SPEECH_PROCESSOR,
    MEMORY_READ,
    MEMORY_WRITE,
    FIRMWARE_IDENTITY,
    USB_AUDIO,
    TRANSMIT,
    TUNE,
}

enum class RgoOneModule { ATU, NOISE_BLANKER, AUDIO_DSP, SPEECH_PROCESSOR, TRANSVERTER_RX_ANTENNA, USB_AUDIO }
enum class RgoOneModuleEvidence { OFFICIAL_RESPONSE, OFFICIAL_MENU, OPERATOR_CONFIRMED, USB_DESCRIPTOR, NONE, ERROR }

data class RgoOneModuleState(
    val state: RgoOneCapabilityState = RgoOneCapabilityState.SUPPORTED_UNKNOWN,
    val evidence: RgoOneModuleEvidence = RgoOneModuleEvidence.NONE,
)

data class RgoOneUsbAudioProfile(
    val sampleRateHz: Int,
    val channels: Int,
    val direction: RgoOneAudioDirection,
    val descriptorDigest: String,
) {
    init {
        require(sampleRateHz > 0)
        require(channels in 1..8)
        require(descriptorDigest.matches(Regex("[0-9a-f]{64}")))
    }

    val isIqSource: Boolean = false
}

enum class RgoOneAudioDirection { INPUT, OUTPUT, BIDIRECTIONAL }

data class RgoOneModuleSnapshot(
    val states: Map<RgoOneModule, RgoOneModuleState> = RgoOneModule.entries.associateWith { RgoOneModuleState() },
    val usbAudio: RgoOneUsbAudioProfile? = null,
) {
    operator fun get(module: RgoOneModule): RgoOneModuleState = states.getValue(module)
}

enum class RgoOneVfo { A, B }
enum class RgoOneMode(val wireValue: Int) { LSB(1), USB(2), CW(3), FM(4), AM(5), DATA(6), CW_REVERSE(7) }
enum class RgoOneAgc { OFF, FAST, SLOW }
enum class RgoOneMeter { RF_POWER, ALC, SWR, COMP }

data class RgoOneRadioSnapshot(
    val connected: Boolean = false,
    val connectionState: RgoOneConnectionState = RgoOneConnectionState.DISCONNECTED,
    val stale: Boolean = true,
    val stableTransportIdentity: String = "",
    val generation: RgoOneGeneration = RgoOneGeneration.UNKNOWN,
    val generationConfirmed: Boolean = false,
    val modelId: String? = null,
    val firmware: RgoOneFirmwareVersion? = null,
    val vfoAHz: Long? = null,
    val vfoBHz: Long? = null,
    val rxVfo: RgoOneVfo? = null,
    val txVfo: RgoOneVfo? = null,
    val mode: RgoOneMode? = null,
    val fineTune: Boolean? = null,
    val ritEnabled: Boolean? = null,
    val xitEnabled: Boolean? = null,
    val ritOffsetHz: Int? = null,
    val xitOffsetHz: Int? = null,
    val agc: RgoOneAgc? = null,
    val sMeter: Int? = null,
    val meters: Map<RgoOneMeter, Int> = emptyMap(),
    val rfGain: Int? = null,
    val txPowerWatts: Int? = null,
    val micGain: Int? = null,
    val keyerSpeedWpm: Int? = null,
    val preamp: Boolean? = null,
    val attenuator: Boolean? = null,
    val noiseBlanker: Boolean? = null,
    val filterLabel: String? = null,
    val filterBandwidthHz: Int? = null,
    val modules: RgoOneModuleSnapshot = RgoOneModuleSnapshot(),
    val capabilities: Map<RgoOneCapability, RgoOneCapabilityState> = RgoOneCapability.entries.associateWith {
        RgoOneCapabilityState.SUPPORTED_UNKNOWN
    },
    val lastUpdatedEpochMillis: Long? = null,
    val status: String = "Disconnected",
) {
    val split: Boolean? get() = if (rxVfo != null && txVfo != null) rxVfo != txVfo else null
    val primaryFrequencyHz: Long? get() = if (rxVfo == RgoOneVfo.B) vfoBHz else vfoAHz
    val secondaryFrequencyHz: Long? get() = if (rxVfo == RgoOneVfo.B) vfoAHz else vfoBHz
}

enum class RgoOneConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, RECONNECTING, CLOSED }
enum class RgoOneTransportType { USB_CAT, TTL_SERIAL }
enum class RgoOneParity { NONE, EVEN, ODD }
data class RgoOneSerialFraming(val dataBits: Int, val parity: RgoOneParity, val stopBits: Int) {
    init {
        require(dataBits in 5..8)
        require(stopBits in 1..2)
    }
}

data class RgoOneSerialConfig(
    val transport: RgoOneTransportType,
    val baud: Int? = null,
    val framing: RgoOneSerialFraming? = null,
)

data class RgoOneUsbIdentityEvidence(
    val generation: RgoOneGeneration = RgoOneGeneration.UNKNOWN,
    val generationConfirmed: Boolean = false,
    val audioProfile: RgoOneUsbAudioProfile? = null,
)

interface RgoOneTransportPort {
    val stableIdentity: String
    fun open(config: RgoOneSerialConfig): Boolean
    fun write(command: ByteArray): Boolean
    fun exchange(command: ByteArray, maximumResponseBytes: Int, timeoutMillis: Long): ByteArray?
    fun close()
}

interface RgoOneSerialPort : RgoOneTransportPort
fun interface RgoOneUsbIdentityPort { fun resolve(stableIdentity: String): RgoOneUsbIdentityEvidence? }
fun interface RgoOneClock { fun epochMillis(): Long }
fun interface RgoOneActionPort { fun emit(action: RgoOneAction) }

enum class RgoOneActionClass { READ_ONLY, SAFE_SET, EDGE_TRIGGERED, TRANSMIT, TUNE, MEMORY_WRITE }

sealed interface RgoOneAction {
    val actionClass: RgoOneActionClass

    data class Read(val command: String) : RgoOneAction { override val actionClass = RgoOneActionClass.READ_ONLY }
    data class SetFrequency(val vfo: RgoOneVfo, val frequencyHz: Long) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SelectRxVfo(val vfo: RgoOneVfo) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SelectTxVfo(val vfo: RgoOneVfo) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SetMode(val mode: RgoOneMode) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SetAgc(val agc: RgoOneAgc) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SetToggle(val command: String, val enabled: Boolean) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data class SetLevel(val command: String, val value: Int) : RgoOneAction { override val actionClass = RgoOneActionClass.SAFE_SET }
    data object ClearRit : RgoOneAction { override val actionClass = RgoOneActionClass.EDGE_TRIGGERED }
    data class NudgeRit(val up: Boolean) : RgoOneAction { override val actionClass = RgoOneActionClass.EDGE_TRIGGERED }
    data class RecallMemory(val channel: Int) : RgoOneAction { override val actionClass = RgoOneActionClass.EDGE_TRIGGERED }
    data object Receive : RgoOneAction { override val actionClass = RgoOneActionClass.EDGE_TRIGGERED }
    data object Transmit : RgoOneAction { override val actionClass = RgoOneActionClass.TRANSMIT }
    data object Tune : RgoOneAction { override val actionClass = RgoOneActionClass.TUNE }
    data class WriteMemory(val memory: RgoOneMemoryRecord) : RgoOneAction { override val actionClass = RgoOneActionClass.MEMORY_WRITE }
}

data class RgoOneMemoryRecord(
    val tx: Boolean,
    val channel: Int,
    val frequencyHz: Long,
    val mode: RgoOneMode,
    val stepCode: Int,
    val noiseBlanker: Boolean,
    val preamp: Boolean,
    val attenuator: Boolean,
    val agc: RgoOneAgc,
    val filterEnabled: Boolean,
) {
    init {
        require(channel in 0..99)
        require(frequencyHz in 1..99_999_999_999L)
        require(stepCode in 0..3)
        require(agc != RgoOneAgc.OFF)
    }
}

enum class RgoOneSafetyDecision { ALLOW_ONCE, DENY, REVIEW_REQUIRED }
fun interface RgoOneSafetyPort { fun review(action: RgoOneAction, snapshot: RgoOneRadioSnapshot): RgoOneSafetyDecision }

enum class RgoOneLayoutPreference { AUTO, COMPACT, STANDARD, WIDE }

data class RgoOneSettingsDocument(
    val version: Int = CURRENT_VERSION,
    val generation: RgoOneGeneration = RgoOneGeneration.UNKNOWN,
    val transport: RgoOneTransportType = RgoOneTransportType.USB_CAT,
    val ttlBaud: Int = 57_600,
    val ttlFraming: RgoOneSerialFraming? = null,
    val fastPollMillis: Long = 500,
    val mediumPollMillis: Long = 2_000,
    val slowPollMillis: Long = 10_000,
    val visibleControls: Set<String> = emptySet(),
    val layout: RgoOneLayoutPreference = RgoOneLayoutPreference.AUTO,
    val manualModules: Map<RgoOneModule, Boolean?> = emptyMap(),
    val meterPreferences: Set<RgoOneMeter> = setOf(RgoOneMeter.RF_POWER, RgoOneMeter.SWR),
    val filterFavorites: List<String> = emptyList(),
    val writesConfirmed: Boolean = false,
    val memoryWriteEnabled: Boolean = false,
) {
    fun safeRestore(): RgoOneSettingsDocument = copy(
        version = CURRENT_VERSION,
        ttlBaud = ttlBaud.takeIf { it in OFFICIAL_TTL_BAUDS } ?: 57_600,
        fastPollMillis = fastPollMillis.coerceIn(250, 60_000),
        mediumPollMillis = mediumPollMillis.coerceIn(500, 120_000),
        slowPollMillis = slowPollMillis.coerceIn(1_000, 300_000),
        visibleControls = visibleControls.map { it.trim().uppercase(Locale.US) }.filter { it.length <= 32 }.take(40).toSet(),
        filterFavorites = filterFavorites.map { it.trim() }.filter(String::isNotBlank).take(20),
        writesConfirmed = false,
        memoryWriteEnabled = false,
    )

    fun safeExport(): Map<String, Any> = mapOf(
        "version" to CURRENT_VERSION,
        "generation" to generation.name,
        "transport" to transport.name,
        "ttl_baud" to ttlBaud,
        "poll_fast_ms" to fastPollMillis,
        "poll_medium_ms" to mediumPollMillis,
        "poll_slow_ms" to slowPollMillis,
        "visible_controls" to visibleControls.sorted(),
        "layout" to layout.name,
        "manual_modules" to manualModules.mapKeys { it.key.name },
        "meters" to meterPreferences.map { it.name }.sorted(),
        "filter_favorites" to filterFavorites,
    )

    companion object {
        const val CURRENT_VERSION = 1
        val OFFICIAL_TTL_BAUDS = setOf(9_600, 19_200, 38_400, 57_600)
    }
}
