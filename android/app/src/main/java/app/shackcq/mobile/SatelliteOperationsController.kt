package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.acos

internal data class SatellitePassRow(
    val satellite: SatelliteCatalogueEntry,
    val pass: OrbitalPass,
    val transponder: SatelliteTransponder?,
)

internal data class SatelliteObserverProfile(val id: String, val label: String, val grid: String)

internal data class HamClockSatellitePosition(
    val noradId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeKm: Double,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val generatedAtEpoch: Long,
    val stale: Boolean,
)

internal data class HamClockSatelliteTrack(
    val noradId: Long,
    val name: String,
    val segments: List<List<GeoPoint>>,
    val generatedAtEpoch: Long,
    val stale: Boolean,
)

internal data class HamClockSatelliteFootprint(
    val noradId: Long,
    val name: String,
    val ring: List<GeoPoint>,
    val generatedAtEpoch: Long,
    val stale: Boolean,
)

private data class RefreshedSatelliteProviders(
    val elements: SatelliteProviderData<SatelliteCatalogueEntry>,
    val transponders: SatelliteProviderData<SatelliteTransponder>,
    val status: SatelliteProviderData<AmsatStatusSummary>,
    val timers: SatelliteProviderData<SatelliteTimer>,
)

internal fun homeSatelliteEntries(
    rows: List<SatelliteCatalogueEntry>,
    favourites: Set<Long>,
    selectedNoradId: Long?,
): List<SatelliteCatalogueEntry> = rows.asSequence()
    .filter { it.noradId in favourites || it.noradId == selectedNoradId }
    .ifEmpty { rows.asSequence().take(8) }
    .take(40)
    .toList()

internal fun calculateHomeSatellitePositions(
    entries: List<SatelliteCatalogueEntry>,
    observer: SatelliteObserver,
    epoch: Long,
    stale: Boolean,
    propagate: (SatelliteElements, SatelliteObserver, Long) -> SatelliteNativeResult<OrbitalPoint>,
): List<HamClockSatellitePosition> = entries.mapNotNull { entry ->
    val position = (propagate(entry.elements, observer, epoch) as? SatelliteNativeResult.Success)?.value
        ?: return@mapNotNull null
    HamClockSatellitePosition(entry.noradId, entry.name, position.latitudeDeg, position.longitudeDeg,
        position.altitudeKm, position.azimuthDeg, position.elevationDeg, position.rangeKm, epoch, stale)
}

