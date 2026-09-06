package app.shackcq.mobile

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val NEURAL_OUTLOOK_MODEL_VERSION = "ShackCQ Empirical Outlook v1"
internal const val NEURAL_OUTLOOK_SHARED_HISTORY_KEY = "global|cluster-history|v1"
internal const val NEURAL_OUTLOOK_KEY_CAP = 24
internal const val NEURAL_OUTLOOK_PREDICTION_CAP = 100_000
internal val NEURAL_OUTLOOK_BANDS = listOf(
    "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m",
    "6m", "4m", "2m", "70cm", "23cm", "3cm",
)

internal enum class OutlookWindow(val minutes: Int) { MINUTES_30(30), MINUTES_60(60), MINUTES_120(120) }
internal enum class OutlookLabel { INSUFFICIENT_EVIDENCE, QUIET, BUILDING, FAVOURABLE, STRONG, DEGRADED }
internal enum class OutlookConfidence { LOW, MEDIUM, HIGH }
internal enum class OutlookVerification { PENDING, HIT, MISS, UNVERIFIABLE }
internal enum class OutlookCalibrationState { COLLECTING, PROVISIONAL, CALIBRATED, STALE_MODEL }
internal enum class OutlookSourceState { CURRENT, CACHED, STALE, DEGRADED, UNAVAILABLE, DISABLED }
internal enum class OutlookCandidateSource(val label: String) {
    CURRENTLY_OBSERVED("CURRENTLY OBSERVED"), SCHEDULED("SCHEDULED"), WATCHLIST("WATCHLIST"),
    NEEDED("NEEDED"), RECENT_PATTERN("RECENT PATTERN"),
}

internal data class OutlookEvidence(
    val id: String,
    val source: String,
    val epoch: Long,
    val callsign: String,
    val receiver: String = "",
    val band: String,
    val mode: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val snr: Int? = null,
    val distanceKm: Int? = null,
)

internal data class OutlookCandidate(
    val callsign: String,
    val source: OutlookCandidateSource,
    val band: String = "",
    val mode: String = "",
    val detail: String = "",
    val epoch: Long = 0,
)

internal data class NeuralOutlookInput(
    val stationProfileId: String,
    val stationCallsign: String,
    val stationGrid: String,
    val epoch: Long,
    val evidence: List<OutlookEvidence>,
    val sourceStates: Map<String, OutlookSourceState>,
    val sfi: Double? = null,
    val ssn: Double? = null,
    val aIndex: Double? = null,
    val kp: Double? = null,
    val xrayClass: String = "",
    val solarWind: Double? = null,
    val bz: Double? = null,
    val auroraActive: Boolean? = null,
    val tropoIndex: Int? = null,
    val lightningCount: Int = 0,
    val qsoSummary: NeuralLogSummary = NeuralLogSummary(),
    val candidates: List<OutlookCandidate> = emptyList(),
)

internal data class OutlookForecast(
    val id: String,
    val window: OutlookWindow,
    val targetStartEpoch: Long,
    val targetEndEpoch: Long,
    val band: String,
    val modeFamily: String,
    val row: Int,
    val column: Int,
    val supportScore: Int,
    val label: OutlookLabel,
    val confidence: OutlookConfidence,
    val calibratedHitRate: Int?,
    val calibrationSamples: Int,
    val sourceCount: Int,
    val baselineSamples: Int,
    val reasons: List<String>,
    val generatedEpoch: Long,
    val currentObservations: Int = 0,
    val contributingSources: Set<String> = emptySet(),
)

internal data class OutlookWorldCell(
    val row: Int,
    val column: Int,
    val latitude: Double,
    val longitude: Double,
    val forecast: OutlookForecast,
)

internal data class OutlookCalibration(
    val state: OutlookCalibrationState = OutlookCalibrationState.COLLECTING,
    val verified: Int = 0,
    val hits: Int = 0,
    val hitRate: Int? = null,
) {
    val label: String get() = if (hitRate == null) "Calibration collecting · $verified verified"
        else "Empirical hit rate · $hitRate% · $verified samples"
}

internal data class NeuralOutlookSnapshot(
    val modelVersion: String = NEURAL_OUTLOOK_MODEL_VERSION,
    val generatedEpoch: Long = 0,
    val stationKey: String = "",
    val selectedWindow: OutlookWindow = OutlookWindow.MINUTES_60,
    val selectedBand: String = "20m",
    val forecasts: List<OutlookForecast> = emptyList(),
    val topBands: List<OutlookForecast> = emptyList(),
    val world: List<OutlookWorldCell> = emptyList(),
    val candidates: List<OutlookCandidate> = emptyList(),
    val calibration: OutlookCalibration = OutlookCalibration(),
    val sources: Map<String, OutlookSourceState> = emptyMap(),
    val sourceAgesSeconds: Map<String, Long> = emptyMap(),
    val partialBaseline: Boolean = true,
    val status: String = "Calibration collecting",
)

internal object ShackCQEmpiricalOutlookV1 {
    const val BASELINE_MAX = 20
    const val ANOMALY_MAX = 25
    const val TREND_MAX = 15
    const val SOURCE_MAX = 20
    const val DISTANCE_MAX = 10
    const val CONTEXT_MIN = -10
    const val CONTEXT_MAX = 10
    const val MIN_BASELINE_BUCKETS = 8
    const val HISTORY_DAYS = 56

    fun label(score: Int, sufficient: Boolean, degraded: Boolean): OutlookLabel = when {
        !sufficient -> OutlookLabel.INSUFFICIENT_EVIDENCE
        degraded -> OutlookLabel.DEGRADED
        score >= 78 -> OutlookLabel.STRONG
        score >= 62 -> OutlookLabel.FAVOURABLE
        score >= 45 -> OutlookLabel.BUILDING
        else -> OutlookLabel.QUIET
    }

    fun contextAdjustment(input: NeuralOutlookInput, band: String, longitude: Double? = null, targetEpoch: Long = input.epoch): Int {
        var score = 0
        if (band in setOf("160m", "80m", "60m", "40m") && (input.kp ?: 0.0) >= 5.0) score -= 5
        if (band in setOf("20m", "17m", "15m", "12m", "10m") && (input.sfi ?: 0.0) >= 120.0) score += 4
        if (band in setOf("6m", "4m", "2m", "70cm") && (input.tropoIndex ?: 0) >= 7) score += 5
        if (band in setOf("6m", "4m", "2m", "70cm") && input.auroraActive == true) score += 3
        if (input.lightningCount >= 20 && band in setOf("160m", "80m", "60m", "40m")) score -= 2
        if (longitude != null && band in setOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m")) {
            val localSolarHour = ((targetEpoch / 3_600.0 + longitude / 15.0) % 24.0 + 24.0) % 24.0
            if (min(kotlin.math.abs(localSolarHour - 6.0), kotlin.math.abs(localSolarHour - 18.0)) <= 1.0) score += 2
        }
        return score.coerceIn(CONTEXT_MIN, CONTEXT_MAX)
    }
}

