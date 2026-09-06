package app.rigweave.mobile

import android.view.MotionEvent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.floor

private val PortableChassis = Color(0xFF111519)
private val PortablePanel = Color(0xFF1B2228)
private val PortableRaised = Color(0xFF283139)
private val PortableInk = Color(0xFFF4F0E7)
private val PortableMuted = Color(0xFFA5ADB2)
private val PortableAmber = Color(0xFFE9A72B)
private val PortableHealthy = Color(0xFF42C77B)
private val PortableBlue = Color(0xFF65A6C7)
private val PortableViolet = Color(0xFFC481D8)
private val PortableDanger = Color(0xFFE4544D)

private enum class PortablePage(val label: String) { ON_AIR("On Air"), MAP("Map"), PLACES("Places") }

@Composable
internal fun PortableChaseScreen(
    controller: PortableController,
    radio: RadioState,
    stationGrid: String,
    foreground: Boolean,
    compact: Boolean,
    onTune: (PortableSpot) -> Unit,
    onTuneAndLog: (PortableSpot) -> Unit,
    potaActivationActive: Boolean = false,
    intelligenceNeeds: Map<String, List<String>> = emptyMap(),
    onP2p: (PortableSpot) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rigweave-portable-filters", Context.MODE_PRIVATE) }
    var page by rememberSaveable { mutableStateOf(PortablePage.ON_AIR) }
    var program by rememberSaveable { mutableStateOf(prefs.getString("program", "ALL") ?: "ALL") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf(prefs.getString("search", "") ?: "") }
    var band by rememberSaveable { mutableStateOf(prefs.getString("band", "ALL") ?: "ALL") }
    var mode by rememberSaveable { mutableStateOf(prefs.getString("mode", "ALL") ?: "ALL") }
    var newOnly by rememberSaveable { mutableStateOf(prefs.getBoolean("new_only", false)) }
    var sort by rememberSaveable { mutableStateOf(runCatching { PortableSort.valueOf(prefs.getString("sort", PortableSort.RECOMMENDED.name)!!) }.getOrDefault(PortableSort.RECOMMENDED)) }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var pendingTune by remember { mutableStateOf<Pair<PortableSpot, Boolean>?>(null) }

    DisposableEffect(controller) {
        onDispose { controller.setSotaClusterActive(false) }
    }
    LaunchedEffect(foreground) { controller.setSotaClusterActive(foreground) }

    LaunchedEffect(controller.requestedSpotId, controller.rankedOpportunities) {
        controller.requestedSpotId?.let { id ->
            if (controller.rankedOpportunities.any { it.spot.id == id }) {
                selectedId = id; page = PortablePage.ON_AIR; controller.consumeRequestedSpot()
            }
        }
    }

    LaunchedEffect(Unit) { controller.pota.checkParksOncePerForegroundDay(); controller.sotaCatalogue.checkOncePerForegroundDay(); controller.refreshAll() }
    LaunchedEffect(foreground, page) {
        while (foreground && page == PortablePage.ON_AIR) { now = Instant.now().epochSecond; controller.markForegroundAge(now); delay(60_000); if (foreground && page == PortablePage.ON_AIR) controller.refreshAll() }
    }
    LaunchedEffect(search, band, mode, newOnly, sort, program) { prefs.edit().putString("search", search).putString("band", band).putString("mode", mode).putBoolean("new_only", newOnly).putString("sort", sort.name).putString("program", program).apply() }

    LaunchedEffect(controller.pota.spots, controller.sotaSpots, controller.wwffSpots, controller.lastQsoRevision,
        now / 15, radio.frequencyHz, stationGrid) {
        controller.refreshOpportunities(now, radio.frequencyHz, stationGrid)
    }
    val all = controller.rankedOpportunities
    val filtered = remember(all, program, search, band, mode, newOnly, sort) {
        val query = search.trim().uppercase(Locale.US)
        sortedPortable(all.filter { row -> val spot = row.spot
            (program == "ALL" || spot.programs.any { it.label == program }) &&
                (query.isBlank() || listOf(spot.callsign, spot.comments) .plus(spot.references.flatMap { listOf(it.code, it.name, it.association, it.region) }).any { it.uppercase(Locale.US).contains(query) }) &&
                (band == "ALL" || spot.band == band) && (mode == "ALL" || modeFamily(spot.mode) == mode) &&
                (!newOnly || row.worked.values.any { !it.referenceWorked || !it.bandWorked || !it.modeWorked } || intelligenceNeeds[spot.id].orEmpty().isNotEmpty()) && spot.activeAt(now)
        }, sort)
    }
    val selected = filtered.firstOrNull { it.spot.id == selectedId } ?: all.firstOrNull { it.spot.id == selectedId }

    pendingTune?.let { (spot, openLog) ->
        val commands = portableCatCommands(spot)
        AlertDialog(onDismissRequest = { pendingTune = null }, title = { Text(if (openLog) "Tune & Log" else "Tune VFO A") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${portableMHz(spot.frequencyHz)} MHz · ${spot.mode.ifBlank { "MODE UNSPECIFIED" }}", fontFamily = FontFamily.Monospace, color = PortableAmber, fontWeight = FontWeight.Bold)
                Text("${spot.callsign} · ${spot.references.joinToString { "${it.program.label} ${it.code}" }}")
                Text(if (commands.size > 1) "Sets VFO A and an unambiguous receive mode. No transmission." else "Sets VFO A only; mode remains a suggestion. No transmission.", color = PortableMuted)
                if (!radio.connected) Text("CAT offline — selection retained.", color = PortableDanger)
            } }, confirmButton = { Button({ if (openLog) onTuneAndLog(spot) else onTune(spot); pendingTune = null }, enabled = radio.connected && spot.frequencyHz > 0) { Text(if (openLog) "Tune & Log" else "Tune") } },
            dismissButton = { TextButton({ pendingTune = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().background(PortableChassis).padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PortableHeader(controller, radio, page, { page = it }, compact)
        if (compact) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { PortablePage.entries.forEachIndexed { index, item -> SegmentedButton(page == item, { page = item }, SegmentedButtonDefaults.itemShape(index, PortablePage.entries.size)) { Text(item.label) } } }
            ProgrammeSelector(program, { program = it }, controller, Modifier.fillMaxWidth())
        } else {
            PortableControlBar(program, { program = it }, controller, search, { search = it }, band, { band = it }, mode,
                { mode = it }, newOnly, { newOnly = it }, sort, { sort = it })
        }
        when (page) {
            PortablePage.MAP -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PortableMap(filtered, selectedId, { selectedId = it }, Modifier.weight(1f))
                selected?.let { PortableDetail(it, radio, { pendingTune = it.spot to false }, { pendingTune = it.spot to true },
                    potaActivationActive, onP2p, intelligenceNeeds, Modifier.heightIn(min = 170.dp, max = 280.dp)) }
            }
            PortablePage.PLACES -> PortablePlaces(controller, stationGrid, program, Modifier.weight(1f))
            PortablePage.ON_AIR -> BoxWithConstraints(Modifier.weight(1f)) {
                if (!compact && maxWidth >= 900.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortableList(filtered, selectedId, { selectedId = it }, intelligenceNeeds, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PortableMap(filtered, selectedId, { selectedId = it }, Modifier.weight(1.25f))
                        PortableDetail(selected, radio, { pendingTune = it.spot to false }, { pendingTune = it.spot to true }, potaActivationActive, onP2p, intelligenceNeeds, Modifier.weight(.75f))
                    }
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PortableCompactFilters(search, { search = it }, band, { band = it }, mode, { mode = it }, newOnly, { newOnly = it }, sort, { sort = it })
                    PortableList(filtered, selectedId, { selectedId = it }, intelligenceNeeds, Modifier.weight(1f)); selected?.let { PortableDetail(it, radio, { pendingTune = it.spot to false }, { pendingTune = it.spot to true }, potaActivationActive, onP2p, intelligenceNeeds, Modifier.heightIn(min = 190.dp, max = 320.dp)) }
                }
            }
        }
    }
}

@Composable private fun PortableHeader(
    controller: PortableController, radio: RadioState, page: PortablePage, changePage: (PortablePage) -> Unit, compact: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PORTABLE CHASE", color = PortableAmber, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
            modifier = if (compact) Modifier.weight(1f) else Modifier)
        if (!compact) SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            PortablePage.entries.forEachIndexed { index, item ->
                SegmentedButton(page == item, { changePage(item) }, SegmentedButtonDefaults.itemShape(index, PortablePage.entries.size)) {
                    Text(item.label)
                }
            }
        }
        PortableStatus(if (radio.connected) "CAT LIVE" else "CAT OFFLINE", radio.connected)
        IconButton(controller::refreshAll, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Refresh portable activity") }
    }
}

