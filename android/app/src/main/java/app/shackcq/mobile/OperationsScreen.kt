package app.shackcq.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val OpsAmber = Color(0xFFE9A72B)
private val OpsPanel = Color(0xFF1B2228)
private val OpsInk = Color(0xFFF4F0E7)
private val OpsMuted = Color(0xFFA5ADB2)
private val OpsHealthy = Color(0xFF42C77B)
private val OpsDanger = Color(0xFFE47D72)
private val OpsBlue = Color(0xFF65A6C7)
private enum class PlanningScope { AROUND_STATION, AROUND_GPS, SELECTED_POINT, MAP_CENTRE, VISIBLE_BOUNDS, GRID_SEARCH }

@Composable
internal fun OperationsScreen(
    controller: OperationsController,
    portable: PortableController,
    activation: PotaActivationController,
    features: FeatureController,
    progress: ProgressController,
    mutations: QsoMutationCoordinator,
    wavelog: WavelogController,
    callbook: CallbookController,
    cty: CtyController,
    app: AppController,
    compact: Boolean,
    openDx: () -> Unit,
    openPortable: () -> Unit,
    openLogbook: () -> Unit,
    tuneReceive: (Long, String?) -> Unit,
    normalSatelliteLogger: (SatellitePassRow) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(controller.section) }
    LaunchedEffect(controller.section) { selected = controller.section }
    LaunchedEffect(Unit) { while (true) { delay(30 * 60_000L); controller.refresh(false) } }
    Column(Modifier.fillMaxSize().background(Color(0xFF111519)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("OPERATIONS", color = OpsInk, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                Text("CALENDAR · CONTESTS · ACTIVATIONS · SATELLITES", color = OpsMuted) }
            OutlinedButton({ if (selected == "SATELLITES") controller.satellites.refresh(true) else controller.refresh(true) }, enabled = !controller.refreshing && !controller.satellites.busy) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(5.dp)); Text(if (controller.refreshing) "REFRESHING" else "REFRESH")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("DX CALENDAR", "CONTESTS", "ACTIVATION PLANNER", "SATELLITES").forEach { label ->
                FilterChip(selected == label, { selected = label; controller.openSection(label) }, { Text(label) })
            }
        }
        when (selected) {
            "CONTESTS" -> ContestOperations(controller, progress, mutations, wavelog, callbook, app, openLogbook)
            "ACTIVATION PLANNER" -> ActivationPlanner(controller, portable, activation, app, openPortable)
            "SATELLITES" -> SatelliteOperationsScreen(controller.satellites, app.stationCallsign, app.stationGrid, controller.nextPlan?.grid,
                mutations, wavelog, callbook, progress, openLogbook, tuneReceive, normalSatelliteLogger)
            else -> DxOperations(controller, features, progress, cty, wavelog, openDx, openLogbook)
        }
    }
}

@Composable private fun ProviderStrip(metadata: OperationsCacheMetadata) {
    Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OpsBadge(metadata.state.label, if (metadata.error.isBlank() && metadata.lastSuccessEpoch > 0) OpsHealthy else OpsAmber)
            Text(metadata.source, color = OpsInk, modifier = Modifier.weight(1f))
            Text(if (metadata.lastSuccessEpoch > 0) "Saved ${localTime(metadata.lastSuccessEpoch)}" else "No successful fetch", color = OpsMuted)
        }
    }
    if (metadata.error.isNotBlank()) Text("Last refresh: ${metadata.error}. Last-good data remains visible.", color = OpsAmber)
}

