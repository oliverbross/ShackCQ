package app.shackcq.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

sealed interface UsbResult {
    data class Connected(val frames: ByteArray, val detail: String, val cwFrames: ByteArray = byteArrayOf()) : UsbResult
    data class PermissionRequired(val detail: String) : UsbResult
    data class Unavailable(val detail: String) : UsbResult
}

data class SerialDeviceDescriptor(
    val sessionKey: String,
    val stableKey: String,
    val driverFamily: String,
    val manufacturer: String,
    val product: String,
    val vidPid: String,
    val serialNumber: String,
    val portIndex: Int,
    val deviceAddress: String,
) {
    val fallbackKey: String get() = listOf(driverFamily, manufacturer, product, vidPid, portIndex.toString()).joinToString("|")
    val displayName: String get() = product.ifBlank { manufacturer }.ifBlank { driverFamily }
    val identityLine: String get() = listOf(
        manufacturer.takeIf { it.isNotBlank() && it != displayName },
        vidPid,
        serialNumber.takeIf(String::isNotBlank)?.let { "S/N ${it.compactIdentity()}" },
    ).filterNotNull().joinToString(" · ")
    val routeLine: String get() = "$driverFamily · Port ${portIndex + 1} · ${deviceAddress.substringAfterLast('/')}"
    val label: String get() = listOf(driverFamily, manufacturer.takeIf(String::isNotBlank), product.takeIf(String::isNotBlank), vidPid,
        serialNumber.takeIf(String::isNotBlank)?.let { "S/N $it" }, "port $portIndex", deviceAddress)
        .filterNotNull().filter(String::isNotBlank).joinToString(" · ")
}

private fun String.compactIdentity(): String = if (length <= 12) this else "…${takeLast(10)}"

fun containsElecraftCatResponse(bytes: ByteArray): Boolean = bytes.toString(Charsets.US_ASCII).split(';').any { frame ->
    (frame.startsWith("K3") && frame.length == 3 && frame[2] in "01") ||
        (frame.startsWith("OM") && frame.length > 4) ||
        (frame.startsWith("ID") && frame.length > 2) ||
        (frame.startsWith("FA") && frame.length == 13 && frame.drop(2).all(Char::isDigit))
}

data class FreshTqResponse(val frames: ByteArray, val transmitting: Boolean?)

data class UsbInterfaceDescriptor(
    val interfaceNumber: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
)

