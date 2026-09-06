package app.shackcq.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val SatAmber = Color(0xFFE9A72B)
private val SatPanel = Color(0xFF1B2228)
private val SatInk = Color(0xFFF4F0E7)
private val SatMuted = Color(0xFFA5ADB2)
private val SatGood = Color(0xFF42C77B)
private val SatBad = Color(0xFFE4544D)
private val satelliteSections = listOf("NEXT PASSES", "LIVE TRACK", "SKY PLOT", "STATUS", "TIMERS", "QO-100", "CATALOGUE")

internal data class GeoSatelliteLookAngle(val azimuthDeg: Double, val elevationDeg: Double, val visible: Boolean)

internal fun geostationaryLookAngle(observer: GeoPoint, satelliteLongitudeDeg: Double = 25.9): GeoSatelliteLookAngle {
    val latitude = Math.toRadians(observer.latitude)
    val deltaLongitude = Math.toRadians(satelliteLongitudeDeg - observer.longitude)
    val centralCosine = cos(latitude) * cos(deltaLongitude)
    val elevation = Math.toDegrees(atan2(centralCosine - 0.15127,
        sqrt((1.0 - centralCosine * centralCosine).coerceAtLeast(0.0))))
    val azimuth = (Math.toDegrees(atan2(sin(deltaLongitude), -sin(latitude) * cos(deltaLongitude))) + 360.0) % 360.0
    return GeoSatelliteLookAngle(azimuth, elevation, elevation > 0.0)
}

@Composable
internal fun SatelliteOperationsScreen(
    controller: SatelliteOperationsController,
    stationCallsign: String,
    stationGrid: String,
    activationGrid: String?,
    mutations: QsoMutationCoordinator,
    wavelog: WavelogController,
    callbook: CallbookController,
    progress: ProgressController,
    openLogbook: () -> Unit,
    tuneReceive: (Long, String?) -> Unit,
    normalLogger: (SatellitePassRow) -> Unit,
) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableStateOf("NEXT PASSES") }
    var fastDraft by remember { mutableStateOf<String?>(null) }
    var tunePreview by remember { mutableStateOf<SatellitePassRow?>(null) }
    var manualGrid by rememberSaveable(controller.observerGrid) { mutableStateOf(controller.observerGrid.ifBlank { stationGrid }) }
    var hours by rememberSaveable { mutableIntStateOf(controller.windowHours) }
    var minimum by rememberSaveable { mutableStateOf(controller.minimumElevation.toString()) }
    var mode by rememberSaveable { mutableStateOf(controller.modeFilter) }
    var favouritesOnly by rememberSaveable { mutableStateOf(controller.favouritesOnly) }
    var utc by rememberSaveable { mutableStateOf(controller.utc) }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var gpsPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val gps = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) satelliteLastKnownPoint(context)?.let { gpsPoint = it; manualGrid = maidenheadGrid(it.latitude, it.longitude) }
    }
    LaunchedEffect(controller.favourites.size) {
        if (controller.favourites.isEmpty() && favouritesOnly) {
            favouritesOnly = false
            controller.updatePreferences(manualGrid,hours,minimum.toDoubleOrNull()?:10.0,false,utc,mode)
            controller.predict()
        }
    }
    LaunchedEffect(section, controller.selectedPass) {
        while (section in setOf("NEXT PASSES", "LIVE TRACK", "SKY PLOT", "TIMERS")) {
            now = Instant.now().epochSecond
            if (section in setOf("LIVE TRACK", "SKY PLOT") && controller.selectedPass != null) controller.updateLivePoint(now)
            delay(1_000)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            satelliteSections.forEach { item -> FilterChip(section == item, { section = item }, { Text(item) }) }
        }
        SatelliteProviderStrip(controller.elements.metadata)
        when (section) {
            "NEXT PASSES" -> NextPasses(controller, stationGrid, activationGrid, manualGrid, { manualGrid = it }, hours, { hours = it }, minimum, { minimum = it }, mode, { mode = it }, favouritesOnly, { favouritesOnly = it }, utc, { utc = it }, now,
                requestGps = { if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) satelliteLastKnownPoint(context)?.let { gpsPoint=it;manualGrid=maidenheadGrid(it.latitude,it.longitude) } else gps.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                apply = { controller.updatePreferences(manualGrid,hours,minimum.toDoubleOrNull()?:10.0,favouritesOnly,utc,mode);controller.predict() },
                openTrack = { controller.select(it); section="LIVE TRACK" }, openSky = { controller.select(it);section="SKY PLOT" },
                prepare = { fastDraft=controller.prepareDraft(it) }, normalLogger = normalLogger, tune = { tunePreview=it },
                logbook = { progress.requestLogbook(LogbookFilter(propagation="SAT",satellite=it.satellite.name));openLogbook() })
            "LIVE TRACK" -> LiveTrack(controller, stationGrid, now) { section="NEXT PASSES" }
            "SKY PLOT" -> SkyPlotPanel(controller, minimum.toDoubleOrNull() ?: controller.minimumElevation) { section="NEXT PASSES" }
            "STATUS" -> SatelliteStatusPanel(controller) { row -> progress.requestLogbook(LogbookFilter(propagation="SAT",satellite=row.name.substringBefore('_')));openLogbook() }
            "TIMERS" -> SatelliteTimersPanel(controller,now) { row -> controller.select(row);section="LIVE TRACK" }
            "QO-100" -> Qo100Panel(stationGrid)
            "CATALOGUE" -> SatelliteCataloguePanel(controller) { selected -> controller.passes.firstOrNull { it.satellite.noradId == selected.noradId }?.let { controller.select(it);section="LIVE TRACK" } }
        }
        Text(controller.message, color = SatMuted, style = MaterialTheme.typography.bodySmall)
    }
    fastDraft?.let { draft -> FastEntryDialog(mutations,wavelog,callbook,stationCallsign,{_,_->controller.predict()},draft){fastDraft=null} }
    tunePreview?.let { row ->
        val frequency = row.transponder?.downlinkLowHz
        val shifted = frequency?.let { hz -> controller.livePoint?.let { NativeSatellite.shiftedFrequencyHz(hz,it.rangeRateKmS) } ?: hz }
        AlertDialog(onDismissRequest={tunePreview=null},title={Text("Receive tune preview")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("${row.satellite.name} · ${row.transponder?.mode.orEmpty()}")
            Text("Catalog downlink ${frequency?.let(::formatSatelliteFrequency) ?: "unavailable"}")
            Text("Current receive preview ${shifted?.let(::formatSatelliteFrequency) ?: "unavailable"}")
            Text("This is receive-only and runs only after your explicit confirmation. It does not set TX, PTT, TUNE, or background Doppler follow.",color=SatMuted)
        }},confirmButton={Button({shifted?.let{tuneReceive(it,row.transponder?.mode)};tunePreview=null},enabled=shifted!=null){Text("TUNE RECEIVE")}},dismissButton={TextButton({tunePreview=null}){Text("CANCEL")}})
    }
}

