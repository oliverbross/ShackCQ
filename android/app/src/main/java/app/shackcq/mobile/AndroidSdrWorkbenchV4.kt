// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

enum class IqCaptureState { STOPPED, RECORDING, FINALIZING, ERROR }
enum class IqReplayState { STOPPED, PLAYING, PAUSED, COMPLETE, ERROR }
enum class CalibrationTruth { UNCALIBRATED, RELATIVE, CALIBRATED_BY_USER }
enum class ScannerIntelligenceOrder { FREQUENCY, MEMORY_ORDER, MOST_ACTIVE, RECENT_ACTIVITY, LEAST_RECENTLY_CHECKED }
enum class AdaptiveDwellMode { OFF, CONSERVATIVE }
enum class ChannelActivityState { HOT, RECENT, QUIET, UNKNOWN }
enum class BandStackCycleDirection { FORWARD, REVERSE }

data class IqCaptureMetadata(
    val id: String,
    val createdEpochMillis: Long,
    val source: String,
    val receiver: Int,
    val centerFrequencyHz: Long,
    val sampleRate: Int,
    val profile: String,
    val band: String,
    val context: String,
    val note: String,
    val formatVersion: Int = 1,
    val complexFrames: Long = 0,
) {
    val durationMillis: Long get() = if (sampleRate > 0) complexFrames * 1_000L / sampleRate else 0
}

data class IqCaptureRow(val metadata: IqCaptureMetadata, val dataBytes: Long)

data class IqCaptureSnapshot(
    val state: IqCaptureState = IqCaptureState.STOPPED,
    val activeId: String? = null,
    val bytes: Long = 0,
    val durationMillis: Long = 0,
    val status: String = "Explicit recording required",
)

data class IqReplaySnapshot(
    val state: IqReplayState = IqReplayState.STOPPED,
    val captureId: String? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val speed: Float = 1f,
    val audioTruthful: Boolean = true,
    val status: String = "Replay stopped",
)

private data class ActiveIqCapture(
    val metadata: IqCaptureMetadata,
    val temporaryData: File,
    val finalData: File,
    val temporaryMetadata: File,
    val finalMetadata: File,
    val output: RandomAccessFile,
    var frames: Long = 0,
)

class IqCaptureRepository(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("shackcq-sdr-workbench-v4-capture", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "sdr/iq-captures").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private var active: ActiveIqCapture? = null
    var maximumFileSeconds by mutableStateOf(preferences.getInt("max_seconds", 600).coerceIn(1, 600)); private set
    var maximumTotalBytes by mutableStateOf(preferences.getLong("max_total_bytes", 2L * 1024 * 1024 * 1024)
        .coerceIn(16L * 1024 * 1024, 2L * 1024 * 1024 * 1024)); private set
    var snapshot by mutableStateOf(IqCaptureSnapshot()); private set
    var captures by mutableStateOf<List<IqCaptureRow>>(emptyList()); private set

    init {
        recoverIncomplete()
        refresh()
    }

    @Synchronized fun configure(maximumSeconds: Int, maximumBytes: Long) {
        maximumFileSeconds = maximumSeconds.coerceIn(1, 600)
        maximumTotalBytes = maximumBytes.coerceIn(16L * 1024 * 1024, 2L * 1024 * 1024 * 1024)
        preferences.edit().putInt("max_seconds", maximumFileSeconds).putLong("max_total_bytes", maximumTotalBytes).apply()
        enforceTotalCap()
    }

    @Synchronized fun start(source: String, receiver: Int, centerHz: Long, sampleRate: Int, profile: String,
        band: String, contextLabel: String, note: String = ""): Boolean {
        if (active != null || source.isBlank() || centerHz <= 0 || sampleRate !in 8_000..3_072_000) return false
        val id = UUID.randomUUID().toString()
        return runCatching {
            val temporaryData = File(directory, "$id.f32iq.tmp")
            val output = RandomAccessFile(temporaryData, "rw").apply { setLength(0) }
            active = ActiveIqCapture(IqCaptureMetadata(id, System.currentTimeMillis(), source.take(80), receiver.coerceIn(0, 7),
                centerHz, sampleRate, profile.take(80), band.take(24), contextLabel.take(120), note.take(240)), temporaryData,
                File(directory, "$id.f32iq"), File(directory, "$id.json.tmp"), File(directory, "$id.json"), output)
            snapshot = IqCaptureSnapshot(IqCaptureState.RECORDING, id, status = "RECORDING · EXPLICIT I/Q")
            true
        }.getOrElse {
            snapshot = IqCaptureSnapshot(IqCaptureState.ERROR, status = "I/Q recording failed: ${it.message}")
            false
        }
    }

    @Synchronized fun append(source: String, receiver: Int, sampleRate: Int, samples: FloatArray) {
        val session = active ?: return
        if (session.metadata.source != source || session.metadata.receiver != receiver || session.metadata.sampleRate != sampleRate ||
            samples.isEmpty() || samples.size % 2 != 0) return
        val maximumFrames = sampleRate.toLong() * maximumFileSeconds
        val frames = minOf(samples.size / 2L, maximumFrames - session.frames).coerceAtLeast(0).toInt()
        if (frames == 0) { stop("Maximum file duration reached"); return }
        runCatching {
            val bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * 2) { bytes.putFloat(samples[it]) }
            session.output.write(bytes.array())
            session.frames += frames
            snapshot = snapshot.copy(bytes = session.frames * 8L, durationMillis = session.frames * 1_000L / sampleRate)
            if (session.frames >= maximumFrames) stop("Maximum file duration reached")
        }.onFailure {
            abort("I/Q recording write failed: ${it.message}")
        }
    }

    @Synchronized fun stop(reason: String = "Operator stopped recording"): IqCaptureRow? {
        val session = active ?: return null
        active = null
        snapshot = snapshot.copy(state = IqCaptureState.FINALIZING, status = "Finalizing I/Q capture")
        return runCatching {
            session.output.fd.sync()
            session.output.close()
            val metadata = session.metadata.copy(complexFrames = session.frames)
            FileOutputStream(session.temporaryMetadata).use { output ->
                output.write(encodeMetadata(metadata).toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(session.temporaryData.renameTo(session.finalData)) { "Atomic I/Q data rename failed" }
            check(session.temporaryMetadata.renameTo(session.finalMetadata)) { "Atomic I/Q metadata rename failed" }
            val row = IqCaptureRow(metadata, session.finalData.length())
            snapshot = IqCaptureSnapshot(IqCaptureState.STOPPED, bytes = row.dataBytes,
                durationMillis = metadata.durationMillis, status = "SAVED · ${metadata.durationMillis / 1_000}s · $reason")
            enforceTotalCap()
            refresh()
            row
        }.getOrElse { error ->
            session.temporaryData.delete(); session.temporaryMetadata.delete(); session.finalData.delete(); session.finalMetadata.delete()
            snapshot = IqCaptureSnapshot(IqCaptureState.ERROR, status = "I/Q finalization failed: ${error.message}")
            null
        }
    }

    @Synchronized fun abort(reason: String) {
        val session = active ?: return
        active = null
        runCatching { session.output.close() }
        session.temporaryData.delete(); session.temporaryMetadata.delete()
        snapshot = IqCaptureSnapshot(IqCaptureState.ERROR, status = reason.take(180))
    }

    @Synchronized fun delete(id: String): Boolean {
        if (active?.metadata?.id == id) return false
        val data = dataFile(id); val metadata = metadataFile(id)
        val deleted = (!data.exists() || data.delete()) && (!metadata.exists() || metadata.delete())
        refresh()
        return deleted
    }

    internal fun dataFile(id: String) = File(directory, "${id.take(80)}.f32iq")
    internal fun row(id: String): IqCaptureRow? = captures.firstOrNull { it.metadata.id == id }

    private fun metadataFile(id: String) = File(directory, "${id.take(80)}.json")
    private fun recoverIncomplete() {
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach(File::delete)
        val dataIds = directory.listFiles()?.filter { it.extension == "f32iq" }?.associateBy(File::nameWithoutExtension).orEmpty()
        val metadataIds = directory.listFiles()?.filter { it.extension == "json" }?.associateBy(File::nameWithoutExtension).orEmpty()
        (dataIds.keys - metadataIds.keys).forEach { dataIds[it]?.delete() }
        (metadataIds.keys - dataIds.keys).forEach { metadataIds[it]?.delete() }
        (dataIds.keys intersect metadataIds.keys).forEach { id ->
            val valid = runCatching {
                val json = JSONObject(metadataIds.getValue(id).readText())
                val metadata = decodeMetadata(json)
                json.getString("format") == "SHACKCQ_FLOAT32_IQ_LE" && metadata.formatVersion == 1 &&
                    metadata.id == id && UUID.fromString(metadata.id).toString() == metadata.id &&
                    metadata.sampleRate in 8_000..3_072_000 && metadata.complexFrames >= 0 &&
                    dataIds.getValue(id).length() == metadata.complexFrames * 8L
            }.getOrDefault(false)
            if (!valid) { dataIds[id]?.delete(); metadataIds[id]?.delete() }
        }
    }

    private fun enforceTotalCap() {
        var total = directory.listFiles()?.filter { it.extension == "f32iq" }?.sumOf(File::length) ?: 0L
        if (total <= maximumTotalBytes) return
        loadRows().sortedBy { it.metadata.createdEpochMillis }.forEach { row ->
            if (total <= maximumTotalBytes) return@forEach
            if (deleteFiles(row.metadata.id)) total -= row.dataBytes
        }
    }

    private fun deleteFiles(id: String): Boolean {
        val data = dataFile(id); val metadata = metadataFile(id)
        return (!data.exists() || data.delete()) && (!metadata.exists() || metadata.delete())
    }

    private fun refresh() { captures = loadRows().sortedByDescending { it.metadata.createdEpochMillis } }

    private fun loadRows(): List<IqCaptureRow> = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
        runCatching {
            val metadata = decodeMetadata(JSONObject(file.readText()))
            val data = dataFile(metadata.id)
            data.takeIf(File::isFile)?.let { IqCaptureRow(metadata, it.length()) }
        }.getOrNull()
    }.orEmpty()

    private fun encodeMetadata(value: IqCaptureMetadata) = JSONObject()
        .put("format", "SHACKCQ_FLOAT32_IQ_LE").put("format_version", value.formatVersion)
        .put("id", value.id).put("utc_epoch_ms", value.createdEpochMillis).put("source", value.source)
        .put("receiver", value.receiver).put("center_frequency_hz", value.centerFrequencyHz)
        .put("sample_rate", value.sampleRate).put("profile", value.profile).put("band", value.band)
        .put("context", value.context).put("note", value.note).put("complex_frames", value.complexFrames)

    private fun decodeMetadata(row: JSONObject) = IqCaptureMetadata(row.getString("id"), row.getLong("utc_epoch_ms"),
        row.getString("source"), row.optInt("receiver"), row.getLong("center_frequency_hz"), row.getInt("sample_rate"),
        row.optString("profile"), row.optString("band"), row.optString("context"), row.optString("note"),
        row.optInt("format_version", 1), row.optLong("complex_frames"))

    override fun close() { if (active != null) abort("Application closed during I/Q recording") }
}