@Composable private fun DxOperations(controller: OperationsController, features: FeatureController, progress: ProgressController,
    cty: CtyController, wavelog: WavelogController, openDx: () -> Unit, openLogbook: () -> Unit) {
    val context = LocalContext.current
    val inAppBrowser = LocalInAppBrowserState.current
    var search by rememberSaveable { mutableStateOf(controller.focusDxCall) }
    LaunchedEffect(controller.focusDxCall) { if (controller.focusDxCall.isNotBlank()) { search = controller.focusDxCall; controller.clearFocus() } }
    var group by rememberSaveable { mutableStateOf("ALL") }
    val now = Instant.now().epochSecond
    val rows = controller.dxItems.filter { item ->
        val matches = search.isBlank() || listOf(item.callsign, item.entity, item.dateText, item.status).any { it.contains(search, true) }
        matches && (group == "ALL" || dxGroup(item, now) == group)
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ProviderStrip(controller.dxMetadata) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, label = { Text("Call, entity, date or status") },
                    modifier = Modifier.width(430.dp), singleLine = true)
                listOf("ALL", "ACTIVE NOW", "STARTING SOON", "UPCOMING", "RECENTLY ENDED").forEach { value ->
                    FilterChip(group == value, { group = value }, { Text(value) })
                }
            }
        }
        if (rows.isEmpty()) item { EmptyOperations("No DX calendar entries match. Provider state is shown above.") }
        items(rows, key = DxCalendarItem::id) { item ->
            val entity = cty.lookup(item.callsign)
            val exact = controller.dxLocal[item.callsign.uppercase()] ?: DxLocalSummary(0,0,null)
            val dxcc = entity?.dxcc.orEmpty()
            val entityCount = progress.snapshot.geography.firstOrNull { it.code==dxcc }?.count?.worked ?: 0
            val live = features.liveSpots.firstOrNull { it.callsign.equals(item.callsign, true) }
            val needs = progress.snapshot.needs.firstOrNull { it.dxSpot?.callsign.equals(item.callsign, true) }?.reasons.orEmpty()
            val status = dxGroup(item, now)
            val sourceLabel = if (wavelog.logMode == LogMode.WAVELOG) "WAVELOG" else "LOCAL LOG"
            Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().border(1.dp, operationStatusColor(status).copy(.35f), RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(item.callsign, color = OpsAmber, fontWeight = FontWeight.Black, fontSize = 24.sp)
                            Text(listOf(item.entity.ifBlank { entity?.country.orEmpty() }, item.dateText, item.modes.joinToString(), item.bands.joinToString())
                                .filter(String::isNotBlank).joinToString(" · "), color = OpsInk, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            OpsBadge(sourceLabel, OpsBlue)
                            OpsCountBadge("${exact.qsos} exact", exact.qsos)
                            OpsCountBadge("${exact.confirmed} confirmed", exact.confirmed)
                            OpsCountBadge("$entityCount entity", entityCount)
                            OpsBadge("DXCC ${dxcc.ifBlank { "unresolved" }}", if (dxcc.isBlank()) OpsDanger else OpsBlue)
                            needs.takeIf { it.isNotEmpty() }?.let { OpsBadge("NEEDS ${it.joinToString()}", OpsAmber) }
                            Text("${item.provider}${item.qsl.takeIf(String::isNotBlank)?.let { " · QSL $it" }.orEmpty()}", color = OpsMuted, maxLines = 1)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ progress.requestLogbook(logbookFilterForDimension("callsign", item.callsign)); openLogbook() }) { Text("LOGBOOK") }
                        TextButton({ features.setWatchlist((features.watchlistText.lineSequence().toList() + item.callsign).joinToString("\n")) }) { Text("WATCH DX") }
                        TextButton({ copyText(context, "DX callsign", item.callsign) }) { Text("COPY") }
                        if (item.sourceUrl.startsWith("https://")) TextButton({ inAppBrowser?.open(item.sourceUrl) }) { Text("SOURCE") }
                        if (live != null) TextButton(openDx) { Text("LIVE") }
                        OpsBadge(status, operationStatusColor(status))
                    }
                }
            }
        }
    }
}

private fun dxGroup(item: DxCalendarItem, now: Long): String = when {
    item.endEpoch != null && item.endEpoch < now -> "RECENTLY ENDED"
    item.startEpoch != null && item.endEpoch != null && now in item.startEpoch..item.endEpoch -> "ACTIVE NOW"
    item.startEpoch != null && item.startEpoch - now <= 3 * 86_400 -> "STARTING SOON"
    else -> "UPCOMING"
}

private fun operationStatusColor(status: String): Color = when (status) {
    "ACTIVE NOW", "TODAY" -> OpsHealthy
    "STARTING SOON", "THIS WEEKEND", "NEXT 7 DAYS" -> OpsAmber
    "UPCOMING", "LATER" -> OpsBlue
    else -> OpsMuted
}

@Composable private fun OpsBadge(label: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .6f))) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable private fun OpsCountBadge(label: String, value: Int) = OpsBadge(label, if (value > 0) OpsHealthy else OpsDanger)