@Composable
private fun Qo100Panel(stationGrid: String) {
    val inAppBrowser = LocalInAppBrowserState.current
    val observer = remember(stationGrid) { maidenheadCenter(stationGrid) }
    val look = remember(observer) { observer?.let(::geostationaryLookAngle) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SatPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("QO-100 · ES'HAIL-2", color = SatAmber, fontWeight = FontWeight.Black,
                                style = MaterialTheme.typography.headlineSmall)
                            Text("NORAD 43700 · geostationary at 25.9°E", color = SatMuted)
                        }
                        AssistChip({}, { Text("GEOSTATIONARY") }, enabled = false)
                    }
                    Text("QO-100 has no AOS/LOS pass timer. Point once, then use the transponder workspace below.", color = SatInk)
                    Text(look?.let { "From ${stationGrid.uppercase(Locale.US)} · azimuth ${"%.1f°".format(Locale.US, it.azimuthDeg)} · elevation ${"%.1f°".format(Locale.US, it.elevationDeg)} · ${if (it.visible) "above horizon" else "below horizon"}" }
                        ?: "Set a valid station Maidenhead grid to calculate dish pointing.",
                        color = if (look?.visible == true) SatGood else SatBad, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SatPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("NARROWBAND TRANSPONDER", color = SatAmber, fontWeight = FontWeight.Black)
                    Text("Uplink 2400.005–2400.490 MHz · downlink 10489.505–10489.990 MHz · inverted", color = SatInk)
                    Qo100BandRow("CW", "2400.005–2400.040", "10489.505–10489.540")
                    Qo100BandRow("DIGITAL · 500 Hz", "2400.040–2400.080", "10489.540–10489.580")
                    Qo100BandRow("DIGITAL · 2700 Hz", "2400.080–2400.150", "10489.580–10489.650")
                    Qo100BandRow("SSB", "2400.150–2400.245", "10489.650–10489.745")
                    Qo100BandRow("SSB", "2400.255–2400.350", "10489.755–10489.850")
                    Qo100BandRow("MIXED / SPECIAL", "2400.365–2400.490", "10489.865–10489.990")
                    HorizontalDivider()
                    Text("Beacons · 10489.500 · 10489.750 · 10489.9935 · 10490.000 MHz", color = SatMuted)
                    Text("Receive reference only. Converter/SDR IF depends on the operator's LNB; ShackCQ does not infer or transmit an uplink frequency.", color = SatMuted)
                    TextButton({ inAppBrowser?.open("https://amsat-dl.org/en/p4-a-nb-transponder-bandplan-and-operating-guidelines/") }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OFFICIAL NARROWBAND PLAN")
                    }
                }
            }
        }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SatPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("WIDEBAND DATV", color = SatAmber, fontWeight = FontWeight.Black)
                    Text("Uplink 2401.0–2410.0 MHz RHCP · downlink 10490.5–10499.5 MHz horizontal", color = SatInk)
                    Text("Use the current AMSAT-DL wideband plan for symbol-rate channels and maintenance notices.", color = SatMuted)
                    TextButton({ inAppBrowser?.open("https://amsat-dl.org/wp-content/uploads/2021/02/QO-100-WB-Bandplan-V3.pdf") }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(5.dp)); Text("OFFICIAL WIDEBAND PLAN")
                    }
                }
            }
        }
        item {
            Surface(color = SatBad.copy(alpha = .12f), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("OPERATING SAFETY", color = SatBad, fontWeight = FontWeight.Black)
                    Text("No FM. Stay inside the published segments and guard bands. Keep uplink power below beacon level and reduce power immediately if LEILA marks the signal.", color = SatInk)
                    Text("This screen is receive-only guidance; it never keys PTT or starts TX.", color = SatGood, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Qo100BandRow(use: String, uplinkMhz: String, downlinkMhz: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(use, color = SatInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("↑ $uplinkMhz", color = SatMuted, modifier = Modifier.weight(1f))
        Text("↓ $downlinkMhz", color = SatGood, modifier = Modifier.weight(1f))
    }
}

@Composable private fun NextPasses(
    controller:SatelliteOperationsController,stationGrid:String,activationGrid:String?,manualGrid:String,changeGrid:(String)->Unit,
    hours:Int,changeHours:(Int)->Unit,minimum:String,changeMinimum:(String)->Unit,mode:String,changeMode:(String)->Unit,
    favouritesOnly:Boolean,changeFavourites:(Boolean)->Unit,utc:Boolean,changeUtc:(Boolean)->Unit,now:Long,requestGps:()->Unit,apply:()->Unit,
    openTrack:(SatellitePassRow)->Unit,openSky:(SatellitePassRow)->Unit,prepare:(SatellitePassRow)->Unit,normalLogger:(SatellitePassRow)->Unit,tune:(SatellitePassRow)->Unit,logbook:(SatellitePassRow)->Unit,
) {
    val context=LocalContext.current
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("OBSERVER & PASS FILTER",color=SatAmber,fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                controller.observerProfiles(stationGrid,activationGrid).forEach{profile->AssistChip({changeGrid(profile.grid)},{Text(profile.label)})}
                AssistChip(requestGps,{Text("CURRENT GPS")},leadingIcon={Icon(Icons.Outlined.MyLocation,null)})
            }
            OutlinedTextField(manualGrid,changeGrid,label={Text("Manual Maidenhead grid")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
                listOf(6,12,24,48).forEach{value->FilterChip(hours==value,{changeHours(value)},{Text("${value}h")})}
                OutlinedTextField(minimum,changeMinimum,label={Text("Min °")},singleLine=true,modifier=Modifier.width(100.dp))
                OutlinedTextField(mode,changeMode,label={Text("Mode/transponder")},singleLine=true,modifier=Modifier.width(180.dp))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){FilterChip(favouritesOnly,{changeFavourites(!favouritesOnly)},{Text("FAVOURITES")});FilterChip(!favouritesOnly,{changeFavourites(false)},{Text("ALL")});FilterChip(utc,{changeUtc(true)},{Text("UTC")});FilterChip(!utc,{changeUtc(false)},{Text("LOCAL")});Button(apply){Text("PREDICT")}}
        }}}
        if(controller.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(controller.passes.isEmpty()&&!controller.busy)item{ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("NO PASSES TO DISPLAY",color=SatAmber,fontWeight=FontWeight.Bold);Text(controller.message,color=SatMuted);if(favouritesOnly)Button({changeFavourites(false);controller.updatePreferences(manualGrid,hours,minimum.toDoubleOrNull()?:10.0,false,utc,mode);controller.predict()}){Text("SHOW ALL SATELLITES")};Text("Select FLIGHTPATH or SKY PLOT on any pass below. That same pass remains selected across Live Track, Sky Plot and Timers.",color=SatMuted)}}}
        items(controller.passes,key={"${it.satellite.noradId}-${it.pass.aos}"}){row->
            ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(row.satellite.name,color=SatInk,fontWeight=FontWeight.Bold);Text("NORAD ${row.satellite.noradId} · ${row.satellite.elements.source}",color=SatMuted)};Text(countdown(row.pass.aos,now),color=if(row.pass.aos<=now&&row.pass.los>=now)SatGood else SatAmber,fontWeight=FontWeight.Bold)}
                Text("AOS ${satelliteTime(row.pass.aos,utc)} · TCA ${satelliteTime(row.pass.tca,utc)} · LOS ${satelliteTime(row.pass.los,utc)}",color=SatInk)
                Text("Max ${"%.1f°".format(Locale.US,row.pass.maximumElevationDeg)} · ${(row.pass.durationSeconds/60)}m ${row.pass.durationSeconds%60}s · az ${"%.0f°".format(row.pass.aosAzimuthDeg)} → ${"%.0f°".format(row.pass.losAzimuthDeg)}",color=SatMuted)
                row.transponder?.let{Text("${it.mode} · ↓ ${it.downlinkLowHz?.let(::formatSatelliteFrequency)?:"—"} · ↑ ${it.uplinkLowHz?.let(::formatSatelliteFrequency)?:"—"} · ${if(it.manual)"LOCAL OVERRIDE" else "SatNOGS catalogue, not operational proof"}",color=SatMuted)}
                val ageDays=(now-row.satellite.elementEpoch)/86400.0;if(ageDays>7)Text("ELEMENT AGE ${"%.1f".format(Locale.US,ageDays)} DAYS · verify before use",color=SatBad)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    TextButton({openTrack(row)}){Text("FLIGHTPATH")};TextButton({openSky(row)}){Text("SKY PLOT")};TextButton({shareSatelliteIcs(context,row)}){Text("CALENDAR / SHARE")};TextButton({prepare(row)}){Text("FAST ENTRY")};TextButton({normalLogger(row)}){Text("NORMAL LOGGER")};TextButton({tune(row)},enabled=row.transponder?.downlinkLowHz!=null){Text("TUNE PREVIEW")};TextButton({logbook(row)}){Text("HISTORY")}
                }
            }}
        }
    }
}