internal fun utcQuarterHourDistance(firstEpoch: Long, secondEpoch: Long): Int {
    val first = ((firstEpoch / 900L) % 96L).toInt()
    val second = ((secondEpoch / 900L) % 96L).toInt()
    return min((first - second + 96) % 96, (second - first + 96) % 96)
}

internal fun outlookBandSupported(band: String, baselineSamples: Int, currentObservations: Int, sources: Int): Boolean = when (band) {
    "23cm", "3cm" -> baselineSamples >= 16 && currentObservations >= 4 && sources >= 2
    "4m", "2m", "70cm" -> baselineSamples >= 8 && currentObservations >= 2
    else -> baselineSamples >= ShackCQEmpiricalOutlookV1.MIN_BASELINE_BUCKETS
}

internal fun calibratedOutlookRate(familyVerified: Int, binVerified: Int, hits: Int): Int? =
    if (familyVerified >= 40 && binVerified >= 15) (((hits + 1.0) / (binVerified + 2.0)) * 100).roundToInt() else null

internal fun outlookVerification(uniqueCallsInOneSource: Int, sameCallSourceCount: Int, validCoverage: Boolean): OutlookVerification = when {
    uniqueCallsInOneSource >= 2 || sameCallSourceCount >= 2 -> OutlookVerification.HIT
    !validCoverage -> OutlookVerification.UNVERIFIABLE
    else -> OutlookVerification.MISS
}

internal fun outlookPersistenceSlot(epoch: Long): Long = ((epoch + 899L) / 900L) * 900L

internal fun mergeOutlookKeys(vararg values: String): String = values.asSequence().flatMap { it.split(',').asSequence() }
    .map(String::trim).filter(String::isNotBlank).distinct().sorted().take(NEURAL_OUTLOOK_KEY_CAP).joinToString(",")

internal fun outlookPersistenceEligible(value: OutlookForecast): Boolean =
    value.row == -1 && value.column == -1 && value.label != OutlookLabel.INSUFFICIENT_EVIDENCE &&
        value.contributingSources.isNotEmpty() &&
        outlookBandSupported(value.band, value.baselineSamples, value.currentObservations, value.contributingSources.size)

internal fun verifyOutlookEvidence(callsBySource: Map<String, Set<String>>, contributingSources: Set<String>,
    coveredSources: Set<String>): OutlookVerification {
    val accepted = callsBySource.filterKeys { it in contributingSources }
    val sourceCountByCall = linkedMapOf<String, Int>()
    accepted.values.forEach { calls -> calls.forEach { call -> sourceCountByCall[call] = (sourceCountByCall[call] ?: 0) + 1 } }
    return outlookVerification(accepted.values.maxOfOrNull(Set<String>::size) ?: 0,
        sourceCountByCall.values.maxOrNull() ?: 0, contributingSources.any { it in coveredSources })
}

internal fun selectedOutlookForecast(values: List<OutlookForecast>, window: OutlookWindow, band: String): OutlookForecast? =
    values.firstOrNull { it.window == window && it.band == band }

internal fun createNeuralOutlookSchema(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS evidence_bucket(
        bucket_start INTEGER NOT NULL, station_key TEXT NOT NULL, station_call TEXT NOT NULL, station_grid TEXT NOT NULL,
        band TEXT NOT NULL, mode_family TEXT NOT NULL, region_row INTEGER NOT NULL, region_col INTEGER NOT NULL,
        source TEXT NOT NULL, observation_count INTEGER NOT NULL, unique_call_count INTEGER NOT NULL,
        unique_receiver_count INTEGER NOT NULL, snr_count INTEGER NOT NULL, snr_sum INTEGER NOT NULL,
        distance_count INTEGER NOT NULL, distance_sum INTEGER NOT NULL, call_keys TEXT NOT NULL,
        receiver_keys TEXT NOT NULL DEFAULT '', source_state TEXT NOT NULL,
        PRIMARY KEY(station_key,bucket_start,band,mode_family,region_row,region_col,source))""")
    db.execSQL("CREATE INDEX IF NOT EXISTS evidence_match_idx ON evidence_bucket(station_key,band,mode_family,region_row,region_col,bucket_start)")
    db.execSQL("CREATE INDEX IF NOT EXISTS evidence_region_match_idx ON evidence_bucket(station_key,band,region_row,region_col,bucket_start)")
    db.execSQL("CREATE INDEX IF NOT EXISTS evidence_global_match_idx ON evidence_bucket(station_key,bucket_start,band)")
    db.execSQL("CREATE INDEX IF NOT EXISTS evidence_retention_idx ON evidence_bucket(bucket_start)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS outlook_prediction(
        id TEXT PRIMARY KEY, model_version TEXT NOT NULL, created_epoch INTEGER NOT NULL, target_start INTEGER NOT NULL,
        target_end INTEGER NOT NULL, window_minutes INTEGER NOT NULL, station_key TEXT NOT NULL, band TEXT NOT NULL,
        mode_family TEXT NOT NULL, region_row INTEGER NOT NULL, region_col INTEGER NOT NULL, raw_score INTEGER NOT NULL,
        label TEXT NOT NULL, confidence TEXT NOT NULL, calibrated_probability INTEGER, calibration_samples INTEGER NOT NULL,
        source_mask TEXT NOT NULL, reasons TEXT NOT NULL, verification_state TEXT NOT NULL, verified_epoch INTEGER NOT NULL DEFAULT 0)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS outlook_prediction_verify_idx ON outlook_prediction(verification_state,target_end)")
    db.execSQL("CREATE INDEX IF NOT EXISTS outlook_prediction_retention_idx ON outlook_prediction(created_epoch)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS outlook_calibration(
        model_version TEXT NOT NULL, window_minutes INTEGER NOT NULL, band_family TEXT NOT NULL, score_bin INTEGER NOT NULL,
        verified_count INTEGER NOT NULL, hit_count INTEGER NOT NULL, unverifiable_count INTEGER NOT NULL, updated_epoch INTEGER NOT NULL,
        PRIMARY KEY(model_version,window_minutes,band_family,score_bin))""")
    db.execSQL("CREATE TABLE IF NOT EXISTS outlook_meta(meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)")
    val cutoff = Instant.now().epochSecond
    db.execSQL("INSERT OR IGNORE INTO outlook_meta(meta_key,meta_value) VALUES('backfill_rowid','0')")
    db.execSQL("INSERT OR IGNORE INTO outlook_meta(meta_key,meta_value) VALUES('backfill_cutoff','$cutoff')")
    db.execSQL("INSERT OR IGNORE INTO outlook_meta(meta_key,meta_value) VALUES('last_compaction_epoch','0')")
}

