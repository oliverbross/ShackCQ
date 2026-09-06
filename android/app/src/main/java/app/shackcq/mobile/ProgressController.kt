package app.shackcq.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.Locale
import app.shackcq.mobile.hamclock.HamClockBandEvidence
import app.shackcq.mobile.hamclock.HamClockBandHealthPreference
import app.shackcq.mobile.hamclock.HamClockBandHealthSnapshot
import app.shackcq.mobile.hamclock.HamClockEvidenceAvailability
import app.shackcq.mobile.hamclock.computeHamClockBandHealthSnapshot

private const val BAND_HISTORY_CONTRACT = "one-year-comparable-utc-hour-v1"

internal data class BandHistoryCacheKey(
    val databaseChangeToken: Long,
    val stationProfileId: String,
    val stationCallsign: String,
    val utcHourBucket: Long,
    val contract: String = BAND_HISTORY_CONTRACT,
)

internal class BandHistoryCache(private val maximumEntries: Int = 4) {
    private val rows = object : LinkedHashMap<BandHistoryCacheKey, List<app.shackcq.mobile.hamclock.HamClockBandHistoricalRow>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BandHistoryCacheKey, List<app.shackcq.mobile.hamclock.HamClockBandHistoricalRow>>?) =
            size > maximumEntries.coerceAtLeast(1)
    }

    fun getOrLoad(
        key: BandHistoryCacheKey,
        loader: () -> List<app.shackcq.mobile.hamclock.HamClockBandHistoricalRow>,
    ): List<app.shackcq.mobile.hamclock.HamClockBandHistoricalRow> {
        synchronized(rows) { rows[key]?.let { return it } }
        val loaded = loader()
        synchronized(rows) { rows[key] = loaded }
        return loaded
    }

    internal fun size(): Int = synchronized(rows) { rows.size }
}

internal class LatestWinsRequestQueue<T> {
    private var active = false
    private var pending: T? = null
    fun submit(value: T): T? = if (active) { pending = value; null } else { active = true; value }
    fun complete(): T? = pending.also { pending = null; active = it != null }
    fun isActive(): Boolean = active
}

