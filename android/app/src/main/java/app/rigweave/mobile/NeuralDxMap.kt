package app.rigweave.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Annotation
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val MapInk = ComposeColor(0xFFF4F0E7)
private val MapMuted = ComposeColor(0xFFB4BDC2)
private val MapCyan = ComposeColor(0xFF43C7D9)
private val MapGreen = ComposeColor(0xFF42C77B)
private val MapAmber = ComposeColor(0xFFE9A72B)
private val MapYellow = ComposeColor(0xFFF4C94E)
private val MapRed = ComposeColor(0xFFE4544D)
private val MapPurple = ComposeColor(0xFFB783F5)
// Legacy MapLibre annotations are Java objects backed by native map state. Hundreds of
// multi-point paths push 256 MB Android tablets over their heap limit during a refresh.
// Eighty keeps the map useful while the keyed diff below preserves the newest paths.
private const val MaxVisibleDxPaths = 80

internal enum class NeuralBasemap(val label: String, val styleJson: String) {
    SATELLITE("ESRI SATELLITE", satelliteStyleJson()),
    DARK("OPENFREEMAP LIBERTY", ""),
}

internal data class MapMarker(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val detail: String = "",
    val color: Int,
    val sizeDp: Int = 12,
    val ring: Boolean = false,
)

internal data class MapPath(val points: List<LatLng>, val color: Int, val widthDp: Float = 1.5f)
internal data class MapArea(val points: List<LatLng>, val fill: Int, val stroke: Int)
private class RenderedMapAnnotations {
    var markers: Map<MapMarker, Annotation> = emptyMap()
    var paths: Map<MapPath, Annotation> = emptyMap()
    var areas: Map<MapArea, Annotation> = emptyMap()
    fun reset() { markers = emptyMap(); paths = emptyMap(); areas = emptyMap() }
}

