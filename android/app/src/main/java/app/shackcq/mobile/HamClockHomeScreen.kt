package app.shackcq.mobile

/*
THESIS: A native ham-radio operations clock: one glance from station state to workable RF activity.
OWN-WORLD: ShackCQ Flightline instrumentation, with OpenHamClock's pinned dashboard density and map-first hierarchy.
STORY: Identify the station and time, read propagation, inspect live paths, then act through the existing DX or Portable workspaces.
FIRST VIEWPORT: UTC/local clocks, CAT truth, solar indices, world activity, live DX, PSK reception, portable activity, and next passes.
FORM: Wide screens use a fixed three-column console; compact screens preserve the same priority in a vertical operating stack.
*/

import android.content.Context
import app.shackcq.mobile.hamclock.*
import app.shackcq.mobile.hamclock.finishline.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File
import kotlin.math.roundToInt

private val HcBg = Color(0xFF081015)
private val HcPanel = Color(0xFF111C22)
private val HcRaised = Color(0xFF182831)
private val HcLine = Color(0xFF32434C)
private val HcInk = Color(0xFFEAF0ED)
private val HcMuted = Color(0xFF91A1A9)
private val HcAmber = Color(0xFFF0AD35)
private val HcGreen = Color(0xFF43D17C)
private val HcCyan = Color(0xFF42C7D8)
private val HcRed = Color(0xFFE65B54)
private val HcYellow = Color(0xFFF3D054)
private val HcDate = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.US)
private val HcLabelSize = 16.sp
private val HcMetaSize = 16.sp
private val HcRowSize = 18.sp

internal fun filterClusterPresentation(spots: List<AndroidDXSpot>, preference: HamClockClusterPreference,
    nowEpoch: Long): List<AndroidDXSpot> {
    if (!preference.enabled) return emptyList()
    val call = preference.filter.callQuery.trim().uppercase(Locale.US)
    return spots.asSequence().filter { spot ->
        nowEpoch - spot.receivedEpoch <= preference.windowMinutes * 60L &&
            (preference.filter.bands.isEmpty() || spot.band.uppercase(Locale.US) in preference.filter.bands) &&
            (preference.filter.modes.isEmpty() || spot.mode.uppercase(Locale.US) in preference.filter.modes) &&
            (preference.filter.continents.isEmpty() || spot.continent.uppercase(Locale.US) in preference.filter.continents) &&
            (call.isBlank() || spot.callsign.contains(call, true))
    }.sortedByDescending(AndroidDXSpot::receivedEpoch).take(preference.maximumSpots).toList()
}

internal fun clusterMapState(connection: ClusterConnectionTruth, visibleCount: Int, staleAfterSeconds: Int,
    nowEpoch: Long): HamClockMapSourceState = when (connection.state) {
    ClusterConnectionState.CONNECTED -> when {
        visibleCount == 0 -> HamClockMapSourceState.EMPTY
        connection.latestSpotEpoch > 0 && nowEpoch - connection.latestSpotEpoch > staleAfterSeconds -> HamClockMapSourceState.STALE
        else -> HamClockMapSourceState.CURRENT
    }
    ClusterConnectionState.DISABLED -> HamClockMapSourceState.UNAVAILABLE
    ClusterConnectionState.DISCONNECTED, ClusterConnectionState.CONNECTING, ClusterConnectionState.RETRYING -> HamClockMapSourceState.STALE
    ClusterConnectionState.ERROR -> HamClockMapSourceState.ERROR
}

