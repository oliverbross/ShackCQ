package app.shackcq.mobile

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shackcq.mobile.hamclock.HamClockBasemap
import app.shackcq.mobile.hamclock.HamClockDxTarget
import app.shackcq.mobile.hamclock.HamClockDxTargetSource
import app.shackcq.mobile.hamclock.HamClockMapLayerId
import app.shackcq.mobile.hamclock.finishline.HamClockAuroraSnapshot
import app.shackcq.mobile.hamclock.HamClockMapLayerAvailability
import app.shackcq.mobile.hamclock.HamClockMapPreference
import app.shackcq.mobile.hamclock.HamClockMapRenderKind
import app.shackcq.mobile.hamclock.HamClockMapSelection
import app.shackcq.mobile.hamclock.HamClockUnitSystem
import app.shackcq.mobile.hamclock.HamClockIbpSchedule
import app.shackcq.mobile.hamclock.HamClockRbnObservation
import app.shackcq.mobile.hamclock.HamClockWsprSnapshot
import app.shackcq.mobile.hamclock.hamClockIbpManifest
import app.shackcq.mobile.hamclock.hamClockMapLayerRegistry
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOpacity
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal data class HamClockMapPoint(
    val id: String,
    val layerId: String,
    val title: String,
    val detail: String,
    val latitude: Double,
    val longitude: Double,
    val color: String,
    val selection: HamClockMapSelection = HamClockMapSelection.NONE,
    val contextId: String = id,
    val callsign: String = "",
    val frequencyHz: Long? = null,
    val mode: String = "",
    val watchlisted: Boolean = false,
)

internal data class HamClockMapLine(
    val id: String,
    val layerId: String,
    val title: String,
    val detail: String,
    val segments: List<List<GeoPoint>>,
    val color: String,
    val selection: HamClockMapSelection = HamClockMapSelection.NONE,
    val contextId: String = id,
    val callsign: String = "",
    val frequencyHz: Long? = null,
    val mode: String = "",
    val watchlisted: Boolean = false,
)

internal data class HamClockMapFill(
    val id: String,
    val layerId: String,
    val title: String,
    val detail: String,
    val ring: List<GeoPoint>,
    val color: String,
)

internal data class HamClockMapSnapshot(
    val points: List<HamClockMapPoint> = emptyList(),
    val lines: List<HamClockMapLine> = emptyList(),
    val fills: List<HamClockMapFill> = emptyList(),
    val generatedAtEpoch: Long = 0,
    val sourceStatus: Map<String, HamClockMapSourceStatus> = emptyMap(),
)

internal enum class HamClockMapSourceState { CURRENT, LIVE, CACHED, DEGRADED, STALE, OFFLINE_CACHE, EMPTY, ERROR, UNAVAILABLE }

internal data class HamClockMapSourceStatus(
    val state: HamClockMapSourceState,
    val observedAtEpoch: Long,
    val provenance: String,
    val detail: String = "",
)

internal data class HamClockVisibleStatusCounts(
    val current: Int,
    val degraded: Int,
    val empty: Int,
    val unavailable: Int,
)

internal fun hamClockVisibleStatusCounts(
    preference: HamClockMapPreference,
    statuses: Map<String, HamClockMapSourceStatus>,
): HamClockVisibleStatusCounts {
    val states = preference.layers.asSequence().filter { it.visible }.map { layer ->
        statuses[layer.id]?.state ?: HamClockMapSourceState.UNAVAILABLE
    }.toList()
    return HamClockVisibleStatusCounts(
        current = states.count { it in setOf(HamClockMapSourceState.CURRENT, HamClockMapSourceState.LIVE) },
    degraded = states.count { it in setOf(HamClockMapSourceState.CACHED, HamClockMapSourceState.DEGRADED, HamClockMapSourceState.STALE,
            HamClockMapSourceState.OFFLINE_CACHE, HamClockMapSourceState.ERROR) },
        empty = states.count { it == HamClockMapSourceState.EMPTY },
        unavailable = states.count { it == HamClockMapSourceState.UNAVAILABLE },
    )
}

internal fun hamClockLateStyleSuccess(activeGeneration: Int, callbackGeneration: Int, previousError: String): Pair<Boolean, String> =
    if (activeGeneration == callbackGeneration) true to "" else false to previousError

internal data class HamClockMapFeatureRef(
    val layerId: String,
    val featureId: String,
    val selection: HamClockMapSelection,
    val callsign: String = "",
    val frequencyHz: Long? = null,
    val mode: String = "",
)

internal fun mergeGestureCameraPreference(
    latest: HamClockMapPreference,
    baseLatitude: Double,
    baseLongitude: Double,
    baseZoom: Double,
    targetLatitude: Double,
    targetLongitude: Double,
    targetZoom: Double,
): HamClockMapPreference? {
    if (latest.centerLatitude != baseLatitude || latest.centerLongitude != baseLongitude || latest.zoom != baseZoom) return null
    return latest.copy(centerLatitude = targetLatitude.coerceIn(-85.0, 85.0),
        centerLongitude = targetLongitude.coerceIn(-180.0, 180.0), zoom = targetZoom.coerceIn(.8, 12.0))
}

internal data class HamClockResolvedTarget(
    val callsign: String,
    val grid: String,
    val point: GeoPoint,
    val source: HamClockDxTargetSource,
    val detail: String,
)

internal fun resolveHamClockTarget(
    stored: HamClockDxTarget?,
    automatic: AndroidDXSpot?,
): HamClockResolvedTarget? {
    val storedPoint = stored?.let { value ->
        value.latitude?.let { latitude -> value.longitude?.let { longitude -> GeoPoint(latitude, longitude) } }
            ?: maidenheadCenter(value.grid)
    }
    if (stored != null && storedPoint != null && (stored.locked || automatic == null)) {
        return HamClockResolvedTarget(
            stored.callsign.ifBlank { stored.grid.ifBlank { "Manual target" } },
            stored.grid,
            storedPoint,
            stored.source,
            if (stored.locked) "Locked manual target" else "Stored manual target",
        )
    }
    return automatic?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let {
        HamClockResolvedTarget(
            it.callsign,
            "",
            GeoPoint(it.latitude, it.longitude),
            HamClockDxTargetSource.AUTOMATIC,
            listOf(it.country, it.band, it.mode).filter(String::isNotBlank).joinToString(" · "),
        )
    } ?: storedPoint?.let {
        HamClockResolvedTarget(stored?.callsign.orEmpty(), stored?.grid.orEmpty(), it,
            stored?.source ?: HamClockDxTargetSource.MANUAL, "Stored target")
    }
}

