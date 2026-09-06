package app.shackcq.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shackcq.mobile.hamclock.HamClockFeed
import app.shackcq.mobile.hamclock.HamClockPublicProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.time.Instant

internal class OperationsController(
    context: Context,
    private val providers: HamClockPublicProviders,
    database: QsoDatabase,
) {
    private val logRepository=OperationsLogRepository(database)
    val satellites = SatelliteOperationsController(context, database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val plansFile = AtomicFile(File(context.filesDir, "operations-plans.json"))
    var dxItems by mutableStateOf(dxCalendarItems(providers.dxpeditions.cached())); private set
    var contestItems by mutableStateOf(contestCalendarItems(providers.contests.cached())); private set
    var dxMetadata by mutableStateOf(operationsMetadata(providers.dxpeditions.cached())); private set
    var contestMetadata by mutableStateOf(operationsMetadata(providers.contests.cached())); private set
    var plans by mutableStateOf(loadPlans()); private set
    var refreshing by mutableStateOf(false); private set
    var focusDxCall by mutableStateOf(""); private set
    var section by mutableStateOf("DX CALENDAR"); private set
    var dxLocal by mutableStateOf<Map<String,DxLocalSummary>>(emptyMap());private set
    var contestLocal by mutableStateOf<Map<String,Int>>(emptyMap());private set

    val nextPlan: ActivationPlan? get() = plans.filter { it.startEpoch + it.durationMinutes * 60L >= Instant.now().epochSecond }.minByOrNull(ActivationPlan::startEpoch)

    init { refresh(false) }

    fun refresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                providers.dxpeditions.refresh(force) to providers.contests.refresh(force)
            }
            apply(snapshot.first, snapshot.second)
            refreshing = false
        }
    }

    fun focusDx(callsign: String) { focusDxCall = callsign.trim().uppercase(); section = "DX CALENDAR" }
    fun openSection(value: String) { section = value }
    fun clearFocus() { focusDxCall = "" }

    fun save(plan: ActivationPlan) {
        plans = (plans.filterNot { it.id == plan.id } + plan.copy(updatedAt = Instant.now().epochSecond)).sortedBy(ActivationPlan::startEpoch)
        persistPlans()
    }

    fun duplicate(plan: ActivationPlan) = save(plan.copy(id = java.util.UUID.randomUUID().toString(), title = "${plan.title} copy",
        createdAt = Instant.now().epochSecond, updatedAt = Instant.now().epochSecond))

    fun delete(id: String) { plans = plans.filterNot { it.id == id }; persistPlans() }
    fun close() { satellites.close(); scope.cancel() }

    private fun apply(dx: HamClockFeed<List<app.shackcq.mobile.hamclock.HamClockDxpedition>>,
        contests: HamClockFeed<List<app.shackcq.mobile.hamclock.HamClockContest>>) {
        dxItems = dxCalendarItems(dx); contestItems = contestCalendarItems(contests)
        dxMetadata = operationsMetadata(dx); contestMetadata = operationsMetadata(contests)
        scope.launch{val local=withContext(Dispatchers.IO){logRepository.callsigns(dxItems.map(DxCalendarItem::callsign)) to logRepository.contests(contestItems.map(ContestCalendarItem::contestId))};dxLocal=local.first;contestLocal=local.second}
    }

    private fun loadPlans(): List<ActivationPlan> = runCatching {
        if (!plansFile.baseFile.isFile) return emptyList()
        val rows = JSONArray(plansFile.openRead().bufferedReader().use { it.readText() })
        buildList { for (i in 0 until rows.length()) add(decodeActivationPlan(rows.getString(i))) }.sortedBy(ActivationPlan::startEpoch)
    }.getOrDefault(emptyList())

    private fun persistPlans() {
        val stream = plansFile.startWrite()
        try { stream.writer().apply { write(JSONArray(plans.map(::encodeActivationPlan)).toString()); flush() }; plansFile.finishWrite(stream) }
        catch (error: Throwable) { plansFile.failWrite(stream); throw error }
    }
}