@Composable private fun SatelliteProviderStrip(meta:SatelliteProviderMetadata){Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Surface(color=SatPanel,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){AssistChip({}, {Text(meta.state.name.replace('_',' '))},enabled=false);Text(meta.source,color=SatInk,modifier=Modifier.weight(1f));Text(meta.fetchedAt.takeIf{it>0}?.let{satelliteTime(it,true)}?:"No cache",color=SatMuted)}};if(meta.lastError.isNotBlank())Text("Last refresh ${meta.lastError}; ${if(meta.state==SatelliteCacheState.OFFLINE_CACHE)"last-good cache retained" else "no valid fallback"}",color=SatBad);if(meta.manualOverride)Text("MANUAL override active · provider validation is not claimed",color=SatAmber)}}

@Composable private fun SatellitePassPicker(controller:SatelliteOperationsController,purpose:String,openPasses:()->Unit){ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("SELECT PASS FOR $purpose",color=SatAmber,fontWeight=FontWeight.Bold);Text(controller.selectedPass?.let{"${it.satellite.name} · AOS ${satelliteTime(it.pass.aos,controller.utc)}"}?:"No pass selected",color=if(controller.selectedPass==null)SatBad else SatInk)};TextButton(openPasses){Text("OPEN PASS LIST")}};if(controller.passes.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){controller.passes.take(16).forEach{row->FilterChip(controller.selectedPass?.let{"${it.satellite.noradId}-${it.pass.aos}"}=="${row.satellite.noradId}-${row.pass.aos}",{controller.select(row)},{Text("${row.satellite.name} · ${satelliteTime(row.pass.aos,controller.utc)}")})}}else Text("No predicted passes are available. Open Pass List, choose ALL, then run PREDICT.",color=SatMuted)}}}

