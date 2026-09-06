package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shackcq.mobile.radio.hamlib.NativeHamlibRotatorPort
import app.shackcq.mobile.rotator.CapabilitySupport
import app.shackcq.mobile.rotator.DcuRotorEzProtocol
import app.shackcq.mobile.rotator.EasyCommProtocol
import app.shackcq.mobile.rotator.EasyCommVersion
import app.shackcq.mobile.rotator.Gs232Protocol
import app.shackcq.mobile.rotator.NativeRotatorDriver
import app.shackcq.mobile.rotator.RemoteRotctldDriver
import app.shackcq.mobile.rotator.RotatorAction
import app.shackcq.mobile.rotator.RotatorAutomationSession
import app.shackcq.mobile.rotator.RotatorBackend
import app.shackcq.mobile.rotator.RotatorBackendRegistry
import app.shackcq.mobile.rotator.RotatorCapability
import app.shackcq.mobile.rotator.RotatorCapabilitySnapshot
import app.shackcq.mobile.rotator.RotatorDeviceProfile
import app.shackcq.mobile.rotator.RotatorPhysicalAuthorityPort
import app.shackcq.mobile.rotator.RotatorPlatformController
import app.shackcq.mobile.rotator.RotatorProfileStore
import app.shackcq.mobile.rotator.RotatorProtocol
import app.shackcq.mobile.rotator.RotatorProtocolKind
import app.shackcq.mobile.rotator.RotatorResponseRule
import app.shackcq.mobile.rotator.RotatorSettingsCodec
import app.shackcq.mobile.rotator.RotatorSettingsDocument
import app.shackcq.mobile.rotator.RotatorStateSnapshot
import app.shackcq.mobile.rotator.RotatorTcpTransport
import app.shackcq.mobile.rotator.RotatorTransport
import app.shackcq.mobile.rotator.RotatorWireCommand
import app.shackcq.mobile.rotator.SpidProtocol
import app.shackcq.mobile.rotator.EmbeddedHamlibRotatorDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AndroidRotatorUsbPort(private val transport: UsbRadioTransport) {
    suspend fun open(profileId: String, settings: app.shackcq.mobile.rotator.SerialSettings): RotatorTransport =
        object : RotatorTransport {
            override val connected: Boolean get() = transport.isConnected
            override suspend fun open() {
                check(transport.connectRaw(settings.stableIdentityHash, settings.baud, settings.dataBits, settings.stopBits, settings.parity) is UsbResult.Connected) {
                    "configured rotator USB device is unavailable"
                }
                check(!settings.dtr && !settings.rts) { "rotator RTS/DTR assertion is not enabled" }
            }

            override suspend fun transact(command: RotatorWireCommand, responseRule: RotatorResponseRule): ByteArray {
                transport.rawWrite(command.bytes, settings.writeTimeoutMs)
                if (!command.expectsResponse || responseRule == RotatorResponseRule.None) return ByteArray(0)
                val output = ArrayList<Byte>()
                while (output.size < app.shackcq.mobile.rotator.MAX_ROTATOR_RESPONSE_BYTES) {
                    val next = transport.rawRead(
                        (app.shackcq.mobile.rotator.MAX_ROTATOR_RESPONSE_BYTES - output.size).coerceAtMost(512),
                        settings.readTimeoutMs,
                    )
                    if (next.isEmpty()) break
                    next.forEach(output::add)
                    val bytes = output.toByteArray()
                    val done = when (responseRule) {
                        is RotatorResponseRule.Fixed -> bytes.size >= responseRule.size
                        is RotatorResponseRule.Line -> bytes.lastOrNull() == responseRule.terminator
                        is RotatorResponseRule.UntilRprt -> bytes.toString(Charsets.US_ASCII).lineSequence().any { it.startsWith("RPRT ") }
                        RotatorResponseRule.None -> true
                    }
                    if (done) return if (responseRule is RotatorResponseRule.Fixed) bytes.copyOf(responseRule.size) else bytes
                }
                error("rotator response was incomplete or exceeded its bound")
            }

            // AndroidRotatorRuntime owns the suspend disconnect; this synchronous adapter never blocks lifecycle teardown.
            override fun close() = Unit
        }
}