internal class SatelliteOperationsController(
    context: Context,
    private val database: QsoDatabase,
) {
    private val appContext = context.applicationContext
    private val providers = SatelliteProviderRepository(appContext)
    private val prefs = appContext.getSharedPreferences("satellite_operations_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val homePropagationMutex = Mutex()
    private val providerGeneration = LifecycleGeneration()
    private val calculationGeneration = LifecycleGeneration()
    private val selectionGeneration = LifecycleGeneration()
    private var homeJob: Job? = null
    private var homeGeneration = 0L
    private var homeActive = false
    private var homeObserverGrid = ""
    private var neuralActive = false
    private var neuralObserverGrid = ""
    private var selectedHomeNoradId: Long? = null

    var elements by mutableStateOf(providers.elements()); private set
    var transponders by mutableStateOf(providers.transponders()); private set
    var status by mutableStateOf(providers.amsatStatus()); private set
    var timers by mutableStateOf(providers.timers()); private set
    var passes by mutableStateOf<List<SatellitePassRow>>(emptyList()); private set
    var selectedPass by mutableStateOf<SatellitePassRow?>(null); private set
    var groundTrack by mutableStateOf<List<OrbitalPoint>>(emptyList()); private set
    var skyTrack by mutableStateOf<List<OrbitalPoint>>(emptyList()); private set
    var livePoint by mutableStateOf<OrbitalPoint?>(null); private set
    var hamClockPositions by mutableStateOf<List<HamClockSatellitePosition>>(emptyList()); private set
    var hamClockTracks by mutableStateOf<List<HamClockSatelliteTrack>>(emptyList()); private set
    var hamClockFootprints by mutableStateOf<List<HamClockSatelliteFootprint>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("Offline prediction uses the last-good validated element cache."); private set

    var observerGrid by mutableStateOf(prefs.getString("observer_grid", "JN88TQ").orEmpty()); private set
    var windowHours by mutableStateOf(prefs.getInt("window_hours", 24).coerceIn(1, 72)); private set
    var minimumElevation by mutableStateOf(prefs.getFloat("minimum_elevation", 10f).toDouble().coerceIn(0.0, 90.0)); private set
    var favouritesOnly by mutableStateOf(prefs.getBoolean("favourites_only", true)); private set
    var utc by mutableStateOf(prefs.getBoolean("utc", true)); private set
    var modeFilter by mutableStateOf(prefs.getString("mode_filter", "").orEmpty()); private set
    var favourites by mutableStateOf(prefs.getStringSet("favourites", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()); private set

    val nextFavouritePass: SatellitePassRow? get() = passes.firstOrNull { it.satellite.noradId in favourites } ?: passes.firstOrNull()

    init { refresh(false) }

    fun refresh(force: Boolean) {
        if (busy) return
        val generation = providerGeneration.next()
        busy = true
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                val e = providers.refreshElements(force)
                val t = providers.refreshTransponders(force)
                val s = providers.refreshAmsat(force)
                val timers = providers.refreshTimers(force)
                RefreshedSatelliteProviders(e, t, s, timers)
            }
            if (!providerGeneration.isCurrent(generation)) return@launch
            elements = refreshed.elements
            transponders = refreshed.transponders
            status = refreshed.status
            this@SatelliteOperationsController.timers = refreshed.timers
            busy = false
            message = "Providers refreshed; predictions remain local in the pinned SGP4 engine."
            predict()
            restartHomeTicker()
        }
    }

    fun predict() {
        val point = maidenheadCenter(observerGrid)
        if (point == null) { message = "Enter a valid Maidenhead observer grid."; passes = emptyList(); return }
        if (busy) return
        busy = true
        val generation = calculationGeneration.next()
        scope.launch {
            val now = Instant.now().epochSecond
            val end = now + windowHours * 3600L
            val observer = SatelliteObserver(point.latitude, point.longitude)
            val source = elements.rows.asSequence()
                .filter { !favouritesOnly || it.noradId in favourites }
                .filter { entry -> modeFilter.isBlank() || transponders.rows.rowsFor(entry.noradId).any { it.mode.contains(modeFilter, true) || it.uplinkMode.contains(modeFilter, true) } }
                .take(250).toList()
            val calculated = withContext(Dispatchers.Default) {
                source.flatMap { entry ->
                    when (val result = NativeSatellite.passes(entry.elements, observer, now, end, minimumPeakDeg = minimumElevation, maximumPasses = 12)) {
                        is SatelliteNativeResult.Success -> result.value.map { SatellitePassRow(entry, it, preferredTransponder(entry.noradId)) }
                        is SatelliteNativeResult.Error -> emptyList()
                    }
                }.sortedBy { it.pass.aos }.take(250)
            }
            if (!calculationGeneration.isCurrent(generation)) {
                busy = false
                return@launch
            }
            passes = calculated
            busy = false
            message = when {
                elements.rows.isEmpty() -> "No valid orbital elements. Refresh CelesTrak or add a manual TLE."
                source.isEmpty() && favouritesOnly -> "No favourites selected. Show all satellites or add favourites in Catalogue."
                calculated.isEmpty() -> "No passes meet the selected interval/elevation/filter."
                else -> "${calculated.size} local predictions · no network propagation"
            }
        }
    }

    fun select(row: SatellitePassRow) {
        val generation = selectionGeneration.next()
        selectedPass = row
        selectedHomeNoradId = row.satellite.noradId
        restartHomeTicker()
        val point = maidenheadCenter(observerGrid) ?: return
        scope.launch {
            val observer = SatelliteObserver(point.latitude, point.longitude)
            val groundStart = row.pass.aos - 30 * 60
            val groundEnd = row.pass.los + 30 * 60
            val tracks = withContext(Dispatchers.Default) {
                val ground = NativeSatellite.samples(row.satellite.elements, observer, groundStart, groundEnd, 60, 500, false)
                val skyStep = ((row.pass.durationSeconds / 180).coerceIn(2, 30)).toInt()
                val sky = NativeSatellite.samples(row.satellite.elements, observer, row.pass.aos, row.pass.los, skyStep, 300, true)
                (ground as? SatelliteNativeResult.Success)?.value.orEmpty() to (sky as? SatelliteNativeResult.Success)?.value.orEmpty()
            }
            if (!selectionGeneration.isCurrent(generation) || selectedPass !== row) return@launch
            groundTrack = tracks.first; skyTrack = tracks.second
            updateLivePoint()
        }
    }

    fun selectNorad(noradId: Long) {
        val generation = selectionGeneration.next()
        selectedHomeNoradId = noradId
        restartHomeTicker()
        passes.firstOrNull { it.satellite.noradId == noradId }?.let { select(it); return }
        val entry = elements.rows.firstOrNull { it.noradId == noradId } ?: return
        val point = maidenheadCenter(observerGrid) ?: return
        scope.launch {
            val now = Instant.now().epochSecond
            val row = withContext(Dispatchers.Default) {
                val predicted = NativeSatellite.passes(entry.elements, SatelliteObserver(point.latitude, point.longitude),
                    now, now + windowHours * 3600L, minimumPeakDeg = minimumElevation, maximumPasses = 1)
                (predicted as? SatelliteNativeResult.Success)?.value?.firstOrNull()?.let {
                    SatellitePassRow(entry, it, preferredTransponder(entry.noradId))
                }
            }
            if (selectionGeneration.isCurrent(generation)) row?.let(::select)
        }
    }

    fun updateLivePoint(epoch: Long = Instant.now().epochSecond) {
        val selected = selectedPass ?: return
        val generation = selectionGeneration.current()
        val point = maidenheadCenter(observerGrid) ?: return
        scope.launch {
            val calculated = withContext(Dispatchers.Default) {
                (NativeSatellite.propagate(selected.satellite.elements, SatelliteObserver(point.latitude, point.longitude), epoch) as? SatelliteNativeResult.Success)?.value
            }
            if (selectionGeneration.isCurrent(generation) && selectedPass === selected) livePoint = calculated
        }
    }

    fun updatePreferences(grid: String = observerGrid, hours: Int = windowHours, elevation: Double = minimumElevation,
        favouritesOnly: Boolean = this.favouritesOnly, utc: Boolean = this.utc, mode: String = modeFilter) {
        observerGrid = grid.trim().uppercase(Locale.US); windowHours = hours.coerceIn(1,72); minimumElevation = elevation.coerceIn(0.0,90.0)
        this.favouritesOnly = favouritesOnly; this.utc = utc; modeFilter = mode.trim().uppercase(Locale.US)
        calculationGeneration.retire()
        selectionGeneration.retire()
        prefs.edit().putString("observer_grid", observerGrid).putInt("window_hours", windowHours).putFloat("minimum_elevation", minimumElevation.toFloat())
            .putBoolean("favourites_only", favouritesOnly).putBoolean("utc", utc).putString("mode_filter", modeFilter).apply()
    }

    fun toggleFavourite(noradId: Long) {
        favourites = if (noradId in favourites) favourites - noradId else favourites + noradId
        prefs.edit().putStringSet("favourites", favourites.map(Long::toString).toSet()).apply()
        restartHomeTicker()
    }

    fun setHomeActive(active: Boolean, observerGrid: String) {
        val normalizedGrid = observerGrid.trim().uppercase(Locale.US)
        if (homeActive == active && homeObserverGrid == normalizedGrid && (!active || homeJob?.isActive == true)) return
        homeActive = active
        homeObserverGrid = normalizedGrid
        restartHomeTicker()
    }

    fun setNeuralActive(active: Boolean, observerGrid: String) {
        val normalizedGrid = observerGrid.trim().uppercase(Locale.US)
        if (neuralActive == active && neuralObserverGrid == normalizedGrid &&
            (!active || homeJob?.isActive == true)) return
        neuralActive = active
        neuralObserverGrid = normalizedGrid
        restartHomeTicker()
    }

    private fun restartHomeTicker() {
        homeJob?.cancel()
        homeJob = null
        val generation = ++homeGeneration
        if (!homeActive && !neuralActive) return
        val activeGrid = if (homeActive) homeObserverGrid else neuralObserverGrid
        val point = maidenheadCenter(activeGrid) ?: run {
            hamClockPositions = emptyList()
            hamClockTracks = emptyList()
            hamClockFootprints = emptyList()
            return
        }
        homeJob = scope.launch {
            while (isActive && generation == homeGeneration && (homeActive || neuralActive)) {
                val epoch = Instant.now().epochSecond
                val entries = homeSatelliteEntries(elements.rows, favourites, selectedHomeNoradId)
                val stale = elements.metadata.state != SatelliteCacheState.CURRENT
                val geometry = withContext(Dispatchers.Default) {
                    homePropagationMutex.withLock {
                        val observer = SatelliteObserver(point.latitude, point.longitude)
                        val positions = calculateHomeSatellitePositions(entries, observer,
                            epoch, stale) { satelliteElements, observer, atEpoch ->
                            NativeSatellite.propagate(satelliteElements, observer, atEpoch)
                        }
                        val tracks = entries.take(4).mapNotNull { entry ->
                            val samples = (NativeSatellite.samples(entry.elements, observer, epoch - 45 * 60,
                                epoch + 90 * 60, 300, 40, false) as? SatelliteNativeResult.Success)?.value.orEmpty()
                            val segments = splitSatelliteTrack(samples.map { GeoPoint(it.latitudeDeg, it.longitudeDeg) })
                            segments.takeIf(List<List<GeoPoint>>::isNotEmpty)?.let {
                                HamClockSatelliteTrack(entry.noradId, entry.name, it, epoch, stale)
                            }
                        }
                        val footprints = positions.take(4).map {
                            HamClockSatelliteFootprint(it.noradId, it.name,
                                satelliteFootprint(it.latitude, it.longitude, it.altitudeKm), epoch, stale)
                        }
                        Triple(positions, tracks, footprints)
                    }
                }
                if (generation == homeGeneration && (homeActive || neuralActive)) {
                    hamClockPositions = geometry.first
                    hamClockTracks = geometry.second
                    hamClockFootprints = geometry.third
                }
                delay(45_000)
            }
        }
    }

    fun observerProfiles(currentGrid: String, activationGrid: String?): List<SatelliteObserverProfile> = buildList {
        currentGrid.takeIf(String::isNotBlank)?.let { add(SatelliteObserverProfile("CURRENT", "Current station · $it", it)) }
        database.satelliteObserverProfiles().forEach { (id, grid) -> add(SatelliteObserverProfile("PROFILE:$id", "Station $id · $grid", grid)) }
        activationGrid?.takeIf(String::isNotBlank)?.let { add(SatelliteObserverProfile("ACTIVATION", "Activation plan · $it", it)) }
    }.distinctBy { it.id + it.grid }

    fun prepareDraft(row: SatellitePassRow): String = satelliteFastEntryDraft(row, observerGrid)
    fun normalLoggerDraft(row: SatellitePassRow): PortableLogDraft = PortableLogDraft(
        token = System.nanoTime(), callsign = "", frequencyHz = row.transponder?.uplinkLowHz ?: 0,
        mode = satelliteQsoMode(row.transponder?.mode.orEmpty()).orEmpty(),
        comment = listOf("${row.satellite.name} pass · AOS ${row.pass.aos} · TCA ${row.pass.tca} · LOS ${row.pass.los}",
            "TX frequency unknown · review required".takeIf { row.transponder?.uplinkLowHz == null },
            "RX frequency unknown · review required".takeIf { row.transponder?.downlinkLowHz == null }).filterNotNull().joinToString(" · "),
        propagationMode = "SAT", satelliteName = row.satellite.name, satelliteMode = row.transponder?.mode.orEmpty(),
        frequencyRxHz = row.transponder?.downlinkLowHz ?: 0, observerGrid = observerGrid,
    )

    fun saveManualElements(id: Long, name: String, one: String, two: String): Boolean {
        val now = Instant.now().epochSecond
        val candidate = SatelliteElements("TLE", name.trim(), one.trim(), two.trim(), now, "MANUAL")
        val inspection = (NativeSatellite.inspect(candidate) as? SatelliteNativeResult.Success)?.value
        if (inspection == null || inspection.noradId != id) {
            message = "Manual TLE rejected by the pinned parser; the previous override is unchanged."
            return false
        }
        val entry = SatelliteCatalogueEntry(id, name.trim().ifBlank { id.toString() }, candidate, inspection.elementEpoch, true)
        return providers.saveManualElements(entry).also {
            elements = providers.elements()
            restartHomeTicker()
            if (it) message = if (now - inspection.elementEpoch > 14L * 24 * 60 * 60)
                "Manual TLE saved as MANUAL · STALE · prediction disabled until updated"
            else "Manual TLE saved and labelled MANUAL"
        }
    }
    fun removeManualElements(id: Long) { providers.removeManualElements(id); elements=providers.elements(); restartHomeTicker(); message="Manual element override removed" }
    fun saveManualTransponder(row: SatelliteTransponder) { providers.saveManualTransponder(row); transponders=providers.transponders(); message="Local transponder override saved" }
    fun removeManualTransponder(id: String) { providers.removeManualTransponder(id); transponders=providers.transponders() }
    fun transpondersFor(noradId: Long) = transponders.rows.rowsFor(noradId)
    private fun preferredTransponder(noradId: Long) = transponders.rows.rowsFor(noradId).firstOrNull { it.downlinkLowHz != null && it.alive && it.providerStatus.equals("active",true) }
        ?: transponders.rows.rowsFor(noradId).firstOrNull { it.downlinkLowHz != null }
    private fun List<SatelliteTransponder>.rowsFor(id: Long) = filter { it.noradId == id }
    fun close() {
        providerGeneration.close()
        calculationGeneration.close()
        selectionGeneration.close()
        homeGeneration++
        homeJob?.cancel()
        scope.cancel()
    }
}