internal fun buildHamClockMapSnapshot(
    stationCall: String,
    stationGrid: String,
    dxSpots: List<AndroidDXSpot>,
    signal: NeuralMySignal,
    portableSpots: List<PortableSpot>,
    satellites: List<HamClockSatellitePosition>,
    recentQsos: List<HamClockRecentQso>,
    lightning: NeuralLightning,
    target: HamClockResolvedTarget?,
    now: java.time.Instant,
    units: HamClockUnitSystem = HamClockUnitSystem.METRIC,
    sourceStatus: Map<String, HamClockMapSourceStatus> = emptyMap(),
    rbn: List<Pair<HamClockRbnObservation, GeoPoint>> = emptyList(),
    wspr: HamClockWsprSnapshot = HamClockWsprSnapshot(),
    ibp: HamClockIbpSchedule? = null,
    rbnShowPaths: Boolean = true,
    wsprShowPaths: Boolean = true,
    ibpPreference: app.shackcq.mobile.hamclock.HamClockIbpPreference = app.shackcq.mobile.hamclock.HamClockIbpPreference(),
    satelliteTracks: List<HamClockSatelliteTrack> = emptyList(),
    satelliteFootprints: List<HamClockSatelliteFootprint> = emptyList(),
    contestQsos: List<HamClockContestQso> = emptyList(),
    aurora: HamClockAuroraSnapshot = HamClockAuroraSnapshot(),
    outlook: NeuralOutlookSnapshot = NeuralOutlookSnapshot(),
): HamClockMapSnapshot {
    val station = maidenheadCenter(stationGrid)
    val points = mutableListOf<HamClockMapPoint>()
    val lines = mutableListOf<HamClockMapLine>()
    val fills = mutableListOf<HamClockMapFill>()
    station?.let {
        points += HamClockMapPoint("de", HamClockMapLayerId.DE_STATION,
            stationCall.ifBlank { "DE station" }, stationGrid.ifBlank { "Configured station" },
            it.latitude, it.longitude, "#42c7d8")
    }
    dxSpots.asSequence().filter { it.latitude != 0.0 || it.longitude != 0.0 }.take(160).forEach { spot ->
        val mapPoint = hamClockDxMapPoint(spot)
        val detail = mapPoint.detail
        points += mapPoint
        station?.let { origin ->
            val path = greatCirclePath(LatLng(origin.latitude, origin.longitude), LatLng(spot.latitude, spot.longitude), 28)
            lines += HamClockMapLine(spot.id, HamClockMapLayerId.DX_PATHS, spot.callsign, detail,
                splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                hamClockBandColor(spot.band), HamClockMapSelection.DX_SPOT, spot.id, spot.callsign, spot.frequencyHz, spot.mode,
                spot.watchlisted)
        }
    }
    target?.let { selected ->
        points += HamClockMapPoint("target", HamClockMapLayerId.SELECTED_TARGET, selected.callsign,
            "${selected.source.name.lowercase().replaceFirstChar(Char::uppercase)} · ${selected.detail}",
            selected.point.latitude, selected.point.longitude, "#f0ad35", HamClockMapSelection.TARGET)
        station?.let { origin ->
            val path = greatCirclePath(LatLng(origin.latitude, origin.longitude),
                LatLng(selected.point.latitude, selected.point.longitude), 40)
            lines += HamClockMapLine("target", HamClockMapLayerId.SELECTED_TARGET, selected.callsign,
                selected.detail, splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                "#f0ad35", HamClockMapSelection.TARGET, "target", selected.callsign)
        }
    }
    signal.reports.asSequence().filter { it.latitude != null && it.longitude != null }.take(60).forEach { report ->
        val latitude = report.latitude ?: return@forEach
        val longitude = report.longitude ?: return@forEach
        val mapPoint = hamClockPskMapPoint(report, units) ?: return@forEach
        val reportId = mapPoint.contextId
        points += mapPoint
        station?.let { origin ->
            val endpoints = hamClockSignalPathEndpoints(report, origin) ?: return@let
            val path = greatCirclePath(LatLng(endpoints.first.latitude, endpoints.first.longitude),
                LatLng(endpoints.second.latitude, endpoints.second.longitude), 20)
            lines += HamClockMapLine(reportId, HamClockMapLayerId.PSK_REPORTER,
                report.callsign, mapPoint.detail, splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                if (report.mutual) "#f3d054" else hamClockBandColor(report.band), HamClockMapSelection.PSK_REPORT, reportId, report.callsign,
                report.frequencyHz, report.mode)
        }
    }
    rbn.take(120).forEach { (row, location) ->
        val relationship = "${row.skimmerCall} heard ${row.dxCall}"
        val detail = "$relationship · ${row.band} ${row.mode}" +
            row.snr?.let { " · $it dB" }.orEmpty() + row.wpm?.let { " · $it WPM" }.orEmpty()
        points += HamClockMapPoint(row.id, HamClockMapLayerId.RBN, relationship, detail,
            location.latitude, location.longitude, "#4ed9b2", HamClockMapSelection.RBN_OBSERVATION,
            row.id, row.skimmerCall, row.frequencyHz, row.mode)
        val endpoints = hamClockRbnPathEndpoints(row)
        if (endpoints != null && rbnShowPaths) {
            val path = greatCirclePath(LatLng(endpoints.first.latitude, endpoints.first.longitude),
                LatLng(endpoints.second.latitude, endpoints.second.longitude), 20)
            lines += HamClockMapLine(row.id, HamClockMapLayerId.RBN, relationship, detail,
                splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                hamClockBandColor(row.band), HamClockMapSelection.RBN_OBSERVATION, row.id, row.skimmerCall,
                row.frequencyHz, row.mode)
        }
    }
    wspr.reports.asSequence().filter { it.latitude != null && it.longitude != null }.take(100).forEach { report ->
        val latitude = report.latitude ?: return@forEach
        val longitude = report.longitude ?: return@forEach
        val id = signalReportReference(report)
        val detail = "${report.direction.name.replace('_', ' ')} · ${report.band} WSPR" +
            report.snr?.let { " · $it dB" }.orEmpty()
        points += HamClockMapPoint(id, HamClockMapLayerId.WSPR_EXPANDED, report.callsign, detail,
            latitude, longitude, "#9d72f2", HamClockMapSelection.WSPR_OBSERVATION,
            id, report.callsign, report.frequencyHz, "WSPR")
        if (station != null && wsprShowPaths) {
            val endpoints = if (report.direction == SignalDirection.HEARING)
                LatLng(latitude, longitude) to LatLng(station.latitude, station.longitude)
            else LatLng(station.latitude, station.longitude) to LatLng(latitude, longitude)
            val path = greatCirclePath(endpoints.first, endpoints.second, 20)
            lines += HamClockMapLine(id, HamClockMapLayerId.WSPR_EXPANDED, report.callsign, detail,
                splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                hamClockBandColor(report.band), HamClockMapSelection.WSPR_OBSERVATION, id,
                report.callsign, report.frequencyHz, "WSPR")
        }
    }
    ibp?.let { schedule ->
        val sites = if (ibpPreference.showAllSites) hamClockIbpManifest else
            schedule.transmissions.map { it.beacon }.distinctBy { it.callsign }
        sites.take(18).forEach { beacon ->
            points += HamClockMapPoint(beacon.callsign, HamClockMapLayerId.IBP, beacon.callsign,
                "${beacon.grid} · schedule reference; not heard evidence", beacon.point.latitude, beacon.point.longitude,
                "#f3d054", HamClockMapSelection.IBP_BEACON, beacon.callsign, beacon.callsign)
        }
        if (station != null && ibpPreference.showPaths) schedule.transmissions.take(5).forEach { transmission ->
            val beacon = transmission.beacon
            val path = greatCirclePath(LatLng(station.latitude, station.longitude),
                LatLng(beacon.point.latitude, beacon.point.longitude), 24)
            lines += HamClockMapLine("${transmission.band}-${beacon.callsign}", HamClockMapLayerId.IBP,
                beacon.callsign, "${transmission.band} scheduled now · not heard evidence",
                splitAtDateline(path).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
                hamClockBandColor(transmission.band), HamClockMapSelection.IBP_BEACON, beacon.callsign,
                beacon.callsign, transmission.frequencyHz, "CW")
        }
    }
    portableSpots.asSequence().filter { it.latitude != null && it.longitude != null }.take(160).forEach { spot ->
        points += HamClockMapPoint(spot.id, HamClockMapLayerId.PORTABLE, spot.callsign,
            "${spot.references.joinToString { it.code }} · ${spot.band} ${spot.mode}",
            spot.latitude!!, spot.longitude!!, "#f3d054", HamClockMapSelection.PORTABLE,
            spot.id, spot.callsign, spot.frequencyHz, spot.mode)
    }
    satellites.take(40).forEach { satellite ->
        val altitude = hamClockDistanceLabel(satellite.altitudeKm, units)
        points += HamClockMapPoint(satellite.noradId.toString(), HamClockMapLayerId.SATELLITES, satellite.name,
            "$altitude · elevation ${satellite.elevationDeg.toInt()}°${if (satellite.stale) " · STALE" else ""}",
            satellite.latitude, satellite.longitude, "#b783f5", HamClockMapSelection.SATELLITE,
            satellite.noradId.toString())
    }
    satelliteTracks.take(4).forEach { track ->
        lines += HamClockMapLine("sat-track-${track.noradId}", HamClockMapLayerId.SATELLITES, track.name,
            "Authoritative local SGP4 ground track${if (track.stale) " · STALE elements" else ""}",
            track.segments, "#b783f5", HamClockMapSelection.SATELLITE, track.noradId.toString())
    }
    satelliteFootprints.take(4).forEach { footprint ->
        fills += HamClockMapFill("sat-footprint-${footprint.noradId}", HamClockMapLayerId.SATELLITES,
            footprint.name, "Radio-horizon footprint${if (footprint.stale) " · STALE elements" else ""}",
            unwrapDatelineRing(footprint.ring), "#563878")
    }
    recentQsos.take(120).forEach { qso ->
        maidenheadCenter(qso.grid)?.let { location ->
            points += HamClockMapPoint(qso.id, HamClockMapLayerId.LOGGED_QSOS, qso.callsign,
                "${qso.grid} · ${qso.band} ${qso.mode} · ${qso.country}",
                location.latitude, location.longitude, "#91a1a9", HamClockMapSelection.QSO,
                qso.id, qso.callsign, mode = qso.mode)
        }
    }
    contestQsos.take(200).forEach { qso ->
        maidenheadCenter(qso.grid)?.let { location ->
            points += HamClockMapPoint("contest-${qso.id}", HamClockMapLayerId.CONTEST_QSOS, qso.callsign,
                "${qso.contestId.ifBlank { "Selected contest" }} · ${qso.band} ${qso.mode}${if (qso.confirmed) " · CONFIRMED" else ""}",
                location.latitude, location.longitude, if (qso.confirmed) "#42c77b" else "#f0ad35",
                HamClockMapSelection.QSO, qso.id, qso.callsign, mode = qso.mode)
            station?.let { origin ->
                val path = greatCirclePath(LatLng(origin.latitude, origin.longitude),
                    LatLng(location.latitude, location.longitude), 20)
                lines += HamClockMapLine("contest-path-${qso.id}", HamClockMapLayerId.CONTEST_QSOS,
                    qso.callsign, "Selected contest logged-QSO path",
                    listOf(path.map { GeoPoint(it.latitude, it.longitude) }),
                    if (qso.confirmed) "#42c77b" else "#f0ad35", HamClockMapSelection.QSO,
                    qso.id, qso.callsign, mode = qso.mode)
            }
        }
    }
    aurora.cells.asSequence().sortedByDescending { it.probability }.take(180).forEachIndexed { index, cell ->
        val half = 1.25
        fills += HamClockMapFill("aurora-$index", HamClockMapLayerId.AURORA, "Aurora ${cell.probability}%",
            "NOAA OVATION forecast · ${aurora.forecastAtEpoch}", listOf(
                GeoPoint((cell.latitude - half).coerceIn(-90.0, 90.0), cell.longitude - half),
                GeoPoint((cell.latitude - half).coerceIn(-90.0, 90.0), cell.longitude + half),
                GeoPoint((cell.latitude + half).coerceIn(-90.0, 90.0), cell.longitude + half),
                GeoPoint((cell.latitude + half).coerceIn(-90.0, 90.0), cell.longitude - half),
                GeoPoint((cell.latitude - half).coerceIn(-90.0, 90.0), cell.longitude - half),
            ), if (cell.probability >= 50) "#45e389" else if (cell.probability >= 25) "#7ccf6b" else "#5d9466")
    }
    outlook.world.take(72).forEach { cell ->
        val halfLat = 15.0; val halfLon = 15.0
        val forecast = cell.forecast
        val color = when (forecast.label) {
            OutlookLabel.STRONG -> "#43d17c"
            OutlookLabel.FAVOURABLE -> "#42c7d8"
            OutlookLabel.BUILDING -> "#f0ad35"
            OutlookLabel.QUIET -> "#61727b"
            OutlookLabel.DEGRADED -> "#e65b54"
            OutlookLabel.INSUFFICIENT_EVIDENCE -> "#293940"
        }
        fills += HamClockMapFill("outlook-${cell.row}-${cell.column}", HamClockMapLayerId.NEURAL_OUTLOOK,
            "${forecast.band} · ${forecast.label.name.replace('_', ' ')}",
            "${outlook.modelVersion} · ${outlook.selectedWindow.minutes} min · ${forecast.confidence} · ${forecast.baselineSamples} matched buckets · ${forecast.sourceCount} sources · generated ${outlook.generatedEpoch}",
            listOf(GeoPoint(cell.latitude - halfLat, cell.longitude - halfLon),
                GeoPoint(cell.latitude - halfLat, cell.longitude + halfLon),
                GeoPoint(cell.latitude + halfLat, cell.longitude + halfLon),
                GeoPoint(cell.latitude + halfLat, cell.longitude - halfLon),
                GeoPoint(cell.latitude - halfLat, cell.longitude - halfLon)), color)
    }
    val grayline = greylineArea(now)
    fills += HamClockMapFill("night", HamClockMapLayerId.GRAYLINE, "Night region", "Local UTC astronomy",
        grayline.points.map { GeoPoint(it.latitude, it.longitude) }, "#13243b")
    val terminator = terminatorPoints(now)
    lines += HamClockMapLine("terminator", HamClockMapLayerId.GRAYLINE, "Grayline", "Current UTC terminator",
        splitAtDateline(terminator).map { segment -> segment.map { GeoPoint(it.latitude, it.longitude) } },
        "#f0ad35")
    val sun = sunPosition(now)
    points += HamClockMapPoint("sun", HamClockMapLayerId.SUN, "Sun", "Approximate subsolar point",
        sun.first, sun.second, "#f3d054")
    var gridIndex = 0
    for (longitude in -160..160 step 20) {
        lines += HamClockMapLine("grid-${gridIndex++}", HamClockMapLayerId.GRID, "Maidenhead field grid",
            "20° longitude field boundary", listOf(listOf(GeoPoint(-80.0, longitude.toDouble()), GeoPoint(80.0, longitude.toDouble()))),
            "#91a1a9")
    }
    for (latitude in -70..70 step 10) {
        lines += HamClockMapLine("grid-${gridIndex++}", HamClockMapLayerId.GRID, "Maidenhead field grid",
            "10° latitude field boundary", listOf(listOf(GeoPoint(latitude.toDouble(), -180.0), GeoPoint(latitude.toDouble(), 180.0))),
            "#91a1a9")
    }
    lightning.strikes.take(120).forEachIndexed { index, strike ->
        points += HamClockMapPoint("${strike.epoch}-$index", HamClockMapLayerId.LIGHTNING, "Lightning",
            hamClockDistanceLabel(strike.distanceKm, units) + " · ${strike.bearing}", strike.latitude, strike.longitude,
            "#e65b54", HamClockMapSelection.WEATHER)
    }
    return boundedHamClockMapSnapshot(HamClockMapSnapshot(points, lines, fills, now.epochSecond, sourceStatus))
}