class AndroidRotatorRuntime(
    context: Context,
    physicalAuthority: PhysicalDeviceAuthority,
) {
    private val preferences = context.getSharedPreferences("shackcq-rotator", Context.MODE_PRIVATE)
    private val document = preferences.getString("document", null)?.let { runCatching { RotatorSettingsCodec.decode(it) }.getOrNull() }
        ?: RotatorSettingsDocument()
    val store = RotatorProfileStore(document.restoredSafe())
    private val usbTransport = UsbRadioTransport(context, "shackcq-rotator-usb")
    private val serial = AndroidRotatorUsbPort(usbTransport)
    private val hamlib = NativeHamlibRotatorPort(serialPort = { profile: RotatorDeviceProfile ->
        profile.hamlibSerial?.let { AndroidHamlibSerialPort(usbTransport, it.stableIdentityHash) }
    })
    private val registry = RotatorBackendRegistry().apply {
        register(RotatorBackend.NATIVE) { profile ->
            val protocol = protocol(profile)
            NativeRotatorDriver(profile, protocol, { serial.open(profile.id, requireNotNull(profile.serial)) }, capabilities(profile))
        }
        register(RotatorBackend.REMOTE_ROTCTLD) { profile ->
            RemoteRotctldDriver(profile, { RotatorTcpTransport(requireNotNull(profile.tcp)) }, capabilities(profile))
        }
        register(RotatorBackend.EMBEDDED_HAMLIB) { profile ->
            val capabilities = hamlib.capabilities(requireNotNull(profile.hamlibModelId)).capabilities
            EmbeddedHamlibRotatorDriver(profile, hamlib, capabilities)
        }
    }
    val controller = RotatorPlatformController(
        store,
        registry,
        sharedPhysicalAuthority = object : RotatorPhysicalAuthorityPort {
            override fun acquire(identity: String, owner: String) = physicalAuthority.acquire(identity, owner)
            override fun release(identity: String, owner: String) = physicalAuthority.release(identity, owner)
        },
    )
    var state: RotatorStateSnapshot? by mutableStateOf(null)
        private set
    var capabilities: RotatorCapabilitySnapshot by mutableStateOf(RotatorCapabilitySnapshot())
        private set
    val automation: RotatorAutomationSession get() = controller.automationSession()
    val profiles: List<RotatorDeviceProfile> get() = store.snapshot().profiles

    fun upsertProfile(profile: RotatorDeviceProfile) {
        store.upsert(profile)
        persist()
    }

    suspend fun deleteProfile(profileId: String): Boolean {
        if (state?.profileId == profileId && state?.connected == true) disconnect()
        store.delete(profileId)
        persist()
        return true
    }

    private fun persist() {
        preferences.edit().putString("document", RotatorSettingsCodec.encode(store.snapshot(), includeLanEndpoints = true)).apply()
    }

    suspend fun connect(profileId: String, readOnlyProbe: Boolean = false): Boolean {
        val connected = runCatching {
            val profile = requireNotNull(profiles.firstOrNull { it.id == profileId })
            capabilities = if (profile.backend == RotatorBackend.EMBEDDED_HAMLIB) {
                hamlib.capabilities(requireNotNull(profile.hamlibModelId)).capabilities
            } else capabilities(profile)
            state = controller.connect(profileId, readOnlyProbe)
            state?.connected == true
        }.getOrDefault(false)
        if (!connected) usbTransport.disconnect()
        return connected
    }

    suspend fun poll() {
        val active = state?.takeIf { it.connected } ?: return
        state = runCatching { controller.poll(active.profileId, active.generation + 1) }.getOrElse {
            active.copy(ready = false, lastSanitizedError = "rotator poll failed closed")
        }
    }

    suspend fun submit(action: RotatorAction, azimuth: Double? = null, elevation: Double? = null): Boolean {
        val active = state ?: return false
        val accepted = controller.submit(active.profileId, action, azimuth, elevation)
        state = controller.states().firstOrNull { it.profileId == active.profileId } ?: state
        return accepted
    }

    suspend fun stopAndDisarm(): Boolean {
        controller.disarmAutomation("global operator stop")
        val active = state?.takeIf { it.connected } ?: return true
        return controller.submit(active.profileId, RotatorAction.STOP)
    }

    suspend fun disconnect() {
        state?.profileId?.let { controller.disconnect(it) }
        usbTransport.disconnect()
        state = controller.states().firstOrNull { it.profileId == state?.profileId }
    }

    fun background() = controller.onBackground()

    private fun capabilities(profile: RotatorDeviceProfile): RotatorCapabilitySnapshot {
        if (profile.backend == RotatorBackend.EMBEDDED_HAMLIB) return capabilities
        val values = mutableMapOf(
            RotatorCapability.AZIMUTH to CapabilitySupport.SUPPORTED,
            RotatorCapability.POSITION_QUERY to CapabilitySupport.SUPPORTED,
            RotatorCapability.ABSOLUTE_MOVE to CapabilitySupport.SUPPORTED,
            RotatorCapability.STOP to CapabilitySupport.SUPPORTED,
            RotatorCapability.LIMITS to CapabilitySupport.SUPPORTED,
        )
        if (profile.protocol in setOf(RotatorProtocolKind.EASYCOMM, RotatorProtocolKind.SPID_ROT2) || profile.limits.elMax > profile.limits.elMin) {
            values[RotatorCapability.ELEVATION] = CapabilitySupport.SUPPORTED
        }
        if (profile.parkAzimuthDeg != null || profile.backend == RotatorBackend.REMOTE_ROTCTLD) values[RotatorCapability.PARK] = CapabilitySupport.SUPPORTED
        return RotatorCapabilitySnapshot(values + profile.capabilityOverrides, "typed protocol + explicit profile overrides")
    }

    private fun protocol(profile: RotatorDeviceProfile): RotatorProtocol = when (profile.protocol) {
        RotatorProtocolKind.GS232 -> Gs232Protocol(profile.limits.elMax > profile.limits.elMin)
        RotatorProtocolKind.DCU_ROTOREZ -> DcuRotorEzProtocol()
        RotatorProtocolKind.EASYCOMM -> EasyCommProtocol(EasyCommVersion.II)
        RotatorProtocolKind.SPID_ROT1 -> SpidProtocol(false)
        RotatorProtocolKind.SPID_ROT2 -> SpidProtocol(true)
        RotatorProtocolKind.ARCO_COMPATIBLE -> error("Select ARCO's published GS-232 or EasyComm compatibility mode")
        else -> error("Protocol ${profile.protocol} is not a native serial protocol")
    }
}
