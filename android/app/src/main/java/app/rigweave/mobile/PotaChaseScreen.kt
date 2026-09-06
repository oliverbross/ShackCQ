package app.rigweave.mobile

import android.view.MotionEvent
import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

private val PotaChassis = Color(0xFF111519)
private val PotaPanel = Color(0xFF1B2228)
private val PotaRaised = Color(0xFF283139)
private val PotaInk = Color(0xFFF4F0E7)
private val PotaMuted = Color(0xFFA5ADB2)
private val PotaAmber = Color(0xFFE9A72B)
private val PotaHealthy = Color(0xFF42C77B)
private val PotaDanger = Color(0xFFE4544D)

private enum class PotaPage(val label: String) { LIVE("Live"), MAP("Map"), PARKS("Parks") }

@Composable
internal fun PotaChaseScreen(
    controller: PotaController,
    radio: RadioState,
    stationGrid: String,
    foreground: Boolean,
    compact: Boolean,
    onTune: (PotaSpot) -> Unit,
    onTuneAndLog: (PotaSpot) -> Unit,
) {
    val context = LocalContext.current
    val filterPrefs = remember { context.getSharedPreferences("rigweave-pota-filters", Context.MODE_PRIVATE) }
    var page by rememberSaveable { mutableStateOf(PotaPage.LIVE) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf(filterPrefs.getString("search", "") ?: "") }
    var band by rememberSaveable { mutableStateOf(filterPrefs.getString("band", "ALL") ?: "ALL") }
    var mode by rememberSaveable { mutableStateOf(filterPrefs.getString("mode", "ALL") ?: "ALL") }
    var newOnly by rememberSaveable { mutableStateOf(filterPrefs.getBoolean("new_only", false)) }
    var showQrt by rememberSaveable { mutableStateOf(filterPrefs.getBoolean("show_qrt", false)) }
    var liveOnly by rememberSaveable { mutableStateOf(filterPrefs.getBoolean("live_only", true)) }
    var sort by rememberSaveable { mutableStateOf(runCatching { PotaSort.valueOf(filterPrefs.getString("sort", PotaSort.RECOMMENDED.name)!!) }.getOrDefault(PotaSort.RECOMMENDED)) }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var pendingTune by remember { mutableStateOf<Pair<PotaSpot, Boolean>?>(null) }

    LaunchedEffect(Unit) { controller.checkParksOncePerForegroundDay(); controller.refreshSpots() }
    LaunchedEffect(foreground) {
        while (foreground) {
            now = Instant.now().epochSecond; controller.markForegroundAge(now)
            delay(60_000); if (foreground) controller.refreshSpots()
        }
    }
    LaunchedEffect(search, band, mode, newOnly, showQrt, liveOnly, sort) {
        filterPrefs.edit().putString("search", search).putString("band", band).putString("mode", mode)
            .putBoolean("new_only", newOnly).putBoolean("show_qrt", showQrt).putBoolean("live_only", liveOnly).putString("sort", sort.name).apply()
    }

    val all = remember(controller.spots, controller.lastQsoRevision, now / 15, radio.frequencyHz, stationGrid) {
        controller.opportunities(now, radio.frequencyHz, stationGrid)
    }
    val filtered = remember(all, search, band, mode, newOnly, showQrt, liveOnly, sort, controller.feedKind) {
        val query = search.trim().uppercase(Locale.US)
        sortedPota(all.filter { row ->
            val spot = row.spot
            (query.isBlank() || listOf(spot.callsign, spot.reference, spot.parkName, spot.location).any { it.uppercase(Locale.US).contains(query) }) &&
                (band == "ALL" || spot.band == band) && (mode == "ALL" || modeFamily(spot.mode) == mode) &&
                (!newOnly || !row.worked.parkWorked || !row.worked.bandWorked || !row.worked.modeWorked) &&
                (showQrt || !spot.qrt) && (!liveOnly || (controller.feedKind == PotaFeedKind.LIVE && spot.activeAt(now)))
        }, sort)
    }
    val selected = filtered.firstOrNull { it.spot.id == selectedId } ?: all.firstOrNull { it.spot.id == selectedId }

    pendingTune?.let { (spot, openLog) ->
        val commands = potaCatCommands(spot)
        AlertDialog(onDismissRequest = { pendingTune = null },
            title = { Text(if (openLog) "Tune & Log" else "Tune VFO A") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${formatPotaMHz(spot.frequencyHz)} MHz · ${spot.mode.ifBlank { "MODE UNSPECIFIED" }}", fontFamily = FontFamily.Monospace, color = PotaAmber, fontWeight = FontWeight.Bold)
                Text("${spot.callsign} · ${spot.reference} · ${spot.parkName}")
                Text(if (commands.size > 1) "RigWeave will set VFO A and the unambiguous receive mode. It will not transmit."
                    else "RigWeave will set VFO A only; the reported mode is a suggestion. It will not transmit.", color = PotaMuted)
                if (!radio.connected) Text("CAT offline — selection is retained.", color = PotaDanger)
            } },
            confirmButton = { Button({ if (openLog) onTuneAndLog(spot) else onTune(spot); pendingTune = null }, enabled = radio.connected && spot.frequencyHz > 0) { Text(if (openLog) "Tune & Log" else "Tune") } },
            dismissButton = { TextButton({ pendingTune = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().background(PotaChassis).padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PotaHeader(controller, radio, filtered.count { it.spot.activeAt(now) }, now)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PotaPage.entries.forEachIndexed { index, item -> SegmentedButton(page == item, { page = item }, SegmentedButtonDefaults.itemShape(index, PotaPage.entries.size)) { Text(item.label) } }
        }
        when (page) {
            PotaPage.PARKS -> PotaParks(controller, stationGrid, Modifier.weight(1f))
            PotaPage.MAP -> PotaMap(filtered, selectedId, { selectedId = it; page = PotaPage.LIVE }, Modifier.weight(1f))
            PotaPage.LIVE -> BoxWithConstraints(Modifier.weight(1f)) {
                if (!compact && maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PotaFilters(search, { search = it }, band, { band = it }, mode, { mode = it }, newOnly, { newOnly = it }, showQrt, { showQrt = it }, liveOnly, { liveOnly = it }, sort, { sort = it },
                        { search = ""; band = "ALL"; mode = "ALL"; newOnly = false; showQrt = false; liveOnly = true }, Modifier.width(220.dp).fillMaxHeight())
                    PotaSpotList(filtered, selectedId, { selectedId = it }, Modifier.weight(1.2f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PotaMap(filtered, selectedId, { selectedId = it }, Modifier.weight(1f))
                        PotaSpotDetail(selected, radio, { pendingTune = it.spot to false }, { pendingTune = it.spot to true }, Modifier.weight(.82f))
                    }
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PotaCompactFilters(search, { search = it }, band, { band = it }, mode, { mode = it }, newOnly, { newOnly = it }, sort, { sort = it })
                    PotaSpotList(filtered, selectedId, { selectedId = it }, Modifier.weight(1f))
                    selected?.let { PotaSpotDetail(it, radio, { pendingTune = it.spot to false }, { pendingTune = it.spot to true }, Modifier.heightIn(min = 190.dp, max = 280.dp)) }
                }
            }
        }
    }
}

@Composable private fun PotaHeader(controller: PotaController, radio: RadioState, active: Int, now: Long) {
    val status = when (controller.feedKind) { PotaFeedKind.LOADING -> "LOADING LIVE SPOTS"; PotaFeedKind.LIVE -> "LIVE"; PotaFeedKind.CACHED -> "CACHED"; PotaFeedKind.OFFLINE -> "OFFLINE"; PotaFeedKind.FAILED -> "REFRESH FAILED" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { Text("POTA CHASE", color = PotaAmber, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp); Text("$active usable spots · ${ageText(controller.fetchedAt, now)} · ${parkStatus(controller.parkMetadata)}", color = PotaMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        PotaStatus(status, controller.feedKind == PotaFeedKind.LIVE)
        PotaStatus(if (radio.connected) "CAT LIVE" else "CAT OFFLINE", radio.connected)
        IconButton(controller::refreshSpots, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Refresh POTA spots") }
    }
    if (controller.feedError.isNotBlank()) Text(controller.feedError, color = PotaAmber, fontSize = 12.sp)
}

@Composable private fun PotaStatus(text: String, good: Boolean) = Surface(color = (if (good) PotaHealthy else PotaAmber).copy(alpha = .14f), shape = RoundedCornerShape(6.dp)) {
    Text(text, color = if (good) PotaHealthy else PotaAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable private fun PotaFilters(search: String, changeSearch: (String) -> Unit, band: String, changeBand: (String) -> Unit, mode: String, changeMode: (String) -> Unit,
    newOnly: Boolean, changeNew: (Boolean) -> Unit, showQrt: Boolean, changeQrt: (Boolean) -> Unit, liveOnly: Boolean, changeLive: (Boolean) -> Unit,
    sort: PotaSort, changeSort: (PotaSort) -> Unit, reset: () -> Unit, modifier: Modifier) {
    Column(modifier.background(PotaPanel, RoundedCornerShape(10.dp)).border(1.dp, PotaRaised, RoundedCornerShape(10.dp)).padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FILTERS", color = PotaAmber, fontWeight = FontWeight.Black)
        OutlinedTextField(search, changeSearch, label = { Text("Call / park") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PotaChoice("Band", band, listOf("ALL", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"), changeBand)
        PotaChoice("Mode", mode, listOf("ALL", "CW", "SSB", "FT8", "FT4", "RTTY", "DIGITAL", "FM", "AM"), changeMode)
        PotaChoice("Sort", sort.label, PotaSort.entries.map(PotaSort::label)) { label -> changeSort(PotaSort.entries.first { it.label == label }) }
        FilterChip(newOnly, { changeNew(!newOnly) }, { Text("New opportunities") })
        FilterChip(liveOnly, { changeLive(!liveOnly) }, { Text("Live only") })
        FilterChip(showQrt, { changeQrt(!showQrt) }, { Text("Show QRT / expired") })
        OutlinedButton(reset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("RESET") }
    }
}

@Composable private fun PotaCompactFilters(search: String, changeSearch: (String) -> Unit, band: String, changeBand: (String) -> Unit, mode: String, changeMode: (String) -> Unit,
    newOnly: Boolean, changeNew: (Boolean) -> Unit, sort: PotaSort, changeSort: (PotaSort) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(search, changeSearch, label = { Text("Search call, park, reference") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1f)) { PotaChoice("Band", band, listOf("ALL", "80m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"), changeBand) }
            Box(Modifier.weight(1f)) { PotaChoice("Mode", mode, listOf("ALL", "CW", "SSB", "FT8", "FT4", "RTTY", "DIGITAL", "FM", "AM"), changeMode) }
            Box(Modifier.weight(1f)) { PotaChoice("Sort", sort.label, PotaSort.entries.map(PotaSort::label)) { label -> changeSort(PotaSort.entries.first { it.label == label }) } }
            FilterChip(newOnly, { changeNew(!newOnly) }, { Text("NEW") }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable private fun PotaChoice(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("$label · $value", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) { choices.forEach { item -> DropdownMenuItem({ Text(item) }, onClick = { change(item); open = false }, trailingIcon = { if (item == value) Icon(Icons.Outlined.Check, null) }) } }
    }
}

@Composable private fun PotaSpotList(rows: List<PotaOpportunity>, selectedId: String?, select: (String) -> Unit, modifier: Modifier) {
    if (rows.isEmpty()) Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching spots", color = PotaMuted) }
    else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(rows, key = { it.spot.id }) { row ->
            val spot = row.spot; val selected = selectedId == spot.id
            Row(Modifier.fillMaxWidth().background(if (selected) PotaAmber.copy(alpha = .13f) else PotaPanel, RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) PotaAmber else PotaRaised, RoundedCornerShape(8.dp)).clickable { select(spot.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(spot.callsign, color = PotaInk, fontWeight = FontWeight.Black, fontSize = 18.sp); Text("${formatPotaMHz(spot.frequencyHz)} · ${spot.mode}", color = PotaAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    Text("${spot.reference} · ${spot.parkName.ifBlank { "Park name unavailable" }}", color = PotaInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(spot.location, ageText(spot.spottedAt, Instant.now().epochSecond), row.distanceKm?.let { "%.0f km · %03d°".format(it, row.bearingDegrees ?: 0) }).filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = PotaMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (row.worked.labels + row.reasons.take(2).map(String::uppercase)).distinct().take(4).forEach { label -> SuggestionChip({}, { Text(label, fontSize = 9.sp) }) } }
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = PotaMuted)
            }
        }
    }
}

@Composable private fun PotaSpotDetail(row: PotaOpportunity?, radio: RadioState, tune: (PotaOpportunity) -> Unit, tuneLog: (PotaOpportunity) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(PotaPanel, RoundedCornerShape(10.dp)).border(1.dp, PotaRaised, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (row == null) { Text("SELECT A LIVE SPOT", color = PotaAmber, fontWeight = FontWeight.Black); Text("Selection never retunes the radio. Review a spot, then choose Tune or Tune & Log.", color = PotaMuted); return@Column }
        val spot = row.spot
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${spot.callsign} · ${spot.reference}", color = PotaAmber, fontWeight = FontWeight.Black); Text("SCORE ${row.score}", color = PotaMuted, fontFamily = FontFamily.Monospace) }
        Text(spot.parkName.ifBlank { "Park name unavailable" }, color = PotaInk, fontWeight = FontWeight.SemiBold)
        Text("${formatPotaMHz(spot.frequencyHz)} MHz · ${spot.mode.ifBlank { "mode unspecified" }} · ${spot.band}", color = PotaInk, fontFamily = FontFamily.Monospace)
        Text(listOf(spot.location, spot.grid, spot.source.takeIf(String::isNotBlank)?.let { "via $it" }).filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = PotaMuted)
        if (spot.comments.isNotBlank()) Text(spot.comments, color = PotaMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ tune(row) }, enabled = radio.connected && spot.frequencyHz > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Tune") }
            Button({ tuneLog(row) }, enabled = radio.connected && spot.frequencyHz > 0, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) { Text("Tune & Log", fontWeight = FontWeight.Black) }
        }
        if (!radio.connected) Text("CAT offline · selection retained", color = PotaDanger, fontSize = 12.sp)
    }
}

@Composable internal fun PotaParks(controller: PotaController, stationGrid: String, modifier: Modifier) {
    val context = LocalContext.current
    val inAppBrowser = LocalInAppBrowserState.current
    var query by rememberSaveable { mutableStateOf("") }; var location by rememberSaveable { mutableStateOf("") }; var grid by rememberSaveable { mutableStateOf(stationGrid) }; var nearby by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) lastDeviceGrid(context)?.let { grid = it; nearby = true; controller.searchParks(query, location, grid, true) } }
    LaunchedEffect(query, location, grid, nearby, controller.parkMetadata.ready) { delay(180); controller.searchParks(query, location, grid, nearby) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = PotaPanel, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PotaRaised)) { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(if (controller.parkMetadata.ready) "PARK DATABASE ${if (controller.parkMetadata.stale) "STALE" else "READY"}" else "DOWNLOAD WORLDWIDE POTA PARKS", color = PotaAmber, fontWeight = FontWeight.Black); Text(if (controller.parkMetadata.ready) "${controller.parkMetadata.rowCount} parks · ${formatBytes(controller.parkMetadata.sourceBytes)} · imported ${dateText(controller.parkMetadata.importedAt)}" else "Search and browse parks offline. Source: Parks on the Air", color = PotaMuted) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { if (controller.parkBusy) OutlinedButton(controller::cancelParkUpdate) { Text("Cancel") } else Button(controller::updateParks) { Text(if (controller.parkMetadata.ready) "Update now" else "Download") } } }
            if (controller.parkBusy) LinearProgressIndicator({ controller.parkProgress / 100f }, Modifier.fillMaxWidth())
            if (controller.parkMetadata.failure.isNotBlank()) Text(controller.parkMetadata.failure, color = PotaAmber)
            Text("Data © Parks on the Air. RigWeave is an independent application. Park points are approximate; verify official park boundaries before activation.", color = PotaMuted, fontSize = 11.sp)
        } }
        if (!controller.parkMetadata.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Park database not downloaded · live spots remain available", color = PotaMuted) }
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it.uppercase() }, label = { Text("Reference or park name") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(location, { location = it.uppercase() }, label = { Text("Location / entity") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(grid, { grid = it.uppercase().take(8) }, label = { Text("Station / manual grid") }, singleLine = true, modifier = Modifier.width(150.dp))
                FilterChip(nearby, { nearby = !nearby }, { Text("Nearby") }, leadingIcon = { Icon(Icons.Outlined.NearMe, null) })
                OutlinedButton({ if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) lastDeviceGrid(context)?.let { grid = it; nearby = true } else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.MyLocation, "Use device location") }
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(360.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { gridItems(controller.parkResults, key = PotaPark::reference) { park ->
                Row(Modifier.fillMaxWidth().background(PotaPanel, RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${park.reference} · ${park.name}", color = PotaInk, fontWeight = FontWeight.Bold); Text(listOf(park.location, park.grid, park.distanceKm?.let { "%.1f km · %03d°".format(it, park.bearingDegrees ?: 0) }, if (park.active) "ACTIVE" else "INACTIVE").filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = PotaMuted) }; IconButton({ inAppBrowser?.open("https://pota.app/#/park/${park.reference}") }, colors = IconButtonDefaults.iconButtonColors(containerColor = PotaAmber, contentColor = Color(0xFF201708))) { Icon(Icons.Outlined.OpenInNew, "Open official POTA park page") } }
            } }
        }
    }
}

@Composable private fun PotaMap(rows: List<PotaOpportunity>, selectedId: String?, select: (String) -> Unit, modifier: Modifier) {
    val valid = rows.filter { it.spot.latitude != null && it.spot.longitude != null }
    Box(modifier.fillMaxSize().background(Color(0xFF06151C), RoundedCornerShape(10.dp)).border(1.dp, PotaRaised, RoundedCornerShape(10.dp))) {
        if (valid.isNotEmpty()) PotaNativeMap(valid, selectedId, select, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No filtered spots have coordinates", color = PotaMuted) }
        Surface(color = Color(0xE6192228), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("${valid.size} mapped · ${rows.size - valid.size} without coordinates\nOpenFreeMap © OpenMapTiles · OpenStreetMap", color = PotaMuted, fontSize = 10.sp, modifier = Modifier.padding(6.dp)) }
    }
}

@Composable private fun PotaNativeMap(rows: List<PotaOpportunity>, selectedId: String?, select: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRows by rememberUpdatedState(rows); val currentSelect by rememberUpdatedState(select)
    val mapView = remember { MapLibre.getInstance(context.applicationContext); MapView(context).apply {
        onCreate(null)
        setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
            false
        }
    } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }; var styleReady by remember { mutableStateOf(false) }
    val callbackLifecycle = remember(mapView) { LifecycleGeneration() }
    val markerSelections = remember { mutableMapOf<Long, String>() }
    DisposableEffect(mapView, lifecycle) {
        val callbackGeneration = callbackLifecycle.next(); var disposed = false
        val observer = LifecycleEventObserver { _, event -> when (event) { Lifecycle.Event.ON_START -> mapView.onStart(); Lifecycle.Event.ON_RESUME -> mapView.onResume(); Lifecycle.Event.ON_PAUSE -> mapView.onPause(); Lifecycle.Event.ON_STOP -> mapView.onStop(); else -> Unit } }
        lifecycle.addObserver(observer); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart(); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.getMapAsync { ready -> if (disposed || !callbackLifecycle.isCurrent(callbackGeneration)) return@getMapAsync; map = ready; ready.uiSettings.isAttributionEnabled = true; ready.uiSettings.isLogoEnabled = true; ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { if (callbackLifecycle.isCurrent(callbackGeneration)) styleReady = true }; ready.setOnMarkerClickListener { marker -> if (!callbackLifecycle.isCurrent(callbackGeneration)) false else { markerSelections[marker.id]?.let(currentSelect); true } } }
        onDispose { disposed = true; callbackLifecycle.retire(); lifecycle.removeObserver(observer); map?.setOnMarkerClickListener(null); mapView.onPause(); mapView.onStop(); mapView.onDestroy(); map = null }
    }
    val markerHash = rows.joinToString { it.spot.id }
    LaunchedEffect(map, styleReady, markerHash, selectedId) {
        val ready = map ?: return@LaunchedEffect; if (!styleReady) return@LaunchedEffect; ready.clear(); markerSelections.clear()
        val clustered = rows.groupBy { row -> "${floor(row.spot.latitude!! / 3)}:${floor(row.spot.longitude!! / 3)}" }
        clustered.values.forEach { group ->
            val chosen = group.firstOrNull { it.spot.id == selectedId } ?: group.first()
            val color = if (chosen.spot.id == selectedId) android.graphics.Color.rgb(233, 167, 43) else if (!chosen.worked.parkWorked) android.graphics.Color.rgb(66, 199, 123) else android.graphics.Color.rgb(165, 173, 178)
            val marker = ready.addMarker(MarkerOptions().position(LatLng(group.map { it.spot.latitude!! }.average(), group.map { it.spot.longitude!! }.average()))
                .title(if (group.size > 1) "${group.size} POTA spots" else "${chosen.spot.callsign} · ${chosen.spot.reference}")
                .snippet(if (group.size > 1) "Tap then zoom for this cluster" else chosen.spot.parkName).icon(potaMarker(context, color, group.size > 1)))
            markerSelections[marker.id] = chosen.spot.id
        }
    }
    LaunchedEffect(map, styleReady, markerHash) {
        val ready = map ?: return@LaunchedEffect; if (!styleReady || rows.isEmpty()) return@LaunchedEffect
        if (rows.size == 1) ready.cameraPosition = CameraPosition.Builder().target(LatLng(rows[0].spot.latitude!!, rows[0].spot.longitude!!)).zoom(6.0).build()
        else runCatching { val bounds = LatLngBounds.Builder().also { b -> rows.forEach { b.include(LatLng(it.spot.latitude!!, it.spot.longitude!!)) } }.build(); ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 70), 500) }
    }
    AndroidView({ mapView }, modifier)
}

private fun potaMarker(context: Context, color: Int, cluster: Boolean): org.maplibre.android.annotations.Icon {
    val size = (if (cluster) 34 else 24) * context.resources.displayMetrics.density; val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(size / 2, size / 2, size * .34f, paint); paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .09f; paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size / 2, size / 2, size * .34f, paint)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun formatPotaMHz(hz: Long) = if (hz > 0) "%.3f".format(Locale.US, hz / 1_000_000.0) else "—.———"
private fun ageText(epoch: Long, now: Long): String { if (epoch <= 0) return "never updated"; val seconds = (now - epoch).coerceAtLeast(0); return when { seconds < 60 -> "just now"; seconds < 3600 -> "${seconds / 60}m ago"; seconds < 86_400 -> "${seconds / 3600}h ago"; else -> "${seconds / 86_400}d ago" } }
private fun parkStatus(meta: PotaParkMetadata) = when { !meta.ready -> "park DB not downloaded"; meta.failure.isNotBlank() -> "park update failed"; meta.stale -> "park DB stale"; meta.updateAvailable -> "park update available"; else -> "${meta.rowCount} parks offline" }
private fun dateText(epoch: Long) = if (epoch <= 0) "unknown" else DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epoch))
private fun formatBytes(bytes: Long) = when { bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0); bytes >= 1_000 -> "%.1f kB".format(Locale.US, bytes / 1_000.0); else -> "$bytes B" }

@Suppress("MissingPermission")
private fun lastDeviceGrid(context: Context): String? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.maxByOrNull { it.time }?.let { maidenheadLocator(it.latitude, it.longitude) }
}

internal fun maidenheadLocator(latitude: Double, longitude: Double): String {
    var lon = (longitude + 180.0).coerceIn(0.0, 359.999999); var lat = (latitude + 90.0).coerceIn(0.0, 179.999999)
    val a = ('A'.code + (lon / 20).toInt()).toChar(); val b = ('A'.code + (lat / 10).toInt()).toChar(); lon %= 20.0; lat %= 10.0
    val c = (lon / 2).toInt(); val d = lat.toInt(); lon %= 2.0; lat %= 1.0
    val e = ('A'.code + (lon * 12).toInt()).toChar(); val f = ('A'.code + (lat * 24).toInt()).toChar()
    return "$a$b$c$d$e$f"
}