@Composable
internal fun HamClockHomeScreen(
    radio: RadioState,
    app: AppController,
    features: FeatureController,
    neuralDx: NeuralDxController,
    portable: PortableController,
    database: QsoDatabase,
    wavelog: WavelogController,
    cty: CtyController,
    callbook: CallbookController,
    publicProviders: HamClockPublicProviders,
    settingsCoordinator: HamClockSettingsCoordinator,
    operations: OperationsController,
    bandHealthSnapshot: HamClockBandHealthSnapshot,
    send: (String) -> Unit,
    openDx: () -> Unit,
    openPortable: () -> Unit,
    openProgress: () -> Unit,
    openOperations: () -> Unit,
    openLogbook: () -> Unit,
    openRadio: () -> Unit,
    openDigi: () -> Unit,
    openGroupsIo: () -> Unit,
    homeForeground: Boolean,
    operatingContext: OperatingContextSnapshot,
    requestReceiveTune: (Long, String?, String, String) -> Unit,
    openExactQso: (String) -> Unit,
) {
    val context = LocalContext.current
    val stationId = operatingContext.stationProfileId.value.takeIf { wavelog.logMode == LogMode.WAVELOG }
    val stationGrid = operatingContext.stationGrid.value
    val stationCall = operatingContext.stationCallsign.value
    val hamClockPrefs = remember(context) { context.getSharedPreferences("shackcq-hamclock-layout", Context.MODE_PRIVATE) }
    val settingsDocument = settingsCoordinator.document
    val finishLineCache = remember(context) { File(context.cacheDir, "hamclock-finishline").apply(File::mkdirs) }
    val spaceWeatherRepository = remember(context) { HamClockSpaceWeatherRepository(finishLineCache) }
    val solarImageRepository = remember(context) { HamClockSolarImageRepository(finishLineCache) }
    val cachedFinishLine = remember { spaceWeatherRepository.cached() }
    var finishLineWeather by remember { mutableStateOf(cachedFinishLine.first) }
    var finishLineAurora by remember { mutableStateOf(cachedFinishLine.second) }
    var solarImage by remember { mutableStateOf(solarImageRepository.cached(HamClockSolarChannel.AIA_193)) }
    var solarImageRequest by remember { mutableStateOf(HamClockSolarChannel.AIA_193 to false) }
    var showShackDisplay by rememberSaveable { mutableStateOf(false) }
    var selectedContestId by rememberSaveable { mutableStateOf("") }
    var contestQsos by remember { mutableStateOf<List<HamClockContestQso>>(emptyList()) }
    var nativeStatus by remember { mutableStateOf<HamClockNativeStatus?>(null) }
    var contestFeed by remember { mutableStateOf<HamClockFeed<List<HamClockContest>>?>(null) }
    var dxpeditionFeed by remember { mutableStateOf<HamClockFeed<List<HamClockDxpedition>>?>(null) }
    var celestialFeed by remember { mutableStateOf<HamClockFeed<HamClockSolarCelestialSnapshot>?>(null) }
    var mapInstant by remember { mutableStateOf(Instant.now()) }
    var configureDashboard by rememberSaveable { mutableStateOf(false) }
    var configureTarget by rememberSaveable { mutableStateOf(false) }
    fun decodeFilter(key: String) = hamClockPrefs.getString(key, "").orEmpty().split(',').map(String::trim).filter(String::isNotBlank).toSet()
    var spotFilters by remember { mutableStateOf(SpotFilters(decodeFilter("cluster_bands"), decodeFilter("cluster_modes"),
        decodeFilter("cluster_cs"), decodeFilter("cluster_ds"))) }
    var activeSpotFilter by remember { mutableStateOf<SpotFilterDimension?>(null) }
    var spotStatuses by remember { mutableStateOf<Map<String, SpotLogStatus>>(emptyMap()) }
    var recentQsos by remember { mutableStateOf<List<HamClockRecentQso>>(emptyList()) }
    var recentQsoRevision by remember { mutableStateOf(0L) }
    val compactScreen = LocalConfiguration.current.screenWidthDp < 900
    fun saveDashboardProfile(name: String, replaceProfileId: String? = null) {
        settingsCoordinator.saveProfile(name, replaceProfileId)
    }
    fun applyDashboardProfile(id: String) {
        settingsCoordinator.applyProfile(id)
    }
    fun updateSpotFilters(value: SpotFilters) {
        spotFilters = value
        hamClockPrefs.edit().putString("cluster_bands", value.bands.joinToString(","))
            .putString("cluster_modes", value.modes.joinToString(","))
            .putString("cluster_cs", value.callStatuses.joinToString(","))
            .putString("cluster_ds", value.dxccStatuses.joinToString(",")).apply()
    }

    LaunchedEffect(homeForeground) {
        if (!homeForeground) return@LaunchedEffect
        while (true) { mapInstant = Instant.now(); delay(10_000) }
    }
    LaunchedEffect(finishLineWeather, finishLineAurora, celestialFeed?.value?.xray?.currentClass) {
        neuralDx.outlook.updateEnvironment(
            celestialFeed?.value?.xray?.currentClass.orEmpty(),
            finishLineWeather.solarWindSpeedKmS,
            finishLineWeather.imfBzNt,
            finishLineAurora.cells.any { it.probability >= 50 },
        )
    }
    LaunchedEffect(settingsDocument.settings.outlook.defaultWindow, settingsDocument.settings.outlook.visibleBands,
        settingsDocument.settings.outlook.worldLayerEnabled, settingsDocument.settings.outlook.worldOpacity) {
        val window = when (settingsDocument.settings.outlook.defaultWindow) {
            HamClockOutlookWindow.MINUTES_30 -> OutlookWindow.MINUTES_30
            HamClockOutlookWindow.MINUTES_60 -> OutlookWindow.MINUTES_60
            HamClockOutlookWindow.MINUTES_120 -> OutlookWindow.MINUTES_120
        }
        val band = neuralDx.outlook.selectedBand.takeIf { it in settingsDocument.settings.outlook.visibleBands }
            ?: settingsDocument.settings.outlook.visibleBands.firstOrNull { it in NEURAL_OUTLOOK_BANDS } ?: "20m"
        neuralDx.outlook.select(window, band)
        val layer = settingsDocument.settings.map.layers.firstOrNull { it.id == HamClockMapLayerId.NEURAL_OUTLOOK }
        if (layer != null && (layer.visible != settingsDocument.settings.outlook.worldLayerEnabled ||
                layer.opacity != settingsDocument.settings.outlook.worldOpacity)) {
            settingsCoordinator.setMapLayer(layer.copy(visible = settingsDocument.settings.outlook.worldLayerEnabled,
                opacity = settingsDocument.settings.outlook.worldOpacity))
        }
    }
    DisposableEffect(operations.satellites, stationGrid, homeForeground) {
        operations.satellites.setHomeActive(homeForeground, stationGrid)
        onDispose { operations.satellites.setHomeActive(false, stationGrid) }
    }
    LaunchedEffect(stationGrid, stationCall, stationId, settingsDocument.settings.pskReporter, homeForeground) {
        if (!homeForeground) return@LaunchedEffect
        while (true) {
            val epoch = Instant.now().epochSecond
            portable.markForegroundAge(epoch)
            if (neuralDx.lastRefreshEpoch == 0L || epoch - neuralDx.lastRefreshEpoch > 15 * 60) {
                neuralDx.refresh(stationCall, stationGrid, stationId,
                    filterClusterPresentation(features.liveSpots, settingsDocument.settings.cluster, epoch),
                    refreshScope = NeuralDxRefreshScope.HOME)
            }
            if (!features.solar.valid || epoch - features.solar.observedEpoch > 60 * 60) features.refreshSolar()
            val potaAge = portable.providerStatus(PortableProgram.POTA).fetchedAt
            val wwffAge = portable.providerStatus(PortableProgram.WWFF).fetchedAt
            if (potaAge == 0L || wwffAge == 0L || epoch - minOf(potaAge, wwffAge) > 5 * 60) portable.refreshAll()
            delay(60_000)
        }
    }
    LaunchedEffect(portable.pota.spots.size, portable.wwffSpots.size, portable.sotaSpots.size,
        radio.frequencyHz, stationGrid, portable.lastQsoRevision) {
        portable.refreshOpportunities(Instant.now().epochSecond, radio.frequencyHz, stationGrid)
    }

    val mapSpots = remember(features.liveSpots, neuralDx.enrichedSpots) {
        mergeEnrichedSpots(features.liveSpots, neuralDx.enrichedSpots)
    }
    LaunchedEffect(mapSpots, stationId, cty.dataRevision) {
        val identities = mapSpots.map { spot -> val entity = cty.lookup(spot.callsign); SpotLogIdentity(
            spot.id, spot.callsign, entity?.dxcc.orEmpty(), entity?.country.orEmpty().ifBlank { spot.country }, spot.band, spot.mode) }
        var observedRevision = Long.MIN_VALUE
        while (true) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                spotStatuses = withContext(Dispatchers.IO) { database.spotStatuses(identities, stationId) }
                observedRevision = revision
            }
            delay(2_000)
        }
    }
    val visibleMapSpots = remember(mapSpots, spotStatuses, spotFilters, settingsDocument.settings.cluster, mapInstant) {
        filterClusterPresentation(mapSpots, settingsDocument.settings.cluster, mapInstant.epochSecond)
            .filter { spotMatchesFilters(it, spotStatuses[it.id], spotFilters) }
    }
    LaunchedEffect(visibleMapSpots, stationId, cty.dataRevision) {
        neuralDx.ingest(visibleMapSpots, stationId, cty, stationCall)
    }
    val propagationRepository = remember(context) { HamClockPropagationRepository(context) }
    var pathPrediction by remember { mutableStateOf(HamClockPropagationSnapshot()) }
    val selectedDxTarget = preferredDxTarget(visibleMapSpots)
    val resolvedDxTarget = resolveHamClockTarget(settingsDocument.settings.dxTarget, selectedDxTarget)
    val stationPoint = maidenheadCenter(stationGrid)
    val targetPoint = resolvedDxTarget?.point
    LaunchedEffect(selectedDxTarget?.id, settingsDocument.settings.dxTarget?.locked) {
        val automatic = selectedDxTarget ?: return@LaunchedEffect
        if (settingsDocument.settings.dxTarget?.locked == true) return@LaunchedEffect
        val current = settingsDocument.settings.dxTarget
        if (current?.source != HamClockDxTargetSource.AUTOMATIC || current.callsign != automatic.callsign ||
            current.latitude != automatic.latitude || current.longitude != automatic.longitude) {
            settingsCoordinator.updateSettings { settings ->
                settings.copy(dxTarget = HamClockDxTarget(
                    callsign = automatic.callsign,
                    latitude = automatic.latitude,
                    longitude = automatic.longitude,
                    source = HamClockDxTargetSource.AUTOMATIC,
                ))
            }
        }
    }
    LaunchedEffect(stationPoint, homeForeground) {
        if (!homeForeground) return@LaunchedEffect
        while (true) {
            val latitude = stationPoint?.latitude ?: 0.0
            val longitude = stationPoint?.longitude ?: 0.0
            val refreshed = withContext(Dispatchers.IO) {
                Triple(publicProviders.contests.refresh(), publicProviders.dxpeditions.refresh(),
                    publicProviders.solarCelestial.refresh(latitude, longitude))
            }
            contestFeed = refreshed.first
            dxpeditionFeed = refreshed.second
            neuralDx.updateDxNewsCalendar(refreshed.second)
            celestialFeed = refreshed.third
            delay(5 * 60_000L)
        }
    }
    LaunchedEffect(homeForeground) {
        if (!homeForeground) return@LaunchedEffect
        while (true) {
            val refreshed = withContext(Dispatchers.IO) { spaceWeatherRepository.refresh() }
            finishLineWeather = refreshed.first
            finishLineAurora = refreshed.second
            delay(15 * 60_000L)
        }
    }
    LaunchedEffect(solarImageRequest, settingsDocument.settings.display.lowDataMode) {
        val request = solarImageRequest
        solarImage = withContext(Dispatchers.IO) {
            solarImageRepository.load(request.first, settingsDocument.settings.display.lowDataMode, request.second)
        }
        if (request.second) solarImageRequest = request.first to false
    }
    LaunchedEffect(stationPoint, targetPoint, radio.mode, radio.powerW) {
        if (stationPoint == null || targetPoint == null) {
            pathPrediction = HamClockPropagationSnapshot(error = "Station or target geometry unavailable")
        } else while (true) {
            pathPrediction = propagationRepository.prediction(stationPoint, targetPoint, radio.mode.ifBlank { "SSB" },
                radio.powerW.takeIf { it > 0 } ?: 100)
            delay(10 * 60_000L)
        }
    }
    LaunchedEffect(stationPoint, targetPoint, settingsDocument.settings.propagation, features.sunspotNumber) {
        val de = stationPoint ?: run { nativeStatus = null; return@LaunchedEffect }
        val dx = targetPoint ?: run { nativeStatus = null; return@LaunchedEffect }
        val now = Instant.now().atZone(ZoneOffset.UTC)
        val preference = settingsDocument.settings.propagation
        nativeStatus = withContext(Dispatchers.Default) {
            HamClockNativePropagation.evaluate(HamClockPropagationInput(
                de.latitude, de.longitude, dx.latitude, dx.longitude, now.year, now.monthValue, now.hour,
                features.sunspotNumber?.roundToInt()?.coerceIn(0, 400) ?: 0,
                preference.txPowerWatts.toDouble(), preference.txGainDb, preference.rxGainDb,
                preference.selectedFrequenciesMHz, preference.noiseEnvironment, preference.requiredReliability,
                preference.requiredSnrDb, preference.bandwidthHz.toDouble(), preference.digital, preference.longPath,
            ))
        }
    }
    var previousTransmitting by remember { mutableStateOf(radio.transmitting) }
    LaunchedEffect(radio.transmitting, settingsDocument.settings.idReminder.startOnVerifiedTx) {
        val reminder = settingsDocument.settings.idReminder
        if (radio.transmitting && !previousTransmitting && reminder.enabled && reminder.startOnVerifiedTx && !reminder.running) {
            settingsCoordinator.updateSettings { it.copy(idReminder = it.idReminder.copy(
                running = true, paused = false, lastResetEpochSeconds = Instant.now().epochSecond)) }
        }
        previousTransmitting = radio.transmitting
    }
    LaunchedEffect(database) {
        var observedRevision = Long.MIN_VALUE
        while (true) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                recentQsos = withContext(Dispatchers.IO) { database.recentHamClockProjection(120) }
                recentQsoRevision = revision
                observedRevision = revision
            }
            delay(5_000)
        }
    }
    val contests = contestFeed?.value.orEmpty()
    val selectedContest = contests.firstOrNull { it.id == selectedContestId }
        ?: contests.firstOrNull { it.status == HamClockContestStatus.ACTIVE }
        ?: contests.firstOrNull()
    LaunchedEffect(contests, selectedContest?.id) {
        if (selectedContest != null && selectedContestId != selectedContest.id) selectedContestId = selectedContest.id
    }
    LaunchedEffect(database, selectedContest, stationId) {
        val contest = selectedContest ?: run { contestQsos = emptyList(); return@LaunchedEffect }
        var observedRevision = Long.MIN_VALUE
        while (true) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                contestQsos = withContext(Dispatchers.IO) { database.contestHamClockProjection(HamClockContestQsoFilter(
                    contestId = contest.id, startEpoch = contest.startEpoch, endEpoch = contest.endEpoch,
                    stationProfileId = stationId, maximumRows = 200,
                )) }
                observedRevision = revision
            }
            delay(5_000)
        }
    }
    val portableMinute = mapInstant.epochSecond / 60
    val portableMapSpots = remember(portable.rankedOpportunities, portableMinute) {
        portable.rankedOpportunities.asSequence().map { it.spot }
            .filter { it.activeAt(portableMinute * 60) && it.latitude != null && it.longitude != null }
            .take(160).toList()
    }
    val portableMapStatuses = PortableProgram.entries.map(portable::providerStatus)
    val rbnMapRows = remember(features.rbnObservations, cty.dataRevision, stationCall, stationPoint, callbook.status) {
        features.rbnObservations.mapNotNull { row ->
            fun ctyPoint(call: String) = cty.lookup(call)?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let { GeoPoint(it.latitude, it.longitude) }
            val resolved = resolveRbnObservationView(row, stationCall, stationPoint,
                { call -> callbook.cachedRecord(call)?.grid }, ::ctyPoint)
            resolved.skimmerPoint?.let { marker -> resolved to marker }
        }
    }
    val ibpSchedule = remember(mapInstant.epochSecond / 10) { hamClockIbpSchedule(mapInstant.epochSecond) }
    val bandHealth = bandHealthSnapshot.rows
    val mapSourceStatus = remember(visibleMapSpots, portableMapSpots, operations.satellites.hamClockPositions,
        recentQsos, recentQsoRevision, neuralDx.mySignal, neuralDx.lightning, mapInstant, stationPoint, resolvedDxTarget,
        portableMapStatuses, features.clusterConnection, operations.satellites.elements.metadata,
        settingsDocument.settings.pskReporter, settingsDocument.settings.rbn, settingsDocument.settings.wspr,
        features.rbnObservations, neuralDx.wsprPersonal, ibpSchedule, finishLineAurora, contestQsos, selectedContest) {
        fun state(rows: Int) = if (rows > 0) HamClockMapSourceState.CURRENT else HamClockMapSourceState.EMPTY
        fun safe(value: String) = value.replace(Regex("https?://\\S+|(?i)(token|key|password)=[^\\s]+"), "[redacted]").take(160)
        val portableStatuses = portableMapStatuses
        val portableState = when {
            portableStatuses.any { it.kind == PortableFeedKind.FAILED } -> HamClockMapSourceState.ERROR
            portableStatuses.any { it.kind == PortableFeedKind.OFFLINE } -> HamClockMapSourceState.OFFLINE_CACHE
            portableStatuses.any { it.kind == PortableFeedKind.STALE } -> HamClockMapSourceState.STALE
            portableStatuses.any { it.kind == PortableFeedKind.CACHED } -> HamClockMapSourceState.CACHED
            else -> state(portableMapSpots.size)
        }
        val satelliteMetadata = operations.satellites.elements.metadata
        val satelliteGeneratedAt = operations.satellites.hamClockPositions.maxOfOrNull(HamClockSatellitePosition::generatedAtEpoch) ?: 0
        val satelliteState = when (satelliteMetadata.state) {
            SatelliteCacheState.CURRENT -> when {
                satelliteGeneratedAt == 0L -> HamClockMapSourceState.EMPTY
                mapInstant.epochSecond - satelliteGeneratedAt > 120 -> HamClockMapSourceState.STALE
                else -> HamClockMapSourceState.CURRENT
            }
            SatelliteCacheState.STALE -> HamClockMapSourceState.STALE
            SatelliteCacheState.OFFLINE_CACHE -> HamClockMapSourceState.OFFLINE_CACHE
            SatelliteCacheState.EMPTY -> HamClockMapSourceState.EMPTY
            SatelliteCacheState.ERROR -> HamClockMapSourceState.ERROR
        }
        val unavailable = hamClockMapLayerRegistry.associate { spec -> spec.id to HamClockMapSourceStatus(
            HamClockMapSourceState.UNAVAILABLE, 0, spec.sourceLabel,
            safe(spec.unavailableReason.ifBlank { "No current source status" })) }
        unavailable + mapOf(
            HamClockMapLayerId.DE_STATION to HamClockMapSourceStatus(
                if (stationPoint != null) HamClockMapSourceState.CURRENT else HamClockMapSourceState.UNAVAILABLE,
                mapInstant.epochSecond, "Configured station", if (stationPoint == null) "Station grid geometry unavailable" else "Station geometry current"),
            HamClockMapLayerId.DX_SPOTS to HamClockMapSourceStatus(
                clusterMapState(features.clusterConnection, visibleMapSpots.size,
                    settingsDocument.settings.cluster.refreshSeconds, mapInstant.epochSecond),
                features.clusterConnection.latestSpotEpoch, "Configured DX cluster", safe(features.clusterStatus)),
            HamClockMapLayerId.DX_PATHS to HamClockMapSourceStatus(
                if (stationPoint == null) HamClockMapSourceState.UNAVAILABLE
                else clusterMapState(features.clusterConnection, visibleMapSpots.size,
                    settingsDocument.settings.cluster.refreshSeconds, mapInstant.epochSecond),
                features.clusterConnection.latestSpotEpoch, "Configured DX cluster · local geodesic geometry",
                if (stationPoint == null) "Station geometry unavailable" else safe(features.clusterStatus)),
            HamClockMapLayerId.SELECTED_TARGET to HamClockMapSourceStatus(
                if (resolvedDxTarget != null) HamClockMapSourceState.CURRENT else HamClockMapSourceState.EMPTY,
                mapInstant.epochSecond, "Manual or ranked DX target"),
            HamClockMapLayerId.PSK_REPORTER to HamClockMapSourceStatus(
                when {
                    neuralDx.mySignal.sourceState == NeuralSignalSourceState.DEGRADED -> HamClockMapSourceState.DEGRADED
                    neuralDx.mySignal.reports.isNotEmpty() -> HamClockMapSourceState.CURRENT
                    neuralDx.mySignal.beingHeardState == HamClockFeedState.UNAVAILABLE &&
                        neuralDx.mySignal.hearingState == HamClockFeedState.UNAVAILABLE -> HamClockMapSourceState.UNAVAILABLE
                    neuralDx.mySignal.error.isNotBlank() -> HamClockMapSourceState.ERROR
                    else -> HamClockMapSourceState.EMPTY
                },
                neuralDx.mySignal.fetchedEpoch, neuralDx.mySignal.source, safe(listOf(
                    if (neuralDx.mySignal.sourceState == NeuralSignalSourceState.DEGRADED) "DEGRADED · retained valid direction" else "",
                    "Being Heard ${neuralDx.mySignal.beingHeardState.name} ${neuralDx.mySignal.beingHeardCount}",
                    "Hearing ${neuralDx.mySignal.hearingState.name} ${neuralDx.mySignal.hearingCount}",
                    "window ${settingsDocument.settings.pskReporter.windowMinutes}m",
                    "cadence ${settingsDocument.settings.pskReporter.refreshSeconds}s",
                     neuralDx.mySignal.error).filter(String::isNotBlank).joinToString(" · "))),
            HamClockMapLayerId.RBN to HamClockMapSourceStatus(
                when (features.rbnSourceSnapshot.state) {
                    HamClockRbnSourceState.DISABLED, HamClockRbnSourceState.DISCONNECTED -> HamClockMapSourceState.UNAVAILABLE
                    HamClockRbnSourceState.CONNECTING -> HamClockMapSourceState.STALE
                    HamClockRbnSourceState.CURRENT -> HamClockMapSourceState.CURRENT
                    HamClockRbnSourceState.EMPTY -> HamClockMapSourceState.EMPTY
                    HamClockRbnSourceState.STALE -> HamClockMapSourceState.STALE
                    HamClockRbnSourceState.ERROR -> HamClockMapSourceState.ERROR
                }, features.rbnSourceSnapshot.latestRbnEpoch,
                "Configured retail DX cluster", "RBN ${features.rbnSourceSnapshot.state} · raw ${features.rbnSourceSnapshot.rawBoundedCount} · filtered ${features.rbnSourceSnapshot.filteredCount}"),
            HamClockMapLayerId.WSPR_EXPANDED to HamClockMapSourceStatus(
                if (!settingsDocument.settings.wspr.personalEnabled) HamClockMapSourceState.UNAVAILABLE
                else when {
                    neuralDx.wsprPersonal.sourceState == HamClockFeedState.DEGRADED -> HamClockMapSourceState.DEGRADED
                    neuralDx.wsprPersonal.reports.isNotEmpty() -> HamClockMapSourceState.CURRENT
                    neuralDx.wsprPersonal.error.isNotBlank() -> HamClockMapSourceState.ERROR
                    neuralDx.wsprPersonal.beingHeardState == HamClockFeedState.UNAVAILABLE &&
                        neuralDx.wsprPersonal.hearingState == HamClockFeedState.UNAVAILABLE -> HamClockMapSourceState.UNAVAILABLE
                    else -> HamClockMapSourceState.EMPTY
                }, neuralDx.wsprPersonal.fetchedEpoch,
                "PSK Reporter · mode WSPR", "Regional WSPR.live ${neuralDx.wsprPersonal.regionalState}"),
            HamClockMapLayerId.IBP to HamClockMapSourceStatus(HamClockMapSourceState.CURRENT,
                mapInstant.epochSecond, "NCDXF/IARU local schedule manifest",
                "${ibpSchedule.manifestVersion} · schedule only; not heard evidence"),
            HamClockMapLayerId.PORTABLE to HamClockMapSourceStatus(portableState,
                portableStatuses.maxOfOrNull(ProviderStatus::fetchedAt) ?: 0, "POTA · SOTA · WWFF",
                safe(PortableProgram.entries.zip(portableStatuses).joinToString(" · ") { (program, status) ->
                    "${program.name} ${status.kind.name.lowercase()} ${status.count}${status.error.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                })),
            HamClockMapLayerId.SATELLITES to HamClockMapSourceStatus(satelliteState,
                satelliteGeneratedAt, "Satellite Operations · pinned NativeSatellite SGP4",
                safe(listOf("element cache ${satelliteMetadata.state.name.lowercase()}",
                    "position age ${if (satelliteGeneratedAt > 0) (mapInstant.epochSecond - satelliteGeneratedAt).coerceAtLeast(0) else "none"}s",
                    satelliteMetadata.lastError).filter(String::isNotBlank).joinToString(" · "))),
            HamClockMapLayerId.LOGGED_QSOS to HamClockMapSourceStatus(state(recentQsos.size), mapInstant.epochSecond,
                "ShackCQ QSO compact projection", "bounded 120 · revision $recentQsoRevision"),
            HamClockMapLayerId.CONTEST_QSOS to HamClockMapSourceStatus(
                if (selectedContest == null) HamClockMapSourceState.EMPTY else state(contestQsos.size), mapInstant.epochSecond,
                "ShackCQ indexed QSO projection", "${selectedContest?.name ?: "No selected contest"} · bounded ${contestQsos.size}/200 · revision $recentQsoRevision"),
            HamClockMapLayerId.AURORA to HamClockMapSourceStatus(
                when (finishLineAurora.truth) {
                    HamClockProviderTruth.CURRENT -> HamClockMapSourceState.CURRENT
                    HamClockProviderTruth.CACHED -> HamClockMapSourceState.CACHED
                    HamClockProviderTruth.STALE -> HamClockMapSourceState.STALE
                    HamClockProviderTruth.ERROR -> HamClockMapSourceState.ERROR
                    HamClockProviderTruth.UNAVAILABLE -> HamClockMapSourceState.UNAVAILABLE
                }, finishLineAurora.forecastAtEpoch, finishLineAurora.source,
                "${finishLineAurora.cells.size} bounded cells · ${finishLineAurora.error}"),
            HamClockMapLayerId.NEURAL_OUTLOOK to HamClockMapSourceStatus(
                if (neuralDx.outlook.snapshot.world.any { it.forecast.label != OutlookLabel.INSUFFICIENT_EVIDENCE })
                    HamClockMapSourceState.CURRENT else HamClockMapSourceState.EMPTY,
                neuralDx.outlook.snapshot.generatedEpoch, neuralDx.outlook.snapshot.modelVersion,
                "${neuralDx.outlook.snapshot.selectedWindow.minutes} min · ${neuralDx.outlook.snapshot.selectedBand} · ${neuralDx.outlook.snapshot.calibration.label}"),
            HamClockMapLayerId.GRAYLINE to HamClockMapSourceStatus(HamClockMapSourceState.CURRENT, mapInstant.epochSecond, "Local UTC astronomy"),
            HamClockMapLayerId.SUN to HamClockMapSourceStatus(HamClockMapSourceState.CURRENT, mapInstant.epochSecond, "Local UTC astronomy"),
            HamClockMapLayerId.GRID to HamClockMapSourceStatus(HamClockMapSourceState.CURRENT, mapInstant.epochSecond, "Local Maidenhead geometry"),
            HamClockMapLayerId.LIGHTNING to HamClockMapSourceStatus(
                when {
                    neuralDx.lightning.error.isNotBlank() -> HamClockMapSourceState.ERROR
                    neuralDx.lightning.connected -> state(neuralDx.lightning.strikes.size)
                    neuralDx.lightning.strikes.isNotEmpty() -> HamClockMapSourceState.CACHED
                    else -> HamClockMapSourceState.UNAVAILABLE
                }, neuralDx.lightning.updatedEpoch, neuralDx.lightning.source,
                safe(listOf(if (neuralDx.lightning.connected) "connected" else "disconnected",
                    neuralDx.lightning.error).filter(String::isNotBlank).joinToString(" · "))),
        )
    }
    val mapSnapshot = remember(visibleMapSpots, neuralDx.mySignal, portableMapSpots, operations.satellites.hamClockPositions,
        recentQsos, neuralDx.lightning, resolvedDxTarget, stationCall, stationGrid, mapInstant,
        settingsDocument.settings.display.unitSystem, mapSourceStatus, rbnMapRows, neuralDx.wsprPersonal,
        ibpSchedule, settingsDocument.settings.rbn.showPaths, settingsDocument.settings.wspr.showPaths,
        settingsDocument.settings.ibp, operations.satellites.hamClockTracks, operations.satellites.hamClockFootprints,
        contestQsos, finishLineAurora, settingsDocument.settings.satellites, neuralDx.outlook.snapshot) {
        val satelliteIds = settingsDocument.settings.satellites.trackedNoradIds
        val satellitePositions = operations.satellites.hamClockPositions.filter {
            satelliteIds.isEmpty() || it.noradId.toInt() in satelliteIds
        }
        val satelliteTracks = if (settingsDocument.settings.satellites.showTracks) operations.satellites.hamClockTracks.filter {
            satelliteIds.isEmpty() || it.noradId.toInt() in satelliteIds
        } else emptyList()
        val satelliteFootprints = if (settingsDocument.settings.satellites.showFootprints) operations.satellites.hamClockFootprints.filter {
            satelliteIds.isEmpty() || it.noradId.toInt() in satelliteIds
        } else emptyList()
        buildHamClockMapSnapshot(stationCall, stationGrid, visibleMapSpots, neuralDx.mySignal,
            portableMapSpots, satellitePositions, recentQsos, neuralDx.lightning,
            resolvedDxTarget, mapInstant, settingsDocument.settings.display.unitSystem, mapSourceStatus,
            rbnMapRows, neuralDx.wsprPersonal, ibpSchedule, settingsDocument.settings.rbn.showPaths,
            settingsDocument.settings.wspr.showPaths, settingsDocument.settings.ibp,
            satelliteTracks = satelliteTracks,
            satelliteFootprints = satelliteFootprints,
            contestQsos = contestQsos, aurora = finishLineAurora, outlook = neuralDx.outlook.snapshot)
    }
    fun updateMapPreference(value: HamClockMapPreference) = settingsCoordinator.updateSettings { it.copy(map = value) }
    fun openMapSelection(reference: HamClockMapFeatureRef) {
        when (reference.selection) {
            HamClockMapSelection.DX_SPOT -> { features.requestSpot(reference.featureId, requireReceiveReview = true); openDx() }
            HamClockMapSelection.PSK_REPORT -> { neuralDx.requestSignalReport(reference.featureId); openDx() }
            HamClockMapSelection.RBN_OBSERVATION,
            HamClockMapSelection.WSPR_OBSERVATION,
            HamClockMapSelection.IBP_BEACON -> {
                neuralDx.requestRfEvidence(reference.featureId)
                neuralDx.requestPage(NeuralDxPage.OBSERVATIONS)
                openDx()
            }
            HamClockMapSelection.TARGET -> configureTarget = true
            HamClockMapSelection.PORTABLE -> { portable.requestSpot(reference.featureId); openPortable() }
            HamClockMapSelection.SATELLITE -> {
                reference.featureId.toLongOrNull()?.let(operations.satellites::selectNorad)
                operations.openSection("SATELLITES"); openOperations()
            }
            HamClockMapSelection.QSO -> openExactQso(reference.featureId)
            HamClockMapSelection.WEATHER -> openDx()
            HamClockMapSelection.NONE -> Unit
        }
    }
    fun openDeepLink(link: HamClockDeepLink) {
        when (link) {
            HamClockDeepLink.DX -> openDx()
            HamClockDeepLink.PORTABLE -> openPortable()
            HamClockDeepLink.OPERATIONS -> openOperations()
            HamClockDeepLink.LOGBOOK -> openLogbook()
            HamClockDeepLink.LOG_INTELLIGENCE -> openProgress()
            HamClockDeepLink.RADIO -> openRadio()
            HamClockDeepLink.DIGI -> openDigi()
            HamClockDeepLink.NONE -> Unit
        }
    }
    val visiblePanels = settingsDocument.settings.panels.filter { panel ->
        panel.visible && !(panel.id == HamClockPanelId.DX_NEWS && compactScreen &&
            !settingsDocument.settings.dxNews.compactVisible) &&
            !(panel.id == HamClockPanelId.NEURAL_OUTLOOK && (!settingsDocument.settings.outlook.enabled ||
                compactScreen && !settingsDocument.settings.outlook.showOnCompact))
    }
    val mapPanel = visiblePanels.firstOrNull { it.id == HamClockPanelId.MAP }
    val leftPanels = visiblePanels.filter { mapPanel != null && it.id != HamClockPanelId.MAP && it.column <= 0 && it.columnSpan == 1 }
        .sortedBy(HamClockPanelPreference::order)
    val centerPanels = visiblePanels.filter { it.id != HamClockPanelId.MAP && (mapPanel == null || it.columnSpan > 1) }
        .sortedBy(HamClockPanelPreference::order)
    val rightPanels = visiblePanels.filter { mapPanel != null && it.id != HamClockPanelId.MAP && it.column > 0 && it.columnSpan == 1 }
        .sortedBy(HamClockPanelPreference::order)

    @Composable fun SidePanel(panel: HamClockPanelPreference, modifier: Modifier) {
        val spec = hamClockModuleSpec(panel.id)
        if (spec == null) {
            Module("Legacy module", "UNAVAILABLE", modifier = modifier) {
                EmptyLine("Unknown module ID ‘${panel.id}’ was preserved. Remove or reset it in Layout.")
            }
            return
        }
        if (panel.collapsed && spec.collapseSupported) {
            Module(spec.title, "COLLAPSED · ${spec.sourceLabel}", modifier = modifier) {
                Text(spec.lowDataRepresentation, color = HcMuted, fontSize = 15.sp)
            }
            return
        }
        val open = { openDeepLink(spec.deepLink) }
        when (spec.renderer) {
            HamClockModuleRenderer.MAP -> HamClockHomeMap(mapSnapshot, settingsDocument.settings.map,
                stationPoint, settingsDocument.settings.display.lowDataMode, ::updateMapPreference,
                ::openMapSelection, modifier)
            HamClockModuleRenderer.STATION -> StationPanel(stationCall, stationGrid, radio, wavelog, app,
                { hz -> requestReceiveTune(hz, null, "Home favourite band", "Review receive-only frequency change") }, open, modifier)
            HamClockModuleRenderer.WEATHER -> WeatherPanel(neuralDx.weather, settingsDocument.settings.display.unitSystem, modifier)
            HamClockModuleRenderer.PSK_REPORTER -> SignalPanel(neuralDx.mySignal, settingsDocument.settings.pskReporter,
                { neuralDx.refreshPsk(stationCall, stationGrid, true) }, neuralDx::clearPskDisplay,
                { neuralDx.requestPage(NeuralDxPage.MAP); openDeepLink(HamClockDeepLink.DX) }, modifier)
            HamClockModuleRenderer.DX_NEWS -> DxNewsPanel(neuralDx.dxNewsSnapshot.merged.filter { item ->
                settingsDocument.settings.dxNews.source == HamClockDxNewsSource.ALL ||
                    settingsDocument.settings.dxNews.source == HamClockDxNewsSource.DX_WORLD && item.sourceId == "dxworld" ||
                    settingsDocument.settings.dxNews.source == HamClockDxNewsSource.NG3K && item.sourceId == "ng3k"
            }, neuralDx.dxNewsSnapshot.sources,
                { neuralDx.requestPage(NeuralDxPage.BRIEFING); openDeepLink(HamClockDeepLink.DX) }, modifier)
            HamClockModuleRenderer.DX_EXPEDITIONS -> DxpeditionPanel(dxpeditionFeed, open, modifier)
            HamClockModuleRenderer.BAND_ACTIVITY -> BandConditionsPanel(neuralDx.bandActivity, open, modifier)
            HamClockModuleRenderer.DX_CLUSTER -> DxPanel(visibleMapSpots, mapSpots.size, features.clusterStatus,
                spotFilters, { activeSpotFilter = it }, open, modifier)
            HamClockModuleRenderer.RBN -> RbnPanel(features.rbnObservations, features.rbnSourceSnapshot, open, modifier)
            HamClockModuleRenderer.WSPR -> WsprPanel(neuralDx.wsprPersonal, open, modifier)
            HamClockModuleRenderer.IBP -> IbpPanel(ibpSchedule, open, modifier)
            HamClockModuleRenderer.BAND_HEALTH -> BandHealthPanel(bandHealth, neuralDx.outlook.snapshot, {
                neuralDx.requestPage(NeuralDxPage.INSIGHT); openDeepLink(HamClockDeepLink.DX)
            }, open, modifier)
            HamClockModuleRenderer.NEURAL_OUTLOOK -> NeuralOutlookPanel(neuralDx.outlook.snapshot, {
                neuralDx.requestPage(NeuralDxPage.INSIGHT); openDeepLink(HamClockDeepLink.DX)
            }, modifier)
            HamClockModuleRenderer.SOLAR -> SolarPanel(features, celestialFeed, modifier)
            HamClockModuleRenderer.DX_TARGET -> DxTargetPanel(resolvedDxTarget, stationGrid,
                settingsDocument.settings.display.unitSystem, { configureTarget = true }, modifier)
            HamClockModuleRenderer.PROPAGATION -> VoacapPanel(resolvedDxTarget, features, pathPrediction,
                neuralDx.outlook.snapshot, settingsDocument.settings.display.unitSystem, modifier)
            HamClockModuleRenderer.PORTABLE -> PortablePanel(portable, open, modifier)
            HamClockModuleRenderer.SATELLITES -> SatellitePanel(operations.satellites, {
                operations.openSection("SATELLITES"); open()
            }, modifier)
            HamClockModuleRenderer.CONTESTS -> ContestsPanel(contestFeed, open, modifier)
            HamClockModuleRenderer.ANALOG_CLOCK -> AnalogClockPanel(settingsDocument.settings.display, modifier)
            HamClockModuleRenderer.LEGACY -> Module(spec.title, "UNAVAILABLE", modifier = modifier) {
                EmptyLine(spec.unavailableReason.ifBlank { "This module is not connected" })
            }
        }
    }
    val densityScale = when (settingsDocument.settings.display.density) {
        HamClockDensity.COMPACT -> .9f; HamClockDensity.COMFORTABLE -> 1f; HamClockDensity.LARGE_TOUCH -> 1.18f
    }
    fun sidePanelHeight(panel: HamClockPanelPreference) = when {
        panel.collapsed -> 82.dp
        else -> ((hamClockModuleSpec(panel.id)?.preferredHeightDp ?: 200) * panel.rowSpan * densityScale).dp
    }
    val compactPanels = visiblePanels.sortedWith(compareBy<HamClockPanelPreference> {
        if (it.id == HamClockPanelId.MAP) -1 else it.column
    }.thenBy(HamClockPanelPreference::order))
    val outerPadding = when (settingsDocument.settings.display.density) {
        HamClockDensity.COMPACT -> 6.dp
        HamClockDensity.COMFORTABLE -> 10.dp
        HamClockDensity.LARGE_TOUCH -> 14.dp
    }
    val panelGap = when (settingsDocument.settings.display.density) {
        HamClockDensity.COMPACT -> 6.dp; HamClockDensity.COMFORTABLE -> 8.dp; HamClockDensity.LARGE_TOUCH -> 12.dp
    }
    fun mutatePanel(value: HamClockPanelPreference) {
        settingsCoordinator.setPanel(value)
        if (value.id == HamClockPanelId.DX_NEWS && compactScreen) {
            settingsCoordinator.updateSettings { it.copy(dxNews = it.dxNews.copy(compactVisible = value.visible)) }
        }
    }
    fun mutateLayer(value: HamClockMapLayerPreference) { settingsCoordinator.setMapLayer(value) }
    fun mutateDisplay(value: HamClockDisplayPreference) {
        settingsCoordinator.updateSettings { it.copy(display = value) }
    }
    fun mutateProviders(cluster: HamClockClusterPreference, psk: HamClockPskPreference) {
        settingsCoordinator.updateSettings { it.copy(cluster = cluster, pskReporter = psk) }
    }
    fun mutateRfEvidence(rbn: HamClockRbnPreference, wspr: HamClockWsprPreference,
        ibp: HamClockIbpPreference, bandHealth: HamClockBandHealthPreference) {
        settingsCoordinator.updateSettings { it.copy(rbn = rbn, wspr = wspr, ibp = ibp, bandHealth = bandHealth) }
    }
    fun mutateOutlook(value: HamClockOutlookPreference) {
        settingsCoordinator.updateSettings { it.copy(outlook = value) }
    }
    val shackDisplayPreference = settingsDocument.settings.shackDisplay
    val shackProfileName = shackDisplayPreference.selectedProfileId?.let { selected ->
        settingsDocument.profiles.firstOrNull { it.id == selected }?.name
    } ?: "Current layout"

    BoxWithConstraints(Modifier.fillMaxSize().background(HcBg).testTag("openhamclock-home")) {
        val wideThreshold = when (settingsDocument.settings.display.density) {
            HamClockDensity.COMPACT -> 900.dp; HamClockDensity.COMFORTABLE -> 960.dp; HamClockDensity.LARGE_TOUCH -> 1080.dp
        }
        val wide = maxWidth >= wideThreshold && maxHeight >= (650 * densityScale).dp
        if (wide) {
            Column(Modifier.fillMaxSize().padding(outerPadding).testTag("openhamclock-safe-content"), verticalArrangement = Arrangement.spacedBy(panelGap)) {
                HamClockHeader(stationCall, stationGrid, radio, app, features, neuralDx,
                    settingsDocument.settings.display, shackDisplayPreference, shackProfileName, neuralDx.weather, {
                    neuralDx.refresh(stationCall, stationGrid, stationId, visibleMapSpots, true, NeuralDxRefreshScope.HOME)
                    features.refreshSolar(); portable.refreshAll()
                }, { showShackDisplay = true }) { configureDashboard = true }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(panelGap)) {
                    if (leftPanels.isNotEmpty()) LazyColumn(Modifier.widthIn(min = 300.dp, max = 350.dp).weight(.26f), verticalArrangement = Arrangement.spacedBy(panelGap)) {
                        items(leftPanels, key = HamClockPanelPreference::id) { panel ->
                            SidePanel(panel, Modifier.fillMaxWidth().height(sidePanelHeight(panel)))
                        }
                    }
                    Column(Modifier.weight(when (mapPanel?.columnSpan) { 1 -> .34f; 2 -> .56f; else -> .75f }),
                        verticalArrangement = Arrangement.spacedBy(panelGap)) {
                        if (mapPanel != null) SidePanel(mapPanel, Modifier.fillMaxWidth()
                            .weight(if (centerPanels.isEmpty()) 1f else 1.5f).heightIn(min = 390.dp))
                        if (centerPanels.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(panelGap)) {
                            items(centerPanels, key = HamClockPanelPreference::id) { panel ->
                                SidePanel(panel, Modifier.fillMaxWidth().height(sidePanelHeight(panel)))
                            }
                        }
                    }
                    if (rightPanels.isNotEmpty()) LazyColumn(Modifier.widthIn(min = 320.dp, max = 380.dp).weight(.28f), verticalArrangement = Arrangement.spacedBy(panelGap)) {
                        items(rightPanels, key = HamClockPanelPreference::id) { panel ->
                            SidePanel(panel, Modifier.fillMaxWidth().height(sidePanelHeight(panel)))
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(outerPadding).testTag("openhamclock-safe-content"), verticalArrangement = Arrangement.spacedBy(panelGap)) {
                item { HamClockHeader(stationCall, stationGrid, radio, app, features, neuralDx,
                    settingsDocument.settings.display, shackDisplayPreference, shackProfileName, neuralDx.weather, {
                    neuralDx.refresh(stationCall, stationGrid, stationId, features.liveSpots, true, NeuralDxRefreshScope.HOME)
                    features.refreshSolar(); portable.refreshAll()
                }, { showShackDisplay = true }) { configureDashboard = true } }
                items(compactPanels, key = HamClockPanelPreference::id) { panel ->
                    val height = if (panel.id == HamClockPanelId.MAP) {
                        if (maxWidth < 500.dp) (290 * panel.rowSpan).dp else (390 * panel.rowSpan).dp
                    } else sidePanelHeight(panel)
                    SidePanel(panel, Modifier.fillMaxWidth().height(height))
                }
            }
        }
        if (configureDashboard) HamClockConfigDialog(settingsDocument, ::mutatePanel,
                settingsCoordinator::resetPanel,
                settingsCoordinator::resetLayout, ::mutateLayer, ::updateMapPreference,
            ::mutateDisplay, ::mutateProviders, ::mutateRfEvidence, ::mutateOutlook, ::saveDashboardProfile, ::applyDashboardProfile,
                settingsCoordinator::renameProfile,
                settingsCoordinator::deleteProfile,
                settingsCoordinator::clearActiveProfile,
                { settingsCoordinator.exportJson(true) },
                { json -> settingsCoordinator.importJson(json) },
            { configureTarget = true },
        ) { configureDashboard = false }
        if (configureTarget) HamClockTargetDialog(settingsDocument.settings.dxTarget, callbook, cty,
            { target -> settingsCoordinator.updateSettings { it.copy(dxTarget = target) }; configureTarget = false },
            { settingsCoordinator.updateSettings { it.copy(dxTarget = null) }; configureTarget = false },
            { configureTarget = false })
        activeSpotFilter?.let { dimension ->
            SpotFilterOverlay(dimension, spotFilters,
                (spotModeOptions + mapSpots.map { canonicalSpotMode(it.mode) }).distinct().sorted(),
                { activeSpotFilter = null }, { updateSpotFilters(it); activeSpotFilter = null }, Modifier.fillMaxSize(), app,
                hamClockHomeBandOptions, hamClockHomeBandPresets)
        }
        if (showShackDisplay) HamClockShackDisplay(
            stationCall, stationGrid, radio, pathPrediction, nativeStatus, finishLineWeather,
            features.solar, features.sunspotNumber, celestialFeed?.value?.xray ?: HamClockXraySeries(), finishLineAurora,
            solarImage, contests, selectedContestId, contestQsos, settingsDocument.profiles, operations.satellites.hamClockPositions,
            operations.satellites.hamClockTracks, settingsDocument.settings,
            { transform -> settingsCoordinator.updateSettings(transform) },
            { selectedContestId = it },
            { id -> applyDashboardProfile(id); settingsCoordinator.updateSettings { it.copy(
                shackDisplay = it.shackDisplay.copy(selectedProfileId = id)) } },
            { channel, force -> solarImageRequest = channel to force }, openExactQso,
            { showShackDisplay = false },
        )
    }
}

@Composable private fun RbnPanel(rows: List<HamClockRbnObservation>, source: HamClockRbnSourceSnapshot, open: () -> Unit, modifier: Modifier) {
    Module("Reverse Beacon Network", "${source.state} · RETAIL CLUSTER · ${rows.size}", modifier = modifier, onClick = open) {
        if (rows.isEmpty()) EmptyLine("No RBN skimmer observations match the current bounded policy.")
        rows.take(6).forEach { row ->
            KeyValue(row.dxCall, "${row.band} ${row.mode} · ${row.skimmerCall}" +
                row.snr?.let { " · $it dB" }.orEmpty(), HcInk)
        }
    }
}

@Composable private fun WsprPanel(snapshot: HamClockWsprSnapshot, open: () -> Unit, modifier: Modifier) {
    Module("Personal WSPR", "${snapshot.sourceState} · PSK REPORTER · ${snapshot.reports.size}", modifier = modifier, onClick = open) {
        Text("Regional WSPR.live · ${snapshot.regionalState}", color = HcMuted, fontSize = 14.sp)
        if (snapshot.reports.isEmpty()) EmptyLine(snapshot.error.ifBlank { "No personal WSPR observations in the selected window." })
        snapshot.reports.take(6).forEach { row ->
            KeyValue(row.callsign, "${row.direction.name.replace('_', ' ')} · ${row.band}" +
                row.snr?.let { " · $it dB" }.orEmpty(), HcInk)
        }
    }
}

@Composable private fun IbpPanel(schedule: HamClockIbpSchedule, open: () -> Unit, modifier: Modifier) {
    Module("IBP schedule", "SLOT ${schedule.slot + 1}/18", modifier = modifier, onClick = open) {
        Text("Schedule reference only · not heard evidence", color = HcAmber, fontSize = 14.sp)
        schedule.transmissions.forEach { row ->
            KeyValue(row.band, "${row.beacon.callsign} · ${row.beacon.grid} · %.3f MHz".format(Locale.US, row.frequencyHz / 1_000_000.0), HcInk)
        }
    }
}

@Composable private fun BandHealthPanel(rows: List<HamClockBandHealthRow>, outlook: NeuralOutlookSnapshot,
    openOutlook: () -> Unit, open: () -> Unit, modifier: Modifier) {
    Module("Band Health", "MEASURED · EXPLAINABLE", modifier = modifier, onClick = open) {
        rows.take(9).forEach { row ->
            KeyValue(row.band, "${row.state} · n=${row.observations} · ${row.trend} · ${row.confidence}", HcInk)
        }
        HorizontalDivider(color = HcLine)
        Text("CURRENT EVIDENCE", color = HcMuted, fontSize = HcMetaSize)
        Text("NEXT 60 MIN OUTLOOK", color = HcAmber, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = openOutlook))
    }
}

@Composable private fun NeuralOutlookPanel(snapshot: NeuralOutlookSnapshot, open: () -> Unit, modifier: Modifier) {
    Module("Neural outlook", "${snapshot.selectedWindow.minutes} MIN · EMPIRICAL", modifier = modifier, onClick = open) {
        Text("Observed support · ${snapshot.modelVersion}", color = HcMuted, fontSize = HcMetaSize)
        if (snapshot.topBands.isEmpty()) EmptyLine("Insufficient evidence · ${snapshot.calibration.label}")
        snapshot.topBands.take(3).forEach { row ->
            KeyValue(row.band, "${row.label.name.replace('_', ' ')} · ${row.confidence} · ${row.sourceCount} sources", HcInk)
        }
        Text(snapshot.calibration.label, color = HcAmber, fontSize = HcMetaSize)
        if (snapshot.status.startsWith("Outlook retrying")) Text(snapshot.status, color = HcAmber, fontSize = HcMetaSize)
        val ageMinutes = if (snapshot.generatedEpoch > 0) ((Instant.now().epochSecond - snapshot.generatedEpoch).coerceAtLeast(0) / 60) else null
        Text("Generated ${ageMinutes?.let { "${it}m ago" } ?: "waiting"} · OPEN INSIGHT & OUTLOOK", color = HcCyan, fontSize = HcMetaSize)
    }
}

@Composable
private fun HamClockHeader(call: String, grid: String, radio: RadioState, app: AppController,
    features: FeatureController, neuralDx: NeuralDxController, display: HamClockDisplayPreference,
    shackDisplay: HamClockShackDisplayPreference, shackProfileName: String,
    weather: NeuralWeather, refresh: () -> Unit, enterShack: () -> Unit, configure: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1_000) } }
    val utc = now.atZone(ZoneOffset.UTC)
    val local = now.atZone(ZoneId.systemDefault())
    val clock = remember(display.hourFormat) {
        DateTimeFormatter.ofPattern(if (display.hourFormat == HamClockHourFormat.H24) "HH:mm:ss" else "hh:mm:ss a", Locale.US)
    }
    val weatherText = if (!weather.available) "WX —" else when (display.unitSystem) {
        HamClockUnitSystem.METRIC -> weather.temperatureC?.let { "WX %.1f°C".format(Locale.US, it) } ?: "WX —"
        HamClockUnitSystem.IMPERIAL -> weather.temperatureC?.let { "WX %.1f°F".format(Locale.US, it * 9 / 5 + 32) } ?: "WX —"
    }
    Surface(color = HcPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().border(1.dp, HcLine, RoundedCornerShape(8.dp))) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (maxWidth < 760.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderIdentity(call, grid, Modifier.weight(1f))
                    StatusPill(if (radio.connected) "CAT LIVE" else "CAT OFFLINE", radio.connected)
                    StatusPill(if (app.transmitArmed) "TX ARMED" else "SAFE / RX", !app.transmitArmed)
                    ShackEntry(enterShack, shackDisplay, shackProfileName, compact = true); ConfigButton(configure); SyncButton(neuralDx.refreshing, refresh)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (display.timeZoneMode != HamClockTimeZoneMode.LOCAL) ClockReadout("UTC", utc.format(clock), utc.format(HcDate))
                    if (display.timeZoneMode != HamClockTimeZoneMode.UTC) ClockReadout("LOCAL", local.format(clock), local.format(HcDate))
                    SolarMetrics(features)
                    Text(weatherText, color = HcCyan, fontFamily = FontFamily.Monospace)
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderIdentity(call, grid, Modifier.weight(1f))
                if (display.timeZoneMode != HamClockTimeZoneMode.LOCAL) ClockReadout("UTC", utc.format(clock), utc.format(HcDate))
                if (display.timeZoneMode != HamClockTimeZoneMode.UTC) ClockReadout("LOCAL", local.format(clock), local.format(HcDate))
                SolarMetrics(features)
                Text(weatherText, color = HcCyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                StatusPill(if (radio.connected) "CAT LIVE" else "CAT OFFLINE", radio.connected)
                StatusPill(if (app.transmitArmed) "TX ARMED" else "SAFE / RX", !app.transmitArmed)
                ShackEntry(enterShack, shackDisplay, shackProfileName, compact = true); ConfigButton(configure); SyncButton(neuralDx.refreshing, refresh)
            }
        }
    }
}