class UsbRadioTransport(
    private val context: Context,
    preferenceStore: String = "shackcq-usb",
) {
    companion object { const val ACTION_USB_PERMISSION = "app.shackcq.mobile.USB_PERMISSION" }
    private data class Candidate(val descriptor: SerialDeviceDescriptor, val driver: UsbSerialDriver, val portIndex: Int)

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = context.getSharedPreferences(preferenceStore, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var sessionSelection: String? = null
    private val fastQueries = listOf("FA;", "FB;", "IF;", "TQ;", "SM;", "SW;", "PO;", "AG;", "RG;", "BW;",
        "PC;", "ML;", "MG;", "KS;", "IS;")
    private val slowQueries = listOf("MD;", "DS;", "GT;", "PA;", "RA;", "RT;", "XT;", "FR;", "FT;")
    private val instrumentQueries = fastQueries + slowQueries
    private val connectQueries = listOf("K3;", "OM;", "ID;", "K31;", "AI2;") + instrumentQueries
    private var pollCount = 0
    @Volatile private var voiceOperationExclusive = false
    @Volatile private var eqOperationExclusive = false

    var candidates by mutableStateOf(emptyList<SerialDeviceDescriptor>()); private set
    var selected by mutableStateOf<SerialDeviceDescriptor?>(null); private set
    var controlLineStatus by mutableStateOf("RTS/DTR not checked"); private set
    val isConnected: Boolean get() = port?.isOpen == true
    val selectedStableIdentityHash: String?
        get() = selected?.stableKey?.let(::usbIdentityDigest)

    fun beginVoiceOperation() { voiceOperationExclusive = true }
    fun endVoiceOperation() { voiceOperationExclusive = false }

    suspend fun <T> exclusiveEqTransaction(block: suspend (EqCatIo) -> T): T = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            check(!voiceOperationExclusive) { "Voice macro owns CAT; stop it before opening EQ Studio" }
            val active = port ?: error("Connect the USB serial adapter first")
            eqOperationExclusive = true
            block(EqTransportSession(active))
        } finally {
            eqOperationExclusive = false
            mutex.unlock()
        }
    }

    fun discovered(): List<String> = refreshCandidates().map(SerialDeviceDescriptor::label)

    fun refreshCandidates(): List<SerialDeviceDescriptor> {
        val available = candidateRecords()
        candidates = available.map(Candidate::descriptor)
        val configured = sessionSelection?.let { key -> available.firstOrNull { it.descriptor.sessionKey == key } }
            ?: chooseConfiguredCandidate(available)
        if (configured != null) {
            sessionSelection = configured.descriptor.sessionKey
            selected = configured.descriptor
        }
        return candidates
    }

    suspend fun selectCandidate(sessionKey: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        closeLocked()
        val candidate = candidateRecords().firstOrNull { it.descriptor.sessionKey == sessionKey }
        if (candidate == null) return@withLock false
        sessionSelection = candidate.descriptor.sessionKey
        prefs.edit().putString("cat_stable_key", candidate.descriptor.stableKey)
            .putString("cat_fallback_key", candidate.descriptor.fallbackKey).apply()
        selected = candidate.descriptor
        refreshCandidates()
        true
    } }

    suspend fun connect(): UsbResult = withContext(Dispatchers.IO) { mutex.withLock {
        if (port?.isOpen == true) return@withLock UsbResult.Connected(byteArrayOf(), "CAT is already connected · ${selected?.label.orEmpty()}")
        val available = candidateRecords()
        candidates = available.map(Candidate::descriptor)
        val explicit = sessionSelection?.let { key -> available.firstOrNull { it.descriptor.sessionKey == key } }
        val choice = explicit ?: chooseConfiguredCandidate(available)
        if (choice == null) return@withLock UsbResult.Unavailable(if (available.size > 1) "Multiple eligible devices detected; select one in Settings · Radio" else "No supported CAT adapter detected")
        selected = choice.descriptor
        if (!awaitPermission(choice.driver.device)) {
            return@withLock UsbResult.PermissionRequired("USB permission was not granted · tap Connect to try again")
        }
        closeLocked()
        val refreshed = candidateRecords().firstOrNull {
            it.driver.device.deviceId == choice.driver.device.deviceId && it.portIndex == choice.portIndex
        } ?: choice
        val openedConnection = manager.openDevice(refreshed.driver.device)
            ?: return@withLock UsbResult.Unavailable("USB device could not be opened")
        val openedPort = refreshed.driver.ports.getOrNull(refreshed.portIndex) ?: run {
            openedConnection.close(); return@withLock UsbResult.Unavailable("Selected serial port is no longer available")
        }
        try {
            openedPort.open(openedConnection)
            openedPort.setParameters(38_400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            deassertControlLines(openedPort)
            connection = openedConnection
            port = openedPort
            pollCount = 0
            selected = refreshed.descriptor
            sessionSelection = refreshed.descriptor.sessionKey
            prefs.edit().putString("cat_stable_key", refreshed.descriptor.stableKey)
                .putString("cat_fallback_key", refreshed.descriptor.fallbackKey).apply()
            val frames = exchange(connectQueries)
            if (!containsElecraftCatResponse(frames)) {
                closeLocked()
                return@withLock UsbResult.Unavailable("Selected adapter opened at 38,400 baud but no Elecraft CAT response was received · ${refreshed.descriptor.label}")
            }
            UsbResult.Connected(frames, "Connected at 38,400 baud · ${refreshed.descriptor.label} · $controlLineStatus")
        } catch (error: Exception) {
            runCatching { deassertControlLines(openedPort) }
            runCatching { openedPort.close() }
            openedConnection.close()
            connection = null; port = null
            UsbResult.Unavailable("USB/CAT failed closed: ${error.message ?: error.javaClass.simpleName}")
        }
    } }

    suspend fun connectRaw(
        stableIdentityHash: String?,
        baud: Int,
        dataBits: Int = 8,
        stopBits: Int = 1,
        parity: String = "N",
    ): UsbResult = withContext(Dispatchers.IO) { mutex.withLock {
        require(baud in 300..3_000_000 && dataBits in 5..8 && stopBits in 1..2)
        require(parity in setOf("N", "E", "O"))
        if (port?.isOpen == true) closeLocked()
        val available = candidateRecords()
        candidates = available.map(Candidate::descriptor)
        val matches = stableIdentityHash?.let { hash -> available.filter { usbIdentityDigest(it.descriptor.stableKey) == hash } }
            ?: sessionSelection?.let { key -> available.filter { it.descriptor.sessionKey == key } }
            ?: chooseConfiguredCandidate(available)?.let(::listOf).orEmpty()
        val choice = matches.singleOrNull()
            ?: return@withLock UsbResult.Unavailable(if (matches.size > 1 || available.size > 1) {
                "Multiple eligible devices detected; select an exact USB identity"
            } else "Configured USB serial device is unavailable")
        selected = choice.descriptor
        if (!awaitPermission(choice.driver.device)) return@withLock UsbResult.PermissionRequired("USB permission was not granted")
        val openedConnection = manager.openDevice(choice.driver.device)
            ?: return@withLock UsbResult.Unavailable("USB device could not be opened")
        val openedPort = choice.driver.ports.getOrNull(choice.portIndex) ?: run {
            openedConnection.close(); return@withLock UsbResult.Unavailable("Selected serial port is no longer available")
        }
        try {
            openedPort.open(openedConnection)
            openedPort.setParameters(
                baud,
                dataBits,
                if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1,
                when (parity) { "E" -> UsbSerialPort.PARITY_EVEN; "O" -> UsbSerialPort.PARITY_ODD; else -> UsbSerialPort.PARITY_NONE },
            )
            deassertControlLines(openedPort)
            connection = openedConnection
            port = openedPort
            selected = choice.descriptor
            sessionSelection = choice.descriptor.sessionKey
            UsbResult.Connected(byteArrayOf(), "Connected raw USB serial · ${choice.descriptor.label}")
        } catch (error: Exception) {
            runCatching { openedPort.close() }
            openedConnection.close()
            connection = null; port = null
            UsbResult.Unavailable("USB serial failed closed: ${error.message ?: error.javaClass.simpleName}")
        }
    } }

    suspend fun rawExchange(request: ByteArray, timeoutMillis: Int = 500): ByteArray =
        withContext(Dispatchers.IO) { mutex.withLock {
            val active = port ?: error("USB serial adapter is disconnected")
            active.write(request, timeoutMillis.coerceIn(50, 60_000))
            readFrames(active, timeoutMillis.coerceIn(1, 60_000), 35)
        } }

    suspend fun rawRead(maximum: Int, timeoutMillis: Int): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        require(maximum in 1..65_536)
        val active = port ?: error("USB serial adapter is disconnected")
        val buffer = ByteArray(maximum)
        val count = active.read(buffer, timeoutMillis.coerceIn(1, 60_000)).coerceAtLeast(0)
        buffer.copyOf(count)
    } }

    suspend fun rawWrite(data: ByteArray, timeoutMillis: Int = 1_000): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val active = port ?: error("USB serial adapter is disconnected")
        active.write(data, timeoutMillis.coerceIn(50, 60_000))
        data.size
    } }

    fun selectedInterfaces(): List<UsbInterfaceDescriptor> {
        val sessionKey = selected?.sessionKey ?: return emptyList()
        val deviceId = sessionKey.substringBefore(':').toIntOrNull() ?: return emptyList()
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return emptyList()
        return (0 until device.interfaceCount).map { index ->
            device.getInterface(index).let { UsbInterfaceDescriptor(it.id, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        }
    }

    suspend fun send(command: String): UsbResult = withContext(Dispatchers.IO) { mutex.withLock {
        val active = port ?: return@withLock UsbResult.Unavailable("Connect the USB serial adapter first")
        val normalized = normalize(command) ?: return@withLock UsbResult.Unavailable("CAT command is empty")
        if (voiceOperationExclusive && normalized != "RX;") return@withLock UsbResult.Unavailable("Voice macro owns CAT until RX cleanup completes")
        try {
            active.write(normalized.toByteArray(Charsets.US_ASCII), 1_000)
            val frames = readFrames(active) + exchange(instrumentQueries)
            UsbResult.Connected(frames, "Sent $normalized")
        } catch (error: Exception) { failLocked(error) }
    } }

    suspend fun sendFast(command: String): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        val active = port ?: error("CAT adapter is disconnected")
        val normalized = normalize(command) ?: error("CAT command is empty")
        try {
            active.write(normalized.toByteArray(Charsets.US_ASCII), 500)
            readFrames(active, 100, 30)
        } catch (error: Exception) { closeLocked(); throw error }
    } }

    suspend fun queryTqFresh(): FreshTqResponse = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val frames = exchangeFresh("TQ;", initialTimeout = 180, trailingTimeout = 35)
            FreshTqResponse(frames, parseFreshTq(frames))
        } catch (error: Exception) { closeLocked(); throw error }
    } }

    suspend fun confirmTq(expected: Boolean, timeoutMillis: Long = 1_000): FreshTqResponse = withContext(Dispatchers.IO) { mutex.withLock {
        val started = System.currentTimeMillis()
        val all = ArrayList<Byte>()
        var value: Boolean? = null
        while (System.currentTimeMillis() - started < timeoutMillis) {
            val frames = exchangeFresh("TQ;", initialTimeout = 120, trailingTimeout = 25)
            frames.forEach(all::add)
            value = parseFreshTq(frames)
            if (value == expected) break
            delay(70)
        }
        FreshTqResponse(all.toByteArray(), value.takeIf { it == expected })
    } }

    suspend fun poll(): UsbResult? = withContext(Dispatchers.IO) { mutex.withLock {
        if (port == null || voiceOperationExclusive || eqOperationExclusive) return@withLock null
        try {
            val queries = if (pollCount++ % 6 == 0) instrumentQueries else fastQueries
            UsbResult.Connected(exchange(queries), "Live CAT state · ${selected?.label.orEmpty()}")
        } catch (error: Exception) { failLocked(error) }
    } }

    suspend fun pollCwText(): UsbResult? = withContext(Dispatchers.IO) { mutex.withLock {
        if (port == null || voiceOperationExclusive || eqOperationExclusive) return@withLock null
        try {
            val frames = exchange(listOf("DB;"), initialTimeout = 120, trailingTimeout = 40)
            UsbResult.Connected(frames, "Live CW text", frames)
        } catch (error: Exception) { failLocked(error) }
    } }

    suspend fun disconnect() = withContext(Dispatchers.IO) { mutex.withLock { closeLocked() } }

    private fun candidateRecords(): List<Candidate> = UsbSerialProber.getDefaultProber().findAllDrivers(manager).flatMap { driver ->
        driver.ports.indices.map { portIndex ->
            val device = driver.device
            val serial = if (manager.hasPermission(device)) runCatching { device.serialNumber.orEmpty() }.getOrDefault("") else ""
            val family = driver.javaClass.simpleName.removeSuffix("SerialDriver").ifBlank { "USB serial" }
            val manufacturer = device.manufacturerName.orEmpty()
            val product = device.productName.orEmpty()
            val vidPid = "%04X:%04X".format(device.vendorId, device.productId)
            val stable = listOf(family, manufacturer, product, vidPid, serial, portIndex.toString()).joinToString("|")
            Candidate(SerialDeviceDescriptor("${device.deviceId}:$portIndex", stable, family, manufacturer, product,
                vidPid, serial, portIndex, device.deviceName), driver, portIndex)
        }
    }

    private fun chooseConfiguredCandidate(available: List<Candidate>): Candidate? {
        val exact = chooseStableCandidate(available, prefs.getString("cat_stable_key", null)) { it.descriptor.stableKey }.selected
        if (exact != null) return exact
        val fallback = prefs.getString("cat_fallback_key", null) ?: return null
        return available.filter { it.descriptor.fallbackKey == fallback }.singleOrNull()
    }

    private suspend fun awaitPermission(device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val returnedDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (returnedDevice?.deviceId != device.deviceId) return
                result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_EXPORTED)
        return try {
            val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pendingIntent)
            withTimeoutOrNull(20_000) {
                while (true) {
                    if (manager.hasPermission(device)) return@withTimeoutOrNull true
                    if (result.isCompleted && !result.await()) return@withTimeoutOrNull false
                    // A granted broadcast can arrive just before UsbManager reflects the grant.
                    // Keep polling the authoritative OS state for the remainder of the bound.
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: manager.hasPermission(device)
        } finally { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun deassertControlLines(active: UsbSerialPort) {
        val supported = active.supportedControlLines
        if (ControlLine.RTS in supported) active.rts = false
        if (ControlLine.DTR in supported) active.dtr = false
        val activeLines = if (ControlLine.RTS in supported || ControlLine.DTR in supported) active.controlLines else emptySet()
        require(ControlLine.RTS !in activeLines && ControlLine.DTR !in activeLines) { "RTS/DTR could not be confirmed inactive" }
        controlLineStatus = "RTS/DTR confirmed inactive"
    }

    private fun exchange(commands: List<String>, initialTimeout: Int = 350, trailingTimeout: Int = 35): ByteArray {
        val active = port ?: return byteArrayOf()
        if (commands.isEmpty()) return byteArrayOf()
        active.write(commands.joinToString("").toByteArray(Charsets.US_ASCII), 1_000)
        return readFrames(active, initialTimeout, trailingTimeout)
    }

    private fun exchangeFresh(command: String, initialTimeout: Int, trailingTimeout: Int): ByteArray {
        val active = port ?: return byteArrayOf()
        readFrames(active, initialTimeout = 1, trailingTimeout = 1)
        active.write(command.toByteArray(Charsets.US_ASCII), 500)
        return readFrames(active, initialTimeout, trailingTimeout)
    }

    private fun readFrames(active: UsbSerialPort, initialTimeout: Int = 350, trailingTimeout: Int = 35): ByteArray {
        val output = ArrayList<Byte>()
        var timeout = initialTimeout
        repeat(12) {
            val buffer = ByteArray(512)
            val count = active.read(buffer, timeout).coerceAtLeast(0)
            if (count == 0) return output.toByteArray()
            repeat(count) { index -> output += buffer[index] }
            timeout = trailingTimeout
        }
        return output.toByteArray()
    }

    private inner class EqTransportSession(private val active: UsbSerialPort) : EqCatIo {
        private val allowedWrites = Regex("^(MN(?:008|009|255);|SWT(?:19|27|20|28|21|29|32|33);|UP;|DN;|TE(?:[+-]\\d{2}){8};)$")
        private val allowedQueries = setOf("TQ;", "MN;", "DB;", "FT;", "MD;", "MD$;", "ES;", "RVM;", "ID;", "OM;")

        override suspend fun query(command: String, expectedPrefix: String, timeoutMillis: Long): String {
            val normalized = normalize(command) ?: error("CAT query is empty")
            require(normalized in allowedQueries) { "EQ query is not allowlisted: $normalized" }
            readFrames(active, 1, 1)
            active.write(normalized.toByteArray(Charsets.US_ASCII), 500)
            val deadline = System.currentTimeMillis() + timeoutMillis
            val pending = StringBuilder()
            val unsolicited = mutableListOf<String>()
            var matched: String? = null
            var quietDeadline = Long.MAX_VALUE
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(256)
                val now = System.currentTimeMillis()
                if (matched != null && now >= quietDeadline) return matched
                val remaining = minOf(deadline - now, if (matched == null) 120 else quietDeadline - now)
                val wait = remaining.coerceIn(1, 120).toInt()
                val count = active.read(buffer, wait).coerceAtLeast(0)
                if (count == 0) {
                    if (matched != null && System.currentTimeMillis() >= quietDeadline) return matched
                    continue
                }
                pending.append(buffer.copyOf(count).toString(Charsets.US_ASCII))
                while (';' in pending) {
                    val end = pending.indexOf(";")
                    val frame = pending.substring(0, end + 1)
                    pending.delete(0, end + 1)
                    if (frame.startsWith(expectedPrefix, ignoreCase = true)) {
                        matched = frame
                        quietDeadline = System.currentTimeMillis() + 55
                    } else unsolicited += frame
                }
            }
            if (matched != null) return matched
            error("Timed out waiting for $expectedPrefix response${if (unsolicited.isEmpty()) "" else " (${unsolicited.size} unrelated frame(s) received)"}")
        }

        override suspend fun write(command: String) {
            val normalized = normalize(command) ?: error("CAT command is empty")
            require(allowedWrites.matches(normalized)) { "EQ write is not allowlisted: $normalized" }
            active.write(normalized.toByteArray(Charsets.US_ASCII), 500)
        }
    }

    private fun normalize(command: String): String? = command.trim().let { if (it.endsWith(';')) it else "$it;" }.takeUnless { it == ";" }

    private fun failLocked(error: Exception): UsbResult.Unavailable {
        closeLocked()
        return UsbResult.Unavailable("USB/CAT failed: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun closeLocked() {
        port?.let { active -> runCatching { deassertControlLines(active) }; runCatching { active.close() } }
        connection?.close()
        port = null; connection = null
    }
}

private fun usbIdentityDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