internal fun unwrapDatelineRing(ring: List<GeoPoint>): List<GeoPoint> {
    if (ring.isEmpty()) return ring
    val result = mutableListOf(ring.first())
    ring.drop(1).forEach { point ->
        var longitude = point.longitude
        while (longitude - result.last().longitude > 180.0) longitude -= 360.0
        while (longitude - result.last().longitude < -180.0) longitude += 360.0
        result += GeoPoint(point.latitude, longitude)
    }
    return result
}

internal fun hamClockRbnPathEndpoints(row: HamClockRbnObservation): Pair<GeoPoint, GeoPoint>? =
    row.dxPoint?.let { dx -> row.skimmerPoint?.let { skimmer -> dx to skimmer } }

internal fun hamClockSignalPathEndpoints(report: SignalReport, station: GeoPoint): Pair<GeoPoint, GeoPoint>? {
    val remote = GeoPoint(report.latitude ?: return null, report.longitude ?: return null)
    return if (report.direction == SignalDirection.HEARING) remote to station else station to remote
}

internal fun hamClockDxMapPoint(spot: AndroidDXSpot): HamClockMapPoint {
    val detail = listOf(spot.country.ifBlank { "Unknown" }, "${spot.band} ${spot.mode}",
        "${spot.frequencyHz / 1000} kHz", "★ WATCHLIST".takeIf { spot.watchlisted }).filterNotNull().joinToString(" · ")
    return HamClockMapPoint(spot.id, HamClockMapLayerId.DX_SPOTS,
        if (spot.watchlisted) "★ ${spot.callsign}" else spot.callsign, detail,
        spot.latitude, spot.longitude, hamClockBandColor(spot.band),
        HamClockMapSelection.DX_SPOT, spot.id, spot.callsign, spot.frequencyHz, spot.mode, spot.watchlisted)
}

