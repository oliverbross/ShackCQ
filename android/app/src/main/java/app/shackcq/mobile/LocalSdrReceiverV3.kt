// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

enum class LocalReceiverMode {
    USB, LSB, CW, DIGU, DIGL, DSB, AM, SAM, NFM, WFM, SPECTRUM;

    fun supported(sampleRate: Int): Boolean = sampleRate in 48_000..384_000 && (this != WFM || sampleRate >= 192_000)
}

enum class LocalSamState { ACQUIRING, LOCKED, FALLBACK }

data class LocalReceiverPreferences(
    val mode: LocalReceiverMode = LocalReceiverMode.USB,
    val filterLowHz: Int = 300,
    val filterHighHz: Int = 2_700,
    val cwPitchHz: Int = 600,
    val agc: Boolean = true,
    val agcHangMillis: Int = 250,
    val squelchDb: Float = -105f,
    val noiseBlanker: Boolean = true,
    val automaticNotch: Boolean = false,
    val noiseReduction: Float = .28f,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val fmDeemphasisUs: Int = 75,
) {
    fun validated() = copy(filterLowHz = filterLowHz.coerceIn(0, 40_000),
        filterHighHz = filterHighHz.coerceIn(filterLowHz + 50, 95_000), cwPitchHz = cwPitchHz.coerceIn(100, 2_000),
        agcHangMillis = agcHangMillis.coerceIn(0, 2_000), squelchDb = squelchDb.coerceIn(-120f, -20f),
        noiseReduction = noiseReduction.coerceIn(0f, 1f), gain = gain.coerceIn(0f, 2f), pan = pan.coerceIn(-1f, 1f),
        fmDeemphasisUs = if (fmDeemphasisUs == 50) 50 else 75)
}

data class LocalReceiverState(
    val id: String,
    val label: String,
    val sourceId: String,
    val sourceReceiver: Int,
    val sourceCenterHz: Long,
    val sourceSampleRate: Int,
    val enabled: Boolean = true,
    val listening: Boolean = false,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val relativeOffsetHz: Int = 0,
    val preferences: LocalReceiverPreferences = LocalReceiverPreferences(),
    val signalDb: Float = -120f,
    val samState: LocalSamState = LocalSamState.ACQUIRING,
    val samErrorHz: Float = 0f,
    val ctcssHz: Float = 0f,
    val ctcssConfidence: Float = 0f,
    val dcsCode: Int = 0,
    val dcsInverted: Boolean = false,
    val dcsConfidence: Float = 0f,
    val wfmPilot: Float = 0f,
    val stereoSeparationDb: Float = 0f,
    val rdsPi: Int = 0,
    val rdsPty: Int = 0,
    val rdsTp: Boolean = false,
    val rdsTa: Boolean = false,
    val rdsClock: String = "",
    val rdsErrorRate: Float = 1f,
    val rdsState: String = "NO RDS",
    val rdsPs: String = "",
    val rdsText: String = "",
    val rdsAfKhz: List<Int> = emptyList(),
    val recording: Boolean = false,
    val sourceAgeMillis: Long = Long.MAX_VALUE,
    val droppedFrames: Long = 0,
    val processingMillis: Float = 0f,
    val lastError: String = "",
) {
    val frequencyHz: Long get() = sourceCenterHz + relativeOffsetHz
    val inSpan: Boolean get() = sourceSampleRate > 0 && kotlin.math.abs(relativeOffsetHz) <= sourceSampleRate * .46f
    val toneState: String get() = when {
        dcsCode > 0 && dcsConfidence >= .55f -> "DCS %03d%s".format(dcsCode, if (dcsInverted) "I" else "N")
        ctcssHz > 0f && ctcssConfidence >= .45f -> "CTCSS %.1f Hz".format(ctcssHz)
        preferences.mode == LocalReceiverMode.NFM -> "CTCSS/DCS SEARCHING"
        else -> "NO TONE"
    }
}

data class LocalSdrSnapshot(
    val receivers: List<LocalReceiverState> = emptyList(),
    val source: String = "NO I/Q SOURCE",
    val inputRate: Int = 0,
    val outputRate: Int = 48_000,
    val queueDepth: Int = 0,
    val droppedBlocks: Long = 0,
    val recordingState: String = "STOPPED",
    val recordingBytes: Long = 0,
    val recordingDurationMillis: Long = 0,
    val status: String = "Local receivers stopped",
)

