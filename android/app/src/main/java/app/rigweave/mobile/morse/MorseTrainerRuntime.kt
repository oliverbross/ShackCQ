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
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

data class MorseSessionRecord(
    val trainer: MorseTrainerKind,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val total: Int,
    val correct: Int,
    val accuracyPercent: Int,
    val score: Int = 0,
    val detail: String = "",
)

data class CallsignProgress(val correct: Int = 0, val incorrect: Int = 0, val hard: Boolean = false, val suspended: Boolean = false)

class MorseTrainerStore(context: Context) {
    private val prefs = context.getSharedPreferences("rigweave-morse", Context.MODE_PRIVATE)
    var settings by mutableStateOf(load()); private set
    var historyRevision by mutableLongStateOf(0L); private set

    fun update(value: MorseTrainerSettings) {
        settings = value.normalized()
        prefs.edit {
            putInt("character_wpm", settings.characterWpm)
            putInt("effective_wpm", settings.effectiveWpm)
            putInt("pitch_hz", settings.pitchHz)
            putInt("volume_percent", settings.volumePercent)
            putInt("session_size", settings.sessionSize)
            putInt("callsign_session_size", settings.callsignSessionSize)
            putString("callsign_source_mode", settings.callsignSourceMode.name)
            putInt("callsign_character_wpm", settings.callsignCharacterWpm)
            putInt("callsign_effective_wpm", settings.callsignEffectiveWpm)
            putInt("callsign_pitch_hz", settings.callsignPitchHz)
            putInt("callsign_volume_percent", settings.callsignVolumePercent)
            putString("tx_mode", settings.txMode.name)
            putString("tx_word_length", settings.txWordLength.name)
            putInt("tx_max_attempts", settings.txMaxAttempts)
            putString("callsign_difficulty", settings.callsignDifficulty.name)
            putInt("callsign_repeats", settings.callsignRepeats)
            putString("repeat_delay_mode", settings.repeatDelayMode.name)
            putInt("repeat_delay_millis", settings.repeatDelayMillis)
            putString("noise_level", settings.noiseLevel.name)
            putInt("audio_filter_hz", settings.audioFilterHz)
            putString("callsign_noise_level", settings.callsignNoiseLevel.name)
            putInt("callsign_audio_filter_hz", settings.callsignAudioFilterHz)
            putInt("machine_wpm", settings.machineWpm)
            putInt("machine_pitch_hz", settings.machinePitchHz)
            putInt("machine_volume_percent", settings.machineVolumePercent)
            putString("machine_set", settings.machineSet.name)
            putString("machine_training_mode", settings.machineTrainingMode.name)
            putString("machine_drill_mode", settings.machineDrillMode.name)
            putInt("machine_lesson", settings.machineLesson)
            putString("machine_characters", settings.machineCharacters)
            putString("machine_confusable_pair", settings.machineConfusablePair)
            putBoolean("machine_buzzer", settings.machineBuzzer)
            putBoolean("machine_show_morse", settings.machineShowMorse)
        }
    }

    fun loadMachineProgress(): Map<Char, CharacterProgress> = prefs.getString("machine_progress", "").orEmpty()
        .split(';').mapNotNull { row ->
            val fields = row.split(':')
            val character = fields.getOrNull(0)?.firstOrNull() ?: return@mapNotNull null
            val correct = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val incorrect = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val response = fields.getOrNull(3)?.toLongOrNull() ?: 0L
            val samples = fields.getOrNull(4)?.toIntOrNull() ?: 0
            character to CharacterProgress(correct, incorrect, response, samples)
        }.toMap()

    fun saveMachineProgress(progress: Map<Char, CharacterProgress>) {
        prefs.edit { putString("machine_progress", progress.entries.joinToString(";") { (character, value) ->
            "$character:${value.correct}:${value.incorrect}:${value.responseMillis}:${value.samples}"
        }) }
    }

    fun resetMachineProgress() {
        prefs.edit { remove("machine_progress") }
        historyRevision++
    }