@Composable private fun ContestOperations(controller: OperationsController, progress: ProgressController, mutations: QsoMutationCoordinator,
    wavelog: WavelogController, callbook: CallbookController, app: AppController, openLogbook: () -> Unit) {
    val context = LocalContext.current
    val inAppBrowser = LocalInAppBrowserState.current
    var search by rememberSaveable { mutableStateOf("") }; var mode by rememberSaveable { mutableStateOf("ALL") }
    var group by rememberSaveable { mutableStateOf("ALL") }; var utc by rememberSaveable { mutableStateOf(false) }
    var fastDraft by remember { mutableStateOf<String?>(null) }
    val now = Instant.now().epochSecond
    val rows = controller.contestItems.filter { row ->
        (search.isBlank() || row.name.contains(search, true)) && (mode == "ALL" || row.mode == mode) &&
            (group == "ALL" || contestGroup(row, now) == group)
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ProviderStrip(controller.contestMetadata) }
        item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("Search contests") }, modifier = Modifier.width(430.dp), singleLine = true)
            listOf("ALL", "ACTIVE NOW", "TODAY", "THIS WEEKEND", "NEXT 7 DAYS", "LATER").forEach { value -> FilterChip(group == value, { group = value }, { Text(value) }) }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (listOf("ALL") + controller.contestItems.map(ContestCalendarItem::mode).distinct()).forEach { value -> FilterChip(mode == value, { mode = value }, { Text(value) }) }
            FilterChip(utc, { utc = !utc }, { Text(if (utc) "UTC" else "LOCAL") })
        } }
        if (rows.isEmpty()) item { EmptyOperations("No contests match. Malformed and expired provider rows are skipped.") }
        items(rows, key = ContestCalendarItem::id) { item ->
            val qsoCount = item.contestId.takeIf(String::isNotBlank)?.let { controller.contestLocal[it.uppercase()] }
            val status = contestGroup(item, now)
            Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().border(1.dp, operationStatusColor(status).copy(.35f), RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().padding(11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, color = OpsAmber, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("${formatContestTime(item.startEpoch, utc)} → ${formatContestTime(item.endEpoch, utc)} · ${item.mode}", color = OpsInk)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        OpsBadge(if (wavelog.logMode == LogMode.WAVELOG) "WAVELOG" else "LOCAL LOG", OpsBlue)
                        item.contestId.takeIf(String::isNotBlank)?.let { OpsBadge("ADIF $it", OpsBlue); OpsCountBadge("${qsoCount ?: 0} QSOs", qsoCount ?: 0) }
                        Text("${item.provider} · ${((item.endEpoch - item.startEpoch) / 3600.0).let { "%.1f h".format(Locale.US, it) }}" +
                            if (item.contestId.isBlank()) " · ADIF contest ID unavailable" else "", color = OpsMuted)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.sourceUrl.startsWith("https://")) TextButton({ inAppBrowser?.open(item.sourceUrl) }) { Text("RULES") }
                    TextButton({ copyText(context, "Contest", "${item.name}\n${formatContestTime(item.startEpoch, true)}–${formatContestTime(item.endEpoch, true)} UTC") }) { Text("COPY") }
                    TextButton({ shareContestIcs(context, item) }) { Text("SHARE ICS") }
                    TextButton({ fastDraft = "DATE ${Instant.ofEpochSecond(item.startEpoch).atZone(ZoneOffset.UTC).toLocalDate()}\n" +
                        item.contestId.takeIf(String::isNotBlank)?.let { "<CONTEST_ID:$it>\n" }.orEmpty() + "# ${item.name}\n" }) { Text("FAST ENTRY") }
                    if (item.contestId.isNotBlank()) TextButton({
                        progress.requestLogbook(logbookFilterForDimension("contest", item.contestId)); openLogbook()
                    }) { Text("LOGBOOK") }
                    OpsBadge(status, operationStatusColor(status))
                }
            } } }
        }
    fastDraft?.let { initial -> FastEntryDialog(mutations, wavelog, callbook, app.stationCallsign,
        { _, _ -> controller.refresh(false) }, initialDraft = initial) { fastDraft = null } }
}