class ReplayIqSource(private val captures: IqCaptureRepository) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val requestedFrame = AtomicLong(-1)
    private val generation = AtomicLong(0)
    private var worker: Thread? = null
    @Volatile var sink: (String, Int, Long, Int, FloatArray) -> Unit = { _, _, _, _, _ -> }
    var snapshot by mutableStateOf(IqReplaySnapshot()); private set

    @Synchronized fun play(id: String, speed: Float = 1f): Boolean {
        val row = captures.row(id) ?: return false
        if (captures.snapshot.state == IqCaptureState.RECORDING) return false
        val selectedSpeed = listOf(.25f, .5f, 1f, 2f).minBy { abs(it - speed) }
        stop("Replay replaced")
        val session = generation.incrementAndGet()
        running.set(true); paused.set(false); requestedFrame.set(0)
        publish(IqReplaySnapshot(IqReplayState.PLAYING, id, durationMillis = row.metadata.durationMillis,
            speed = selectedSpeed, audioTruthful = selectedSpeed == 1f,
            status = if (selectedSpeed == 1f) "REPLAY · OFFLINE" else "REPLAY · AUDIO DISABLED AT REPLAY SPEED"))
        worker = Thread({ replayLoop(row, selectedSpeed, session) }, "ShackCQ-IQ-Replay-v4").apply { isDaemon = true; start() }
        return true
    }

    fun pause() {
        if (!running.get()) return
        val value = !paused.getAndSet(!paused.get())
        publish(snapshot.copy(state = if (value) IqReplayState.PAUSED else IqReplayState.PLAYING,
            status = if (value) "REPLAY PAUSED" else if (snapshot.audioTruthful) "REPLAY · OFFLINE" else "REPLAY · AUDIO DISABLED AT REPLAY SPEED"))
    }

    fun seek(positionMillis: Long) {
        val row = snapshot.captureId?.let(captures::row) ?: return
        requestedFrame.set((positionMillis.coerceIn(0, row.metadata.durationMillis) * row.metadata.sampleRate / 1_000L))
    }

    fun skip(deltaMillis: Long) = seek(snapshot.positionMillis + deltaMillis)

    @Synchronized fun stop(reason: String = "Replay stopped") {
        generation.incrementAndGet(); running.set(false); paused.set(false)
        val previous = worker
        worker = null
        previous?.interrupt()
        if (previous != null && previous !== Thread.currentThread()) runCatching { previous.join(250) }
        if (snapshot.state !in setOf(IqReplayState.STOPPED, IqReplayState.COMPLETE))
            publish(IqReplaySnapshot(IqReplayState.STOPPED, status = reason.take(120)))
    }

    private fun replayLoop(row: IqCaptureRow, speed: Float, session: Long) {
        runCatching {
            RandomAccessFile(captures.dataFile(row.metadata.id), "r").use { input ->
                val framesPerBlock = minOf(4_096, max(256, row.metadata.sampleRate / 25))
                val bytes = ByteArray(framesPerBlock * 8)
                while (running.get() && generation.get() == session) {
                    if (paused.get()) { Thread.sleep(20); continue }
                    val requested = requestedFrame.getAndSet(-1)
                    if (requested >= 0) input.seek((requested * 8L).coerceAtMost(input.length()))
                    val read = input.read(bytes)
                    if (read <= 0) break
                    val complete = read - read % 8
                    val floats = FloatArray(complete / 4)
                    ByteBuffer.wrap(bytes, 0, complete).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                    if (generation.get() != session) break
                    sink("REPLAY", row.metadata.receiver, row.metadata.centerFrequencyHz, row.metadata.sampleRate, floats)
                    val frames = complete / 8
                    val position = input.filePointer / 8 * 1_000L / row.metadata.sampleRate
                    publish(snapshot.copy(positionMillis = position.coerceAtMost(row.metadata.durationMillis)), session)
                    val delayNanos = (frames * 1_000_000_000.0 / row.metadata.sampleRate / speed).roundToLong()
                    if (delayNanos > 0) Thread.sleep(delayNanos / 1_000_000, (delayNanos % 1_000_000).toInt())
                }
            }
        }.onFailure { error ->
            if (running.get() && generation.get() == session)
                publish(snapshot.copy(state = IqReplayState.ERROR, status = "Replay failed: ${error.message}"), session)
        }
        if (generation.get() == session && running.getAndSet(false)) publish(snapshot.copy(state = IqReplayState.COMPLETE,
            positionMillis = snapshot.durationMillis, status = "REPLAY COMPLETE · RETURN LIVE"), session)
    }

    private fun publish(value: IqReplaySnapshot, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && generation.get() != expectedGeneration) return
        if (Looper.myLooper() == Looper.getMainLooper()) snapshot = value else main.post {
            if (expectedGeneration == null || generation.get() == expectedGeneration) snapshot = value
        }
    }

    override fun close() = stop("Replay source closed")
}