internal fun splitSatelliteTrack(points: List<GeoPoint>): List<List<GeoPoint>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf(mutableListOf(points.first()))
    points.drop(1).forEach { point ->
        val current = segments.last()
        if (kotlin.math.abs(point.longitude - current.last().longitude) > 180.0) segments += mutableListOf(point)
        else current += point
    }
    return segments.filter { it.size >= 2 }
}

internal fun satelliteFootprint(latitude: Double, longitude: Double, altitudeKm: Double): List<GeoPoint> {
    val angularRadius = acos((6371.0 / (6371.0 + altitudeKm.coerceAtLeast(0.0))).coerceIn(-1.0, 1.0))
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    return (0..48).map { index ->
        val bearing = 2.0 * Math.PI * index / 48.0
        val lat2 = asin(sin(lat1) * cos(angularRadius) + cos(lat1) * sin(angularRadius) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angularRadius) * cos(lat1),
            cos(angularRadius) - sin(lat1) * sin(lat2))
        GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }
}

internal fun satelliteQsoMode(label: String): String? = label.uppercase(Locale.US).let {
    when {
        "FM" in it -> "FM"
        "CW" in it -> "CW"
        "SSB" in it || "USB" in it || "LSB" in it -> "SSB"
        else -> null
    }
}

internal fun satelliteFastEntryDraft(row: SatellitePassRow, observerGrid: String = ""): String {
    val transponder = row.transponder
    val downlink = transponder?.downlinkLowHz
    val uplink = transponder?.uplinkLowHz
    val uplinkBand = uplink?.let(::bandForFrequency).orEmpty()
    val downlinkBand = downlink?.let(::bandForFrequency).orEmpty()
    val qsoMode = satelliteQsoMode(transponder?.mode.orEmpty())
    return buildString {
        append("<PROP_MODE:SAT>\n<SAT_NAME:${row.satellite.name}>\n")
        observerGrid.takeIf(String::isNotBlank)?.let { append("<MY_GRIDSQUARE:${it.uppercase(Locale.US)}>\n") }
        transponder?.mode?.takeIf(String::isNotBlank)?.let { append("<SAT_MODE:$it>\n") }
        qsoMode?.let { append("<MODE:$it>\n") }
        uplinkBand.takeIf(String::isNotBlank)?.let { append("<BAND:$it>\n") }
        uplink?.let { append("<FREQ:${"%.6f".format(Locale.US, it / 1_000_000.0)}>\n") }
        downlinkBand.takeIf(String::isNotBlank)?.let { append("<BAND_RX:$it>\n") }
        downlink?.let { append("<FREQ_RX:${"%.6f".format(Locale.US, it / 1_000_000.0)}>\n") }
        if (uplink != null && qsoMode != null) append("${"%.6f".format(Locale.US, uplink / 1_000_000.0)} $qsoMode\n")
        downlink?.let { append("# RX PREVIEW ${"%.6f".format(Locale.US, it / 1_000_000.0)} ${qsoMode.orEmpty()} · explicit receive tune only\n") }
        if (uplink == null) append("# TX frequency unknown · operator review required before save\n")
        if (downlink == null) append("# RX frequency unknown · operator review required before save\n")
        append("# Review before save · AOS ${row.pass.aos} · TCA ${row.pass.tca} · LOS ${row.pass.los}\n")
    }
}