@Composable private fun ActivationPlanner(controller: OperationsController, portable: PortableController,
    activation: PotaActivationController, app: AppController, openPortable: () -> Unit) {
    val context = LocalContext.current
    var grid by rememberSaveable { mutableStateOf(app.stationGrid.ifBlank { "JN88TQ" }) }
    var point by remember { mutableStateOf(maidenheadCenter(grid) ?: GeoPoint(48.6875, 16.625)) }
    var radius by rememberSaveable { mutableStateOf(100.0) }
    var planningScope by rememberSaveable { mutableStateOf(PlanningScope.AROUND_STATION) }
    var cameraReset by remember { mutableIntStateOf(0) }
    var program by rememberSaveable { mutableStateOf("ALL") }
    var editing by remember { mutableStateOf<ActivationPlan?>(null) }
    var delete by remember { mutableStateOf<ActivationPlan?>(null) }
    var catalogueRows by remember { mutableStateOf<List<ActivationCatalogReference>>(emptyList()) }
    var nearbyRows by remember { mutableStateOf<List<ActivationCatalogReference>>(emptyList()) }
    var invalidPotaCoordinates by remember { mutableIntStateOf(0) }
    var invalidSotaCoordinates by remember { mutableIntStateOf(0) }
    var catalogueBusy by remember { mutableStateOf(false) }
    var catalogueSort by rememberSaveable { mutableStateOf(ActivationCatalogSort.DISTANCE) }
    var cq by rememberSaveable { mutableStateOf(false) }; var itu by rememberSaveable { mutableStateOf(false) }; var states by rememberSaveable { mutableStateOf(false) }
    var pota by rememberSaveable { mutableStateOf(true) }; var sota by rememberSaveable { mutableStateOf(true) }; var wwff by rememberSaveable { mutableStateOf(true) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lastKnownPoint(context)?.let { selected -> point = selected; grid = maidenheadGrid(selected.latitude, selected.longitude) }
    }
    LaunchedEffect(grid, radius, portable.pota.parkMetadata, portable.sotaCatalogue.metadata, portable.wwffSpots) {
        val origin = maidenheadCenter(grid) ?: return@LaunchedEffect
        point = origin
        catalogueBusy = true
        try {
            val (potaResult, sotaResult) = coroutineScope {
                val parks = async { portable.pota.nearbyParks(grid, radius) }
                val summits = async { portable.sotaCatalogue.nearbySummits(grid, radius) }
                parks.await() to summits.await()
            }
            invalidPotaCoordinates = potaResult.invalidCoordinates
            invalidSotaCoordinates = sotaResult.invalidCoordinates
            catalogueRows = withContext(Dispatchers.Default) {
                potaResult.rows.mapNotNull { row ->
                    if (row.latitude == null || row.longitude == null) null else ActivationCatalogReference("POTA", row.reference, row.name, row.grid, GeoPoint(row.latitude, row.longitude), row.active, POTA_PARK_URL)
                } + sotaResult.rows.mapNotNull { row ->
                    if (row.latitude == null || row.longitude == null) null else ActivationCatalogReference("SOTA", row.code, row.name, row.grid, GeoPoint(row.latitude, row.longitude), row.active, SOTA_SUMMITS_URL)
                } + portable.wwffSpots.flatMap { spot -> spot.references.filter { it.program == PortableProgram.WWFF }.mapNotNull { reference ->
                    val latitude = reference.latitude ?: spot.latitude ?: return@mapNotNull null
                    val longitude = reference.longitude ?: spot.longitude ?: return@mapNotNull null
                    ActivationCatalogReference("WWFF", reference.code, reference.name.ifBlank { "Live-discovered reference" }, "",
                        GeoPoint(latitude, longitude), spot.activeAt(Instant.now().epochSecond), WWFF_SPOTS_URL)
                } }.distinctBy { it.reference }
            }
        } finally { catalogueBusy = false }
    }
    LaunchedEffect(catalogueRows, point, radius, program, pota, sota, wwff, catalogueSort) {
        val enabled = buildSet {
            if (pota && (program == "ALL" || program == "POTA")) add("POTA")
            if (sota && (program == "ALL" || program == "SOTA")) add("SOTA")
            if (wwff && (program == "ALL" || program == "WWFF")) add("WWFF")
        }
        nearbyRows = withContext(Dispatchers.Default) { nearbyActivationReferences(point, radius, catalogueRows, enabled, catalogueSort) }
    }
    val mapReferences = nearbyRows.map { PlanningMapReference(it.program, it.reference, it.name, it.point) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(grid, { grid = it.uppercase(Locale.US).take(8) }, label = { Text("Grid") }, modifier = Modifier.weight(1f))
            OutlinedButton({
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    lastKnownPoint(context)?.let { point = it; grid = maidenheadGrid(it.latitude, it.longitude); planningScope = PlanningScope.AROUND_GPS; cameraReset++ }
                else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }, modifier = Modifier.heightIn(min = 56.dp)) { Icon(Icons.Outlined.MyLocation, null); Text(" USE LOCATION") }
        } }
        item { ActivationPlanningMap(point, grid, radius, mapReferences, cameraReset,
            select = { selected -> planningScope = PlanningScope.SELECTED_POINT; point = selected; grid = maidenheadGrid(selected.latitude, selected.longitude) },
            viewport = { centre, visibleRadius ->
                if (planningScope in setOf(PlanningScope.MAP_CENTRE, PlanningScope.VISIBLE_BOUNDS)) {
                    point = centre; grid = maidenheadGrid(centre.latitude, centre.longitude)
                    if (planningScope == PlanningScope.VISIBLE_BOUNDS) radius = visibleRadius.coerceIn(1.0, 5_000.0)
                }
            }) }
        item { Text("${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)} · ${distanceBearing(app.stationGrid, point)}", color = OpsInk) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PlanningScope.entries.forEach { value -> FilterChip(planningScope == value, { planningScope = value }, { Text(value.name.replace('_', ' ')) }) }
            OutlinedButton({
                planningScope = PlanningScope.AROUND_STATION
                maidenheadCenter(app.stationGrid)?.let { point = it; grid = app.stationGrid; cameraReset++ }
            }) { Text("RETURN TO STATION") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("ALL", "POTA", "SOTA", "WWFF").forEach { value -> FilterChip(program == value, { program = value }, { Text(value) }) }
            listOf(25.0, 50.0, 100.0, 250.0).forEach { value -> FilterChip(radius == value, { radius = value }, { Text("${value.toInt()} km") }) }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("SORT", color = OpsMuted, modifier = Modifier.align(Alignment.CenterVertically))
            ActivationCatalogSort.entries.forEach { value -> FilterChip(catalogueSort == value, { catalogueSort = value }, { Text(value.label) }) }
        } }
        item { Text("OVERLAYS", color = OpsAmber, fontWeight = FontWeight.Black); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(false, {}, { Text("CQ ZONES · UNAVAILABLE_DATA") }, enabled = false)
            FilterChip(false, {}, { Text("ITU ZONES · UNAVAILABLE_DATA") }, enabled = false)
            FilterChip(false, {}, { Text("STATES · UNAVAILABLE_DATA") }, enabled = false)
            FilterChip(pota, { pota = !pota }, { Text("POTA") }); FilterChip(sota, { sota = !sota }, { Text("SOTA") }); FilterChip(wwff, { wwff = !wwff }, { Text("WWFF") })
        }; Text("No legally reviewed pinned CQ/ITU/state polygon bundle is packaged. Controls report UNAVAILABLE_DATA and no boundary is fabricated.", color = OpsMuted) }
        item { Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CATALOGUE AUTHORITIES", color = OpsAmber, fontWeight = FontWeight.Black)
            Text("POTA · ${if (portable.pota.parkMetadata.ready) if (portable.pota.parkMetadata.stale) "OFFLINE CACHE / STALE" else "READY" else "NOT DOWNLOADED"} · ${portable.pota.parkMetadata.rowCount} references · ${nearbyRows.count { it.program == "POTA" }} nearby · $invalidPotaCoordinates without coordinates\nSource: $POTA_PARK_URL · imported ${catalogueTime(portable.pota.parkMetadata.importedAt)}", color = OpsMuted)
            Text("SOTA · ${if (portable.sotaCatalogue.metadata.ready) if (portable.sotaCatalogue.metadata.stale) "OFFLINE CACHE / STALE" else "READY" else "NOT DOWNLOADED"} · ${portable.sotaCatalogue.metadata.rowCount} summits · ${nearbyRows.count { it.program == "SOTA" }} nearby · $invalidSotaCoordinates without coordinates\nSource: $SOTA_SUMMITS_URL · imported ${catalogueTime(portable.sotaCatalogue.metadata.importedAt)}", color = OpsMuted)
            val wwffLive = when (portable.wwffStatus.kind) {
                PortableFeedKind.LIVE -> "AVAILABLE"
                PortableFeedKind.STALE -> "STALE"
                PortableFeedKind.CACHED, PortableFeedKind.OFFLINE -> "OFFLINE_CACHE"
                PortableFeedKind.EMPTY -> "EMPTY"
                else -> "ERROR"
            }
            Text("WWFF LIVE SPOTS · $wwffLive · ${portable.wwffStatus.count} active · ${nearbyRows.count { it.program == "WWFF" }} viewport references\nWWFF DIRECTORY · NEEDS API KEY OR AUTHORISED IMPORT · live-discovered references remain a partial cache", color = if (wwffLive == "AVAILABLE") OpsHealthy else OpsDanger)
            if (catalogueBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } } }
        item { Text("NEARBY REFERENCES · ${nearbyRows.size}", color = OpsAmber, fontWeight = FontWeight.Black) }
        if (!catalogueBusy && nearbyRows.isEmpty()) item { EmptyOperations("No catalogue references within the inclusive ${radius.toInt()} km radius for the selected programmes.") }
        items(nearbyRows.take(100), key = { "${it.program}:${it.reference}" }) { row -> ReferenceRow(row.program, row.reference, row.name, row.distanceKm, row.bearingDegrees, if (row.active) "ACTIVE CATALOGUE" else "RETIRED / EXPIRED") { editing = planFor(row.program, row.reference, row.name, row.grid.ifBlank { grid }, row.point.latitude, row.point.longitude) } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("SAVED PLANS", color = OpsAmber, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f)); Button({ editing = planFor("GENERAL", "", "New activation", grid, point.latitude, point.longitude) }) { Text("NEW PLAN") } } }
        if (controller.plans.isEmpty()) item { EmptyOperations("No saved activation plans. Plans are local and survive app upgrades.") }
        items(controller.plans, key = ActivationPlan::id) { plan -> Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(plan.title, color = OpsInk, fontWeight = FontWeight.Bold); Text(activationPlanSummary(plan), color = OpsMuted); OpsBadge(plan.program, OpsBlue) }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ editing = plan }) { Text("EDIT") }; TextButton({ controller.duplicate(plan) }) { Text("DUPLICATE") }
                TextButton({ sharePlanIcs(context, plan) }) { Text("SHARE") }; TextButton({ copyText(context, "Activation plan", activationPlanSummary(plan)) }) { Text("COPY") }
                TextButton({ delete = plan }) { Text("DELETE") }
                TextButton({
                    if (plan.program == "POTA") activation.preparePlan(potaSetupForActivationPlan(plan, app.stationCallsign))
                    openPortable()
                }) { Text(if (plan.program == "POTA") "PREPARE POTA" else "OPEN PORTABLE") }
            }
        } } }
    }
    editing?.let { plan -> PlanEditor(plan, { controller.save(it); editing = null }, { editing = null }) }
    delete?.let { plan -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete activation plan?") }, text = { Text(plan.title) },
        confirmButton = { Button({ controller.delete(plan.id); delete = null }) { Text("DELETE") } }, dismissButton = { TextButton({ delete = null }) { Text("CANCEL") } }) }
}