data class ReceiverRecording(
    val id: String,
    val receiverId: String,
    val frequencyHz: Long,
    val mode: String,
    val startedUtc: Long,
    val durationMillis: Long,
    val bytes: Long,
    val displayName: String,
)

internal fun localReceiverModeDefaults(mode: LocalReceiverMode): LocalReceiverPreferences = when (mode) {
    LocalReceiverMode.USB, LocalReceiverMode.LSB -> LocalReceiverPreferences(mode, 300, 2_700)
    LocalReceiverMode.DIGU, LocalReceiverMode.DIGL -> LocalReceiverPreferences(mode, 200, 3_000)
    LocalReceiverMode.CW -> LocalReceiverPreferences(mode, 500, 700, cwPitchHz = 600)
    LocalReceiverMode.DSB -> LocalReceiverPreferences(mode, 50, 3_300)
    LocalReceiverMode.AM, LocalReceiverMode.SAM -> LocalReceiverPreferences(mode, 50, 6_000)
    LocalReceiverMode.NFM -> LocalReceiverPreferences(mode, 0, 12_500, squelchDb = -95f)
    LocalReceiverMode.WFM -> LocalReceiverPreferences(mode, 0, 95_000, squelchDb = -100f)
    LocalReceiverMode.SPECTRUM -> LocalReceiverPreferences(mode, 0, 3_000, gain = 0f)
}