@Composable private fun ProgrammeSelector(selected: String, change: (String) -> Unit, controller: PortableController, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("ALL" to null, "POTA" to PortableProgram.POTA, "SOTA" to PortableProgram.SOTA, "WWFF" to PortableProgram.WWFF).forEach { (label, provider) ->
            val status = provider?.let(controller::providerStatus)
            FilterChip(selected == label, { change(label) }, {
                Text(if (status == null) label else "$label · ${status.count}", fontSize = 11.sp)
            }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable private fun PortableControlBar(
    program: String, changeProgram: (String) -> Unit, controller: PortableController,
    search: String, changeSearch: (String) -> Unit, band: String, changeBand: (String) -> Unit,
    mode: String, changeMode: (String) -> Unit, newOnly: Boolean, changeNew: (Boolean) -> Unit,
    sort: PortableSort, changeSort: (PortableSort) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgrammeSelector(program, changeProgram, controller, Modifier)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(search, changeSearch, label = { Text("Call / park / summit") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.width(280.dp))
        Box(Modifier.width(125.dp)) { PortableChoice("Band", band, listOf("ALL", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"), changeBand) }
        Box(Modifier.width(135.dp)) { PortableChoice("Mode", mode, listOf("ALL", "CW", "SSB", "FT8", "FT4", "RTTY", "DIGITAL", "FM", "AM"), changeMode) }
        Box(Modifier.width(175.dp)) { PortableChoice("Sort", sort.label, PortableSort.entries.map(PortableSort::label)) {
            changeSort(PortableSort.entries.first { item -> item.label == it })
        } }
        FilterChip(newOnly, { changeNew(!newOnly) }, { Text("New") }, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable private fun PortableStatus(text: String, good: Boolean) = Surface(color = (if (good) PortableHealthy else PortableAmber).copy(alpha = .14f), shape = RoundedCornerShape(6.dp)) { Text(text, color = if (good) PortableHealthy else PortableAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) }

@Composable private fun PortableFilters(search: String, changeSearch: (String) -> Unit, band: String, changeBand: (String) -> Unit, mode: String, changeMode: (String) -> Unit, newOnly: Boolean, changeNew: (Boolean) -> Unit, sort: PortableSort, changeSort: (PortableSort) -> Unit, modifier: Modifier) {
    Column(modifier.background(PortablePanel, RoundedCornerShape(10.dp)).border(1.dp, PortableRaised, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FILTERS", color = PortableAmber, fontWeight = FontWeight.Black); OutlinedTextField(search, changeSearch, label = { Text("Call / reference / place") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PortableChoice("Band", band, listOf("ALL", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"), changeBand)
        PortableChoice("Mode", mode, listOf("ALL", "CW", "SSB", "FT8", "FT4", "RTTY", "DIGITAL", "FM", "AM"), changeMode)
        PortableChoice("Sort", sort.label, PortableSort.entries.map(PortableSort::label)) { changeSort(PortableSort.entries.first { item -> item.label == it }) }
        FilterChip(newOnly, { changeNew(!newOnly) }, { Text("New opportunities") })
    }
}

@Composable private fun PortableCompactFilters(search: String, changeSearch: (String) -> Unit, band: String, changeBand: (String) -> Unit, mode: String, changeMode: (String) -> Unit, newOnly: Boolean, changeNew: (Boolean) -> Unit, sort: PortableSort, changeSort: (PortableSort) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { OutlinedTextField(search, changeSearch, label = { Text("Search call, place, reference") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) { PortableChoice("Band", band, listOf("ALL", "80m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"), changeBand) }; Box(Modifier.weight(1f)) { PortableChoice("Mode", mode, listOf("ALL", "CW", "SSB", "FT8", "FT4", "RTTY", "DIGITAL", "FM"), changeMode) }; Box(Modifier.weight(1f)) { PortableChoice("Sort", sort.label, PortableSort.entries.map(PortableSort::label)) { changeSort(PortableSort.entries.first { item -> item.label == it }) } }; FilterChip(newOnly, { changeNew(!newOnly) }, { Text("NEW") }, modifier = Modifier.heightIn(min = 48.dp))
    } }
}

@Composable private fun PortableChoice(label: String, value: String, choices: List<String>, change: (String) -> Unit) { var open by remember { mutableStateOf(false) }; Box { OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 7.dp)) { Text("$label · $value", maxLines = 1, overflow = TextOverflow.Ellipsis) }; DropdownMenu(open, { open = false }) { choices.forEach { item -> DropdownMenuItem({ Text(item) }, onClick = { change(item); open = false }, trailingIcon = { if (item == value) Icon(Icons.Outlined.Check, null) }) } } } }

@Composable private fun PortableList(rows: List<PortableOpportunity>, selectedId: String?, select: (String) -> Unit,
    intelligenceNeeds: Map<String, List<String>>, modifier: Modifier) {
    if (rows.isEmpty()) Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching active portable activity", color = PortableMuted) }
    else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 8.dp)) { items(rows, key = { it.spot.id }) { row -> val spot = row.spot; val selected = selectedId == spot.id
        Column(Modifier.fillMaxWidth().background(if (selected) PortableAmber.copy(alpha = .13f) else PortablePanel, RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) PortableAmber else PortableRaised, RoundedCornerShape(8.dp))
            .clickable { select(spot.id) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val tags = (spot.programs.map(PortableProgram::label) + intelligenceNeeds[spot.id].orEmpty() +
                row.worked.flatMap { (programme, worked) -> worked.labels(programme) } + row.reasons.map(String::uppercase)).distinct().take(4)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(spot.callsign, color = PortableInk, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    tags.forEach { tag -> Box(Modifier.padding(horizontal = 2.dp)) { PortableOpportunityTag(tag) } }
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = PortableMuted)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(spot.references.joinToString("  ") { "${it.program.label} ${it.code} · ${it.name.ifBlank { "Name unavailable" }}" }, color = PortableInk,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${portableMHz(spot.frequencyHz)} MHz · ${spot.mode}", color = PortableAmber, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
            Text(listOf(portableAge(spot.spottedAt),
                row.distanceKm?.let { "%.0f km · %03d°".format(it, row.bearingDegrees ?: 0) }).filterNotNull().joinToString(" · "),
                color = PortableMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } }
}

@Composable private fun PortableOpportunityTag(label: String) {
    val color = when {
        label == "POTA" || label.contains("PARK") -> PortableHealthy
        label == "SOTA" || label.contains("SUMMIT") -> PortableBlue
        label == "WWFF" || label.contains("REFERENCE") -> PortableViolet
        label.contains("FRESH") -> PortableAmber
        label.contains("BAND") -> PortableBlue
        label.contains("MODE") -> PortableViolet
        label.contains("WORKED") -> PortableMuted
        else -> PortableAmber
    }
    Surface(color = color.copy(alpha = .16f), shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, color.copy(alpha = .55f))) {
        Text(label.lowercase().replaceFirstChar(Char::uppercase), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable private fun PortableDetail(row: PortableOpportunity?, radio: RadioState, tune: (PortableOpportunity) -> Unit, tuneLog: (PortableOpportunity) -> Unit,
    potaActivationActive: Boolean, onP2p: (PortableSpot) -> Unit, intelligenceNeeds: Map<String, List<String>>, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(PortablePanel, RoundedCornerShape(10.dp)).border(1.dp, PortableRaised, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (row == null) { Text("SELECT AN ACTIVITY", color = PortableAmber, fontWeight = FontWeight.Black); Text("Selection never tunes. Review details, then choose Tune or Tune & Log.", color = PortableMuted); return@Column }
        val spot = row.spot; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(spot.callsign, color = PortableAmber, fontWeight = FontWeight.Black); Text("SCORE ${row.score}", color = PortableMuted, fontFamily = FontFamily.Monospace) }
        spot.references.forEach { ref -> Text("${ref.program.label} ${ref.code} · ${ref.name.ifBlank { "name unavailable" }}", color = PortableInk, fontWeight = FontWeight.SemiBold); val detail = listOf(ref.association, ref.region, ref.altitudeM?.let { "$it m" }, ref.points?.let { "$it points" }, ref.activeAgenda.takeIf(String::isNotBlank)?.let { "AGENDA · $it" }).filterNotNull().filter(String::isNotBlank).joinToString(" · "); if (detail.isNotBlank()) Text(detail, color = PortableMuted, fontSize = 12.sp) }
        Text("${portableMHz(spot.frequencyHz)} MHz · ${spot.mode.ifBlank { "mode unspecified" }} · ${spot.band} · via ${spot.source}", color = PortableInk, fontFamily = FontFamily.Monospace); if (spot.comments.isNotBlank()) Text(spot.comments, color = PortableMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        intelligenceNeeds[spot.id].orEmpty().takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = PortableAmber, fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ tune(row) }, enabled = radio.connected, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Tune") }; Button({ tuneLog(row) }, enabled = radio.connected, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) { Text("Tune & Log", fontWeight = FontWeight.Black) } }
        if (potaActivationActive && PortableProgram.POTA in spot.programs) Button({ onP2p(spot) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("P2P LOG · OPEN EDITABLE DRAFT", fontWeight = FontWeight.Black)
        }
        if (!radio.connected) Text("CAT offline · selection retained · zero commands sent", color = PortableDanger, fontSize = 12.sp)
    }
}

@Composable private fun PortablePlaces(controller: PortableController, stationGrid: String, selectedProgram: String, modifier: Modifier) {
    var program by rememberSaveable { mutableStateOf(if (selectedProgram == "ALL") "POTA" else selectedProgram) }
    val pages = listOf("POTA", "SOTA", "WWFF", "CATALOGUES")
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { pages.forEachIndexed { index, item ->
            SegmentedButton(program == item, { program = item }, SegmentedButtonDefaults.itemShape(index, pages.size)) {
                Text(when (item) { "POTA" -> "POTA Parks"; "SOTA" -> "SOTA Summits"; "WWFF" -> "WWFF"; else -> "More catalogues" })
            }
        } }
        when (program) {
            "POTA" -> PotaParks(controller.pota, stationGrid, Modifier.weight(1f))
            "SOTA" -> SotaPlaces(controller.sotaCatalogue, stationGrid, Modifier.weight(1f))
            "WWFF" -> WwffPlaces(controller, Modifier.weight(1f))
            else -> PortableCataloguePlaces(controller.catalogues, Modifier.weight(1f))
        }
    }
}

@Composable private fun PortableCataloguePlaces(registry: PortableCatalogueRegistry, modifier: Modifier,
    initialProgramme: PortableCatalogueProgram = PortableCatalogueProgram.IOTA, showProgrammeTabs: Boolean = true) {
    val context = LocalContext.current
    val inAppBrowser = LocalInAppBrowserState.current
    var programme by rememberSaveable(initialProgramme) { mutableStateOf(initialProgramme) }
    var query by rememberSaveable { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32_768); var total = 0
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                require(total <= 32_000_000) { "Import exceeds 32 MB" }; output.write(buffer, 0, count) }
            registry.importAuthorised(programme, output.toByteArray(), uri.lastPathSegment.orEmpty())
        } }
    }
    LaunchedEffect(programme, query, registry.statuses) { registry.search(programme, query) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showProgrammeTabs) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PortableCatalogueProgram.entries.forEach { item -> FilterChip(programme == item, { programme = item }, { Text(item.label) }) }
        }
        val status = registry.statuses.getValue(programme)
        Surface(color = PortablePanel, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, PortableRaised)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${programme.label} · ${status.state.name.replace('_', ' ')}", color = if (status.state in setOf(PortableCatalogueState.AVAILABLE, PortableCatalogueState.OFFLINE_CACHE, PortableCatalogueState.USER_IMPORT)) PortableHealthy else PortableAmber, fontWeight = FontWeight.Black)
                        Text("${status.rowCount} rows · ${if (status.updatedAt > 0) "updated ${Instant.ofEpochSecond(status.updatedAt)}" else "never updated"} · ${status.digest.take(12).ifBlank { "no digest" }}", color = PortableMuted)
                        Text(status.reason, color = PortableMuted)
                        Text(status.source, color = PortableBlue, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when (programme) {
                        PortableCatalogueProgram.IOTA -> Button(registry::refreshIota, enabled = !status.busy) { Text(if (status.rowCount > 0) "UPDATE" else "DOWNLOAD") }
                        PortableCatalogueProgram.WWFF -> Button(registry::refreshWwff, enabled = !status.busy) { Text(if (status.rowCount > 0) "UPDATE" else "DOWNLOAD") }
                        else -> OutlinedButton({ importer.launch(arrayOf("text/csv", "text/comma-separated-values", "application/octet-stream")) }) { Text("IMPORT") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ inAppBrowser?.open(status.source) }) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OFFICIAL SOURCE") }
                    Text("Official download where available · no scraping · private last-good only", color = PortableMuted, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(),
            label = { Text("Reference, name, DXCC, entity, country or region") }, singleLine = true)
        if (status.rowCount == 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Provider is ${status.state.name.replace('_', ' ').lowercase()} — this is not an empty result.", color = PortableMuted)
        } else LazyVerticalGrid(GridCells.Adaptive(330.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            gridItems(registry.results, key = { "${it.programme}:${it.reference}" }) { place ->
                Surface(color = PortablePanel, shape = RoundedCornerShape(8.dp)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${place.reference} · ${place.name}", color = PortableInk, fontWeight = FontWeight.Bold)
                    Text(listOf(place.entity, place.dxcc.takeIf(String::isNotBlank)?.let { "DXCC $it" }, place.region,
                        place.members.takeIf { it.isNotEmpty() }?.let { "${it.size} listed islands" }).filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = PortableMuted)
                    Text(if (place.latitudeMin != null && place.latitudeMax != null && place.longitudeMin != null && place.longitudeMax != null)
                        "Official bounds available · no fabricated centroid" else "No valid provider geometry · not mapped", color = PortableMuted, fontSize = 11.sp)
                    if (place.officialUrl.isNotBlank()) OutlinedButton({ inAppBrowser?.open(place.officialUrl) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PortableAmber)) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OFFICIAL DIRECTORY") }
                } }
            }
        }
    }
}

@Composable private fun SotaPlaces(catalogue: SotaCatalogue, stationGrid: String, modifier: Modifier) {
    val context = LocalContext.current; val inAppBrowser = LocalInAppBrowserState.current; var query by rememberSaveable { mutableStateOf("") }; var association by rememberSaveable { mutableStateOf("") }; var region by rememberSaveable { mutableStateOf("") }; var grid by rememberSaveable { mutableStateOf(stationGrid) }; var nearby by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query, association, region, grid, nearby, catalogue.metadata.ready) { delay(180); catalogue.search(query, association, region, grid, nearby) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Surface(color = PortablePanel, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PortableRaised)) { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(if (catalogue.metadata.ready) "SOTA SUMMIT DATABASE ${if (catalogue.metadata.stale) "STALE" else "READY"}" else "DOWNLOAD OFFICIAL SOTA SUMMITS", color = PortableAmber, fontWeight = FontWeight.Black); Text(if (catalogue.metadata.ready) "${catalogue.metadata.rowCount} summits · offline search available" else "Explicit, staged app-private import from SOTA", color = PortableMuted) }; if (catalogue.busy) OutlinedButton(catalogue::cancelUpdate) { Text("Cancel") } else Button(catalogue::update) { Text(if (catalogue.metadata.ready) "Update now" else "Download") } }; if (catalogue.busy) LinearProgressIndicator({ catalogue.progress / 100f }, Modifier.fillMaxWidth()); if (catalogue.metadata.failure.isNotBlank()) Text(catalogue.metadata.failure, color = PortableAmber); Text("Summit data © Summits on the Air. RigWeave is independent.", color = PortableMuted, fontSize = 11.sp) } }
        if (catalogue.metadata.ready) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(query, { query = it.uppercase() }, label = { Text("Reference or summit") }, modifier = Modifier.weight(2f), singleLine = true); OutlinedTextField(association, { association = it }, label = { Text("Association") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(region, { region = it }, label = { Text("Region") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(grid, { grid = it.uppercase().take(8) }, label = { Text("Station / manual grid") }, modifier = Modifier.width(160.dp), singleLine = true); FilterChip(nearby, { nearby = !nearby }, { Text("Nearby") }, leadingIcon = { Icon(Icons.Outlined.NearMe, null) }) }; LazyVerticalGrid(columns = GridCells.Adaptive(360.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { gridItems(catalogue.results, key = SotaSummit::code) { summit -> Row(Modifier.fillMaxWidth().background(PortablePanel, RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${summit.code} · ${summit.name}", color = PortableInk, fontWeight = FontWeight.Bold); Text(listOf(summit.association, summit.region, summit.altitudeM?.let { "$it m" }, summit.points?.let { "$it points" }, summit.distanceKm?.let { "%.1f km · %03d°".format(it, summit.bearingDegrees ?: 0) }, if (summit.active) "VALID" else "RETIRED").filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = PortableMuted) }; IconButton({ inAppBrowser?.open("https://www.sotadata.org.uk/en/summit/${summit.code}") }, colors = IconButtonDefaults.iconButtonColors(containerColor = PortableAmber, contentColor = Color(0xFF201708))) { Icon(Icons.Outlined.OpenInNew, "Open official SOTA summit page") } } } } }
    }
}

@Composable private fun WwffPlaces(controller: PortableController, modifier: Modifier) {
    val inAppBrowser = LocalInAppBrowserState.current
    val status = controller.catalogues.statuses.getValue(PortableCatalogueProgram.WWFF)
    val now = Instant.now().epochSecond
    val live = remember(controller.rankedOpportunities, now / 15) {
        controller.rankedOpportunities.filter { row ->
            PortableProgram.WWFF in row.spot.programs && row.spot.activeAt(now)
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = PortablePanel, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PortableRaised)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LIVE WWFF REFERENCES · ${controller.wwffStatus.kind.name} · ${live.size} visible",
                        color = PortableAmber, fontWeight = FontWeight.Black)
                    Text(if (status.rowCount > 0) "${status.rowCount} authorised offline catalogue rows available"
                        else "Full directory requires authorised import · More catalogues → WWFF",
                        color = PortableMuted)
                }
                OutlinedButton({ inAppBrowser?.open(status.source) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PortableAmber)) {
                    Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OFFICIAL DIRECTORY")
                }
            }
        }
        if (status.rowCount > 0) {
            PortableCataloguePlaces(controller.catalogues, Modifier.weight(1f), PortableCatalogueProgram.WWFF, showProgrammeTabs = false)
        } else if (live.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No active WWFF references in the current public feeds.", color = PortableMuted)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(360.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                gridItems(live, key = { it.spot.id }) { row ->
                    val spot = row.spot
                    val references = spot.references.filter { it.program == PortableProgram.WWFF }
                    Column(Modifier.fillMaxWidth().background(PortablePanel, RoundedCornerShape(8.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(references.joinToString(" · ") { ref -> "${ref.code} · ${ref.name.ifBlank { "Name unavailable" }}" },
                            color = PortableInk, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${spot.callsign} · ${portableMHz(spot.frequencyHz)} MHz · ${spot.mode.ifBlank { "mode unspecified" }}",
                            color = PortableAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text((references.flatMap { listOf(it.association, it.region) } + portableAge(spot.spottedAt) + "via ${spot.source}")
                            .filter(String::isNotBlank).distinct().joinToString(" · "), color = PortableMuted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable private fun PortableMap(rows: List<PortableOpportunity>, selectedId: String?, select: (String) -> Unit, modifier: Modifier) {
    val valid = rows.filter { it.spot.latitude != null && it.spot.longitude != null }
    val mapped = valid.take(200)
    Box(modifier.fillMaxSize().background(Color(0xFF06151C), RoundedCornerShape(10.dp)).border(1.dp, PortableRaised, RoundedCornerShape(10.dp))) {
        if (mapped.isNotEmpty()) PortableNativeMap(mapped, selectedId, select, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No filtered activity has coordinates", color = PortableMuted) }
        Surface(color = Color(0xE6192228), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("${mapped.size} of ${valid.size} mapped · ${rows.size - valid.size} without coordinates\nOpenFreeMap © OpenMapTiles · OpenStreetMap",
                color = PortableMuted, fontSize = 10.sp, modifier = Modifier.padding(6.dp))
        }
    }
}

@Composable private fun PortableNativeMap(rows: List<PortableOpportunity>, selectedId: String?, select: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current.lifecycle; val currentSelect by rememberUpdatedState(select); val mapView = remember { MapLibre.getInstance(context.applicationContext); MapView(context).apply { onCreate(null); setOnTouchListener { view, event -> view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)); false } } }; val markerSelections = remember { mutableMapOf<Long, String>() }; var map by remember { mutableStateOf<MapLibreMap?>(null) }; var styleReady by remember { mutableStateOf(false) }; var userMoved by remember { mutableStateOf(false) }
    val callbackLifecycle = remember(mapView) { LifecycleGeneration() }
    DisposableEffect(mapView, lifecycle) {
        val callbackGeneration = callbackLifecycle.next()
        var started = false; var resumed = false; var destroyed = false
        fun start() { if (!started && !destroyed) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed && !destroyed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed && !destroyed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started && !destroyed) { mapView.onStop(); started = false } }
        fun destroy() { if (!destroyed) { stop(); mapView.onDestroy(); destroyed = true } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_RESUME -> resume(); Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop(); Lifecycle.Event.ON_DESTROY -> destroy(); else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        var disposed = false
        mapView.getMapAsync { ready ->
            if (disposed) return@getMapAsync
            map = ready
            ready.uiSettings.apply {
                isAttributionEnabled = true; isLogoEnabled = true; isCompassEnabled = true
                isZoomGesturesEnabled = true; isScrollGesturesEnabled = true; isRotateGesturesEnabled = true; isTiltGesturesEnabled = false
            }
            ready.setMinZoomPreference(.8); ready.setMaxZoomPreference(14.0)
            ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                if (callbackLifecycle.isCurrent(callbackGeneration)) { installPortableLabelLayers(style); styleReady = true }
            }
            ready.addOnCameraMoveStartedListener { reason -> if (callbackLifecycle.isCurrent(callbackGeneration) && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved = true }
            ready.setOnMarkerClickListener { marker ->
                if (!callbackLifecycle.isCurrent(callbackGeneration)) return@setOnMarkerClickListener false
                markerSelections[marker.id]?.let(currentSelect)
                true
            }
            ready.addOnMapClickListener { point -> val feature = ready.queryRenderedFeatures(ready.projection.toScreenLocation(point), PORTABLE_SELECTED_LABEL_LAYER, PORTABLE_LABEL_LAYER).firstOrNull(); feature?.getStringProperty("spot_id")?.let(currentSelect); feature != null }
        }
        onDispose { disposed = true; callbackLifecycle.retire(); lifecycle.removeObserver(observer); map?.setOnMarkerClickListener(null); styleReady = false; map = null; destroy() }
    }
    val markerHash = rows.joinToString { "${it.spot.id}:${it.spot.latitude}:${it.spot.longitude}:${it.spot.callsign}" }
    LaunchedEffect(map, styleReady, markerHash, selectedId) {
        val ready = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        ready.style?.let { style ->
            installPortableLabelLayers(style)
            style.getSourceAs<GeoJsonSource>(PORTABLE_LABEL_SOURCE)?.setGeoJson(portableLabelGeoJson(rows, selectedId))
        }
        ready.clear()
        markerSelections.clear()
        rows.groupBy { "${floor(it.spot.latitude!! / 3)}:${floor(it.spot.longitude!! / 3)}" }.values.forEach { group ->
            val chosen = group.firstOrNull { it.spot.id == selectedId } ?: group.first()
            val isSelected = chosen.spot.id == selectedId
            val color = when {
                isSelected -> android.graphics.Color.rgb(233, 167, 43)
                chosen.spot.programs.size > 1 -> android.graphics.Color.rgb(244, 201, 78)
                chosen.spot.programs.contains(PortableProgram.POTA) -> android.graphics.Color.rgb(66, 199, 123)
                chosen.spot.programs.contains(PortableProgram.SOTA) -> android.graphics.Color.rgb(101, 166, 199)
                else -> android.graphics.Color.rgb(196, 129, 216)
            }
            val title = if (isSelected || group.size == 1) chosen.spot.callsign else "${group.size} portable activities"
            val place = chosen.spot.references.joinToString(" · ") {
                "${it.program.label} ${it.code} · ${it.name.ifBlank { it.region.ifBlank { it.association.ifBlank { "Location unavailable" } } }}"
            }
            val marker = ready.addMarker(
                MarkerOptions()
                    .position(LatLng(group.map { it.spot.latitude!! }.average(), group.map { it.spot.longitude!! }.average()))
                    .title(title)
                    .snippet(place)
                    .icon(portableMarker(context, color, group.size > 1)),
            )
            markerSelections[marker.id] = chosen.spot.id
        }
    }
    LaunchedEffect(map, styleReady, selectedId) {
        val ready = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val selected = rows.firstOrNull { it.spot.id == selectedId } ?: return@LaunchedEffect
        val latitude = selected.spot.latitude ?: return@LaunchedEffect
        val longitude = selected.spot.longitude ?: return@LaunchedEffect
        userMoved = false
        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 8.0), 450)
    }
    LaunchedEffect(map, styleReady, markerHash) { val ready = map ?: return@LaunchedEffect; if (!styleReady || rows.isEmpty() || userMoved) return@LaunchedEffect; if (rows.size == 1) ready.cameraPosition = CameraPosition.Builder().target(LatLng(rows[0].spot.latitude!!, rows[0].spot.longitude!!)).zoom(6.0).build() else runCatching { val bounds = LatLngBounds.Builder().also { b -> rows.forEach { b.include(LatLng(it.spot.latitude!!, it.spot.longitude!!)) } }.build(); ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 70), 500) } }; AndroidView({ mapView }, modifier)
}

private const val PORTABLE_LABEL_SOURCE = "portable-coordinate-labels"
private const val PORTABLE_LABEL_LAYER = "portable-coordinate-labels-visible"
private const val PORTABLE_SELECTED_LABEL_LAYER = "portable-coordinate-label-selected"

private fun installPortableLabelLayers(style: Style) {
    if (style.getSourceAs<GeoJsonSource>(PORTABLE_LABEL_SOURCE) == null) style.addSource(GeoJsonSource(PORTABLE_LABEL_SOURCE, portableLabelGeoJson(emptyList(), null)))
    if (style.getLayerAs<SymbolLayer>(PORTABLE_LABEL_LAYER) == null) {
        style.addLayer(SymbolLayer(PORTABLE_LABEL_LAYER, PORTABLE_LABEL_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")), PropertyFactory.textSize(12f),
            PropertyFactory.textColor(android.graphics.Color.WHITE), PropertyFactory.textHaloColor(android.graphics.Color.rgb(6, 21, 28)),
            PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOffset(arrayOf(0f, 1.25f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textAllowOverlap(false), PropertyFactory.textOptional(true),
        ).apply { setFilter(Expression.neq(Expression.get("selected"), true)); setMinZoom(6.0f) })
    }
    if (style.getLayerAs<SymbolLayer>(PORTABLE_SELECTED_LABEL_LAYER) == null) {
        style.addLayer(SymbolLayer(PORTABLE_SELECTED_LABEL_LAYER, PORTABLE_LABEL_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")), PropertyFactory.textSize(13f),
            PropertyFactory.textColor(android.graphics.Color.rgb(255, 215, 118)), PropertyFactory.textHaloColor(android.graphics.Color.rgb(6, 21, 28)),
            PropertyFactory.textHaloWidth(2f), PropertyFactory.textOffset(arrayOf(0f, 1.25f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textAllowOverlap(true), PropertyFactory.textOptional(false),
        ).apply { setFilter(Expression.eq(Expression.get("selected"), true)) })
    }
}

internal fun portableLabelGeoJson(rows: List<PortableOpportunity>, selectedId: String?): String {
    val features = JSONArray()
    rows.forEach { row ->
        val latitude = row.spot.latitude ?: return@forEach
        val longitude = row.spot.longitude ?: return@forEach
        val compactReference = row.spot.primary.let { "${it.program.label} ${it.code}" }
        val location = row.spot.primary.let { it.name.ifBlank { it.region.ifBlank { it.association.ifBlank { "Location unavailable" } } } }
        features.put(JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(longitude).put(latitude)))
            .put("properties", JSONObject()
                .put("spot_id", row.spot.id)
                .put("selected", row.spot.id == selectedId)
                .put("label", "${row.spot.callsign} · $compactReference\n$location")))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun portableMarker(context: Context, color: Int, cluster: Boolean): org.maplibre.android.annotations.Icon { val size = (if (cluster) 34 else 24) * context.resources.displayMetrics.density; val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }; canvas.drawCircle(size / 2, size / 2, size * .34f, paint); paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .09f; paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size / 2, size / 2, size * .34f, paint); return IconFactory.getInstance(context).fromBitmap(bitmap) }
private fun portableMHz(hz: Long) = if (hz > 0) "%.3f".format(Locale.US, hz / 1_000_000.0) else "—.———"
private fun portableAge(epoch: Long): String { val seconds = (Instant.now().epochSecond - epoch).coerceAtLeast(0); return when { seconds < 60 -> "just now"; seconds < 3600 -> "${seconds / 60}m ago"; else -> "${seconds / 3600}h ago" } }