@Composable private fun ShackEntry(
    open: () -> Unit,
    preference: HamClockShackDisplayPreference,
    profileName: String,
    compact: Boolean,
) {
    Button(open, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.DesktopWindows, contentDescription = "Open Shack display", Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text("Shack")
    }
}

@Composable private fun ConfigButton(configure: () -> Unit) {
    OutlinedButton(configure, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Tune, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("LAYOUT")
    }
}

@Composable private fun HamClockConfigDialog(
    document: HamClockSettingsDocument,
    updatePanel: (HamClockPanelPreference) -> Unit,
    resetPanel: (String) -> Unit,
    resetLayout: () -> Unit,
    updateLayer: (HamClockMapLayerPreference) -> Unit,
    updateMap: (HamClockMapPreference) -> Unit,
    updateDisplay: (HamClockDisplayPreference) -> Unit,
    updateProviders: (HamClockClusterPreference, HamClockPskPreference) -> Unit,
    updateRfEvidence: (HamClockRbnPreference, HamClockWsprPreference, HamClockIbpPreference, HamClockBandHealthPreference) -> Unit,
    updateOutlook: (HamClockOutlookPreference) -> Unit,
    saveProfile: (String, String?) -> Unit,
    applyProfile: (String) -> Unit,
    renameProfile: (String, String) -> Unit,
    deleteProfile: (String) -> Unit,
    clearProfile: () -> Unit,
    exportJson: () -> String,
    importJson: (String) -> Unit,
    editTarget: () -> Unit,
    dismiss: () -> Unit,
) {
    val settings = document.settings
    var profileName by remember { mutableStateOf("Layout ${document.profiles.size + 1}") }
    var transferJson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val panelRows = hamClockModuleRegistry.map { spec ->
        settings.panels.firstOrNull { it.id == spec.id } ?: defaultHamClockPanels().first { it.id == spec.id }
    } + settings.panels.filter { hamClockModuleSpec(it.id) == null }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Configure Open Ham Clock") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                Text("Every control below is live and persisted locally. Unknown imported IDs remain visible as unavailable and can be removed.",
                    color = HcMuted, fontSize = 15.sp)
            }
            item {
                Text("DISPLAY", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text("Layout density · spacing, panel height and responsive breakpoints", color = HcMuted, fontSize = 14.sp)
                EnumChips(HamClockDensity.entries, settings.display.density) {
                    updateDisplay(settings.display.copy(density = it))
                }
                EnumChips(HamClockTimeZoneMode.entries, settings.display.timeZoneMode) {
                    updateDisplay(settings.display.copy(timeZoneMode = it))
                }
                EnumChips(HamClockHourFormat.entries, settings.display.hourFormat) {
                    updateDisplay(settings.display.copy(hourFormat = it))
                }
                EnumChips(HamClockUnitSystem.entries, settings.display.unitSystem) {
                    updateDisplay(settings.display.copy(unitSystem = it))
                }
                ToggleRow("Low-data Map Data view", settings.display.lowDataMode) {
                    updateDisplay(settings.display.copy(lowDataMode = it))
                }
                OutlinedButton(editTarget, modifier = Modifier.heightIn(min = 48.dp)) { Text("MANUAL DX TARGET") }
                HorizontalDivider(color = HcLine)
            }
            item {
                val cluster = settings.cluster
                val psk = settings.pskReporter
                val rbn = settings.rbn
                val wspr = settings.wspr
                val ibp = settings.ibp
                val health = settings.bandHealth
                val outlook = settings.outlook
                Text("LIVE RF SOURCES", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                ToggleRow("DX cluster connection and Home visibility", cluster.enabled) {
                    updateProviders(cluster.copy(enabled = it), psk)
                }
                Text("Cluster window", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(15, 30, 60, 120), cluster.windowMinutes, "m") {
                    updateProviders(cluster.copy(windowMinutes = it), psk)
                }
                Text("Cluster presentation cap", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(50, 100, 250, 500), cluster.maximumSpots) {
                    updateProviders(cluster.copy(maximumSpots = it), psk)
                }
                Text("Cluster stale threshold (live socket; not polling)", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(30, 60, 120, 300), cluster.refreshSeconds, "s") {
                    updateProviders(cluster.copy(refreshSeconds = it), psk)
                }
                FilterTokenChips("Cluster bands", listOf("80M", "40M", "20M", "15M", "10M", "6M"), cluster.filter.bands) {
                    updateProviders(cluster.copy(filter = cluster.filter.copy(bands = it)), psk)
                }
                FilterTokenChips("Cluster modes", listOf("CW", "SSB", "FT8", "FT4", "RTTY"), cluster.filter.modes) {
                    updateProviders(cluster.copy(filter = cluster.filter.copy(modes = it)), psk)
                }
                FilterTokenChips("Cluster continents", listOf("AF", "AS", "EU", "NA", "OC", "SA"), cluster.filter.continents) {
                    updateProviders(cluster.copy(filter = cluster.filter.copy(continents = it)), psk)
                }
                OutlinedTextField(cluster.filter.callQuery, { value ->
                    updateProviders(cluster.copy(filter = cluster.filter.copy(callQuery = value.uppercase().take(32))), psk)
                }, label = { Text("Cluster callsign filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Cluster SNR filtering is unavailable: ordinary cluster spots do not carry comparable SNR.", color = HcMuted, fontSize = 14.sp)
                HorizontalDivider(color = HcLine)
                ToggleRow("PSK Reporter", psk.enabled) { updateProviders(cluster, psk.copy(enabled = it)) }
                EnumChips(HamClockPskDirection.entries, psk.direction) { updateProviders(cluster, psk.copy(direction = it)) }
                Text("PSK window", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(2, 5, 10, 15, 30, 60, 120), psk.windowMinutes, "m") {
                    updateProviders(cluster, psk.copy(windowMinutes = it))
                }
                Text("Provider-safe refresh cadence", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(300, 600, 900), psk.refreshSeconds, "s") {
                    updateProviders(cluster, psk.copy(refreshSeconds = it))
                }
                Text("PSK report cap", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(50, 100, 250, 500), psk.maximumReports) {
                    updateProviders(cluster, psk.copy(maximumReports = it))
                }
                FilterTokenChips("PSK bands", listOf("80M", "40M", "20M", "15M", "10M", "6M"), psk.filter.bands) {
                    updateProviders(cluster, psk.copy(filter = psk.filter.copy(bands = it)))
                }
                FilterTokenChips("PSK modes", listOf("FT8", "FT4", "JS8", "WSPR", "RTTY", "PSK31"), psk.filter.modes) {
                    updateProviders(cluster, psk.copy(filter = psk.filter.copy(modes = it)))
                }
                FilterTokenChips("PSK continents", listOf("AF", "AS", "EU", "NA", "OC", "SA"), psk.filter.continents) {
                    updateProviders(cluster, psk.copy(filter = psk.filter.copy(continents = it)))
                }
                OutlinedTextField(psk.filter.callQuery, { value ->
                    updateProviders(cluster, psk.copy(filter = psk.filter.copy(callQuery = value.uppercase().take(32))))
                }, label = { Text("PSK remote callsign filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Minimum SNR", color = HcMuted, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf<Int?>(null, -20, -15, -10, -5, 0).forEach { value ->
                        FilterChip(psk.filter.minimumSnr == value, {
                            updateProviders(cluster, psk.copy(filter = psk.filter.copy(minimumSnr = value)))
                        }, { Text(value?.let { "$it dB" } ?: "ANY") })
                    }
                }
                HorizontalDivider(color = HcLine)
                Text("RBN OBSERVATIONS", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                ToggleRow("RBN from configured retail cluster", rbn.enabled) {
                    updateRfEvidence(rbn.copy(enabled = it), wspr, ibp, health)
                }
                EnumChips(HamClockRbnSource.entries, rbn.source) {
                    updateRfEvidence(rbn.copy(source = it), wspr, ibp, health)
                }
                Text("RBN view", color = HcMuted, fontSize = HcMetaSize)
                EnumChips(HamClockRbnMode.entries, rbn.viewMode) {
                    updateRfEvidence(rbn.copy(viewMode = it), wspr, ibp, health)
                }
                Text("No official raw RBN firehose is opened. Source choices remain inside the existing configured retail-cluster connection.",
                    color = HcMuted, fontSize = 14.sp)
                Text("RBN window", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(2, 5, 10, 15, 30), rbn.windowMinutes, "m") {
                    updateRfEvidence(rbn.copy(windowMinutes = it), wspr, ibp, health)
                }
                Text("RBN row/map cap", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(50, 100, 120, 250), rbn.maximumRows) {
                    updateRfEvidence(rbn.copy(maximumRows = it), wspr, ibp, health)
                }
                FilterTokenChips("RBN bands", listOf("80M", "40M", "20M", "15M", "10M", "6M"), rbn.bands) {
                    updateRfEvidence(rbn.copy(bands = it), wspr, ibp, health)
                }
                FilterTokenChips("RBN modes", listOf("CW", "RTTY", "PSK", "FT8"), rbn.modes) {
                    updateRfEvidence(rbn.copy(modes = it), wspr, ibp, health)
                }
                OutlinedTextField(rbn.skimmerCall, { updateRfEvidence(rbn.copy(skimmerCall = it.uppercase().take(24)), wspr, ibp, health) },
                    label = { Text("Skimmer callsign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rbn.dxCall, { updateRfEvidence(rbn.copy(dxCall = it.uppercase().take(24)), wspr, ibp, health) },
                    label = { Text("DX callsign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ToggleRow("Watchlist only", rbn.watchlistOnly) { updateRfEvidence(rbn.copy(watchlistOnly = it), wspr, ibp, health) }
                ToggleRow("Show RBN paths", rbn.showPaths) { updateRfEvidence(rbn.copy(showPaths = it), wspr, ibp, health) }
                Text("Minimum RBN SNR", color = HcMuted, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf<Int?>(null, -20, -15, -10, -5, 0).forEach { value ->
                        FilterChip(rbn.minimumSnr == value, { updateRfEvidence(rbn.copy(minimumSnr = value), wspr, ibp, health) },
                            { Text(value?.let { "$it dB" } ?: "ANY") })
                    }
                }
                HorizontalDivider(color = HcLine)
                Text("PERSONAL WSPR", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                ToggleRow("Personal WSPR via PSK Reporter", wspr.personalEnabled) {
                    updateRfEvidence(rbn, wspr.copy(personalEnabled = it), ibp, health)
                }
                EnumChips(HamClockPskDirection.entries, wspr.direction) {
                    updateRfEvidence(rbn, wspr.copy(direction = it), ibp, health)
                }
                IntChips(listOf(2, 5, 10, 15, 30, 60, 120), wspr.windowMinutes, "m") {
                    updateRfEvidence(rbn, wspr.copy(windowMinutes = it), ibp, health)
                }
                FilterTokenChips("WSPR band", listOf("ALL", "80M", "40M", "20M", "15M", "10M", "6M"), setOf(wspr.band)) {
                    updateRfEvidence(rbn, wspr.copy(band = it.firstOrNull() ?: "ALL"), ibp, health)
                }
                ToggleRow("Show personal WSPR paths", wspr.showPaths) {
                    updateRfEvidence(rbn, wspr.copy(showPaths = it), ibp, health)
                }
                Text("Personal WSPR path cap", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(25, 50, 100), wspr.maximumPaths) {
                    updateRfEvidence(rbn, wspr.copy(maximumPaths = it), ibp, health)
                }
                Text("Minimum WSPR SNR", color = HcMuted, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf<Int?>(null, -25, -20, -15, -10, -5).forEach { value ->
                        FilterChip(wspr.minimumSnr == value, { updateRfEvidence(rbn, wspr.copy(minimumSnr = value), ibp, health) },
                            { Text(value?.let { "$it dB" } ?: "ANY") })
                    }
                }
                Text("Regional WSPR.live · UNAVAILABLE_POLICY", color = HcAmber,
                    fontWeight = FontWeight.Bold, fontSize = HcMetaSize)
                Text("No regional request or grid control is enabled until an owner-approved provider policy exists.",
                    color = HcMuted, fontSize = HcMetaSize)
                HorizontalDivider(color = HcLine)
                Text("IBP AND BAND HEALTH", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                ToggleRow("Show all 18 IBP sites", ibp.showAllSites) { updateRfEvidence(rbn, wspr, ibp.copy(showAllSites = it), health) }
                ToggleRow("Show five scheduled IBP paths", ibp.showPaths) { updateRfEvidence(rbn, wspr, ibp.copy(showPaths = it), health) }
                Text("Band Health window", color = HcMuted, fontSize = 14.sp)
                IntChips(listOf(5, 10, 15, 30, 60), health.windowMinutes, "m") {
                    updateRfEvidence(rbn, wspr, ibp, health.copy(windowMinutes = it))
                }
                FilterTokenChips("Band Health mode", listOf("ALL", "CW", "SSB", "FT8", "WSPR", "RTTY"), setOf(health.mode)) {
                    updateRfEvidence(rbn, wspr, ibp, health.copy(mode = it.firstOrNull() ?: "ALL"))
                }
                FilterTokenChips("Band Health live sources", listOf("CLUSTER", "PSK", "RBN", "WSPR"), health.enabledSources) {
                    updateRfEvidence(rbn, wspr, ibp, health.copy(enabledSources = it))
                }
                Text("Logbook QSOs are historical comparison only and never make a band live.", color = HcMuted, fontSize = HcMetaSize)
                FilterTokenChips("Band Health visible bands", listOf("160M", "80M", "60M", "40M", "30M", "20M", "17M", "15M", "12M", "10M", "6M"), health.visibleBands) {
                    updateRfEvidence(rbn, wspr, ibp, health.copy(visibleBands = it))
                }
                HorizontalDivider(color = HcLine)
                Text("EMPIRICAL OUTLOOK", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                ToggleRow("Outlook enabled", outlook.enabled) { updateOutlook(outlook.copy(enabled = it)) }
                ToggleRow("Show module on compact layouts", outlook.showOnCompact) { updateOutlook(outlook.copy(showOnCompact = it)) }
                EnumChips(HamClockOutlookWindow.entries, outlook.defaultWindow) { updateOutlook(outlook.copy(defaultWindow = it)) }
                FilterTokenChips("Outlook visible bands", NEURAL_OUTLOOK_BANDS.map(String::uppercase), outlook.visibleBands) {
                    updateOutlook(outlook.copy(visibleBands = it.map(String::lowercase).toSet()))
                }
                ToggleRow("World empirical outlook layer", outlook.worldLayerEnabled) {
                    updateOutlook(outlook.copy(worldLayerEnabled = it))
                }
                Text("World outlook opacity", color = HcMuted, fontSize = 14.sp)
                Slider(outlook.worldOpacity, { updateOutlook(outlook.copy(worldOpacity = it)) }, valueRange = .15f..9f / 10f)
                Text("Evidence retention · 180 days fixed", color = HcMuted, fontSize = HcMetaSize)
                HorizontalDivider(color = HcLine)
            }
            item {
                Text("LAYOUT PROFILES", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                OutlinedTextField(profileName, { profileName = it.take(64) }, label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ runCatching { saveProfile(profileName, null) }.onFailure { message = it.message.orEmpty() } },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("SAVE NEW") }
                    if (document.activeProfileId != null) OutlinedButton({
                        runCatching { saveProfile(profileName, document.activeProfileId) }.onFailure { message = it.message.orEmpty() }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OVERWRITE") }
                    TextButton(clearProfile) { Text("CLEAR ACTIVE") }
                }
            }
            items(document.profiles.sortedBy(HamClockNamedProfile::name), key = HamClockNamedProfile::id) { profile ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(profile.id == document.activeProfileId, { applyProfile(profile.id) }, { Text(profile.name) },
                        modifier = Modifier.weight(1f))
                    TextButton({ renameProfile(profile.id, profileName) }) { Text("RENAME") }
                    TextButton({ deleteProfile(profile.id) }) { Text("DELETE") }
                }
            }
            item {
                OutlinedTextField(transferJson, { transferJson = it }, label = { Text("Profile JSON import/export") },
                    minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton({ transferJson = exportJson(); message = "Export ready" }) { Text("EXPORT") }
                    TextButton({
                        runCatching { importJson(transferJson) }
                            .onSuccess { message = "Import applied" }.onFailure { message = it.message.orEmpty() }
                    }) { Text("IMPORT") }
                }
                if (message.isNotBlank()) Text(message, color = HcAmber, fontSize = 15.sp)
                HorizontalDivider(color = HcLine)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("MODULE REGISTRY", color = HcAmber, fontWeight = FontWeight.Black,
                        fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(resetLayout) { Text("RESET PANELS") }
                }
            }
            items(panelRows, key = HamClockPanelPreference::id) { panel ->
                val spec = hamClockModuleSpec(panel.id)
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ToggleRow(spec?.title ?: "Legacy ${panel.id}", panel.visible) { updatePanel(panel.copy(visible = it)) }
                    Text(spec?.let { "${it.category.name} · ${it.sourceLabel} · ${it.visualRole}" }
                        ?: "Unavailable · unknown imported module ID", color = HcMuted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        (spec?.allowedColumns ?: setOf(0, 2)).sorted().forEach { column ->
                            FilterChip(panel.column == column, { updatePanel(panel.copy(column = column)) },
                                { Text(if (column == 0) "LEFT" else if (column == 1) "CENTRE" else "RIGHT") })
                        }
                        OutlinedButton({ updatePanel(panel.copy(order = (panel.order - 1).coerceAtLeast(0))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("↑") }
                        OutlinedButton({ updatePanel(panel.copy(order = (panel.order + 1).coerceAtMost(999))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("↓") }
                        OutlinedButton({ updatePanel(panel.copy(rowSpan = (panel.rowSpan - 1).coerceAtLeast(1))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("ROW−") }
                        Text("${panel.rowSpan}×${panel.columnSpan}", color = HcInk)
                        OutlinedButton({ updatePanel(panel.copy(rowSpan = (panel.rowSpan + 1).coerceAtMost(4))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("ROW+") }
                        OutlinedButton({ updatePanel(panel.copy(columnSpan = (panel.columnSpan - 1).coerceAtLeast(1))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("COL−") }
                        OutlinedButton({ updatePanel(panel.copy(columnSpan = (panel.columnSpan + 1).coerceAtMost(3))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("COL+") }
                    }
                    if (spec?.collapseSupported != false) ToggleRow("Collapsed", panel.collapsed) {
                        updatePanel(panel.copy(collapsed = it))
                    }
                    TextButton({ resetPanel(panel.id) }) { Text(if (spec == null) "REMOVE UNKNOWN" else "RESET MODULE") }
                    HorizontalDivider(color = HcLine)
                }
            }
            item {
                Text("MAP", color = HcAmber, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    HamClockBasemap.entries.forEach { basemap ->
                        val lawful = basemap == HamClockBasemap.DARK || basemap == HamClockBasemap.LIGHT
                        FilterChip(settings.map.basemap == basemap, { if (lawful) updateMap(settings.map.copy(basemap = basemap)) },
                            { Text(basemap.name) }, enabled = lawful)
                    }
                }
                Text("Satellite and terrain unavailable: no lawful configured tile source.", color = HcMuted, fontSize = 14.sp)
                ToggleRow("Follow DE station", settings.map.followStation) { updateMap(settings.map.copy(followStation = it)) }
                TextButton({ updateMap(settings.map.copy(followStation = true, centerLatitude = 0.0,
                    centerLongitude = 0.0, zoom = 1.2)) }) { Text("RESET CAMERA") }
            }
            items(hamClockMapLayerRegistry, key = HamClockMapLayerSpec::id) { spec ->
                val pref = settings.map.layers.firstOrNull { it.id == spec.id }
                    ?: HamClockMapLayerPreference(spec.id, spec.defaultVisible, spec.defaultOpacity)
                Column(Modifier.fillMaxWidth()) {
                    ToggleRow(spec.title, pref.visible, spec.availability != HamClockMapLayerAvailability.UNAVAILABLE) {
                        updateLayer(pref.copy(visible = it))
                    }
                    Text("${spec.sourceLabel} · ${spec.lowDataRepresentation}", color = HcMuted, fontSize = 14.sp)
                    if (spec.availability == HamClockMapLayerAvailability.UNAVAILABLE) {
                        Text("Unavailable · ${spec.unavailableReason}", color = HcAmber, fontSize = 14.sp)
                    } else {
                        Slider(pref.opacity, { updateLayer(pref.copy(opacity = it)) }, valueRange = 0.1f..1f)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Done") } })
}

@Composable private fun <T : Enum<T>> EnumChips(values: List<T>, selected: T, update: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value -> FilterChip(value == selected, { update(value) }, { Text(value.name.replace('_', ' ')) }) }
    }
}

@Composable private fun IntChips(values: List<Int>, selected: Int, suffix: String = "", update: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value -> FilterChip(value == selected, { update(value) }, { Text("$value$suffix") }) }
    }
}

@Composable private fun FilterTokenChips(label: String, values: List<String>, selected: Set<String>, update: (Set<String>) -> Unit) {
    Text(label, color = HcMuted, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value -> FilterChip(value in selected, {
            update(selected.toMutableSet().apply { if (!add(value)) remove(value) })
        }, { Text(value) }) }
    }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) HcInk else HcMuted, modifier = Modifier.weight(1f))
        Switch(checked, update, enabled = enabled)
    }
}

@Composable private fun HamClockTargetDialog(
    current: HamClockDxTarget?,
    callbook: CallbookController,
    cty: CtyController,
    save: (HamClockDxTarget) -> Unit,
    clear: () -> Unit,
    dismiss: () -> Unit,
) {
    var callsign by remember(current) { mutableStateOf(current?.callsign.orEmpty()) }
    var grid by remember(current) { mutableStateOf(current?.grid.orEmpty()) }
    var latitude by remember(current) { mutableStateOf(current?.latitude) }
    var longitude by remember(current) { mutableStateOf(current?.longitude) }
    var locked by remember(current) { mutableStateOf(current?.locked ?: true) }
    var status by remember { mutableStateOf("") }
    fun lookup() {
        val call = callsign.trim().uppercase(Locale.US)
        if (call.isBlank()) { status = "Enter a callsign"; return }
        status = "Resolving through configured callbook…"
        callbook.lookup(call) { record ->
            val ctyRecord = cty.lookup(call)
            grid = record?.grid?.ifBlank { grid } ?: grid
            val gridPoint = maidenheadCenter(grid)
            latitude = record?.latitude?.toDoubleOrNull() ?: gridPoint?.latitude
                ?: ctyRecord?.latitude?.takeUnless { it == 0.0 }
            longitude = record?.longitude?.toDoubleOrNull() ?: gridPoint?.longitude
                ?: ctyRecord?.longitude?.takeUnless { it == 0.0 }
            status = when {
                record != null -> "Resolved by ${record.source.ifBlank { "callbook" }}"
                ctyRecord != null && (latitude != null || longitude != null) -> "Resolved by CTY.DAT fallback"
                gridPoint != null -> "Resolved from Maidenhead grid"
                else -> "No coordinate available; enter a valid grid"
            }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Manual DX target") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A locked manual target blocks automatic cluster target replacement. This control never tunes the radio.",
                color = HcMuted, fontSize = 15.sp)
            OutlinedTextField(callsign, { callsign = it.uppercase(Locale.US).take(16) },
                label = { Text("Callsign") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(grid, {
                grid = it.uppercase(Locale.US).take(8)
                maidenheadCenter(grid)?.let { point -> latitude = point.latitude; longitude = point.longitude }
            }, label = { Text("Maidenhead grid") }, modifier = Modifier.fillMaxWidth())
            ToggleRow("Lock manual target", locked) { locked = it }
            OutlinedButton(::lookup, modifier = Modifier.heightIn(min = 48.dp)) { Text("LOOK UP") }
            Text(status.ifBlank {
                listOfNotNull(latitude?.let { "%.4f".format(Locale.US, it) },
                    longitude?.let { "%.4f".format(Locale.US, it) }).joinToString(", ").ifBlank { "Coordinate not resolved" }
            }, color = HcMuted, fontSize = 15.sp)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val point = latitude?.let { lat -> longitude?.let { lon -> GeoPoint(lat, lon) } } ?: maidenheadCenter(grid)
            if (point == null) { status = "A valid grid or resolved coordinate is required"; return@TextButton }
            save(HamClockDxTarget(callsign.trim(), grid.trim(), point.latitude, point.longitude,
                locked, HamClockDxTargetSource.MANUAL))
        }) { Text("Save") }
    }, dismissButton = {
        Row {
            TextButton(clear) { Text("Clear") }
            TextButton(dismiss) { Text("Cancel") }
        }
    })
}

@Composable private fun HeaderIdentity(call: String, grid: String, modifier: Modifier) = Column(modifier) {
    Text("SHACKCQ · OPEN HAM CLOCK", color = HcAmber, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, fontSize = 22.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("${call.ifBlank { "CALL NOT SET" }} · ${grid.ifBlank { "GRID NOT SET" }}", color = HcInk,
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun SolarMetrics(features: FeatureController) {
    Metric("SFI", features.solar.flux.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—", HcAmber)
    Metric("A", features.solar.aIndex.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—",
        if (features.solar.aIndex >= 30) HcRed else HcYellow)
    Metric("KP", features.solar.kpIndex.takeIf { features.solar.valid }?.let { "%.1f".format(Locale.US, it) } ?: "—",
        if (features.solar.kpIndex >= 5) HcRed else HcGreen)
    SunspotMetric(features)
}

@Composable private fun SunspotMetric(features: FeatureController) {
    val observed = features.sunspotObservedMonth
    val stale = observed.isNotBlank() && runCatching {
        java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.parse(observed), java.time.YearMonth.now()) > 2
    }.getOrDefault(true)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SSN NOAA", color = HcMuted, fontSize = HcLabelSize, fontWeight = FontWeight.Bold)
        Text(features.sunspotNumber?.roundToInt()?.toString() ?: "—", color = when {
            features.sunspotNumber == null -> HcMuted; stale || features.sunspotError.isNotBlank() -> HcYellow; else -> HcGreen
        }, fontFamily = FontFamily.Monospace, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(when { observed.isBlank() -> "UNAVAILABLE"; stale -> "$observed STALE"; features.sunspotError.isNotBlank() -> "$observed CACHED"; else -> "$observed MONTHLY" },
            color = HcMuted, fontSize = 12.sp)
    }
}

@Composable private fun SyncButton(refreshing: Boolean, refresh: () -> Unit) {
    Button(refresh, enabled = !refreshing, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (refreshing) "…" else "SYNC")
    }
}

@Composable private fun ClockReadout(label: String, time: String, date: String) = Column(horizontalAlignment = Alignment.End) {
    Text("$label  $time", color = HcInk, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text(date.uppercase(), color = HcMuted, fontSize = 15.sp)
}

@Composable private fun Metric(label: String, value: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, color = HcMuted, fontSize = HcLabelSize, fontWeight = FontWeight.Bold, letterSpacing = .15.sp)
    Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 25.sp, fontWeight = FontWeight.Black)
}

@Composable private fun StatusPill(text: String, good: Boolean) {
    val color = if (good) HcGreen else HcRed
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(4.dp)) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable private fun Module(title: String, subtitle: String = "", onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = HcPanel, shape = RoundedCornerShape(7.dp), modifier = modifier.border(1.dp, HcLine, RoundedCornerShape(7.dp))) {
        Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(), color = HcAmber, fontWeight = FontWeight.Black, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (subtitle.isNotBlank()) Text(subtitle, color = HcMuted, fontFamily = FontFamily.Monospace, fontSize = HcMetaSize,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = HcLine)
            content()
        }
    }
}

internal fun mergeEnrichedSpots(live: List<AndroidDXSpot>, enriched: List<AndroidDXSpot>): List<AndroidDXSpot> {
    if (live.isEmpty() || enriched.isEmpty()) return live
    val enrichedById = enriched.associateBy(AndroidDXSpot::id)
    return live.map { current -> enrichedById[current.id]?.let { resolved -> current.copy(
        country = resolved.country.ifBlank { current.country },
        continent = resolved.continent.ifBlank { current.continent },
        cqZone = resolved.cqZone.takeUnless { it == 0 } ?: current.cqZone,
        ituZone = resolved.ituZone.takeUnless { it == 0 } ?: current.ituZone,
        latitude = resolved.latitude.takeUnless { it == 0.0 } ?: current.latitude,
        longitude = resolved.longitude.takeUnless { it == 0.0 } ?: current.longitude,
    ) } ?: current }
}

@Composable private fun StationPanel(call: String, grid: String, radio: RadioState, wavelog: WavelogController,
    app: AppController, requestReceiveTune: (Long) -> Unit, openRadio: () -> Unit, modifier: Modifier) {
    Module("DE station", if (wavelog.logMode == LogMode.WAVELOG) "WAVELOG" else "LOCAL", openRadio, modifier) {
        KeyValue("CALL", call.ifBlank { "NOT SET" }, HcCyan)
        KeyValue("GRID", grid.ifBlank { "NOT SET" }, HcInk)
        KeyValue("RADIO", radio.model, if (radio.connected) HcGreen else HcMuted)
        KeyValue("VFO A", if (radio.connected && radio.frequencyHz > 0) "%.3f MHz".format(Locale.US, radio.frequencyHz / 1_000_000.0) else "NO LIVE STATE", if (radio.connected) HcAmber else HcMuted)
        KeyValue("MODE", if (radio.connected) radio.mode else "—", HcInk)
        KeyValue("POWER", if (radio.connected && radio.powerW > 0) "${radio.powerW} W" else "—", HcInk)
        KeyValue("TX SAFETY", if (app.transmitArmed) "ARMED" else "SAFE / RX", if (app.transmitArmed) HcRed else HcGreen)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FieldProfile.entries.forEach { profile ->
                FilterChip(app.fieldProfile == profile, { app.setProfile(profile) }, { Text(profile.name, fontSize = HcMetaSize) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            app.favoriteBands.forEach { value -> OutlinedButton({
                value.toDoubleOrNull()?.let { requestReceiveTune((it * 1_000_000).toLong()) }
            }, enabled = radio.connected, modifier = Modifier.heightIn(min = 48.dp)) { Text(value, fontFamily = FontFamily.Monospace, fontSize = HcMetaSize) } }
        }
    }
}

@Composable private fun WeatherPanel(weather: NeuralWeather, units: HamClockUnitSystem, modifier: Modifier) {
    Module("Local weather", weather.source, modifier = modifier) {
        if (!weather.available) EmptyLine(weather.error.ifBlank { "Weather awaiting station grid and internet" }) else {
            val temperature = weather.temperatureC?.let {
                if (units == HamClockUnitSystem.METRIC) "%.1f °C".format(Locale.US, it)
                else "%.1f °F".format(Locale.US, it * 9 / 5 + 32)
            } ?: "—"
            val wind = weather.windKmh?.let {
                if (units == HamClockUnitSystem.METRIC) "%.0f km/h".format(Locale.US, it)
                else "%.0f mph".format(Locale.US, it * .621371)
            } ?: "—"
            KeyValue("TEMP", temperature, HcCyan)
            KeyValue("HUMIDITY", weather.humidityPercent?.let { "$it%" } ?: "—", HcInk)
            KeyValue("PRESSURE", weather.pressureHpa?.let { "%.0f hPa".format(Locale.US, it) } ?: "—", HcInk)
            KeyValue("WIND", wind, HcInk)
            KeyValue("TROPO", weather.tropoIndex?.toString() ?: "—", when ((weather.tropoIndex ?: 0)) { in 6..Int.MAX_VALUE -> HcGreen; else -> HcMuted })
            KeyValue("DUCTING", weather.ductingRisk ?: "—", HcYellow)
        }
    }
}

@Composable private fun DxPanel(spots: List<AndroidDXSpot>, total: Int, status: String, filters: SpotFilters,
    openFilter: (SpotFilterDimension) -> Unit, openDx: () -> Unit, modifier: Modifier) {
    Module("DX cluster", "${spots.size}/$total · ${status.substringAfter("DX cluster ").uppercase()}", openDx, modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpotFilterDimension.entries.forEach { dimension ->
                val count = filters.count(dimension)
                OutlinedButton({ openFilter(dimension) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (count == 0) dimension.title else "${dimension.title} $count", maxLines = 1, fontSize = HcLabelSize)
                }
            }
        }
        if (total == 0) EmptyLine(status) else if (spots.isEmpty()) EmptyLine("No spots match the selected filters") else spots.take(6).forEach { spot ->
            Column(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spot.callsign, color = if (spot.watchlisted) HcYellow else HcCyan, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text("%.3f".format(Locale.US, spot.frequencyHz / 1_000_000.0), color = HcInk,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = HcRowSize,
                        textAlign = TextAlign.End)
                    Text("${spot.band} ${spot.mode}", color = HcMuted, fontSize = HcMetaSize, maxLines = 1,
                        textAlign = TextAlign.End)
                }
                Text("${spot.country.ifBlank { "Unknown" }} · heard by ${spot.spotter}", color = HcMuted,
                    fontSize = HcMetaSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun SolarPanel(features: FeatureController,
    celestial: HamClockFeed<HamClockSolarCelestialSnapshot>?, modifier: Modifier) {
    val snapshot = celestial?.value
    Module("Solar conditions", celestial?.let { "${it.state.name} · ${it.source.substringBefore(" · ")}" }
        ?: if (features.solar.valid) ageLabel(features.solar.observedEpoch) else "AWAITING NOAA", modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("SFI", features.solar.flux.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—", HcAmber)
            Metric("A", features.solar.aIndex.takeIf { features.solar.valid }?.roundToInt()?.toString() ?: "—",
                if (features.solar.aIndex >= 30) HcRed else HcYellow)
            Metric("KP", features.solar.kpIndex.takeIf { features.solar.valid }?.let { "%.1f".format(Locale.US, it) } ?: "—",
                if (features.solar.kpIndex >= 5) HcRed else HcGreen)
        }
        if (snapshot != null) {
            val moon = snapshot.moon
            val sun = snapshot.sunTimes
            val time = { epoch: Long? -> epoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—" }
            KeyValue("X-RAY", "${snapshot.xray.currentClass} now · ${snapshot.xray.peakClass} peak", HcCyan)
            KeyValue("MOON", "${moon.name.name.replace('_', ' ')} · ${(moon.illumination * 100).roundToInt()}%", HcInk)
            KeyValue("DAYLIGHT", "↑ ${time(sun.sunriseEpoch)} · ↓ ${time(sun.sunsetEpoch)}", HcAmber)
        }
        Text(celestial?.error?.takeIf(String::isNotBlank)
            ?: "NOAA GOES · NASA SDO · local astronomy · grayline live on map", color = HcMuted, fontSize = HcMetaSize,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun preferredDxTarget(spots: List<AndroidDXSpot>): AndroidDXSpot? = spots.maxWithOrNull(
    compareBy<AndroidDXSpot> { it.watchlisted }.thenBy { it.score }.thenBy { it.receivedEpoch }
)

@Composable private fun DxTargetPanel(target: HamClockResolvedTarget?, stationGrid: String, units: HamClockUnitSystem,
    editTarget: () -> Unit, modifier: Modifier) {
    val station = maidenheadCenter(stationGrid)
    val remote = target?.point
    val computedDistance = if (station != null && remote != null) distanceKm(station, remote).roundToInt() else null
    val computedBearing = if (station != null && remote != null) initialBearingDegrees(station, remote) else null
    Module("DX target", target?.callsign ?: "NO TARGET", editTarget, modifier) {
        if (target == null) EmptyLine("Set a manual target or wait for a ranked live DX spot") else {
            KeyValue("SOURCE", target.source.name, if (target.source == HamClockDxTargetSource.MANUAL) HcAmber else HcCyan)
            KeyValue("GRID", target.grid.ifBlank { "coordinate resolved" }, HcInk)
            KeyValue("PATH", listOfNotNull(computedDistance?.let { hamClockDistanceLabel(it.toDouble(), units) },
                computedBearing?.let { "%03d°".format(it) }).joinToString(" · ").ifBlank { "Awaiting station geometry" }, HcAmber)
            Text(target.detail, color = HcMuted, fontSize = HcMetaSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun VoacapPanel(target: HamClockResolvedTarget?, features: FeatureController,
    prediction: HamClockPropagationSnapshot, outlook: NeuralOutlookSnapshot, units: HamClockUnitSystem, modifier: Modifier) {
    val zone = when (target?.point?.longitude ?: 151.0) {
        in -170.0..-30.0 -> "AMERICAS"
        in -30.0..55.0 -> "EUROPE / AFRICA"
        in 55.0..150.0 -> "ASIA"
        else -> "OCEANIA"
    }
    val rows = prediction.bands.takeIf { it.isNotEmpty() }?.take(6)
    Module("Propagation", target?.let { "${it.callsign} · ${it.source.name} · ${prediction.model.ifBlank { zone }}" } ?: zone, modifier = modifier) {
        Text("Remote propagation estimate", color = HcAmber, fontWeight = FontWeight.Bold)
        (rows?.map { it.band to it.reliability } ?: listOf("40m", "20m", "15m", "10m").map { band ->
            band to hamClockReliability(band, zone, features.solar.flux, features.solar.kpIndex)
        }).forEach { (band, reliability) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(band, color = HcMuted, fontSize = 15.sp, modifier = Modifier.width(34.dp))
                Box(Modifier.weight(1f).height(7.dp).background(HcRaised, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(reliability / 100f).height(7.dp).background(if (reliability >= 70) HcGreen else HcAmber, RoundedCornerShape(4.dp)))
                }
                Text("$reliability%", color = HcInk, fontFamily = FontFamily.Monospace, modifier = Modifier.width(42.dp))
            }
        }
        val limits = listOfNotNull(prediction.distanceKm?.let { "PATH ${hamClockDistanceLabel(it.toDouble(), units)}" },
            prediction.mufMHz?.let { "MUF %.1f".format(Locale.US, it) },
            prediction.lufMHz?.let { "LUF %.1f MHz".format(Locale.US, it) }).joinToString(" · ")
        if (limits.isNotBlank()) Text(limits, color = HcCyan, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        Text(if (prediction.authoritative) "ITU-R P.533 path result · ${prediction.source}"
            else "ESTIMATE · ${prediction.source.ifBlank { prediction.error.ifBlank { "live SFI/Kp regional fallback" } }}",
            color = if (prediction.authoritative) HcGreen else HcMuted, fontSize = 14.sp, maxLines = 2)
        HorizontalDivider(color = HcLine)
        Text("Observed empirical outlook", color = HcAmber, fontWeight = FontWeight.Bold)
        outlook.topBands.firstOrNull()?.let { row ->
            Text("${row.band} · ${row.label.name.replace('_', ' ')} · ${row.confidence} · ${row.sourceCount} sources",
                color = HcInk, fontSize = 14.sp)
        } ?: Text("Insufficient evidence", color = HcMuted, fontSize = 14.sp)
        Text("P.533 local engine unavailable · LICENSE_BLOCKED", color = HcMuted, fontSize = HcMetaSize)
    }
}

private fun hamClockReliability(band: String, zone: String, sfi: Float, kp: Float): Int {
    val base = mapOf("80m" to 58, "40m" to 70, "30m" to 76, "20m" to 82, "17m" to 72,
        "15m" to 64, "12m" to 50, "10m" to 42)[band] ?: 50
    val flux = ((sfi - 80) / 2).roundToInt().coerceIn(-15, 25)
    val storm = (kp * 5).roundToInt()
    val distance = when (zone) { "EUROPE" -> 8; "AFRICA" -> 2; "ASIA" -> -4; "AMERICAS" -> -7; else -> -8 }
    return (base + flux - storm + distance).coerceIn(0, 100)
}

@Composable private fun DxpeditionPanel(feed: HamClockFeed<List<HamClockDxpedition>>?, openDx: () -> Unit, modifier: Modifier) {
    val rows = feed?.value.orEmpty()
    Module("DXpeditions", feed?.let { "NG3K · ${it.state.name}" } ?: "NG3K", openDx, modifier) {
        if (rows.isEmpty()) EmptyLine(feed?.error?.ifBlank { "No current DXpedition entries" } ?: "DXpedition feed has not refreshed")
        else rows.take(7).forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(item.callsign, color = HcYellow, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black, fontSize = 19.sp, modifier = Modifier.width(70.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.entity.ifBlank { item.information }, item.dateText, item.modes.take(3).joinToString())
                    .filter(String::isNotBlank).joinToString(" · "), color = HcInk, fontSize = HcMetaSize,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun ContestsPanel(feed: HamClockFeed<List<HamClockContest>>?, openOperations: () -> Unit, modifier: Modifier) {
    val rows = feed?.value.orEmpty()
    Module("Contests", feed?.let { "WA7BNM · ${it.state.name}" } ?: "WA7BNM", openOperations, modifier) {
        if (rows.isEmpty()) EmptyLine(feed?.error?.ifBlank { "No active or upcoming contests" } ?: "Contest calendar has not refreshed")
        else rows.take(5).forEach { contest ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(contest.mode.uppercase(), color = if (contest.status.name == "ACTIVE") HcGreen else HcAmber,
                    fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(contest.name, color = HcInk, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val start = Instant.ofEpochSecond(contest.startEpoch).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("dd MMM HH:mm 'UTC'"))
                    Text(start, color = HcMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable private fun SignalPanel(signal: NeuralMySignal, preference: HamClockPskPreference,
    refresh: () -> Unit, clear: () -> Unit, openDx: () -> Unit, modifier: Modifier) {
    val reports = signal.reports
    Module("PSK Reporter", "${preference.direction.name.replace('_', ' ')} · ↑${signal.beingHeardCount} ↓${signal.hearingCount}", openDx, modifier) {
        Text("${signal.sourceState.name} · Being Heard ${signal.beingHeardState.name} (${signal.beingHeardCount}) · Hearing ${signal.hearingState.name} (${signal.hearingCount})",
            color = if (signal.sourceState == NeuralSignalSourceState.DEGRADED) HcYellow else HcMuted,
            fontSize = HcMetaSize, maxLines = 2)
        Text("Remote station shown for each direction · ${ageLabel(signal.fetchedEpoch)}", color = HcMuted,
            fontSize = HcMetaSize, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(refresh) { Text("REFRESH") }
            TextButton(clear) { Text("CLEAR DISPLAY") }
        }
        if (!signal.available) EmptyLine(signal.error.ifBlank { "PSK Reporter unavailable or no station callsign set" })
        else if (reports.isEmpty()) EmptyLine("PSK Reporter returned no recent reception reports") else reports.take(3).forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                Text("${if(row.direction == SignalDirection.BEING_HEARD) "↑" else "↓"} ${row.callsign}${if(row.mutual)" ⇄" else ""}",
                    color = if (row.mutual) HcYellow else HcGreen, fontFamily = FontFamily.Monospace,
                    fontSize = HcRowSize, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${row.band} ${row.mode}", color = HcInk, fontSize = HcMetaSize, fontFamily = FontFamily.Monospace)
                }
                Text("${if(row.direction == SignalDirection.BEING_HEARD) "received by" else "transmitted by"} remote · ${row.gridOrDash()} · " +
                    listOfNotNull(row.snr?.let { "$it dB" }, row.distanceKm?.let { "${it} km" }).joinToString(" · ").ifBlank { "no SNR/distance" },
                    color = HcMuted, fontSize = HcMetaSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun SignalReport.gridOrDash() = locator.ifBlank { "—" }

@Composable private fun DxNewsPanel(rows: List<DxNewsItem>, sources: List<BriefingSource>, openDx: () -> Unit, modifier: Modifier) {
    val current = rows.firstOrNull()
    val contributing = rows.mapTo(linkedSetOf(), DxNewsItem::sourceId)
    val sourceTruth = sources.filter { it.id in contributing }.joinToString(" · ") { "${it.name} ${it.state.name}" }
        .ifBlank { "NO CURRENT MERGED SOURCE" }
    Module("DX News", sourceTruth, openDx, modifier) {
        if (current == null) EmptyLine(sources.map(DxNewsSource::error).firstOrNull(String::isNotBlank)
            ?: "No current or last-good DX News item") else {
            Text(current.title, color = HcCyan, fontWeight = FontWeight.Black, fontSize = HcRowSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${current.sourceLabel} · ${ageLabel(current.publishedEpoch)}${if(sources.firstOrNull{it.id==current.sourceId}?.stale==true)" · STALE" else ""}",
                color = HcMuted, fontSize = 15.sp)
            Text(listOf(current.callsigns.joinToString(), current.entity).filter(String::isNotBlank).joinToString(" · ").ifBlank { current.summary },
                color = HcInk, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Touch to open News/Briefing · no automatic tuning", color = HcMuted, fontSize = 14.sp)
        }
    }
}

@Composable private fun PortablePanel(portable: PortableController, openPortable: () -> Unit, modifier: Modifier) {
    val opportunities = portable.rankedOpportunities
    val potaCount = portable.pota.spots.size
    val wwffCount = portable.wwffSpots.size
    Module("Portable activators", "POTA $potaCount · WWFF $wwffCount", openPortable, modifier) {
        val potaStatus = portable.providerStatus(PortableProgram.POTA)
        val wwffStatus = portable.providerStatus(PortableProgram.WWFF)
        if (opportunities.isEmpty()) EmptyLine(listOf(potaStatus, wwffStatus).joinToString(" · ") {
            it.error.ifBlank { "${it.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · ${it.count} spots" }
        }) else opportunities.take(4).forEach { opportunity ->
            val spot = opportunity.spot
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spot.callsign, color = HcYellow, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text(spot.references.joinToString { it.code }, color = HcMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("%.3f".format(Locale.US, spot.frequencyHz / 1_000_000.0), color = HcInk, fontFamily = FontFamily.Monospace)
            }
        }
        Text("SOTA live feed unavailable", color = HcMuted, fontSize = 14.sp)
    }
}

@Composable private fun BandConditionsPanel(activity: Map<String, Int>, openProgress: () -> Unit, modifier: Modifier) {
    Module("Band activity", "NEURAL DX WINDOW", openProgress, modifier) {
        val bands = listOf("80m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m")
        bands.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            row.forEach { band ->
                val count = activity[band] ?: 0
                Surface(color = (if (count > 0) HcGreen else HcRaised).copy(alpha = if (count > 0) .18f else 1f),
                    shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(band, color = if (count > 0) HcGreen else HcMuted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(count.toString(), color = HcInk, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    }
                }
            }
        } }
    }
}

@Composable private fun SatellitePanel(controller: SatelliteOperationsController, openSatellites: () -> Unit, modifier: Modifier) {
    val next = controller.nextFavouritePass
    val state = controller.elements.metadata.state.name.replace('_', ' ')
    Module("Satellite operations", state, openSatellites, modifier) {
        if (next == null) {
            EmptyLine(controller.message)
        } else {
            val now = Instant.now().epochSecond
            val untilAos = (next.pass.aos - now).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(next.satellite.name, color = HcCyan, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "AOS ${Instant.ofEpochSecond(next.pass.aos).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} · " +
                            "T− ${untilAos / 3600}h ${(untilAos % 3600) / 60}m · ${next.pass.maximumElevationDeg.roundToInt()}°",
                        color = HcInk,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text("OPEN", color = HcGreen, fontWeight = FontWeight.Bold)
            }
            Text("Favourite-first · local pinned SGP4 · tap for passes, track and sky plot", color = HcMuted)
        }
    }
}

@Composable private fun PropagationStrip(neuralDx: NeuralDxController, modifier: Modifier) {
    Module("Propagation intelligence", neuralDx.status, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("OPPORTUNITIES", neuralDx.currentOpportunities.size.toString(), HcCyan)
            Metric("WSPR HF", neuralDx.wspr.hf.size.toString(), HcGreen)
            Metric("WSPR VHF", neuralDx.wspr.vhf.size.toString(), HcGreen)
            Metric("WORLD CELLS", neuralDx.world.size.toString(), HcYellow)
            val next = neuralDx.currentOpportunities.maxByOrNull { it.priority }
            Column(Modifier.weight(1f)) {
                Text(next?.let { "${it.callsign} · ${it.band} ${it.mode} · P ${it.priority} · E ${it.evidenceScore}" } ?: "No current live opportunity",
                    color = HcInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(next?.reason ?: "Live heuristic ranking only; not a probability or forecast", color = HcMuted, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun KeyValue(key: String, value: String, color: Color) = Row(Modifier.fillMaxWidth()) {
    Text(key, color = HcMuted, fontSize = HcLabelSize, fontWeight = FontWeight.Bold,
        letterSpacing = .15.sp, modifier = Modifier.weight(1f))
    Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 19.sp,
        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun EmptyLine(text: String) {
    Box(Modifier.fillMaxWidth().background(HcRaised, RoundedCornerShape(4.dp)).padding(10.dp)) {
        Text(text, color = HcMuted, fontSize = 16.sp)
    }
}

private fun ageLabel(epoch: Long): String {
    val seconds = (Instant.now().epochSecond - epoch).coerceAtLeast(0)
    return when { seconds < 60 -> "NOW"; seconds < 3600 -> "${seconds / 60}m AGO"; else -> "${seconds / 3600}h AGO" }
}