data class ReceiveCalibration(
    val source: String,
    val levelOffsetDb: Float = 0f,
    val frequencyCorrectionPpm: Float = 0f,
    val iqGainCorrection: Float = 1f,
    val iqPhaseCorrectionDegrees: Float = 0f,
    val truth: CalibrationTruth = CalibrationTruth.UNCALIBRATED,
    val reference: String = "",
) {
    fun validated() = copy(source = source.take(80), levelOffsetDb = levelOffsetDb.coerceIn(-100f, 100f),
        frequencyCorrectionPpm = frequencyCorrectionPpm.coerceIn(-250f, 250f), iqGainCorrection = iqGainCorrection.coerceIn(.5f, 1.5f),
        iqPhaseCorrectionDegrees = iqPhaseCorrectionDegrees.coerceIn(-30f, 30f), reference = reference.take(120))
}

class ReceiveCalibrationRepository(context: Context) {
    private val preferences = context.getSharedPreferences("shackcq-sdr-workbench-v4-calibration", Context.MODE_PRIVATE)
    private val phaseBySource = mutableMapOf<String, Double>()
    var rows by mutableStateOf(load()); private set

    fun calibration(source: String): ReceiveCalibration = rows[source] ?: ReceiveCalibration(source, truth = CalibrationTruth.RELATIVE)

    fun update(value: ReceiveCalibration) {
        val calibrated = value.validated()
        rows = rows + (calibrated.source to calibrated)
        persist()
    }

    fun guided(source: String, knownFrequencyHz: Long, observedFrequencyHz: Long, knownLevelDbm: Float?, measuredDbfs: Float,
        reference: String): ReceiveCalibration {
        require(knownFrequencyHz > 0 && observedFrequencyHz > 0)
        val ppm = ((observedFrequencyHz - knownFrequencyHz).toDouble() / knownFrequencyHz * 1_000_000.0).toFloat()
        val level = knownLevelDbm?.minus(measuredDbfs) ?: 0f
        return ReceiveCalibration(source, level, ppm, truth = if (knownLevelDbm == null) CalibrationTruth.RELATIVE else CalibrationTruth.CALIBRATED_BY_USER,
            reference = reference).validated().also(::update)
    }

    @Synchronized fun apply(source: String, centerHz: Long, sampleRate: Int, samples: FloatArray): FloatArray {
        val value = calibration(source)
        if (samples.isEmpty() || samples.size % 2 != 0 || sampleRate <= 0 || value == ReceiveCalibration(source, truth = CalibrationTruth.RELATIVE)) return samples
        val output = samples.copyOf()
        val phaseCorrection = value.iqPhaseCorrectionDegrees * PI / 180.0
        val phaseCos = cos(phaseCorrection); val phaseSin = sin(phaseCorrection)
        val frequencyRadians = -2.0 * PI * centerHz * value.frequencyCorrectionPpm / 1_000_000.0 / sampleRate
        var oscillator = phaseBySource[source] ?: 0.0
        var index = 0
        while (index + 1 < output.size) {
            val i = output[index].toDouble()
            val q = value.iqGainCorrection * (output[index + 1] * phaseCos - i * phaseSin)
            val c = cos(oscillator); val s = sin(oscillator)
            output[index] = (i * c - q * s).toFloat()
            output[index + 1] = (i * s + q * c).toFloat()
            oscillator += frequencyRadians
            if (oscillator > PI || oscillator < -PI) oscillator %= 2.0 * PI
            index += 2
        }
        phaseBySource[source] = oscillator
        return output
    }

    private fun load(): Map<String, ReceiveCalibration> = runCatching {
        val array = JSONArray(preferences.getString("rows", "[]"))
        List(array.length().coerceAtMost(64)) { index -> array.getJSONObject(index).let { row ->
            val value = ReceiveCalibration(row.getString("source"), row.optDouble("level").toFloat(), row.optDouble("ppm").toFloat(),
                row.optDouble("gain", 1.0).toFloat(), row.optDouble("phase").toFloat(),
                runCatching { CalibrationTruth.valueOf(row.optString("truth")) }.getOrDefault(CalibrationTruth.RELATIVE), row.optString("reference")).validated()
            value.source to value
        } }.toMap()
    }.getOrDefault(emptyMap())