    fun saveSession(record: MorseSessionRecord) {
        val encoded = listOf(
            record.trainer.name, record.endedAtMillis, record.durationMillis, record.total,
            record.correct, record.accuracyPercent, record.score,
            record.detail.replace('|', '/').replace('\n', ' '),
        ).joinToString("|")
        val rows = (prefs.getString("session_history", "").orEmpty().lineSequence().filter(String::isNotBlank).toList() + encoded)
            .takeLast(150)
        prefs.edit { putString("session_history", rows.joinToString("\n")) }
        historyRevision++
    }

    fun sessionHistory(trainer: MorseTrainerKind): List<MorseSessionRecord> {
        historyRevision // Compose observation
        return prefs.getString("session_history", "").orEmpty().lineSequence().mapNotNull { row ->
            val field = row.split('|', limit = 8)
            val kind = runCatching { MorseTrainerKind.valueOf(field.getOrNull(0).orEmpty()) }.getOrNull() ?: return@mapNotNull null
            MorseSessionRecord(
                trainer = kind,
                endedAtMillis = field.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null,
                durationMillis = field.getOrNull(2)?.toLongOrNull() ?: 0,
                total = field.getOrNull(3)?.toIntOrNull() ?: 0,
                correct = field.getOrNull(4)?.toIntOrNull() ?: 0,
                accuracyPercent = field.getOrNull(5)?.toIntOrNull() ?: 0,
                score = field.getOrNull(6)?.toIntOrNull() ?: 0,
                detail = field.getOrNull(7).orEmpty(),
            )
        }.filter { it.trainer == trainer }.sortedByDescending(MorseSessionRecord::endedAtMillis).toList()
    }

    fun callsignProgress(): Map<String, CallsignProgress> = prefs.getString("callsign_progress", "").orEmpty()
        .split(';').mapNotNull { row ->
            val field = row.split(':')
            val call = field.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            call to CallsignProgress(field.getOrNull(1)?.toIntOrNull() ?: 0, field.getOrNull(2)?.toIntOrNull() ?: 0,
                field.getOrNull(3) == "1", field.getOrNull(4) == "1")
        }.toMap()

    fun reviewCallsigns(): List<String> = callsignProgress().entries.filterNot { it.value.suspended }
        .sortedWith(compareByDescending<Map.Entry<String, CallsignProgress>> { it.value.hard }
            .thenByDescending { it.value.incorrect - it.value.correct }).map(Map.Entry<String, CallsignProgress>::key)

    fun recordCallsign(callsign: String, correct: Boolean) = updateCallsign(callsign) { prior ->
        if (correct) prior.copy(correct = prior.correct + 1) else prior.copy(incorrect = prior.incorrect + 1)
    }

    fun setCallsignHard(callsign: String, hard: Boolean) = updateCallsign(callsign) { it.copy(hard = hard) }
    fun setCallsignSuspended(callsign: String, suspended: Boolean) = updateCallsign(callsign) { it.copy(suspended = suspended) }
    fun resetCallsign(callsign: String) = updateCallsign(callsign) { CallsignProgress() }

