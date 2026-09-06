package app.shackcq.mobile

import app.shackcq.mobile.radio.hamlib.HamlibAction
import app.shackcq.mobile.radio.hamlib.HamlibConnectionController
import app.shackcq.mobile.radio.hamlib.HamlibNetworkProfile
import app.shackcq.mobile.radio.hamlib.HamlibSerialProfile
import app.shackcq.mobile.radio.hamlib.HamlibSerialTransportPort
import app.shackcq.mobile.radio.qmx.QmxAudioRouteEvidence
import app.shackcq.mobile.radio.qmx.QmxClock
import app.shackcq.mobile.radio.qmx.QmxConnectionController
import app.shackcq.mobile.radio.qmx.QmxMode
import app.shackcq.mobile.radio.qmx.QmxRadioAction
import app.shackcq.mobile.radio.qmx.QmxSerialPort
import app.shackcq.mobile.radio.qmx.QmxUsbCompositeProfile
import app.shackcq.mobile.radio.qmx.QmxUsbFunctionDescriptor
import app.shackcq.mobile.radio.qmx.QmxUsbFunctionKind
import app.shackcq.mobile.radio.qmx.QmxUsbIdentityEvidence
import app.shackcq.mobile.radio.rgoone.RgoOneAction
import app.shackcq.mobile.radio.rgoone.RgoOneConnectionController
import app.shackcq.mobile.radio.rgoone.RgoOneGeneration
import app.shackcq.mobile.radio.rgoone.RgoOneMode
import app.shackcq.mobile.radio.rgoone.RgoOneSafetyDecision
import app.shackcq.mobile.radio.rgoone.RgoOneSafetyPort
import app.shackcq.mobile.radio.rgoone.RgoOneSerialConfig
import app.shackcq.mobile.radio.rgoone.RgoOneSerialPort
import app.shackcq.mobile.radio.rgoone.RgoOneSettingsDocument
import app.shackcq.mobile.radio.rgoone.RgoOneTransportType
import app.shackcq.mobile.radio.rgoone.RgoOneUsbIdentityEvidence
import app.shackcq.mobile.radio.rgoone.RgoOneVfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Android-owned serial adapters. Native radio implementations never open USB themselves. */
class AndroidRadioBackendFactory(
    private val transport: UsbRadioTransport,
) : RadioBackendFactory {
    override suspend fun create(profile: RadioConnectionProfile): ManagedRadioBackend = when (profile.backendKind) {
        RadioBackendKind.NATIVE_QMX -> QmxManagedBackend(profile, transport)
        RadioBackendKind.NATIVE_RGO_ONE -> RgoOneManagedBackend(profile, transport)
        RadioBackendKind.HAMLIB_EMBEDDED, RadioBackendKind.HAMLIB_NETWORK -> HamlibManagedBackend(profile, transport)
        else -> error("${profile.backendKind} remains owned by its established application backend")
    }
}

internal class AndroidHamlibSerialPort(
    private val transport: UsbRadioTransport,
    private val identityHash: String?,
) : HamlibSerialTransportPort {
    override suspend fun configure(profile: HamlibSerialProfile) {
        val parity = when (profile.parity) { 1 -> "E"; 2 -> "O"; else -> "N" }
        check(transport.connectRaw(identityHash, profile.baud, profile.dataBits, profile.stopBits, parity) is UsbResult.Connected) {
            "Configured Hamlib USB device is unavailable"
        }
    }

    override suspend fun read(maximum: Int, timeoutMs: Int) = transport.rawRead(maximum, timeoutMs)
    override suspend fun write(data: ByteArray) = transport.rawWrite(data)
    override suspend fun flush() { runCatching { transport.rawRead(4_096, 1) } }
    override suspend fun setControlLines(rts: Int, dtr: Int) {
        check(rts == 0 && dtr == 0) { "RTS/DTR assertion is not enabled for this Android transport" }
    }
    override fun cancelPendingIo() = Unit
    override suspend fun disconnect() = transport.disconnect()
}