internal class ReceiverRecordingStore(context: Context) : SQLiteOpenHelper(context, "shackcq-local-sdr-v3.db", null, 1) {
    private val root = File(context.filesDir, "local-receiver-recordings").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receiver_recording(id TEXT PRIMARY KEY,receiver_id TEXT NOT NULL,source TEXT NOT NULL,
            frequency_hz INTEGER NOT NULL,mode TEXT NOT NULL,filter_low INTEGER NOT NULL,filter_high INTEGER NOT NULL,
            started_utc INTEGER NOT NULL,ended_utc INTEGER NOT NULL,sample_rate INTEGER NOT NULL,tone TEXT NOT NULL,
            rds TEXT NOT NULL,note TEXT NOT NULL,wav_name TEXT NOT NULL,json_name TEXT NOT NULL,bytes INTEGER NOT NULL,
            complete INTEGER NOT NULL CHECK(complete IN(0,1)))""")
        db.execSQL("CREATE INDEX receiver_recording_time ON receiver_recording(started_utc DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) error("Unsupported local receiver recording schema $oldVersion to $newVersion")
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Future local receiver recording schema $oldVersion is not supported by $newVersion")

    fun recover() {
        root.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach(File::delete)
        writableDatabase.delete("receiver_recording", "complete=0", null)
        val owned = readableDatabase.rawQuery("SELECT wav_name,json_name FROM receiver_recording WHERE complete=1", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) { add(cursor.getString(0)); add(cursor.getString(1)) } }
        }
        root.listFiles()?.filter { it.name !in owned }?.forEach(File::delete)
    }

    fun file(name: String) = File(root, name.substringAfterLast('/'))

    fun finish(state: LocalReceiverState, id: String, started: Long, ended: Long, wav: File, json: File, bytes: Long, note: String) {
        val values = ContentValues().apply {
            put("id", id); put("receiver_id", state.id); put("source", state.sourceId.take(80)); put("frequency_hz", state.frequencyHz)
            put("mode", state.preferences.mode.name); put("filter_low", state.preferences.filterLowHz); put("filter_high", state.preferences.filterHighHz)
            put("started_utc", started); put("ended_utc", ended); put("sample_rate", 48_000); put("tone", state.toneState.take(80))
            put("rds", state.rdsState.take(120)); put("note", note.take(240)); put("wav_name", wav.name); put("json_name", json.name)
            put("bytes", bytes); put("complete", 1)
        }
        writableDatabase.insertOrThrow("receiver_recording", null, values)
    }

    fun rows(limit: Int = 100): List<ReceiverRecording> = readableDatabase.rawQuery(
        "SELECT id,receiver_id,frequency_hz,mode,started_utc,ended_utc-started_utc,bytes,wav_name FROM receiver_recording WHERE complete=1 ORDER BY started_utc DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 500).toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) add(ReceiverRecording(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3),
                cursor.getLong(4), cursor.getLong(5) * 1_000L, cursor.getLong(6), cursor.getString(7)))
        } }

    fun totalBytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM receiver_recording WHERE complete=1", null)
        .use { it.moveToFirst(); it.getLong(0) }

    fun enforceCap(maximumBytes: Long) {
        while (totalBytes() > maximumBytes) {
            val oldest = readableDatabase.rawQuery("SELECT id,wav_name,json_name FROM receiver_recording WHERE complete=1 ORDER BY started_utc LIMIT 1", null)
                .use { if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), it.getString(2)) else null } ?: break
            file(oldest[1]).delete(); file(oldest[2]).delete(); writableDatabase.delete("receiver_recording", "id=?", arrayOf(oldest[0]))
        }
    }

    fun delete(id: String): Boolean {
        val files = readableDatabase.rawQuery("SELECT wav_name,json_name FROM receiver_recording WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) listOf(it.getString(0), it.getString(1)) else null
        } ?: return false
        files.forEach { file(it).delete() }
        return writableDatabase.delete("receiver_recording", "id=?", arrayOf(id)) == 1
    }

    fun wav(id: String): File? = readableDatabase.rawQuery(
        "SELECT wav_name FROM receiver_recording WHERE id=? AND complete=1", arrayOf(id)).use {
        if (it.moveToFirst()) file(it.getString(0)).takeIf(File::isFile) else null
    }
}

private class WavSession(
    private val store: ReceiverRecordingStore,
    val state: LocalReceiverState,
    val id: String,
    val started: Long,
    private val preRollSeconds: Int,
    private val note: String,
) {
    private val partial = store.file("$id.wav.partial")
    private val output = RandomAccessFile(partial, "rw")
    var frames = 0L; private set
    var bytes = 44L; private set

    init { output.write(ByteArray(44)) }

    @Synchronized fun append(samples: FloatArray, channels: Int) {
        if (frames >= 48_000L * 60L * 30L || bytes >= 250L * 1024L * 1024L) return
        val mono = if (channels <= 1) samples else FloatArray(samples.size / channels) { index ->
            (samples[index * channels] + samples[index * channels + 1]) * .5f
        }
        val allowed = minOf(mono.size, (48_000L * 60L * 30L - frames).toInt(), ((250L * 1024L * 1024L - bytes) / 2L).toInt())
        val encoded = ByteBuffer.allocate(allowed * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(allowed) { encoded.putShort((mono[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        output.write(encoded.array()); frames += allowed; bytes += allowed * 2L
    }

    @Synchronized fun finish(): ReceiverRecording {
        val ended = Instant.now().epochSecond
        output.seek(0); output.write(wavHeader((bytes - 44L).toInt(), 48_000, 1)); output.fd.sync(); output.close()
        val wav = store.file("$id.wav"); check(partial.renameTo(wav)) { "WAV atomic finalisation failed" }
        val sidecar = store.file("$id.json")
        sidecar.writeText(JSONObject().put("id", id).put("receiver", state.id).put("source", state.sourceId)
            .put("frequency_hz", state.frequencyHz).put("mode", state.preferences.mode.name)
            .put("filter_low_hz", state.preferences.filterLowHz).put("filter_high_hz", state.preferences.filterHighHz)
            .put("started_utc", started).put("ended_utc", ended).put("sample_rate", 48_000)
            .put("tone", state.toneState).put("rds", state.rdsState).put("pre_roll_seconds", preRollSeconds)
            .put("operator_note", note.take(240)).toString(2))
        store.finish(state, id, started, ended, wav, sidecar, bytes, note)
        return ReceiverRecording(id, state.id, state.frequencyHz, state.preferences.mode.name, started,
            (ended - started) * 1_000L, bytes, wav.name)
    }

    @Synchronized fun abort() { runCatching { output.close() }; partial.delete() }
}

internal fun wavHeader(dataBytes: Int, sampleRate: Int, channels: Int): ByteArray = ByteBuffer.allocate(44)
    .order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1.toShort())
        putShort(channels.toShort()); putInt(sampleRate); putInt(sampleRate * channels * 2); putShort((channels * 2).toShort())
        putShort(16.toShort()); put("data".toByteArray()); putInt(dataBytes)
    }.array()

class LocalReceiverController(
    context: Context,
    private val audio: TciRxAudioController,
    private val timeShift: ReceiveTimeShiftController,
) : AutoCloseable {
    private data class IqFrame(val source: String, val receiver: Int, val centerHz: Long, val rate: Int, val samples: FloatArray, val captured: Long)
    private data class Slot(val id: String, val handle: NativeHandleOwner, var configuredKey: String = "")

    private val prefs = context.getSharedPreferences("shackcq-local-sdr-v3", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<IqFrame>(8)
    private val active = AtomicBoolean(true)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ShackCQ-Local-SDR").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "ShackCQ-Local-SDR-Timers").apply { isDaemon = true } }
    private val slots = listOf("local:A", "local:B").map { id -> Slot(id, NativeHandleOwner(NativeLocalReceiver.create(), NativeLocalReceiver::destroy)) }
    private val recordingStore = ReceiverRecordingStore(context)
    private var recording: WavSession? = null
    private var dropped = 0L
    private var lastSource = "NO I/Q SOURCE"
    private var lastRate = 0
    private val savedPreferences = loadPreferenceMap().toMutableMap()
    private var latestReceivers = emptyList<LocalReceiverState>()

    var snapshot by mutableStateOf(LocalSdrSnapshot()); private set
    var recordings by mutableStateOf<List<ReceiverRecording>>(emptyList()); private set
    var maximumRecordingBytes by mutableStateOf(prefs.getLong("recording_cap", 250L * 1024L * 1024L).coerceIn(16L * 1024L * 1024L, 250L * 1024L * 1024L)); private set
    var preRollSeconds by mutableStateOf(prefs.getInt("pre_roll", 5).coerceIn(0, 120)); private set

    init {
        recordingStore.recover(); recordings = recordingStore.rows(); worker.execute(::loop)
    }

    fun add(source: String, sourceReceiver: Int, centerHz: Long, sampleRate: Int): Boolean {
        if (sampleRate !in 48_000..384_000 || centerHz <= 0 || latestReceivers.size >= 2) return false
        val slot = slots.first { candidate -> latestReceivers.none { it.id == candidate.id } }
        val defaults = loadModeDefaults(LocalReceiverMode.USB)
        latestReceivers = latestReceivers + LocalReceiverState(slot.id, if (slot.id.endsWith("A")) "RX A" else "RX B",
            source.take(80), sourceReceiver, centerHz, sampleRate, preferences = defaults)
        publish("${slot.id.substringAfter(':')} added · listen remains OFF")
        return true
    }

    fun remove(id: String) {
        if (recording?.state?.id == id) stopRecording("Receiver removed")
        latestReceivers = latestReceivers.filterNot { it.id == id }; slots.firstOrNull { it.id == id }?.configuredKey = ""
        syncMixer(); if (latestReceivers.none { it.listening }) audio.stop("Local receiver audio stopped")
        publish("${id.substringAfter(':')} removed")
    }

    fun update(id: String, transform: (LocalReceiverState) -> LocalReceiverState) {
        latestReceivers = latestReceivers.map { if (it.id == id) validate(transform(it)) else it }
        persistPreferences(); syncMixer(); publish("Local receiver updated")
    }

    fun setMode(id: String, mode: LocalReceiverMode) = update(id) { state ->
        if (!mode.supported(state.sourceSampleRate)) state.copy(lastError = "$mode UNAVAILABLE · SOURCE BANDWIDTH TOO NARROW")
        else state.copy(preferences = loadModeDefaults(mode), lastError = "")
    }

    fun move(id: String, offsetHz: Int): Boolean {
        val state = latestReceivers.firstOrNull { it.id == id } ?: return false
        if (kotlin.math.abs(offsetHz) > state.sourceSampleRate * .46f) {
            update(id) { it.copy(lastError = "OUTSIDE CURRENT I/Q SPAN · PHYSICAL RECEIVE REVIEW REQUIRED") }
            return false
        }
        update(id) { it.copy(relativeOffsetHz = offsetHz, lastError = "") }
        return true
    }

    fun listen(id: String, enabled: Boolean): Boolean {
        if (enabled && !audio.running && !audio.start(0, 48_000)) { update(id) { it.copy(lastError = audio.status) }; return false }
        update(id) { it.copy(listening = enabled) }
        if (latestReceivers.none { it.listening }) audio.stop("Local receiver audio stopped")
        return true
    }

    fun pushIq(source: String, sourceReceiver: Int, centerHz: Long, sampleRate: Int, samples: FloatArray) {
        if (!active.get() || samples.isEmpty() || samples.size % 2 != 0 || sampleRate !in 48_000..384_000) return
        val frame = IqFrame(source.take(80), sourceReceiver, centerHz, sampleRate, samples.copyOf(), System.currentTimeMillis())
        if (!queue.offer(frame)) { queue.poll(); queue.offer(frame); dropped++ }
    }

    fun startRecording(id: String, note: String = "", scanner: Boolean = false): Boolean {
        if (recording != null) { publish("Recording unavailable · one recording already active"); return false }
        val state = latestReceivers.firstOrNull { it.id == id && it.listening && it.preferences.mode != LocalReceiverMode.SPECTRUM } ?: run {
            publish("Recording requires an explicitly listening audio receiver"); return false
        }
        val session = WavSession(recordingStore, state, UUID.randomUUID().toString(), Instant.now().epochSecond, preRollSeconds, note)
        val preRoll = timeShift.audioPreRoll(if (state.id.endsWith("A")) 0 else 1, preRollSeconds)
        if (preRoll.isNotEmpty()) session.append(preRoll, 1)
        recording = session
        latestReceivers = latestReceivers.map { if (it.id == id) it.copy(recording = true) else it }
        publish(if (scanner) "RECORDING · SCANNER HIT · PRE-ROLL ${preRoll.size / 48_000}s" else "RECORDING · EXPLICIT · PRE-ROLL ${preRoll.size / 48_000}s")
        return true
    }

    fun stopRecording(reason: String = "Operator stopped recording"): ReceiverRecording? {
        val session = recording ?: return null
        recording = null
        return runCatching { session.finish() }.onFailure { session.abort() }.getOrNull().also { saved ->
            latestReceivers = latestReceivers.map { it.copy(recording = false) }
            recordingStore.enforceCap(maximumRecordingBytes); recordings = recordingStore.rows()
            publish(if (saved != null) "SAVED · ${saved.displayName} · $reason" else "RECORDING FINALISATION FAILED")
        }
    }

    fun scannerRecordOnHit(bank: ScanBank, frequencyHz: Long): String? {
        if (bank.recordOnHit != RecordOnHitMode.AUDIO) return null
        val target = latestReceivers.minByOrNull { kotlin.math.abs(it.frequencyHz - frequencyHz) }
            ?.takeIf { kotlin.math.abs(it.frequencyHz - frequencyHz) <= maxOf(2_000L, it.preferences.filterHighHz.toLong()) }
            ?: return null
        if (!target.listening || !startRecording(target.id, "Scanner · ${bank.name}", scanner = true)) return null
        val duration = (preRollSeconds + 10).coerceAtMost(30)
        scheduler.schedule({ stopRecording("Scanner post-roll complete") }, duration.toLong(), TimeUnit.SECONDS)
        return recording?.id
    }

    fun updateRecordingPolicy(preRoll: Int, maximumBytes: Long) {
        preRollSeconds = preRoll.coerceIn(0, 120)
        maximumRecordingBytes = maximumBytes.coerceIn(16L * 1024L * 1024L, 250L * 1024L * 1024L)
        prefs.edit().putInt("pre_roll", preRollSeconds).putLong("recording_cap", maximumRecordingBytes).apply()
        recordingStore.enforceCap(maximumRecordingBytes); recordings = recordingStore.rows()
    }

    fun deleteRecording(id: String): Boolean = recordingStore.delete(id).also { if (it) recordings = recordingStore.rows() }
    fun recordingFile(id: String): File? = recordingStore.wav(id)

    fun debugRds(id: String, ps: String = "DEMO FM ") {
        val slot = slots.firstOrNull { it.id == id } ?: return
        val text = ps.padEnd(8).take(8)
        repeat(4) { segment -> slot.handle.withHandle { NativeLocalReceiver.debugRdsGroup(it, 0xC0DE, segment,
            0x0102, (text[segment * 2].code shl 8) or text[segment * 2 + 1].code) } }
    }

    fun stopActive(reason: String) {
        queue.clear(); stopRecording(reason); latestReceivers = latestReceivers.map { it.copy(listening = false, recording = false) }
        audio.stop(reason); publish(reason)
    }

    private fun loop() {
        while (active.get()) {
            val frame = try { queue.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return } ?: continue
            val receivers = latestReceivers.filter { it.enabled && it.sourceReceiver == frame.receiver &&
                (it.sourceId == frame.source || frame.source == "REPLAY") }
            receivers.forEach { state -> process(state, frame) }
        }
    }

    private fun process(state: LocalReceiverState, frame: IqFrame) {
        val slot = slots.first { it.id == state.id }
        val preferences = state.preferences.validated()
        val key = listOf(frame.rate, preferences.mode, state.relativeOffsetHz, preferences.filterLowHz, preferences.filterHighHz,
            preferences.cwPitchHz, preferences.squelchDb, preferences.fmDeemphasisUs).joinToString("|")
        if (slot.configuredKey != key) {
            val configured = slot.handle.withHandle { NativeLocalReceiver.configure(it, frame.rate, preferences.mode.ordinal,
                state.relativeOffsetHz.toFloat(), preferences.filterLowHz.toFloat(), preferences.filterHighHz.toFloat(),
                preferences.cwPitchHz.toFloat(), preferences.squelchDb, preferences.fmDeemphasisUs) } == true
            if (!configured) { updateRuntime(state.id) { it.copy(lastError = "SOURCE OR MODE CAPABILITY REJECTED") }; return }
            slot.configuredKey = key
        }
        val output = slot.handle.withHandle { NativeLocalReceiver.process(it, frame.samples) } ?: return
        if (output.size < NativeLocalReceiver.HEADER_SIZE) { updateRuntime(state.id) { it.copy(lastError = "LOCAL DSP OUTPUT INVALID") }; return }
        val channels = output[0].toInt().coerceIn(1, 2)
        val pcm = output.copyOfRange(NativeLocalReceiver.HEADER_SIZE, output.size)
        val metadata = if (output[16] > .5f) slot.handle.withHandle { JSONObject(NativeLocalReceiver.metadata(it)) } else null
        val receiverIndex = if (state.id.endsWith("A")) 0 else 1
        if (state.listening && pcm.isNotEmpty()) audio.pushLocal(receiverIndex, 48_000, channels, pcm, RxDspSettings(
            noiseBlanker = preferences.noiseBlanker, automaticNotch = preferences.automaticNotch,
            noiseReduction = preferences.noiseReduction, agc = preferences.agc, agcHangMillis = preferences.agcHangMillis,
            squelchDb = preferences.squelchDb, outputGain = preferences.gain,
        ))
        if (pcm.isNotEmpty()) {
            val mono = if (channels == 1) pcm else FloatArray(pcm.size / channels) { (pcm[it * channels] + pcm[it * channels + 1]) * .5f }
            timeShift.captureAudio(receiverIndex, mono)
            recording?.takeIf { it.state.id == state.id }?.append(pcm, channels)
        }
        lastSource = frame.source; lastRate = frame.rate
        updateRuntime(state.id) { current -> current.copy(sourceCenterHz = frame.centerHz, sourceSampleRate = frame.rate,
            signalDb = output[1], samState = LocalSamState.entries.getOrElse(output[4].toInt()) { LocalSamState.ACQUIRING }, samErrorHz = output[5],
            ctcssHz = output[6], ctcssConfidence = output[7], dcsCode = output[8].toInt(), dcsInverted = output[9] > .5f,
            dcsConfidence = output[10], wfmPilot = output[11], stereoSeparationDb = output[12], rdsPi = output[13].toInt(),
            rdsPty = output[14].toInt(), rdsTp = metadata?.optBoolean("tp") == true,
            rdsTa = metadata?.optBoolean("ta") == true, rdsClock = metadata?.optString("clock").orEmpty(),
            rdsErrorRate = output[15], rdsState = when {
                preferences.mode != LocalReceiverMode.WFM -> "NO RDS"
                output[16] > .5f && output[15] < .2f -> "RDS ${metadata?.optString("ps")?.ifBlank { "%04X".format(output[13].toInt()) }}"
                output[11] > .08f -> "RDS ACQUIRING"
                else -> "NO RDS"
            }, rdsPs = metadata?.optString("ps").orEmpty(), rdsText = metadata?.optString("text").orEmpty(),
            rdsAfKhz = metadata?.optJSONArray("af_khz")?.let { rows -> List(rows.length().coerceAtMost(25)) { rows.optInt(it) } }.orEmpty(),
            sourceAgeMillis = System.currentTimeMillis() - frame.captured, droppedFrames = dropped,
            processingMillis = output[17], lastError = "") }
    }

    private fun validate(state: LocalReceiverState): LocalReceiverState = state.copy(relativeOffsetHz = state.relativeOffsetHz.coerceIn(
        (-state.sourceSampleRate * .46f).toInt(), (state.sourceSampleRate * .46f).toInt()), preferences = state.preferences.validated())

    private fun updateRuntime(id: String, transform: (LocalReceiverState) -> LocalReceiverState) = main.post {
        latestReceivers = latestReceivers.map { if (it.id == id) transform(it) else it }; publishOnMain("LOCAL DSP LIVE")
    }

    private fun publish(status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) publishOnMain(status) else main.post { publishOnMain(status) }
    }

    private fun publishOnMain(status: String) {
        val session = recording
        snapshot = LocalSdrSnapshot(latestReceivers, lastSource, lastRate, 48_000, queue.size, dropped,
            if (session != null) "RECORDING" else "STOPPED", session?.bytes ?: 0L,
            session?.let { (Instant.now().epochSecond - it.started) * 1_000L } ?: 0L, status)
    }

    private fun syncMixer() {
        fun mix(id: String, fallbackPan: Float) = latestReceivers.firstOrNull { it.id == id }?.let {
            ReceiverMixSettings(it.preferences.gain, it.muted, it.solo, it.preferences.pan)
        } ?: ReceiverMixSettings(muted = true, pan = fallbackPan)
        val listeningA = latestReceivers.any { it.id == "local:A" && it.listening }
        val listeningB = latestReceivers.any { it.id == "local:B" && it.listening }
        val mode = when {
            listeningA && !listeningB -> RxMixerMode.RECEIVER_A
            listeningB && !listeningA -> RxMixerMode.RECEIVER_B
            else -> audio.mixer.mode
        }
        audio.updateMixer(audio.mixer.copy(mode = mode, receiverA = mix("local:A", -1f), receiverB = mix("local:B", 1f)))
    }

    private fun persistPreferences() {
        latestReceivers.forEach { savedPreferences[it.preferences.mode] = it.preferences }
        val rows = JSONArray(savedPreferences.values.map { value -> JSONObject().put("mode", value.mode.name)
            .put("low", value.filterLowHz).put("high", value.filterHighHz).put("pitch", value.cwPitchHz)
            .put("agc", value.agc).put("hang", value.agcHangMillis).put("squelch", value.squelchDb.toDouble())
            .put("nb", value.noiseBlanker).put("notch", value.automaticNotch).put("nr", value.noiseReduction.toDouble())
            .put("gain", value.gain.toDouble()).put("pan", value.pan.toDouble()).put("deemphasis", value.fmDeemphasisUs) })
        prefs.edit().putString("safe_preferences", rows.toString()).apply()
    }

    private fun loadModeDefaults(mode: LocalReceiverMode): LocalReceiverPreferences = savedPreferences[mode] ?: localReceiverModeDefaults(mode)

    private fun loadPreferenceMap(): Map<LocalReceiverMode, LocalReceiverPreferences> = runCatching {
        val rows = JSONArray(prefs.getString("safe_preferences", "[]"))
        List(rows.length().coerceAtMost(LocalReceiverMode.entries.size)) { index -> rows.getJSONObject(index).let { row ->
            val mode = runCatching { LocalReceiverMode.valueOf(row.optString("mode")) }.getOrDefault(LocalReceiverMode.USB)
            mode to LocalReceiverPreferences(mode,
                row.optInt("low", localReceiverModeDefaults(mode).filterLowHz), row.optInt("high", localReceiverModeDefaults(mode).filterHighHz),
                row.optInt("pitch", 600), row.optBoolean("agc", true), row.optInt("hang", 250), row.optDouble("squelch", -105.0).toFloat(),
                row.optBoolean("nb", true), row.optBoolean("notch"), row.optDouble("nr", .28).toFloat(), row.optDouble("gain", 1.0).toFloat(),
                row.optDouble("pan", if (index == 0) -1.0 else 1.0).toFloat(), row.optInt("deemphasis", 75)).validated()
        } }.toMap()
    }.getOrDefault(emptyMap())

    override fun close() {
        active.set(false); worker.shutdownNow(); scheduler.shutdownNow(); stopActive("Local receiver closed")
        slots.forEach { it.handle.close() }; recordingStore.close()
    }
}