@Composable private fun LiveTrack(controller:SatelliteOperationsController,stationGrid:String,now:Long,openPasses:()->Unit){val row=controller.selectedPass;Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(7.dp)){SatellitePassPicker(controller,"LIVE TRACK",openPasses);if(row==null){Text("Select one of the pass chips above, or open the full pass list.",color=SatMuted);return@Column};if(controller.elements.metadata.state!=SatelliteCacheState.CURRENT)Text("${controller.elements.metadata.state.name.replace('_',' ')} · coordinates are a stale/offline prediction, not live provider truth",color=SatBad,fontWeight=FontWeight.Bold);controller.livePoint?.let{Text("${row.satellite.name} · Az ${"%.1f°".format(it.azimuthDeg)} · El ${"%.1f°".format(it.elevationDeg)} · Range ${"%.0f km".format(it.rangeKm)} · ${countdown(row.pass.los,now)} to LOS",color=SatInk)};SatelliteFlightpathMap(controller.groundTrack,controller.livePoint,maidenheadCenter(controller.observerGrid.ifBlank{stationGrid}),row.pass,Modifier.fillMaxWidth().weight(1f));Text("Map © OpenStreetMap contributors · propagation: pinned local SGP4",color=SatMuted,style=MaterialTheme.typography.bodySmall)}}

@Composable private fun SkyPlotPanel(controller:SatelliteOperationsController,minimum:Double,openPasses:()->Unit){val row=controller.selectedPass;Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(7.dp)){SatellitePassPicker(controller,"SKY PLOT",openPasses);if(row==null){Text("Select one of the pass chips above, or open the full pass list.",color=SatMuted);return@Column};Text("${row.satellite.name} · north-up observer sky · rings show 0°, 30° and 60° elevation",color=SatInk,fontWeight=FontWeight.Bold);SatelliteSkyPlot(controller.skyTrack,controller.livePoint,minimum,Modifier.fillMaxWidth().weight(1f));Text("Green AOS · amber peak · red LOS · white current position",color=SatMuted);Text("AOS ${satelliteTime(row.pass.aos,controller.utc)} · TCA ${satelliteTime(row.pass.tca,controller.utc)} · LOS ${satelliteTime(row.pass.los,controller.utc)}",color=SatMuted)}}