private class HamlibManagedBackend(
    override val profile: RadioConnectionProfile,
    private val transport: UsbRadioTransport,
) : ManagedRadioBackend {
    private val controller = HamlibConnectionController()
    override val snapshot: RadioRuntimeSnapshot
        get() {
            val value = controller.snapshot.value
            return RadioRuntimeSnapshot(
                generation = controller.diagnostics.value.generation,
                profileId = profile.id,
                backendKind = profile.backendKind,
                modelId = profile.modelId,
                connected = controller.diagnostics.value.connected,
                vfoAHz = available(value?.frequencyAHz),
                vfoBHz = available(value?.frequencyBHz),
                receiveVfo = available(value?.vfo),
                transmitVfo = available(value?.txVfo),
                mode = available(value?.mode),
                split = available(value?.split),
                transmitting = available(value?.ptt),
            )
        }

    override suspend fun connect(): Boolean = runCatching {
        val model = requireNotNull(profile.hamlibModelId)
        if (profile.backendKind == RadioBackendKind.HAMLIB_NETWORK) {
            controller.connectNetwork(model, HamlibNetworkProfile(requireNotNull(profile.host), requireNotNull(profile.port), enabled = true),
                profile.readOnly, profile.pollCadenceMillis)
        } else {
            controller.connectSerial(model, HamlibSerialProfile(profile.stableSerialIdentityHash ?: "selected-usb", profile.baud,
                profile.dataBits, profile.stopBits, when (profile.parity) { "E" -> 1; "O" -> 2; else -> 0 }),
                AndroidHamlibSerialPort(transport, profile.stableSerialIdentityHash), profile.readOnly, profile.pollCadenceMillis)
        }
    }.isSuccess

    override suspend fun disconnect() = controller.disconnect()
    override suspend fun requestReceive(): Boolean = runCatching { controller.submit(HamlibAction.SetPtt(false)) }.isSuccess
    override suspend fun execute(action: RadioPlatformAction): Boolean = runCatching {
        val mapped = when (action.name) {
            "frequency" -> HamlibAction.SetFrequency(requireNotNull(action.longValue))
            "mode" -> HamlibAction.SetMode(requireNotNull(action.textValue))
            "split" -> HamlibAction.SetSplit(action.longValue == 1L)
            "ptt" -> HamlibAction.SetPtt(action.longValue == 1L)
            "tune" -> HamlibAction.Tune
            else -> return false
        }
        controller.submit(mapped)
    }.isSuccess
    override fun close() = controller.close()
}

private class QmxRawPort(private val transport: UsbRadioTransport) : QmxSerialPort {
    override fun exchange(command: String, timeoutMillis: Long): String = runBlocking(Dispatchers.IO) {
        transport.rawExchange(command.toByteArray(Charsets.US_ASCII), timeoutMillis.toInt()).toString(Charsets.US_ASCII)
    }
    // The managed backend performs the suspend disconnect before closing this synchronous adapter.
    override fun close() = Unit
}