    private fun persist() {
        val array = JSONArray(rows.values.map { row -> JSONObject().put("source", row.source).put("level", row.levelOffsetDb)
            .put("ppm", row.frequencyCorrectionPpm).put("gain", row.iqGainCorrection).put("phase", row.iqPhaseCorrectionDegrees)
            .put("truth", row.truth.name).put("reference", row.reference) })
        preferences.edit().putString("rows", array.toString()).apply()
    }
}

data class SignalMeasurement(
    val frequencyHz: Long,
    val level: Float,
    val noiseFloor: Float,
    val snr: Float,
    val occupiedBandwidthHz: Float,
    val bandwidth3DbHz: Float,
    val bandwidth6DbHz: Float,
    val bandwidth26DbHz: Float,
    val channelPower: Float,
    val adjacentChannel: Float,
    val centerOffsetHz: Long,
    val units: String,
)

data class SignalInspectorSnapshot(
    val markerAHz: Long? = null,
    val markerBHz: Long? = null,
    val markerA: SignalMeasurement? = null,
    val markerB: SignalMeasurement? = null,
    val deltaFrequencyHz: Long? = null,
    val deltaLevel: Float? = null,
    val status: String = "Set Marker A and Marker B",
)

data class SignalTrackSnapshot(
    val selected: Boolean = false,
    val frequencyHz: Long = 0,
    val startFrequencyHz: Long = 0,
    val driftHz: Long = 0,
    val level: Float = Float.NaN,
    val snr: Float = Float.NaN,
    val durationMillis: Long = 0,
    val localRxFollow: Boolean = false,
    val localReceiverId: String? = null,
    val status: String = "No signal selected",
)

data class ChannelMonitor(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val frequencyHz: Long,
    val mode: String = "NFM",
    val squelchDb: Float = -100f,
    val expectedCtcssHz: Float? = null,
    val expectedDcs: Int? = null,
    val level: Float = Float.NaN,
    val occupied: Boolean = false,
    val occupancyPercent: Float = 0f,
    val activityDurationMillis: Long = 0,
    val activity: ChannelActivityState = ChannelActivityState.UNKNOWN,
    val toneState: String = "UNDETECTED",
)

data class SpectrumAggregate(
    val bucketEpoch: Long,
    val band: String,
    val frequencyBucketHz: Long,
    val mode: String,
    val source: String,
    val receiver: Int,
    val occupancyPercent: Float,
    val medianLevel: Float,
    val peakLevel: Float,
    val medianNoiseFloor: Float,
    val signalCount: Long,
    val scannerHitCount: Long,
)

data class SpectrumSurveyStats(val rows: Long = 0, val bytes: Long = 0, val aggregationLatencyMillis: Long = 0)

data class SpectrumSurveyInput(
    val epochMillis: Long,
    val band: String,
    val frequencyHz: Long,
    val mode: String,
    val source: String,
    val receiver: Int,
    val level: Float,
    val noise: Float,
    val occupied: Boolean,
    val signalCount: Int = if (occupied) 1 else 0,
    val scannerHit: Boolean = false,
)

