// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class ReceiverLinkMode { INDEPENDENT, FREQUENCY_LINKED, MODE_LINKED, SAME_FREQUENCY_COMPARE }
enum class ProtocolFeatureState { AVAILABLE, DISABLED, UNAVAILABLE_PROTOCOL, PHYSICAL_ACCEPTANCE_REQUIRED }
enum class TimeShiftLength(val seconds: Int) { OFF(0), SECONDS_30(30), SECONDS_60(60), SECONDS_120(120) }
enum class TimeShiftPlayback { LIVE, PAUSED, REPLAYING }
enum class SkimmerMode { PSK31, RTTY }
enum class RecordOnHitMode { OFF, AUDIO, IQ }

data class RecordOnHitPolicy(
    val preRollSeconds: Int = 5,
    val postRollSeconds: Int = 5,
    val maximumDurationSeconds: Int = 30,
    val dailyBytes: Long = 64L * 1024 * 1024,
    val totalBytes: Long = 256L * 1024 * 1024,
) {
    fun validated() = copy(preRollSeconds = preRollSeconds.coerceIn(0, 30), postRollSeconds = postRollSeconds.coerceIn(0, 30),
        maximumDurationSeconds = maximumDurationSeconds.coerceIn(1, 120), dailyBytes = dailyBytes.coerceIn(1L shl 20, 512L shl 20),
        totalBytes = totalBytes.coerceIn(4L shl 20, 2L shl 30))
}

data class ScanMemory(
    val frequencyHz: Long,
    val mode: String,
    val filterHz: Int = 2_700,
    val name: String = "",
    val group: String = "",
    val expectedCtcssHz: Float? = null,
    val expectedDcs: Int? = null,
    val scanEnabled: Boolean = true,
    val priority: Boolean = false,
    val note: String = "",
    val locationGrid: String = "",
    val lastHeardEpoch: Long = 0,
    val activityScore: Float = 0f,
) {
    fun validated() = copy(frequencyHz = frequencyHz.coerceIn(100_000, 10_500_000_000),
        mode = mode.uppercase().take(12).ifBlank { "USB" }, filterHz = filterHz.coerceIn(50, 100_000),
        name = name.take(60), group = group.take(40), expectedCtcssHz = expectedCtcssHz?.coerceIn(50f, 300f),
        expectedDcs = expectedDcs?.coerceIn(0, 777), note = note.take(240), locationGrid = locationGrid.uppercase().take(10),
        lastHeardEpoch = lastHeardEpoch.coerceAtLeast(0), activityScore = activityScore.coerceIn(0f, 100f))
}

data class ScanBank(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val memories: List<ScanMemory> = emptyList(),
    val priority: ScanMemory? = null,
    val thresholdDb: Float = -90f,
    val dwellMillis: Long = 1_500,
    val resumePolicy: ScannerResumePolicy = ScannerResumePolicy.CARRIER_DROP,
    val recordOnHit: RecordOnHitMode = RecordOnHitMode.OFF,
) {
    fun validated() = copy(id = id.take(64), name = name.take(60).ifBlank { "Scan bank" },
        memories = memories.take(2_000).map(ScanMemory::validated), priority = priority?.validated(),
        thresholdDb = thresholdDb.coerceIn(-140f, 0f), dwellMillis = dwellMillis.coerceIn(100, 60_000))
}

data class ScannerJournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val epoch: Long = Instant.now().epochSecond,
    val bank: String,
    val source: String,
    val frequencyHz: Long,
    val mode: String,
    val peakDb: Float?,
    val durationMillis: Long,
    val resumeReason: String,
    val captureId: String? = null,
)

data class SignalBookmark(
    val id: String,
    val epoch: Long,
    val receiver: Int,
    val frequencyHz: Long,
    val source: String,
    val label: String,
    val sampleCount: Int,
)

data class TimeShiftFrame(
    val sequence: Long,
    val epochMillis: Long,
    val receiver: Int,
    val centerHz: Long,
    val sampleRate: Int,
    val source: String,
    val trace: FloatArray,
)

data class TimeShiftSnapshot(
    val length: TimeShiftLength = TimeShiftLength.OFF,
    val playback: TimeShiftPlayback = TimeShiftPlayback.LIVE,
    val bufferedSeconds: Int = 0,
    val cursorSecondsBehind: Int = 0,
    val frameCount: Int = 0,
    val bytes: Long = 0,
    val source: String = "NONE",
    val invalidations: Long = 0,
)

data class SkimmerMarker(
    val id: String,
    val mode: SkimmerMode,
    val frequencyHz: Long,
    val levelDb: Float,
    val snrDb: Float,
    val confidence: Float,
    val text: String,
    val callLikeToken: String?,
    val source: String,
    val epochMillis: Long,
    val confirmed: Boolean,
)