private data class PlanningMapReference(val program:String,val code:String,val name:String,val point:GeoPoint)

@Composable private fun ActivationPlanningMap(point:GeoPoint,grid:String,radius:Double,references:List<PlanningMapReference>,resetKey:Int,
    select:(GeoPoint)->Unit, viewport:(GeoPoint,Double)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentSelect by rememberUpdatedState(select)
    val mapView=remember { MapLibre.getInstance(context.applicationContext); MapView(context).apply { onCreate(null) } }
    val callbackLifecycle=remember(mapView) { LifecycleGeneration() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styled by remember { mutableStateOf(false) }
    var userMoved by remember { mutableStateOf(false) }
    var cameraFitted by remember { mutableStateOf(false) }
    val currentViewport by rememberUpdatedState(viewport)
    LaunchedEffect(resetKey) { userMoved = false; cameraFitted = false }
    DisposableEffect(mapView,lifecycle) {
        val callbackGeneration=callbackLifecycle.next()
        var disposed=false
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { ready ->
            if(disposed || !callbackLifecycle.isCurrent(callbackGeneration)) return@getMapAsync
            map=ready
            ready.uiSettings.isAttributionEnabled=true
            ready.uiSettings.isLogoEnabled=true
            ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) {
                if(callbackLifecycle.isCurrent(callbackGeneration)) styled=true
            }
            ready.addOnCameraMoveStartedListener { reason -> if (callbackLifecycle.isCurrent(callbackGeneration) && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved = true }
            ready.addOnCameraIdleListener {
                if(!callbackLifecycle.isCurrent(callbackGeneration)) return@addOnCameraIdleListener
                if (userMoved) {
                    val centre = ready.cameraPosition.target ?: return@addOnCameraIdleListener
                    val bounds = ready.projection.visibleRegion.latLngBounds
                    val radiusKm = distanceKm(GeoPoint(centre.latitude, centre.longitude), GeoPoint(bounds.latitudeNorth, bounds.longitudeEast))
                    currentViewport(GeoPoint(centre.latitude, centre.longitude), radiusKm)
                }
            }
            ready.addOnMapClickListener { location ->
                if(!callbackLifecycle.isCurrent(callbackGeneration)) false
                else { currentSelect(GeoPoint(location.latitude,location.longitude)); true }
            }
        }
        onDispose { disposed=true;callbackLifecycle.retire();lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy(); map=null }
    }
    val referenceHash=references.joinToString { "${it.program}:${it.code}:${it.point.latitude}:${it.point.longitude}" }
    LaunchedEffect(map,styled,point,grid,radius,referenceHash,resetKey) {
        val ready=map ?: return@LaunchedEffect
        if(!styled)return@LaunchedEffect
        ready.clear()
        ready.addMarker(MarkerOptions().position(LatLng(point.latitude,point.longitude)).title("Selected grid $grid").snippet("Tap the map to move the activation plan")
            .icon(planningMarker(context,android.graphics.Color.rgb(233,167,43))))
        references.take(200).forEach { row ->
            val color=when(row.program) { "POTA" -> android.graphics.Color.rgb(66,199,123); "SOTA" -> android.graphics.Color.rgb(101,166,199); else -> android.graphics.Color.rgb(196,129,216) }
            ready.addMarker(MarkerOptions().position(LatLng(row.point.latitude,row.point.longitude)).title("${row.program} ${row.code}").snippet(row.name)
                .icon(planningMarker(context,color)))
        }
        if (!cameraFitted) {
            val positions=listOf(LatLng(point.latitude,point.longitude))+references.take(200).map { LatLng(it.point.latitude,it.point.longitude) }
            if(positions.size>1) runCatching { ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(positions).build(),70),450) }
            else ready.cameraPosition=CameraPosition.Builder().target(positions.first()).zoom(when { radius<=25 -> 9.5; radius<=50 -> 8.5; radius<=100 -> 7.5; else -> 6.5 }).build()
            cameraFitted = true
        }
    }
    Box(Modifier.fillMaxWidth().height(330.dp).background(Color(0xFF15262B),RoundedCornerShape(8.dp)).border(1.dp,OpsAmber.copy(.45f),RoundedCornerShape(8.dp))) {
        AndroidView({ mapView },Modifier.fillMaxSize())
        Surface(color=Color(0xE6192228),shape=RoundedCornerShape(5.dp),modifier=Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("$grid · ${radius.toInt()} km · ${references.size} nearby references\nPOTA green · SOTA blue · WWFF violet · tap map to select",
                color=OpsInk,fontSize=11.sp,modifier=Modifier.padding(7.dp))
        }
    }
}