class SpectrumSurveyRepository(context: Context) : SQLiteOpenHelper(context, "shackcq-spectrum-survey.sqlite", null, 2) {
    private val preferences = context.getSharedPreferences("shackcq-spectrum-survey-v4", Context.MODE_PRIVATE)
    private val databaseFile = context.getDatabasePath("shackcq-spectrum-survey.sqlite")
    var retentionDays by mutableStateOf(preferences.getInt("retention_days", 30).let { when { it <= 7 -> 7; it <= 30 -> 30; else -> 90 } }); private set
    var timeBucketMinutes by mutableStateOf(preferences.getInt("bucket_minutes", 15).coerceIn(1, 60)); private set
    var frequencyBucketHz by mutableStateOf(1_000L); private set
    var maximumRows by mutableStateOf(preferences.getInt("maximum_rows", 250_000).coerceIn(10_000, 500_000)); private set
    var maximumBytes by mutableStateOf(preferences.getLong("maximum_bytes", 64L * 1024 * 1024)
        .coerceIn(8L * 1024 * 1024, 128L * 1024 * 1024)); private set
    var stats by mutableStateOf(SpectrumSurveyStats()); private set

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE spectrum_aggregate(bucket_epoch INTEGER NOT NULL, band TEXT NOT NULL, frequency_bucket_hz INTEGER NOT NULL, mode TEXT NOT NULL, source TEXT NOT NULL, receiver INTEGER NOT NULL, samples INTEGER NOT NULL, occupied_samples INTEGER NOT NULL, median_level REAL NOT NULL, peak_level REAL NOT NULL, median_noise REAL NOT NULL, signal_count INTEGER NOT NULL, scanner_hit_count INTEGER NOT NULL, PRIMARY KEY(bucket_epoch, band, frequency_bucket_hz, mode, source, receiver))")
        db.execSQL("CREATE INDEX spectrum_aggregate_time ON spectrum_aggregate(bucket_epoch DESC)")
        db.execSQL("CREATE INDEX spectrum_aggregate_frequency ON spectrum_aggregate(frequency_bucket_hz, bucket_epoch DESC)")
        db.execSQL("CREATE TABLE spectrum_survey_meta(schema_version INTEGER NOT NULL, created_epoch INTEGER NOT NULL)")
        db.execSQL("INSERT INTO spectrum_survey_meta VALUES(2, strftime('%s','now'))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("CREATE TABLE spectrum_survey_meta(schema_version INTEGER NOT NULL, created_epoch INTEGER NOT NULL)")
            db.execSQL("INSERT INTO spectrum_survey_meta VALUES(2, strftime('%s','now'))")
        } else throw SQLiteException("Unsupported Spectrum Survey migration $oldVersion->$newVersion")
    }

    fun configure(retention: Int, bucketMinutes: Int, rowCap: Int = maximumRows, byteCap: Long = maximumBytes) {
        retentionDays = when { retention <= 7 -> 7; retention <= 30 -> 30; else -> 90 }
        timeBucketMinutes = bucketMinutes.coerceIn(1, 60)
        maximumRows = rowCap.coerceIn(10_000, 500_000)
        maximumBytes = byteCap.coerceIn(8L * 1024 * 1024, 128L * 1024 * 1024)
        preferences.edit().putInt("retention_days", retentionDays).putInt("bucket_minutes", timeBucketMinutes)
            .putInt("maximum_rows", maximumRows).putLong("maximum_bytes", maximumBytes).apply()
        compact()
    }

    @Synchronized fun aggregate(epochMillis: Long, band: String, frequencyHz: Long, mode: String, source: String, receiver: Int,
        level: Float, noise: Float, occupied: Boolean, signalCount: Int = if (occupied) 1 else 0, scannerHit: Boolean = false) {
        aggregateBatch(sequenceOf(SpectrumSurveyInput(epochMillis, band, frequencyHz, mode, source, receiver,
            level, noise, occupied, signalCount, scannerHit)))
    }

    @Synchronized fun aggregateBatch(rows: Sequence<SpectrumSurveyInput>) {
        val started = System.nanoTime()
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { aggregateOne(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        stats = stats.copy(aggregationLatencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
    }

    private fun aggregateOne(db: SQLiteDatabase, row: SpectrumSurveyInput) {
        val bucketSeconds = timeBucketMinutes * 60L
        val bucket = row.epochMillis / 1_000L / bucketSeconds * bucketSeconds
        val frequency = row.frequencyHz / frequencyBucketHz * frequencyBucketHz
        val where = "bucket_epoch=? AND band=? AND frequency_bucket_hz=? AND mode=? AND source=? AND receiver=?"
        val args = arrayOf(bucket.toString(), row.band.take(24), frequency.toString(), row.mode.take(12), row.source.take(40), row.receiver.coerceIn(0, 7).toString())
        val values = ContentValues().apply {
            put("samples", 1); put("occupied_samples", if (row.occupied) 1 else 0); put("median_level", row.level)
            put("peak_level", row.level); put("median_noise", row.noise); put("signal_count", row.signalCount.coerceAtLeast(0)); put("scanner_hit_count", if (row.scannerHit) 1 else 0)
        }
        val existing = db.query("spectrum_aggregate", arrayOf("samples", "occupied_samples", "median_level", "peak_level", "median_noise", "signal_count", "scanner_hit_count"),
            where, args, null, null, null).use { cursor -> if (cursor.moveToFirst()) DoubleArray(7) { cursor.getDouble(it) } else null }
        if (existing == null) {
            values.put("bucket_epoch", bucket); values.put("band", row.band.take(24)); values.put("frequency_bucket_hz", frequency)
            values.put("mode", row.mode.take(12)); values.put("source", row.source.take(40)); values.put("receiver", row.receiver.coerceIn(0, 7))
            db.insertOrThrow("spectrum_aggregate", null, values)
        } else {
            val samples = existing[0].toLong() + 1
            val medianStep = .5 / samples.toDouble().pow(.5)
            values.put("samples", samples); values.put("occupied_samples", existing[1].toLong() + if (row.occupied) 1 else 0)
            values.put("median_level", existing[2] + when { row.level > existing[2] -> medianStep; row.level < existing[2] -> -medianStep; else -> 0.0 })
            values.put("peak_level", max(existing[3].toFloat(), row.level))
            values.put("median_noise", existing[4] + when { row.noise > existing[4] -> medianStep; row.noise < existing[4] -> -medianStep; else -> 0.0 })
            values.put("signal_count", existing[5].toLong() + row.signalCount)
            values.put("scanner_hit_count", existing[6].toLong() + if (row.scannerHit) 1 else 0)
            db.update("spectrum_aggregate", values, where, args)
        }
    }

    fun query(band: String? = null, startEpoch: Long = 0, endEpoch: Long = Long.MAX_VALUE, source: String? = null,
        receiver: Int? = null, limit: Int = 10_000): List<SpectrumAggregate> {
        val where = mutableListOf("bucket_epoch>=?", "bucket_epoch<=?")
        val args = mutableListOf(startEpoch.toString(), endEpoch.toString())
        band?.takeIf(String::isNotBlank)?.let { where += "band=?"; args += it }
        source?.takeIf(String::isNotBlank)?.let { where += "source=?"; args += it }
        receiver?.let { where += "receiver=?"; args += it.toString() }
        val rows = mutableListOf<SpectrumAggregate>()
        readableDatabase.query("spectrum_aggregate", null, where.joinToString(" AND "), args.toTypedArray(), null, null,
            "bucket_epoch DESC, frequency_bucket_hz", limit.coerceIn(1, 100_000).toString()).use { cursor ->
            while (cursor.moveToNext()) {
                val samples = cursor.getLong(cursor.getColumnIndexOrThrow("samples")).coerceAtLeast(1)
                rows += SpectrumAggregate(cursor.getLong(cursor.getColumnIndexOrThrow("bucket_epoch")), cursor.getString(cursor.getColumnIndexOrThrow("band")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("frequency_bucket_hz")), cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                    cursor.getString(cursor.getColumnIndexOrThrow("source")), cursor.getInt(cursor.getColumnIndexOrThrow("receiver")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("occupied_samples")) * 100f / samples,
                    cursor.getFloat(cursor.getColumnIndexOrThrow("median_level")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("peak_level")), cursor.getFloat(cursor.getColumnIndexOrThrow("median_noise")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("signal_count")), cursor.getLong(cursor.getColumnIndexOrThrow("scanner_hit_count")))
            }
        }
        return rows
    }

    fun activity(frequencyHz: Long): SpectrumAggregate? {
        val bucket = frequencyHz / frequencyBucketHz * frequencyBucketHz
        val cutoff = Instant.now().epochSecond - retentionDays * 86_400L
        return readableDatabase.query("spectrum_aggregate", null, "bucket_epoch>=? AND frequency_bucket_hz BETWEEN ? AND ?",
            arrayOf(cutoff.toString(), (bucket - frequencyBucketHz).toString(), (bucket + frequencyBucketHz).toString()),
            null, null, "bucket_epoch DESC", "1").use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val samples = cursor.getLong(cursor.getColumnIndexOrThrow("samples")).coerceAtLeast(1)
                SpectrumAggregate(cursor.getLong(cursor.getColumnIndexOrThrow("bucket_epoch")), cursor.getString(cursor.getColumnIndexOrThrow("band")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("frequency_bucket_hz")), cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                    cursor.getString(cursor.getColumnIndexOrThrow("source")), cursor.getInt(cursor.getColumnIndexOrThrow("receiver")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("occupied_samples")) * 100f / samples,
                    cursor.getFloat(cursor.getColumnIndexOrThrow("median_level")), cursor.getFloat(cursor.getColumnIndexOrThrow("peak_level")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("median_noise")), cursor.getLong(cursor.getColumnIndexOrThrow("signal_count")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("scanner_hit_count")))
            }
        }
    }

    fun compact() {
        val db = writableDatabase
        val cutoff = Instant.now().epochSecond - retentionDays * 86_400L
        db.delete("spectrum_aggregate", "bucket_epoch < ?", arrayOf(cutoff.toString()))
        db.execSQL("DELETE FROM spectrum_aggregate WHERE rowid NOT IN (SELECT rowid FROM spectrum_aggregate ORDER BY bucket_epoch DESC LIMIT ?)", arrayOf(maximumRows))
        var attempts = 0
        while (databaseFile.length() > maximumBytes && attempts < 64) {
            val count = db.rawQuery("SELECT COUNT(*) FROM spectrum_aggregate", null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0 }
            if (count == 0L) break
            db.execSQL("DELETE FROM spectrum_aggregate WHERE rowid IN (SELECT rowid FROM spectrum_aggregate ORDER BY bucket_epoch ASC LIMIT ?)",
                arrayOf(max(1L, count / 5)))
            db.execSQL("VACUUM")
            attempts++
        }
        if (databaseFile.length() > maximumBytes) throw SQLiteException("Spectrum Survey byte cap could not be enforced")
        val count = db.rawQuery("SELECT COUNT(*) FROM spectrum_aggregate", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        stats = stats.copy(rows = count, bytes = databaseFile.length())
    }

    fun quickCheck(): String = readableDatabase.rawQuery("PRAGMA quick_check", null).use { if (it.moveToFirst()) it.getString(0) else "unavailable" }
}

data class WorkbenchV4Settings(
    val scannerOrder: ScannerIntelligenceOrder = ScannerIntelligenceOrder.MEMORY_ORDER,
    val adaptiveDwell: AdaptiveDwellMode = AdaptiveDwellMode.OFF,
    val historyOverlay: Boolean = false,
    val replayAudioEnabled: Boolean = true,
    val measurementUnits: String = "AUTO",
    val surveyDisplayDefault: String = "HEATMAP",
)

class SignalMeasurementController(private val calibration: ReceiveCalibrationRepository) {
    private var frame: PanadapterFrame? = null
    private var centerHz: Long = 0
    private var source: String = "NONE"
    private var trackerStarted = 0L
    var inspector by mutableStateOf(SignalInspectorSnapshot()); private set
    var tracker by mutableStateOf(SignalTrackSnapshot()); private set
    var monitors by mutableStateOf<List<ChannelMonitor>>(emptyList()); private set
    var unitPreference by mutableStateOf("AUTO"); private set
    @Volatile var localFollowSink: (String, Int) -> Boolean = { _, _ -> false }

    fun updateFrame(value: PanadapterFrame, center: Long, sourceId: String) {
        frame = value; centerHz = center; source = sourceId
        val a = inspector.markerAHz?.let(::measure)
        val b = inspector.markerBHz?.let(::measure)
        inspector = inspector.copy(markerA = a, markerB = b,
            deltaFrequencyHz = if (a != null && b != null) b.frequencyHz - a.frequencyHz else null,
            deltaLevel = if (a != null && b != null) b.level - a.level else null,
            status = if (a == null && b == null) "Set Marker A and Marker B" else truthLabel())
        updateTracker(value)
        updateMonitors(value)
    }

    fun setMarkerA(frequencyHz: Long?) { inspector = inspector.copy(markerAHz = frequencyHz) }
    fun setMarkerB(frequencyHz: Long?) { inspector = inspector.copy(markerBHz = frequencyHz) }

    fun selectTracker(frequencyHz: Long, localReceiverId: String? = null) {
        trackerStarted = System.currentTimeMillis()
        tracker = SignalTrackSnapshot(true, frequencyHz, frequencyHz, localRxFollow = false, localReceiverId = localReceiverId,
            status = "TRACKING · LOCAL RX FOLLOW OFF")
    }

    fun setLocalFollow(enabled: Boolean) {
        tracker = tracker.copy(localRxFollow = enabled && tracker.localReceiverId != null,
            status = if (enabled && tracker.localReceiverId != null) "TRACKING · LOCAL RX FOLLOW" else "TRACKING · LOCAL RX FOLLOW OFF")
    }

    fun stopTracker() { tracker = SignalTrackSnapshot() }

    fun upsertMonitor(value: ChannelMonitor) {
        val row = value.copy(name = value.name.take(40), frequencyHz = value.frequencyHz.coerceIn(100_000, 10_500_000_000),
            mode = value.mode.uppercase().take(12), squelchDb = value.squelchDb.coerceIn(-140f, 0f))
        monitors = (monitors.filterNot { it.id == row.id } + row).take(4)
    }

    fun removeMonitor(id: String) { monitors = monitors.filterNot { it.id == id } }
    fun updateUnitPreference(value: String) { unitPreference = if (value == "DBFS") "DBFS" else "AUTO" }

    fun measure(frequencyHz: Long): SignalMeasurement? {
        val value = frame ?: return null
        if (value.trace.isEmpty() || centerHz <= 0 || frequencyHz !in (centerHz - value.effectiveSampleRate / 2L)..(centerHz + value.effectiveSampleRate / 2L)) return null
        val bin = (((frequencyHz - (centerHz - value.effectiveSampleRate / 2.0)) / value.effectiveSampleRate * value.trace.size).toInt())
            .coerceIn(0, value.trace.lastIndex)
        val peakBin = (bin - 3..bin + 3).filter { it in value.trace.indices }.maxByOrNull { value.trace[it] } ?: bin
        val rawPeak = value.trace[peakBin]
        val noiseValues = value.trace.indices.filter { abs(it - peakBin) > 12 && value.validMask.getOrElse(it) { true } }
            .map { value.trace[it] }.filter(Float::isFinite).sorted()
        val noise = noiseValues.getOrElse(noiseValues.size / 2) { value.floorDb }
        fun width(threshold: Float): Float {
            var left = peakBin; var right = peakBin
            while (left > 0 && value.trace[left] >= rawPeak - threshold) left--
            while (right < value.trace.lastIndex && value.trace[right] >= rawPeak - threshold) right++
            return (right - left).coerceAtLeast(1) * value.effectiveSampleRate.toFloat() / value.trace.size
        }
        val width26 = width(26f)
        val halfChannel = max(1, (width26 / value.effectiveSampleRate * value.trace.size / 2).toInt())
        fun power(first: Int, last: Int): Float {
            val watts = (first..last).filter { it in value.trace.indices }.sumOf { 10.0.pow(value.trace[it] / 10.0) }
            return if (watts > 0) (10.0 * log10(watts)).toFloat() else -160f
        }
        val channelPower = power(peakBin - halfChannel, peakBin + halfChannel)
        val adjacent = max(power(peakBin - halfChannel * 3, peakBin - halfChannel - 1), power(peakBin + halfChannel + 1, peakBin + halfChannel * 3))
        val calibrated = calibration.calibration(source)
        val useCalibration = calibrated.truth == CalibrationTruth.CALIBRATED_BY_USER && unitPreference != "DBFS"
        val offset = if (useCalibration) calibrated.levelOffsetDb else 0f
        val units = if (useCalibration) "dBm · CALIBRATED BY USER" else "dBFS · ${calibrated.truth}"
        val exact = (centerHz - value.effectiveSampleRate / 2.0 + peakBin.toDouble() * value.effectiveSampleRate / value.trace.size).roundToLong()
        return SignalMeasurement(exact, rawPeak + offset, noise + offset, rawPeak - noise, width(10f), width(3f), width(6f), width26,
            channelPower + offset, adjacent + offset, exact - centerHz, units)
    }

    private fun updateTracker(value: PanadapterFrame) {
        if (!tracker.selected || tracker.frequencyHz !in (centerHz - value.effectiveSampleRate / 2L)..(centerHz + value.effectiveSampleRate / 2L)) {
            if (tracker.selected) tracker = tracker.copy(status = "TRACKED SIGNAL OUTSIDE CURRENT I/Q SPAN · RECEIVE REVIEW REQUIRED")
            return
        }
        val measurement = measure(tracker.frequencyHz) ?: return
        val next = tracker.copy(frequencyHz = measurement.frequencyHz, driftHz = measurement.frequencyHz - tracker.startFrequencyHz,
            level = measurement.level, snr = measurement.snr, durationMillis = System.currentTimeMillis() - trackerStarted)
        tracker = if (next.localRxFollow && next.localReceiverId != null) {
            val accepted = localFollowSink(next.localReceiverId, (next.frequencyHz - centerHz).toInt())
            next.copy(status = if (accepted) "TRACKING · LOCAL RX FOLLOW" else "LOCAL RX FOLLOW BLOCKED · OUTSIDE CURRENT I/Q SPAN")
        } else next.copy(status = "TRACKING · PHYSICAL VFO UNCHANGED")
    }

    private fun updateMonitors(value: PanadapterFrame) {
        val now = System.currentTimeMillis()
        monitors = monitors.map { row ->
            val measurement = measure(row.frequencyHz)
            if (measurement == null) row.copy(level = Float.NaN, occupied = false, activity = ChannelActivityState.UNKNOWN,
                toneState = if (row.mode == "NFM") "UNDETECTED · OUT OF SPAN" else "NOT APPLICABLE")
            else {
                val occupied = measurement.level >= row.squelchDb
                val duration = if (occupied) row.activityDurationMillis + 100L else 0L
                val occupancy = (row.occupancyPercent * .95f + if (occupied) 5f else 0f).coerceIn(0f, 100f)
                row.copy(level = measurement.level, occupied = occupied, activityDurationMillis = duration,
                    occupancyPercent = occupancy, activity = when { occupied && occupancy >= 50 -> ChannelActivityState.HOT
                        occupied -> ChannelActivityState.RECENT; occupancy < 5 -> ChannelActivityState.QUIET; else -> ChannelActivityState.RECENT },
                    toneState = if (row.mode == "NFM") "UNDETECTED · NO TONE CLAIM" else "NOT APPLICABLE")
            }
        }
    }

    private fun truthLabel(): String = calibration.calibration(source).truth.name.replace('_', ' ')
}

class AndroidSdrWorkbenchV4(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("shackcq-sdr-workbench-v4", Context.MODE_PRIVATE)
    private val surveyExecutor = Executors.newSingleThreadExecutor { Thread(it, "ShackCQ-Spectrum-Survey").apply { isDaemon = true } }
    private var lastSurveyMillis = 0L
    val capture = IqCaptureRepository(context)
    val replay = ReplayIqSource(capture)
    val calibration = ReceiveCalibrationRepository(context)
    val survey = SpectrumSurveyRepository(context)
    val measurement = SignalMeasurementController(calibration)
    var settings by mutableStateOf(loadSettings()); private set
    var surveyRows by mutableStateOf<List<SpectrumAggregate>>(emptyList()); private set
    var historicalLabel by mutableStateOf("LIVE"); private set
    @Volatile var replaySink: (String, Int, Long, Int, FloatArray) -> Unit = { _, _, _, _, _ -> }

    init {
        replay.sink = { source, receiver, center, rate, samples -> replaySink(source, receiver, center, rate, samples) }
        measurement.updateUnitPreference(settings.measurementUnits)
    }

    fun updateSettings(value: WorkbenchV4Settings) {
        settings = value
        measurement.updateUnitPreference(value.measurementUnits)
        preferences.edit().putString("scanner_order", value.scannerOrder.name).putString("adaptive_dwell", value.adaptiveDwell.name)
            .putBoolean("history_overlay", value.historyOverlay).putBoolean("replay_audio", value.replayAudioEnabled)
            .putString("measurement_units", value.measurementUnits.take(20))
            .putString("survey_display_default", value.surveyDisplayDefault.take(20)).apply()
    }

    fun ingestLive(source: String, receiver: Int, centerHz: Long, sampleRate: Int, samples: FloatArray): FloatArray {
        val corrected = calibration.apply(source, centerHz, sampleRate, samples)
        capture.append(source, receiver, sampleRate, corrected)
        return corrected
    }

    fun onPanadapterFrame(frame: PanadapterFrame, receiver: Int, centerHz: Long, band: String, mode: String, source: String,
        scannerHit: Boolean = false) {
        measurement.updateFrame(frame, centerHz, source)
        if (source == "DEBUG FIXTURE") return
        val now = System.currentTimeMillis()
        if (now - lastSurveyMillis < 1_000) return
        lastSurveyMillis = now
        val peakFrequency = measurement.measure(centerHz)?.frequencyHz ?: centerHz
        val occupied = frame.peakDb - frame.floorDb >= 6f
        surveyExecutor.execute {
            survey.aggregate(now, band, peakFrequency, mode, source, receiver, frame.peakDb, frame.floorDb, occupied,
                if (occupied) 1 else 0, scannerHit)
            if (survey.stats.rows % 1_000L == 0L) survey.compact()
        }
    }

    fun updateHistory(timeShift: ReceiveTimeShiftController, currentCenterHz: Long) {
        val state = timeShift.snapshot
        val selected = timeShift.selectedFrame
        historicalLabel = when (state.playback) {
            TimeShiftPlayback.LIVE -> "LIVE"
            TimeShiftPlayback.PAUSED -> if (selected != null && abs(selected.centerHz - currentCenterHz) > max(1, selected.sampleRate / 2))
                "-${state.cursorSecondsBehind}s · HISTORICAL · RADIO NOW ELSEWHERE" else "-${state.cursorSecondsBehind}s · HISTORICAL"
            TimeShiftPlayback.REPLAYING -> if (selected != null && abs(selected.centerHz - currentCenterHz) > max(1, selected.sampleRate / 2))
                "REPLAY · HISTORICAL · RADIO NOW ELSEWHERE" else "REPLAY · HISTORICAL"
        }
    }

    fun ordered(memories: List<ScanMemory>, bankId: String?): List<ScanMemory> = when (settings.scannerOrder) {
        ScannerIntelligenceOrder.FREQUENCY -> memories.sortedBy { it.frequencyHz }
        ScannerIntelligenceOrder.MEMORY_ORDER -> memories
        ScannerIntelligenceOrder.MOST_ACTIVE -> memories.sortedByDescending { survey.activity(it.frequencyHz)?.occupancyPercent ?: it.activityScore }
        ScannerIntelligenceOrder.RECENT_ACTIVITY -> memories.sortedByDescending { survey.activity(it.frequencyHz)?.bucketEpoch ?: it.lastHeardEpoch }
        ScannerIntelligenceOrder.LEAST_RECENTLY_CHECKED -> memories.sortedBy { survey.activity(it.frequencyHz)?.bucketEpoch ?: Long.MIN_VALUE }
    }

    fun dwell(frequencyHz: Long, operatorMinimumMillis: Long, priority: Boolean): Long {
        if (settings.adaptiveDwell == AdaptiveDwellMode.OFF) return operatorMinimumMillis
        val activity = survey.activity(frequencyHz)?.occupancyPercent ?: 0f
        val multiplier = when { priority -> 2.0; activity >= 50f -> 1.75; activity >= 10f -> 1.35; else -> 1.0 }
        return (operatorMinimumMillis * multiplier).roundToLong().coerceIn(operatorMinimumMillis, operatorMinimumMillis * 2)
    }

    fun refreshSurvey(band: String? = null, source: String? = null, receiver: Int? = null) {
        surveyRows = survey.query(band, Instant.now().epochSecond - survey.retentionDays * 86_400L, Instant.now().epochSecond, source, receiver)
    }

    fun seedDebugSurvey() {
        check(BuildConfig.DEBUG)
        val now = Instant.now().epochSecond
        surveyRows = List(30 * 24) { index -> SpectrumAggregate(
            now - (30 * 24 - index) * 3_600L, listOf("20m", "40m", "2m")[index % 3],
            listOf(14_074_000L, 7_074_000L, 145_500_000L)[index % 3] + (index % 9) * 1_000L,
            if (index % 3 == 2) "NFM" else "DIGU", "DEMO · NO RADIO", index % 2,
            ((index * 17) % 100).toFloat(), -110f + index % 45, -75f + index % 20,
            -125f + index % 12, (index % 8).toLong(), (index % 4).toLong()) }
    }

    fun channelState(frequencyHz: Long): ChannelActivityState {
        val row = survey.activity(frequencyHz) ?: return ChannelActivityState.UNKNOWN
        val age = Instant.now().epochSecond - row.bucketEpoch
        return when { row.occupancyPercent >= 50f -> ChannelActivityState.HOT; age <= 86_400 -> ChannelActivityState.RECENT
            row.occupancyPercent < 5f -> ChannelActivityState.QUIET; else -> ChannelActivityState.RECENT }
    }

    private fun loadSettings() = WorkbenchV4Settings(
        scannerOrder = runCatching { ScannerIntelligenceOrder.valueOf(preferences.getString("scanner_order", "") ?: "") }.getOrDefault(ScannerIntelligenceOrder.MEMORY_ORDER),
        adaptiveDwell = runCatching { AdaptiveDwellMode.valueOf(preferences.getString("adaptive_dwell", "") ?: "") }.getOrDefault(AdaptiveDwellMode.OFF),
        historyOverlay = preferences.getBoolean("history_overlay", false), replayAudioEnabled = preferences.getBoolean("replay_audio", true),
        measurementUnits = preferences.getString("measurement_units", "AUTO")?.takeIf { it in setOf("AUTO", "DBFS") } ?: "AUTO",
        surveyDisplayDefault = preferences.getString("survey_display_default", "HEATMAP")
            ?.takeIf { it in setOf("HEATMAP", "BAND", "DAILY", "SCANNER") } ?: "HEATMAP")

    override fun close() {
        replay.close(); capture.close(); surveyExecutor.shutdownNow(); survey.compact(); survey.close()
    }
}

internal fun exportChannelMemoriesJson(rows: List<ScanMemory>): String = JSONArray(rows.take(2_000).map { row -> JSONObject()
    .put("name", row.name).put("group", row.group).put("rx_frequency_hz", row.frequencyHz).put("mode", row.mode)
    .put("filter_hz", row.filterHz).put("expected_ctcss_hz", row.expectedCtcssHz).put("expected_dcs", row.expectedDcs)
    .put("scan_enabled", row.scanEnabled).put("priority", row.priority).put("note", row.note).put("location_grid", row.locationGrid)
    .put("last_heard_epoch", row.lastHeardEpoch).put("activity_score", row.activityScore) }).toString(2)

internal fun importChannelMemoriesJson(value: String): List<ScanMemory> {
    val array = JSONArray(value)
    return List(array.length().coerceAtMost(2_000)) { index -> array.getJSONObject(index).let { row -> ScanMemory(
        row.getLong("rx_frequency_hz"), row.optString("mode", "USB"), row.optInt("filter_hz", 2_700), row.optString("name"),
        row.optString("group"), row.optDouble("expected_ctcss_hz").takeIf { !row.isNull("expected_ctcss_hz") }?.toFloat(),
        row.optInt("expected_dcs").takeIf { !row.isNull("expected_dcs") }, row.optBoolean("scan_enabled", true),
        row.optBoolean("priority"), row.optString("note"), row.optString("location_grid"), row.optLong("last_heard_epoch"),
        row.optDouble("activity_score").toFloat()).validated() } }
}

internal fun exportChannelMemoriesCsv(rows: List<ScanMemory>): String = buildString {
    appendLine("name,group,rx_frequency_hz,mode,filter_hz,expected_ctcss_hz,expected_dcs,scan_enabled,priority,note,location_grid,last_heard_epoch,activity_score")
    rows.take(2_000).forEach { row -> appendLine(listOf(row.name, row.group, row.frequencyHz, row.mode, row.filterHz,
        row.expectedCtcssHz ?: "", row.expectedDcs ?: "", row.scanEnabled, row.priority, row.note, row.locationGrid,
        row.lastHeardEpoch, row.activityScore).joinToString(",") { csvCell(it.toString()) }) }
}

internal fun importChannelMemoriesCsv(value: String): List<ScanMemory> = value.lineSequence().drop(1).take(2_000).mapNotNull { line ->
    val fields = parseCsvLine(line)
    if (fields.size != 13) null else runCatching { ScanMemory(fields[2].toLong(), fields[3], fields[4].toInt(), fields[0], fields[1],
        fields[5].toFloatOrNull(), fields[6].toIntOrNull(), fields[7].toBooleanStrict(), fields[8].toBooleanStrict(), fields[9], fields[10],
        fields[11].toLong(), fields[12].toFloat()).validated() }.getOrNull()
}.toList()

private fun csvCell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

private fun parseCsvLine(value: String): List<String> {
    val output = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var index = 0
    while (index < value.length) {
        val character = value[index]
        when { character == '"' && quoted && index + 1 < value.length && value[index + 1] == '"' -> { field.append('"'); index++ }
            character == '"' -> quoted = !quoted
            character == ',' && !quoted -> { output += field.toString(); field.clear() }
            else -> field.append(character) }
        index++
    }
    output += field.toString()
    return output
}