internal fun hamClockPskMapPoint(report: SignalReport, units: HamClockUnitSystem): HamClockMapPoint? {
    val latitude = report.latitude ?: return null
    val longitude = report.longitude ?: return null
    val detail = listOf(report.direction.name.replace('_', ' '), report.locator, "${report.band} ${report.mode}",
        report.snr?.let { "$it dB" } ?: "SNR unavailable",
        report.distanceKm?.let { hamClockDistanceLabel(it.toDouble(), units) }, "MUTUAL".takeIf { report.mutual }).filterNotNull()
        .filter(String::isNotBlank).joinToString(" · ")
    val reportId = signalReportReference(report)
    return HamClockMapPoint(reportId, HamClockMapLayerId.PSK_REPORTER,
        report.callsign, detail, latitude, longitude, if (report.mutual) "#f3d054" else hamClockBandColor(report.band), HamClockMapSelection.PSK_REPORT,
        reportId, report.callsign, report.frequencyHz, report.mode)
}

internal fun hamClockDistanceLabel(kilometres: Double, units: HamClockUnitSystem): String =
    when (units) {
        HamClockUnitSystem.METRIC -> if (kilometres < 1.0) "${(kilometres * 1_000).toInt()} m" else "${kilometres.toInt()} km"
        HamClockUnitSystem.IMPERIAL -> {
            val miles = kilometres * .621371
            if (miles < 1.0) "${(kilometres * 3_280.84).toInt()} ft" else "${miles.toInt()} mi"
        }
    }