internal class ProgressGoalStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "progress-goals.json"))
    var goals by mutableStateOf(load()); private set

    fun add(metric: ProgressGoalMetric, target: Int, name: String = metric.label) {
        if (goals.size >= 4 || target <= 0) return
        save(goals + ProgressGoal(UUID.randomUUID().toString(), metric, target, name.trim().ifBlank { metric.label }))
    }

    fun remove(id: String) = save(goals.filterNot { it.id == id })

    private fun save(value: List<ProgressGoal>) {
        val bytes = encodeProgressGoals(value).toByteArray()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output); goals = value.take(4) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun load() = runCatching { decodeProgressGoals(file.readFully().toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
}

internal fun encodeProgressGoals(value: List<ProgressGoal>) = JSONArray().apply {
    value.take(4).forEach { goal -> put(JSONObject()
        .put("id", goal.id).put("metric", goal.metric.name).put("target", goal.target)
        .put("name", goal.name).put("band", goal.band).put("mode", goal.mode.name).put("deadline", goal.deadline))
    }
}.toString()

internal fun decodeProgressGoals(raw: String): List<ProgressGoal> {
    val rows = JSONArray(raw)
    return buildList {
        for (index in 0 until rows.length().coerceAtMost(4)) {
            val row = rows.getJSONObject(index)
            add(ProgressGoal(row.getString("id"), ProgressGoalMetric.valueOf(row.getString("metric")),
                row.getInt("target"), row.optString("name"), row.optString("band"),
                ProgressMode.valueOf(row.optString("mode", ProgressMode.ALL.name)), row.optString("deadline")))
        }
    }
}

internal class ProgressController(context: Context, private val database: QsoDatabase) {
    private val repository = LogIntelligenceRepository(database)
    val goalStore = ProgressGoalStore(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("shackcq-log-intelligence", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var pendingRefresh: (() -> Unit)? = null
    private var lastKey: Any? = null
    private var bandHealthJob: Job? = null
    private val bandHealthQueue = LatestWinsRequestQueue<BandHealthRefreshRequest>()
    private val bandHistoryCache = BandHistoryCache()
    private var completedBandHealthKey: Any? = null
    private var fastSnapshotKey: Any? = null
    private var scopeGeneration = 0L
    private var bandHealthGeneration = 0L
    var snapshot by mutableStateOf(ProgressSnapshot()); private set
    var bandHealthSnapshot by mutableStateOf(HamClockBandHealthSnapshot()); private set
    var bandHealthMessage by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var stationProfiles by mutableStateOf<List<String>>(emptyList()); private set
    var stationCallsigns by mutableStateOf<List<String>>(emptyList()); private set
    var operators by mutableStateOf<List<String>>(emptyList()); private set
    var submodes by mutableStateOf<List<String>>(emptyList()); private set
    var filters by mutableStateOf(loadFilters()); private set
    var selectedAward by mutableStateOf(runCatching {
        AwardKind.valueOf(preferences.getString("selected_award", AwardKind.DXCC.name).orEmpty())
    }.getOrDefault(AwardKind.DXCC)); private set
    var logbookRequest by mutableStateOf<LogbookFilter?>(null); private set

    fun requestLogbook(filter: LogbookFilter) { logbookRequest = filter }
    fun consumeLogbookRequest() { logbookRequest = null }

    fun updateFilters(value: ProgressFilters) {
        val applied = value.copy(includeDeleted = false)
        filters = applied
        preferences.edit()
            .putBoolean("all_stations", applied.allStations).putString("station_profile", applied.stationProfileId)
            .putString("station_callsign", applied.stationCallsign).putString("period", applied.period.name)
            .putString("band", applied.band).putString("mode", applied.mode.name).putString("submode", applied.submode)
            .putString("operator", applied.operator).putString("confirmation", applied.confirmationSource)
            .putString("portable_program", applied.portableProgram).putBoolean("include_conflicted", applied.includeConflicted)
            .putBoolean("include_deleted", false).apply()
        lastKey = null
        scopeGeneration++
        refreshJob?.cancel()
        refreshJob = null
        pendingRefresh = null
    }

    fun selectAward(value: AwardKind) {
        selectedAward = value
        preferences.edit().putString("selected_award", value.name).apply()
    }

    private fun loadFilters() = ProgressFilters(
        allStations = preferences.getBoolean("all_stations", false),
        stationProfileId = preferences.getString("station_profile", "").orEmpty(),
        stationCallsign = preferences.getString("station_callsign", "").orEmpty(),
        period = runCatching { ProgressPeriod.valueOf(preferences.getString("period", ProgressPeriod.ALL.name).orEmpty()) }.getOrDefault(ProgressPeriod.ALL),
        band = preferences.getString("band", "").orEmpty(),
        mode = runCatching { ProgressMode.valueOf(preferences.getString("mode", ProgressMode.ALL.name).orEmpty()) }.getOrDefault(ProgressMode.ALL),
        submode = preferences.getString("submode", "").orEmpty(), operator = preferences.getString("operator", "").orEmpty(),
        confirmationSource = preferences.getString("confirmation", "").orEmpty(),
        portableProgram = preferences.getString("portable_program", "").orEmpty(),
        includeConflicted = preferences.getBoolean("include_conflicted", false),
        includeDeleted = false,
    )

    fun refresh(
        filters: ProgressFilters,
        dxSpots: List<AndroidDXSpot>,
        portableSpots: List<PortableSpot>,
        syncAttention: Int,
        cty: CtyController,
        sotaCatalogue: SotaCatalogue,
        logAuthorityIdentity: String,
    ) {
        val key = listOf(database.changeToken(), filters, goalStore.goals, syncAttention, cty.dataRevision,
            progressSpotIdentity(dxSpots, portableSpots), logAuthorityIdentity)
        if (key == lastKey) return
        if (refreshJob?.isActive == true) {
            pendingRefresh = { refresh(filters, dxSpots, portableSpots, syncAttention, cty, sotaCatalogue, logAuthorityIdentity) }
            return
        }
        lastKey = key
        val requestedGeneration = scopeGeneration
        refreshJob = scope.launch {
            busy = true
            try {
                val databaseToken=database.changeToken()
                val requestedFastKey=listOf(databaseToken,filters,goalStore.goals,syncAttention,logAuthorityIdentity)
                if (requestedFastKey != fastSnapshotKey) {
                    val fast=withContext(Dispatchers.IO) {
                        StabilityDiagnostics.timedQuery("LOG_INTELLIGENCE_FAST", filters.hashCode().toUInt().toString(16), "PROJECTION_CORE", { it.totalQsos }) {
                            repository.fastSnapshot(filters,goalStore.goals,syncAttention)
                        }
                    }
                    if (requestedGeneration == scopeGeneration) {
                        snapshot=fast
                        fastSnapshotKey=requestedFastKey
                    }
                }
                val result=withContext(Dispatchers.IO){
                    StabilityDiagnostics.timedQuery("LOG_INTELLIGENCE", filters.hashCode().toUInt().toString(16), "PROJECTION_AGGREGATES", { it.totalQsos }) {
                        repository.snapshot(filters,goalStore.goals,dxSpots,portableSpots,syncAttention,cty::lookup)
                    }
                }
                if (requestedGeneration == scopeGeneration) {
                    stationProfiles=result.stationProfiles.keys.sorted();stationCallsigns=result.stationCallsigns.keys.sorted()
                    operators=result.operators.keys.sorted();submodes=result.submodes.keys.sorted()
                    snapshot=result
                }
            } finally {
                if (requestedGeneration == scopeGeneration) {
                    busy = false
                    refreshJob = null
                    pendingRefresh.also { pendingRefresh = null }?.invoke()
                }
            }
        }
    }

    fun goalsChanged() { lastKey = null }

    fun refreshBandHealth(
        evidence: List<HamClockBandEvidence>,
        availability: Map<String, HamClockEvidenceAvailability>,
        preference: HamClockBandHealthPreference,
        stationProfileId: String?,
        stationCall: String,
        nowEpoch: Long = java.time.Instant.now().epochSecond,
    ) {
        val identity = evidence.fold(1L) { value, row -> 31L * value + row.id.hashCode() }
        val databaseToken = database.changeToken()
        val key = listOf(databaseToken, identity, availability, preference, stationProfileId, stationCall,
            nowEpoch / 60L)
        if (key == completedBandHealthKey && !bandHealthQueue.isActive()) return
        val request = BandHealthRefreshRequest(key, ++bandHealthGeneration, evidence, availability, preference,
            stationProfileId, stationCall, nowEpoch, BandHistoryCacheKey(databaseToken,
                stationProfileId.orEmpty(), stationCall.trim().uppercase(Locale.US), nowEpoch / 3_600L))
        bandHealthQueue.submit(request)?.let(::startBandHealthRefresh)
    }

    private fun startBandHealthRefresh(request: BandHealthRefreshRequest) {
        bandHealthJob = scope.launch {
            try {
                val built = withContext(Dispatchers.IO) {
                    val historical = bandHistoryCache.getOrLoad(request.historyKey) {
                        repository.bandHistory(request.stationProfileId, request.stationCall, request.nowEpoch)
                    }
                    computeHamClockBandHealthSnapshot(request.evidence, request.availability, request.preference,
                        historical, request.nowEpoch)
                }
                if (request.generation == bandHealthGeneration) {
                    bandHealthSnapshot = built
                    bandHealthMessage = ""
                    completedBandHealthKey = request.key
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request.generation == bandHealthGeneration) {
                    bandHealthMessage = error.message.orEmpty()
                        .replace(Regex("https?://\\S+|(?i)(token|key|password)=[^\\s]+"), "[redacted]")
                        .take(160).ifBlank { "Band Health refresh unavailable; retry remains enabled" }
                    bandHealthSnapshot = bandHealthSnapshot.copy(message = bandHealthMessage)
                }
            } finally {
                bandHealthJob = null
                bandHealthQueue.complete()?.let(::startBandHealthRefresh)
            }
        }
    }

    private data class BandHealthRefreshRequest(
        val key: Any,
        val generation: Long,
        val evidence: List<HamClockBandEvidence>,
        val availability: Map<String, HamClockEvidenceAvailability>,
        val preference: HamClockBandHealthPreference,
        val stationProfileId: String?,
        val stationCall: String,
        val nowEpoch: Long,
        val historyKey: BandHistoryCacheKey,
    )
    fun close() = scope.cancel()
}

internal fun progressSpotIdentity(dx: List<AndroidDXSpot>, portable: List<PortableSpot>): Long {
    var hash = -0x340d631b7bdddcdbL
    fun add(value: Any?) { value.toString().forEach { hash = (hash xor it.code.toLong()) * 0x100000001b3L }; hash = (hash xor 0xff) * 0x100000001b3L }
    dx.sortedBy(AndroidDXSpot::id).forEach { add(it.id);add(it.callsign);add(it.frequencyHz);add(it.mode);add(it.band);add(it.receivedEpoch);add(it.score) }
    portable.sortedBy(PortableSpot::id).forEach { row -> add(row.id);add(row.callsign);add(row.frequencyHz);add(row.mode);add(row.spottedAt);add(row.expiresAt);row.references.sortedBy { it.program.name+it.code }.forEach { add(it.program);add(it.code) } }
    return hash
}