@Composable
internal fun NativeNeuralMap(
    markers: List<MapMarker>,
    paths: List<MapPath>,
    areas: List<MapArea>,
    center: LatLng,
    zoom: Double,
    basemap: NeuralBasemap,
    label: String,
    description: String,
    modifier: Modifier,
    legend: String = "",
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = context.resources.displayMetrics.density
    val mapView = remember(basemap) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    val callbackLifecycle = remember(mapView) { LifecycleGeneration() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val renderedAnnotations = remember(mapView) { RenderedMapAnnotations() }

    DisposableEffect(mapView, lifecycle) {
        val callbackGeneration = callbackLifecycle.next()
        var started = false
        var resumed = false
        var destroyed = false
        fun start() {
            if (!started && !destroyed) {
                mapView.onStart()
                started = true
            }
        }
        fun resume() {
            start()
            if (!resumed && !destroyed) {
                mapView.onResume()
                resumed = true
            }
        }
        fun pause() {
            if (resumed && !destroyed) {
                mapView.onPause()
                resumed = false
            }
        }
        fun stop() {
            pause()
            if (started && !destroyed) {
                mapView.onStop()
                started = false
            }
        }
        fun destroy() {
            if (!destroyed) {
                stop()
                mapView.onDestroy()
                destroyed = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        var disposed = false
        mapView.getMapAsync { readyMap ->
            if (disposed || !callbackLifecycle.isCurrent(callbackGeneration)) return@getMapAsync
            map = readyMap
            readyMap.uiSettings.apply {
                isAttributionEnabled = true
                isLogoEnabled = true
                isCompassEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            readyMap.setMinZoomPreference(0.8)
            readyMap.setMaxZoomPreference(12.0)
            readyMap.cameraPosition = CameraPosition.Builder().target(center).zoom(zoom).build()
            val style = if (basemap == NeuralBasemap.DARK)
                Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
            else Style.Builder().fromJson(basemap.styleJson)
            readyMap.setStyle(style) {
                if (!callbackLifecycle.isCurrent(callbackGeneration)) return@setStyle
                renderedAnnotations.reset()
                styleReady = true
            }
        }
        onDispose {
            disposed = true
            callbackLifecycle.retire()
            lifecycle.removeObserver(observer)
            map?.let { readyMap ->
                val annotations = renderedAnnotations.areas.values +
                    renderedAnnotations.paths.values + renderedAnnotations.markers.values
                if (annotations.isNotEmpty()) readyMap.removeAnnotations(annotations)
            }
            renderedAnnotations.reset()
            map = null
            styleReady = false
            destroy()
        }
    }

    LaunchedEffect(map, styleReady, markers, paths, areas) {
        val readyMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val oldAreas = renderedAnnotations.areas
        val nextAreas = areas.associateWith { area -> oldAreas[area] ?: readyMap.addPolygon(
            PolygonOptions().addAll(area.points).fillColor(area.fill).strokeColor(area.stroke)
        ) }
        val oldPaths = renderedAnnotations.paths
        val nextPaths = paths.associateWith { path -> oldPaths[path] ?: readyMap.addPolyline(
            PolylineOptions().addAll(path.points).color(path.color).width(path.widthDp * density)
        ) }
        val oldMarkers = renderedAnnotations.markers
        val nextMarkers = markers.associateWith { marker -> oldMarkers[marker] ?: readyMap.addMarker(
            MarkerOptions().position(LatLng(marker.latitude, marker.longitude)).title(marker.title)
                .snippet(marker.detail).icon(mapMarkerIcon(context, marker.color, marker.sizeDp, marker.ring))
        ) }
        val stale = buildList {
            oldAreas.filterKeys { it !in nextAreas }.values.let(::addAll)
            oldPaths.filterKeys { it !in nextPaths }.values.let(::addAll)
            oldMarkers.filterKeys { it !in nextMarkers }.values.let(::addAll)
        }
        renderedAnnotations.areas = nextAreas
        renderedAnnotations.paths = nextPaths
        renderedAnnotations.markers = nextMarkers
        if (stale.isNotEmpty()) readyMap.removeAnnotations(stale)
    }

    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(ComposeColor(0xFF06151C))
            .border(1.dp, ComposeColor(0xFF3D474E), RoundedCornerShape(10.dp))
            .semantics { contentDescription = description }
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Surface(
            color = ComposeColor(0xE6192228),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        ) {
            Text("$label · ${basemap.label}", color = MapInk, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        }
        Surface(
            color = ComposeColor(0xD9111519),
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 48.dp),
        ) {
            Text(
                if (basemap == NeuralBasemap.SATELLITE) "Imagery and reference labels © Esri"
                else "OpenFreeMap © OpenMapTiles · OpenStreetMap",
                color = MapMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Surface(
            color = ComposeColor(0xE6192228),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
        ) {
            IconButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(center, zoom), 550) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.MyLocation, contentDescription = "Reset map view", tint = MapCyan)
            }
        }
        if (legend.isNotBlank()) {
            Surface(
                color = ComposeColor(0xE6192228),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            ) {
                Text(legend, color = MapInk, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
internal fun DxWorldCanvas(rows: List<AndroidDXSpot>, stationGrid: String, hearsMe: Boolean,
    cty: CtyController, showPaths: Boolean, modifier: Modifier, showGreyline: Boolean = false,
    signalReports: List<SignalReport> = emptyList(), portableSpots: List<PortableSpot> = emptyList(),
    satellites: List<SatellitePosition> = emptyList(), loggedQsos: List<Qso> = emptyList(),
    lightning: List<LightningStrike> = emptyList()) {
    var currentTime by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(showGreyline) {
        currentTime = Instant.now()
        while (showGreyline) {
            delay(60_000)
            currentTime = Instant.now()
        }
    }
    val qth = remember(stationGrid) { maidenheadCenter(stationGrid) }
    val resolvedPaths = remember(rows, showPaths, cty.dataRevision) {
        if (!showPaths) emptyList() else rows.asSequence().mapNotNull { spot ->
            val reporter = cty.lookup(spot.spotter) ?: return@mapNotNull null
            val segments = dxReportRoute(reporter, spot)
            if (segments.isEmpty()) null else Triple(spot, reporter, segments)
        }.take(MaxVisibleDxPaths).toList()
    }
    val markers = remember(qth, rows, resolvedPaths, hearsMe, signalReports, portableSpots,
        satellites, loggedQsos, lightning, cty.dataRevision) { buildList {
        qth?.let { add(qthMarker(it)) }
        rows.forEach { spot ->
            if (spot.latitude != 0.0 || spot.longitude != 0.0) {
                add(MapMarker(
                    spot.latitude, spot.longitude, spot.callsign,
                    "${spot.band} ${spot.mode} · ${"%.3f".format(spot.frequencyHz / 1_000_000.0)} MHz · ${spot.country} · DX de ${spot.spotter}",
                    when { spot.watchlisted -> colorInt(MapYellow); hearsMe -> colorInt(MapGreen); else -> colorInt(dxBandColor(spot.band)) },
                    if (spot.watchlisted) 15 else 11,
                    spot.watchlisted,
                ))
            }
        }
        resolvedPaths.distinctBy { it.first.spotter.uppercase() }.forEach { (spot, reporter) ->
            add(MapMarker(reporter.latitude, reporter.longitude, "DX DE ${spot.spotter}",
                "Reported ${spot.callsign} · ${spot.band} ${spot.mode} · approximate CTY location",
                colorInt(MapAmber), 9))
        }
        signalReports.take(60).forEach { report ->
            val latitude = report.latitude ?: return@forEach
            val longitude = report.longitude ?: return@forEach
            add(MapMarker(latitude, longitude, report.callsign,
                "Heard ${report.band} ${report.mode} · ${report.snr?.let { "$it dB" } ?: "SNR —"} · PSK Reporter",
                colorInt(MapPurple), 9))
        }
        portableSpots.take(160).forEach { spot ->
            val latitude = spot.latitude ?: return@forEach
            val longitude = spot.longitude ?: return@forEach
            val color = when {
                PortableProgram.POTA in spot.programs -> MapGreen
                PortableProgram.SOTA in spot.programs -> MapCyan
                else -> ComposeColor(0xFFC481D8)
            }
            add(MapMarker(latitude, longitude, spot.callsign,
                "${spot.programs.joinToString { it.label }} ${spot.primary.code} · ${spot.band} ${spot.mode}",
                colorInt(color), 10))
        }
        satellites.take(40).forEach { satellite ->
            add(MapMarker(satellite.latitude, satellite.longitude, satellite.name,
                "Az ${satellite.azimuth.roundToInt()}° · El ${satellite.elevation.roundToInt()}° · ${satellite.altitudeKm.roundToInt()} km",
                colorInt(if (satellite.visible) MapGreen else MapCyan), if (satellite.visible) 13 else 9, satellite.visible))
        }
        loggedQsos.take(120).forEach { qso ->
            val point = qsoMapPoint(qso, cty) ?: return@forEach
            add(MapMarker(point.latitude, point.longitude, qso.callsign,
                "Logged ${qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }} ${qso.mode} · ${qso.country.ifBlank { "entity unresolved" }}",
                colorInt(MapInk), 8))
        }
        lightning.take(120).forEach { strike ->
            add(MapMarker(strike.latitude, strike.longitude, "Lightning",
                "${strike.distanceKm.roundToInt()} km ${strike.bearing} · ${((Instant.now().epochSecond - strike.epoch) / 60).coerceAtLeast(0)}m ago",
                colorInt(MapRed), 8, strike.epoch >= Instant.now().epochSecond - 300))
        }
    } }
    val dxPaths = remember(resolvedPaths) { resolvedPaths.flatMap { (spot, _, segments) ->
        segments.map { points ->
            MapPath(points, colorInt((if (spot.watchlisted) MapYellow else dxBandColor(spot.band)).copy(alpha = if (spot.watchlisted) .78f else .54f)),
                if (spot.watchlisted) 1.65f else 1.1f)
        }
    } }
    val signalPaths = remember(qth, signalReports) { qth?.let { station -> signalReports.take(60).flatMap { report ->
        val latitude = report.latitude ?: return@flatMap emptyList()
        val longitude = report.longitude ?: return@flatMap emptyList()
        splitAtDateline(greatCirclePath(LatLng(station.latitude, station.longitude), LatLng(latitude, longitude)))
            .map { MapPath(it, colorInt(MapPurple.copy(alpha = .48f)), 1.15f) }
    } }.orEmpty() }
    val loggedPaths = remember(qth, loggedQsos, cty.dataRevision) { qth?.let { station -> loggedQsos.take(120).flatMap { qso ->
        val point = qsoMapPoint(qso, cty) ?: return@flatMap emptyList()
        splitAtDateline(greatCirclePath(LatLng(station.latitude, station.longitude), LatLng(point.latitude, point.longitude)))
            .map { MapPath(it, colorInt(MapInk.copy(alpha = .32f)), .9f) }
    } }.orEmpty() }
    val greylinePaths = remember(showGreyline, currentTime) { if (showGreyline) listOf(greylinePath(currentTime)) else emptyList() }
    val greylineAreas = remember(showGreyline, currentTime) { if (showGreyline) listOf(greylineArea(currentTime)) else emptyList() }
    val sun = remember(showGreyline, currentTime) { if (showGreyline) {
        val (latitude, longitude) = sunPosition(currentTime)
        listOf(MapMarker(latitude, longitude, "Sun", "Current subsolar point", colorInt(MapYellow), 15, true))
    } else emptyList() }
    val pathLabel = if (showPaths) " · ${resolvedPaths.size} PATHS" else ""
    val cappedLabel = if (resolvedPaths.size == MaxVisibleDxPaths && rows.size > MaxVisibleDxPaths) " · MAX $MaxVisibleDxPaths SHOWN" else ""
    val legend = buildList {
        if (showPaths) add("DX DE → DX · BAND COLOURS · APPROX. CTY CENTRES$cappedLabel")
        if (signalReports.isNotEmpty()) add("PURPLE PSK REPORTER")
        if (portableSpots.isNotEmpty()) add("GREEN POTA · CYAN SOTA · PURPLE WWFF")
        if (loggedQsos.isNotEmpty()) add("WHITE LOGGED QSO")
        if (lightning.isNotEmpty()) add("RED LIGHTNING")
    }.joinToString(" · ")
    NativeNeuralMap(markers + sun, greylinePaths + dxPaths + signalPaths + loggedPaths, greylineAreas, LatLng(20.0, 0.0), 1.35, NeuralBasemap.SATELLITE,
        "LIVE DX$pathLabel", "Interactive world map with ${rows.size} DX observations, ${resolvedPaths.size} reporting paths, ${signalReports.size} PSK Reporter receivers, ${portableSpots.size} portable activations, ${satellites.size} satellites, ${loggedQsos.size} logged contacts, and ${lightning.size} lightning strikes",
        modifier, legend)
}

private fun qsoMapPoint(qso: Qso, cty: CtyController): GeoPoint? = maidenheadCenter(qso.grid)
    ?: cty.lookup(qso.callsign)?.let { entity ->
        if (entity.latitude == 0.0 && entity.longitude == 0.0) null else GeoPoint(entity.latitude, entity.longitude)
    }

private fun dxBandColor(band: String): ComposeColor = when (band.trim().lowercase()) {
    "160m" -> ComposeColor(0xFFFF6B6B)
    "80m" -> ComposeColor(0xFFFF9F68)
    "60m", "40m" -> MapYellow
    "30m" -> ComposeColor(0xFF8FDF62)
    "20m" -> MapGreen
    "17m" -> ComposeColor(0xFF4ED9B2)
    "15m" -> MapCyan
    "12m" -> ComposeColor(0xFF69A7F5)
    "10m" -> ComposeColor(0xFF9D72F2)
    "6m" -> ComposeColor(0xFFE86AD7)
    else -> MapCyan
}

internal fun dxReportRoute(reporter: AndroidCtyRecord, spot: AndroidDXSpot): List<List<LatLng>> {
    val from = LatLng(reporter.latitude, reporter.longitude)
    val to = LatLng(spot.latitude, spot.longitude)
    if (!validMapPoint(from) || !validMapPoint(to)) return emptyList()
    if (from.latitude == to.latitude && from.longitude == to.longitude) return emptyList()
    return splitAtDateline(greatCirclePath(from, to))
}

private fun validMapPoint(point: LatLng): Boolean =
    point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
        (point.latitude != 0.0 || point.longitude != 0.0)

internal fun greatCirclePath(from: LatLng, to: LatLng, steps: Int = 32): List<LatLng> {
    val count = steps.coerceAtLeast(2)
    val fromLat = Math.toRadians(from.latitude)
    val fromLon = Math.toRadians(from.longitude)
    val toLat = Math.toRadians(to.latitude)
    val toLon = Math.toRadians(to.longitude)
    val fromVector = doubleArrayOf(cos(fromLat) * cos(fromLon), cos(fromLat) * sin(fromLon), sin(fromLat))
    val toVector = doubleArrayOf(cos(toLat) * cos(toLon), cos(toLat) * sin(toLon), sin(toLat))
    val omega = acos((fromVector.indices.sumOf { fromVector[it] * toVector[it] }).coerceIn(-1.0, 1.0))
    val sinOmega = sin(omega)
    if (omega < 1e-8 || kotlin.math.abs(sinOmega) < 1e-8) return listOf(from, to)
    return (0..count).map { step ->
        val fraction = step.toDouble() / count
        val fromScale = sin((1.0 - fraction) * omega) / sinOmega
        val toScale = sin(fraction * omega) / sinOmega
        val x = fromScale * fromVector[0] + toScale * toVector[0]
        val y = fromScale * fromVector[1] + toScale * toVector[1]
        val z = fromScale * fromVector[2] + toScale * toVector[2]
        LatLng(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), Math.toDegrees(atan2(y, x)))
    }
}

@Composable
internal fun DxReceiverMap(rows: List<SignalReport>, stationGrid: String, modifier: Modifier) {
    val qth = maidenheadCenter(stationGrid)
    val markers = buildList {
        qth?.let { add(qthMarker(it)) }
        rows.forEach { row ->
            val lat = row.latitude ?: return@forEach
            val lon = row.longitude ?: return@forEach
            add(MapMarker(lat, lon, row.callsign, "${row.band} ${row.mode} · ${row.snr?.let { "$it dB" } ?: "SNR —"} · ${row.distanceKm?.let { "$it km" } ?: row.locator}", colorInt(MapGreen), 12))
        }
    }
    val paths = if (qth == null) emptyList() else rows.mapNotNull { row ->
        val lat = row.latitude ?: return@mapNotNull null
        val lon = row.longitude ?: return@mapNotNull null
        MapPath(listOf(LatLng(qth.latitude, qth.longitude), LatLng(lat, lon)), colorInt(MapGreen.copy(alpha = .52f)), 1.35f)
    }
    NativeNeuralMap(markers, paths, emptyList(), LatLng(20.0, 0.0), 1.35, NeuralBasemap.SATELLITE,
        "WHO HEARS ME", "Interactive map with ${rows.size} PSK Reporter receivers", modifier)
}

@Composable
internal fun DxWorldAnomalyCanvas(rows: List<NeuralWorldCell>, greyline: Boolean, modifier: Modifier) {
    var currentTime by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(greyline) {
        currentTime = Instant.now()
        while (greyline) {
            delay(60_000)
            currentTime = Instant.now()
        }
    }
    val cellMarkers = buildList {
        rows.forEach { cell ->
            val ratio = cell.anomalyRatio ?: 1.0
            val hue = when { ratio >= 2.5 -> MapRed; ratio >= 1.8 -> MapYellow; else -> MapCyan }
            val expected = cell.expected?.let { "%.1f".format(it) } ?: "—"
            val calls = cell.calls.take(4).joinToString().ifBlank { "No representative calls" }
            add(MapMarker(
                cell.latitude,
                cell.longitude,
                "Propagation ${"%.1f".format(ratio)}×",
                "Observed ${cell.observed} · Expected $expected · ${cell.confidence}\n$calls",
                colorInt(hue),
                (11 + cell.observed.coerceIn(0, 8)).coerceAtMost(17),
                ring = true,
            ))
        }
    }
    val paths = if (greyline) listOf(greylinePath(currentTime)) else emptyList()
    val areas = if (greyline) listOf(greylineArea(currentTime)) else emptyList()
    val sun = if (greyline) {
        val pos = sunPosition(currentTime)
        listOf(MapMarker(pos.first, pos.second, "Sun", "Current subsolar point", colorInt(MapYellow), 15, true))
    } else emptyList()
    NativeNeuralMap(cellMarkers + sun, paths, areas, LatLng(20.0, 0.0), 1.35, NeuralBasemap.SATELLITE,
        "PROPAGATION SIGNALS", "Interactive world propagation signals and grey-line map", modifier)
}

@Composable
internal fun DxWorldOutlookCanvas(rows: List<OutlookWorldCell>, greyline: Boolean, modifier: Modifier) {
    val markers = rows.take(72).map { cell ->
        val forecast = cell.forecast
        val color = when (forecast.label) {
            OutlookLabel.STRONG -> MapGreen; OutlookLabel.FAVOURABLE -> MapCyan; OutlookLabel.BUILDING -> MapYellow
            OutlookLabel.DEGRADED -> MapRed; OutlookLabel.QUIET -> MapMuted; OutlookLabel.INSUFFICIENT_EVIDENCE -> MapMuted
        }
        MapMarker(cell.latitude, cell.longitude, "${forecast.band} · ${forecast.label.name.replace('_', ' ')}",
            "${forecast.confidence} · support ${forecast.supportScore} · ${forecast.baselineSamples} matched buckets\n${forecast.reasons.joinToString(" · ")}",
            colorInt(color), if (forecast.confidence == OutlookConfidence.HIGH) 16 else 12, ring = true)
    }
    val areas = if (greyline) listOf(greylineArea(Instant.now())) else emptyList()
    NativeNeuralMap(markers, emptyList(), areas, LatLng(20.0, 0.0), 1.35, NeuralBasemap.SATELLITE,
        "EMPIRICAL OUTLOOK", "Bounded 6 × 12 future-window outlook · no CAT action", modifier)
}

@Composable
internal fun DxSatelliteMap(rows: List<SatellitePosition>, stationGrid: String, selectedNorad: Int?, modifier: Modifier) {
    val qth = maidenheadCenter(stationGrid)
    val footprintSatellite = selectedNorad?.let { norad -> rows.firstOrNull { it.norad == norad } }
        ?: rows.firstOrNull(SatellitePosition::visible)
    val markers = buildList {
        qth?.let { add(qthMarker(it)) }
        rows.forEach { satellite ->
            add(MapMarker(
                satellite.latitude, satellite.longitude, satellite.name,
                "Az ${satellite.azimuth.roundToInt()}° · El ${satellite.elevation.roundToInt()}° · ${satellite.altitudeKm.roundToInt()} km",
                colorInt(if (satellite.visible) MapGreen else MapCyan), if (satellite.visible) 16 else 13,
                satellite.norad == footprintSatellite?.norad,
            ))
        }
    }
    val footprintCircle = footprintSatellite?.let { satellite ->
        geodesicCircle(satellite.latitude, satellite.longitude, satellite.footprintKm)
    }.orEmpty()
    val footprintPaths = footprintSatellite?.let {
        splitAtDateline(footprintCircle)
            .map { MapPath(it, colorInt(MapGreen.copy(alpha = .75f)), 1.7f) }
    }.orEmpty()
    val footprintAreas = splitPolygonAtDateline(footprintCircle).map { polygon ->
        MapArea(polygon, Color.rgb(12, 58, 39), colorInt(MapGreen.copy(alpha = .75f)))
    }
    val footprintName = footprintSatellite?.name?.let { " · $it FOOTPRINT" }.orEmpty()
    NativeNeuralMap(markers, footprintPaths, footprintAreas, LatLng(20.0, 0.0), 1.35, NeuralBasemap.SATELLITE,
        "LIVE ORBITS$footprintName", "Interactive satellite map with ${rows.size} tracked spacecraft and selected radio footprint", modifier)
}

@Composable
internal fun DxLightningMap(rows: List<LightningStrike>, stationGrid: String, modifier: Modifier) {
    val qth = maidenheadCenter(stationGrid)
    val now = Instant.now().epochSecond
    val markers = buildList {
        qth?.let { add(qthMarker(it)) }
        rows.take(200).forEach { strike ->
            val fresh = now - strike.epoch < 300
            add(MapMarker(strike.latitude, strike.longitude, "Lightning · ${strike.distanceKm} km ${strike.bearing}",
                if (fresh) "Fresh strike · under 5 minutes" else "${((now - strike.epoch) / 60).coerceAtLeast(0)} minutes ago",
                colorInt(if (fresh) MapRed else MapYellow), if (fresh) 14 else 10, fresh))
        }
    }
    val radius = qth?.let { splitAtDateline(geodesicCircle(it.latitude, it.longitude, 300.0)).map { segment -> MapPath(segment, colorInt(MapAmber.copy(alpha = .78f)), 1.4f) } }.orEmpty()
    val center = qth?.let { LatLng(it.latitude, it.longitude) }
        ?: rows.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        ?: LatLng(20.0, 0.0)
    NativeNeuralMap(markers, radius, emptyList(), center, if (qth != null) 4.7 else 1.35, NeuralBasemap.DARK,
        "REGIONAL LIGHTNING · 300 KM", "Interactive regional lightning map with ${rows.size} strikes", modifier)
}

private fun qthMarker(point: GeoPoint) = MapMarker(point.latitude, point.longitude, "Your QTH", "Configured station location", colorInt(MapAmber), 16, true)

private val markerIcons = ConcurrentHashMap<String, Icon>()
private fun mapMarkerIcon(context: android.content.Context, color: Int, sizeDp: Int, ring: Boolean): Icon {
    val density = context.resources.displayMetrics.density
    val key = "$color:$sizeDp:$ring:${(density * 10).roundToInt()}"
    return markerIcons.getOrPut(key) {
        val diameter = ((sizeDp + if (ring) 12 else 8) * density).roundToInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = diameter / 2f
        if (ring) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(color, 42)
            canvas.drawCircle(center, center, center - 1, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = withAlpha(color, 190)
            canvas.drawCircle(center, center, center - 2f * density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(center, center, sizeDp * density / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, sizeDp * density / 2f, paint)
        IconFactory.getInstance(context).fromBitmap(bitmap)
    }
}

private fun colorInt(color: ComposeColor): Int = Color.argb(
    (color.alpha * 255).roundToInt(), (color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt()
)

private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun cellPolygon(latitude: Double, longitude: Double): List<LatLng> {
    val south = (latitude - 15.0).coerceAtLeast(-85.0)
    val north = (latitude + 15.0).coerceAtMost(85.0)
    val west = (longitude - 15.0).coerceAtLeast(-180.0)
    val east = (longitude + 15.0).coerceAtMost(180.0)
    return listOf(LatLng(south, west), LatLng(south, east), LatLng(north, east), LatLng(north, west))
}

internal fun geodesicCircle(latitude: Double, longitude: Double, radiusKm: Double, steps: Int = 72): List<LatLng> {
    val angular = radiusKm / 6371.0088
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    return (0..steps).map { step ->
        val bearing = 2.0 * PI * step / steps
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        LatLng(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }
}

internal fun splitAtDateline(points: List<LatLng>): List<List<LatLng>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf(mutableListOf(points.first()))
    points.zipWithNext().forEach { (previous, next) ->
        if (kotlin.math.abs(next.longitude - previous.longitude) > 180.0) segments += mutableListOf(next)
        else segments.last() += next
    }
    return segments.filter { it.size >= 2 }
}

internal fun splitPolygonAtDateline(points: List<LatLng>): List<List<LatLng>> {
    if (points.size < 3) return emptyList()
    val ring = if (points.first() == points.last()) points.dropLast(1) else points
    if (ring.size < 3) return emptyList()

    val unwrapped = ArrayList<LatLng>(ring.size)
    var previousLongitude = ring.first().longitude
    unwrapped += LatLng(ring.first().latitude, previousLongitude)
    ring.drop(1).forEach { point ->
        var longitude = point.longitude
        while (longitude - previousLongitude > 180.0) longitude -= 360.0
        while (longitude - previousLongitude < -180.0) longitude += 360.0
        unwrapped += LatLng(point.latitude, longitude)
        previousLongitude = longitude
    }

    val firstWindow = kotlin.math.floor((unwrapped.minOf { it.longitude } + 180.0) / 360.0).toInt()
    val lastWindow = kotlin.math.floor((unwrapped.maxOf { it.longitude } + 180.0) / 360.0).toInt()
    return (firstWindow..lastWindow).mapNotNull { window ->
        val west = -180.0 + window * 360.0
        val east = 180.0 + window * 360.0
        val clipped = clipPolygonLongitude(clipPolygonLongitude(unwrapped, west, keepGreater = true), east, keepGreater = false)
        if (clipped.size < 3) null else clipped.map { point ->
            LatLng(point.latitude, (point.longitude - window * 360.0).coerceIn(-180.0, 180.0))
        }
    }
}

private fun clipPolygonLongitude(points: List<LatLng>, boundary: Double, keepGreater: Boolean): List<LatLng> {
    if (points.isEmpty()) return emptyList()
    fun inside(point: LatLng) = if (keepGreater) point.longitude >= boundary else point.longitude <= boundary
    fun intersection(from: LatLng, to: LatLng): LatLng {
        val span = to.longitude - from.longitude
        val fraction = if (kotlin.math.abs(span) < 1e-9) 0.0 else (boundary - from.longitude) / span
        return LatLng(from.latitude + (to.latitude - from.latitude) * fraction, boundary)
    }
    val result = mutableListOf<LatLng>()
    var previous = points.last()
    var previousInside = inside(previous)
    points.forEach { current ->
        val currentInside = inside(current)
        if (currentInside != previousInside) result += intersection(previous, current)
        if (currentInside) result += current
        previous = current
        previousInside = currentInside
    }
    return result
}

internal fun sunPosition(instant: Instant): Pair<Double, Double> {
    val utc = instant.atZone(java.time.ZoneOffset.UTC)
    val jd = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
    val n = jd - 2_451_545.0
    val meanLongitude = ((280.460 + 0.9856474 * n) % 360.0 + 360.0) % 360.0
    val anomaly = Math.toRadians(((357.528 + 0.9856003 * n) % 360.0 + 360.0) % 360.0)
    val eclipticLongitude = meanLongitude + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly)
    val obliquity = 23.439 - 0.0000004 * n
    val declination = Math.toDegrees(asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(eclipticLongitude))))
    val rightAscension = Math.toDegrees(atan2(cos(Math.toRadians(obliquity)) * sin(Math.toRadians(eclipticLongitude)), cos(Math.toRadians(eclipticLongitude))))
    val equationOfTime = meanLongitude - rightAscension
    val utcHours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
    val longitude = ((-(utcHours - 12.0) * 15.0 + equationOfTime) % 360.0 + 540.0) % 360.0 - 180.0
    return declination to longitude
}

internal fun terminatorPoints(instant: Instant): List<LatLng> {
    val (declination, sunLongitude) = sunPosition(instant)
    if (kotlin.math.abs(declination) < .01) {
        fun normalized(longitude: Double) = ((longitude + 540.0) % 360.0) - 180.0
        val first = normalized(sunLongitude - 90.0)
        val second = normalized(sunLongitude + 90.0)
        return (-90..90 step 2).map { LatLng(it.toDouble(), first) } +
            (90 downTo -90 step 2).map { LatLng(it.toDouble(), second) }
    }
    return (-180..180 step 2).map { longitude ->
        val hourAngle = Math.toRadians(longitude - sunLongitude)
        val latitude = Math.toDegrees(kotlin.math.atan(-cos(hourAngle) / kotlin.math.tan(Math.toRadians(declination))))
        LatLng(latitude, longitude.toDouble())
    }
}

internal fun greylineArea(instant: Instant): MapArea {
    val terminator = terminatorPoints(instant)
    if (kotlin.math.abs(sunPosition(instant).first) < .01) {
        return MapArea(terminator, Color.argb(92, 0, 2, 36), Color.TRANSPARENT)
    }
    val pole = if (sunPosition(instant).first >= 0) -85.0 else 85.0
    return MapArea(terminator + listOf(LatLng(pole, 180.0), LatLng(pole, -180.0)), Color.argb(92, 0, 2, 36), Color.TRANSPARENT)
}

private fun greylinePath(instant: Instant) = MapPath(terminatorPoints(instant), colorInt(MapYellow.copy(alpha = .9f)), 1.5f)

private fun satelliteStyleJson() = """
{
  "version": 8,
  "name": "RigWeave Satellite Operations",
  "sources": {
    "esri": {"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"maxzoom":18,"attribution":"Tiles © Esri"},
    "labels": {"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"maxzoom":18,"attribution":"Reference labels © Esri"}
  },
  "layers": [
    {"id":"background","type":"background","paint":{"background-color":"#06151c"}},
    {"id":"imagery","type":"raster","source":"esri","paint":{"raster-opacity":0.82,"raster-saturation":-0.32,"raster-contrast":0.16,"raster-brightness-max":0.72}},
    {"id":"labels","type":"raster","source":"labels","paint":{"raster-opacity":0.72}}
  ]
}
""".trimIndent()