private fun planningMarker(context:Context,color:Int):org.maplibre.android.annotations.Icon {
    val size=25*context.resources.displayMetrics.density
    val bitmap=Bitmap.createBitmap(size.toInt(),size.toInt(),Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color }
    canvas.drawCircle(size/2,size/2,size*.34f,paint)
    paint.style=Paint.Style.STROKE;paint.strokeWidth=size*.09f;paint.color=android.graphics.Color.WHITE
    canvas.drawCircle(size/2,size/2,size*.34f,paint)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

@Composable private fun ReferenceRow(program: String, code: String, name: String, distance: Double?, bearing: Int?, status: String, add: () -> Unit) {
    Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpsBadge(program, when (program) { "POTA" -> OpsHealthy; "SOTA" -> OpsBlue; else -> Color(0xFFC481D8) })
            Column(Modifier.weight(1f)) { Text("$code · $name", color = OpsInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${distance?.let { "%.0f km".format(it) } ?: "distance unknown"} · ${bearing?.let { "$it°" } ?: "bearing unknown"}", color = OpsMuted) }
            OpsBadge(status, if (status.startsWith("ACTIVE")) OpsHealthy else if (status.contains("RETIRED") || status.contains("EXPIRED")) OpsDanger else OpsAmber)
            TextButton(add) { Text("PLAN") }
        }
    }
}

@Composable private fun PlanEditor(initial: ActivationPlan, save: (ActivationPlan) -> Unit, dismiss: () -> Unit) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }; var refs by remember(initial.id) { mutableStateOf(initial.references.joinToString(", ")) }
    var grid by remember(initial.id) { mutableStateOf(initial.grid) }; var start by remember(initial.id) { mutableStateOf(Instant.ofEpochSecond(initial.startEpoch).toString()) }
    var duration by remember(initial.id) { mutableStateOf(initial.durationMinutes.toString()) }; var notes by remember(initial.id) { mutableStateOf(initial.notes) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Activation plan") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(refs, { refs = it.uppercase(Locale.US) }, label = { Text("References") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(grid, { grid = it.uppercase(Locale.US) }, label = { Text("Grid") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(start, { start = it }, label = { Text("Start UTC ISO-8601") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, label = { Text("Duration minutes") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()) }
    } }, confirmButton = { Button({ save(initial.copy(title = title.trim(), references = refs.split(',', ' ', '\n').filter(String::isNotBlank), grid = grid,
        startEpoch = runCatching { Instant.parse(start).epochSecond }.getOrDefault(initial.startEpoch), durationMinutes = duration.toIntOrNull()?.coerceIn(15, 1440) ?: 120, notes = notes)) }, enabled = title.isNotBlank() && maidenheadCell(grid) != null && runCatching { Instant.parse(start) }.isSuccess) { Text("SAVE") } },
        dismissButton = { TextButton(dismiss) { Text("CANCEL") } })
}