internal fun upgradeNeuralOutlookSchemaV5(db: SQLiteDatabase) {
    val columns = db.rawQuery("PRAGMA table_info(evidence_bucket)", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }
    if ("receiver_keys" !in columns) db.execSQL("ALTER TABLE evidence_bucket ADD COLUMN receiver_keys TEXT NOT NULL DEFAULT ''")
    db.execSQL("UPDATE OR IGNORE evidence_bucket SET station_key=?,station_call='',station_grid='' WHERE source_state='HISTORICAL'",
        arrayOf(NEURAL_OUTLOOK_SHARED_HISTORY_KEY))
    db.delete("evidence_bucket", "source_state='HISTORICAL' AND station_key<>?", arrayOf(NEURAL_OUTLOOK_SHARED_HISTORY_KEY))
    db.execSQL("UPDATE outlook_prediction SET verification_state='UNVERIFIABLE',verified_epoch=? WHERE verification_state='PENDING'",
        arrayOf(Instant.now().epochSecond))
    db.execSQL("INSERT OR IGNORE INTO outlook_meta(meta_key,meta_value) VALUES('last_compaction_epoch','0')")
}

private data class EvidenceAggregate(
    val bucket: Long, val band: String, val mode: String, val row: Int, val column: Int, val source: String,
    val count: Int, val calls: Int, val receivers: Int, val snrCount: Int, val snrSum: Int,
    val distanceCount: Int, val distanceSum: Int, val callKeys: String, val receiverKeys: String, val state: OutlookSourceState,
)

private data class OutlookMetrics(
    val current: Int, val previous: Int, val sources: Int, val calls: Int, val receivers: Int,
    val distanceCount: Int, val distanceSum: Int, val staleSources: Int, val sourceFamilies: Set<String>,
)

private data class BaselineMetrics(val expected: Double, val samples: Int, val observed: Int)

private data class PendingOutlookInput(
    val input: NeuralOutlookInput,
    val immediate: Boolean,
    val generation: Long,
)