@Composable private fun SatelliteSkyPlot(samples:List<OrbitalPoint>,current:OrbitalPoint?,minimum:Double,modifier:Modifier){Canvas(modifier.background(SatPanel)){val center=Offset(size.width/2,size.height/2);val radius=minOf(size.width,size.height)*.43f;listOf(0,30,60).forEach{el->drawCircle(SatMuted.copy(alpha=.45f),radius*((90-el)/90f),center,style=Stroke(1.5f))};drawCircle(SatAmber.copy(alpha=.55f),radius*((90-minimum.coerceIn(0.0,90.0))/90.0).toFloat(),center,style=Stroke(2f));drawLine(SatMuted,Offset(center.x,center.y-radius),Offset(center.x,center.y+radius));drawLine(SatMuted,Offset(center.x-radius,center.y),Offset(center.x+radius,center.y));fun pos(p:OrbitalPoint):Offset{val r=radius*((90-p.elevationDeg.coerceIn(0.0,90.0))/90.0).toFloat();val angle=Math.toRadians(p.azimuthDeg-90);return Offset(center.x+(cos(angle)*r).toFloat(),center.y+(sin(angle)*r).toFloat())};samples.zipWithNext().forEach{(a,b)->if(a.elevationDeg>=0||b.elevationDeg>=0)drawLine(SatAmber,pos(a),pos(b),3f)};samples.firstOrNull()?.let{drawCircle(SatGood,7f,pos(it))};samples.maxByOrNull(OrbitalPoint::elevationDeg)?.let{drawCircle(SatAmber,8f,pos(it))};samples.lastOrNull()?.let{drawCircle(SatBad,7f,pos(it))};current?.takeIf{it.elevationDeg>=0}?.let{drawCircle(Color.White,9f,pos(it))}}}

@Composable private fun SatelliteStatusPanel(controller:SatelliteOperationsController,history:(AmsatStatusSummary)->Unit){var search by rememberSaveable{mutableStateOf("")};var favouritesOnly by rememberSaveable{mutableStateOf(false)};val favouriteNames=controller.elements.rows.filter{it.noradId in controller.favourites}.map{it.name.substringBefore(' ').uppercase(Locale.US)};val rows=controller.status.rows.filter{row->row.displayName.contains(search,true)&&(!favouritesOnly||favouriteNames.any{row.name.uppercase(Locale.US).contains(it)})};Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(7.dp)){SatelliteProviderStrip(controller.status.metadata);Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(search,{search=it},label={Text("Search status")},singleLine=true,modifier=Modifier.weight(1f));FilterChip(favouritesOnly,{favouritesOnly=!favouritesOnly},{Text("FAVOURITES")});Text("${rows.size} satellites",color=SatMuted)};BoxWithConstraints(Modifier.weight(1f)){val columns=if(maxWidth>=900.dp)2 else 1;LazyVerticalGrid(GridCells.Fixed(columns),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){gridItems(rows.take(250),key={it.name}){row->SatelliteStatusCard(controller,row,history)}}}}}

@Composable private fun SatelliteStatusCard(controller:SatelliteOperationsController,row:AmsatStatusSummary,history:(AmsatStatusSummary)->Unit){ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(row.displayName,color=SatInk,fontWeight=FontWeight.Bold);Text(listOf("Heard","Crew Active","Telemetry Only","Not Heard").joinToString(" · "){"$it ${row.counts[it]?:0}"},color=SatMuted);Text("24-hour community timeline · latest ${row.latestReportEpoch?.let{satelliteTime(it,true)}?:"none"} · advisory only",color=SatMuted);row.timeline.take(4).forEach{report->Text("${satelliteTime(report.reportedEpoch,true)} · ${report.status} · ${report.callsign.ifBlank{"anonymous"}}${report.grid.takeIf(String::isNotBlank)?.let{" · $it"}.orEmpty()}",color=SatMuted)};val next=controller.passes.firstOrNull{it.satellite.name.substringBefore(' ').let{n->row.name.contains(n,true)}};if(next!=null)Text("Next local prediction ${satelliteTime(next.pass.aos,controller.utc)} · ${"%.0f°".format(next.pass.maximumElevationDeg)}",color=SatAmber);TextButton({history(row)}){Text("SATELLITE LOGBOOK")}}}}

