package app.rigweave.mobile.morse

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.rigweave.mobile.SerialDeviceDescriptor
import app.rigweave.mobile.UsbRadioTransport
import app.rigweave.mobile.UsbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class MorseTrainerStore(context: Context) {
    private val prefs = context.getSharedPreferences("rigweave-morse", Context.MODE_PRIVATE)
    var settings by mutableStateOf(load()); private set

    fun update(value: MorseTrainerSettings) {
        settings = value.normalized()
        prefs.edit {
            putInt("character_wpm", settings.characterWpm)
            putInt("effective_wpm", settings.effectiveWpm)
            putInt("pitch_hz", settings.pitchHz)
            putInt("volume_percent", settings.volumePercent)
            putInt("session_size", settings.sessionSize)
            putInt("koch_characters", settings.kochCharacters)
            putInt("group_length", settings.groupLength)
            putString("callsign_difficulty", settings.callsignDifficulty.name)
            putInt("callsign_repeats", settings.callsignRepeats)
            putString("machine_characters", settings.machineCharacters)
        }
    }

    private fun load() = MorseTrainerSettings(
        characterWpm = prefs.getInt("character_wpm", 20),
        effectiveWpm = prefs.getInt("effective_wpm", 12),
        pitchHz = prefs.getInt("pitch_hz", 600),
        volumePercent = prefs.getInt("volume_percent", 70),
        sessionSize = prefs.getInt("session_size", 25),
        kochCharacters = prefs.getInt("koch_characters", 12),
        groupLength = prefs.getInt("group_length", 5),
        callsignDifficulty = runCatching {
            CallsignDifficulty.valueOf(prefs.getString("callsign_difficulty", CallsignDifficulty.FIVE.name).orEmpty())
        }.getOrDefault(CallsignDifficulty.FIVE),
        callsignRepeats = prefs.getInt("callsign_repeats", 1),
        machineCharacters = prefs.getString("machine_characters", "KMURESNAPTLWIOGJHFVBYXCQZ1234567890").orEmpty(),
    ).normalized()
}

class MorseAudioPlayer {
    @Volatile private var active: AudioTrack? = null

    suspend fun play(text: String, settings: MorseTrainerSettings, repeats: Int = 1) = withContext(Dispatchers.IO) {
        stop()
        val safe = settings.normalized()
        val sampleRate = 16_000
        val dotSeconds = 1.2 / safe.characterWpm
        val samples = ByteArrayOutputStream()
        fun tone(seconds: Double) {
            val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
            repeat(count) { index ->
                val envelope = minOf(1.0, index / 64.0, (count - index) / 64.0).coerceAtLeast(0.0)
                val sample = (sin(2.0 * PI * safe.pitchHz * index / sampleRate) * Short.MAX_VALUE *
                    (safe.volumePercent / 100.0) * 0.45 * envelope).toInt().toShort()
                samples.write(sample.toInt() and 0xff)
                samples.write((sample.toInt() shr 8) and 0xff)
            }
        }
        fun silence(seconds: Double) { repeat((seconds * sampleRate).toInt().coerceAtLeast(0) * 2) { samples.write(0) } }
        val encoded = MorseCode.encode(text)
        repeat(repeats.coerceIn(1, 3)) { repeatIndex ->
            encoded.forEachIndexed { characterIndex, pattern ->
                if (pattern == null) {
                    // The previous character already contributed its 3-unit gap.
                    silence(dotSeconds * 4)
                } else {
                    pattern.forEachIndexed { symbolIndex, symbol ->
                        tone(dotSeconds * if (symbol == '.') 1 else 3)
                        if (symbolIndex != pattern.lastIndex) silence(dotSeconds)
                    }
                    if (characterIndex != encoded.lastIndex) {
                        val characterGap = if (safe.effectiveWpm < safe.characterWpm) {
                            3.0 * dotSeconds * safe.characterWpm / safe.effectiveWpm
                        } else 3.0 * dotSeconds
                        silence(characterGap)
                    }
                }
            }
            if (repeatIndex + 1 < repeats) silence(1.5)
        }
        val pcm = samples.toByteArray()
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(pcm.size, AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        active = track
        track.write(pcm, 0, pcm.size)
        track.play()
        val durationMillis = (pcm.size / 2L) * 1_000L / sampleRate
        delay(durationMillis + 80)
        if (active !== track) return@withContext
        active = null
        runCatching { track.stop() }
        track.release()
    }

    fun stop() {
        val track = active ?: return
        active = null
        runCatching { track.stop() }
        track.release()
    }
}

enum class M32ConnectionState { DISCONNECTED, CONNECTING, READY, ERROR }
enum class M32ConnectionRoute { USB, BLE }

class M32PocketController(context: Context) {
    private val usbTransport = UsbRadioTransport(context.applicationContext, "rigweave-m32-usb")
    private val bleTransport = M32BleSerialTransport(context.applicationContext)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var previousSerialOutput: Int? = null
    var devices by mutableStateOf(emptyList<SerialDeviceDescriptor>()); private set
    var selectedSessionKey by mutableStateOf<String?>(null); private set
    var bleDevices by mutableStateOf(emptyList<M32BleDevice>()); private set
    var selectedBleAddress by mutableStateOf<String?>(null); private set
    var selectedRoute by mutableStateOf(M32ConnectionRoute.BLE); private set
    var state by mutableStateOf(M32ConnectionState.DISCONNECTED); private set
    var detail by mutableStateOf("Use BLE Serial for the full M32 protocol, or choose a USB device."); private set
    var reportedWpm by mutableStateOf<Int?>(null); private set
    var inputChunk by mutableStateOf(""); private set
    var inputRevision by mutableLongStateOf(0L); private set