    private fun updateCallsign(callsign: String, transform: (CallsignProgress) -> CallsignProgress) {
        val normalized = callsign.uppercase().trim()
        if (normalized.isBlank()) return
        val values = callsignProgress().toMutableMap()
        values[normalized] = transform(values[normalized] ?: CallsignProgress())
        prefs.edit { putString("callsign_progress", values.entries.joinToString(";") { (call, value) ->
            "$call:${value.correct}:${value.incorrect}:${if (value.hard) 1 else 0}:${if (value.suspended) 1 else 0}"
        }) }
        historyRevision++
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T = runCatching {
        enumValueOf<T>(prefs.getString(key, fallback.name).orEmpty())
    }.getOrDefault(fallback)

    private fun load() = MorseTrainerSettings(
        characterWpm = prefs.getInt("character_wpm", 20),
        effectiveWpm = prefs.getInt("effective_wpm", 12),
        pitchHz = prefs.getInt("pitch_hz", 600),
        volumePercent = prefs.getInt("volume_percent", 70),
        sessionSize = prefs.getInt("session_size", 25),
        callsignSessionSize = prefs.getInt("callsign_session_size", 25),
        callsignSourceMode = enumValue("callsign_source_mode", CallsignSourceMode.DIFFICULTY),
        callsignCharacterWpm = prefs.getInt("callsign_character_wpm", 28),
        callsignEffectiveWpm = prefs.getInt("callsign_effective_wpm", 8),
        callsignPitchHz = prefs.getInt("callsign_pitch_hz", 550),
        callsignVolumePercent = prefs.getInt("callsign_volume_percent", 60),
        txMode = enumValue("tx_mode", TxContentMode.REAL_WORDS),
        txWordLength = enumValue("tx_word_length", TxWordLength.ANY),
        txMaxAttempts = prefs.getInt("tx_max_attempts", 3),
        callsignDifficulty = enumValue("callsign_difficulty", CallsignDifficulty.FIVE),
        callsignRepeats = prefs.getInt("callsign_repeats", 1),
        repeatDelayMode = enumValue("repeat_delay_mode", RepeatDelayMode.AUTO),
        repeatDelayMillis = prefs.getInt("repeat_delay_millis", 1_500),
        noiseLevel = enumValue("noise_level", MorseNoiseLevel.OFF),
        audioFilterHz = prefs.getInt("audio_filter_hz", 700),
        callsignNoiseLevel = enumValue("callsign_noise_level", MorseNoiseLevel.S3),
        callsignAudioFilterHz = prefs.getInt("callsign_audio_filter_hz", 700),
        machineWpm = prefs.getInt("machine_wpm", 20),
        machinePitchHz = prefs.getInt("machine_pitch_hz", 600),
        machineVolumePercent = prefs.getInt("machine_volume_percent", 70),
        machineSet = enumValue("machine_set", MachineCharacterSet.KOCH),
        machineTrainingMode = enumValue("machine_training_mode", MachineTrainingMode.LESSON),
        machineDrillMode = enumValue("machine_drill_mode", MachineDrillMode.ADAPTIVE),
        machineLesson = prefs.getInt("machine_lesson", 1),
        machineCharacters = prefs.getString("machine_characters", "KM").orEmpty(),
        machineConfusablePair = prefs.getString("machine_confusable_pair", "U/D").orEmpty(),
        machineBuzzer = prefs.getBoolean("machine_buzzer", true),
        machineShowMorse = prefs.getBoolean("machine_show_morse", false),
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
        val random = Random(text.hashCode())
        val noiseMix = safe.noiseLevel.amplitude
        val filterAlpha = (2.0 * PI * safe.audioFilterHz / sampleRate).coerceIn(0.02, 0.45)
        var filteredNoise = 0.0
        fun writeSample(signal: Double) {
            val white = random.nextDouble(-1.0, 1.0)
            filteredNoise += filterAlpha * (white - filteredNoise)
            val mixed = (signal + filteredNoise * noiseMix).coerceIn(-1.0, 1.0)
            val sample = (mixed * Short.MAX_VALUE).toInt().toShort()
            samples.write(sample.toInt() and 0xff)
            samples.write((sample.toInt() shr 8) and 0xff)
        }
        fun tone(seconds: Double) {
            val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
            repeat(count) { index ->
                val envelope = minOf(1.0, index / 64.0, (count - index) / 64.0).coerceAtLeast(0.0)
                writeSample(sin(2.0 * PI * safe.pitchHz * index / sampleRate) *
                    (safe.volumePercent / 100.0) * 0.80 * envelope)
            }
        }
        fun silence(seconds: Double) { repeat((seconds * sampleRate).toInt().coerceAtLeast(0)) { writeSample(0.0) } }
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
            if (repeatIndex + 1 < repeats) {
                val autoDelay = text.length * 60.0 / (5.0 * safe.effectiveWpm) * 1.5
                silence(if (safe.repeatDelayMode == RepeatDelayMode.CUSTOM) safe.repeatDelayMillis / 1_000.0 else autoDelay)
            }
        }
        val pcm = samples.toByteArray()
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(pcm.size, AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        active = track
        track.setVolume(1.0f)
        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written <= 0) {
            active = null
            track.release()
            return@withContext
        }
        track.play()
        val durationMillis = (written / 2L) * 1_000L / sampleRate
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
    private val prefs = context.getSharedPreferences("rigweave-m32", Context.MODE_PRIVATE)
    private val usbTransport = UsbRadioTransport(context.applicationContext, "rigweave-m32-usb")
    private val bleTransport = M32BleSerialTransport(context.applicationContext)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var previousSerialOutput: Int? = null
    var devices by mutableStateOf(emptyList<SerialDeviceDescriptor>()); private set
    var selectedSessionKey by mutableStateOf<String?>(null); private set
    var bleDevices by mutableStateOf(emptyList<M32BleDevice>()); private set
    var selectedBleAddress by mutableStateOf(prefs.getString("ble_address", null)); private set
    var selectedRoute by mutableStateOf(runCatching {
        M32ConnectionRoute.valueOf(prefs.getString("route", M32ConnectionRoute.BLE.name).orEmpty())
    }.getOrDefault(M32ConnectionRoute.BLE)); private set
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
        prefs.edit { putString("route", selectedRoute.name) }
        detail = "Searching for Morserino-32…"
        runCatching { bleTransport.scan() }
            .onSuccess { found ->
                val remembered = selectedBleAddress?.let { address ->
                    found.firstOrNull { it.address == address } ?: M32BleDevice(address, prefs.getString("ble_name", "Morserino-32").orEmpty())
                }
                bleDevices = (found + listOfNotNull(remembered)).distinctBy(M32BleDevice::address)
                if (found.size == 1) selectBle(found.single().address)
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
        prefs.edit { putString("route", selectedRoute.name) }
    }

    fun selectBle(address: String) {
        selectedBleAddress = address
        selectedRoute = M32ConnectionRoute.BLE
        val name = bleDevices.firstOrNull { it.address == address }?.name ?: "Morserino-32"
        prefs.edit { putString("route", selectedRoute.name); putString("ble_address", address); putString("ble_name", name) }
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
        if (selectedBleAddress == null && bleDevices.isEmpty()) scanBle()
        val address = selectedBleAddress ?: bleDevices.singleOrNull()?.address
        if (address == null) {
            state = M32ConnectionState.ERROR
            detail = "Find and select Morserino-32 first."
            return
        }
        detail = "Connecting to M32 BLE Serial…"
        runCatching {
            var lastFailure: Throwable? = null
            repeat(2) { attempt ->
                val result = runCatching { bleTransport.connect(address) }
                if (result.isSuccess) return@runCatching
                lastFailure = result.exceptionOrNull()
                bleTransport.disconnect()
                if (attempt == 0) { detail = "M32 did not answer; retrying BLE Serial…"; delay(700) }
            }
            throw lastFailure ?: IllegalStateException("M32 BLE connection failed.")
        }
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
            val serialOutput = command("get config/Serial Output")
            val serialOutputValue = Regex("\"value\"\\s*:\\s*(\\d+)").find(serialOutput)?.groupValues?.get(1)?.toIntOrNull()
            previousSerialOutput = serialOutputValue?.takeUnless { it in setOf(1, 3, 5) }
            if (previousSerialOutput != null) command("put config/Serial Output/1")
            // CW Keyer is protocol-stable menu 1. Avoid transferring the complete
            // multi-kilobyte menu catalogue over 20-byte BLE notifications.
            command("put menu/start now/1")
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
        previousSerialOutput?.let { value -> runCatching { command("put config/Serial Output/$value", 500) } }
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
        val effectiveTimeout = if (selectedRoute == M32ConnectionRoute.BLE) maxOf(timeout, 3_000) else timeout
        val response = try {
            if (selectedRoute == M32ConnectionRoute.BLE) {
                bleTransport.exchange(value, effectiveTimeout.toLong()).toString(Charsets.UTF_8)
            } else usbTransport.rawExchange("$value\n".toByteArray(Charsets.UTF_8), effectiveTimeout).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("$value failed: ${error.message ?: error::class.simpleName}", error)
        }
        val error = extractJsonObjects(response).firstOrNull { "\"error\"" in it }
        check(error == null) { "$value failed: ${error ?: "M32 command failed."}" }
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