@Composable
private fun SatelliteTimersPanel(controller:SatelliteOperationsController,now:Long,track:(SatellitePassRow)->Unit) {
    val context=LocalContext.current
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        SatelliteProviderStrip(controller.timers.metadata)
        Text("LOCAL PASS TIMERS",color=SatAmber,fontWeight=FontWeight.Bold)
        Text("The external timer page has no stable machine-readable contract. These AOS/TCA/LOS countdowns are calculated locally by the pinned SGP4 engine and remain available.",color=SatMuted)
        if(controller.passes.isEmpty()) {
            ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("No local pass timers",color=SatInk,fontWeight=FontWeight.Bold)
                    Text("Open Next Passes, select ALL if needed, then run PREDICT.",color=SatMuted)
                }
            }
        } else BoxWithConstraints(Modifier.weight(1f)) {
            val columns=if(maxWidth>=900.dp)2 else 1
            LazyVerticalGrid(GridCells.Fixed(columns),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                gridItems(controller.passes.take(100),key={"${it.satellite.noradId}-${it.pass.aos}"}) { row ->
                    ElevatedCard(colors=CardDefaults.elevatedCardColors(containerColor=SatPanel)) {
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(row.satellite.name,color=SatInk,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                Text(if(now in row.pass.aos..row.pass.los)"IN PASS" else countdown(row.pass.aos,now),color=if(now in row.pass.aos..row.pass.los)SatGood else SatAmber,fontWeight=FontWeight.Bold)
                            }
                            Text("AOS ${satelliteTime(row.pass.aos,controller.utc)} · TCA ${satelliteTime(row.pass.tca,controller.utc)} · LOS ${satelliteTime(row.pass.los,controller.utc)}",color=SatMuted)
                            Text("Max ${"%.1f°".format(Locale.US,row.pass.maximumElevationDeg)} · ${if(now<row.pass.aos)"to AOS ${countdown(row.pass.aos,now)}" else "to LOS ${countdown(row.pass.los,now)}"}",color=SatMuted)
                            Row {
                                TextButton({track(row)}) { Text("LIVE TRACK") }
                                TextButton({shareSatelliteIcs(context,row)}) { Text("CALENDAR") }
                            }
                        }
                    }
                }
            }
        }
        if(controller.timers.rows.isNotEmpty()) {
            Text("PROVIDER WINDOWS",color=SatAmber,fontWeight=FontWeight.Bold)
            controller.timers.rows.take(5).forEach { row ->
                Text("${row.satellite.ifBlank{"Satellite"}} · ${row.label} · ${satelliteTime(row.startEpoch,true)} → ${satelliteTime(row.endEpoch,true)}",color=if(row.functional)SatGood else SatMuted)
            }
        }
    }
}

@Composable
private fun SatelliteCataloguePanel(
    controller: SatelliteOperationsController,
    track: (SatelliteCatalogueEntry) -> Unit,
) {
    val inAppBrowser = LocalInAppBrowserState.current
    var search by rememberSaveable { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var transponderFor by remember { mutableStateOf<SatelliteCatalogueEntry?>(null) }
    var remove by remember { mutableStateOf<SatelliteCatalogueEntry?>(null) }
    var removeTransponder by remember { mutableStateOf<SatelliteTransponder?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Name / alias / NORAD") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton({ controller.refresh(true) }, enabled = !controller.busy) { Text("REFRESH") }
            OutlinedButton({ manual = true }) { Text("ADD MANUAL TLE") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton({ inAppBrowser?.open(SatelliteProviderRepository.CELESTRAK_URL) }) { Text("CELESTRAK SOURCE") }
            TextButton({ inAppBrowser?.open(SatelliteProviderRepository.SATNOGS_URL) }) { Text("SATNOGS SOURCE") }
        }
        Text(SatelliteProviderRepository.SATNOGS_ATTRIBUTION, color = SatMuted, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(
                controller.elements.rows.filter { it.name.contains(search, true) || it.noradId.toString().contains(search) }.take(500),
                key = { it.noradId },
            ) { row ->
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SatPanel)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(row.name, color = SatInk, fontWeight = FontWeight.Bold)
                                val staleManual = row.manual && Instant.now().epochSecond - row.elementEpoch > 14L * 24 * 60 * 60
                                Text("NORAD ${row.noradId} · ${row.elements.source} · epoch ${satelliteTime(row.elementEpoch, true)}${if(staleManual) " · STALE · PREDICTION DISABLED" else ""}", color = if(staleManual) SatBad else SatMuted)
                            }
                            IconButton({ controller.toggleFavourite(row.noradId) }) {
                                Icon(if (row.noradId in controller.favourites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Favourite", tint = SatAmber)
                            }
                        }
                        controller.transpondersFor(row.noradId).take(6).forEach { transponder ->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(
                                "${if (transponder.manual) "LOCAL" else "SatNOGS"} · ${transponder.typeLabel()} · ${transponder.mode} · " +
                                    "↓ ${transponder.downlinkLowHz?.let(::formatSatelliteFrequency) ?: "—"} · " +
                                    "↑ ${transponder.uplinkLowHz?.let(::formatSatelliteFrequency) ?: "—"} · ${transponder.providerStatus.ifBlank { "status unknown" }}",
                                color = SatMuted,modifier=Modifier.weight(1f),
                            );if(transponder.manual)TextButton({removeTransponder=transponder}){Text("REMOVE LOCAL")}}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TextButton(
                                onClick = {
                                    controller.passes.firstOrNull { it.satellite.noradId == row.noradId }?.let {
                                        controller.select(it)
                                        track(row)
                                    }
                                },
                                enabled = controller.passes.any { it.satellite.noradId == row.noradId },
                            ) { Text("TRACK") }
                            TextButton({ transponderFor = row }) { Text("LOCAL TRANSPONDER") }
                            if (row.manual) TextButton({ remove = row }) { Text("REMOVE OVERRIDE") }
                        }
                    }
                }
            }
        }
    }
    if(manual)ManualElementsDialog({manual=false}){id,name,one,two->controller.saveManualElements(id,name,one,two).also{if(it)manual=false}}
    transponderFor?.let{row->ManualTransponderDialog(row,{transponderFor=null}){controller.saveManualTransponder(it);transponderFor=null}}
    remove?.let{row->AlertDialog(onDismissRequest={remove=null},title={Text("Remove manual elements?")},text={Text("${row.name} will fall back to a validated provider row if one is cached.")},confirmButton={Button({controller.removeManualElements(row.noradId);remove=null}){Text("REMOVE")}},dismissButton={TextButton({remove=null}){Text("CANCEL")}})}
    removeTransponder?.let{row->AlertDialog(onDismissRequest={removeTransponder=null},title={Text("Remove local transponder?")},text={Text("The local override will be removed; provider rows remain unchanged.")},confirmButton={Button({controller.removeManualTransponder(row.id);removeTransponder=null}){Text("REMOVE")}},dismissButton={TextButton({removeTransponder=null}){Text("CANCEL")}})}
}