    fun scanUsb() {
        selectedRoute = M32ConnectionRoute.USB
        devices = usbTransport.refreshCandidates()
        if (devices.isEmpty()) detail = "No USB serial device detected. Use BLE Serial on this tablet."
    }

    suspend fun scanBle() {
        selectedRoute = M32ConnectionRoute.BLE
        detail = "Searching for Morserino-32…"
        runCatching { bleTransport.scan() }
            .onSuccess { found ->
                bleDevices = found
                if (found.size == 1) selectedBleAddress = found.single().address
                detail = if (found.isEmpty()) {
                    "No M32 found. On M32 set Bluetooth Use to BLE Serial, then try again."
                } else "Select Morserino-32, keep it at the main menu, then connect."
            }
            .onFailure { error ->
                state = M32ConnectionState.ERROR
                detail = "Bluetooth search failed: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    fun selectUsb(sessionKey: String) {
        selectedSessionKey = sessionKey
        selectedRoute = M32ConnectionRoute.USB
    }

    fun selectBle(address: String) {
        selectedBleAddress = address
        selectedRoute = M32ConnectionRoute.BLE
    }

    fun bluetoothPermissionDenied() {
        state = M32ConnectionState.ERROR
        detail = "Bluetooth permission is required to find and connect to Morserino-32."
    }

    suspend fun connect(targetWpm: Int) {
        state = M32ConnectionState.CONNECTING
        if (selectedRoute == M32ConnectionRoute.BLE) connectBle(targetWpm) else connectUsb(targetWpm)
    }

    private suspend fun connectUsb(targetWpm: Int) {
        detail = "Requesting USB access…"
        val key = selectedSessionKey ?: devices.singleOrNull()?.sessionKey
        if (key == null) {
            state = M32ConnectionState.ERROR
            detail = if (devices.isEmpty()) "No USB serial device detected." else "Select the M32 Pocket USB device first."
            return
        }
        if (!usbTransport.selectCandidate(key)) {
            state = M32ConnectionState.ERROR; detail = "The selected USB device disappeared."; return
        }
        when (val result = usbTransport.connectRaw(null, 115_200)) {
            is UsbResult.Connected -> configureM32(targetWpm)
            is UsbResult.PermissionRequired -> { state = M32ConnectionState.ERROR; detail = result.detail }
            is UsbResult.Unavailable -> { state = M32ConnectionState.ERROR; detail = result.detail }
        }
    }

    private suspend fun connectBle(targetWpm: Int) {
        val address = selectedBleAddress ?: bleDevices.singleOrNull()?.address
        if (address == null) {
            state = M32ConnectionState.ERROR
            detail = "Find and select Morserino-32 first."
            return
        }
        detail = "Connecting to M32 BLE Serial…"
        runCatching { bleTransport.connect(address) }
            .onSuccess {
                detail = "On the M32 main menu, press FN when ‘Allow connect?’ appears."
                configureM32(targetWpm)
            }
            .onFailure { error ->
                state = M32ConnectionState.ERROR
                detail = "M32 Bluetooth connection failed: ${error.message ?: error.javaClass.simpleName}"
                bleTransport.disconnect()
            }
    }

    private suspend fun configureM32(targetWpm: Int) {
        runCatching {
            command("put device/protocol/on", if (selectedRoute == M32ConnectionRoute.BLE) 23_000 else 700)
            command("put control/speed/${targetWpm.coerceIn(5, 60)}")
            val serialOutput = command("get config/serialOut")
            val serialOutputValue = Regex("\"value\"\\s*:\\s*(\\d+)").find(serialOutput)?.groupValues?.get(1)?.toIntOrNull()
            previousSerialOutput = serialOutputValue?.takeUnless { it in setOf(1, 3, 5) }
            if (previousSerialOutput != null) command("put config/serialOut/1")
            val menus = command("get menus", 1_200)
            val keyerNumber = extractJsonObjects(menus).asSequence().mapNotNull { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                .flatMap { json -> sequence {
                    val values = json.optJSONArray("menus") ?: return@sequence
                    repeat(values.length()) { yield(values.optJSONObject(it)) }
                } }.filterNotNull().firstOrNull { menu ->
                    menu.optBoolean("executable", true) && menu.optString("content").matches(Regex("(?i).*CW Keyer.*"))
                }?.optInt("menu number", -1)?.takeIf { it >= 0 }
            if (keyerNumber != null) command("put menu/start now/$keyerNumber", 1_200)
            else {
                command("put menu/start/1")
                runCatching { command("put menu/activate/1") }
            }
            reportedWpm = targetWpm.coerceIn(5, 60)
            state = M32ConnectionState.READY
            detail = "M32 Pocket ready at ${reportedWpm} WPM · keyed characters stream over ${selectedRoute.name}."
            startInputReader()
        }.onFailure { error ->
            state = M32ConnectionState.ERROR
            detail = "M32 setup failed closed: ${error.message ?: error.javaClass.simpleName}"
            disconnectTransport()
        }
    }

    suspend fun disconnect() {
        readerJob?.cancelAndJoin()
        readerJob = null
        runCatching { command("put menu/stop/1", 500) }
        previousSerialOutput?.let { value -> runCatching { command("put config/serialOut/$value", 500) } }
        previousSerialOutput = null
        runCatching { command("put device/protocol/off", 500) }
        disconnectTransport()
        state = M32ConnectionState.DISCONNECTED
        reportedWpm = null
        detail = "M32 Pocket disconnected."
    }

    fun dispose() { ioScope.launch { disconnect() } }

    private fun startInputReader() {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            while (isActive && state == M32ConnectionState.READY) {
                val bytes = runCatching {
                    if (selectedRoute == M32ConnectionRoute.BLE) bleTransport.read(180)
                    else usbTransport.rawRead(256, 180)
                }.getOrElse {
                    state = M32ConnectionState.ERROR
                    detail = "M32 ${selectedRoute.name} input stopped: ${it.message ?: it.javaClass.simpleName}"
                    break
                }
                if (bytes.isEmpty()) continue
                val decoded = bytes.toString(Charsets.UTF_8).uppercase().filter { character ->
                    character.isLetterOrDigit() || character in ".?/=+" || character.isWhitespace()
                }
                if (decoded.isNotEmpty()) {
                    inputChunk = decoded
                    inputRevision++
                }
            }
        }
    }

    private suspend fun command(value: String, timeout: Int = 700): String {
        val response = if (selectedRoute == M32ConnectionRoute.BLE) {
            bleTransport.exchange(value, timeout.toLong()).toString(Charsets.UTF_8)
        } else usbTransport.rawExchange("$value\n".toByteArray(Charsets.UTF_8), timeout).toString(Charsets.UTF_8)
        val error = extractJsonObjects(response).firstOrNull { "\"error\"" in it }
        check(error == null) { error ?: "M32 command failed." }
        return response
    }

    private suspend fun disconnectTransport() {
        usbTransport.disconnect()
        bleTransport.disconnect()
    }
}

internal fun extractJsonObjects(value: String): List<String> {
    val result = mutableListOf<String>()
    var start = -1
    var depth = 0
    var quoted = false
    var escaped = false
    value.forEachIndexed { index, character ->
        if (escaped) { escaped = false; return@forEachIndexed }
        if (character == '\\' && quoted) { escaped = true; return@forEachIndexed }
        if (character == '"') { quoted = !quoted; return@forEachIndexed }
        if (quoted) return@forEachIndexed
        if (character == '{') { if (depth == 0) start = index; depth++ }
        if (character == '}' && depth > 0) {
            depth--
            if (depth == 0 && start >= 0) { result += value.substring(start, index + 1); start = -1 }
        }
    }
    return result
}