internal class NeuralOutlookController(private val store: NeuralDxStore, private val clock: () -> Long = { Instant.now().epochSecond }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var foreground = true
    private var closed = false
    @Volatile private var pending: PendingOutlookInput? = null
    private var worker: Job? = null
    private var lastWriteEpoch = 0L
    private var lastComputeEpoch = 0L
    private var nextBackfillEpoch = 0L
    private var backfillPending = true
    private var lastInput: NeuralOutlookInput? = null
    @Volatile private var generation = 0L
    private var environmentXray = ""
    private var environmentSolarWind: Double? = null
    private var environmentBz: Double? = null
    private var environmentAurora: Boolean? = null
    var snapshot by mutableStateOf(NeuralOutlookSnapshot()); private set
    var selectedWindow by mutableStateOf(OutlookWindow.MINUTES_60); private set
    var selectedBand by mutableStateOf("20m"); private set

    fun submit(input: NeuralOutlookInput, immediate: Boolean = false) {
        if (closed) return
        val normalized = input.copy(
            stationCallsign = input.stationCallsign.trim().uppercase(Locale.US),
            stationGrid = input.stationGrid.trim().uppercase(Locale.US),
            evidence = input.evidence.distinctBy { it.source to it.id }.take(2_000),
            candidates = input.candidates.distinctBy { it.source to it.callsign }.take(48),
            xrayClass = input.xrayClass.ifBlank { environmentXray },
            solarWind = input.solarWind ?: environmentSolarWind,
            bz = input.bz ?: environmentBz,
            auroraActive = input.auroraActive ?: environmentAurora,
        )
        val effectiveImmediate = immediate || lastInput?.sourceStates != normalized.sourceStates ||
            lastInput?.stationKey() != normalized.stationKey()
        lastInput = normalized
        val inputGeneration = ++generation
        pending = PendingOutlookInput(normalized, effectiveImmediate, inputGeneration)
        if (!foreground && !effectiveImmediate) return
        startWorker()
        wake.trySend(Unit)
    }

    fun select(window: OutlookWindow, band: String) {
        selectedWindow = window
        selectedBand = band.takeIf { it in NEURAL_OUTLOOK_BANDS } ?: "20m"
        lastInput?.let { submit(it.copy(epoch = clock()), immediate = true) }
    }

    fun updateEnvironment(xrayClass: String, solarWind: Double?, bz: Double?, auroraActive: Boolean?) {
        environmentXray = xrayClass
        environmentSolarWind = solarWind
        environmentBz = bz
        environmentAurora = auroraActive
        lastInput?.let { input -> submit(input.copy(epoch = clock(), xrayClass = xrayClass,
            solarWind = solarWind, bz = bz, auroraActive = auroraActive), immediate = true) }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        lastInput?.let { submit(it.copy(epoch = clock()), immediate = true) }
        wake.trySend(Unit)
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (isActive && !closed) {
                    val now = clock()
                    val heartbeatDue = foreground && lastInput != null && (lastComputeEpoch == 0L || now - lastComputeEpoch >= 300L)
                    val backfillDue = foreground && backfillPending && lastInput != null && now >= nextBackfillEpoch
                    if (pending == null && !heartbeatDue && !backfillDue) {
                        val heartbeatWait = if (foreground && lastInput != null) (300L - (now - lastComputeEpoch)).coerceAtLeast(1L) else 3_600L
                        val backfillWait = if (foreground && backfillPending && lastInput != null)
                            (nextBackfillEpoch - now).coerceAtLeast(1L) else 3_600L
                        withTimeoutOrNull(min(heartbeatWait, backfillWait) * 1_000L) { wake.receive() }
                        continue
                    }
                    val work = pending.also { pending = null }
                    val input = (work?.input ?: lastInput)?.copy(epoch = now) ?: continue
                    try {
                        if (work != null || heartbeatDue) {
                            if (lastWriteEpoch > 0 && now - lastWriteEpoch < 60L) delay((60L - (now - lastWriteEpoch)) * 1_000L)
                            ingest(input)
                            lastWriteEpoch = clock()
                        }
                        if (foreground && (work != null || backfillDue)) {
                            backfillPending = backfillBatch()
                            nextBackfillEpoch = clock() + 5L
                        }
                        verifyPredictions(100)
                        val shouldCompute = work?.immediate == true || heartbeatDue || lastComputeEpoch == 0L || now - lastComputeEpoch >= 300L
                        if (shouldCompute) {
                            val calculated = calculate(input)
                            val publishGeneration = work?.generation ?: generation
                            if (publishGeneration == generation) withContext(Dispatchers.Main) { snapshot = calculated }
                            lastComputeEpoch = clock()
                        }
                        compactPredictions(clock())
                    } catch (failure: CancellationException) {
                        if (work != null) pending = pending ?: work
                        throw failure
                    } catch (failure: Throwable) {
                        if (work != null) pending = pending ?: work
                        val error = failure.javaClass.simpleName.take(48).ifBlank { "storage error" }
                        withContext(Dispatchers.Main) { snapshot = snapshot.copy(status = "Outlook retrying · $error") }
                        nextBackfillEpoch = clock() + 5L
                        withTimeoutOrNull(5_000L) { wake.receive() }
                    }
                }
            } finally {
                worker = null
            }
        }
    }

    private fun NeuralOutlookInput.stationKey(): String = listOf(
        stationProfileId.ifBlank { "local" }, stationCallsign.uppercase(Locale.US), stationGrid.uppercase(Locale.US),
    ).joinToString("|")

    private fun modeFamily(mode: String): String = when (mode.uppercase(Locale.US)) {
        "CW" -> "CW"
        "SSB", "USB", "LSB", "AM", "FM" -> "PHONE"
        "FT8", "FT4", "FT2", "RTTY", "PSK31", "PSK", "JT65", "WSPR" -> "DIGITAL"
        else -> "OTHER"
    }

    private fun region(latitude: Double?, longitude: Double?): Pair<Int, Int> {
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)) return -1 to -1
        return ((75.0 - latitude) / 30.0).toInt().coerceIn(0, 5) to
            ((longitude + 180.0) / 30.0).toInt().coerceIn(0, 11)
    }

    private fun ingest(input: NeuralOutlookInput) {
        val stationKey = input.stationKey()
        val grouped = input.evidence.asSequence().filter { it.band in NEURAL_OUTLOOK_BANDS }
            .groupBy { evidence ->
                val cell = region(evidence.latitude, evidence.longitude)
                listOf((evidence.epoch / 300L) * 300L, evidence.band, modeFamily(evidence.mode), cell.first, cell.second, evidence.source.uppercase(Locale.US))
            }
        val aggregates = grouped.map { (key, rows) -> EvidenceAggregate(
            key[0] as Long, key[1] as String, key[2] as String, key[3] as Int, key[4] as Int, key[5] as String,
            rows.size, rows.map { it.callsign.uppercase(Locale.US) }.filter(String::isNotBlank).distinct().size,
            rows.map { it.receiver.uppercase(Locale.US) }.filter(String::isNotBlank).distinct().size,
            rows.count { it.snr != null }, rows.sumOf { it.snr ?: 0 }, rows.count { it.distanceKm != null },
            rows.sumOf { it.distanceKm ?: 0 }, rows.map { callKey(it.callsign) }.filter(String::isNotBlank).distinct().take(24).joinToString(","),
            rows.map { callKey(it.receiver) }.filter(String::isNotBlank).distinct().take(NEURAL_OUTLOOK_KEY_CAP).joinToString(","),
            input.sourceStates[key[5] as String] ?: OutlookSourceState.CURRENT,
        ) }
        val currentBucket = (input.epoch / 300L) * 300L
        val states = input.sourceStates.map { (source, state) -> EvidenceAggregate(
            currentBucket, "*", "*", -2, -2, source.uppercase(Locale.US), 0, 0, 0, 0, 0, 0, 0, "", "", state,
        ) }
        val db = store.outlookWritableDatabase()
        db.beginTransaction()
        try {
            (aggregates + states).forEach { row ->
                val values = ContentValues().apply {
                    put("bucket_start", row.bucket); put("station_key", stationKey); put("station_call", input.stationCallsign)
                    put("station_grid", input.stationGrid); put("band", row.band); put("mode_family", row.mode)
                    put("region_row", row.row); put("region_col", row.column); put("source", row.source)
                    put("observation_count", row.count); put("unique_call_count", row.calls); put("unique_receiver_count", row.receivers)
                    put("snr_count", row.snrCount); put("snr_sum", row.snrSum); put("distance_count", row.distanceCount)
                    put("distance_sum", row.distanceSum); put("source_state", row.state.name)
                    put("call_keys", row.callKeys)
                    put("receiver_keys", row.receiverKeys)
                }
                db.insertWithOnConflict("evidence_bucket", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun backfillBatch(): Boolean {
        val db = store.outlookWritableDatabase()
        val progressValue = meta(db, "backfill_rowid")
        if (progressValue == "complete") return false
        val progress = progressValue.toLongOrNull() ?: 0L
        val cutoff = meta(db, "backfill_cutoff").toLongOrNull() ?: 0L
        data class Row(val rowId: Long, val epoch: Long, val call: String, val spotter: String, val band: String, val mode: String,
            val latitude: Double, val longitude: Double)
        val rows = mutableListOf<Row>()
        db.rawQuery("""SELECT rowid,ts,call,spotter,band,mode,latitude,longitude FROM spot
            WHERE rowid>? AND ts<=? ORDER BY rowid LIMIT 1000""", arrayOf(progress.toString(), cutoff.toString())).use { c ->
            while (c.moveToNext()) rows += Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getDouble(6), c.getDouble(7))
        }
        if (rows.isEmpty()) { setMeta(db, "backfill_rowid", "complete"); return false }
        val grouped = rows.groupBy { row ->
            val cell = region(row.latitude, row.longitude)
            listOf((row.epoch / 300L) * 300L, row.band, modeFamily(row.mode), cell.first, cell.second)
        }
        db.beginTransaction()
        try {
            grouped.forEach { (key, values) ->
                val whereArgs = arrayOf(NEURAL_OUTLOOK_SHARED_HISTORY_KEY, key[0].toString(), key[1].toString(), key[2].toString(),
                    key[3].toString(), key[4].toString(), "CLUSTER")
                var priorCount = 0; var priorCalls = ""; var priorReceivers = ""
                db.rawQuery("""SELECT observation_count,call_keys,receiver_keys FROM evidence_bucket WHERE station_key=?
                    AND bucket_start=? AND band=? AND mode_family=? AND region_row=? AND region_col=? AND source=?""", whereArgs).use { cursor ->
                    if (cursor.moveToFirst()) { priorCount = cursor.getInt(0); priorCalls = cursor.getString(1); priorReceivers = cursor.getString(2) }
                }
                val callKeys = mergeOutlookKeys(priorCalls, values.joinToString(",") { callKey(it.call) })
                val receiverKeys = mergeOutlookKeys(priorReceivers, values.joinToString(",") { callKey(it.spotter) })
                val row = ContentValues().apply {
                    put("bucket_start", key[0] as Long); put("station_key", NEURAL_OUTLOOK_SHARED_HISTORY_KEY)
                    put("station_call", ""); put("station_grid", ""); put("band", key[1] as String); put("mode_family", key[2] as String)
                    put("region_row", key[3] as Int); put("region_col", key[4] as Int); put("source", "CLUSTER")
                    put("observation_count", priorCount + values.size); put("unique_call_count", callKeys.split(',').count(String::isNotBlank))
                    put("unique_receiver_count", receiverKeys.split(',').count(String::isNotBlank)); put("snr_count", 0); put("snr_sum", 0)
                    put("distance_count", 0); put("distance_sum", 0); put("call_keys", callKeys); put("receiver_keys", receiverKeys)
                    put("source_state", "HISTORICAL")
                }
                db.insertWithOnConflict("evidence_bucket", null, row, SQLiteDatabase.CONFLICT_REPLACE)
            }
            setMeta(db, "backfill_rowid", rows.last().rowId.toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return rows.size == 1_000
    }

    private fun calculate(input: NeuralOutlookInput): NeuralOutlookSnapshot {
        val stationKey = input.stationKey()
        val now = (input.epoch / 300L) * 300L
        val globalBaselines = globalBaselines(stationKey, now)
        val allGlobalForecasts = OutlookWindow.entries.flatMap { window -> NEURAL_OUTLOOK_BANDS.map { band ->
            forecast(input, stationKey, now, window, band, -1, -1, globalBaselines[window to band])
        } }
        val topBands = allGlobalForecasts.filterNot { it.label == OutlookLabel.INSUFFICIENT_EVIDENCE }
        val selectedTop = topBands.filter { it.window == selectedWindow }.sortedByDescending(OutlookForecast::supportScore).take(3)
        val cells = buildList {
            for (row in 0..5) for (column in 0..11) add(OutlookWorldCell(row, column,
                75.0 - row * 30.0 - 15.0, -180.0 + column * 30.0 + 15.0,
                forecast(input, stationKey, now, selectedWindow, selectedBand, row, column)))
        }.take(72)
        val selectedForecast = selectedOutlookForecast(allGlobalForecasts, selectedWindow, selectedBand)
        val calibration = selectedForecast?.takeIf { it.label != OutlookLabel.INSUFFICIENT_EVIDENCE }
            ?.let { calibration(selectedWindow, selectedBand, it.supportScore) }
            ?: OutlookCalibration()
        val rankedCandidates = (input.candidates + recentPatternCandidates(input.epoch))
            .distinctBy { it.source to it.callsign }.sortedWith(
                compareBy<OutlookCandidate> { it.source.ordinal }.thenByDescending(OutlookCandidate::epoch)).take(12)
        return NeuralOutlookSnapshot(
            generatedEpoch = input.epoch, stationKey = stationKey, selectedWindow = selectedWindow, selectedBand = selectedBand,
            forecasts = topBands, topBands = selectedTop, world = cells, candidates = rankedCandidates,
            calibration = calibration, sources = input.sourceStates, partialBaseline = meta(store.outlookReadableDatabase(), "backfill_rowid") != "complete",
            sourceAgesSeconds = sourceAges(input, stationKey),
            status = if (selectedTop.isEmpty()) "Insufficient evidence" else "Empirical outlook · observed support",
        )
    }

    private fun recentPatternCandidates(now: Long): List<OutlookCandidate> {
        val slot = (now / 900L) % 96L
        val rows = mutableListOf<OutlookCandidate>()
        store.outlookReadableDatabase().rawQuery("""SELECT call,band,mode,COUNT(*),MAX(ts) FROM spot
            WHERE ts>=? AND ts<? AND MIN(ABS(((ts / 900) % 96) - $slot),
                96 - ABS(((ts / 900) % 96) - $slot)) <= 2
            GROUP BY call,band,mode HAVING COUNT(*)>=3 ORDER BY COUNT(*) DESC,MAX(ts) DESC LIMIT 12""",
            arrayOf((now - ShackCQEmpiricalOutlookV1.HISTORY_DAYS * 86_400L).toString(), (now - 1_800L).toString())).use { cursor ->
            while (cursor.moveToNext()) rows += OutlookCandidate(cursor.getString(0), OutlookCandidateSource.RECENT_PATTERN,
                cursor.getString(1), cursor.getString(2), "${cursor.getInt(3)} recurring UTC-matched observations", cursor.getLong(4))
        }
        return rows
    }

    private fun forecast(input: NeuralOutlookInput, stationKey: String, now: Long, window: OutlookWindow,
        band: String, row: Int, column: Int, baselineOverride: BaselineMetrics? = null): OutlookForecast {
        val targetStart = now + 300L
        val targetEnd = now + window.minutes * 60L
        val baseline = baselineOverride ?: baseline(stationKey, band, row, column, targetStart + window.minutes * 30L)
        val metrics = currentMetrics(stationKey, band, row, column, now)
        val supportedBand = outlookBandSupported(band, baseline.samples, metrics.current, metrics.sources)
        val baselinePoints = min(ShackCQEmpiricalOutlookV1.BASELINE_MAX,
            ((baseline.expected / 4.0) * ShackCQEmpiricalOutlookV1.BASELINE_MAX).roundToInt())
        val ratio = if (baseline.expected > 0.0) metrics.current / baseline.expected else 0.0
        val anomalyPoints = min(ShackCQEmpiricalOutlookV1.ANOMALY_MAX, (ratio * 10.0).roundToInt())
        val trendPoints = ((metrics.current - metrics.previous).coerceAtLeast(0) * 3).coerceAtMost(ShackCQEmpiricalOutlookV1.TREND_MAX)
        val sourcePoints = (metrics.sources * 6 + min(metrics.calls, 6) + min(metrics.receivers, 4))
            .coerceAtMost(ShackCQEmpiricalOutlookV1.SOURCE_MAX)
        val distancePoints = if (metrics.distanceCount == 0) 0 else min(ShackCQEmpiricalOutlookV1.DISTANCE_MAX,
            metrics.distanceSum / metrics.distanceCount / 1_500)
        val longitude = column.takeIf { row >= 0 }?.let { -180.0 + it * 30.0 + 15.0 }
        val context = ShackCQEmpiricalOutlookV1.contextAdjustment(input, band, longitude, targetStart + window.minutes * 30L)
        val penalty = metrics.staleSources * 5 + input.sourceStates.values.count { it in setOf(OutlookSourceState.DEGRADED, OutlookSourceState.UNAVAILABLE) } * 2
        val score = (baselinePoints + anomalyPoints + trendPoints + sourcePoints + distancePoints + context - penalty).coerceIn(0, 100)
        val degraded = metrics.sources > 0 && metrics.staleSources >= metrics.sources
        val confidence = when {
            baseline.samples >= 24 && metrics.sources >= 3 && !degraded -> OutlookConfidence.HIGH
            baseline.samples >= 8 && metrics.sources >= 1 -> OutlookConfidence.MEDIUM
            else -> OutlookConfidence.LOW
        }
        val calibration = calibratedRate(window, band, score)
        val reasons = buildList {
            if (baseline.samples < 8) add("Insufficient time-matched baseline · ${baseline.samples} buckets")
            else add("${baseline.samples} UTC-matched historical buckets · expected ${"%.1f".format(Locale.US, baseline.expected)}")
            add("${metrics.current} recent observations · ${metrics.sources} independent sources")
            if (metrics.current > metrics.previous) add("Short-term activity is building")
            if (context != 0) add("Environmental context ${if (context > 0) "+" else ""}$context")
            if (penalty > 0) add("Freshness/degradation penalty −$penalty")
        }.take(4)
        val label = ShackCQEmpiricalOutlookV1.label(score, supportedBand, degraded)
        val id = stableId(NEURAL_OUTLOOK_MODEL_VERSION, stationKey, targetStart.toString(), window.minutes.toString(), band, row.toString(), column.toString())
        val result = OutlookForecast(id, window, targetStart, targetEnd, band, "ALL", row, column, score, label,
            confidence, calibration.first, calibration.second, metrics.sources, baseline.samples, reasons, input.epoch,
            metrics.current, metrics.sourceFamilies)
        persistEligiblePrediction(result, stationKey)
        return result
    }

    private fun globalBaselines(stationKey: String, now: Long): Map<Pair<OutlookWindow, String>, BaselineMetrics> = buildMap {
        OutlookWindow.entries.forEach { window ->
            val targetEpoch = now + 300L + window.minutes * 30L
            val since = targetEpoch - ShackCQEmpiricalOutlookV1.HISTORY_DAYS * 86_400L
            val targetSlot = (targetEpoch / 900L) % 96L
            val totals = linkedMapOf<String, Pair<Int, Int>>()
            store.outlookReadableDatabase().rawQuery("""SELECT band,bucket_start,SUM(observation_count) FROM evidence_bucket
                WHERE station_key IN (?,?) AND bucket_start>=? AND bucket_start<?
                AND MIN(ABS(((bucket_start / 900) % 96) - $targetSlot),
                    96 - ABS(((bucket_start / 900) % 96) - $targetSlot)) <= 2
                GROUP BY band,bucket_start""", arrayOf(stationKey, NEURAL_OUTLOOK_SHARED_HISTORY_KEY, since.toString(), targetEpoch.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val band = cursor.getString(0)
                    val prior = totals[band] ?: (0 to 0)
                    totals[band] = prior.first + cursor.getInt(2) to prior.second + 1
                }
            }
            NEURAL_OUTLOOK_BANDS.forEach { band ->
                val aggregate = totals[band] ?: (0 to 0)
                put(window to band, BaselineMetrics(
                    if (aggregate.second == 0) 0.0 else aggregate.first.toDouble() / aggregate.second,
                    aggregate.second, aggregate.first))
            }
        }
    }

    private fun baseline(stationKey: String, band: String, row: Int, column: Int, targetEpoch: Long): BaselineMetrics {
        val since = targetEpoch - ShackCQEmpiricalOutlookV1.HISTORY_DAYS * 86_400L
        var total = 0; val buckets = linkedSetOf<Long>()
        val regionSql = if (row < 0) "" else " AND region_row=? AND region_col=?"
        val targetSlot = (targetEpoch / 900L) % 96L
        val args = mutableListOf(stationKey, NEURAL_OUTLOOK_SHARED_HISTORY_KEY, band, since.toString(), targetEpoch.toString()).apply {
            if (row >= 0) { add(row.toString()); add(column.toString()) }
        }.toTypedArray()
        store.outlookReadableDatabase().rawQuery("""SELECT bucket_start,SUM(observation_count) FROM evidence_bucket
            WHERE station_key IN (?,?) AND band=? AND bucket_start>=? AND bucket_start<?$regionSql
            AND MIN(ABS(((bucket_start / 900) % 96) - $targetSlot),
                96 - ABS(((bucket_start / 900) % 96) - $targetSlot)) <= 2
            GROUP BY bucket_start""", args).use { c ->
            while (c.moveToNext()) {
                val bucket = c.getLong(0)
                buckets += bucket; total += c.getInt(1)
            }
        }
        return BaselineMetrics(if (buckets.isEmpty()) 0.0 else total.toDouble() / buckets.size, buckets.size, total)
    }

    private fun currentMetrics(stationKey: String, band: String, row: Int, column: Int, now: Long): OutlookMetrics {
        var current = 0; var previous = 0; var distanceCount = 0; var distanceSum = 0
        val sources = linkedSetOf<String>(); val calls = linkedSetOf<String>(); val receivers = linkedSetOf<String>()
        val staleSources = linkedSetOf<String>()
        val regionSql = if (row < 0) "" else " AND region_row=? AND region_col=?"
        val args = mutableListOf(stationKey, band, (now - 1_800L).toString(), now.toString()).apply {
            if (row >= 0) { add(row.toString()); add(column.toString()) }
        }.toTypedArray()
        store.outlookReadableDatabase().rawQuery("""SELECT bucket_start,source,observation_count,call_keys,
            receiver_keys,distance_count,distance_sum,source_state FROM evidence_bucket
            WHERE station_key=? AND band=? AND bucket_start>=? AND bucket_start<=?$regionSql""", args).use { c ->
            while (c.moveToNext()) {
                val family = sourceFamily(c.getString(1))
                if (c.getLong(0) >= now - 900L) {
                    current += c.getInt(2)
                    if (c.getInt(2) > 0) sources += family
                } else previous += c.getInt(2)
                calls += c.getString(3).orEmpty().split(',').filter(String::isNotBlank)
                receivers += c.getString(4).orEmpty().split(',').filter(String::isNotBlank)
                distanceCount += c.getInt(5); distanceSum += c.getInt(6)
                if (c.getString(7) in setOf("STALE", "DEGRADED")) staleSources += family
            }
        }
        return OutlookMetrics(current, previous, sources.size, calls.size, receivers.size, distanceCount, distanceSum,
            staleSources.size, sources)
    }

    private fun sourceAges(input: NeuralOutlookInput, stationKey: String): Map<String, Long> {
        val latest = linkedMapOf<String, Long>()
        store.outlookReadableDatabase().rawQuery("""SELECT source,MAX(bucket_start) FROM evidence_bucket
            WHERE station_key=? AND band='*' GROUP BY source""", arrayOf(stationKey)).use { cursor ->
            while (cursor.moveToNext()) latest[cursor.getString(0)] = cursor.getLong(1)
        }
        return input.sourceStates.keys.associateWith { source -> latest[source.uppercase(Locale.US)]
            ?.let { (input.epoch - it).coerceAtLeast(0L) } ?: -1L }
    }

    private fun savePrediction(value: OutlookForecast, stationKey: String) {
        val db = store.outlookWritableDatabase()
        val persistenceSlot = outlookPersistenceSlot(value.targetStartEpoch)
        val id = stableId(NEURAL_OUTLOOK_MODEL_VERSION, stationKey, persistenceSlot.toString(),
            value.window.minutes.toString(), value.band)
        db.execSQL("""INSERT OR IGNORE INTO outlook_prediction(id,model_version,created_epoch,target_start,target_end,window_minutes,
            station_key,band,mode_family,region_row,region_col,raw_score,label,confidence,calibrated_probability,
            calibration_samples,source_mask,reasons,verification_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(id, NEURAL_OUTLOOK_MODEL_VERSION, value.generatedEpoch, persistenceSlot,
                persistenceSlot + value.window.minutes * 60L,
                value.window.minutes, stationKey, value.band, value.modeFamily, value.row, value.column, value.supportScore,
                value.label.name, value.confidence.name, value.calibratedHitRate, value.calibrationSamples,
                value.contributingSources.sorted().joinToString(","), value.reasons.joinToString(" | ").take(480), OutlookVerification.PENDING.name))
    }

    private fun persistEligiblePrediction(value: OutlookForecast, stationKey: String) {
        if (outlookPersistenceEligible(value)) savePrediction(value, stationKey)
    }

    private fun verifyPredictions(limit: Int) {
        val db = store.outlookWritableDatabase(); val now = clock()
        data class Pending(val id: String, val station: String, val start: Long, val end: Long, val window: Int,
            val band: String, val family: String, val bin: Int, val row: Int, val column: Int, val mask: String)
        val pending = mutableListOf<Pending>()
        db.rawQuery("""SELECT id,station_key,target_start,target_end,window_minutes,band,mode_family,raw_score/10,
            region_row,region_col,source_mask FROM outlook_prediction WHERE verification_state='PENDING' AND target_end<=?
            AND label<>'INSUFFICIENT_EVIDENCE' AND region_row=-1 AND region_col=-1
            ORDER BY target_end LIMIT ?""", arrayOf(now.toString(), limit.toString())).use { c -> while (c.moveToNext()) pending += Pending(
            c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getString(5), c.getString(6),
            c.getInt(7), c.getInt(8), c.getInt(9), c.getString(10)) }
        pending.forEach { prediction ->
            val contributing = prediction.mask.split(',').filter(String::isNotBlank).map(::sourceFamily).toSet()
            val callsBySource = linkedMapOf<String, MutableSet<String>>()
            db.rawQuery("""SELECT source,call_keys FROM evidence_bucket WHERE station_key=? AND band=?
                AND bucket_start>=? AND bucket_start<=? AND observation_count>0""",
                arrayOf(prediction.station, prediction.band, prediction.start.toString(), prediction.end.toString())).use { c -> while (c.moveToNext()) {
                val family = sourceFamily(c.getString(0))
                if (family in contributing) callsBySource.getOrPut(family) { linkedSetOf() } +=
                    c.getString(1).orEmpty().split(',').filter(String::isNotBlank)
            } }
            val covered = linkedSetOf<String>()
            db.rawQuery("""SELECT source,source_state FROM evidence_bucket WHERE station_key=? AND band='*'
                AND bucket_start>=? AND bucket_start<=?""",
                arrayOf(prediction.station, prediction.start.toString(), prediction.end.toString())).use { cursor -> while (cursor.moveToNext()) {
                if (cursor.getString(1) in setOf("CURRENT", "CACHED")) covered += sourceFamily(cursor.getString(0))
            } }
            val result = verifyOutlookEvidence(callsBySource, contributing, covered)
            db.beginTransaction()
            try {
                db.execSQL("UPDATE outlook_prediction SET verification_state=?,verified_epoch=? WHERE id=? AND verification_state='PENDING'",
                    arrayOf<Any?>(result.name, now, prediction.id))
                val bandFamily = bandFamily(prediction.band)
                db.execSQL("""INSERT INTO outlook_calibration(model_version,window_minutes,band_family,score_bin,verified_count,
                    hit_count,unverifiable_count,updated_epoch) VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(model_version,window_minutes,band_family,score_bin) DO UPDATE SET
                    verified_count=verified_count+excluded.verified_count,hit_count=hit_count+excluded.hit_count,
                    unverifiable_count=unverifiable_count+excluded.unverifiable_count,updated_epoch=excluded.updated_epoch""",
                    arrayOf<Any?>(NEURAL_OUTLOOK_MODEL_VERSION, prediction.window, bandFamily, prediction.bin,
                        if (result == OutlookVerification.UNVERIFIABLE) 0 else 1, if (result == OutlookVerification.HIT) 1 else 0,
                        if (result == OutlookVerification.UNVERIFIABLE) 1 else 0, now))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        compactPredictions(now)
    }

    private fun calibratedRate(window: OutlookWindow, band: String, score: Int): Pair<Int?, Int> {
        val db = store.outlookReadableDatabase(); val family = bandFamily(band); var familyVerified = 0
        var binVerified = 0; var hits = 0
        db.rawQuery("SELECT SUM(verified_count) FROM outlook_calibration WHERE model_version=? AND window_minutes=? AND band_family=?",
            arrayOf(NEURAL_OUTLOOK_MODEL_VERSION, window.minutes.toString(), family)).use { if (it.moveToFirst()) familyVerified = it.getInt(0) }
        db.rawQuery("""SELECT verified_count,hit_count FROM outlook_calibration WHERE model_version=? AND window_minutes=?
            AND band_family=? AND score_bin=?""", arrayOf(NEURAL_OUTLOOK_MODEL_VERSION, window.minutes.toString(), family,
            (score / 10).coerceIn(0, 9).toString())).use { if (it.moveToFirst()) { binVerified = it.getInt(0); hits = it.getInt(1) } }
        val rate = calibratedOutlookRate(familyVerified, binVerified, hits)
        return rate to binVerified
    }

    private fun calibration(window: OutlookWindow, band: String, score: Int): OutlookCalibration {
        val db = store.outlookReadableDatabase(); var familyVerified = 0; var binVerified = 0; var hits = 0
        db.rawQuery("""SELECT COALESCE(SUM(verified_count),0) FROM outlook_calibration
            WHERE model_version=? AND window_minutes=? AND band_family=?""",
            arrayOf(NEURAL_OUTLOOK_MODEL_VERSION, window.minutes.toString(), bandFamily(band))).use {
            if (it.moveToFirst()) familyVerified = it.getInt(0)
        }
        db.rawQuery("""SELECT verified_count,hit_count FROM outlook_calibration WHERE model_version=? AND window_minutes=?
            AND band_family=? AND score_bin=?""", arrayOf(NEURAL_OUTLOOK_MODEL_VERSION, window.minutes.toString(),
            bandFamily(band), (score / 10).coerceIn(0, 9).toString())).use {
            if (it.moveToFirst()) { binVerified = it.getInt(0); hits = it.getInt(1) }
        }
        val rate = calibratedOutlookRate(familyVerified, binVerified, hits)
        val hasStaleModel = if (familyVerified > 0) false else db.rawQuery(
            "SELECT 1 FROM outlook_calibration WHERE model_version<>? LIMIT 1", arrayOf(NEURAL_OUTLOOK_MODEL_VERSION),
        ).use { it.moveToFirst() }
        val state = when { rate != null -> OutlookCalibrationState.CALIBRATED; hasStaleModel -> OutlookCalibrationState.STALE_MODEL
            familyVerified >= 15 -> OutlookCalibrationState.PROVISIONAL
            else -> OutlookCalibrationState.COLLECTING }
        return OutlookCalibration(state, binVerified, hits, rate)
    }

    private fun compactPredictions(now: Long, force: Boolean = false, hardCap: Int = NEURAL_OUTLOOK_PREDICTION_CAP) {
        val db = store.outlookWritableDatabase()
        val last = meta(db, "last_compaction_epoch").toLongOrNull() ?: 0L
        if (!force && now - last < 86_400L) return
        db.beginTransaction()
        try {
            db.delete("evidence_bucket", "bucket_start<?", arrayOf((now - 180L * 86_400L).toString()))
            db.delete("outlook_prediction", "verification_state='PENDING' AND created_epoch<?",
                arrayOf((now - 180L * 86_400L).toString()))
            db.delete("outlook_prediction", "verification_state<>'PENDING' AND verified_epoch<?",
                arrayOf((now - 14L * 86_400L).toString()))
            var total = db.rawQuery("SELECT COUNT(*) FROM outlook_prediction", null).use { it.moveToFirst(); it.getInt(0) }
            if (total > hardCap) {
                db.execSQL("""DELETE FROM outlook_prediction WHERE id IN (SELECT id FROM outlook_prediction
                    WHERE verification_state<>'PENDING' ORDER BY verified_epoch,created_epoch LIMIT ?)""",
                    arrayOf(total - hardCap))
                total = db.rawQuery("SELECT COUNT(*) FROM outlook_prediction", null).use { it.moveToFirst(); it.getInt(0) }
            }
            if (total > hardCap) {
                db.execSQL("""DELETE FROM outlook_prediction WHERE id IN (SELECT id FROM outlook_prediction
                    WHERE verification_state='PENDING' AND target_end<=? ORDER BY target_end LIMIT ?)""",
                    arrayOf<Any?>(now, total - hardCap))
            }
            setMeta(db, "last_compaction_epoch", now.toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun databaseFacts(): Map<String, Long> {
        val db = store.outlookReadableDatabase(); fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L }
        return mapOf("evidence_bucket" to count("evidence_bucket"), "outlook_prediction" to count("outlook_prediction"),
            "outlook_calibration" to count("outlook_calibration"), "backfill_rowid" to (meta(db, "backfill_rowid").toLongOrNull() ?: 0L))
    }

    internal fun runBackfillBatchForTest(): Boolean = backfillBatch()

    internal fun runCompactionForTest(now: Long, hardCap: Int = NEURAL_OUTLOOK_PREDICTION_CAP) =
        compactPredictions(now, force = true, hardCap = hardCap)

    internal fun persistForecastForTest(value: OutlookForecast, stationKey: String) = persistEligiblePrediction(value, stationKey)

    internal fun runVerificationForTest(limit: Int = 100) = verifyPredictions(limit)

    fun close() {
        if (closed) return
        closed = true
        wake.close()
        worker?.cancel()
        scope.cancel()
    }

    private fun bandFamily(band: String) = when (band) {
        "160m", "80m", "60m", "40m" -> "LOW_HF"
        "30m", "20m", "17m", "15m", "12m", "10m" -> "HIGH_HF"
        "6m", "4m", "2m", "70cm" -> "VHF_UHF"
        else -> "MICROWAVE"
    }

    private fun sourceFamily(source: String): String = when {
        source.startsWith("PSK_") || source.startsWith("WSPR_") -> "PSK_REPORTER"
        else -> source
    }

    private fun meta(db: SQLiteDatabase, key: String): String = db.rawQuery(
        "SELECT meta_value FROM outlook_meta WHERE meta_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else "" }

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO outlook_meta(meta_key,meta_value) VALUES(?,?)", arrayOf(key, value))
    }

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("|").toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun callKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().uppercase(Locale.US).toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