private data class HamClockSelectedFeature(
    val title: String,
    val detail: String,
    val selection: HamClockMapSelection,
    val reference: HamClockMapFeatureRef,
)

private data class HamClockPendingCamera(
    val camera: CameraPosition,
    val generation: Int,
    val baseLatitude: Double,
    val baseLongitude: Double,
    val baseZoom: Double,
)

internal fun boundedHamClockMapSnapshot(snapshot: HamClockMapSnapshot): HamClockMapSnapshot {
    fun maximum(layerId: String) = hamClockMapLayerRegistry.firstOrNull { it.id == layerId }?.maximumObjectCount ?: 0
    return snapshot.copy(
        points = snapshot.points.groupBy { it.layerId }.flatMap { (id, rows) -> rows.take(maximum(id)) },
        lines = snapshot.lines.groupBy { it.layerId }.flatMap { (id, rows) -> rows.take(maximum(id)) },
        fills = snapshot.fills.groupBy { it.layerId }.flatMap { (id, rows) -> rows.take(maximum(id)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HamClockHomeMap(
    snapshot: HamClockMapSnapshot,
    preference: HamClockMapPreference,
    station: GeoPoint?,
    lowDataMode: Boolean,
    onPreferenceChange: (HamClockMapPreference) -> Unit,
    onOpenSelection: (HamClockMapFeatureRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounded = remember(snapshot) { boundedHamClockMapSnapshot(snapshot) }
    if (lowDataMode) {
        HamClockMapDataView(bounded, preference, onOpenSelection, modifier)
        return
    }
    val context = LocalContext.current
    val hitSlopPx = with(LocalDensity.current) { 20.dp.toPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    val callbackLifecycle = remember(mapView) { LifecycleGeneration() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<HamClockSelectedFeature?>(null) }
    var pendingCamera by remember { mutableStateOf<HamClockPendingCamera?>(null) }
    var cameraGeneration by remember { mutableStateOf(0) }
    var gestureInProgress by remember { mutableStateOf(false) }
    var styleGeneration by remember { mutableStateOf(0) }
    val sourceFingerprints = remember { mutableMapOf<String, Int>() }
    val currentPreference by rememberUpdatedState(preference)
    val currentPreferenceChange by rememberUpdatedState(onPreferenceChange)
    val currentOpenSelection by rememberUpdatedState(onOpenSelection)

    DisposableEffect(mapView, lifecycle) {
        val callbackGeneration = callbackLifecycle.next()
        var started = false
        var resumed = false
        var destroyed = false
        fun start() { if (!started && !destroyed) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed && !destroyed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed && !destroyed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started && !destroyed) { mapView.onStop(); started = false } }
        fun destroy() { if (!destroyed) { stop(); mapView.onDestroy(); destroyed = true } }
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
        mapView.getMapAsync { ready ->
            if (disposed || !callbackLifecycle.isCurrent(callbackGeneration)) return@getMapAsync
            map = ready
            ready.uiSettings.apply {
                isAttributionEnabled = true
                isLogoEnabled = true
                isCompassEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            ready.setMinZoomPreference(.8)
            ready.setMaxZoomPreference(12.0)
            ready.cameraPosition = CameraPosition.Builder()
                .target(LatLng(preference.centerLatitude, preference.centerLongitude))
                .zoom(preference.zoom)
                .build()
            ready.addOnCameraMoveStartedListener { reason ->
                if (!callbackLifecycle.isCurrent(callbackGeneration)) return@addOnCameraMoveStartedListener
                val active = currentPreference
                gestureInProgress = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                if (gestureInProgress) {
                    cameraGeneration += 1
                    if (active.followStation) currentPreferenceChange(active.copy(followStation = false))
                }
            }
            ready.addOnCameraIdleListener {
                if (!callbackLifecycle.isCurrent(callbackGeneration)) return@addOnCameraIdleListener
                if (gestureInProgress) {
                    val active = currentPreference
                    pendingCamera = HamClockPendingCamera(ready.cameraPosition, cameraGeneration,
                        active.centerLatitude, active.centerLongitude, active.zoom)
                }
                gestureInProgress = false
            }
            ready.addOnMapClickListener { coordinate ->
                if (!callbackLifecycle.isCurrent(callbackGeneration)) return@addOnMapClickListener false
                val layerIds = activeLayerIds(currentPreference)
                val screen = ready.projection.toScreenLocation(coordinate)
                val hits = ready.queryRenderedFeatures(RectF(screen.x - hitSlopPx, screen.y - hitSlopPx,
                    screen.x + hitSlopPx, screen.y + hitSlopPx), *layerIds.toTypedArray())
                val hit = hits.firstOrNull { feature ->
                    feature.getStringProperty("selection")?.let {
                        runCatching { HamClockMapSelection.valueOf(it) }.getOrDefault(HamClockMapSelection.NONE)
                    } != HamClockMapSelection.NONE
                } ?: hits.firstOrNull()
                selected = hit?.let { feature ->
                    HamClockSelectedFeature(
                        feature.getStringProperty("title") ?: "Map item",
                        feature.getStringProperty("detail") ?: "",
                        runCatching { HamClockMapSelection.valueOf(feature.getStringProperty("selection") ?: "NONE") }.getOrDefault(HamClockMapSelection.NONE),
                        HamClockMapFeatureRef(feature.getStringProperty("layerId") ?: "",
                            feature.getStringProperty("contextId") ?: feature.id() ?: "",
                            runCatching { HamClockMapSelection.valueOf(feature.getStringProperty("selection") ?: "NONE") }.getOrDefault(HamClockMapSelection.NONE),
                            feature.getStringProperty("callsign") ?: "",
                            feature.getNumberProperty("frequencyHz")?.toLong(), feature.getStringProperty("mode") ?: ""),
                    )
                }
                hit != null
            }
        }
        onDispose {
            disposed = true
            callbackLifecycle.retire()
            styleGeneration += 1
            lifecycle.removeObserver(observer)
            map = null
            styleReady = false
            destroy()
        }
    }

    LaunchedEffect(map, preference.basemap) {
        val ready = map ?: return@LaunchedEffect
        val generation = ++styleGeneration
        styleReady = false
        mapError = ""
        if (preference.basemap == HamClockBasemap.SATELLITE || preference.basemap == HamClockBasemap.TERRAIN) {
            mapError = "${preference.basemap.name.lowercase().replaceFirstChar(Char::uppercase)} basemap is unavailable: no lawful configured tile source"
            return@LaunchedEffect
        }
        runCatching {
                    val builder = Style.Builder().fromUri(
                        if (preference.basemap == HamClockBasemap.LIGHT) OPEN_FREE_MAP_LIGHT_STYLE
                        else OPEN_FREE_MAP_DARK_STYLE
                    )
            ready.setStyle(builder) { style ->
                val (accepted, recoveredError) = hamClockLateStyleSuccess(styleGeneration, generation, mapError)
                if (accepted) {
                    sourceFingerprints.clear()
                    installHamClockLayers(style, currentPreference)
                    mapError = recoveredError
                    styleReady = true
                }
            }
        }.onFailure { mapError = it.message ?: "Map style unavailable" }
        if (!styleReady) {
            delay(8_000)
            if (generation == styleGeneration && !styleReady) mapError = "Basemap style timed out; showing bounded Map Data"
        }
    }

    LaunchedEffect(map, styleReady, bounded, preference.layers) {
        val style = map?.style ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        updateHamClockSources(style, bounded, preference, sourceFingerprints)
    }

    LaunchedEffect(preference.followStation, station, map, styleReady) {
        if (preference.followStation && station != null && styleReady) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(station.latitude, station.longitude), preference.zoom), 500)
        }
    }

    LaunchedEffect(preference.centerLatitude, preference.centerLongitude, preference.zoom, map, styleReady) {
        if (styleReady && !preference.followStation) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(preference.centerLatitude, preference.centerLongitude), preference.zoom), 300)
        }
    }

    LaunchedEffect(pendingCamera) {
        val pending = pendingCamera ?: return@LaunchedEffect
        val camera = pending.camera
        delay(600)
        val active = currentPreference
        if (pending == pendingCamera && pending.generation == cameraGeneration) {
            val target = camera.target ?: return@LaunchedEffect
            mergeGestureCameraPreference(active, pending.baseLatitude, pending.baseLongitude, pending.baseZoom,
                target.latitude, target.longitude, camera.zoom)?.let(currentPreferenceChange)
        }
    }

    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF07151B))
        .border(1.dp, Color(0xFF32434C), RoundedCornerShape(8.dp))
        .semantics { contentDescription = "Interactive HamClock world activity map" }) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Surface(color = Color(0xD9111C22), shape = RoundedCornerShape(5.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            val counts = hamClockVisibleStatusCounts(preference, bounded.sourceStatus)
            Text("MAP · ${preference.basemap.name} · ${counts.current} current / ${counts.degraded} degraded / ${counts.empty} empty / ${counts.unavailable} unavailable", color = Color(0xFFEAF0ED),
                fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
        }
        IconButton(onClick = {
            station?.let {
                val reset = preference.copy(followStation = true, centerLatitude = it.latitude, centerLongitude = it.longitude)
                onPreferenceChange(reset)
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), reset.zoom), 500)
            }
        }, enabled = station != null, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp)) {
            Icon(Icons.Outlined.MyLocation, contentDescription = "Reset map to station", tint = Color(0xFF42C7D8))
        }
        if (mapError.isNotBlank()) {
            HamClockMapFallback(mapError, bounded, preference, onOpenSelection, Modifier.fillMaxSize())
        }
    }
    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Color(0xFF111C22)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, color = Color(0xFFEAF0ED), fontSize = 18.sp)
                Text(item.detail.ifBlank { "No additional detail" }, color = Color(0xFF91A1A9))
                if (item.selection != HamClockMapSelection.NONE) {
                    TextButton(onClick = { selected = null; currentOpenSelection(item.reference) }) { Text(hamClockSelectionAction(item.selection)) }
                } else TextButton(onClick = { selected = null }) { Text("Close") }
                if (item.selection != HamClockMapSelection.NONE) {
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
internal fun HamClockMapDataView(
    snapshot: HamClockMapSnapshot,
    preference: HamClockMapPreference,
    onOpenSelection: (HamClockMapFeatureRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = preference.layers.filter { it.visible }.associateBy { it.id }
    LazyColumn(modifier.background(Color(0xFF07151B)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        hamClockMapLayerRegistry.forEach { spec ->
            val pref = visible[spec.id] ?: return@forEach
            item(key = "header-${spec.id}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.title, color = Color(0xFFEAF0ED), fontSize = 12.sp)
                        Text("${spec.sourceLabel} · ${spec.lowDataRepresentation}", color = Color(0xFF91A1A9), fontSize = 9.sp)
                        snapshot.sourceStatus[spec.id]?.let { status ->
                            Text("${status.state} · ${status.provenance} · ${status.observedAtEpoch.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it).toString() } ?: "no observation time"}${status.detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                color = if (status.state in setOf(HamClockMapSourceState.ERROR, HamClockMapSourceState.DEGRADED, HamClockMapSourceState.STALE, HamClockMapSourceState.OFFLINE_CACHE)) Color(0xFFF0AD35) else Color(0xFF42C7D8), fontSize = 9.sp)
                        }
                    }
                    Text("${(pref.opacity * 100).toInt()}%", color = Color(0xFF42C7D8), fontSize = 9.sp)
                }
                if (spec.availability == HamClockMapLayerAvailability.UNAVAILABLE) {
                    Text("Unavailable · ${spec.unavailableReason}", color = Color(0xFFF0AD35), fontSize = 10.sp)
                }
            }
            val points = snapshot.points.filter { it.layerId == spec.id }
            items(points, key = { "${spec.id}-${it.id}" }) { row ->
                Surface(color = Color(0xFF182831), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(7.dp)) {
                        Text(row.title, color = Color(0xFFEAF0ED), fontSize = 11.sp)
                        Text(row.detail, color = Color(0xFF91A1A9), fontSize = 9.sp)
                        if (row.selection != HamClockMapSelection.NONE) {
                            TextButton(onClick = { onOpenSelection(row.reference()) }) { Text(hamClockSelectionAction(row.selection)) }
                        }
                    }
                }
            }
            val lineCount = snapshot.lines.count { it.layerId == spec.id }
            val fillCount = snapshot.fills.count { it.layerId == spec.id }
            if (lineCount + fillCount > 0) item(key = "summary-${spec.id}") {
                Text("$lineCount paths · $fillCount areas", color = Color(0xFF91A1A9), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun HamClockMapFallback(
    error: String,
    snapshot: HamClockMapSnapshot,
    preference: HamClockMapPreference,
    onOpenSelection: (HamClockMapFeatureRef) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(Color(0xF20B1419)).padding(12.dp)) {
        Text("Map unavailable", color = Color(0xFFF0AD35))
        Text(error, color = Color(0xFF91A1A9), fontSize = 10.sp)
        HamClockMapDataView(snapshot, preference, onOpenSelection, Modifier.weight(1f).fillMaxWidth())
    }
}

private fun activeLayerIds(preference: HamClockMapPreference): List<String> {
    val visible = preference.layers.filter { it.visible }.map { it.id }.toSet()
    return hamClockMapLayerRegistry.filter { it.id in visible && it.availability != HamClockMapLayerAvailability.UNAVAILABLE }
        .flatMap { spec -> spec.renderKinds.map { "${spec.sourcePrefix}-${it.name.lowercase()}-layer" } }
}

private fun installHamClockLayers(style: Style, preference: HamClockMapPreference) {
    hamClockMapLayerRegistry.filter { it.availability != HamClockMapLayerAvailability.UNAVAILABLE }.forEach { spec ->
        spec.renderKinds.forEach { kind ->
            val suffix = kind.name.lowercase()
            val sourceId = "${spec.sourcePrefix}-$suffix-source"
            val layerId = "${spec.sourcePrefix}-$suffix-layer"
            style.addSource(GeoJsonSource(sourceId, emptyFeatureCollection()))
            val opacity = preference.layers.firstOrNull { it.id == spec.id }?.opacity ?: spec.defaultOpacity
            when (kind) {
                HamClockMapRenderKind.POINT -> {
                    style.addLayer(CircleLayer(layerId, sourceId).withProperties(
                        circleColor(Expression.get("color")), circleRadius(5.5f), circleOpacity(opacity),
                        circleStrokeColor(Expression.switchCase(Expression.eq(Expression.get("watchlisted"), Expression.literal(true)),
                            Expression.literal("#f3d054"), Expression.literal("rgba(0,0,0,0)"))),
                        circleStrokeWidth(Expression.switchCase(Expression.eq(Expression.get("watchlisted"), Expression.literal(true)),
                            Expression.literal(2.5f), Expression.literal(0f)))))
                    val label = SymbolLayer("${spec.sourcePrefix}-point-label-layer", sourceId).withProperties(
                        textField(Expression.get("callsign")), textSize(12.5f), textColor("#f4f0e7"),
                        textFont(arrayOf("Noto Sans Regular")),
                        textHaloColor("#07151b"), textHaloWidth(1.6f), textOffset(arrayOf(0f, 1.15f)),
                        textAnchor(Property.TEXT_ANCHOR_TOP), textAllowOverlap(false), textIgnorePlacement(false),
                        textOpacity(opacity),
                    )
                    label.setMinZoom(3.0f)
                    style.addLayer(label)
                }
                HamClockMapRenderKind.LINE -> style.addLayer(LineLayer(layerId, sourceId).withProperties(
                    lineColor(Expression.get("color")), lineWidth(1.7f), lineOpacity(opacity)))
                HamClockMapRenderKind.FILL -> style.addLayer(FillLayer(layerId, sourceId).withProperties(
                    fillColor(Expression.get("color")), fillOpacity(opacity * .5f)))
            }
        }
    }
}

private fun updateHamClockSources(style: Style, snapshot: HamClockMapSnapshot, preference: HamClockMapPreference,
    fingerprints: MutableMap<String, Int>) {
    val visible = preference.layers.filter { it.visible }.map { it.id }.toSet()
    hamClockMapLayerRegistry.filter { it.availability != HamClockMapLayerAvailability.UNAVAILABLE }.forEach { spec ->
        spec.renderKinds.forEach { kind ->
            val sourceId = "${spec.sourcePrefix}-${kind.name.lowercase()}-source"
            val layerId = "${spec.sourcePrefix}-${kind.name.lowercase()}-layer"
            val opacity = preference.layers.firstOrNull { it.id == spec.id }?.opacity ?: spec.defaultOpacity
            style.getLayer(layerId)?.setProperties(when (kind) {
                HamClockMapRenderKind.POINT -> circleOpacity(opacity)
                HamClockMapRenderKind.LINE -> lineOpacity(opacity)
                HamClockMapRenderKind.FILL -> fillOpacity(opacity * .5f)
            })
            if (kind == HamClockMapRenderKind.POINT) {
                style.getLayer("${spec.sourcePrefix}-point-label-layer")?.setProperties(textOpacity(opacity))
            }
            val features = if (spec.id !in visible) emptyList() else when (kind) {
                HamClockMapRenderKind.POINT -> snapshot.points.filter { it.layerId == spec.id }.map { pointFeature(it) }
                HamClockMapRenderKind.LINE -> snapshot.lines.filter { it.layerId == spec.id }.flatMap { lineFeatures(it) }
                HamClockMapRenderKind.FILL -> snapshot.fills.filter { it.layerId == spec.id }.mapNotNull { fillFeature(it) }
            }
            val fingerprint = features.fold(1) { hash, feature -> 31 * hash + feature.toJson().hashCode() }
            if (fingerprints[sourceId] != fingerprint) {
                (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
                fingerprints[sourceId] = fingerprint
            }
        }
    }
}

private fun pointFeature(item: HamClockMapPoint): Feature =
    feature(Point.fromLngLat(item.longitude, item.latitude), item.id, item.layerId, item.title, item.detail, item.color,
        item.selection, item.contextId, item.callsign, item.frequencyHz, item.mode, item.watchlisted)

private fun lineFeatures(item: HamClockMapLine): List<Feature> = item.segments.mapIndexedNotNull { index, segment ->
    val points = segment.map { Point.fromLngLat(it.longitude, it.latitude) }
    if (points.size < 2) null else feature(LineString.fromLngLats(points), "${item.id}-$index", item.layerId,
        item.title, item.detail, item.color, item.selection, item.contextId, item.callsign, item.frequencyHz, item.mode)
}

private fun fillFeature(item: HamClockMapFill): Feature? {
    val points = item.ring.map { Point.fromLngLat(it.longitude, it.latitude) }.toMutableList()
    if (points.size < 3) return null
    if (points.first() != points.last()) points += points.first()
    return feature(Polygon.fromLngLats(listOf(points)), item.id, item.layerId, item.title, item.detail, item.color, HamClockMapSelection.NONE)
}

private fun feature(
    geometry: org.maplibre.geojson.Geometry,
    id: String,
    layerId: String,
    title: String,
    detail: String,
    color: String,
    selection: HamClockMapSelection,
    contextId: String = id,
    callsign: String = "",
    frequencyHz: Long? = null,
    mode: String = "",
    watchlisted: Boolean = false,
): Feature {
    val properties = JsonObject().apply {
        addProperty("title", title)
        addProperty("detail", detail)
        addProperty("color", color)
        addProperty("selection", selection.name)
        addProperty("layerId", layerId)
        addProperty("contextId", contextId)
        addProperty("callsign", callsign)
        frequencyHz?.let { addProperty("frequencyHz", it) }
        addProperty("mode", mode)
        addProperty("watchlisted", watchlisted)
    }
    return Feature.fromGeometry(geometry, properties, id)
}

private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

private fun hamClockStyleJson(basemap: HamClockBasemap): String {
    val family = if (basemap == HamClockBasemap.LIGHT) "light" else "dark"
    val background = if (basemap == HamClockBasemap.LIGHT) "#e8edf0" else "#06151c"
    return """{
      "version":8,
      "name":"ShackCQ HamClock ${family.replaceFirstChar(Char::uppercase)}",
      "sources":{},
      "layers":[
        {"id":"background","type":"background","paint":{"background-color":"$background"}}
      ]
    }""".trimIndent()
}

private const val OPEN_FREE_MAP_LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OPEN_FREE_MAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private fun HamClockMapPoint.reference() = HamClockMapFeatureRef(layerId, contextId, selection, callsign, frequencyHz, mode)

internal fun hamClockSelectionAction(selection: HamClockMapSelection): String = when (selection) {
    HamClockMapSelection.DX_SPOT -> "Open DX spot"
    HamClockMapSelection.PSK_REPORT -> "Open PSK report"
    HamClockMapSelection.RBN_OBSERVATION -> "Open RBN observation"
    HamClockMapSelection.WSPR_OBSERVATION -> "Open WSPR observation"
    HamClockMapSelection.IBP_BEACON -> "Open IBP schedule"
    HamClockMapSelection.PORTABLE -> "Open portable spot"
    HamClockMapSelection.SATELLITE -> "Open satellite"
    HamClockMapSelection.QSO -> "Open logged QSO"
    HamClockMapSelection.TARGET -> "Open target settings"
    HamClockMapSelection.WEATHER -> "Open weather"
    HamClockMapSelection.NONE -> "Close"
}

internal fun hamClockBandColor(band: String): String = when (band.trim().lowercase()) {
    "2190m", "2200m", "630m", "560m", "160m" -> "#ff6b6b"
    "80m" -> "#ff9f68"; "60m", "40m" -> "#f3d054"; "30m" -> "#8fdf62"
    "20m" -> "#43d17c"; "17m" -> "#4ed9b2"; "15m" -> "#42c7d8"; "12m" -> "#69a7f5"
    "10m", "8m" -> "#9d72f2"; "6m", "5m", "4m" -> "#e86ad7"; "2m" -> "#f08ba7"
    "1.25m", "70cm" -> "#c481d8"; "33cm", "23cm" -> "#5b8ff9"; "13cm", "9cm" -> "#43c7d9"
    "6cm", "3cm", "1.25cm", "6mm", "4mm", "2.5mm", "2mm", "1mm", "submm" -> "#35b7a6"
    "sat" -> "#f4c94e"; else -> "#42c7d8"
}
