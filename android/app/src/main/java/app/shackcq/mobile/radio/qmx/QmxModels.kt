// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import java.security.MessageDigest

enum class QmxModel { QMX, QMX_PLUS, UNKNOWN_QMX }
enum class QmxMode(val catDigit: Char?) {
    LSB('1'), USB('2'), CW('3'), FM('4'), AM('5'), DIGI('6'), CW_REVERSE('7'), SWR_TUNE('8'), DIGI_REVERSE('9'), UNKNOWN(null);

    companion object { fun fromCat(digit: Char) = entries.firstOrNull { it.catDigit == digit } ?: UNKNOWN }
}
enum class QmxVfo { A, B, SPLIT, UNKNOWN }
enum class QmxTxState { RX, TX, UNKNOWN }
enum class QmxTriState { TRUE, FALSE, UNKNOWN }
enum class QmxCapabilityState { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class QmxGpsSource { INTERNAL, EXTERNAL_OR_HOST, NONE, UNKNOWN }

data class QmxFirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String = "",
) : Comparable<QmxFirmwareVersion> {
    override fun compareTo(other: QmxFirmwareVersion): Int =
        compareValuesBy(this, other, QmxFirmwareVersion::major, QmxFirmwareVersion::minor, QmxFirmwareVersion::patch)

    override fun toString() = "%d_%02d_%03d%s".format(major, minor, patch, suffix)

    companion object {
        private val pattern = Regex("^(\\d+)_(\\d+)_(\\d+)([A-Za-z0-9+_-]*)$")
        fun parse(raw: String?): QmxFirmwareVersion? {
            val match = raw?.trim()?.let(pattern::matchEntire) ?: return null
            return QmxFirmwareVersion(
                match.groupValues[1].toIntOrNull() ?: return null,
                match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null,
                match.groupValues[4],
            )
        }
    }
}