@Composable private fun ManualElementsDialog(dismiss:()->Unit,save:(Long,String,String,String)->Boolean){var id by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var one by remember{mutableStateOf("")};var two by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};AlertDialog(onDismissRequest=dismiss,title={Text("Manual TLE override")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(id,{id=it.filter(Char::isDigit)},label={Text("NORAD ID")});OutlinedTextField(name,{name=it},label={Text("Satellite name")});OutlinedTextField(one,{one=it},label={Text("TLE line 1")});OutlinedTextField(two,{two=it},label={Text("TLE line 2")});Text("Saved as MANUAL; provider validation is not claimed.",color=SatAmber);if(error.isNotBlank())Text(error,color=SatBad)}},confirmButton={Button({if(!save(id.toLongOrNull()?:0,name,one,two))error="Invalid NORAD ID or TLE lines"}){Text("SAVE MANUAL")}},dismissButton={TextButton(dismiss){Text("CANCEL")}})}

@Composable private fun ManualTransponderDialog(satellite:SatelliteCatalogueEntry,dismiss:()->Unit,save:(SatelliteTransponder)->Unit){var down by remember{mutableStateOf("")};var up by remember{mutableStateOf("")};var mode by remember{mutableStateOf("")};AlertDialog(onDismissRequest=dismiss,title={Text("Local transponder · ${satellite.name}")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(down,{down=it.filter(Char::isDigit)},label={Text("Downlink Hz")});OutlinedTextField(up,{up=it.filter(Char::isDigit)},label={Text("Uplink Hz")});OutlinedTextField(mode,{mode=it.uppercase()},label={Text("Mode")});Text("Local override is visibly distinguished and does not claim active service.",color=SatAmber)}},confirmButton={Button({save(SatelliteTransponder("manual-${satellite.noradId}-${down}",satellite.noradId,"Local operator override",up.toLongOrNull(),null,down.toLongOrNull(),null,mode,mode,false,true,"local override",Instant.now().toString(),true))},enabled=down.toLongOrNull()!=null||up.toLongOrNull()!=null){Text("SAVE LOCAL")}},dismissButton={TextButton(dismiss){Text("CANCEL")}})}

@Composable
private fun SatelliteFlightpathMap(
    track: List<OrbitalPoint>,
    live: OrbitalPoint?,
    observer: GeoPoint?,
    pass: OrbitalPass,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    val callbackLifecycle = remember(mapView) { LifecycleGeneration() }
    var map by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var styled by remember { mutableStateOf(false) }
    var fittedPassKey by remember { mutableStateOf("") }
    DisposableEffect(mapView, lifecycle) {
        val callbackGeneration = callbackLifecycle.next(); var disposed = false
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(watcher)
        mapView.getMapAsync { ready ->
            if (disposed || !callbackLifecycle.isCurrent(callbackGeneration)) return@getMapAsync
            map = ready
            ready.uiSettings.isAttributionEnabled = true
            ready.setMinZoomPreference(2.0)
            ready.setStyle(Style.Builder().fromJson(satelliteMapStyle())) {
                if (callbackLifecycle.isCurrent(callbackGeneration)) styled = true
            }
        }
        onDispose {
            disposed = true
            callbackLifecycle.retire()
            lifecycle.removeObserver(watcher)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy(); map = null
        }
    }
    AndroidView({ mapView }, modifier)
    LaunchedEffect(track, live, observer, styled, pass.aos, pass.los) {
        val ready = map ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        ready.clear()
        datelineSegments(track).forEach { segment ->
            if (segment.size > 1) ready.addPolyline(PolylineOptions()
                .addAll(segment.map { LatLng(it.latitudeDeg, it.longitudeDeg) })
                .color(android.graphics.Color.rgb(233, 167, 43)).width(3f))
        }
        observer?.let { ready.addMarker(MarkerOptions().position(LatLng(it.latitude, it.longitude)).title("Observer")) }
        live?.let { point ->
            ready.addMarker(MarkerOptions().position(LatLng(point.latitudeDeg, point.longitudeDeg)).title("Satellite"))
            ready.addPolyline(PolylineOptions().addAll(footprint(point))
                .color(android.graphics.Color.argb(150, 66, 199, 123)).width(2f))
        }
        track.minByOrNull { kotlin.math.abs(it.epoch - pass.aos) }?.let {
            ready.addMarker(MarkerOptions().position(LatLng(it.latitudeDeg, it.longitudeDeg)).title("AOS"))
        }
        track.minByOrNull { kotlin.math.abs(it.epoch - pass.los) }?.let {
            ready.addMarker(MarkerOptions().position(LatLng(it.latitudeDeg, it.longitudeDeg)).title("LOS"))
        }
        val passKey = "${pass.aos}-${pass.los}"
        if (fittedPassKey != passKey) {
            fittedPassKey = passKey
            val anchor = live?.longitudeDeg ?: observer?.longitude ?: track.firstOrNull()?.longitudeDeg ?: 0.0
            val points = track.map { LatLng(it.latitudeDeg, unwrapSatelliteLongitude(it.longitudeDeg, anchor)) } +
                listOfNotNull(
                    observer?.let { LatLng(it.latitude, unwrapSatelliteLongitude(it.longitude, anchor)) },
                    live?.let { LatLng(it.latitudeDeg, unwrapSatelliteLongitude(it.longitudeDeg, anchor)) },
                )
            if (points.isNotEmpty()) runCatching {
                ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 60))
            }.onFailure {
                val centre = live?.let { LatLng(it.latitudeDeg, it.longitudeDeg) }
                    ?: observer?.let { LatLng(it.latitude, it.longitude) }
                centre?.let { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 3.5)) }
            }
        }
    }
}