private class QmxManagedBackend(
    override val profile: RadioConnectionProfile,
    private val transport: UsbRadioTransport,
) : ManagedRadioBackend {
    private var evidence: QmxUsbIdentityEvidence? = null
    private val controller = QmxConnectionController(
        QmxRawPort(transport),
        { evidence },
        { QmxAudioRouteEvidence(routeName = "UNAVAILABLE: exact QMX UAC route not proven") },
        object : QmxClock { override fun monotonicNanos() = System.nanoTime() },
    )
    override val snapshot: RadioRuntimeSnapshot
        get() = controller.snapshot.let { value ->
            RadioRuntimeSnapshot(
                generation = value.generation, profileId = profile.id, backendKind = profile.backendKind, modelId = profile.modelId,
                connected = value.connected, sourceAgeMillis = value.sourceAgeMillis,
                vfoAHz = available(value.vfoAHz), vfoBHz = available(value.vfoBHz),
                receiveVfo = available(value.receiveVfo.name.takeUnless { value.receiveVfo.name == "UNKNOWN" }),
                transmitVfo = available(value.transmitVfo.name.takeUnless { value.transmitVfo.name == "UNKNOWN" }),
                mode = available(value.mode.name.takeUnless { value.mode == QmxMode.UNKNOWN }),
                split = available(value.split.name.takeUnless { value.split.name == "UNKNOWN" }?.let { it == "TRUE" }),
                transmitting = available(value.txState.name.takeUnless { value.txState.name == "UNKNOWN" }?.let { it == "TX" }),
            )
        }

    override suspend fun connect(): Boolean {
        if (transport.connectRaw(profile.stableSerialIdentityHash, profile.baud, profile.dataBits, profile.stopBits, profile.parity) !is UsbResult.Connected) return false
        val selected = transport.selected ?: return false
        val digest = transport.selectedStableIdentityHash ?: return false
        val functions = transport.selectedInterfaces().mapNotNull { row ->
            val kind = when {
                row.interfaceClass == 2 -> QmxUsbFunctionKind.CDC_CONTROL
                row.interfaceClass == 10 -> QmxUsbFunctionKind.CDC_DATA
                row.interfaceClass == 1 && row.interfaceSubclass == 1 -> QmxUsbFunctionKind.UAC_CONTROL
                row.interfaceClass == 1 && row.interfaceSubclass == 2 -> QmxUsbFunctionKind.UAC_STREAMING
                else -> null
            } ?: return@mapNotNull null
            QmxUsbFunctionDescriptor(row.interfaceNumber, kind)
        }
        evidence = QmxUsbIdentityEvidence(
            selected.vidPid.substringBefore(':').toInt(16), selected.vidPid.substringAfter(':').toInt(16), selected.product, digest, functions,
        )
        val composite = QmxUsbCompositeProfile.resolve(requireNotNull(evidence)) ?: run { transport.disconnect(); return false }
        return runCatching { controller.attach(composite) }.getOrDefault(false)
    }

    override suspend fun disconnect() { controller.close(); transport.disconnect() }
    override suspend fun requestReceive(): Boolean = controller.submit(QmxRadioAction.RequestEmergencyReceive)
    override suspend fun execute(action: RadioPlatformAction): Boolean {
        val mapped = when (action.name) {
            "frequency" -> QmxRadioAction.SetFrequency(requireNotNull(action.longValue))
            "mode" -> QmxRadioAction.SetMode(runCatching { QmxMode.valueOf(requireNotNull(action.textValue)) }.getOrDefault(QmxMode.UNKNOWN))
            "split" -> QmxRadioAction.SetSplit(action.longValue == 1L)
            else -> return false
        }
        if (!controller.submit(mapped)) return false
        controller.drain()
        return true
    }
    override fun close() = controller.close()
}

private class RgoRawPort(
    private val profile: RadioConnectionProfile,
    private val transport: UsbRadioTransport,
) : RgoOneSerialPort {
    override val stableIdentity: String get() = profile.stableSerialIdentityHash ?: transport.selectedStableIdentityHash ?: "unselected"
    override fun open(config: RgoOneSerialConfig): Boolean = runBlocking(Dispatchers.IO) {
        val baud = config.baud ?: profile.baud
        transport.connectRaw(profile.stableSerialIdentityHash, baud, profile.dataBits, profile.stopBits, profile.parity) is UsbResult.Connected
    }
    override fun write(command: ByteArray): Boolean = runBlocking(Dispatchers.IO) { runCatching { transport.rawWrite(command) == command.size }.getOrDefault(false) }
    override fun exchange(command: ByteArray, maximumResponseBytes: Int, timeoutMillis: Long): ByteArray? = runBlocking(Dispatchers.IO) {
        runCatching { transport.rawExchange(command, timeoutMillis.toInt()).take(maximumResponseBytes).toByteArray() }.getOrNull()
    }
    // The managed backend performs the suspend disconnect before closing this synchronous adapter.
    override fun close() = Unit
}