data class QmxCapabilities(
    val frequency: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val vfoB: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val mode: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val filter: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val afGain: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val rfGain: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val rit: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val split: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val cwOffset: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val gpsSource: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val meters: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val powerSWR: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val iqMode: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val voxControl: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val amMode: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val swrTune: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val directToneTx: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
    val menuTerminal: QmxCapabilityState = QmxCapabilityState.UNKNOWN,
) {
    fun digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

enum class QmxReadback {
    FA, FB, MD, FW, IF_STATE, AG, RG, RIT, SPLIT, CW_OFFSET, GPS_SOURCE, VN, ID, Q9, Q3, PC, SW, SM, TX_STATE
}

data class QmxCapabilityEvidence(
    val model: QmxModel = QmxModel.UNKNOWN_QMX,
    val firmware: QmxFirmwareVersion? = null,
    val successfulReadbacks: Set<QmxReadback> = emptySet(),
    val unsupportedReadbacks: Set<QmxReadback> = emptySet(),
    val cdcInterfaceCount: Int? = null,
    val directToneTxProvenByIntegration: Boolean = false,
)

object QmxCapabilityResolver {
    private val firmware104 = QmxFirmwareVersion(1, 4, 0)
    private val firmware103002 = QmxFirmwareVersion(1, 3, 2)

    fun resolve(evidence: QmxCapabilityEvidence): QmxCapabilities {
        fun readback(value: QmxReadback): QmxCapabilityState = when (value) {
            in evidence.successfulReadbacks -> QmxCapabilityState.SUPPORTED
            in evidence.unsupportedReadbacks -> QmxCapabilityState.UNSUPPORTED
            else -> QmxCapabilityState.UNKNOWN
        }
        val versionKnown = evidence.firmware != null
        val modeReadback = readback(QmxReadback.MD)
        val firmware104Cap = when {
            !versionKnown -> QmxCapabilityState.UNKNOWN
            evidence.firmware!! < firmware104 -> QmxCapabilityState.UNSUPPORTED
            modeReadback == QmxCapabilityState.SUPPORTED -> QmxCapabilityState.SUPPORTED
            modeReadback == QmxCapabilityState.UNSUPPORTED -> QmxCapabilityState.UNSUPPORTED
            else -> QmxCapabilityState.UNKNOWN
        }
        val terminal = when (evidence.cdcInterfaceCount) {
            null -> QmxCapabilityState.UNKNOWN
            0, 1 -> QmxCapabilityState.UNSUPPORTED
            else -> QmxCapabilityState.SUPPORTED
        }
        val tone = when {
            evidence.firmware == null -> QmxCapabilityState.UNKNOWN
            evidence.firmware < firmware103002 -> QmxCapabilityState.UNSUPPORTED
            evidence.directToneTxProvenByIntegration && QmxReadback.TX_STATE in evidence.successfulReadbacks -> QmxCapabilityState.SUPPORTED
            else -> QmxCapabilityState.UNKNOWN
        }
        return QmxCapabilities(
            frequency = readback(QmxReadback.FA),
            vfoB = readback(QmxReadback.FB),
            mode = modeReadback,
            filter = readback(QmxReadback.FW),
            afGain = readback(QmxReadback.AG),
            rfGain = readback(QmxReadback.RG),
            rit = readback(QmxReadback.RIT),
            split = readback(QmxReadback.SPLIT),
            cwOffset = readback(QmxReadback.CW_OFFSET),
            gpsSource = readback(QmxReadback.GPS_SOURCE),
            meters = readback(QmxReadback.SM),
            powerSWR = if (readback(QmxReadback.PC) == QmxCapabilityState.SUPPORTED && readback(QmxReadback.SW) == QmxCapabilityState.SUPPORTED) QmxCapabilityState.SUPPORTED else if (QmxReadback.PC in evidence.unsupportedReadbacks || QmxReadback.SW in evidence.unsupportedReadbacks) QmxCapabilityState.UNSUPPORTED else QmxCapabilityState.UNKNOWN,
            iqMode = readback(QmxReadback.Q9),
            voxControl = readback(QmxReadback.Q3),
            amMode = firmware104Cap,
            swrTune = firmware104Cap,
            directToneTx = tone,
            menuTerminal = terminal,
        )
    }
}

data class QmxRadioSnapshot(
    val generation: Long = 0,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val model: QmxModel = QmxModel.UNKNOWN_QMX,
    val firmware: QmxFirmwareVersion? = null,
    val vfoAHz: Long? = null,
    val vfoBHz: Long? = null,
    val receiveVfo: QmxVfo = QmxVfo.UNKNOWN,
    val transmitVfo: QmxVfo = QmxVfo.UNKNOWN,
    val mode: QmxMode = QmxMode.UNKNOWN,
    val filterHz: Int? = null,
    val afGainDb: Double? = null,
    val afGainNativeQuarterDb: Int? = null,
    val rfGainDb: Int? = null,
    val ritHz: Int? = null,
    val split: QmxTriState = QmxTriState.UNKNOWN,
    val cwOffsetHz: Int? = null,
    val sMeter: Int? = null,
    val powerWatts: Double? = null,
    val swr: Double? = null,
    val txState: QmxTxState = QmxTxState.UNKNOWN,
    val iqModeEnabled: QmxTriState = QmxTriState.UNKNOWN,
    val voxDisabled: QmxTriState = QmxTriState.UNKNOWN,
    val gpsSource: QmxGpsSource = QmxGpsSource.UNKNOWN,
    val swrFault: Boolean = false,
    val menuTerminalAvailable: QmxTriState = QmxTriState.UNKNOWN,
    val sourceAgeMillis: Long? = null,
    val lastSanitizedError: String? = null,
    val capabilities: QmxCapabilities = QmxCapabilities(),
)

sealed interface QmxRadioAction {
    data class SetFrequency(val hertz: Long) : QmxRadioAction
    data class SetMode(val mode: QmxMode) : QmxRadioAction
    data class SetFilter(val hertz: Int) : QmxRadioAction
    data class SetAfGain(val quarterDbSteps: Int) : QmxRadioAction
    data class SetRfGain(val decibels: Int) : QmxRadioAction
    data class SetRit(val hertz: Int) : QmxRadioAction
    data object ClearRit : QmxRadioAction
    data class SetSplit(val enabled: Boolean) : QmxRadioAction
    data object RequestTransmitConfirmation : QmxRadioAction
    data object RequestSWRProtectionTuneConfirmation : QmxRadioAction
    data object RequestEmergencyReceive : QmxRadioAction
}

fun interface QmxRadioActionPort { fun emit(action: QmxRadioAction) }
interface QmxSerialPort : AutoCloseable {
    fun exchange(command: String, timeoutMillis: Long): String
    override fun close()
}
fun interface QmxUsbIdentityPort { fun currentIdentity(): QmxUsbIdentityEvidence? }
fun interface QmxUacAudioPort { fun currentRoute(): QmxAudioRouteEvidence }
fun interface QmxPanadapterPort { fun apply(profile: QmxPanadapterAdapterContract) }
fun interface QmxClock {
    fun monotonicNanos(): Long
    fun wallTimeMillis(): Long = System.currentTimeMillis()
    fun sleepUntilMonotonic(targetNanos: Long) {
        while (true) {
            val remaining = targetNanos - monotonicNanos()
            if (remaining <= 0) return
            val millis = (remaining / 1_000_000L).coerceAtLeast(1L)
            Thread.sleep(millis)
        }
    }
}

enum class QmxUsbFunctionKind { CDC_CONTROL, CDC_DATA, UAC_CONTROL, UAC_STREAMING }
data class QmxUsbFunctionDescriptor(
    val interfaceNumber: Int,
    val kind: QmxUsbFunctionKind,
    val endpointAddresses: Set<Int> = emptySet(),
)
data class QmxUsbIdentityEvidence(
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val stableDeviceDigest: String,
    val functions: List<QmxUsbFunctionDescriptor>,
)
data class QmxUsbCompositeProfile(
    val stableDeviceDigest: String,
    val model: QmxModel,
    val primaryCatControlInterface: Int,
    val primaryCatDataInterface: Int?,
    val uacStreamingInterfaces: Set<Int>,
    val extraCdcControlInterfaces: Set<Int>,
) {
    val cdcInterfaceCount: Int get() = 1 + extraCdcControlInterfaces.size

    companion object {
        fun resolve(evidence: QmxUsbIdentityEvidence): QmxUsbCompositeProfile? {
            if (evidence.stableDeviceDigest.length < 16) return null
            val controls = evidence.functions.filter { it.kind == QmxUsbFunctionKind.CDC_CONTROL }.sortedBy { it.interfaceNumber }
            val primary = controls.firstOrNull { it.interfaceNumber == 0 } ?: return null
            val audio = evidence.functions.filter { it.kind == QmxUsbFunctionKind.UAC_STREAMING }.map { it.interfaceNumber }.toSet()
            if (audio.isEmpty()) return null
            val data = evidence.functions.firstOrNull { it.kind == QmxUsbFunctionKind.CDC_DATA && it.interfaceNumber > primary.interfaceNumber }?.interfaceNumber
            val product = evidence.productName.orEmpty().uppercase()
            val model = when {
                "QMX+" in product || "QMX PLUS" in product -> QmxModel.QMX_PLUS
                "QMX" in product -> QmxModel.QMX
                else -> QmxModel.UNKNOWN_QMX
            }
            return QmxUsbCompositeProfile(
                evidence.stableDeviceDigest,
                model,
                primary.interfaceNumber,
                data,
                audio,
                controls.drop(1).map { it.interfaceNumber }.toSet(),
            )
        }
    }
}

data class QmxDiagnostics(
    val model: QmxModel,
    val firmware: String?,
    val usbInterfaceCount: Int,
    val catReady: Boolean,
    val iqReady: Boolean,
    val audioRoute: String,
    val pollAgeMillis: Long?,
    val capabilityDigest: String,
    val powerWatts: Double?,
    val swr: Double?,
    val lastSanitizedError: String?,
)

data class QmxRadioProfile(
    val usb: QmxUsbCompositeProfile,
    val audio: QmxAudioProfile?,
    val snapshot: QmxRadioSnapshot,
    val panadapter: QmxPanadapterAdapterContract?,
)

object QmxProtocol {
    const val MAX_RESPONSE_BYTES = 256
    const val FAST_POLL_MILLIS = 200L
    const val MEDIUM_POLL_MILLIS = 1_000L
    const val SLOW_POLL_MILLIS = 10_000L
}

data class QmxSettingsDocument(
    val iqSwap: Boolean = false,
    val correctionEnabled: Boolean = true,
    val flatSpectrumMode: Boolean = false,
    val smoothing: Double = 0.25,
    val window: QmxWindow = QmxWindow.BLACKMAN_HARRIS,
    val displayFloorDb: Double = -120.0,
    val displayTopDb: Double = -20.0,
    val waterfallFloorDb: Double = -110.0,
    val waterfallRangeDb: Double = 55.0,
    val zoom: Int = 1,
    val ifOffsetOverrideHz: Int? = null,
) {
    fun validated() = copy(
        smoothing = smoothing.coerceIn(0.0, 1.0),
        displayFloorDb = displayFloorDb.coerceIn(-150.0, -40.0),
        displayTopDb = displayTopDb.coerceIn(displayFloorDb + 20.0, 20.0),
        waterfallFloorDb = waterfallFloorDb.coerceIn(-150.0, -30.0),
        waterfallRangeDb = waterfallRangeDb.coerceIn(20.0, 120.0),
        zoom = zoom.takeIf { it in setOf(1, 2, 4, 8, 16, 24) } ?: 1,
        ifOffsetOverrideHz = ifOffsetOverrideHz?.coerceIn(-24_000, 24_000),
    )

    fun toSafeMap(): Map<String, String> = validated().let { safe -> mapOf(
        "version" to "1", "iq_swap" to safe.iqSwap.toString(), "correction" to safe.correctionEnabled.toString(),
        "flat_spectrum" to safe.flatSpectrumMode.toString(), "smoothing" to safe.smoothing.toString(), "window" to safe.window.name,
        "display_floor" to safe.displayFloorDb.toString(), "display_top" to safe.displayTopDb.toString(),
        "waterfall_floor" to safe.waterfallFloorDb.toString(), "waterfall_range" to safe.waterfallRangeDb.toString(),
        "zoom" to safe.zoom.toString(), "if_offset_override" to (safe.ifOffsetOverrideHz?.toString() ?: ""),
    ) }

    companion object {
        fun restore(values: Map<String, String>): QmxSettingsDocument = runCatching {
            require(values["version"] == "1")
            QmxSettingsDocument(
                iqSwap = values["iq_swap"]!!.toBooleanStrict(),
                correctionEnabled = values["correction"]!!.toBooleanStrict(),
                flatSpectrumMode = values["flat_spectrum"]!!.toBooleanStrict(),
                smoothing = values["smoothing"]!!.toDouble(),
                window = enumValueOf(values["window"]!!),
                displayFloorDb = values["display_floor"]!!.toDouble(),
                displayTopDb = values["display_top"]!!.toDouble(),
                waterfallFloorDb = values["waterfall_floor"]!!.toDouble(),
                waterfallRangeDb = values["waterfall_range"]!!.toDouble(),
                zoom = values["zoom"]!!.toInt(),
                ifOffsetOverrideHz = values["if_offset_override"]?.toIntOrNull(),
            ).validated()
        }.getOrDefault(QmxSettingsDocument())
    }
}

enum class QmxWindow { BLACKMAN_HARRIS, HANN, NUTTALL, FLAT_TOP }