private fun datelineSegments(points:List<OrbitalPoint>):List<List<OrbitalPoint>>{if(points.isEmpty())return emptyList();val rows=mutableListOf<MutableList<OrbitalPoint>>(mutableListOf(points.first()));points.drop(1).forEach{point->if(kotlin.math.abs(point.longitudeDeg-rows.last().last().longitudeDeg)>180)rows.add(mutableListOf());rows.last().add(point)};return rows.filter{it.isNotEmpty()}}
internal fun unwrapSatelliteLongitude(longitude: Double, anchor: Double): Double {
    var result = longitude
    while (result - anchor > 180.0) result -= 360.0
    while (anchor - result > 180.0) result += 360.0
    return result
}
private fun footprint(point:OrbitalPoint):List<LatLng>{val earth=6371.0;val angle=Math.toDegrees(acos((earth/(earth+point.altitudeKm.coerceAtLeast(0.0))).coerceIn(-1.0,1.0)));return(0..72).map{i->val bearing=Math.toRadians(i*5.0);val lat1=Math.toRadians(point.latitudeDeg);val lon1=Math.toRadians(point.longitudeDeg);val distance=Math.toRadians(angle);val lat2=kotlin.math.asin(sin(lat1)*cos(distance)+cos(lat1)*sin(distance)*cos(bearing));val lon2=lon1+kotlin.math.atan2(sin(bearing)*sin(distance)*cos(lat1),cos(distance)-sin(lat1)*sin(lat2));LatLng(Math.toDegrees(lat2),((Math.toDegrees(lon2)+540)%360)-180)}}
private fun satelliteMapStyle()="""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}"""
private fun SatelliteTransponder.typeLabel()=if(manual)"LOCAL OVERRIDE" else if(alive&&providerStatus.equals("active",true))"catalogue active flag" else "catalogue ${providerStatus.ifBlank{"unknown"}}"
private fun satelliteTime(epoch:Long,utc:Boolean):String=DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(if(utc)ZoneOffset.UTC else ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))+(if(utc)"Z" else "")
private fun countdown(target:Long,now:Long):String{val seconds=target-now;val abs=kotlin.math.abs(seconds);val value="${abs/3600}h ${(abs%3600)/60}m ${abs%60}s";return if(seconds>=0)"T− $value" else "T+ $value"}
private fun formatSatelliteFrequency(hz:Long)="%.6f MHz".format(Locale.US,hz/1_000_000.0)
private fun shareSatelliteIcs(context:Context,row:SatellitePassRow){val stamp: (Long)->String={DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(it))};val text="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ShackCQ//Satellite Operations//EN\r\nBEGIN:VEVENT\r\nUID:${row.satellite.noradId}-${row.pass.aos}@shackcq\r\nDTSTAMP:${stamp(Instant.now().epochSecond)}\r\nDTSTART:${stamp(row.pass.aos)}\r\nDTEND:${stamp(row.pass.los)}\r\nSUMMARY:${row.satellite.name} satellite pass\r\nDESCRIPTION:Max elevation ${"%.1f".format(Locale.US,row.pass.maximumElevationDeg)} deg; local SGP4 prediction\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/calendar";putExtra(Intent.EXTRA_TEXT,text);putExtra(Intent.EXTRA_SUBJECT,"${row.satellite.name} pass")},"Add/share satellite pass"))}
private fun satelliteLastKnownPoint(context:Context):GeoPoint? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(LocationManager::class.java)
    return manager.getProviders(true).mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) }
}