data class TxAudioLevel(
    val mode: String,
    val level: Float,
    val inherited: Boolean,
    val source: String,
)

data class TxCalibrationSnapshot(
    val mode: String,
    val level: Float,
    val inherited: Boolean,
    val profile: String,
    val route: String,
    val physicalAcceptance: Boolean = false,
    val telemetryReady: Boolean = false,
    val sendEnabled: Boolean = false,
    val reason: String = "Physical TX-audio acceptance is absent; SEND and CALIBRATE ON AIR are locked",
)

internal fun linkedReceiverActions(linkMode: ReceiverLinkMode, sourceReceiver: Int, frequencyHz: Long?, mode: String?,
    receivers: List<TciReceiverSnapshot>): List<RadioPlatformAction> =
    receivers.filter { it.backendIndex != sourceReceiver }.take(1).flatMap { target -> buildList {
        if (frequencyHz != null && linkMode in setOf(ReceiverLinkMode.FREQUENCY_LINKED, ReceiverLinkMode.SAME_FREQUENCY_COMPARE))
            add(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = frequencyHz, targetReceiver = target.backendIndex))
        if (mode != null && linkMode == ReceiverLinkMode.MODE_LINKED)
            add(RadioPlatformAction(RadioActionClass.SAFE_SET, "mode", textValue = mode, targetReceiver = target.backendIndex))
    } }

internal fun resolveTxAudioLevel(mode: String, defaultLevel: Float, levels: Map<String, Float>): TxAudioLevel {
    val key = mode.uppercase()
    val explicit = levels[key]
    return TxAudioLevel(key, (explicit ?: defaultLevel).coerceIn(0f, 1f), explicit == null,
        if (explicit == null) "DEFAULT" else "MODE OVERRIDE")
}

