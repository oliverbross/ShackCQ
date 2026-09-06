package app.shackcq.mobile

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal data class SlowQuerySummary(
    val timestamp: Long,
    val category: String,
    val filterHash: String,
    val elapsedMs: Long,
    val rowCount: Int,
    val cancelled: Boolean,
    val planLabel: String,
)

internal data class CrashSummary(
    val timestamp: Long,
    val exceptionClass: String,
    val frames: List<String>,
    val projectionState: String,
    val canonicalRows: Int?,
    val appVersion: String,
    val freeMemoryCategory: String,
)

internal data class StabilitySnapshot(
    val databaseBytes: Long,
    val projection: ProjectionHealth,
    val slowQueries: List<SlowQuerySummary>,
    val crashes: List<CrashSummary>,
)

internal object StabilityDiagnostics {
    private const val PREFS = "stability_diagnostics"
    private const val CRASHES = "crashes_v1"
    private const val SLOW = "slow_queries_v1"
    private const val SLOW_LIMIT = 12
    private const val CRASH_LIMIT = 3
    const val SLOW_QUERY_THRESHOLD_MS = 250L
    private val installed = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var databaseFacts: ProjectionHealth? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun refreshDatabaseFacts(database: QsoDatabase) {
        databaseFacts = runCatching { database.projectionHealth() }.getOrNull()
    }

    inline fun <T> timedQuery(
        category: String,
        filterHash: String,
        planLabel: String,
        rowCount: (T) -> Int,
        block: () -> T,
    ): T {
        val started = SystemClock.elapsedRealtime()
        var cancelled = false
        try {
            return block().also { result ->
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= SLOW_QUERY_THRESHOLD_MS) recordSlowQuery(category, filterHash, elapsed, rowCount(result), false, planLabel)
            }
        } catch (error: Throwable) {
            cancelled = error is android.os.OperationCanceledException || error is kotlinx.coroutines.CancellationException
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= SLOW_QUERY_THRESHOLD_MS) recordSlowQuery(category, filterHash, elapsed, 0, cancelled, planLabel)
            throw error
        }
    }

    fun recordSlowQuery(category: String, filterHash: String, elapsedMs: Long, rowCount: Int, cancelled: Boolean, planLabel: String) {
        val context = appContext ?: return
        val safeCategory = safeToken(category)
        val safePlan = safeToken(planLabel)
        val value = JSONObject()
            .put("timestamp", Instant.now().epochSecond)
            .put("category", safeCategory)
            .put("filter_hash", safeToken(filterHash))
            .put("elapsed_ms", elapsedMs.coerceAtLeast(0))
            .put("row_count", rowCount.coerceAtLeast(0))
            .put("cancelled", cancelled)
            .put("plan", safePlan)
        append(context, SLOW, value, SLOW_LIMIT)
    }

    fun snapshot(context: Context, database: QsoDatabase): StabilitySnapshot {
        val health = database.projectionHealth().also { databaseFacts = it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return StabilitySnapshot(
            databaseBytes = databaseFile(context).let { if (it.exists()) it.length() else 0L },
            projection = health,
            slowQueries = readArray(prefs.getString(SLOW, null)).mapNotNull(::parseSlow),
            crashes = readArray(prefs.getString(CRASHES, null)).mapNotNull(::parseCrash),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CRASHES).remove(SLOW).apply()
    }

    private fun recordCrash(throwable: Throwable) {
        val context = appContext ?: return
        val facts = databaseFacts
        val runtime = Runtime.getRuntime()
        val freeRatio = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).toDouble() / runtime.maxMemory().coerceAtLeast(1)
        val memory = when { freeRatio < .1 -> "CRITICAL"; freeRatio < .25 -> "LOW"; else -> "NORMAL" }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val frames = throwable.stackTrace.take(8).map { frame ->
            listOf(frame.className, frame.methodName, frame.fileName.orEmpty(), frame.lineNumber.toString()).joinToString("|")
        }
        val value = JSONObject()
            .put("timestamp", Instant.now().epochSecond)
            .put("exception_class", throwable.javaClass.name)
            .put("frames", JSONArray(frames))
            .put("projection_state", facts?.state?.name.orEmpty())
            .put("canonical_rows", facts?.canonicalRows ?: JSONObject.NULL)
            .put("app_version", safeToken(version))
            .put("free_memory", memory)
        append(context, CRASHES, value, CRASH_LIMIT)
    }

    @Synchronized private fun append(context: Context, key: String, value: JSONObject, limit: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = readArray(prefs.getString(key, null)).toMutableList()
        rows += value
        while (rows.size > limit) rows.removeAt(0)
        prefs.edit().putString(key, JSONArray(rows).toString()).commit()
    }

    private fun readArray(raw: String?): List<JSONObject> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add) }
    }.getOrDefault(emptyList())

    private fun parseSlow(value: JSONObject) = runCatching { SlowQuerySummary(
        value.getLong("timestamp"), value.getString("category"), value.getString("filter_hash"), value.getLong("elapsed_ms"),
        value.getInt("row_count"), value.getBoolean("cancelled"), value.getString("plan"),
    ) }.getOrNull()

    private fun parseCrash(value: JSONObject) = runCatching {
        val frames = value.optJSONArray("frames") ?: JSONArray()
        CrashSummary(
            value.getLong("timestamp"), value.getString("exception_class"),
            buildList { for (index in 0 until frames.length()) add(frames.optString(index)) },
            value.optString("projection_state"), value.optInt("canonical_rows").takeIf { !value.isNull("canonical_rows") },
            value.optString("app_version"), value.optString("free_memory"),
        )
    }.getOrNull()

    private fun safeToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9_.:-]"), "_").take(80)
    private fun databaseFile(context: Context): File = context.getDatabasePath("shackcq.sqlite")
}