private class RgoOneManagedBackend(
    override val profile: RadioConnectionProfile,
    private val transport: UsbRadioTransport,
) : ManagedRadioBackend {
    private val serial = RgoRawPort(profile, transport)
    private val controller = RgoOneConnectionController(
        serial = serial,
        usbIdentity = { RgoOneUsbIdentityEvidence(
            generation = if (profile.modelId.value == "RGO_ONE_V6") RgoOneGeneration.V6 else RgoOneGeneration.UNKNOWN,
            generationConfirmed = profile.modelId.value == "RGO_ONE_V6",
        ) },
        safety = RgoOneSafetyPort { action, _ ->
            if (action == RgoOneAction.Receive) RgoOneSafetyDecision.ALLOW_ONCE else RgoOneSafetyDecision.REVIEW_REQUIRED
        },
    )
    override val snapshot: RadioRuntimeSnapshot
        get() = controller.snapshot().let { value ->
            RadioRuntimeSnapshot(
                profileId = profile.id, backendKind = profile.backendKind, modelId = profile.modelId, connected = value.connected,
                sourceAgeMillis = value.lastUpdatedEpochMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
                vfoAHz = available(value.vfoAHz), vfoBHz = available(value.vfoBHz),
                receiveVfo = available(value.rxVfo?.name), transmitVfo = available(value.txVfo?.name),
                mode = available(value.mode?.name), split = available(value.split),
            )
        }
    override suspend fun connect(): Boolean = controller.connect(RgoOneSettingsDocument(
        generation = if (profile.modelId.value == "RGO_ONE_V6") RgoOneGeneration.V6 else RgoOneGeneration.UNKNOWN,
        transport = RgoOneTransportType.USB_CAT,
    ))
    override suspend fun disconnect() {
        controller.disconnect()
        transport.disconnect()
    }
    override suspend fun requestReceive() = controller.dispatch(RgoOneAction.Receive).name == "SENT"
    override suspend fun execute(action: RadioPlatformAction): Boolean {
        val mapped = when (action.name) {
            "frequency" -> RgoOneAction.SetFrequency(RgoOneVfo.A, requireNotNull(action.longValue))
            "mode" -> RgoOneAction.SetMode(runCatching { RgoOneMode.valueOf(requireNotNull(action.textValue)) }.getOrDefault(RgoOneMode.USB))
            else -> return false
        }
        return controller.dispatch(mapped).name == "SENT"
    }
    override fun close() = controller.close()
}

private fun <T> available(value: T?): AvailableRadioValue<T> = if (value == null) {
    AvailableRadioValue(RadioAvailability.UNKNOWN)
} else AvailableRadioValue(RadioAvailability.AVAILABLE, value)

fun RadioRuntimeSnapshot.asRadioState(profile: RadioConnectionProfile): RadioState = RadioState(
    identity = profile.id.value,
    model = profile.model,
    mode = mode.value ?: "--",
    frequencyHz = vfoAHz.value ?: 0,
    frequencyBHz = vfoBHz.value ?: 0,
    connected = connected,
    transmitting = transmitting.value ?: false,
    meter = sMeter.value?.toInt() ?: 0,
    swrTenths = swr.value?.times(10)?.toInt() ?: -1,
    rfOutputTenths = powerWatts.value?.times(10)?.toInt() ?: -1,
    bandwidthHz = passbandHz.value ?: 0,
    rit = (ritHz.value ?: 0) != 0,
    xit = (xitHz.value ?: 0) != 0,
    rxVfo = if (receiveVfo.value == "B" || receiveVfo.value == "VFOB") 1 else 0,
    txVfo = if (transmitVfo.value == "B" || transmitVfo.value == "VFOB") 1 else 0,
    split = split.value ?: false,
    revision = generation,
    ritXitOffsetHz = ritHz.value ?: xitHz.value ?: 0,
    effectiveRxHz = vfoAHz.value ?: 0,
    effectiveTxHz = if (split.value == true) vfoBHz.value ?: 0 else vfoAHz.value ?: 0,
    updatedMonotonicMs = android.os.SystemClock.elapsedRealtime(),
)