internal class SdrV2DerivedStore(context: Context) : SQLiteOpenHelper(
    context, "shackcq-sdr-operational-v2-derived.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scanner_journal(id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, bank TEXT NOT NULL, source TEXT NOT NULL, frequency_hz INTEGER NOT NULL, mode TEXT NOT NULL, peak_db REAL, duration_ms INTEGER NOT NULL, resume_reason TEXT NOT NULL, capture_id TEXT)")
        db.execSQL("CREATE INDEX scanner_journal_epoch ON scanner_journal(epoch DESC)")
        db.execSQL("CREATE TABLE signal_bookmarks(id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, receiver INTEGER NOT NULL, frequency_hz INTEGER NOT NULL, source TEXT NOT NULL, label TEXT NOT NULL, trace BLOB NOT NULL)")
        db.execSQL("CREATE INDEX signal_bookmarks_epoch ON signal_bookmarks(epoch DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addJournal(row: ScannerJournalEntry, retentionDays: Int, maximumRows: Int) {
        writableDatabase.insertWithOnConflict("scanner_journal", null, ContentValues().apply {
            put("id", row.id); put("epoch", row.epoch); put("bank", row.bank.take(60)); put("source", row.source.take(40))
            put("frequency_hz", row.frequencyHz); put("mode", row.mode.take(12)); row.peakDb?.let { put("peak_db", it) }
            put("duration_ms", row.durationMillis.coerceIn(0, 3_600_000)); put("resume_reason", row.resumeReason.take(120))
            row.captureId?.let { put("capture_id", it.take(80)) }
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val cutoff = Instant.now().epochSecond - retentionDays.coerceIn(7, 90) * 86_400L
        writableDatabase.delete("scanner_journal", "epoch < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL("DELETE FROM scanner_journal WHERE id NOT IN (SELECT id FROM scanner_journal ORDER BY epoch DESC LIMIT ?)",
            arrayOf(maximumRows.coerceIn(100, 50_000)))
    }

    fun journal(limit: Int = 100): List<ScannerJournalEntry> {
        val rows = mutableListOf<ScannerJournalEntry>()
        readableDatabase.query("scanner_journal", null, null, null, null, null, "epoch DESC", limit.coerceIn(1, 500).toString()).use { cursor ->
            while (cursor.moveToNext()) rows += ScannerJournalEntry(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                epoch = cursor.getLong(cursor.getColumnIndexOrThrow("epoch")),
                bank = cursor.getString(cursor.getColumnIndexOrThrow("bank")),
                source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                frequencyHz = cursor.getLong(cursor.getColumnIndexOrThrow("frequency_hz")),
                mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                peakDb = cursor.getColumnIndexOrThrow("peak_db").let { if (cursor.isNull(it)) null else cursor.getFloat(it) },
                durationMillis = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                resumeReason = cursor.getString(cursor.getColumnIndexOrThrow("resume_reason")),
                captureId = cursor.getColumnIndexOrThrow("capture_id").let { if (cursor.isNull(it)) null else cursor.getString(it) },
            )
        }
        return rows
    }

    fun addBookmark(frame: TimeShiftFrame, label: String): SignalBookmark {
        val id = UUID.randomUUID().toString()
        val bytes = ByteBuffer.allocate(16 + frame.trace.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(frame.sampleRate).putLong(frame.centerHz).also { buffer -> frame.trace.forEach(buffer::putFloat) }.array()
        writableDatabase.insertOrThrow("signal_bookmarks", null, ContentValues().apply {
            put("id", id); put("epoch", frame.epochMillis / 1_000); put("receiver", frame.receiver)
            put("frequency_hz", frame.centerHz); put("source", frame.source.take(40)); put("label", label.take(80)); put("trace", bytes)
        })
        writableDatabase.execSQL("DELETE FROM signal_bookmarks WHERE id NOT IN (SELECT id FROM signal_bookmarks ORDER BY epoch DESC LIMIT 256)")
        return SignalBookmark(id, frame.epochMillis / 1_000, frame.receiver, frame.centerHz, frame.source, label.take(80), frame.trace.size)
    }

    fun bookmarks(limit: Int = 100): List<SignalBookmark> {
        val rows = mutableListOf<SignalBookmark>()
        readableDatabase.query("signal_bookmarks", arrayOf("id", "epoch", "receiver", "frequency_hz", "source", "label", "length(trace) AS bytes"),
            null, null, null, null, "epoch DESC", limit.coerceIn(1, 256).toString()).use { cursor ->
            while (cursor.moveToNext()) rows += SignalBookmark(cursor.getString(0), cursor.getLong(1), cursor.getInt(2), cursor.getLong(3),
                cursor.getString(4), cursor.getString(5), ((cursor.getInt(6) - 16) / 4).coerceAtLeast(0))
        }
        return rows
    }

    fun bookmarkBytesSince(epoch: Long = 0): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(length(trace)), 0) FROM signal_bookmarks WHERE epoch >= ?", arrayOf(epoch.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    fun deleteBookmark(id: String): Boolean = writableDatabase.delete("signal_bookmarks", "id = ?", arrayOf(id.take(80))) == 1
}

class ReceiveTimeShiftController internal constructor(private val store: SdrV2DerivedStore) {
    private data class AudioFrame(val receiver: Int, val epochMillis: Long, val samples: FloatArray)
    private val ring = ArrayDeque<TimeShiftFrame>()
    private val audioRing = ArrayDeque<AudioFrame>()
    private var audioSamples = 0L
    private var lastCaptureMillis = 0L
    private var invalidations = 0L
    var snapshot by mutableStateOf(TimeShiftSnapshot()); private set
    var bookmarks by mutableStateOf(store.bookmarks()); private set
    var selectedFrame by mutableStateOf<TimeShiftFrame?>(null); private set

    fun configure(length: TimeShiftLength) {
        if (snapshot.length == length) return
        clear("Time-shift length changed")
        snapshot = snapshot.copy(length = length, playback = TimeShiftPlayback.LIVE)
    }

    @Synchronized fun capture(frame: PanadapterFrame, receiver: Int, centerHz: Long, source: String) {
        if (snapshot.length == TimeShiftLength.OFF || snapshot.playback != TimeShiftPlayback.LIVE || centerHz <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureMillis < 90) return
        val previous = ring.lastOrNull()
        if (previous != null && (previous.receiver != receiver || previous.source != source ||
                kotlin.math.abs(previous.centerHz - centerHz) > max(frame.sampleRate / 2L, 1L))) {
            clear("Source or radio changed")
            invalidations++
        }
        lastCaptureMillis = now
        val reduced = reduceTrace(frame.trace, 512)
        ring += TimeShiftFrame(frame.sequence, now, receiver, centerHz, frame.sampleRate, source.take(40), reduced)
        val cutoff = now - snapshot.length.seconds * 1_000L
        while (ring.firstOrNull()?.epochMillis?.let { it < cutoff } == true) ring.removeFirst()
        selectedFrame = ring.lastOrNull()
        publish()
    }

    /** The existing time-shift owner also retains explicitly enabled 48 kHz demod audio for recording pre-roll. */
    @Synchronized fun captureAudio(receiver: Int, samples: FloatArray) {
        if (snapshot.length == TimeShiftLength.OFF || snapshot.playback != TimeShiftPlayback.LIVE || samples.isEmpty()) return
        val copy = samples.copyOf(samples.size.coerceAtMost(48_000))
        audioRing += AudioFrame(receiver, System.currentTimeMillis(), copy)
        audioSamples += copy.size
        val maximum = snapshot.length.seconds.toLong() * 48_000L * 2L
        while (audioSamples > maximum && audioRing.isNotEmpty()) audioSamples -= audioRing.removeFirst().samples.size
    }

    @Synchronized fun audioPreRoll(receiver: Int, seconds: Int): FloatArray {
        val wanted = seconds.coerceIn(0, snapshot.length.seconds) * 48_000
        if (wanted == 0) return FloatArray(0)
        val selected = audioRing.asReversed().filter { it.receiver == receiver }
        val output = FloatArray(minOf(wanted, selected.sumOf { it.samples.size }))
        var cursor = output.size
        selected.forEach { frame ->
            if (cursor <= 0) return@forEach
            val count = minOf(cursor, frame.samples.size)
            cursor -= count
            frame.samples.copyInto(output, cursor, frame.samples.size - count)
        }
        return if (cursor == 0) output else output.copyOfRange(cursor, output.size)
    }

    @Synchronized fun pause() {
        if (ring.isEmpty()) return
        selectedFrame = ring.last()
        snapshot = snapshot.copy(playback = TimeShiftPlayback.PAUSED, cursorSecondsBehind = 0)
    }

    @Synchronized fun scrub(secondsBehind: Int) {
        if (ring.isEmpty()) return
        val bounded = secondsBehind.coerceIn(0, snapshot.length.seconds)
        val target = System.currentTimeMillis() - bounded * 1_000L
        selectedFrame = ring.minByOrNull { kotlin.math.abs(it.epochMillis - target) }
        snapshot = snapshot.copy(playback = TimeShiftPlayback.PAUSED, cursorSecondsBehind = bounded)
    }

    @Synchronized fun replay() { if (selectedFrame != null) snapshot = snapshot.copy(playback = TimeShiftPlayback.REPLAYING) }
    @Synchronized fun returnLive() { selectedFrame = ring.lastOrNull(); snapshot = snapshot.copy(playback = TimeShiftPlayback.LIVE, cursorSecondsBehind = 0) }

    @Synchronized fun bookmark(label: String = "Signal bookmark"): SignalBookmark? {
        val frame = selectedFrame ?: ring.lastOrNull() ?: return null
        return store.addBookmark(frame, label).also { bookmarks = store.bookmarks() }
    }

    @Synchronized fun deleteBookmark(id: String) {
        if (store.deleteBookmark(id)) bookmarks = store.bookmarks()
    }

    @Synchronized fun clear(reason: String = "Cleared by operator") {
        ring.clear(); audioRing.clear(); audioSamples = 0; selectedFrame = null
        snapshot = snapshot.copy(playback = TimeShiftPlayback.LIVE, bufferedSeconds = 0, cursorSecondsBehind = 0,
            frameCount = 0, bytes = 0, source = reason.take(40), invalidations = invalidations)
    }

    @Synchronized private fun publish() {
        val seconds = if (ring.size < 2) 0 else ((ring.last().epochMillis - ring.first().epochMillis) / 1_000).toInt()
        snapshot = snapshot.copy(bufferedSeconds = seconds, frameCount = ring.size,
            bytes = ring.sumOf { 40L + it.trace.size * 4L }, source = ring.lastOrNull()?.source ?: "NONE", invalidations = invalidations)
    }
}

internal fun reduceTrace(values: FloatArray, maximum: Int): FloatArray {
    if (values.size <= maximum) return values.copyOf()
    val bucket = values.size.toDouble() / maximum
    return FloatArray(maximum) { index ->
        val start = (index * bucket).toInt().coerceIn(0, values.lastIndex)
        val end = (((index + 1) * bucket).toInt()).coerceIn(start + 1, values.size)
        values.sliceArray(start until end).maxOrNull() ?: values[start]
    }
}

internal fun frequencyFromDigits(value: String): Long? {
    val digits = value.filter(Char::isDigit).take(11)
    if (digits.isEmpty()) return null
    return digits.toLongOrNull()?.takeIf { it in 100_000L..10_500_000_000L }
}

internal fun adjustFrequencyDigit(frequencyHz: Long, decadeHz: Long, direction: Int): Long =
    (frequencyHz + decadeHz.coerceIn(1, 1_000_000_000) * direction.coerceIn(-1, 1)).coerceIn(100_000, 10_500_000_000)

internal fun skimmerCandidates(frame: PanadapterFrame, centerHz: Long, mode: SkimmerMode, maximum: Int = 4): List<SkimmerMarker> {
    if (frame.trace.size < 3 || centerHz <= 0 || maximum <= 0) return emptyList()
    val segments = when (mode) {
        SkimmerMode.PSK31 -> listOf(3_580_000L..3_585_000L, 7_035_000L..7_040_000L, 14_070_000L..14_075_000L,
            21_070_000L..21_075_000L, 28_120_000L..28_125_000L)
        SkimmerMode.RTTY -> listOf(3_580_000L..3_600_000L, 7_035_000L..7_045_000L, 14_080_000L..14_100_000L,
            21_080_000L..21_100_000L, 28_080_000L..28_100_000L)
    }
    val span = frame.effectiveSampleRate.takeIf { it > 0 } ?: frame.sampleRate
    return (1 until frame.trace.lastIndex).asSequence().map { index ->
        val hz = centerHz - span / 2L + span.toLong() * index / frame.trace.size
        Triple(index, hz, frame.trace[index])
    }.filter { (index, hz, level) -> segments.any { hz in it } && level.isFinite() && level >= frame.floorDb + 10f &&
        level >= frame.trace[index - 1] && level > frame.trace[index + 1] }
        .sortedByDescending { it.third }.take(maximum.coerceAtMost(4)).map { (_, hz, level) ->
            val snr = (level - frame.floorDb).coerceAtLeast(0f)
            SkimmerMarker("${mode.name}:$hz", mode, hz, level, snr, (snr / 30f).coerceIn(0f, .95f), "", null,
                "LIVE FFT CANDIDATE", System.currentTimeMillis(), confirmed = false)
        }.toList()
}

class WidebandSkimmerController {
    private data class IqSource(val receiver: Int, val rate: Int, val centerHz: Long, val values: FloatArray)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ShackCQ-Wideband-Skimmer").apply { isDaemon = true } }
    private val working = AtomicBoolean(false)
    @Volatile private var iq: IqSource? = null
    var enabledModes by mutableStateOf<Set<SkimmerMode>>(emptySet()); private set
    var markers by mutableStateOf<List<SkimmerMarker>>(emptyList()); private set
    var selectedMarker by mutableStateOf<SkimmerMarker?>(null); private set
    var decodeMillis by mutableStateOf(0L); private set
    var expired by mutableStateOf(0L); private set
    var status by mutableStateOf("Skimmers stopped"); private set

    fun setEnabled(mode: SkimmerMode, enabled: Boolean) {
        enabledModes = if (enabled) enabledModes + mode else enabledModes - mode
        if (enabledModes.isEmpty()) { markers = emptyList(); selectedMarker = null; status = "Skimmers stopped" }
        else status = "Operator-enabled · ${enabledModes.joinToString()} · maximum 4 lanes per mode"
    }

    fun pushIq(receiver: Int, rate: Int, centerHz: Long, values: FloatArray) {
        if (enabledModes.isEmpty() || receiver !in 0..7 || rate !in 8_000..384_000 || centerHz <= 0 || values.size < 4) return
        iq = IqSource(receiver, rate, centerHz, values.copyOf(values.size.coerceAtMost(262_144)))
    }

    fun analyze(frame: PanadapterFrame, centerHz: Long) {
        if (enabledModes.isEmpty() || !working.compareAndSet(false, true)) return
        val modes = enabledModes
        executor.execute {
            val start = System.nanoTime()
            val candidates = modes.flatMap { skimmerCandidates(frame, centerHz, it, 4) }
            val decoded = candidates.map(::decodeCandidate)
            val now = System.currentTimeMillis()
            val previous = markers.filter { now - it.epochMillis <= 15_000 && decoded.none { row -> row.id == it.id } }
            expired += markers.size - previous.size
            markers = (decoded + previous).sortedByDescending(SkimmerMarker::confidence).take(32)
            selectedMarker = selectedMarker?.let { old -> markers.firstOrNull { it.id == old.id } }
            decodeMillis = (System.nanoTime() - start) / 1_000_000
            status = "${markers.size} bounded markers · ${decodeMillis} ms · no TX"
            working.set(false)
        }
    }

    fun select(id: String?) { selectedMarker = markers.firstOrNull { it.id == id } }
    fun hide(id: String) { markers = markers.filterNot { it.id == id }; if (selectedMarker?.id == id) selectedMarker = null }

    fun seedDebug() {
        val now = System.currentTimeMillis()
        markers = listOf(
            SkimmerMarker("debug-psk", SkimmerMode.PSK31, 14_070_850, -61f, 24f, .91f, "CQ DEMO1", "DEMO1", "DEBUG FIXTURE", now, true),
            SkimmerMarker("debug-rtty", SkimmerMode.RTTY, 14_084_170, -68f, 18f, .84f, "CQ TEST2", "TEST2", "DEBUG FIXTURE", now, true),
        )
        status = "Deterministic fixtures · not received RF"
    }

    private fun decodeCandidate(marker: SkimmerMarker): SkimmerMarker {
        val source = iq ?: return marker
        val audio = iqLaneAudio(source.values, source.rate, marker.frequencyHz, source.centerHz, 48_000)
        if (audio.isEmpty()) return marker
        val result = runCatching {
            if (marker.mode == SkimmerMode.PSK31) NativeCore.digiDecodePsk31(audio, 1_000f)
            else NativeCore.digiCreate(48_000, 700f, false, 2_210f).let { handle ->
                if (handle == 0L) "{}" else try { NativeCore.digiFeedRtty(handle, audio) } finally { NativeCore.digiDestroy(handle) }
            }
        }.getOrDefault("{}")
        val text = runCatching { JSONObject(result).optString("text") }.getOrDefault("").takeLast(160)
        val token = Regex("\\b[A-Z0-9]{1,3}[0-9][A-Z0-9]{1,4}\\b").find(text.uppercase())?.value
        return marker.copy(text = text, callLikeToken = token, confidence = if (token == null) marker.confidence else max(marker.confidence, .82f),
            source = if (token == null) "LIVE FFT CANDIDATE" else "SHACKCQ ${marker.mode} DECODER", confirmed = token != null)
    }

    fun stop(reason: String = "Skimmers stopped") { enabledModes = emptySet(); markers = emptyList(); selectedMarker = null; iq = null; status = reason }
    fun close() { stop("Skimmers closed"); executor.shutdownNow() }
}

internal fun iqLaneAudio(iq: FloatArray, sourceRate: Int, candidateHz: Long, centerHz: Long, targetRate: Int): FloatArray {
    if (iq.size < 4 || iq.size % 2 != 0 || sourceRate <= 0) return FloatArray(0)
    val offset = (candidateHz - centerHz).toDouble()
    val mono = FloatArray(iq.size / 2)
    repeat(mono.size) { index ->
        val phase = 2.0 * PI * (offset - 1_000.0) * index / sourceRate
        val i = iq[index * 2]; val q = iq[index * 2 + 1]
        mono[index] = (i * cos(phase) + q * sin(phase)).toFloat()
    }
    return resampleTciAudio(mono, sourceRate, targetRate)
}

class PerModeTxAudioController(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("shackcq-sdr-tx-levels-v2", Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ShackCQ-TX-Level-Store").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private val supportedModes = setOf("LSB", "USB", "CW", "AM", "SAM", "NFM", "WFM", "DIGU", "DIGL", "DSB")
    var defaultLevel by mutableStateOf(preferences.getFloat("default", .5f).coerceIn(0f, 1f)); private set
    var levels by mutableStateOf(load()); private set
    var selectedMode by mutableStateOf("USB"); private set
    var pendingWrites by mutableStateOf(0); private set

    fun select(mode: String) { selectedMode = normalize(mode) }
    fun level(mode: String = selectedMode): TxAudioLevel {
        val key = normalize(mode)
        return resolveTxAudioLevel(key, defaultLevel, levels)
    }

    fun update(level: Float, mode: String = selectedMode) {
        val key = normalize(mode); levels = levels + (key to level.coerceIn(0f, 1f)); schedulePersist(key, levels.getValue(key))
    }

    fun clearOverride(mode: String = selectedMode) { val key = normalize(mode); levels = levels - key; schedulePersist(key, null) }
    fun updateDefault(level: Float) { defaultLevel = level.coerceIn(0f, 1f); schedulePersist("default", defaultLevel) }

    fun snapshot(profile: String, route: String, telemetryReady: Boolean): TxCalibrationSnapshot {
        val current = level()
        return TxCalibrationSnapshot(current.mode, current.level, current.inherited, profile.take(80), route.take(80),
            physicalAcceptance = false, telemetryReady = telemetryReady, sendEnabled = false)
    }

    private fun normalize(mode: String): String = mode.uppercase().takeIf(supportedModes::contains) ?: "USB"
    private fun schedulePersist(mode: String, value: Float?) {
        pending?.cancel(false); pendingWrites = 1
        pending = writer.schedule({
            val edit = preferences.edit()
            if (mode == "default") edit.putFloat("default", value ?: .5f)
            else if (value == null) edit.remove("mode_$mode") else edit.putFloat("mode_$mode", value)
            edit.apply(); pendingWrites = (pendingWrites - 1).coerceAtLeast(0)
        }, 250, TimeUnit.MILLISECONDS)
    }

    private fun load(): Map<String, Float> = supportedModes.mapNotNull { mode ->
        if (preferences.contains("mode_$mode")) mode to preferences.getFloat("mode_$mode", .5f).coerceIn(0f, 1f) else null
    }.toMap()

    override fun close() { pending?.cancel(false); writer.shutdownNow() }
}

class SdrOperationalV2(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("shackcq-sdr-operational-v2", Context.MODE_PRIVATE)
    private val store = SdrV2DerivedStore(context)
    val timeShift = ReceiveTimeShiftController(store)
    val skimmer = WidebandSkimmerController()
    val txLevels = PerModeTxAudioController(context)
    val spotBridge = ProtocolFeatureState.UNAVAILABLE_PROTOCOL
    val diversity = ProtocolFeatureState.UNAVAILABLE_PROTOCOL
    var linkMode by mutableStateOf(runCatching { ReceiverLinkMode.valueOf(preferences.getString("link_mode", "") ?: "") }
        .getOrDefault(ReceiverLinkMode.INDEPENDENT)); private set
    var scanBanks by mutableStateOf(loadBanks()); private set
    var selectedBankId by mutableStateOf(scanBanks.firstOrNull()?.id); private set
    var journal by mutableStateOf(store.journal()); private set
    var journalRetentionDays by mutableStateOf(preferences.getInt("journal_days", 30).coerceIn(7, 90)); private set
    var maximumJournalRows by mutableStateOf(preferences.getInt("journal_rows", 5_000).coerceIn(100, 50_000)); private set
    var recordPolicy by mutableStateOf(RecordOnHitPolicy(
        preRollSeconds = preferences.getInt("record_pre", 5), postRollSeconds = preferences.getInt("record_post", 5),
        maximumDurationSeconds = preferences.getInt("record_max_seconds", 30),
        dailyBytes = preferences.getLong("record_daily_bytes", 64L * 1024 * 1024),
        totalBytes = preferences.getLong("record_total_bytes", 256L * 1024 * 1024),
    ).validated()); private set
    var recordingState by mutableStateOf("STOPPED"); private set

    fun updateLinkMode(value: ReceiverLinkMode) { linkMode = value; preferences.edit().putString("link_mode", value.name).apply() }
    fun selectBank(id: String) { selectedBankId = scanBanks.firstOrNull { it.id == id }?.id }
    fun upsertBank(value: ScanBank) {
        val bank = value.validated(); scanBanks = (scanBanks.filterNot { it.id == bank.id } + bank).take(64); persistBanks()
    }

    fun linkedActions(sourceReceiver: Int, frequencyHz: Long?, mode: String?, receivers: List<TciReceiverSnapshot>): List<RadioPlatformAction> {
        return linkedReceiverActions(linkMode, sourceReceiver, frequencyHz, mode, receivers)
    }

    fun onPanadapterFrame(frame: PanadapterFrame, receiver: Int, centerHz: Long, source: String) {
        timeShift.capture(frame, receiver, centerHz, source)
        skimmer.analyze(frame, centerHz)
    }

    fun addJournal(row: ScannerJournalEntry) {
        store.addJournal(row, journalRetentionDays, maximumJournalRows); journal = store.journal()
    }

    fun recordOnHit(bank: ScanBank, frequencyHz: Long, mode: String, peakDb: Float?, dwellMillis: Long,
        audioCapture: String? = null): String? {
        val estimatedBytes = timeShift.selectedFrame?.let { 16L + it.trace.size * 4L } ?: 0L
        val startOfDay = Instant.now().epochSecond / 86_400L * 86_400L
        val withinQuota = estimatedBytes > 0 && store.bookmarkBytesSince(startOfDay) + estimatedBytes <= recordPolicy.dailyBytes &&
            store.bookmarkBytesSince() + estimatedBytes <= recordPolicy.totalBytes
        val capture = when (bank.recordOnHit) {
            RecordOnHitMode.OFF -> null
            RecordOnHitMode.AUDIO -> audioCapture
            RecordOnHitMode.IQ -> if (withinQuota && dwellMillis <= recordPolicy.maximumDurationSeconds * 1_000L)
                timeShift.bookmark("Scan hit · ${bank.name}")?.id else null
        }
        recordingState = when {
            capture != null -> "SAVED · ${bank.recordOnHit}"
            bank.recordOnHit == RecordOnHitMode.AUDIO -> "UNAVAILABLE · LOCAL AUDIO SOURCE NOT LISTENING"
            bank.recordOnHit == RecordOnHitMode.IQ && !withinQuota -> "STOPPED · QUOTA OR SOURCE UNAVAILABLE"
            bank.recordOnHit == RecordOnHitMode.IQ -> "STOPPED · MAX DURATION EXCEEDED"
            else -> "STOPPED"
        }
        addJournal(ScannerJournalEntry(bank = bank.name, source = "TCI SCANNER", frequencyHz = frequencyHz,
            mode = mode, peakDb = peakDb, durationMillis = dwellMillis, resumeReason = "Bounded dwell complete", captureId = capture))
        return capture
    }

    fun updateJournalRetention(days: Int, rows: Int) {
        journalRetentionDays = when { days <= 7 -> 7; days <= 30 -> 30; else -> 90 }
        maximumJournalRows = rows.coerceIn(100, 50_000)
        preferences.edit().putInt("journal_days", journalRetentionDays).putInt("journal_rows", maximumJournalRows).apply()
    }

    fun updateRecordPolicy(value: RecordOnHitPolicy) {
        recordPolicy = value.validated()
        preferences.edit().putInt("record_pre", recordPolicy.preRollSeconds).putInt("record_post", recordPolicy.postRollSeconds)
            .putInt("record_max_seconds", recordPolicy.maximumDurationSeconds).putLong("record_daily_bytes", recordPolicy.dailyBytes)
            .putLong("record_total_bytes", recordPolicy.totalBytes).apply()
    }

    fun seedDebug() { skimmer.seedDebug() }
    fun stopActive(reason: String = "Global Stop") { skimmer.stop(reason); timeShift.returnLive(); recordingState = "STOPPED" }

    private fun loadBanks(): List<ScanBank> = runCatching {
        val rows = JSONArray(preferences.getString("scan_banks_v2", "[]"))
        List(rows.length().coerceAtMost(64)) { index -> rows.getJSONObject(index).let { row ->
            val memories = row.optJSONArray("memories") ?: JSONArray()
            ScanBank(row.getString("id"), row.getString("name"), row.optBoolean("enabled", true),
                List(memories.length().coerceAtMost(2_000)) { item -> memories.getJSONObject(item).let { memory ->
                    ScanMemory(memory.getLong("hz"), memory.getString("mode"), memory.optInt("filter", 2_700),
                        memory.optString("name"), memory.optString("group"),
                        memory.optDouble("ctcss").takeIf { !memory.isNull("ctcss") }?.toFloat(),
                        memory.optInt("dcs").takeIf { !memory.isNull("dcs") }, memory.optBoolean("scan", true),
                        memory.optBoolean("priority"), memory.optString("note"), memory.optString("grid"),
                        memory.optLong("last_heard"), memory.optDouble("activity").toFloat()) } },
                priority = row.optJSONObject("priority")?.let { priority ->
                    ScanMemory(priority.getLong("hz"), priority.getString("mode"), priority.optInt("filter", 2_700))
                },
                thresholdDb = row.optDouble("threshold", -90.0).toFloat(), dwellMillis = row.optLong("dwell", 1_500),
                resumePolicy = runCatching { ScannerResumePolicy.valueOf(row.optString("resume")) }.getOrDefault(ScannerResumePolicy.CARRIER_DROP),
                recordOnHit = runCatching { RecordOnHitMode.valueOf(row.optString("record")) }.getOrDefault(RecordOnHitMode.OFF)).validated()
        } }
    }.getOrDefault(emptyList()).ifEmpty { listOf(
        ScanBank("calling", "Calling frequencies", memories = listOf(ScanMemory(14_074_000, "DIGU"), ScanMemory(7_074_000, "DIGU")),
            priority = ScanMemory(14_074_000, "DIGU")),
    ) }

    private fun persistBanks() {
        val rows = JSONArray(scanBanks.map { bank -> JSONObject().put("id", bank.id).put("name", bank.name).put("enabled", bank.enabled)
            .put("threshold", bank.thresholdDb.toDouble()).put("dwell", bank.dwellMillis).put("resume", bank.resumePolicy.name)
            .put("priority", bank.priority?.let { JSONObject().put("hz", it.frequencyHz).put("mode", it.mode).put("filter", it.filterHz) })
            .put("record", bank.recordOnHit.name).put("memories", JSONArray(bank.memories.map { memory -> JSONObject()
                .put("hz", memory.frequencyHz).put("mode", memory.mode).put("filter", memory.filterHz)
                .put("name", memory.name).put("group", memory.group).put("ctcss", memory.expectedCtcssHz)
                .put("dcs", memory.expectedDcs).put("scan", memory.scanEnabled).put("priority", memory.priority)
                .put("note", memory.note).put("grid", memory.locationGrid).put("last_heard", memory.lastHeardEpoch)
                .put("activity", memory.activityScore) })) })
        preferences.edit().putString("scan_banks_v2", rows.toString()).apply()
    }

    override fun close() { skimmer.close(); txLevels.close(); store.close() }
}