@Composable private fun EmptyOperations(text: String) { Surface(color = OpsPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(text, color = OpsMuted, modifier = Modifier.padding(14.dp)) } }

private fun planFor(program: String, ref: String, name: String, grid: String, lat: Double, lon: Double) = ActivationPlan(
    title = name.ifBlank { "$program activation" }, program = program, references = listOf(ref).filter(String::isNotBlank), grid = grid,
    latitude = lat, longitude = lon, startEpoch = Instant.now().epochSecond + 3600)

private fun localTime(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
private fun catalogueTime(epoch: Long) = if (epoch <= 0) "never" else localTime(epoch)
private fun formatContestTime(epoch: Long, utc: Boolean) = Instant.ofEpochSecond(epoch).atZone(if (utc) ZoneOffset.UTC else ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE dd MMM HH:mm"))
private fun distanceBearing(stationGrid: String, target: GeoPoint): String = maidenheadCenter(stationGrid)?.let { "%.0f km · %03d° from %s".format(distanceKm(it, target), initialBearingDegrees(it, target), stationGrid) } ?: "station grid unavailable"

private fun copyText(context: Context, label: String, value: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value)) }
private fun shareContestIcs(context: Context, item: ContestCalendarItem) {
    val plan = ActivationPlan(id = "contest-${item.id}", title = item.name, program = "CONTEST", grid = "AA00AA", latitude = 0.0, longitude = 0.0,
        startEpoch = item.startEpoch, durationMinutes = ((item.endEpoch - item.startEpoch) / 60).toInt().coerceAtLeast(1), notes = item.sourceUrl)
    sharePlanIcs(context, plan)
}

private fun sharePlanIcs(context: Context, plan: ActivationPlan) {
    runCatching {
        val dir = File(context.cacheDir, "operations-exports").apply(File::mkdirs)
        val file = File(dir, plan.title.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "shackcq-plan" } + ".ics")
        file.writeText(activationPlanIcs(plan))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/calendar"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share calendar file"))
    }
}

private fun lastKnownPoint(context: Context): GeoPoint? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(LocationManager::class.java)
    return manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) }
}
