package app.shackcq.mobile

import app.shackcq.mobile.groupsio.GroupsIoController
import app.shackcq.mobile.groupsio.GroupsIoScreen
import app.shackcq.mobile.groupsio.GroupsIoSettingsPanel
import app.shackcq.mobile.groupsio.GroupsIoNewMessageAlert
import app.shackcq.mobile.groupsio.groupsIoDestinationVisible
import app.shackcq.mobile.keyer.*
import app.shackcq.mobile.dxchaser.DxChaserActionType
import app.shackcq.mobile.bandmap.*
import app.shackcq.mobile.radio.hamlib.HamlibConnectionController
import app.shackcq.mobile.radio.hamlib.HamlibModelDescriptor
import app.shackcq.mobile.rotator.*

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent as AndroidKeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import app.shackcq.mobile.hamclock.*

class MainActivity : ComponentActivity() {
    private lateinit var controlSurfaces: ControlSurfaceController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StabilityDiagnostics.install(this)
        controlSurfaces = ControlSurfaceController(this)
        setContent { ShackCQTheme { ShackCQApp(controlSurfaces) } }
    }
    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent): Boolean =
        controlSurfaces.handleKeyEvent(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent): Boolean =
        controlSurfaces.handleKeyEvent(event) || super.onKeyUp(keyCode, event)
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        controlSurfaces.handleMotionEvent(event) || super.onGenericMotionEvent(event)
    override fun onDestroy() { controlSurfaces.close(); super.onDestroy() }
}

private val Chassis = Color(0xFF111519)
private val Panel = Color(0xFF1B2228)
private val Raised = Color(0xFF283139)
private val Ink = Color(0xFFF4F0E7)
private val Muted = Color(0xFFA5ADB2)
private val Amber = Color(0xFFE9A72B)
private val Hold = Color(0xFFF4C94E)
private val Healthy = Color(0xFF42C77B)
private val Danger = Color(0xFFE4544D)

@Composable private fun ShackCQTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Amber, onPrimary = Color(0xFF201708), background = Chassis,
        secondary = Hold, onSecondary = Color(0xFF211B05), tertiary = Healthy, onTertiary = Color(0xFF071B10),
        surface = Panel, surfaceVariant = Raised, onSurfaceVariant = Muted,
        outline = Color(0xFF66727B), outlineVariant = Color(0xFF3D4850),
        error = Danger, onError = Color.White, onBackground = Ink, onSurface = Ink),
    content = content,
)

private enum class Destination(val label: String) {
    HOME("Home"), RADIO("Radio"), REMOTE("Remote"), DIGI("Digi"), CONTEST("Contest"), BAND_MAPS("Band Maps"), PANADAPTER("Panadapter"), EQ("EQ"), LOGBOOK("Logbook"), PROGRESS("Intelligence"), SYNC("Sync"), PRESETS("Presets"), DX("DX"), PORTABLE("Portable"), OPERATIONS("Operations"), ROTATOR("Rotator"), GROUPS_IO("Groups.io"), SETTINGS("Settings")
}
private enum class SettingsSection(val label: String) {
    RADIO("Radio"), LOG("Log"), CLUSTER("Cluster"), MACROS("Macros"), ALERTS("Alerts"),
    AUDIO("Audio"), CONTROLS("Controls"), DIGI("Digi"), CONTEST("Contest"), BAND_MAPS("Band Maps"), ROTATOR("Rotator"), INTEGRATIONS("Integrations"), COLOURS("Colours"),
    HEALTH("Health"), DIAG("Diag"), ABOUT("About")
}
private enum class QsoEditorTab(val label: String) { QSO("QSO"), STATION("Station"), GENERAL("General"), NOTES("Notes"), QSL("QSL") }

internal data class HomeReceiveTuneReview(
    val frequencyHz: Long,
    val mode: String?,
    val source: String,
    val reason: String,
)

internal data class HomeReceiveTuneDecision(
    val pending: HomeReceiveTuneReview?,
    val dispatch: HomeReceiveTuneReview?,
)

internal fun decideHomeReceiveTune(review: HomeReceiveTuneReview, confirm: Boolean): HomeReceiveTuneDecision =
    HomeReceiveTuneDecision(pending = null, dispatch = review.takeIf { confirm })

internal data class GeneralRadioCommand(
    val raw: String,
    val frequencyHz: Long?,
    val mode: String?,
)

internal fun parseGeneralRadioCommand(raw: String): GeneralRadioCommand {
    val command = raw.uppercase(Locale.US)
    val frequency = Regex("FA(\\d{11});").find(command)?.groupValues?.get(1)?.toLongOrNull()
    val mode = Regex("MD([1-5]);").find(command)?.groupValues?.get(1)?.let { code ->
        mapOf("1" to "LSB", "2" to "USB", "3" to "CW", "4" to "FM", "5" to "AM")[code]
    }
    return GeneralRadioCommand(raw, frequency, mode)
}

@Composable private fun ShackCQApp(controlSurfaces: ControlSurfaceController) {
    val context = LocalContext.current
    val core = remember {
        NativeHandleOwner(
            NativeCore.create().also { check(it != 0L) { "Native CAT parser unavailable" } },
            NativeCore::destroy,
        )
    }
    val transport = remember { UsbRadioTransport(context) }
    val database = remember { QsoDatabase.shared(context) }
    LaunchedEffect(database) {
        StabilityDiagnostics.refreshDatabaseFacts(database)
        while (withContext(Dispatchers.IO) { database.backfillProjectionBatch() }) {
            StabilityDiagnostics.refreshDatabaseFacts(database)
            delay(25)
        }
        while (withContext(Dispatchers.IO) { database.repairProjectionGridBatch() } > 0) delay(25)
        StabilityDiagnostics.refreshDatabaseFacts(database)
    }
    val mutations = remember { QsoMutationCoordinator(database) }
    val progress = remember { ProgressController(context, database) }
    val publicProviders = remember { app.shackcq.mobile.hamclock.HamClockPublicProviders(File(context.filesDir, "hamclock-public")) }
    val hamClockSettings = remember { HamClockSettingsCoordinator(context) }
    val operations = remember { OperationsController(context, publicProviders, database) }
    val portable = remember { PortableController(context, database) }
    val activation = remember { PotaActivationController(context, database) }
    val features = remember { FeatureController(context) }
    val app = remember { AppController(context) }
    val remoteRuntime = remember { RemoteRuntimeState() }
    val remoteFactory = remember { RemoteStationBackendFactory(app::remoteStation, remoteRuntime) }
    val tciRuntime = remember { TciRuntimeState() }
    val tciTransmit = remember { TciTransmitAuthority() }
    val tciFactory = remember { AndroidTciBackendFactory(tciRuntime, tciTransmit) }
    val physicalAuthority = remember { PhysicalDeviceAuthority() }
    val platformTransport = remember { UsbRadioTransport(context, "shackcq-radio-platform-usb") }
    val androidRadioFactory = remember { AndroidRadioBackendFactory(platformTransport) }
    val radioPlatform = remember {
        RadioPlatformController(
            mapOf(
                RadioBackendKind.NATIVE_QMX to androidRadioFactory,
                RadioBackendKind.NATIVE_RGO_ONE to androidRadioFactory,
                RadioBackendKind.HAMLIB_EMBEDDED to androidRadioFactory,
                RadioBackendKind.HAMLIB_NETWORK to androidRadioFactory,
                RadioBackendKind.NATIVE_TCI to tciFactory,
                RadioBackendKind.REMOTE_STATION to remoteFactory,
            ),
            devices = physicalAuthority,
            disarmTransmitWorkflows = app::disarmAll,
        )
    }
    val rotator = remember { AndroidRotatorRuntime(context, physicalAuthority) }
    val hamlibRegistry = remember { HamlibConnectionController() }
    val hamlibModels = remember { runCatching { hamlibRegistry.registry.models }.getOrDefault(emptyList()) }
    val selectedProfile = app.selectedRadioProfile
    var pendingRemoteAutoConnect by remember { mutableStateOf<RadioProfileId?>(null) }
    val integratedRadioSelected = selectedProfile.backendKind in setOf(
        RadioBackendKind.NATIVE_QMX, RadioBackendKind.NATIVE_RGO_ONE,
        RadioBackendKind.HAMLIB_EMBEDDED, RadioBackendKind.HAMLIB_NETWORK, RadioBackendKind.NATIVE_TCI, RadioBackendKind.REMOTE_STATION,
    )
    val bandMapStore = remember { BandMapStateStore(context) }
    val bandMaps = remember { BandMapController(bandMapStore.load(), bandMapStore::save) }
    val keyerProfiles = remember { KeyerProfileStore(context, app.macroLabels.toList(), app.macroTexts.toList(), app.voiceMacroLabels.toList(), app.cqRepeatSeconds) }
    val wavelog = remember { WavelogController(context, database) }
    val logbook = remember(database) { LogbookController(LogbookRepository(database)) }
    DisposableEffect(logbook) { onDispose(logbook::close) }
    LaunchedEffect(logbook, wavelog.logMode, wavelog.stationId) {
        logbook.apply(LogbookFilter(), wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG })
    }
    val operatingContext = remember { OperatingContextAuthority() }
    val keyerPort = remember { arrayOfNulls<KeyerDispatchPort>(1) }
    val contest = remember { ContestRuntime(context, database, mutations, keyerProfiles,
        { keyerPort[0] }, { operatingContext.snapshot }) }
    val neuralDx = remember {
        NeuralDxController(context, database, publicProviders, operations.satellites,
            wavelog.selectedStation?.grid?.ifBlank { null } ?: app.stationGrid)
    }
    val wavelogNative = remember { WavelogNativeController(database, wavelog, mutations) }
    val flex = remember { FlexRadioController(context, { app.preferredFlexStation }, app::savePreferredFlexStation) { app.manualFlexIp } }
    val syncHub = remember { SyncHubController(context, database, { wavelog.logMode },
        { operatingContext.snapshot.stationGrid.value.ifBlank { app.stationGrid } }) }
    val callbook = remember { CallbookController(context) { app.stationCallsign } }
    val cty = remember { CtyController(context) }
    val audio = remember { AudioMonitorController(context) }
    val tciRxAudio = remember { TciRxAudioController(context, audio) }
    flex.attachAudioRoutes(audio)
    val cwDecoder = remember { CwDecodeBuffer() }
    var kxRadio by remember {
        mutableStateOf(core.withHandle { NativeCore.parseState(NativeCore.state(it)) } ?: RadioState())
    }
    var radio by remember { mutableStateOf(kxRadio) }
    var platformSnapshot by remember { mutableStateOf(RadioRuntimeSnapshot()) }
    val voiceStore = remember { VoiceMacroStore(context) { app.voiceMacroLabels.toList() } }
    flex.attachVoiceMacroStore(voiceStore)
    val voiceAudio = remember { VoiceMacroAudioController(context, audio, voiceStore) }
    val eqAudio = remember { EqAudioController(context, audio) }
    val eqProfiles = remember { EqProfileStore(context) }
    val eqStudio = remember { EqStudioController(transport, { radio }, eqAudio, eqProfiles) }
    val groupsIo = remember { GroupsIoController(context) }
    val connectivity = remember { context.getSystemService(ConnectivityManager::class.java) }
    var networkAvailable by remember { mutableStateOf(connectivity.activeNetwork != null) }
    var foreground by remember { mutableStateOf(true) }
    var foregroundGeneration by remember { mutableLongStateOf(1L) }
    LaunchedEffect(groupsIo.enabled, groupsIo.connected, groupsIo.syncSettings.refreshOnOpen,
        groupsIo.syncSettings.foregroundRefreshMinutes, foreground) {
        groupsIo.loadCachedGroups()
        if (!foreground || !groupsIo.enabled) return@LaunchedEffect
        if (groupsIo.syncSettings.refreshOnOpen) groupsIo.maybeForegroundRefresh(force = true)
        while (isActive && foreground) {
            delay(groupsIo.syncSettings.foregroundRefreshMinutes * 60_000L)
            groupsIo.maybeForegroundRefresh()
        }
    }
    val voiceTx = remember { VoiceMacroTransmitController(context, transport, audio, voiceStore, app,
        radioState = { radio }, foreground = { foreground }, audioOperationIdle = { voiceAudio.state is VoiceAudioState.Idle }, onFrames = { frames ->
            core.withHandle { handle ->
                NativeCore.feed(handle, frames)
                NativeCore.parseState(NativeCore.state(handle))
            }?.let { parsed ->
                kxRadio = parsed.copy(cwDecodedText = cwDecoder.text)
                if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) radio = kxRadio
            }
        }, tciAuthority = tciTransmit,
        tciSelected = { app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_TCI }) }
    var usbDetail by remember { mutableStateOf("No USB CAT adapter opened") }
    val navigationPrefs = remember { context.getSharedPreferences("navigation", android.content.Context.MODE_PRIVATE) }
    var destination by rememberSaveable {
        mutableStateOf(runCatching {
            Destination.valueOf(navigationPrefs.getString("destination", Destination.HOME.name).orEmpty())
        }.getOrDefault(Destination.HOME))
    }
    val eqVisible = selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT &&
        eqDestinationVisible(app.eqVisibilityPolicy, app.radioFamily)
    var integratedDigiPage by rememberSaveable { mutableStateOf(IntegratedDigiPage.DIGI) }
    LaunchedEffect(destination) { navigationPrefs.edit().putString("destination", destination.name).apply() }
    LaunchedEffect(groupsIo.enabled, destination) {
        if (!groupsIo.enabled && destination == Destination.GROUPS_IO) destination = Destination.SETTINGS
    }
    LaunchedEffect(app.rotatorEnabled, destination) {
        if (!app.rotatorEnabled && destination == Destination.ROTATOR) destination = Destination.SETTINGS
    }
    LaunchedEffect(bandMaps.settings.enabled, bandMaps.settings.navigationVisible, destination) {
        if ((!bandMaps.settings.enabled || !bandMaps.settings.navigationVisible) && destination == Destination.BAND_MAPS) destination = Destination.SETTINGS
    }
    LaunchedEffect(eqVisible, app.contestEnabled, destination) {
        if (!eqVisible && destination == Destination.EQ) destination = Destination.RADIO
        if (!app.contestEnabled) {
            contest.pause("DESTINATION DISABLED")
            if (destination == Destination.CONTEST) destination = Destination.SETTINGS
        }
    }
    var pendingPortableDraft by remember { mutableStateOf<PortableLogDraft?>(null) }
    var pendingRisk by remember { mutableStateOf<String?>(null) }
    var pendingHomeReceiveTune by remember { mutableStateOf<HomeReceiveTuneReview?>(null) }
    var pendingHomeQsoId by remember { mutableStateOf<String?>(null) }
    var pendingCallbookRecord by remember { mutableStateOf<AndroidCallbookRecord?>(null) }
    var pendingVoiceSlot by remember { mutableStateOf<Int?>(null) }
    var voiceArmedMode by remember { mutableStateOf<String?>(null) }
    fun dispatchWorkspaceAction(action: WorkspaceAction) {
        val route = WorkspaceActionRouter.resolve(action, operatingContext.snapshot.generation) ?: return
        destination = when (action.destination) {
            WorkspaceDestination.HOME -> Destination.HOME
            WorkspaceDestination.RADIO -> Destination.RADIO
            WorkspaceDestination.DIGI -> Destination.DIGI
            WorkspaceDestination.DX_CHASER -> Destination.DIGI
            WorkspaceDestination.CONTEST -> if (app.contestEnabled) Destination.CONTEST else Destination.SETTINGS
            WorkspaceDestination.BAND_MAPS -> Destination.BAND_MAPS
            WorkspaceDestination.PANADAPTER -> Destination.PANADAPTER
            WorkspaceDestination.EQ -> if (eqVisible) Destination.EQ else Destination.RADIO
            WorkspaceDestination.LOGBOOK -> Destination.LOGBOOK
            WorkspaceDestination.PROGRESS -> Destination.PROGRESS
            WorkspaceDestination.SYNC -> Destination.SYNC
            WorkspaceDestination.PRESETS -> Destination.PRESETS
            WorkspaceDestination.DX -> Destination.DX
            WorkspaceDestination.CALLBOOK -> Destination.RADIO
            WorkspaceDestination.PORTABLE -> Destination.PORTABLE
            WorkspaceDestination.OPERATIONS -> Destination.OPERATIONS
            WorkspaceDestination.SATELLITE -> Destination.OPERATIONS
            WorkspaceDestination.ROTATOR -> if (app.rotatorEnabled) Destination.ROTATOR else Destination.SETTINGS
            WorkspaceDestination.GROUPS_IO -> Destination.GROUPS_IO
            WorkspaceDestination.SETTINGS -> Destination.SETTINGS
        }
        if (action.destination == WorkspaceDestination.DX_CHASER) integratedDigiPage = IntegratedDigiPage.DX_CHASER
        if (action.destination == WorkspaceDestination.BAND_MAPS) bandMaps.prepare(action)
        if (action.destination == WorkspaceDestination.CALLBOOK && action.callsign.isNotBlank())
            pendingCallbookRecord = callbookFallbackRecord(action.callsign, cty)
        action.qsoId.takeIf(String::isNotBlank)?.let { pendingHomeQsoId = it }
        action.groupsIoGroupId?.let(groupsIo::selectGroup)
        action.groupsIoTopicId?.let(groupsIo::selectTopic)
        route.receiveReview?.let { pendingHomeReceiveTune = it }
    }
    val digi = remember { DigiController(context, audio, transport, flex, { app.radioFamily },
        { operatingContext.snapshot.stationCallsign.value.ifBlank { app.stationCallsign } },
        { operatingContext.snapshot.stationGrid.value.ifBlank { app.stationGrid } }, DigiDependencies(
            database = database, mutations = mutations, cty = cty, radioState = { radio },
            stationProfile = { operatingContext.snapshot.stationProfileId.value },
            stationLocation = { operatingContext.snapshot.stationLocation.value },
            operatorCallsign = { operatingContext.snapshot.operatorCallsign.value.ifBlank { app.stationCallsign } },
            activationContext = { operatingContext.snapshot.activationProgram.value to operatingContext.snapshot.activationSession.value },
            needsByCallsign = { progress.snapshot.needs.mapNotNull { need ->
                (need.dxSpot?.callsign ?: need.portableSpot?.callsign ?: need.title).takeIf(String::isNotBlank)?.uppercase()?.let { it to need.reasons }
            }.groupBy({ it.first }, { it.second }).mapValues { entry -> entry.value.flatten().distinct() } },
            liveSpots = { features.liveSpots },
            onOpenLogbook = { call -> progress.requestLogbook(logbookFilterForDimension("callsign", call));
                dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.LOGBOOK, callsign = call,
                    source = "Digi decode", reason = "Open exact callsign history")) },
            onOpenDx = { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DX,
                source = "Digi decode", reason = "Open DX details")) },
            onOpenCallbook = { call ->
                dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.CALLBOOK, callsign = call,
                    source = "Digi decode", reason = "Open exact callbook identity")) },
            nextIssPass = {
                operations.satellites.passes.firstOrNull { row -> row.satellite.name.contains("ISS", true) }?.let { row ->
                    DigiIssPass(row.satellite.name, row.pass.aos, row.pass.los, row.pass.maximumElevationDeg)
                }
            },
            requestIssReceiveReview = {
                pendingHomeReceiveTune = HomeReceiveTuneReview(145_800_000L, "FM", "ISS SSTV receive-only session",
                    "Review the 145.800 MHz downlink. This does not transmit or tune without confirmation.")
            },
        ), transmitEligible = {
            app.selectedRadioProfile.backendKind in setOf(
                RadioBackendKind.NATIVE_ELECRAFT, RadioBackendKind.NATIVE_FLEX, RadioBackendKind.NATIVE_TCI)
        }, tciAuthority = tciTransmit,
        tciSelected = { app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_TCI }) }
    LaunchedEffect(foreground) { digi.onForegroundChanged(foreground) }
    LaunchedEffect(foreground) { if (!foreground) rotator.background() }
    LaunchedEffect(rotator.state?.profileId, rotator.state?.connected, foreground) {
        while (foreground && rotator.state?.connected == true) {
            delay(rotator.profiles.firstOrNull { it.id == rotator.state?.profileId }?.pollIntervalMs?.toLong() ?: 1_000L)
            rotator.poll()
        }
    }
    LaunchedEffect(radio.identity, radio.frequencyHz, radio.connected) { digi.onRadioStateChanged(radio) }
    val contextGeneration = remember(app.stationCallsign, app.stationGrid, app.activationProgram, app.activationReference,
        wavelog.stationId, wavelog.selectedStation, activation.session, radio, foreground, networkAvailable,
        database.changeToken(), cty.dataRevision, neuralDx.lastRefreshEpoch, features.liveSpots.size,
        contest.activeSession.id, contest.activeSession.state, contest.activeSession.role,
        selectedProfile.id, platformSnapshot.capabilities, rotator.state, rotator.automation) {
        operatingContext.beginUpdate()
    }
    val contextSnapshot = remember(contextGeneration) {
        val selectedStation = wavelog.selectedStation
        val session = activation.session
        val stationCall = selectedStation?.callsign?.ifBlank { null } ?: app.stationCallsign.ifBlank { features.clusterCallsign }
        val stationGrid = selectedStation?.grid?.ifBlank { null } ?: app.stationGrid
        OperatingContextSnapshot(
            generation = contextGeneration,
            stationProfileId = ContextValue(wavelog.stationId, "WavelogController.stationId"),
            stationCallsign = ContextValue(stationCall, "Wavelog selected station -> AppController fallback"),
            operatorCallsign = ContextValue(session?.setup?.operatorCallsign.orEmpty().ifBlank { app.stationCallsign }, "PotaActivationController -> AppController fallback"),
            stationGrid = ContextValue(stationGrid, "Wavelog selected station -> AppController fallback"),
            stationLocation = ContextValue(selectedStation?.city.orEmpty(), "WavelogController.selectedStation"),
            wavelogBindingId = ContextValue(wavelog.stationId, "WavelogController"),
            wavelogRemoteStationId = ContextValue(wavelog.stationId, "WavelogController"),
            activationProgram = ContextValue(if (session != null) "POTA" else app.activationProgram, "PotaActivationController -> AppController fallback"),
            activationReference = ContextValue(session?.setup?.primaryReference ?: app.activationReference, "PotaActivationController -> AppController fallback"),
            activationSession = ContextValue(session?.id.orEmpty(), "PotaActivationController"),
            selectedContestId = ContextValue(contest.activeSession.id.value, "ContestRuntime"),
            radioProfileId = ContextValue(selectedProfile.id.value, "AppController"),
            radioBackendKind = ContextValue(selectedProfile.backendKind.name, "AppController"),
            radioCapabilityRevision = ContextValue(platformSnapshot.capabilities.hashCode().toString(), "RadioPlatformController"),
            radioFamily = ContextValue(app.radioFamily.name, "AppController compatibility"),
            radioModel = ContextValue(radio.model, "radio backend"),
            radioIdentity = ContextValue(radio.identity, "radio backend"),
            connected = ContextValue(radio.connected, "radio backend"),
            receiveFrequencyHz = ContextValue(radio.effectiveRxHz.takeIf { it > 0 } ?: radio.frequencyHz, "radio backend"),
            transmitFrequencyHz = ContextValue((radio.effectiveTxHz.takeIf { it > 0 } ?: radio.frequencyBHz.takeIf { it > 0 }), "radio backend"),
            receiveBandwidthHz = ContextValue(radio.bandwidthHz, "radio backend"),
            band = ContextValue(bandForFrequency(radio.frequencyHz), "QsoDatabase.bandForFrequency"),
            mode = ContextValue(radio.mode, "radio backend"),
            submode = ContextValue(radio.dataSubmode.takeIf { it >= 0 }?.toString().orEmpty(), "radio backend"),
            split = ContextValue(radio.split, "radio backend"),
            radioState = ContextValue(if (radio.transmitting) "TX" else "RX", "radio backend"),
            rotatorProfileId = ContextValue(rotator.state?.profileId.orEmpty(), "RotatorPlatformController"),
            rotatorConnected = ContextValue(rotator.state?.connected == true, "RotatorPlatformController"),
            rotatorAzimuthDeg = ContextValue(rotator.state?.azimuthDeg, "RotatorPlatformController"),
            rotatorElevationDeg = ContextValue(rotator.state?.elevationDeg, "RotatorPlatformController"),
            rotatorMovement = ContextValue(rotator.state?.movement?.name ?: "UNKNOWN", "RotatorPlatformController"),
            rotatorAutomationArmed = ContextValue(rotator.automation.armed, "RotatorPlatformController"),
            networkAvailable = ContextValue(networkAvailable, "ConnectivityManager"),
            foreground = ContextValue(foreground, "Android lifecycle"),
            qsoDatabaseRevision = ContextValue(database.changeToken(), "QsoDatabase"),
            providerGeneration = ContextValue(cty.dataRevision + neuralDx.lastRefreshEpoch + features.liveSpots.size, "CTY + Neural + FeatureController"),
        )
    }
    SideEffect { operatingContext.publish(contextGeneration, contextSnapshot) }
    val scope = rememberCoroutineScope()
    fun accept(result: UsbResult) {
        when (result) {
            is UsbResult.Connected -> {
                cwDecoder.feed(result.cwFrames)
                val parsed = core.withHandle { handle ->
                    NativeCore.feed(handle, result.frames)
                    NativeCore.parseState(NativeCore.state(handle))
                } ?: return
                kxRadio = parsed.copy(cwDecodedText = cwDecoder.text)
                if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) radio = kxRadio
                usbDetail = result.detail
            }
            is UsbResult.PermissionRequired -> { usbDetail = result.detail; app.disarmAll(); voiceTx.stop("CAT permission unavailable") }
            is UsbResult.Unavailable -> { usbDetail = result.detail; app.disarmAll(); voiceTx.stop("CAT disconnected or unavailable") }
        }
    }
    suspend fun connectKx3() {
        app.disarmAll(); voiceTx.stop("CAT reconnect clears voice macro arm")
        usbDetail = "Connecting to ${app.radioFamily.displayName}…"
        accept(transport.connect())
    }
    suspend fun connectSelectedRadio() {
        if (selectedProfile.id == RadioProfileCatalog.UNKNOWN.id) {
            app.disarmAll()
            voiceTx.stop("Unknown radio profile remains disconnected")
            usbDetail = "Unknown radio profile · choose a reviewed model before connecting"
            return
        }
        when (selectedProfile.backendKind) {
            RadioBackendKind.NATIVE_ELECRAFT -> connectKx3()
            RadioBackendKind.NATIVE_FLEX -> {
                usbDetail = "Select a discovered FlexRadio station to connect"
                flex.discoverLan()
            }
            else -> {
                app.disarmAll(); voiceTx.stop("Radio connection clears transmit arms")
                radioPlatform.select(selectedProfile, connectAfterSelection = false)
                val connected = radioPlatform.connectSelected()
                platformSnapshot = radioPlatform.snapshot
                radio = platformSnapshot.asRadioState(selectedProfile)
                usbDetail = if (connected) "Connected · ${selectedProfile.name}" else "Connection failed closed · ${selectedProfile.name}"
            }
        }
    }
    val connect: () -> Unit = { scope.launch { connectSelectedRadio() } }
    val direct: (String) -> Unit = { command -> scope.launch {
        if (selectedProfile.id == RadioProfileCatalog.UNKNOWN.id) {
            usbDetail = "Unknown radio profile · raw CAT blocked"
        } else if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) accept(transport.send(command))
        else usbDetail = "Raw CAT is unavailable for ${selectedProfile.name}; use capability actions"
    } }
    val keyerRuntime = remember(keyerProfiles) { AndroidKeyerRuntime(keyerProfiles, app, voiceTx,
        { radio }, { operatingContext.snapshot }, { foreground }, { foregroundGeneration }, direct, scope,
        tciTransmit) { app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_TCI } }
    val keyer = keyerRuntime.controller
    SideEffect { keyerPort[0] = keyer }
    val repeatCq = remember { RepeatCqController() }
    val chaser = remember { DxChaserRuntime(context, database, digi, { operatingContext.snapshot }, { foreground },
        { keyer.snapshot() }, contest, { intent -> intent.dialFrequencyHz?.let { frequency ->
            pendingHomeReceiveTune = HomeReceiveTuneReview(frequency, intent.mode.ifBlank { null }, "DX Chaser receive review",
                "${intent.reason}. Review receive frequency only; a fresh qualifying local decode remains required.")
        } }, { intent -> when (intent.type) {
            DxChaserActionType.OPEN_LOGBOOK_HISTORY -> dispatchWorkspaceAction(
                WorkspaceAction(WorkspaceDestination.LOGBOOK, callsign = intent.callsign, source = "DX Chaser", reason = intent.reason))
            else -> dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DX, callsign = intent.callsign,
                source = "DX Chaser", reason = intent.reason.ifBlank { "Open exact DX details" }))
        } }) }
    val panadapter = remember { PanadapterController(context, audio, { radio }, direct) }
    val sdrOperationalV2 = remember { SdrOperationalV2(context) }
    val sdrWorkbenchV4 = remember { AndroidSdrWorkbenchV4(context) }
    val localReceivers = remember { LocalReceiverController(context, tciRxAudio, sdrOperationalV2.timeShift) }
    val scanner = remember { ReceiveOnlyScannerController(
        tuneReceive = { frequency, mode, _ ->
            val tuned = radioPlatform.dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = frequency))
            tuned && radioPlatform.dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "mode", textValue = mode))
        },
        signalLevel = { panadapter.frame?.peakDb },
        onDwell = { event ->
            val bank = event.bankId?.let { id -> sdrOperationalV2.scanBanks.firstOrNull { it.id == id } }
            if (bank != null && event.peakDb?.let { it >= bank.thresholdDb } == true) {
                val audioCapture = localReceivers.scannerRecordOnHit(bank, event.frequencyHz)
                sdrOperationalV2.recordOnHit(bank, event.frequencyHz, event.mode, event.peakDb, event.dwellMillis, audioCapture)
            }
        },
        orderEntries = sdrWorkbenchV4::ordered,
        adaptiveDwell = sdrWorkbenchV4::dwell,
    ) }
    val rfObservations = remember { RfObservationController() }
    val bandStacks = remember { BandStackStore(context) }
    val announcements = remember { SpokenAnnouncementController(context, { radio.transmitting }, { voiceTx.isBusy }, { app.quietAlerts }) }
    val debugTciTx = remember { if (BuildConfig.DEBUG) DebugTciTransmitter(tciTransmit) else null }
    val debugSdrLab = remember { if (BuildConfig.DEBUG) DebugSdrLab(tciRuntime, panadapter, rfObservations,
        sdrOperationalV2, localReceivers, sdrWorkbenchV4, debugTciTx) else null }
    LaunchedEffect(panadapter.frame?.sequence, tciRuntime.snapshot.activeReceiverId) {
        val frame = panadapter.frame ?: return@LaunchedEffect
        val receiver = tciRuntime.snapshot.receivers.firstOrNull { it.id == tciRuntime.snapshot.activeReceiverId }
            ?: tciRuntime.snapshot.receivers.firstOrNull()
        val receiverIndex = receiver?.backendIndex ?: 0
        val centerHz = receiver?.effectiveRxHz?.takeIf { it > 0 } ?: panadapter.effectiveCenter()
        if (centerHz <= 0) return@LaunchedEffect
        val receiveMode = receiver?.mode ?: radio.mode
        val source = when {
            sdrWorkbenchV4.replay.snapshot.state in setOf(IqReplayState.PLAYING, IqReplayState.PAUSED) -> "REPLAY"
            debugSdrLab?.active == true -> "DEBUG FIXTURE"
            receiver != null -> "TCI"
            else -> "STEREO I/Q"
        }
        sdrOperationalV2.onPanadapterFrame(frame, receiverIndex, centerHz, source)
        sdrWorkbenchV4.onPanadapterFrame(frame, receiverIndex, centerHz,
            radioPresetBandName(centerHz) ?: "OUTSIDE", receiveMode, source,
            scanner.snapshot.state == ScannerState.DWELLING)
        sdrWorkbenchV4.updateHistory(sdrOperationalV2.timeShift, centerHz)
    }
    LaunchedEffect(radio.connected, radio.frequencyHz, radio.mode) {
        if (radio.connected && radio.frequencyHz > 0) {
            val band = radioPresetBandName(radio.frequencyHz)
            announcements.announceTuning(radio.frequencyHz, band ?: "Outside amateur allocation", radio.mode)
            if (band == null) announcements.announceAllocationWarning("Warning. Frequency is outside the configured amateur allocations")
        }
    }
    LaunchedEffect(tciRxAudio.status) {
        if (tciRxAudio.status.contains("route was lost", ignoreCase = true)) announcements.announceRouteLoss(tciRxAudio.status)
    }
    LaunchedEffect(radio.transmitting, radio.swrTenths) {
        if (radio.transmitting && radio.swrTenths >= 30) announcements.announceHighSwr(radio.swrTenths / 10.0)
    }
    SideEffect {
        sdrWorkbenchV4.measurement.localFollowSink = localReceivers::move
        panadapter.localIqSink = { source, receiver, center, rate, values ->
            if (sdrWorkbenchV4.replay.snapshot.state !in setOf(IqReplayState.PLAYING, IqReplayState.PAUSED)) {
                val corrected = sdrWorkbenchV4.ingestLive(source, receiver, center, rate, values)
                localReceivers.pushIq(source, receiver, center, rate, corrected)
            }
        }
        tciRuntime.iqSink = { receiver, rate, values ->
            if (sdrWorkbenchV4.replay.snapshot.state !in setOf(IqReplayState.PLAYING, IqReplayState.PAUSED)) {
                val center = tciRuntime.snapshot.receivers.firstOrNull { it.backendIndex == receiver }?.effectiveRxHz ?: 0
                val source = if (debugSdrLab?.active == true) "DEBUG FIXTURE" else "TCI"
                val corrected = sdrWorkbenchV4.ingestLive(source, receiver, center, rate, values)
                panadapter.pushTciIq(receiver, rate, corrected)
                sdrOperationalV2.skimmer.pushIq(receiver, rate, center, corrected)
                localReceivers.pushIq(source, receiver, center, rate, corrected)
            }
        }
        sdrWorkbenchV4.replaySink = { source, receiver, center, rate, values ->
            panadapter.pushTciIq(receiver, rate, values)
            sdrOperationalV2.skimmer.pushIq(receiver, rate, center, values)
            if (sdrWorkbenchV4.replay.snapshot.audioTruthful && sdrWorkbenchV4.settings.replayAudioEnabled)
                localReceivers.pushIq(source, receiver, center, rate, values)
        }
        tciRuntime.rxAudioSink = tciRxAudio::push
        remoteRuntime.spectrumSink = { bins, sequence, _ -> panadapter.pushRemoteDerivedSpectrum(bins, sequence) }
        remoteRuntime.audioPcm16Sink = { rate, channels, pcm, _, _ ->
            val values = FloatArray(pcm.size / 2) { index ->
                val low = pcm[index * 2].toInt() and 0xff
                val high = pcm[index * 2 + 1].toInt()
                ((high shl 8) or low).toShort() / 32768f
            }
            tciRxAudio.push(0, rate, channels, values)
        }
    }
    val operatorStop = remember { OperatorStopRouter(digi, keyer, repeatCq, contest, chaser) {
        tciTransmit.globalStop("GLOBAL_STOP")
        scanner.globalStop(); sdrOperationalV2.stopActive(); sdrWorkbenchV4.capture.stop("Global Stop"); sdrWorkbenchV4.replay.stop("Global Stop")
        localReceivers.stopActive("Global Stop"); announcements.globalStop(); tciRxAudio.stop("Global Stop")
        scope.launch { radioPlatform.stopAndDisarm(); rotator.stopAndDisarm() }
    } }
    val sendKx: (String) -> Unit = { raw ->
        val command = raw.uppercase()
        val cwMacro = command.startsWith("KY ")
        val risky = command.startsWith("TX") || cwMacro ||
            command in setOf("SWT11;", "SWH11;", "SWT44;", "SWT16;", "SWH16;")
        if (cwMacro && app.cwMacrosArmed) direct(command)
        else if (risky) pendingRisk = command else direct(command)
    }
    val send: (String) -> Unit = { raw ->
        val command = parseGeneralRadioCommand(raw)
        if (selectedProfile.id == RadioProfileCatalog.UNKNOWN.id) {
            usbDetail = "Unknown radio profile · commands blocked"
        } else if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) sendKx(command.raw)
        else if (selectedProfile.backendKind == RadioBackendKind.NATIVE_FLEX) {
            val target = command.frequencyHz ?: flex.snapshot.selected(flex.selectedSliceIndex)?.frequencyHz
            if (target != null) scope.launch { flex.tune(ReceiveTuneRequest(target, command.mode)) }
        } else {
            command.frequencyHz?.let { frequency -> scope.launch {
                radioPlatform.dispatch(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = frequency))
            } }
        }
    }
    SideEffect {
        controlSurfaces.actionPort = object : ControlSurfaceActionPort {
            override fun globalStop() = operatorStop.stopAll("CONTROL_SURFACE_GLOBAL_STOP")
            override fun frequencyDelta(steps: Int) {
                val current = radio.frequencyHz.takeIf { it > 0 } ?: return
                send("FA${(current + steps * 10L).coerceAtLeast(0).toString().padStart(11, '0')};")
            }
            override fun absoluteFrequency(normalized: Float) {
                val target = (1_800_000L + normalized.coerceIn(0f, 1f) * 52_200_000L).toLong()
                send("FA${target.toString().padStart(11, '0')};")
            }
            override fun nextWorkspace() { destination = Destination.entries[(destination.ordinal + 1) % Destination.entries.size] }
        }
    }
    LaunchedEffect(transport, selectedProfile.id) {
        scanner.onProfileChange()
        localReceivers.stopActive("Radio profile changed")
        tciRxAudio.stop("Radio profile changed")
        tciRxAudio.selectProfile(selectedProfile.id.value)
        debugSdrLab?.takeIf { it.active }?.stop()
        panadapter.detachTciSources("Radio profile changed")
        digi.stopRx("Radio selection changed · RX stopped")
        digi.disarm()
        radioPlatform.disconnect()
        transport.disconnect()
        flex.disconnect()
        app.disarmAll()
        audio.refreshDevices()
        usbDetail = "${selectedProfile.name} selected · press Connect"
        radio = RadioState(identity = selectedProfile.id.value, model = selectedProfile.model)
        if (selectedProfile.backendKind == RadioBackendKind.NATIVE_FLEX) {
            voiceTx.stop("FlexRadio selection closes KX CAT")
            if (destination in setOf(Destination.EQ, Destination.PANADAPTER)) destination = Destination.RADIO
            flex.discoverLan()
            while (app.selectedRadioProfileId == selectedProfile.id) { radio = flex.state; delay(120) }
        } else if (selectedProfile.id == RadioProfileCatalog.UNKNOWN.id) {
            voiceTx.stop("Unknown radio profile remains disconnected")
        } else if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) {
            while (app.selectedRadioProfileId == selectedProfile.id) { delay(240); transport.poll()?.let(::accept) }
        } else {
            radioPlatform.select(selectedProfile, connectAfterSelection = false)
            if (pendingRemoteAutoConnect == selectedProfile.id) {
                pendingRemoteAutoConnect = null
                val connected = radioPlatform.connectSelected()
                platformSnapshot = radioPlatform.snapshot
                radio = platformSnapshot.asRadioState(selectedProfile)
                usbDetail = if (connected) "Connected · ${selectedProfile.name}" else "Connection failed closed · ${selectedProfile.name}"
            }
            while (app.selectedRadioProfileId == selectedProfile.id) {
                platformSnapshot = radioPlatform.snapshot
                radio = platformSnapshot.asRadioState(selectedProfile)
                delay(200)
            }
        }
    }
    LaunchedEffect(features, database, wavelog, cty) {
        features.startWorkedLogSync(database, wavelog, cty)
    }
    val hamClockStationCall = contextSnapshot.stationCallsign.value
    val hamClockStationGrid = contextSnapshot.stationGrid.value
    LaunchedEffect(hamClockSettings.document.settings.cluster.enabled, hamClockSettings.document.settings.rbn, foreground, hamClockStationCall) {
        features.setForeground(foreground)
        features.applyRbnPreference(hamClockSettings.document.settings.rbn, hamClockStationCall)
        if (!foreground || !hamClockSettings.document.settings.cluster.enabled) features.disconnectCluster(disabled = true)
        else if (features.clusterConnection.state in setOf(ClusterConnectionState.DISABLED, ClusterConnectionState.DISCONNECTED)) {
            features.connectConfiguredCluster()
        }
    }
    LaunchedEffect(hamClockStationGrid) { neuralDx.updateSignalGeometry(hamClockStationGrid) }
    LaunchedEffect(hamClockSettings.document.settings.wspr, foreground, hamClockStationCall, hamClockStationGrid) {
        val preference = hamClockSettings.document.settings.wspr
        neuralDx.applyWsprPreference(preference)
        if (!foreground || !preference.personalEnabled) return@LaunchedEffect
        while (true) {
            val now = Instant.now().epochSecond
            if (neuralDx.wsprPersonal.fetchedEpoch == 0L || now - neuralDx.wsprPersonal.fetchedEpoch >= 300L) {
                neuralDx.refreshWspr(hamClockStationCall, hamClockStationGrid)
            }
            delay(60_000)
        }
    }
    LaunchedEffect(hamClockSettings.document.settings.pskReporter, foreground, hamClockStationCall, hamClockStationGrid) {
        val preference = hamClockSettings.document.settings.pskReporter
        neuralDx.applyPskPreference(preference)
        if (!foreground || !preference.enabled) return@LaunchedEffect
        while (true) {
            val now = Instant.now().epochSecond
            if (neuralDx.mySignal.fetchedEpoch == 0L || now - neuralDx.mySignal.fetchedEpoch >= preference.refreshSeconds) {
                neuralDx.refreshPsk(hamClockStationCall, hamClockStationGrid)
            }
            delay(60_000)
    }
    }
    LaunchedEffect(wavelog.logMode) { syncHub.setAuthority(wavelog.logMode) }
    LaunchedEffect(transport, radio.mode) {
        while (isCwMacroMode(radio.mode)) { delay(90); transport.pollCwText()?.let(::accept) }
    }
    val clearCwDecode: () -> Unit = {
        cwDecoder.clear()
        radio = radio.copy(cwDecodedText = "")
    }
    LaunchedEffect(radio.connected, radio.mode, app.cwMacrosArmed) {
        if (app.cwMacrosArmed && (!radio.connected || !isCwMacroMode(radio.mode))) {
            app.updateCwMacrosArmed(false)
        }
    }
    LaunchedEffect(radio.connected, radio.mode, app.voiceMacrosArmed) {
        if (app.voiceMacrosArmed && (!radio.connected || !isVoiceMacroMode(radio.mode) || radio.mode != voiceArmedMode)) {
            app.updateVoiceMacrosArmed(false); voiceTx.stop("CAT or exact sideband mode changed")
        }
    }
    LaunchedEffect(radio.connected) { eqStudio.onConnectionChanged(radio.connected) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                foreground = true; foregroundGeneration++; contest.setForeground(true); features.setForeground(true); syncHub.setForeground(true); neuralDx.setForeground(true); wavelogNative.onForeground()
            }
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                foreground = false; foregroundGeneration++; contest.setForeground(false); chaser.contextLost("BACKGROUND"); repeatCq.stop(); keyer.stop(KeyerStopReason.Background); features.setForeground(false); syncHub.setForeground(false); neuralDx.setForeground(false); app.disarmAll(); voiceAudio.stopCurrent(); eqAudio.stop()
                digi.stopRx("App left foreground · RX stopped")
                digi.disarm()
                voiceTx.stop("App left foreground; defensive RX cleanup requested")
                scanner.onBackground(); sdrOperationalV2.stopActive("App left foreground"); localReceivers.stopActive("App left foreground"); announcements.stop(); tciRxAudio.stop("App left foreground")
                scope.launch { flex.onForegroundChanged(false) }
            }
            else -> Unit
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkAvailable = true; wavelogNative.onConnectivityAvailable() }
            override fun onLost(network: Network) { networkAvailable = connectivity.activeNetwork != null }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    LaunchedEffect(voiceAudio) { voiceAudio.onFailure = { app.updateVoiceMacrosArmed(false) } }
    LaunchedEffect(contextSnapshot.generation, foregroundGeneration, radio.identity, radio.connected, radio.mode, keyerProfiles.activeProfileId) {
        keyer.invalidate(); repeatCq.stop()
    }
    LaunchedEffect(repeatCq.state.active, keyerProfiles.repeatIntervalSeconds, keyerProfiles.repeatMaximumCycles,
        keyerProfiles.repeatMaximumMinutes) {
        val limits = RepeatCqLimits(keyerProfiles.repeatIntervalSeconds, keyerProfiles.repeatMaximumCycles, keyerProfiles.repeatMaximumMinutes)
        while (repeatCq.state.active) {
            delay(250)
            if (repeatCq.due(limits, keyer.snapshot().active == null && keyer.snapshot().pending == null)) {
                keyer.submit(KeyerAction.SendMessage(repeatCq.state.messageId), keyerRuntime.context())
            }
        }
    }
    LaunchedEffect(voiceTx.state) {
        keyerRuntime.onVoiceStateChanged(voiceTx.state)
    }
    LaunchedEffect(foreground, contextSnapshot.generation) {
        while (foreground) { chaser.poll(); delay(1_000) }
    }
    DisposableEffect(Unit) { onDispose {
        app.disarmAll(); bandMaps.close(); chaser.close(); contest.close(); scanner.close(); debugSdrLab?.close(); localReceivers.close(); sdrWorkbenchV4.close(); sdrOperationalV2.close(); rfObservations.close(); announcements.close(); tciRxAudio.close(); digi.close(); voiceTx.close(); voiceAudio.close(); eqAudio.close(); panadapter.close(); flex.close(); audio.close(); groupsIo.close()
        runBlocking {
            transport.disconnect()
            radioPlatform.close()
            platformTransport.disconnect()
            rotator.stopAndDisarm()
            rotator.disconnect()
        }
        hamlibRegistry.close()
        neuralDx.close(); features.close(); wavelogNative.close(); wavelog.close(); callbook.close(); cty.close()
        portable.close(); progress.close(); operations.close(); syncHub.close(); core.close()
    } }
    pendingRisk?.let { command ->
        val cwMacro = command.startsWith("KY ")
        AlertDialog(
        onDismissRequest = { pendingRisk = null }, title = { Text(if (cwMacro) "Arm and send CW macro?" else "Confirm radio action") },
        text = { Text(if (cwMacro)
            "Send $command now and arm CW macros for this connected CW session. Later macro taps send immediately; the arm clears on disconnect or mode change."
            else "Send $command once? This may key or tune the transmitter.") },
        confirmButton = { Button({
            if (cwMacro && (!radio.connected || !isCwMacroMode(radio.mode))) {
                pendingRisk = null
            } else {
                if (cwMacro) app.updateCwMacrosArmed(true)
                direct(command); pendingRisk = null
            }
        }, enabled = !cwMacro || (radio.connected && isCwMacroMode(radio.mode))) {
            Text(if (cwMacro) "Arm & send" else "Send once")
        } },
        dismissButton = { TextButton({ pendingRisk = null }) { Text("Cancel") } },
    ) }
    pendingHomeReceiveTune?.let { review ->
        AlertDialog(
            onDismissRequest = { pendingHomeReceiveTune = decideHomeReceiveTune(review, false).pending },
            title = { Text("Review receive tune") },
            text = { Text("${"%.6f".format(java.util.Locale.US, review.frequencyHz / 1_000_000.0)} MHz" +
                review.mode?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty() +
                "\n${review.source}\n${review.reason}\nRadio: ${app.radioFamily.displayName}\n\nReceive frequency only. This does not key PTT or start TUNE.") },
            confirmButton = { Button({
                val decision = decideHomeReceiveTune(review, true)
                decision.dispatch?.let { approved ->
                    if (app.radioFamily.isElecraft) {
                        direct("FA${approved.frequencyHz.toString().padStart(11, '0')};")
                    } else scope.launch { flex.tune(ReceiveTuneRequest(approved.frequencyHz, approved.mode)) }
                }
                pendingHomeReceiveTune = decision.pending
            }, enabled = radio.connected && review.frequencyHz in 100_000L..77_000_000_000L) { Text("Confirm receive tune") } },
            dismissButton = { TextButton({ pendingHomeReceiveTune = decideHomeReceiveTune(review, false).pending }) { Text("Cancel") } },
        )
    }
    pendingVoiceSlot?.let { slot ->
        val item = voiceStore.slots.getOrNull(slot)
        AlertDialog(onDismissRequest = { pendingVoiceSlot = null }, title = { Text("Arm & send voice macro?") },
            text = { Text("${item?.label ?: "M${slot + 1}"} · ${(item?.durationMillis ?: 0) / 1_000f}s\n${audio.selectedTx?.name ?: "No selected USB output"}\n\nCAT PTT → DigiRig USB audio → KX3 MIC") },
            confirmButton = { Button({
                if (radio.connected && isVoiceMacroMode(radio.mode) && item?.exists == true && audio.selectedTxDevice() != null && foreground) {
                    app.updateVoiceMacrosArmed(true); voiceArmedMode = radio.mode; voiceTx.send(slot)
                }
                pendingVoiceSlot = null
            }, enabled = radio.connected && isVoiceMacroMode(radio.mode) && item?.exists == true && audio.selectedTxDevice() != null) { Text("Arm & send") } },
            dismissButton = { TextButton({ pendingVoiceSlot = null }) { Text("Cancel") } })
    }
    val requestVoice: (Int) -> Unit = { slot ->
        if (app.voiceMacrosArmed && voiceArmedMode == radio.mode) voiceTx.send(slot) else pendingVoiceSlot = slot
    }
    val inAppBrowser = rememberInAppBrowserState()
    CompositionLocalProvider(LocalInAppBrowserState provides inAppBrowser) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Chassis).windowInsetsPadding(WindowInsets.safeDrawing).onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val initialDown = event.type == KeyEventType.KeyDown && native.repeatCount == 0
            val inputMethod = context.getSystemService(InputMethodManager::class.java)
            if (initialDown && inputMethod.isAcceptingText && keyerProfiles.repeatStopsOnInput) repeatCq.stop()
            if (initialDown && native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE) {
                operatorStop.stopAll("ESCAPE")
                true
            } else {
                val decision = KeyerHotkeyDispatcher.dispatch(
                    KeyerKeyEvent(
                        chord = androidFunctionChord(native),
                        escape = false,
                        initialDown = initialDown,
                        textInputFocused = inputMethod.isAcceptingText,
                        foreground = foreground,
                        modalOpen = pendingRisk != null || pendingVoiceSlot != null || pendingHomeReceiveTune != null,
                    ),
                    enabled = keyerProfiles.hotkeysEnabled,
                    bindings = keyerProfiles.bindingsForActive(),
                    keyerActive = keyer.snapshot().active != null || voiceTx.isBusy,
                )
                decision.action?.let { keyer.submit(it, keyerRuntime.context()) }
                decision.consumed
            }
        },
    ) {
        if (keyerProfiles.showStrip) KeyerHotkeyStrip(keyerProfiles.activeStripProfile(), keyer.snapshot(),
            keyer.availability(keyerRuntime.context()), { keyer.submit(it, keyerRuntime.context()) }, Modifier.align(Alignment.TopCenter).fillMaxWidth())
        val keyerStripInset = if (keyerProfiles.showStrip) 76.dp else 0.dp
        if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize().padding(top = keyerStripInset)) {
            NavigationRail(
                modifier = Modifier.testTag("primary-navigation-rail"),
                containerColor = Panel,
            ) {
                Image(
                    painter = painterResource(R.drawable.shackcq_logo_mark),
                    contentDescription = "ShackCQ",
                    modifier = Modifier.padding(vertical = 12.dp).size(42.dp),
                    contentScale = ContentScale.Fit,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .testTag("primary-navigation-destinations"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Destination.entries.filterNot { item ->
                        item == Destination.SYNC ||
                        (item == Destination.GROUPS_IO && !groupsIoDestinationVisible(groupsIo.enabled, compact = false)) ||
                        (item == Destination.BAND_MAPS && (!bandMaps.settings.enabled || !bandMaps.settings.navigationVisible)) ||
                        (item == Destination.ROTATOR && !app.rotatorEnabled) ||
                        (item == Destination.PANADAPTER &&
                            ((app.selectedRadioProfile.backendKind != RadioBackendKind.NATIVE_TCI && !app.panadapterEnabled) ||
                                app.radioFamily == RadioFamily.FLEXRADIO)) ||
                        (item == Destination.EQ && !eqVisible) ||
                        (item == Destination.CONTEST && !contestDestinationVisible(app.contestEnabled))
                    }.forEach { item -> NavigationRailItem(destination == item, { destination = item },
                        { Icon(navIcon(item), item.label) }, label = { Text(item.label) }) }
                }
            }
            Screen(destination, radio, usbDetail, database, logbook, mutations, progress, operations, publicProviders, hamClockSettings, features, neuralDx, wavelog, wavelogNative, syncHub, callbook, cty, audio,
                panadapter, tciRxAudio, tciRuntime, tciTransmit, scanner, sdrOperationalV2, sdrWorkbenchV4, localReceivers, rfObservations, bandStacks, announcements, debugSdrLab, controlSurfaces,
                portable, activation, pendingPortableDraft, { pendingPortableDraft = null }, foreground, app, remoteRuntime, remoteFactory,
                { station -> pendingRemoteAutoConnect = station.radioProfile().id; app.upsertRemoteStation(station) },
                selectedProfile, platformSnapshot, hamlibModels, rotator,
                { tciRxAudio.stop("Radio disconnected"); sdrOperationalV2.stopActive("Radio disconnected"); localReceivers.stopActive("Radio disconnected"); scope.launch { radioPlatform.disconnect(); platformSnapshot = radioPlatform.snapshot } },
                { action -> scope.launch {
                    if (action.name.equals("frequency", ignoreCase = true)) scanner.onManualTune()
                    val accepted = radioPlatform.dispatch(action)
                    platformSnapshot = radioPlatform.snapshot
                    if (!accepted) usbDetail = "Action unavailable or blocked by the selected profile"
                } },
                transport, flex, digi,
                voiceStore, voiceAudio, voiceTx, eqStudio, groupsIo, contextSnapshot, keyerProfiles, keyer, repeatCq, bandMaps,
                BandMapKeyerContext(keyer.snapshot(), keyer.availability(keyerRuntime.context()), contest.activeSession.role.name),
                contest, chaser, integratedDigiPage, { integratedDigiPage = it }, { destination = Destination.CONTEST },
                { destination = Destination.DIGI; integratedDigiPage = IntegratedDigiPage.DX_CHASER }, { destination = Destination.SETTINGS },
                ::dispatchWorkspaceAction,
                false, { destination = Destination.EQ }, { destination = Destination.RADIO },
                connect, send, direct, requestVoice, clearCwDecode, { spot -> executePortableTune(radio.connected, spot, direct) },
                { spot -> if (executePortableTune(radio.connected, spot, direct)) { pendingPortableDraft = toPortableLogDraft(spot); destination = Destination.RADIO } },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DX, source = "Navigation", reason = "Open DX workspace")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PORTABLE, source = "Navigation", reason = "Open Portable workspace")) },
                { activation.requestOpen(); dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PORTABLE, source = "Home", reason = "Open activation setup")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.LOGBOOK, source = "Navigation", reason = "Open Logbook")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.SYNC, source = "Navigation", reason = "Open reconciliation")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PROGRESS, source = "Navigation", reason = "Open Log Intelligence")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DIGI, source = "Navigation", reason = "Open Digi")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.GROUPS_IO, source = "Home", reason = "Open Groups.io exact destination")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.OPERATIONS, source = "Navigation", reason = "Open Operations")) },
                { app.updateRotatorEnabled(true); destination = Destination.ROTATOR },
                { row -> pendingPortableDraft = operations.satellites.normalLoggerDraft(row);
                    dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.RADIO, noradId = row.satellite.noradId,
                        source = "Satellite Operations", reason = "Prepare exact logger context")) },
                { frequency, mode, source, reason -> dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.RADIO,
                    frequencyHz = frequency, mode = mode.orEmpty(), source = source, reason = reason)) },
                pendingHomeQsoId, { pendingHomeQsoId = null },
                { id -> dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.LOGBOOK, qsoId = id,
                    source = "Home QSO marker", reason = "Open exact QSO")) })
        } else Scaffold(modifier = Modifier.padding(top = keyerStripInset), bottomBar = { NavigationBar(containerColor = Panel) {
            Destination.entries.filterNot { it == Destination.DIGI || it == Destination.EQ || it == Destination.PANADAPTER || it == Destination.PORTABLE || it == Destination.PROGRESS || it == Destination.SYNC || it == Destination.OPERATIONS || it == Destination.GROUPS_IO || (it == Destination.ROTATOR && !app.rotatorEnabled) || (it == Destination.CONTEST && !contestDestinationVisible(app.contestEnabled)) || (it == Destination.BAND_MAPS && (!bandMaps.settings.enabled || !bandMaps.settings.navigationVisible)) }.forEach { item -> NavigationBarItem(destination == item || (item == Destination.RADIO && destination == Destination.DIGI), { destination = item },
                { Icon(navIcon(item), item.label) }, label = { Text(item.label, fontSize = 9.sp) }) }
        } }) { padding -> Box(Modifier.padding(padding)) {
            Screen(destination, radio, usbDetail, database, logbook, mutations, progress, operations, publicProviders, hamClockSettings, features, neuralDx, wavelog, wavelogNative, syncHub, callbook, cty, audio,
                panadapter, tciRxAudio, tciRuntime, tciTransmit, scanner, sdrOperationalV2, sdrWorkbenchV4, localReceivers, rfObservations, bandStacks, announcements, debugSdrLab, controlSurfaces,
                portable, activation, pendingPortableDraft, { pendingPortableDraft = null }, foreground, app, remoteRuntime, remoteFactory,
                { station -> pendingRemoteAutoConnect = station.radioProfile().id; app.upsertRemoteStation(station) },
                selectedProfile, platformSnapshot, hamlibModels, rotator,
                { tciRxAudio.stop("Radio disconnected"); sdrOperationalV2.stopActive("Radio disconnected"); localReceivers.stopActive("Radio disconnected"); scope.launch { radioPlatform.disconnect(); platformSnapshot = radioPlatform.snapshot } },
                { action -> scope.launch {
                    if (action.name.equals("frequency", ignoreCase = true)) scanner.onManualTune()
                    val accepted = radioPlatform.dispatch(action)
                    platformSnapshot = radioPlatform.snapshot
                    if (!accepted) usbDetail = "Action unavailable or blocked by the selected profile"
                } },
                transport, flex, digi,
                voiceStore, voiceAudio, voiceTx, eqStudio, groupsIo, contextSnapshot, keyerProfiles, keyer, repeatCq, bandMaps,
                BandMapKeyerContext(keyer.snapshot(), keyer.availability(keyerRuntime.context()), contest.activeSession.role.name),
                contest, chaser, integratedDigiPage, { integratedDigiPage = it }, { destination = Destination.CONTEST },
                { destination = Destination.DIGI; integratedDigiPage = IntegratedDigiPage.DX_CHASER }, { destination = Destination.SETTINGS },
                ::dispatchWorkspaceAction,
                true, { destination = Destination.EQ }, { destination = Destination.RADIO },
                connect, send, direct, requestVoice, clearCwDecode, { spot -> executePortableTune(radio.connected, spot, direct) },
                { spot -> if (executePortableTune(radio.connected, spot, direct)) { pendingPortableDraft = toPortableLogDraft(spot); destination = Destination.RADIO } },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DX, source = "Navigation", reason = "Open DX workspace")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PORTABLE, source = "Navigation", reason = "Open Portable workspace")) },
                { activation.requestOpen(); dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PORTABLE, source = "Home", reason = "Open activation setup")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.LOGBOOK, source = "Navigation", reason = "Open Logbook")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.SYNC, source = "Navigation", reason = "Open reconciliation")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.PROGRESS, source = "Navigation", reason = "Open Log Intelligence")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.DIGI, source = "Navigation", reason = "Open Digi")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.GROUPS_IO, source = "Home", reason = "Open Groups.io exact destination")) },
                { dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.OPERATIONS, source = "Navigation", reason = "Open Operations")) },
                { app.updateRotatorEnabled(true); destination = Destination.ROTATOR },
                { row -> pendingPortableDraft = operations.satellites.normalLoggerDraft(row);
                    dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.RADIO, noradId = row.satellite.noradId,
                        source = "Satellite Operations", reason = "Prepare exact logger context")) },
                { frequency, mode, source, reason -> dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.RADIO,
                    frequencyHz = frequency, mode = mode.orEmpty(), source = source, reason = reason)) },
                pendingHomeQsoId, { pendingHomeQsoId = null },
                { id -> dispatchWorkspaceAction(WorkspaceAction(WorkspaceDestination.LOGBOOK, qsoId = id,
                    source = "Home QSO marker", reason = "Open exact QSO")) })
        } }
        GroupsIoNewMessageAlert(groupsIo, onOpen = { destination = Destination.GROUPS_IO },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = keyerStripInset))
    }
    pendingCallbookRecord?.let { record ->
        PreviousQsosDialog(record, database, wavelog, callbook) { pendingCallbookRecord = null }
    }
    InAppBrowserDialog(inAppBrowser)
    }
}

private fun navIcon(item: Destination) = when (item) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.RADIO -> Icons.Outlined.SettingsInputAntenna
    Destination.REMOTE -> Icons.Outlined.Router
    Destination.DIGI -> Icons.Outlined.GraphicEq
    Destination.CONTEST -> Icons.Outlined.EmojiEvents
    Destination.BAND_MAPS -> Icons.Outlined.StackedLineChart
    Destination.PANADAPTER -> Icons.Outlined.WaterfallChart
    Destination.EQ -> Icons.Outlined.Equalizer
    Destination.LOGBOOK -> Icons.AutoMirrored.Outlined.List
    Destination.PROGRESS -> Icons.Outlined.Insights
    Destination.SYNC -> Icons.Outlined.CloudSync
    Destination.PRESETS -> Icons.Outlined.Bookmarks
    Destination.DX -> Icons.Outlined.Public
    Destination.PORTABLE -> Icons.Outlined.Hiking
    Destination.GROUPS_IO -> Icons.Outlined.Forum
    Destination.OPERATIONS -> Icons.AutoMirrored.Outlined.EventNote
    Destination.ROTATOR -> Icons.Outlined.Explore
    Destination.SETTINGS -> Icons.Outlined.Settings
}

@Composable private fun Screen(destination: Destination, radio: RadioState, detail: String, database: QsoDatabase,
    logbookController: LogbookController,
    mutations: QsoMutationCoordinator, progress: ProgressController, operations: OperationsController,
    publicProviders: app.shackcq.mobile.hamclock.HamClockPublicProviders,
    hamClockSettings: HamClockSettingsCoordinator,
    features: FeatureController, neuralDx: NeuralDxController, wavelog: WavelogController, wavelogNative: WavelogNativeController,
    syncHub: SyncHubController, callbook: CallbookController, cty: CtyController, audio: AudioMonitorController, panadapter: PanadapterController,
    tciRxAudio: TciRxAudioController,
    tciRuntime: TciRuntimeState, tciTransmit: TciTransmitAuthority, scanner: ReceiveOnlyScannerController, sdrOperationalV2: SdrOperationalV2, sdrWorkbenchV4: AndroidSdrWorkbenchV4,
    localReceivers: LocalReceiverController, rfObservations: RfObservationController,
    bandStacks: BandStackStore, announcements: SpokenAnnouncementController, debugSdrLab: DebugSdrLab?, controlSurfaces: ControlSurfaceController,
    portable: PortableController, activation: PotaActivationController, portableDraft: PortableLogDraft?, consumePortableDraft: () -> Unit, foreground: Boolean, app: AppController,
    remoteRuntime: RemoteRuntimeState, remoteFactory: RemoteStationBackendFactory,
    selectRemoteStation: (RemoteStationProfile) -> Unit,
    selectedProfile: RadioConnectionProfile, platformSnapshot: RadioRuntimeSnapshot, hamlibModels: List<HamlibModelDescriptor>,
    rotator: AndroidRotatorRuntime,
    disconnectPlatform: () -> Unit, dispatchPlatform: (RadioPlatformAction) -> Unit,
    transport: UsbRadioTransport, flex: FlexRadioController, digi: DigiController, voiceStore: VoiceMacroStore, voiceAudio: VoiceMacroAudioController, voiceTx: VoiceMacroTransmitController,
    eqStudio: EqStudioController, groupsIo: GroupsIoController, operatingContext: OperatingContextSnapshot,
    keyerProfiles: KeyerProfileStore, keyer: KeyerController, repeatCq: RepeatCqController,
    bandMaps: BandMapController, bandMapKeyer: BandMapKeyerContext,
    contest: ContestRuntime, chaser: DxChaserRuntime, integratedDigiPage: IntegratedDigiPage,
    setIntegratedDigiPage: (IntegratedDigiPage) -> Unit, openContest: () -> Unit, openChaser: () -> Unit,
    openSettings: () -> Unit, workspaceAction: (WorkspaceAction) -> Unit,
    compact: Boolean, openEq: () -> Unit, closeEq: () -> Unit,
    connect: () -> Unit, send: (String) -> Unit, direct: (String) -> Unit, requestVoice: (Int) -> Unit, clearCwDecode: () -> Unit,
    tunePortable: (PortableSpot) -> Unit, tuneLogPortable: (PortableSpot) -> Unit, openDx: () -> Unit, openPortable: () -> Unit,
    openActivation: () -> Unit, openLogbook: () -> Unit, openSync: () -> Unit, openProgress: () -> Unit, openDigi: () -> Unit,
    openGroupsIo: () -> Unit, openOperations: () -> Unit, openRotator: () -> Unit, prepareSatelliteLogger: (SatellitePassRow) -> Unit,
    requestHomeReceiveTune: (Long, String?, String, String) -> Unit,
    homeQsoId: String?, consumeHomeQso: () -> Unit, openHomeQso: (String) -> Unit) {
    val screenScope = rememberCoroutineScope()
    val integratedRadioSelected = selectedProfile.id == RadioProfileCatalog.UNKNOWN.id || selectedProfile.backendKind in setOf(
        RadioBackendKind.NATIVE_QMX, RadioBackendKind.NATIVE_RGO_ONE,
        RadioBackendKind.HAMLIB_EMBEDDED, RadioBackendKind.HAMLIB_NETWORK, RadioBackendKind.NATIVE_TCI, RadioBackendKind.REMOTE_STATION,
    )
    var compactPanadapter by rememberSaveable { mutableStateOf(false) }
    val intelligencePortableSpots = portable.pota.spots.map(PotaSpot::toPortable) + portable.sotaSpots + portable.wwffSpots
    // BandMapController is an application-scoped authority. Feed it here so Radio and
    // Contest remain live even when the dedicated Band Maps destination has never opened.
    val bandMapDatabaseRevision = database.changeToken()
    val bandMapNeeds by produceState(BandMapNeedsSnapshot(), operatingContext.stationProfileId.value,
        operatingContext.stationCallsign.value, bandMapDatabaseRevision) {
        value = withContext(Dispatchers.IO) {
            database.bandMapNeedsSnapshot(operatingContext.stationProfileId.value, operatingContext.stationCallsign.value)
        }
    }
    val bandMapObservations = remember(features.liveSpots, features.rbnObservations, neuralDx.mySignal.reports,
        neuralDx.wsprPersonal.reports, portable.pota.spots, portable.sotaSpots, portable.wwffSpots) {
        BandMapSourceAdapters.cluster(features.liveSpots) + BandMapSourceAdapters.rbn(features.rbnObservations) +
            BandMapSourceAdapters.signal(neuralDx.mySignal.reports, false) +
            BandMapSourceAdapters.signal(neuralDx.wsprPersonal.reports, true) +
            BandMapSourceAdapters.portable(intelligencePortableSpots)
    }
    val bandMapContest = contest.snapshot()
    val bandMapChaser = chaser.snapshot
    LaunchedEffect(bandMapObservations, bandMapNeeds, operatingContext.generation, bandMapContest, bandMapChaser, bandMapKeyer) {
        bandMaps.submit(BandMapInputs(bandMapObservations, operatingContext, bandMapNeeds, bandMapContest,
            contest::opportunity, bandMapChaser, bandMapKeyer, cty::lookup,
            providerHealth = mapOf(
                BandMapSource.DX_CLUSTER to (features.clusterConnection.state == ClusterConnectionState.CONNECTED),
                BandMapSource.RBN to (features.rbnSourceSnapshot.state.name == "CURRENT"),
                BandMapSource.PSK_REPORTER to neuralDx.mySignal.available,
                BandMapSource.PERSONAL_WSPR to neuralDx.wsprPersonal.reports.isNotEmpty(),
                BandMapSource.POTA to portable.pota.spots.isNotEmpty(),
                BandMapSource.SOTA to portable.sotaSpots.isNotEmpty(),
                BandMapSource.WWFF to portable.wwffSpots.isNotEmpty(),
            )))
    }
    LaunchedEffect(features.liveSpots, features.rbnObservations, neuralDx.mySignal.reports, neuralDx.wsprPersonal.reports,
        operatingContext.stationGrid.value, debugSdrLab?.active) {
        if (debugSdrLab?.active == true) return@LaunchedEffect
        val station = maidenheadCenter(operatingContext.stationGrid.value) ?: return@LaunchedEffect
        val clusterRows = features.liveSpots.mapNotNull { row ->
            if (!row.latitude.isFinite() || !row.longitude.isFinite()) null else RfObservation(
                "cluster:${row.id}", "CLUSTER", RfEvidenceClass.OBSERVED, row.receivedEpoch, row.callsign,
                row.band, row.mode, station.latitude, station.longitude, row.latitude, row.longitude,
                RfPrecision.COARSE, worked = row.workedCall, confirmed = null,
                needed = buildSet { if (!row.workedCountry) add("DXCC"); if (!row.workedBandMode) add("BAND_MODE") },
                contestDuplicate = row.recentDupe, continent = row.continent, entity = row.dxcc.ifBlank { row.country },
                cqZone = row.cqZone.takeIf { it > 0 }, ituZone = row.ituZone.takeIf { it > 0 })
        }
        fun signalRows(rows: List<SignalReport>, source: String) = rows.mapNotNull { row ->
            val latitude = row.latitude ?: maidenheadCenter(row.locator)?.latitude ?: return@mapNotNull null
            val longitude = row.longitude ?: maidenheadCenter(row.locator)?.longitude ?: return@mapNotNull null
            RfObservation("$source:${signalReportReference(row)}", source, RfEvidenceClass.OBSERVED, row.epoch,
                row.callsign, row.band, row.mode, station.latitude, station.longitude, latitude, longitude,
                if (row.locator.length >= 6) RfPrecision.GRID else RfPrecision.COARSE, row.snr)
        }
        val rbnRows = features.rbnObservations.mapNotNull { row ->
            val from = row.skimmerPoint ?: return@mapNotNull null
            val to = row.dxPoint ?: return@mapNotNull null
            RfObservation("RBN:${row.id}", "RBN", RfEvidenceClass.OBSERVED, row.observedEpoch,
                row.dxCall, row.band, row.mode, from.latitude, from.longitude, to.latitude, to.longitude,
                if (row.dxGeometry == "EXACT") RfPrecision.EXACT else RfPrecision.COARSE, row.snr)
        }
        rfObservations.submit(clusterRows + rbnRows + signalRows(neuralDx.mySignal.reports, "PSK") +
            signalRows(neuralDx.wsprPersonal.reports, "WSPR"))
    }
    val deliveredStates = setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED)
    val intelligenceAttention = syncHub.records.count { it.state !in deliveredStates }
    val intelligenceLogAuthority = "${wavelog.logMode.name}|${wavelog.stationId}|${wavelog.selectedStation?.callsign.orEmpty()}"
    LaunchedEffect(destination, progress.filters, database.changeToken(), features.liveSpots, intelligencePortableSpots,
        intelligenceAttention, cty.dataRevision, progress.goalStore.goals, intelligenceLogAuthority) {
        progress.refresh(progress.filters, features.liveSpots, intelligencePortableSpots, intelligenceAttention, cty,
            portable.sotaCatalogue, intelligenceLogAuthority)
    }
    val clusterBandEvidenceSpots = remember(features.liveSpots, hamClockSettings.document.settings.cluster,
        Instant.now().epochSecond / 60) {
        filterClusterPresentation(features.liveSpots, hamClockSettings.document.settings.cluster, Instant.now().epochSecond)
    }
    val bandEvidence = remember(clusterBandEvidenceSpots, neuralDx.mySignal, features.rbnObservations, neuralDx.wsprPersonal) {
        clusterBandEvidenceSpots.map { HamClockBandEvidence("CLUSTER", it.band, it.mode, it.callsign, it.spotter,
            observedEpoch = it.receivedEpoch, frequencyHz = it.frequencyHz, id = "cluster:${it.id}") } +
            neuralDx.mySignal.reports.map { HamClockBandEvidence("PSK", it.band, it.mode, it.callsign,
                it.receiverCallsign, it.snr, it.epoch, it.frequencyHz, "psk:${signalReportReference(it)}") } +
            features.rbnObservations.map { HamClockBandEvidence("RBN", it.band, it.mode, it.dxCall,
                it.skimmerCall, it.snr, it.observedEpoch, it.frequencyHz, "rbn:${it.id}") } +
            neuralDx.wsprPersonal.reports.map { HamClockBandEvidence("WSPR", it.band, "WSPR", it.callsign,
                it.receiverCallsign, it.snr, it.epoch, it.frequencyHz, "wspr:${signalReportReference(it)}") }
    }
    fun signalAvailability(state: HamClockFeedState) = when (state) {
        HamClockFeedState.LIVE, HamClockFeedState.CACHED -> HamClockEvidenceAvailability.CURRENT
        HamClockFeedState.DEGRADED -> HamClockEvidenceAvailability.DEGRADED
        HamClockFeedState.STALE -> HamClockEvidenceAvailability.STALE
        HamClockFeedState.UNAVAILABLE -> HamClockEvidenceAvailability.UNAVAILABLE
    }
    val bandAvailability = remember(features.clusterConnection, features.rbnSourceSnapshot, neuralDx.mySignal,
        neuralDx.wsprPersonal, hamClockSettings.document.settings) { mapOf(
        "CLUSTER" to if (features.clusterConnection.state == ClusterConnectionState.CONNECTED) HamClockEvidenceAvailability.CURRENT else HamClockEvidenceAvailability.UNAVAILABLE,
        "PSK" to if (!hamClockSettings.document.settings.pskReporter.enabled) HamClockEvidenceAvailability.DISABLED else signalAvailability(
            combinedSignalFeedState(neuralDx.mySignal.beingHeardState, neuralDx.mySignal.hearingState)),
        "RBN" to when (features.rbnSourceSnapshot.state) {
            HamClockRbnSourceState.CURRENT, HamClockRbnSourceState.EMPTY -> HamClockEvidenceAvailability.CURRENT
            HamClockRbnSourceState.STALE -> HamClockEvidenceAvailability.STALE
            HamClockRbnSourceState.ERROR -> HamClockEvidenceAvailability.ERROR
            HamClockRbnSourceState.DISABLED -> HamClockEvidenceAvailability.DISABLED
            else -> HamClockEvidenceAvailability.UNAVAILABLE
        },
        "WSPR" to if (!hamClockSettings.document.settings.wspr.personalEnabled) HamClockEvidenceAvailability.DISABLED
            else signalAvailability(neuralDx.wsprPersonal.sourceState),
    ) }
    val bandStationId = operatingContext.stationProfileId.value.takeIf { wavelog.logMode == LogMode.WAVELOG }
    val bandStationCall = operatingContext.stationCallsign.value
    val bandStationGrid = operatingContext.stationGrid.value
    LaunchedEffect(bandEvidence, bandAvailability, hamClockSettings.document.settings.bandHealth,
        database.changeToken(), bandStationId, bandStationCall) {
        progress.refreshBandHealth(bandEvidence, bandAvailability, hamClockSettings.document.settings.bandHealth,
            bandStationId, bandStationCall)
    }
    LaunchedEffect(clusterBandEvidenceSpots, features.rbnObservations, neuralDx.mySignal, neuralDx.wsprPersonal,
        bandAvailability, bandStationId, bandStationCall, bandStationGrid, progress.snapshot.needs,
        neuralDx.dxNewsSnapshot, features.solar, features.sunspotNumber, neuralDx.weather, neuralDx.lightning) {
        fun sourceState(value: HamClockEvidenceAvailability) = when (value) {
            HamClockEvidenceAvailability.CURRENT -> OutlookSourceState.CURRENT
            HamClockEvidenceAvailability.STALE -> OutlookSourceState.STALE
            HamClockEvidenceAvailability.DEGRADED -> OutlookSourceState.DEGRADED
            HamClockEvidenceAvailability.DISABLED -> OutlookSourceState.DISABLED
            HamClockEvidenceAvailability.ERROR, HamClockEvidenceAvailability.UNAVAILABLE -> OutlookSourceState.UNAVAILABLE
        }
        val observations = clusterBandEvidenceSpots.map { spot -> OutlookEvidence(
            spot.id, "CLUSTER", spot.receivedEpoch, spot.callsign, spot.spotter, spot.band, spot.mode,
            spot.latitude, spot.longitude, distanceKm = spot.distanceKm.takeIf { it > 0 }) } +
            features.rbnObservations.map { row -> OutlookEvidence(
                row.id, "RBN", row.observedEpoch, row.dxCall, row.skimmerCall, row.band, row.mode,
                row.dxPoint?.latitude, row.dxPoint?.longitude, row.snr) } +
            neuralDx.mySignal.reports.map { row -> OutlookEvidence(
                signalReportReference(row), if (row.direction == SignalDirection.BEING_HEARD) "PSK_BEING_HEARD" else "PSK_HEARING",
                row.epoch, row.callsign, row.receiverCallsign, row.band, row.mode, row.latitude, row.longitude, row.snr, row.distanceKm) } +
            neuralDx.wsprPersonal.reports.map { row -> OutlookEvidence(
                signalReportReference(row), if (row.direction == SignalDirection.BEING_HEARD) "WSPR_BEING_HEARD" else "WSPR_HEARING",
                row.epoch, row.callsign, row.receiverCallsign, row.band, "WSPR", row.latitude, row.longitude, row.snr, row.distanceKm) }
        val pskState = sourceState(bandAvailability["PSK"] ?: HamClockEvidenceAvailability.UNAVAILABLE)
        val wsprState = sourceState(bandAvailability["WSPR"] ?: HamClockEvidenceAvailability.UNAVAILABLE)
        val candidates = neuralDx.currentOpportunities.map { row -> OutlookCandidate(row.callsign,
            OutlookCandidateSource.CURRENTLY_OBSERVED, row.band, row.mode, row.reason, row.observedEpoch) } +
            clusterBandEvidenceSpots.filter(AndroidDXSpot::watchlisted).map { row -> OutlookCandidate(row.callsign,
                OutlookCandidateSource.WATCHLIST, row.band, row.mode, row.reason, row.receivedEpoch) } +
            progress.snapshot.needs.mapNotNull { need -> need.dxSpot?.let { row -> OutlookCandidate(row.callsign,
                OutlookCandidateSource.NEEDED, row.band, row.mode, need.reasons.joinToString(" · "), row.receivedEpoch) } } +
            neuralDx.dxNewsSnapshot.merged.filter { it.sourceId == "ng3k" || it.activityEndEpoch != null }
                .flatMap { item -> item.callsigns.map { call -> OutlookCandidate(call,
                OutlookCandidateSource.SCHEDULED, detail = item.title, epoch = item.publishedEpoch) } }
        val logSummary = withContext(Dispatchers.IO) { database.neuralLogSummary(bandStationId) }
        neuralDx.outlook.submit(NeuralOutlookInput(
            stationProfileId = bandStationId.orEmpty(), stationCallsign = bandStationCall, stationGrid = bandStationGrid,
            epoch = Instant.now().epochSecond, evidence = observations,
            sourceStates = mapOf(
                "CLUSTER" to sourceState(bandAvailability["CLUSTER"] ?: HamClockEvidenceAvailability.UNAVAILABLE),
                "RBN" to sourceState(bandAvailability["RBN"] ?: HamClockEvidenceAvailability.UNAVAILABLE),
                "PSK_BEING_HEARD" to pskState, "PSK_HEARING" to pskState,
                "WSPR_BEING_HEARD" to wsprState, "WSPR_HEARING" to wsprState,
            ),
            sfi = features.solar.flux.takeIf { features.solar.valid }?.toDouble(), ssn = features.sunspotNumber?.toDouble(),
            aIndex = features.solar.aIndex.takeIf { features.solar.valid }?.toDouble(),
            kp = features.solar.kpIndex.takeIf { features.solar.valid }?.toDouble(),
            tropoIndex = neuralDx.weather.tropoIndex, lightningCount = neuralDx.lightning.strikes.size,
            qsoSummary = logSummary, candidates = candidates,
        ))
    }
    Surface(Modifier.fillMaxSize(), color = Chassis, contentColor = Ink) {
    Column(Modifier.fillMaxSize()) {
        if (selectedProfile.backendKind == RadioBackendKind.REMOTE_STATION) {
            RemoteConnectionBanner(remoteRuntime.snapshot)
        }
        Box(Modifier.weight(1f)) { when (destination) {
        Destination.HOME -> HamClockHomeScreen(radio, app, features, neuralDx, portable, database, wavelog, cty, callbook,
            publicProviders, hamClockSettings, operations, progress.bandHealthSnapshot, send, openDx, openPortable, openProgress, openOperations,
            openLogbook, closeEq, openDigi, openGroupsIo, foreground, operatingContext, requestHomeReceiveTune,
            openHomeQso)
        Destination.RADIO -> Column(Modifier.fillMaxSize()) {
            if (compact) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SegmentedButton(true, {}, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Radio") }
                SegmentedButton(false, openDigi, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Digi") }
            }
            PotaActivationStrip(activation, radio, openActivation)
            BoxWithConstraints(Modifier.weight(1f)) {
                val supportsCompactBandMap = maxWidth >= 760.dp
                val showCompactBandMap = bandMaps.settings.showOnRadioScreen && supportsCompactBandMap
                Column(Modifier.fillMaxSize()) {
                    if (supportsCompactBandMap) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        FilterChip(
                            selected = showCompactBandMap,
                            onClick = { bandMaps.updateSettings { it.copy(showOnRadioScreen = !it.showOnRadioScreen) } },
                            label = { Text(if (showCompactBandMap) "VERTICAL BAND MAP · ON" else "VERTICAL BAND MAP · OFF") },
                        )
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (showCompactBandMap) 8.dp else 0.dp)) {
                        if (showCompactBandMap) CompactRadioBandMap(bandMaps, database, cty, operatingContext, app, workspaceAction,
                            Modifier.fillMaxHeight().weight(.17f))
                        Box(Modifier.fillMaxHeight().weight(if (showCompactBandMap) .83f else 1f)) {
                        if (selectedProfile.backendKind == RadioBackendKind.NATIVE_FLEX) FlexRadioScreen(flex, openLogbook)
                        else if (selectedProfile.backendKind == RadioBackendKind.NATIVE_TCI) TciRadioCockpit(
                            tciRuntime, platformSnapshot, panadapter, tciRxAudio, scanner, sdrOperationalV2, sdrWorkbenchV4, localReceivers,
                            tciTransmit, app.presets, dispatchPlatform, connect, disconnectPlatform, debugSdrLab, openDigi,
                            { screenScope.launch { tciTransmit.requestRxAndRecheck() } },
                            { screenScope.launch { tciTransmit.tune("TCI UI", 0) } })
                        else if (integratedRadioSelected) IntegratedRadioPlatformScreen(
                            selectedProfile,
                            platformSnapshot,
                            detail,
                            connect,
                            disconnectPlatform,
                            dispatchPlatform,
                        )
                        else if (!compact || !app.panadapterEnabled) RadioScreen(radio, detail, app, database, mutations, wavelog, callbook, cty,
                            features, voiceStore, voiceTx, connect, send, direct, requestVoice, clearCwDecode,
                            portableDraft, consumePortableDraft, portable::notifyQsoChanged)
                        else Column(Modifier.fillMaxSize()) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                SegmentedButton(!compactPanadapter, { compactPanadapter = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Controls") }
                                SegmentedButton(compactPanadapter, { compactPanadapter = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Panadapter") }
                            }
                            if (compactPanadapter) PanadapterScreen(panadapter, radio, features.liveSpots, true, localReceivers, sdrWorkbenchV4) { compactPanadapter = false }
                            else RadioScreen(radio, detail, app, database, mutations, wavelog, callbook, cty, features, voiceStore, voiceTx,
                                connect, send, direct, requestVoice, clearCwDecode, portableDraft, consumePortableDraft, portable::notifyQsoChanged)
                        }
                        }
                    }
                }
            }
        }
        Destination.REMOTE -> RemoteStationScreen(
            app, remoteRuntime, remoteFactory,
            selectAndConnect = { station ->
                disconnectPlatform()
                selectRemoteStation(station)
            },
            disconnect = disconnectPlatform,
            globalStop = { screenScope.launch { remoteFactory.active?.requestReceive() } },
        )
        Destination.DIGI -> DigiRfPathWrapper(rfObservations) {
            IntegratedDigiWorkspace(integratedDigiPage, setIntegratedDigiPage, digi, radio, compact, chaser)
        }
        Destination.CONTEST -> if (app.contestEnabled) IntegratedContestWorkspace(contest, keyer.snapshot(), bandMaps, features,
            onOpenLogbook = openLogbook,
            onOpenSettings = openSettings) else EqUnavailableScreen("Contest is hidden in Settings", openSettings)
        Destination.BAND_MAPS -> BandMapScreen(bandMaps, database, features, neuralDx, portable, cty, contest, chaser,
            operatingContext, bandMapKeyer, app, workspaceAction)
        Destination.PANADAPTER -> if (selectedProfile.backendKind == RadioBackendKind.NATIVE_TCI)
            TciPanadapterPanel(tciRuntime.snapshot, panadapter, dispatchPlatform, scanner, sdrOperationalV2, sdrWorkbenchV4,
                localReceivers, app.presets, openDigi)
        else if (app.panadapterEnabled && selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT)
            PanadapterScreen(panadapter, radio, features.liveSpots, compact, localReceivers, sdrWorkbenchV4)
        else RadioScreen(radio, detail, app, database, mutations, wavelog, callbook, cty, features, voiceStore, voiceTx,
            connect, send, direct, requestVoice, clearCwDecode, portableDraft, consumePortableDraft, portable::notifyQsoChanged)
        Destination.EQ -> if (selectedProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT) EqStudioScreen(eqStudio, radio, compact, closeEq)
            else EqUnavailableScreen("EQ is unavailable for ${app.radioFamily.displayName}; SHOW only exposes this setup state and sends no CAT command.", openSettings)
        Destination.LOGBOOK -> Column(Modifier.fillMaxSize()) {
            PotaActivationStrip(activation, radio, openActivation)
            Box(Modifier.weight(1f)) { LogbookScreen(radio, database, logbookController, mutations, wavelog, wavelogNative, syncHub, callbook, app,
                openSync, openProgress, progress.logbookRequest, progress::consumeLogbookRequest, homeQsoId, consumeHomeQso) }
        }
        Destination.PROGRESS -> RfIntelligenceWorkspace(rfObservations, sdrWorkbenchV4) {
            ProgressScreen(progress, features, portable, syncHub, cty, wavelog.logMode,
                wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG }.orEmpty(),
                if (wavelog.logMode == LogMode.WAVELOG) wavelog.selectedStation?.callsign.orEmpty() else app.stationCallsign, compact,
                outlook = neuralDx.outlook.snapshot, openDx = openDx,
                openOutlook = { neuralDx.requestPage(NeuralDxPage.INSIGHT); openDx() },
                openDxEvidence = { band -> neuralDx.requestBandEvidence(band); openDx() }, openPortable = openPortable,
                openLogbook = openLogbook, openLogbookFilter = { filter -> progress.requestLogbook(filter); openLogbook() }, openSync = openSync)
        }
        Destination.SYNC -> SyncHubScreen(database, mutations, syncHub, wavelog, wavelogNative, openLogbook)
        Destination.PRESETS -> PresetsScreen(radio, app, send, bandStacks, scanner, panadapter.frame, tciRuntime.snapshot, dispatchPlatform) { entry ->
            dispatchPlatform(RadioPlatformAction(RadioActionClass.SAFE_SET, "frequency", longValue = entry.frequencyHz))
            dispatchPlatform(RadioPlatformAction(RadioActionClass.SAFE_SET, "mode", textValue = entry.mode))
            dispatchPlatform(RadioPlatformAction(RadioActionClass.SAFE_SET, "bandwidth", longValue = entry.filterHz.toLong()))
        }
        Destination.DX -> DXScreen(neuralDx, features, database, wavelog, callbook, cty, app,
            hamClockSettings.document.settings.cluster, hamClockSettings.document.settings.dxNews,
            hamClockSettings.document.settings.bandHealth, progress.bandHealthSnapshot,
            { value -> hamClockSettings.updateSettings { it.copy(dxNews = value) } },
            { value -> hamClockSettings.updateSettings { it.copy(bandHealth = value) } }, progress.snapshot.needs,
            operations, openOperations, send, requestHomeReceiveTune) { callsign ->
            progress.requestLogbook(logbookFilterForDimension("callsign", callsign)); openLogbook()
        }
        Destination.PORTABLE -> PortableWorkspaceScreen(portable, activation, radio, app.stationGrid, foreground, compact,
            app, database, mutations, wavelog, callbook, cty, tunePortable, tuneLogPortable,
            progress.snapshot.needs.mapNotNull { need -> need.portableSpot?.id?.let { it to need.reasons } }.toMap(), openLogbook,
            operations.nextPlan, openOperations)
        Destination.GROUPS_IO -> GroupsIoScreen(groupsIo, compact)
        Destination.OPERATIONS -> OperationsScreen(operations, portable, activation, features, progress, mutations, wavelog, callbook, cty,
            app, compact, openDx, openPortable, openLogbook, { frequency, mode ->
                requestHomeReceiveTune(frequency, mode, "Operations satellite receive preview",
                    "Review receive-only downlink change")
            }, prepareSatelliteLogger)
        Destination.ROTATOR -> if (app.rotatorEnabled) IntegratedRotatorScreen(rotator)
            else EqUnavailableScreen("Rotator workspace is hidden in Settings", openSettings)
        Destination.SETTINGS -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { SettingsScreen(radio, detail, database, mutations, features, neuralDx, wavelog, syncHub, callbook, cty, audio, panadapter, app,
                transport, flex, digi, voiceStore, voiceAudio, voiceTx, groupsIo, operatingContext, keyerProfiles, keyer, repeatCq,
                 bandMaps, contest, chaser, hamlibModels, rotator, tciRuntime, tciTransmit, tciRxAudio, scanner, sdrOperationalV2, sdrWorkbenchV4,
                 localReceivers, rfObservations, bandStacks, announcements, debugSdrLab, controlSurfaces,
                openEq, openContest, openSync, openDigi, openGroupsIo, openRotator,
                disconnectPlatform, connect, direct) }
        }
        }
    }
    }
}
}

@Composable private fun RemoteConnectionBanner(snapshot: RemoteRuntimeSnapshot) {
    val healthy = snapshot.state == RemoteConnectionState.READY
    Surface(color = (if (healthy) Healthy else Danger).copy(alpha = .16f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("REMOTE · ${snapshot.stationName ?: "STATION"}", color = if (healthy) Healthy else Danger,
                fontWeight = FontWeight.Black)
            Text("${snapshot.state.name} · WRITER ${snapshot.writerLease.name} · TX ${snapshot.txLease.name} · ROTATOR ${snapshot.rotatorLease.name}",
                color = Ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun EqUnavailableScreen(message: String, openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("EQ")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Equalizer, contentDescription = null, tint = Hold, modifier = Modifier.size(32.dp))
                Text("EQ UNAVAILABLE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(message, color = Muted)
                Text("No EQ CAT command is available from this state.", color = Hold, fontWeight = FontWeight.Bold)
                Button(openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("OPEN SETTINGS") }
            }
        }
    }
}

@Composable private fun Header(title: String, state: RadioState? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painter = painterResource(R.drawable.shackcq_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                contentScale = ContentScale.Fit,
            )
            Column { Text("SHACKCQ", color = Amber, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text(title.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall) }
        }
        state?.let { StatusChip(if (it.connected) "RADIO LIVE" else "RADIO OFFLINE", it.connected) }
    }
}

@Composable private fun StatusChip(text: String, good: Boolean) {
    Surface(color = (if (good) Healthy else Danger).copy(alpha = .15f), shape = MaterialTheme.shapes.small) {
        Text(text, color = if (good) Healthy else Danger, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable private fun Instrument(state: RadioState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF201708))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VFO A · ${state.mode}", color = Amber, fontWeight = FontWeight.Bold)
                Text(if (state.transmitting) "TRANSMIT" else "RECEIVE", color = if (state.transmitting) Danger else Healthy, fontWeight = FontWeight.Bold)
            }
            Text(state.frequencyText, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 48.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VFO B  ${if (state.frequencyBHz > 0) formatRadioFrequency(state.frequencyBHz) else "—.———"} MHz",
                    color = if (state.split) Hold else Muted, fontFamily = FontFamily.Monospace)
                Text(listOfNotNull(if (state.split) "SPLIT" else null, if (state.rit) "RIT" else null,
                    if (state.xit) "XIT" else null, if (state.preamp) "PRE" else null, if (state.attenuator) "ATT" else null).joinToString("  "), color = Hold)
            }
            Text(if (state.transmitting && state.swrTenths >= 0) "RF ${state.rfOutputTenths / 10.0} W · SWR ${state.swrTenths / 10.0}:1"
                else "S-METER  ${state.meter}", color = Muted, style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator({ if (state.transmitting) (state.rfOutputTenths / 120f).coerceIn(0f, 1f) else (state.meter / 30f).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth(), color = Amber)
        }
    }
}

@Composable private fun HealthTile(label: String, value: String, good: Boolean, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = if (good) Healthy else Ink, fontWeight = FontWeight.SemiBold, maxLines = 2)
    } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RadioScreen(state: RadioState, detail: String, app: AppController, database: QsoDatabase,
    mutations: QsoMutationCoordinator,
    wavelog: WavelogController, callbook: CallbookController, cty: CtyController, features: FeatureController,
    voiceStore: VoiceMacroStore, voiceTx: VoiceMacroTransmitController, connect: () -> Unit, send: (String) -> Unit,
    direct: (String) -> Unit, requestVoice: (Int) -> Unit, clearCwDecode: () -> Unit,
    portableDraft: PortableLogDraft?, consumePortableDraft: () -> Unit, onQsoSaved: () -> Unit) {
    var previousState by remember { mutableStateOf<RadioState?>(null) }
    var radioFeedback by remember { mutableStateOf<RadioFeedback?>(null) }
    var feedbackVisible by remember { mutableStateOf(false) }
    var feedbackGeneration by remember { mutableIntStateOf(0) }
    var feedbackBaseline by remember { mutableStateOf<RadioState?>(null) }
    var stationInsight by remember { mutableStateOf<StationInsight?>(null) }
    var identityVisible by remember { mutableStateOf(false) }
    val cwMacroContext = remember { mutableStateOf(CwMacroContext(myCall = app.stationCallsign, myGrid = app.stationGrid)) }
    LaunchedEffect(state.revision) {
        val previous = previousState
        previousState = state
        if (!state.connected) {
            radioFeedback = null
            feedbackVisible = false
            feedbackBaseline = null
            return@LaunchedEffect
        }
        val burst = previous?.let { mergeRadioFeedbackBurst(feedbackBaseline, it, state) }
        burst?.let {
            feedbackBaseline = it.baseline
            radioFeedback = it.feedback
            feedbackVisible = true
            feedbackGeneration++
        }
    }
    LaunchedEffect(feedbackGeneration) {
        if (feedbackGeneration > 0) {
            delay(1_200)
            feedbackVisible = false
            feedbackBaseline = null
        }
    }
    LaunchedEffect(wavelog.logMode, wavelog.stationId, stationInsight?.record?.callsign) {
        val current = stationInsight ?: return@LaunchedEffect
        val stationScope = if (wavelog.logMode == LogMode.LOCAL) null
            else wavelog.stationId.takeIf(String::isNotBlank) ?: "__NO_SELECTED_WAVELOG_STATION__"
        stationInsight = withContext(Dispatchers.IO) { database.stationInsight(current.record, stationScope) }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF090B0C)).navigationBarsPadding().padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().weight(1.25f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KxStatusRail(app.radioFamily, state, detail, connect, direct)
                if (app.radioFamily == RadioFamily.ELECRAFT_KX2)
                    CompactKx2Face(state, send, radioFeedback, feedbackVisible, Modifier.fillMaxWidth().weight(1f))
                else CompactKx3Face(state, send, radioFeedback, feedbackVisible, Modifier.fillMaxWidth().weight(1f))
            }
            Row(Modifier.fillMaxWidth().weight(1.75f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        isCwMacroMode(state.mode) -> CwMacroStrip(state, app, cwMacroContext.value, send, Modifier.fillMaxWidth().height(70.dp))
                        isVoiceMacroMode(state.mode) -> VoiceMacroStrip(state, voiceStore, voiceTx, requestVoice,
                            Modifier.fillMaxWidth().heightIn(min = 54.dp))
                    }
            CompactLogger(state, database, mutations, wavelog, callbook, cty, app, send, cwMacroContext, portableDraft, consumePortableDraft, onQsoSaved,
                        onInsight = { stationInsight = it; identityVisible = true },
                        onInsightCleared = { stationInsight = null; identityVisible = false },
                        modifier = Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cwActive = isCwMacroMode(state.mode)
                    Box(Modifier.fillMaxWidth().weight(if (cwActive) 1.2f else 1f)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cwActive) CwDecodeLine(state.cwDecodedText, state.connected, clearCwDecode,
                                Modifier.fillMaxWidth().heightIn(min = 48.dp))
                            if (app.radioFamily == RadioFamily.ELECRAFT_KX2)
                                CompactKx2TuningDeck(state, send, Modifier.fillMaxWidth().weight(1f))
                            else CompactKx3TuningDeck(state, send, Modifier.fillMaxWidth().weight(1f))
                        }
                        stationInsight?.takeIf { identityVisible }?.let { insight ->
                            CallbookIdentityOverlay(insight.record, { identityVisible = false }, Modifier.fillMaxSize())
                        }
                    }
                    LiveSpotsPanel(features, database, wavelog, callbook, cty, app, send, stationInsight,
                        Modifier.fillMaxWidth().weight(3f))
                }
            }
        }
    }
}

@Composable private fun VoiceMacroStrip(state: RadioState, store: VoiceMacroStore,
    tx: VoiceMacroTransmitController, send: (Int) -> Unit, modifier: Modifier = Modifier) {
    val configured = store.slots.filter(VoiceMacroSlot::exists)
    if (!isVoiceMacroMode(state.mode) || configured.isEmpty()) return
    Surface(color = Color(0xFF15191A), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = .72f)), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                configured.forEach { slot ->
                    Kx3DirectKey(slot.label, state.connected && !tx.isBusy, { send(slot.index) }, Modifier.weight(1f),
                        secondary = true, risky = true, compact = true)
                }
                if (tx.isBusy) Button(tx::forceRx, colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("STOP / RX") }
            }
            if (tx.isBusy) LinearProgressIndicator({ tx.progress }, Modifier.fillMaxWidth(), color = Danger)
        }
    }
}

@Composable private fun CwDecodeLine(text: String, connected: Boolean, clear: () -> Unit,
    modifier: Modifier = Modifier) {
    val display = text.takeLast(48).ifBlank { if (connected) "WAITING FOR RADIO TEXT…" else "CONNECT RADIO TO DECODE" }
    Surface(onClick = clear, enabled = text.isNotEmpty(), color = Color(0xFF171307),
        contentColor = Hold, shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = .82f)), modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TEXT (CW)", color = Amber, fontWeight = FontWeight.Black, fontSize = 13.sp,
                letterSpacing = .5.sp, maxLines = 1)
            Box(Modifier.padding(horizontal = 11.dp).width(1.dp).height(22.dp).background(Amber.copy(alpha = .45f)))
            Text(display, color = if (text.isEmpty()) Amber.copy(alpha = .78f) else Hold,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            if (text.isNotEmpty()) Text("TAP TO CLEAR", color = Amber.copy(alpha = .72f),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable private fun CwMacroStrip(state: RadioState, app: AppController, context: CwMacroContext, send: (String) -> Unit,
    modifier: Modifier = Modifier) {
    var resolutionError by remember { mutableStateOf("") }
    val configured = (0 until CW_MACRO_COUNT).mapNotNull { index ->
        app.macroTexts.getOrNull(index)?.takeIf(String::isNotBlank)?.let { text ->
            Triple(index, app.macroLabels.getOrNull(index).orEmpty().ifBlank { "M${index + 1}" }, text)
        }
    }
    if (!isCwMacroMode(state.mode) || configured.isEmpty()) return
    Surface(color = Color(0xFF15191A), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hold.copy(alpha = .72f)), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            configured.forEach { (_, label, text) ->
                Kx3DirectKey(label, state.connected, {
                    val resolved = resolveCwMacroTemplate(text, context)
                    resolutionError = resolved.error.orEmpty()
                    if (resolved.error == null) cwMacroCommand(resolved.text)?.let(send)
                },
                    Modifier.weight(1f), secondary = true, risky = true, compact = true)
            }
        }
        if (resolutionError.isNotBlank()) Text(resolutionError, color = Danger, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable private fun CompactKx3Face(state: RadioState, send: (String) -> Unit, feedback: RadioFeedback?,
    feedbackVisible: Boolean, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF0B0D0E), shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF454C50)), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                FaceplateScrew(); Spacer(Modifier.weight(1f))
                Text("ELECRAFT KX3", color = Ink, fontWeight = FontWeight.Black, letterSpacing = 3.sp, style = MaterialTheme.typography.labelLarge)
                Text("  ·  SHACKCQ", color = Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                FaceplateScrew()
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Kx3KeyMatrix(
                    columns = listOf(
                        listOf(Kx3KeySpec("BAND +", "SWT08;"), Kx3KeySpec("BAND −", "SWT41;"), Kx3KeySpec("FREQ ENT", "SWT10;")),
                        listOf(Kx3KeySpec("RCL", "SWH08;"), Kx3KeySpec("STORE", "SWH41;"), Kx3KeySpec("SCAN", "SWH10;")),
                        listOf(Kx3KeySpec("MSG", "SWT11;", true), Kx3KeySpec("ATU TUNE", "SWT44;", true), Kx3KeySpec("XMIT", "SWT16;", true)),
                        listOf(Kx3KeySpec("REC", "SWH11;", true), Kx3KeySpec("ANT", "SWH44;"), Kx3KeySpec("TUNE", "SWH16;", true)),
                    ), state.connected, send, Modifier.width(318.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Kx3CompactLcd(state, send, Modifier.weight(4f).fillMaxWidth())
                    Kx3ReceiveKeyRow(state.connected, send, Modifier.weight(1f).fillMaxWidth())
                }
                Box(Modifier.width(318.dp).fillMaxHeight()) {
                    Kx3KeyMatrix(
                        columns = listOf(
                            listOf(Kx3KeySpec("MODE", "SWT14;"), Kx3KeySpec("DATA", "SWT17;"), Kx3KeySpec("RIT", "SWT18;")),
                            listOf(Kx3KeySpec("ALT", "SWH14;"), Kx3KeySpec("TEXT", "SWH17;"), Kx3KeySpec("PF1", "SWH18;")),
                            listOf(Kx3KeySpec("A/B", "SWT24;"), Kx3KeySpec("A → B", "SWT25;"), Kx3KeySpec("XIT", "SWT26;")),
                            listOf(Kx3KeySpec("REV", "SWH24;"), Kx3KeySpec("SPLIT", "SWH25;"), Kx3KeySpec("PF2", "SWH26;")),
                        ), state.connected, send, Modifier.fillMaxSize())
                    RadioActionVisibility(feedback, feedbackVisible, "KX3", Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable private fun CompactKx2Face(state: RadioState, send: (String) -> Unit, feedback: RadioFeedback?,
    feedbackVisible: Boolean, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF111314), shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF4B5052)), modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    FaceplateScrew(); Spacer(Modifier.weight(1f))
                    Text("ELECRAFT KX2 TRANSCEIVER", color = Ink, fontWeight = FontWeight.Black,
                        letterSpacing = 2.4.sp, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f)); FaceplateScrew()
                }
                Kx2CompactLcd(state, send, Modifier.fillMaxWidth().weight(1.35f))
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    kx2FaceKeys.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEach { key ->
                                Kx2DualKey(key, state.connected, send, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Kx3DirectKey("BAND −", state.connected,
                            { send(kx2AdjacentBandCommand(state.frequencyHz, -1)) }, Modifier.weight(1f), compact = true)
                        Kx3DirectKey("BAND +", state.connected,
                            { send(kx2AdjacentBandCommand(state.frequencyHz, 1)) }, Modifier.weight(1f), compact = true)
                        Kx3DirectKey("FREQ ENTRY", state.connected, { send("SWH41;") }, Modifier.weight(1f), compact = true)
                        Kx3DirectKey("OFS / B", state.connected, { send("SWT35;") }, Modifier.weight(1f), compact = true)
                        Kx3DirectKey("CLR", state.connected, { send("SWH35;") }, Modifier.weight(1f), secondary = true, compact = true)
                    }
                }
            }
            RadioActionVisibility(feedback, feedbackVisible, "KX2", Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Kx2DualKey(key: Kx2FaceKey, enabled: Boolean, send: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val edge = if (key.transmitRisk) Color(0xFFB39A57) else Color(0xFF777E81)
    Surface(color = if (enabled) Color(0xFF34393B) else Color(0xFF242829), contentColor = Ink,
        shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, edge), modifier = modifier
            .padding(1.dp).semantics { contentDescription = "${key.tapLabel}; hold for ${key.holdLabel}" }
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = { send(key.tapCommand) },
                onLongClick = { send(key.holdCommand) })) {
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text("${key.tapLabel}  ·  HOLD ${key.holdLabel}", color = if (enabled) Ink else Muted,
                fontWeight = FontWeight.Black, fontSize = 9.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable private fun Kx2CompactLcd(state: RadioState, send: (String) -> Unit, modifier: Modifier = Modifier) {
    val ink = Color(0xFF241A02)
    val splitInk = Color(0xFF7E1510)
    var picker by remember { mutableStateOf<Kx3LcdPicker?>(null) }
    Surface(shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF343839)),
        modifier = modifier) {
        Row(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF7CA47), Color(0xFFE6A50E))))
            .padding(horizontal = 9.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kx2MeterAndFilter(state, ink, { picker = Kx3LcdPicker.FILTER }, Modifier.fillMaxHeight().weight(.42f))
            Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().weight(.58f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.fillMaxHeight().weight(1f).clickable(enabled = state.connected) { picker = Kx3LcdPicker.BAND }
                        .padding(horizontal = 16.dp, vertical = 7.dp)) {
                        SegmentedReadout(if (state.connected) formatRadioFrequency(state.frequencyHz) else "--.---.---", ink,
                            Modifier.fillMaxSize())
                    }
                    Kx2ModeColumn(state, ink, splitInk, { picker = Kx3LcdPicker.MODE }, Modifier.width(65.dp).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(.42f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Kx2Annunciators(state, ink, { picker = Kx3LcdPicker.FILTER }, Modifier.weight(1.15f).fillMaxHeight())
                    SegmentedReadout(if (state.frequencyBHz > 0) formatRadioFrequency(state.frequencyBHz) else "--.---.---",
                        if (state.split) splitInk else ink, Modifier.weight(.85f).fillMaxHeight().padding(vertical = 5.dp))
                    Column(Modifier.width(46.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly) {
                        Box(Modifier.border(1.dp, ink).padding(horizontal = 4.dp)) { Text("B", color = ink, fontWeight = FontWeight.Black) }
                        Text(if (state.split) "TX" else "", color = splitInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    picker?.let { selected ->
        Kx3LcdPickerDialog(selected, state.mode, { command -> send(command); picker = null }, { picker = null }, kx2 = true)
    }
}

@Composable private fun Kx2ModeColumn(state: RadioState, ink: Color, activeInk: Color, action: () -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier.clickable(enabled = state.connected, onClick = action), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly) {
        Text("▰", color = ink.copy(alpha = if (state.connected) 1f else .28f), fontSize = 12.sp)
        Box(Modifier.border(1.dp, ink).padding(horizontal = 4.dp)) { Text("A", color = ink, fontWeight = FontWeight.Black) }
        listOf("LSB", "USB", "CW", "REV", "DATA", "AM-S").forEach { label ->
            val selected = label == state.mode || (label == "REV" && state.mode == "CW-R") ||
                (label == "DATA" && state.mode.startsWith("DATA"))
            Text(label, color = if (selected) activeInk else ink.copy(alpha = .42f), fontWeight = FontWeight.Black,
                fontSize = 10.sp, lineHeight = 10.sp)
        }
    }
}

@Composable private fun Kx2MeterAndFilter(state: RadioState, ink: Color, filterAction: () -> Unit,
    modifier: Modifier = Modifier) {
    val swr = if (state.transmitting && state.swrTenths >= 10) (state.swrTenths - 10) / 25f else 0f
    Column(modifier.clickable(enabled = state.connected, onClick = filterAction), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Kx3BarMeter("S", "1  3  5  7  9  +20  40  60", state.meter / 21f, ink, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Kx3BarMeter("SWR", "1  2  3", swr, ink, Modifier.weight(1f))
            Kx3BarMeter("RF", "5  10", state.rfOutputTenths / 120f, ink, Modifier.weight(1f))
        }
        Canvas(Modifier.weight(.78f).fillMaxWidth()) {
            val center = size.width * .48f
            val half = (state.bandwidthHz.coerceIn(100, 4000) / 4000f) * size.width * .22f + size.width * .08f
            val path = Path().apply {
                moveTo(center - half, size.height * .82f); lineTo(center - half * .7f, size.height * .2f)
                lineTo(center + half * .7f, size.height * .2f); lineTo(center + half, size.height * .82f)
            }
            drawPath(path, ink, style = Stroke(2.dp.toPx()))
            drawLine(ink, Offset(size.width * .12f, size.height * .82f), Offset(size.width * .84f, size.height * .82f), 1.5.dp.toPx())
        }
        Text("NTCH   ◀  XFIL · FL1  ▶   II", color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable private fun Kx2Annunciators(state: RadioState, ink: Color, filterAction: () -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("VOX", "QSK", "ANT1", "RX", "ATU").forEach { Text(it, color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp) }
            Text(if (state.rit) "RIT" else "RIT", color = ink.copy(alpha = if (state.rit) 1f else .3f), fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text(if (state.xit) "XIT" else "XIT", color = ink.copy(alpha = if (state.xit) 1f else .3f), fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("SPLT", color = ink.copy(alpha = if (state.split) 1f else .3f), fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(agcLabel(state.agcMode), color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("ATT", color = ink.copy(alpha = if (state.attenuator) 1f else .3f), fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("PRE", color = ink.copy(alpha = if (state.preamp) 1f else .3f), fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("NB", color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("NR", color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp)
            Text("XFIL FL1", color = ink, fontWeight = FontWeight.Black, fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = state.connected, onClick = filterAction))
        }
    }
}

@Composable private fun RadioActionVisibility(feedback: RadioFeedback?, visible: Boolean, model: String,
    modifier: Modifier = Modifier) {
    AnimatedVisibility(visible && feedback != null,
        enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = .96f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = .98f), modifier = modifier) {
        feedback?.let { RadioActionOverlay(it, model, Modifier.fillMaxSize()) }
    }
}

@Composable private fun RadioActionOverlay(feedback: RadioFeedback, model: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xE6121718)).padding(12.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF17291F), contentColor = Ink, shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, Healthy), shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(feedback.title, color = Healthy, fontWeight = FontWeight.Black, fontSize = 13.sp,
                    letterSpacing = 1.1.sp, maxLines = 1)
                Text(feedback.value, color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                    fontSize = 24.sp, maxLines = 1, softWrap = false)
                if (feedback.details.isNotEmpty()) Text(feedback.details.joinToString("  ·  "), color = Healthy.copy(alpha = .9f),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$model LIVE", color = Healthy.copy(alpha = .72f), fontWeight = FontWeight.Bold, fontSize = 9.sp,
                    letterSpacing = .8.sp)
            }
        }
    }
}

private data class Kx3KeySpec(val label: String, val command: String, val requiresArm: Boolean = false)

@Composable private fun Kx3KeyMatrix(columns: List<List<Kx3KeySpec>>, connected: Boolean,
    send: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        columns.forEachIndexed { columnIndex, column ->
            Column(Modifier.weight(1f).fillMaxHeight()) {
                column.forEach { key ->
                    Kx3DirectKey(key.label, connected, { send(key.command) },
                        Modifier.weight(1f), secondary = columnIndex % 2 == 1, risky = key.requiresArm,
                        bold = columnIndex == 1 || columnIndex == 2)
                }
            }
        }
    }
}

@Composable private fun Kx3ReceiveKeyRow(connected: Boolean, send: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf(
        Kx3KeySpec("PRE", "SWT19;"), Kx3KeySpec("NR", "SWH19;"),
        Kx3KeySpec("ATTN", "SWT27;"), Kx3KeySpec("NB", "SWH27;"),
        Kx3KeySpec("APF", "SWT20;"), Kx3KeySpec("NTCH", "SWH20;"),
        Kx3KeySpec("SPOT", "SWT28;"), Kx3KeySpec("CWT", "SWH28;"),
        Kx3KeySpec("CMP", "SWT21;"), Kx3KeySpec("PITCH", "SWH21;"),
        Kx3KeySpec("DLY", "SWT29;"), Kx3KeySpec("VOX", "SWH29;"),
    )
    Row(modifier) {
        keys.forEachIndexed { index, key ->
            Kx3DirectKey(key.label, connected, { send(key.command) }, Modifier.weight(1f), secondary = index % 2 == 1,
                bold = true, compact = true)
        }
    }
}

@Composable private fun Kx3PairKeys(main: String, secondary: String, enabled: Boolean, mainAction: () -> Unit,
    secondaryAction: () -> Unit, modifier: Modifier = Modifier, risky: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Kx3DirectKey(main, enabled, mainAction, Modifier.weight(1f), risky)
        Kx3DirectKey(secondary, enabled, secondaryAction, Modifier.weight(1f), secondary = true, risky = risky)
    }
}

@Composable private fun Kx3DirectKey(label: String, enabled: Boolean, action: () -> Unit, modifier: Modifier = Modifier,
    secondary: Boolean = false, risky: Boolean = false, bold: Boolean = true, compact: Boolean = false) {
    val pressFlash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(4.dp)
    val baseCap = if (enabled) listOf(Color(0xFF818789), Color(0xFF555B5D), Color(0xFF363A3C))
        else listOf(Color(0xFF34383A), Color(0xFF282C2E), Color(0xFF202426))
    val flash = pressFlash.value
    val cap = baseCap.map { lerp(it, Color(0xFF167C43), flash) }
    val baseEdge = if (risky) Color(0xFFB39A57) else Color(0xFFA4AAAC)
    val edge = lerp(baseEdge, Healthy, flash)
    Button({
        scope.launch {
            pressFlash.snapTo(1f)
            pressFlash.animateTo(0f, tween(900))
        }
        action()
    }, enabled = enabled, modifier = modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 48.dp).padding(1.dp),
        shape = shape, border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) edge else Color(0xFF454A4C)),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Ink,
            disabledContainerColor = Color.Transparent, disabledContentColor = Muted),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 0.dp, disabledElevation = 0.dp)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(cap)), contentAlignment = Alignment.Center) {
            Text(label, color = if (!enabled) Muted else if (flash > .45f) Color.White else if (secondary) Hold else Ink,
                fontWeight = if (bold) FontWeight.Black else FontWeight.SemiBold,
                fontSize = if (compact) 11.sp else 14.sp, maxLines = 1, softWrap = false)
        }
    }
}

private enum class Kx3LcdPicker { BAND, MODE, FILTER }

@Composable private fun Kx3CompactLcd(state: RadioState, send: (String) -> Unit, modifier: Modifier = Modifier) {
    val lcdInk = Color(0xFF291D03)
    val splitInk = Color(0xFF8E1717)
    var picker by remember { mutableStateOf<Kx3LcdPicker?>(null) }
    Surface(shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF343839)), modifier = modifier) {
        Row(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8C945), Color(0xFFE3A00E)))).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kx3OriginalMeter(state, lcdInk, { picker = Kx3LcdPicker.FILTER }, Modifier.fillMaxHeight().weight(.40f))
            Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().weight(.61f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.fillMaxHeight().weight(1f).clickable(enabled = state.connected) { picker = Kx3LcdPicker.BAND }
                        .padding(horizontal = 42.dp, vertical = 13.dp)) {
                        SegmentedReadout(if (state.connected) formatRadioFrequency(state.frequencyHz) else "--.---.---", lcdInk,
                            Modifier.fillMaxSize())
                    }
                    Kx3VfoIndicator("A", state.mode, state.transmitting, lcdInk, splitInk, { picker = Kx3LcdPicker.MODE },
                        Modifier.width(58.dp).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(.39f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Kx3OriginalAnnunciators(state, lcdInk, { picker = Kx3LcdPicker.FILTER }, Modifier.fillMaxHeight().weight(1.22f))
                    SegmentedReadout(if (state.frequencyBHz > 0) formatRadioFrequency(state.frequencyBHz) else "--.---.---",
                        if (state.split) splitInk else lcdInk, Modifier.fillMaxHeight().weight(.78f).padding(vertical = 8.dp))
                    Kx3VfoIndicator("B", if (state.split) "SPLIT" else "", state.split, lcdInk, splitInk,
                        {}, Modifier.width(58.dp).fillMaxHeight())
                }
            }
        }
    }
    picker?.let { selected ->
        Kx3LcdPickerDialog(selected, state.mode, { command -> send(command); picker = null }, { picker = null })
    }
}

private fun agcLabel(value: Int) = when (value) { 2 -> "AGC-F"; 4 -> "AGC-S"; 0 -> "AGC OFF"; else -> "AGC --" }
private fun displayBandwidth(value: Int) = if (value > 0) "$value Hz" else "--"
private fun tenths(value: Int) = if (value >= 0) "%.1f".format(value / 10f) else "--"

@Composable private fun Kx3VfoIndicator(vfo: String, mode: String, active: Boolean, ink: Color, activeInk: Color,
    modeAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(mode, color = if (active) activeInk else ink, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1,
            modifier = Modifier.clickable(enabled = mode.isNotBlank(), onClick = modeAction).padding(horizontal = 3.dp, vertical = 2.dp))
        Box(Modifier.border(1.dp, if (active) activeInk else ink).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Text(vfo, color = if (active) activeInk else ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Text(if (active) "TX" else "", color = activeInk, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable private fun Kx3OriginalAnnunciators(state: RadioState, ink: Color, bandwidthAction: () -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ANT1", color = ink, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text("ATU", color = ink.copy(alpha = if (state.powerW > 0) 1f else .28f), fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text(if (state.rit) "RIT" else if (state.xit) "XIT" else "RIT", color = ink.copy(alpha = if (state.rit || state.xit) 1f else .28f),
                fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(agcLabel(state.agcMode), color = ink, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text("PRE", color = ink.copy(alpha = if (state.preamp) 1f else .28f), fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text("ATT", color = ink.copy(alpha = if (state.attenuator) 1f else .28f), fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text("CWT", color = ink.copy(alpha = if (state.cwt) 1f else .28f), fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("XFIL  FL2", color = ink, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text("BW ${state.bandwidthHz}", color = ink, fontWeight = FontWeight.Black, fontSize = 13.sp,
                modifier = Modifier.clickable(enabled = state.connected, onClick = bandwidthAction).padding(horizontal = 3.dp, vertical = 2.dp))
            Text("PWR ${state.powerW}W", color = ink, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

private fun kx3FilterWidths(mode: String): List<Int> = when (mode) {
    "CW", "CW-R" -> listOf(100, 200, 300, 400, 500, 1000)
    "AM" -> listOf(3000, 4000, 5000, 6000, 7000, 8000)
    "FM" -> listOf(5000, 7000, 9000, 11000, 13000, 15000)
    else -> listOf(1800, 2100, 2400, 2700, 3000, 3500)
}

@Composable private fun Kx3LcdPickerDialog(picker: Kx3LcdPicker, mode: String, select: (String) -> Unit,
    dismiss: () -> Unit, kx2: Boolean = false) {
    val choices = when (picker) {
        Kx3LcdPicker.BAND -> listOf("160m" to "BN00;", "80m" to "BN01;", "60m" to "BN02;", "40m" to "BN03;",
            "30m" to "BN04;", "20m" to "BN05;", "17m" to "BN06;", "15m" to "BN07;", "12m" to "BN08;", "10m" to "BN09;")
        Kx3LcdPicker.MODE -> if (kx2) kx2ModeCommands else listOf("LSB" to "MD1;", "USB" to "MD2;", "CW" to "MD3;",
            "CW-R" to "MD7;", "AM" to "MD5;", "FM" to "MD4;")
        Kx3LcdPicker.FILTER -> kx3FilterWidths(mode).map { width ->
            (if (width >= 1000) "${width / 1000.0} kHz" else "$width Hz") to "BW%04d;".format(width / 10)
        }
    }
    val columns = if (picker == Kx3LcdPicker.BAND) 5 else 3
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(when (picker) {
            Kx3LcdPicker.BAND -> "SELECT BAND"
            Kx3LcdPicker.MODE -> "SELECT MODE"
            Kx3LcdPicker.FILTER -> "FILTER WIDTH · $mode"
        }, color = Amber, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, command) ->
                            OutlinedButton({ select(command) }, Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(label, fontWeight = FontWeight.Black, maxLines = 1)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(dismiss) { Text("CLOSE") } })
}

@Composable private fun Kx3OriginalMeter(state: RadioState, ink: Color, bandwidthAction: () -> Unit,
    modifier: Modifier = Modifier) {
    val swrProgress = if (state.transmitting && state.swrTenths >= 10) (state.swrTenths - 10) / 25f else 0f
    Box(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kx3BarMeter("S", "1  3  5  7  9  +20", state.meter / 21f, ink, Modifier.weight(1f).fillMaxHeight())
                Kx3CwtMeter(state.cwt, ink, Modifier.weight(.72f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kx3BarMeter("SWR", "1  1.5  2  3", swrProgress, ink, Modifier.weight(1f).fillMaxHeight())
                Kx3BarMeter("RF", "0   5   10", state.rfOutputTenths / 120f, ink, Modifier.weight(1f).fillMaxHeight())
            }
            Canvas(Modifier.fillMaxWidth().weight(.68f)) {
                val center = size.width * .42f
                val halfWidth = (state.bandwidthHz.coerceIn(100, 4000) / 4000f) * size.width * .18f + size.width * .06f
                val path = Path().apply {
                    moveTo(center - halfWidth, size.height * .76f)
                    lineTo(center - halfWidth * .68f, size.height * .24f)
                    lineTo(center + halfWidth * .68f, size.height * .24f)
                    lineTo(center + halfWidth, size.height * .76f)
                }
                drawPath(path, ink, style = Stroke(2.dp.toPx()))
                drawLine(ink, Offset(size.width * .12f, size.height * .76f), Offset(size.width * .74f, size.height * .76f), 1.5.dp.toPx())
                if (state.cwt) {
                    drawLine(ink, Offset(size.width * .86f, size.height * .2f), Offset(size.width * .86f, size.height * .76f), 2.dp.toPx())
                    drawCircle(ink, 2.5.dp.toPx(), Offset(size.width * .86f, size.height * .18f))
                }
            }
            Text("I   XFIL · ${displayBandwidth(state.bandwidthHz)}   FL2", color = ink, fontWeight = FontWeight.Black,
                fontSize = 12.sp, maxLines = 1)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.30f)
            .semantics { contentDescription = "Select filter width" }
            .clickable(enabled = state.connected, role = Role.Button, onClick = bandwidthAction))
    }
}

@Composable private fun Kx3BarMeter(title: String, scale: String, progress: Float, ink: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title  $scale", color = ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
            fontSize = 12.sp, maxLines = 1, softWrap = false)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val bounded = progress.coerceIn(0f, 1f)
            drawRect(ink.copy(alpha = .34f), size = size, style = Stroke(1.dp.toPx()))
            if (bounded > 0f) drawRect(ink, size = Size(size.width * bounded, size.height))
            repeat(7) { tick ->
                val x = size.width * tick / 6f
                drawLine(ink.copy(alpha = .62f), Offset(x, 0f), Offset(x, size.height * .48f), 1.dp.toPx())
            }
        }
    }
}

@Composable private fun Kx3CwtMeter(active: Boolean, ink: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CWT", color = ink.copy(alpha = if (active) 1f else .38f), fontWeight = FontWeight.Black,
            fontSize = 12.sp, maxLines = 1)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val barWidth = size.width / 12f
            repeat(7) { index ->
                val x = size.width * (index + 1) / 8f - barWidth / 2f
                drawRect(ink.copy(alpha = if (active) .9f else .16f), Offset(x, size.height * .62f),
                    Size(barWidth, size.height * .28f))
            }
            val center = size.width / 2f
            drawLine(ink.copy(alpha = if (active) 1f else .38f), Offset(center, 0f), Offset(center, size.height * .48f), 2.dp.toPx())
            val pointer = Path().apply {
                moveTo(center - 5.dp.toPx(), size.height * .42f)
                lineTo(center + 5.dp.toPx(), size.height * .42f)
                lineTo(center, size.height * .59f)
                close()
            }
            drawPath(pointer, ink.copy(alpha = if (active) 1f else .38f))
        }
    }
}

private fun segmentMask(character: Char) = when (character) {
    '0' -> 0b0111111; '1' -> 0b0000110; '2' -> 0b1011011; '3' -> 0b1001111
    '4' -> 0b1100110; '5' -> 0b1101101; '6' -> 0b1111101; '7' -> 0b0000111
    '8' -> 0b1111111; '9' -> 0b1101111; '-' -> 0b1000000; else -> 0
}

@Composable private fun SegmentedReadout(value: String, ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val units = value.sumOf { if (it == '.' || it == ':') .28 else 1.0 }.toFloat().coerceAtLeast(1f)
        val scale = minOf(size.height, size.width / (units * .62f + (value.length - 1).coerceAtLeast(0) * .08f))
        val digitWidth = scale * .54f
        val gap = scale * .08f
        val dotWidth = scale * .17f
        val totalWidth = value.sumOf { if (it == '.' || it == ':') dotWidth.toDouble() else digitWidth.toDouble() }.toFloat() + gap * (value.length - 1).coerceAtLeast(0)
        var x = (size.width - totalWidth).coerceAtLeast(0f) / 2f
        val y = (size.height - scale) / 2f
        val thickness = scale * .068f
        fun horizontal(top: Float, active: Boolean) {
            val path = Path().apply {
                moveTo(x + thickness * .55f, top); lineTo(x + digitWidth - thickness * .55f, top)
                lineTo(x + digitWidth - thickness, top + thickness); lineTo(x + thickness, top + thickness); close()
            }
            drawPath(path, ink.copy(alpha = if (active) 1f else .025f))
        }
        fun vertical(left: Float, top: Float, bottom: Float, active: Boolean) {
            val path = Path().apply {
                moveTo(left, top + thickness * .55f); lineTo(left + thickness, top + thickness)
                lineTo(left + thickness, bottom - thickness); lineTo(left, bottom - thickness * .55f)
                lineTo(left - thickness * .15f, bottom - thickness); lineTo(left - thickness * .15f, top + thickness); close()
            }
            drawPath(path, ink.copy(alpha = if (active) 1f else .025f))
        }
        value.forEach { character ->
            if (character == '.') {
                drawCircle(ink, radius = thickness * .72f, center = Offset(x + dotWidth * .45f, y + scale - thickness * .65f))
                x += dotWidth + gap
            } else if (character == ':') {
                drawCircle(ink, radius = thickness * .55f, center = Offset(x + dotWidth * .45f, y + scale * .34f))
                drawCircle(ink, radius = thickness * .55f, center = Offset(x + dotWidth * .45f, y + scale * .68f))
                x += dotWidth + gap
            } else {
                val mask = segmentMask(character)
                val middle = y + scale * .50f - thickness * .5f
                horizontal(y, mask and 0b0000001 != 0)
                vertical(x + digitWidth - thickness, y, middle + thickness, mask and 0b0000010 != 0)
                vertical(x + digitWidth - thickness, middle, y + scale, mask and 0b0000100 != 0)
                horizontal(y + scale - thickness, mask and 0b0001000 != 0)
                vertical(x, middle, y + scale, mask and 0b0010000 != 0)
                vertical(x, y, middle + thickness, mask and 0b0100000 != 0)
                horizontal(middle, mask and 0b1000000 != 0)
                x += digitWidth + gap
            }
        }
    }
}

private enum class Kx3Adjustment(val title: String, val unit: String) {
    AF("AF GAIN", "of $KX3_AF_GAIN_MAX"), RF("RF GAIN", "of $KX3_RF_GAIN_MAX"), MONITOR("MONITOR", "of 60"),
    WIDTH("FILTER WIDTH", "Hz"), SHIFT("IF SHIFT", "Hz"), KEYER("KEYER SPEED", "WPM"),
    MIC("MIC GAIN", "of 60"), POWER("TX POWER", "W")
}

@Composable private fun CompactKx3TuningDeck(state: RadioState, send: (String) -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(100) }
    var af by remember(state.afGain) { mutableFloatStateOf(state.afGain.toFloat()) }
    var rf by remember(state.rfGain) { mutableFloatStateOf(state.rfGain.toFloat()) }
    var monitor by remember(state.monitorLevel) { mutableFloatStateOf(state.monitorLevel.coerceAtLeast(0).toFloat()) }
    var width by remember(state.bandwidthHz) { mutableFloatStateOf(state.bandwidthHz.coerceIn(100, 4000).toFloat()) }
    var shift by remember(state.ifShiftHz) { mutableFloatStateOf(state.ifShiftHz.takeIf { it >= 0 }?.toFloat() ?: 1500f) }
    var mic by remember(state.micGain) { mutableFloatStateOf(state.micGain.coerceAtLeast(0).toFloat()) }
    var keyer by remember(state.keyerSpeed) { mutableFloatStateOf(state.keyerSpeed.takeIf { it >= 8 }?.toFloat() ?: 20f) }
    var power by remember(state.powerW) { mutableFloatStateOf(state.powerW.coerceIn(0, 12).toFloat()) }
    var expanded by remember { mutableStateOf<Kx3Adjustment?>(null) }
    var expandedVisible by remember { mutableStateOf(false) }
    var adjustmentGeneration by remember { mutableIntStateOf(0) }
    fun open(adjustment: Kx3Adjustment) {
        expanded = adjustment
        expandedVisible = true
        adjustmentGeneration++
    }
    fun keepAlive() {
        expandedVisible = true
        adjustmentGeneration++
    }
    LaunchedEffect(expanded, adjustmentGeneration) {
        if (expandedVisible) {
            delay(3_000)
            expandedVisible = false
        }
    }
    Surface(color = Color(0xFF111516), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF434A4D)), modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    InlineKx3Slider("AF", af, KX3_AF_GAIN_MIN.toFloat()..KX3_AF_GAIN_MAX.toFloat(),
                        { af = it }, { send(kx3AfGainCommand(af.toInt())) },
                        state.connected) { open(Kx3Adjustment.AF) }
                    InlineKx3Slider("RF", rf, KX3_RF_GAIN_MIN.toFloat()..KX3_RF_GAIN_MAX.toFloat(),
                        { rf = it }, { send(kx3RfGainCommand(rf.toInt())) },
                        state.connected) { open(Kx3Adjustment.RF) }
                    InlineKx3Slider("MON", monitor, 0f..60f, { monitor = it }, { send("ML%03d;".format(monitor.toInt())) },
                        state.connected) { open(Kx3Adjustment.MONITOR) }
                }
                Kx3DeckDivider()
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    InlineKx3Slider("I/WID", width, 100f..4000f, { width = it },
                        { send("BW%04d;".format((width.toInt() / 10).coerceIn(0, 9999))) }, state.connected) { open(Kx3Adjustment.WIDTH) }
                    InlineKx3Slider("II/SHT", shift, 300f..3000f, { shift = it },
                        { send("IS %04d;".format(shift.toInt())) }, state.connected) { open(Kx3Adjustment.SHIFT) }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        InlineKx3Button("NORM", state.connected, Modifier.fillMaxWidth(.5f)) { send("IS 9999;") }
                    }
                }
                Kx3DeckDivider()
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    InlineKx3Slider("KEYER", keyer, 8f..50f, { keyer = it }, { send("KS%03d;".format(keyer.toInt())) },
                        state.connected) { open(Kx3Adjustment.KEYER) }
                    InlineKx3Slider("MIC", mic, 0f..60f, { mic = it }, { send("MG%03d;".format(mic.toInt())) },
                        state.connected) { open(Kx3Adjustment.MIC) }
                    InlineKx3Slider("PWR", power, 0f..12f, { power = it }, { send("PC%03d;".format(power.toInt())) },
                        state.connected) { open(Kx3Adjustment.POWER) }
                }
                Kx3VfoWheel(state, step, send, { step = when (step) { 10 -> 100; 100 -> 1000; 1000 -> 10000; else -> 10 } }, Modifier.fillMaxHeight().aspectRatio(1f))
            }
            AnimatedVisibility(expandedVisible && expanded != null,
                enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = .97f),
                exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = .98f),
                modifier = Modifier.fillMaxSize()) {
                expanded?.let { adjustment ->
                    val value = when (adjustment) {
                        Kx3Adjustment.AF -> af; Kx3Adjustment.RF -> rf; Kx3Adjustment.MONITOR -> monitor
                        Kx3Adjustment.WIDTH -> width; Kx3Adjustment.SHIFT -> shift; Kx3Adjustment.KEYER -> keyer
                        Kx3Adjustment.MIC -> mic; Kx3Adjustment.POWER -> power
                    }
                    val range = when (adjustment) {
                        Kx3Adjustment.AF -> KX3_AF_GAIN_MIN.toFloat()..KX3_AF_GAIN_MAX.toFloat()
                        Kx3Adjustment.RF -> KX3_RF_GAIN_MIN.toFloat()..KX3_RF_GAIN_MAX.toFloat()
                        Kx3Adjustment.MONITOR -> 0f..60f
                        Kx3Adjustment.WIDTH -> 100f..4000f; Kx3Adjustment.SHIFT -> 300f..3000f
                        Kx3Adjustment.KEYER -> 8f..50f; Kx3Adjustment.MIC -> 0f..60f; Kx3Adjustment.POWER -> 0f..12f
                    }
                    fun change(next: Float) {
                        when (adjustment) {
                            Kx3Adjustment.AF -> af = next; Kx3Adjustment.RF -> rf = next; Kx3Adjustment.MONITOR -> monitor = next
                            Kx3Adjustment.WIDTH -> width = next; Kx3Adjustment.SHIFT -> shift = next
                            Kx3Adjustment.KEYER -> keyer = next; Kx3Adjustment.MIC -> mic = next; Kx3Adjustment.POWER -> power = next
                        }
                        keepAlive()
                    }
                    fun finish() {
                        when (adjustment) {
                            Kx3Adjustment.AF -> send(kx3AfGainCommand(af.toInt()))
                            Kx3Adjustment.RF -> send(kx3RfGainCommand(rf.toInt()))
                            Kx3Adjustment.MONITOR -> send("ML%03d;".format(monitor.toInt()))
                            Kx3Adjustment.WIDTH -> send("BW%04d;".format((width.toInt() / 10).coerceIn(0, 9999)))
                            Kx3Adjustment.SHIFT -> send("IS %04d;".format(shift.toInt()))
                            Kx3Adjustment.KEYER -> send("KS%03d;".format(keyer.toInt()))
                            Kx3Adjustment.MIC -> send("MG%03d;".format(mic.toInt()))
                            Kx3Adjustment.POWER -> send("PC%03d;".format(power.toInt()))
                        }
                        keepAlive()
                    }
                    ExpandedKx3Adjustment(adjustment, value, range, state.connected, ::change, ::finish,
                        Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable private fun CompactKx2TuningDeck(state: RadioState, send: (String) -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(100) }
    var af by remember(state.afGain) { mutableFloatStateOf(state.afGain.toFloat()) }
    var rf by remember(state.rfGain) { mutableFloatStateOf(state.rfGain.toFloat()) }
    var monitor by remember(state.monitorLevel) { mutableFloatStateOf(state.monitorLevel.coerceAtLeast(0).toFloat()) }
    var width by remember(state.bandwidthHz) { mutableFloatStateOf(state.bandwidthHz.coerceIn(100, 4000).toFloat()) }
    var keyer by remember(state.keyerSpeed) { mutableFloatStateOf(state.keyerSpeed.takeIf { it >= 8 }?.toFloat() ?: 20f) }
    var mic by remember(state.micGain) { mutableFloatStateOf(state.micGain.coerceAtLeast(0).toFloat()) }
    var power by remember(state.powerW) { mutableFloatStateOf(state.powerW.coerceIn(0, 12).toFloat()) }
    Surface(color = Color(0xFF111516), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF434A4D)), modifier = modifier) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                InlineKx3Slider("AF", af, KX3_AF_GAIN_MIN.toFloat()..KX3_AF_GAIN_MAX.toFloat(), { af = it },
                    { send(kx3AfGainCommand(af.toInt())) }, state.connected) {}
                InlineKx3Slider("RF", rf, KX3_RF_GAIN_MIN.toFloat()..KX3_RF_GAIN_MAX.toFloat(), { rf = it },
                    { send(kx3RfGainCommand(rf.toInt())) }, state.connected) {}
                InlineKx3Slider("MON", monitor, 0f..60f, { monitor = it },
                    { send("ML%03d;".format(monitor.toInt())) }, state.connected) {}
            }
            Kx3DeckDivider()
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                InlineKx3Slider("XFIL", width, 100f..4000f, { width = it },
                    { send("BW%04d;".format((width.toInt() / 10).coerceIn(0, 9999))) }, state.connected) {}
                Row(Modifier.fillMaxWidth().height(37.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    InlineKx3Button("RIT −", state.connected, Modifier.weight(1f)) { send("RD;") }
                    InlineKx3Button("CLR", state.connected, Modifier.weight(1f)) { send("RC;") }
                    InlineKx3Button("RIT +", state.connected, Modifier.weight(1f)) { send("RU;") }
                }
                Text("OFFSET  ${if (state.ritXitOffsetHz >= 0) "+" else ""}${state.ritXitOffsetHz} Hz",
                    color = Hold, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                    fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
            }
            Kx3DeckDivider()
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                InlineKx3Slider("KEYER", keyer, 8f..50f, { keyer = it },
                    { send("KS%03d;".format(keyer.toInt())) }, state.connected) {}
                InlineKx3Slider("MIC", mic, 0f..60f, { mic = it },
                    { send("MG%03d;".format(mic.toInt())) }, state.connected) {}
                InlineKx3Slider("PWR", power, 0f..12f, { power = it },
                    { send("PC%03d;".format(power.toInt())) }, state.connected) {}
            }
            Kx3VfoWheel(state, step, send,
                { step = when (step) { 10 -> 100; 100 -> 1000; 1000 -> 10000; else -> 10 } },
                Modifier.fillMaxHeight().aspectRatio(1f))
        }
    }
}

@Composable private fun InlineKx3Slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit,
    finish: () -> Unit, enabled: Boolean = true, expand: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(37.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(50.dp).fillMaxHeight().clickable(enabled = enabled, onClick = expand),
            contentAlignment = Alignment.CenterStart) {
            Text(label, color = if (enabled) Ink else Muted, fontWeight = FontWeight.Black, fontSize = 12.sp,
                letterSpacing = .15.sp, maxLines = 1, softWrap = false)
        }
        Slider(value, change, enabled = enabled, valueRange = range, onValueChangeFinished = finish, modifier = Modifier.weight(1f))
        Text(value.toInt().toString(), color = if (enabled) Hold else Muted, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(36.dp))
    }
}

@Composable private fun ExpandedKx3Adjustment(adjustment: Kx3Adjustment, value: Float,
    range: ClosedFloatingPointRange<Float>, enabled: Boolean, change: (Float) -> Unit, finish: () -> Unit,
    modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF17201C), contentColor = Ink, shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Healthy), shadowElevation = 12.dp, modifier = modifier.padding(4.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(adjustment.title, color = Healthy, fontWeight = FontWeight.Black, fontSize = 16.sp,
                        letterSpacing = .8.sp, maxLines = 1)
                    Text("Drag to adjust · release to send", color = Muted, fontSize = 10.sp, maxLines = 1)
                }
                Text("${value.toInt()} ${adjustment.unit}", color = Hold, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black, fontSize = 24.sp, maxLines = 1)
            }
            Slider(value, change, enabled = enabled, valueRange = range, onValueChangeFinished = finish,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MIN ${range.start.toInt()}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("MAX ${range.endInclusive.toInt()}", color = Hold, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable private fun Kx3DeckDivider() = Box(Modifier.width(1.dp).fillMaxHeight(.84f).background(Color(0xFF394044)))

@Composable private fun InlineKx3Button(label: String, enabled: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    Button(action, enabled = enabled, modifier = modifier.height(37.dp), shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33383B), contentColor = Hold),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
        Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = .2.sp)
    }
}

@Composable private fun CallbookIdentityOverlay(record: AndroidCallbookRecord, close: () -> Unit,
    modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF101517), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Healthy.copy(alpha = .8f)), modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteCallbookImage(record.imageUrl, record.callsign, record.source, Modifier.fillMaxHeight().weight(1f))
                Column(Modifier.fillMaxHeight().weight(2f).padding(end = 38.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.name.ifBlank { record.callsign }, color = Ink, fontWeight = FontWeight.Black,
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (record.name.isNotBlank()) IdentityLine("CALL", record.callsign, Healthy)
                    IdentityLine("QTH", listOf(record.address, record.qth, record.postalCode, record.country)
                        .filter(String::isNotBlank).distinct().joinToString(" · "))
                    IdentityLine("GRID", record.grid)
                    IdentityLine("E-MAIL", record.email, Healthy)
                    IdentityLine("BORN", record.born)
                    IdentityLine("QSL", listOf(record.qslText, record.qslManager.takeIf(String::isNotBlank)?.let { "via $it" }.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · "))
                    IdentityLine("ENTITY", listOf(record.country, record.dxcc.takeIf(String::isNotBlank)?.let { "DXCC $it" }.orEmpty(),
                        record.continent, record.cqZone.takeIf(String::isNotBlank)?.let { "CQ $it" }.orEmpty())
                        .filter(String::isNotBlank).distinct().joinToString(" · "))
                    IdentityLine("SOURCE", record.source.ifBlank { "CTY.DAT" }, Amber)
                }
            }
            IconButton(close, Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close station details", tint = Ink)
            }
        }
    }
}

@Composable private fun IdentityLine(label: String, value: String, color: Color = Ink) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
    }
}

private val callbookImageCache = object : LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
}

@Composable private fun RemoteCallbookImage(imageUrl: String, callsign: String, source: String, modifier: Modifier = Modifier,
    largeType: Boolean = false) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, imageUrl) {
        value = null
        value = withContext(Dispatchers.IO) {
            callbookImageCache.get(imageUrl)?.let { return@withContext it }
            runCatching {
                if (!imageUrl.startsWith("https://", ignoreCase = true)) return@runCatching null
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 7_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input ->
                    val bytes = input.readBoundedBytesOrThrow(5 * 1024 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 640) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()?.also { callbookImageCache.put(imageUrl, it) }
                }
            }.getOrNull()
        }
    }
    Surface(color = Color(0xFF20282C), shape = RoundedCornerShape(5.dp), modifier = modifier) {
        if (bitmap != null) Image(bitmap!!, contentDescription = "$source profile photo for $callsign",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AccountCircle, null, tint = Muted, modifier = Modifier.size(if (largeType) 40.dp else 38.dp))
                Text(callsign, color = Ink, fontWeight = FontWeight.Black, fontSize = if (largeType) 26.sp else 13.sp)
                Text("NO PROFILE PHOTO", color = Muted, fontSize = if (largeType) 18.sp else 9.sp)
            }
        }
    }
}

private enum class RadioActivityTab(val label: String) {
    SPOTS("LIVE DX SPOTS"), LOG("LOG"), DETAILS("QRZ/QSO DETAILS"), SEARCH("SEARCH")
}

@Composable private fun LiveSpotsPanel(features: FeatureController, database: QsoDatabase, wavelog: WavelogController,
    callbook: CallbookController, cty: CtyController, app: AppController, send: (String) -> Unit,
    insight: StationInsight?, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf(RadioActivityTab.SPOTS) }
    var logPage by remember { mutableStateOf(QsoPage(emptyList(), 0, 0, 50)) }
    var page by remember { mutableIntStateOf(0) }
    var pageSize by remember { mutableIntStateOf(50) }
    var spotPage by remember { mutableIntStateOf(0) }
    var searchPage by remember { mutableIntStateOf(0) }
    var spotStatuses by remember { mutableStateOf(emptyMap<String, SpotLogStatus>()) }
    var spotFilters by remember { mutableStateOf(SpotFilters()) }
    var activeSpotFilter by remember { mutableStateOf<SpotFilterDimension?>(null) }
    var spotSearchQuery by remember { mutableStateOf("") }
    var previousQsoRecord by remember { mutableStateOf<AndroidCallbookRecord?>(null) }
    val ctyRevision = cty.dataRevision
    val filteredSpots = remember(features.liveSpots, spotStatuses, spotFilters) {
        features.liveSpots.filter { spotMatchesFilters(it, spotStatuses[it.id], spotFilters) }
    }
    val searchedSpots = remember(filteredSpots, spotSearchQuery, ctyRevision) {
        filteredSpots.filter { spot ->
            val entity = cty.lookup(spot.callsign)
            spotMatchesSearch(spot, entity?.country.orEmpty(), entity?.dxcc.orEmpty(), spotSearchQuery)
        }
    }
    val modeOptions = remember(features.liveSpots, spotFilters.modes) {
        (spotModeOptions + features.liveSpots.map { canonicalSpotMode(it.mode) }.filter(String::isNotBlank) + spotFilters.modes)
            .distinct().sorted()
    }
    val spotPageCount = ((filteredSpots.size + 49) / 50).coerceAtLeast(1)
    val searchPageCount = ((searchedSpots.size + 49) / 50).coerceAtLeast(1)
    val visibleSpots = filteredSpots.drop(spotPage * 50).take(50)
    val visibleSearchSpots = searchedSpots.drop(searchPage * 50).take(50)
    LaunchedEffect(spotPageCount, searchPageCount) {
        spotPage = spotPage.coerceAtMost(spotPageCount - 1)
        searchPage = searchPage.coerceAtMost(searchPageCount - 1)
    }
    LaunchedEffect(spotSearchQuery) { searchPage = 0 }
    LaunchedEffect(insight?.record?.callsign) { if (insight != null) selected = RadioActivityTab.DETAILS }
    LaunchedEffect(wavelog.logMode, wavelog.stationId) { page = 0 }
    LaunchedEffect(selected, wavelog.logMode, wavelog.stationId, page, pageSize) {
        if (selected == RadioActivityTab.LOG && wavelog.logMode == LogMode.WAVELOG &&
            wavelog.configured && wavelog.stations.isEmpty()) wavelog.loadStations()
        var observedRevision = Long.MIN_VALUE
        while (selected == RadioActivityTab.LOG) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                logPage = withContext(Dispatchers.IO) {
                    database.page(page, pageSize, stationId = wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG })
                }
                observedRevision = revision
                if (page != logPage.page) page = logPage.page
            }
            delay(2_000)
        }
    }
    LaunchedEffect(selected, features.liveSpots, wavelog.logMode, wavelog.stationId, wavelog.configured, ctyRevision) {
        if (selected !in setOf(RadioActivityTab.SPOTS, RadioActivityTab.SEARCH)) return@LaunchedEffect
        val identities = features.liveSpots.map { spot ->
            val entity = cty.lookup(spot.callsign)
            SpotLogIdentity(spot.id, spot.callsign, entity?.dxcc.orEmpty(),
                entity?.country.orEmpty().ifBlank { spot.country }, spot.band, spot.mode)
        }
        if (identities.isEmpty() || (wavelog.logMode == LogMode.WAVELOG &&
                (!wavelog.configured || wavelog.stationId.isBlank()))) {
            spotStatuses = emptyMap()
            return@LaunchedEffect
        }
        var observedRevision = Long.MIN_VALUE
        while (selected in setOf(RadioActivityTab.SPOTS, RadioActivityTab.SEARCH)) {
            val revision = database.changeToken()
            if (revision != observedRevision) {
                spotStatuses = withContext(Dispatchers.IO) {
                    database.spotStatuses(identities,
                        wavelog.stationId.takeIf { wavelog.logMode == LogMode.WAVELOG })
                }
                observedRevision = revision
            }
            delay(2_000)
        }
    }
    Surface(color = Color(0xFF121617), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444B4E)), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF171D20)), verticalAlignment = Alignment.CenterVertically) {
                PrimaryTabRow(selected.ordinal, modifier = Modifier.width(510.dp).fillMaxHeight(), containerColor = Color.Transparent,
                    contentColor = Amber, divider = {}) {
                    RadioActivityTab.entries.forEach { tab -> Tab(selected == tab, { selected = tab },
                        text = { Text(tab.label, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium) }) }
                }
                Text(when (selected) {
                    RadioActivityTab.SPOTS -> features.clusterStatus
                    RadioActivityTab.SEARCH -> "${searchedSpots.size} MATCH${if (searchedSpots.size == 1) "" else "ES"}"
                    RadioActivityTab.DETAILS -> insight?.let { "${it.record.callsign} · ${it.history.total} QSO${if (it.history.total == 1) "" else "S"}" } ?: "ENTER A CALLSIGN"
                    RadioActivityTab.LOG -> if (wavelog.logMode == LogMode.LOCAL) "LOCAL LOG · ${logPage.total}"
                        else "WAVELOG · ${wavelog.selectedStation?.name ?: "STATION ${wavelog.stationId}"}"
                    },
                    color = if (selected == RadioActivityTab.SPOTS && features.liveSpots.isEmpty()) Muted else Healthy,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                if (selected == RadioActivityTab.LOG) {
                    CompactPager(logPage, pageSize, { pageSize = it; page = 0 }, { page = (page - 1).coerceAtLeast(0) },
                        { page = (page + 1).coerceAtMost(logPage.pageCount - 1) })
                } else if (selected == RadioActivityTab.SPOTS) {
                    Text("50 / PAGE · ${spotPage + 1} / $spotPageCount", color = Muted,
                        style = MaterialTheme.typography.labelSmall)
                    IconButton({ spotPage = (spotPage - 1).coerceAtLeast(0) }, enabled = spotPage > 0) {
                        Icon(Icons.Outlined.ChevronLeft, "Previous spots")
                    }
                    IconButton({ spotPage = (spotPage + 1).coerceAtMost(spotPageCount - 1) },
                        enabled = spotPage + 1 < spotPageCount) {
                        Icon(Icons.Outlined.ChevronRight, "Next spots")
                    }
                } else if (selected == RadioActivityTab.SEARCH) {
                    Text("50 / PAGE · ${searchPage + 1} / $searchPageCount", color = Muted,
                        style = MaterialTheme.typography.labelSmall)
                    IconButton({ searchPage = (searchPage - 1).coerceAtLeast(0) }, enabled = searchPage > 0) {
                        Icon(Icons.Outlined.ChevronLeft, "Previous search results")
                    }
                    IconButton({ searchPage = (searchPage + 1).coerceAtMost(searchPageCount - 1) },
                        enabled = searchPage + 1 < searchPageCount) {
                        Icon(Icons.Outlined.ChevronRight, "Next search results")
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (selected) {
                    RadioActivityTab.SPOTS -> LiveSpotTable(visibleSpots, spotStatuses, cty, app, send,
                        { previousQsoRecord = it.previousQsoRecord(cty) }, { activeSpotFilter = it },
                        if (features.liveSpots.isEmpty()) "No live spots yet · configured clusters connect automatically"
                        else "No live spots match the selected filters", Modifier.fillMaxSize())
                    RadioActivityTab.LOG -> RadioLogTable(logPage.rows, { previousQsoRecord = it.previousQsoRecord() }, Modifier.fillMaxSize())
                    RadioActivityTab.DETAILS -> QrzQsoDetails(insight, Modifier.fillMaxSize())
                    RadioActivityTab.SEARCH -> SpotSearchPanel(spotSearchQuery, { spotSearchQuery = it }, spotFilters,
                        { activeSpotFilter = it }, visibleSearchSpots, spotStatuses, cty, app, send,
                        { previousQsoRecord = it.previousQsoRecord(cty) }, Modifier.fillMaxSize())
                }
                activeSpotFilter?.let { dimension ->
                    SpotFilterOverlay(dimension, spotFilters, modeOptions, { activeSpotFilter = null }, {
                        spotFilters = it; spotPage = 0; searchPage = 0; activeSpotFilter = null
                    }, Modifier.fillMaxSize(), app)
                }
            }
        }
    }
    previousQsoRecord?.let { record ->
        PreviousQsosDialog(record, database, wavelog, callbook) { previousQsoRecord = null }
    }
}

private enum class InsightTab(val label: String) { HISTORY("Worked Before"), DXCC("DXCC Matrix") }

@Composable private fun QrzQsoDetails(insight: StationInsight?, modifier: Modifier = Modifier) {
    if (insight == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.PersonSearch, null, tint = Muted, modifier = Modifier.size(34.dp))
                Text("Enter a callsign in Log QSO", color = Ink, fontWeight = FontWeight.Bold)
                Text("Callbook identity, worked history and DXCC status will appear here.", color = Muted, fontSize = 12.sp)
            }
        }
        return
    }
    var tab by remember(insight.record.callsign) { mutableStateOf(InsightTab.HISTORY) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF1A2023)).padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            InsightTab.entries.forEach { item ->
                FilterChip(tab == item, { tab = item }, {
                    Text(item.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .25.sp,
                        modifier = Modifier.padding(horizontal = 7.dp))
                }, modifier = Modifier.height(48.dp).width(if (item == InsightTab.HISTORY) 148.dp else 132.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("CONFIRMED = PAPER QSL OR LoTW", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        when (tab) {
            InsightTab.HISTORY -> CallsignHistoryTable(insight, Modifier.fillMaxSize())
            InsightTab.DXCC -> DxccMatrix(insight.dxcc, Modifier.fillMaxSize())
        }
    }
}

private data class InsightColumn(val label: String, val weight: Float)

@Composable private fun CallsignHistoryTable(insight: StationInsight, modifier: Modifier = Modifier,
    showSummary: Boolean = true) {
    val columns = listOf(InsightColumn("Date / UTC", 1.5f), InsightColumn("Callsign", 1.25f),
        InsightColumn("Mode", .8f), InsightColumn("RST S", .7f), InsightColumn("RST R", .7f),
        InsightColumn("Band", .65f), InsightColumn("QSL", .55f), InsightColumn("LoTW", .55f))
    Column(modifier) {
        if (showSummary) Text("Worked Before  ·  ${insight.history.total} QSO${if (insight.history.total == 1) "" else "s"}",
            color = Ink, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = .15.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth().height(34.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
            columns.forEach { column -> InsightCell(column.label, column.weight, header = true) }
        }
        if (insight.history.rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No previous QSO with ${insight.record.callsign} in this configured log.", color = Muted)
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(insight.history.rows, key = { it.id }) { qso ->
                val time = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
                Row(Modifier.fillMaxWidth().height(42.dp).background(if (qso.createdAt % 2L == 0L) Color(0xFF171D20) else Color(0xFF20272B)),
                    verticalAlignment = Alignment.CenterVertically) {
                    InsightCell(time.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")), columns[0].weight)
                    InsightCell(qso.callsign, columns[1].weight, Healthy, true)
                    InsightCell(qso.submode.ifBlank { qso.mode }, columns[2].weight)
                    InsightCell(qso.rstSent, columns[3].weight)
                    InsightCell(qso.rstReceived, columns[4].weight)
                    InsightCell(qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }, columns[5].weight)
                    InsightCell(qso.qslReceived.confirmationGlyph(), columns[6].weight, qso.qslReceived.confirmationColor())
                    InsightCell(qso.lotwReceived.confirmationGlyph(), columns[7].weight, qso.lotwReceived.confirmationColor())
                }
            }
        }
    }
}

@Composable private fun RowScope.InsightCell(value: String, weight: Float, color: Color = Ink, bold: Boolean = false,
    header: Boolean = false) {
    Text(value, color = if (header) Ink else color, fontSize = if (header) 12.sp else 13.sp,
        fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(weight).padding(horizontal = 6.dp))
}

private fun String.confirmationGlyph() = if (uppercase() in setOf("Y", "V")) "C" else "—"
private fun String.confirmationColor() = if (uppercase() in setOf("Y", "V")) Healthy else Muted

@Composable private fun DxccMatrix(summary: DxccSummary, modifier: Modifier = Modifier, largeType: Boolean = false) {
    Column(modifier.padding(top = 4.dp).then(if (largeType) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Text("DXCC ${summary.country.ifBlank { summary.dxcc }} · W = worked · C = confirmed",
            color = Ink, fontWeight = FontWeight.Black,
            fontSize = if (largeType) PREVIOUS_QSO_SECTION_FONT_SP.sp else 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, Color(0xFF424B50), RoundedCornerShape(4.dp))) {
            val modeWidth = 54.dp
            val bandWidth = (maxWidth - modeWidth) / insightBands.size
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(30.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
                    MatrixLabel("MODE", modeWidth, largeType)
                    insightBands.forEach { MatrixLabel(it, bandWidth, largeType) }
                }
                insightModes.forEachIndexed { index, mode ->
                    Row(Modifier.fillMaxWidth().height(34.dp)
                        .background(if (index % 2 == 0) Color(0xFF171D20) else Color(0xFF242B2F)),
                        verticalAlignment = Alignment.CenterVertically) {
                        MatrixLabel(mode, modeWidth, largeType)
                        insightBands.forEach { band ->
                            val cell = summary.cells["$mode|$band"]
                            val label = when { cell?.confirmed == true -> "C"; cell?.worked == true -> "W"; else -> "—" }
                            val color = when (label) { "C" -> Healthy; "W" -> Danger; else -> Color.Transparent }
                            Box(Modifier.width(bandWidth).height(27.dp).padding(horizontal = 2.dp, vertical = 1.dp)
                                .background(color, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                                Text(label, color = if (label == "—") Muted else Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (largeType) PREVIOUS_QSO_MATRIX_CELL_FONT_SP.sp else 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MatrixLabel(value: String, width: Dp, largeType: Boolean = false) =
    Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
    Text(value, color = Ink, fontWeight = FontWeight.Bold,
        fontSize = if (largeType) PREVIOUS_QSO_MATRIX_LABEL_FONT_SP.sp else 10.sp, maxLines = 1)
}

private sealed class PreviousQsoLoad {
    data object Loading : PreviousQsoLoad()
    data class Ready(val insight: StationInsight) : PreviousQsoLoad()
    data object Failed : PreviousQsoLoad()
}

private fun configuredStationScope(wavelog: WavelogController): String? =
    if (wavelog.logMode == LogMode.LOCAL) null
    else wavelog.stationId.takeIf(String::isNotBlank) ?: "__NO_SELECTED_WAVELOG_STATION__"

private fun Qso.previousQsoRecord() = AndroidCallbookRecord(
    callsign = callsign, name = name, qth = qth, country = country, grid = grid, dxcc = dxcc,
    continent = continent, region = region, cqZone = cqZone, ituZone = ituZone, state = state,
    email = email, latitude = "", longitude = "", source = "LOG",
)

private fun AndroidDXSpot.previousQsoRecord(cty: CtyController): AndroidCallbookRecord {
    val entity = cty.lookup(callsign)
    return AndroidCallbookRecord(
        callsign = callsign, name = "", qth = "", country = entity?.country.orEmpty().ifBlank { country },
        grid = "", dxcc = entity?.dxcc.orEmpty(), continent = entity?.continent.orEmpty().ifBlank { continent },
        region = entity?.region.orEmpty(), cqZone = entity?.cqZone.orEmpty().ifBlank { cqZone.takeIf { it > 0 }?.toString().orEmpty() },
        ituZone = entity?.ituZone.orEmpty(), state = "", email = "", latitude = "", longitude = "",
        source = if (entity == null) "DX CLUSTER" else "CTY.DAT",
    )
}

private fun callbookFallbackRecord(callsign: String, cty: CtyController): AndroidCallbookRecord {
    val call = callsign.trim().uppercase(java.util.Locale.US)
    val entity = cty.lookup(call)
    return AndroidCallbookRecord(
        callsign = call, name = "", qth = "", country = entity?.country.orEmpty(), grid = "",
        dxcc = entity?.dxcc.orEmpty(), continent = entity?.continent.orEmpty(), region = entity?.region.orEmpty(),
        cqZone = entity?.cqZone.orEmpty(), ituZone = entity?.ituZone.orEmpty(), state = "", email = "",
        latitude = "", longitude = "", source = if (entity == null) "CALLBOOK" else "CTY.DAT",
    )
}

@Composable private fun PreviousQsosDialog(record: AndroidCallbookRecord, database: QsoDatabase,
    wavelog: WavelogController, callbook: CallbookController, dismiss: () -> Unit) {
    val stationScope = configuredStationScope(wavelog)
    var retry by remember(record.callsign, stationScope) { mutableIntStateOf(0) }
    var load by remember(record.callsign, stationScope) { mutableStateOf<PreviousQsoLoad>(PreviousQsoLoad.Loading) }
    var liveProfile by remember(record.callsign) { mutableStateOf<AndroidCallbookRecord?>(null) }
    var profileLoading by remember(record.callsign) { mutableStateOf(true) }
    LaunchedEffect(record.callsign, callbook.qrzEnabled, callbook.hamQthEnabled) {
        profileLoading = true
        callbook.lookup(record.callsign) { result ->
            liveProfile = result
            profileLoading = false
        }
    }
    LaunchedEffect(record, stationScope, retry) {
        load = PreviousQsoLoad.Loading
        load = try {
            PreviousQsoLoad.Ready(withContext(Dispatchers.IO) { database.stationInsight(record, stationScope) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreviousQsoLoad.Failed
        }
    }
    when (val result = load) {
        PreviousQsoLoad.Loading -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("PREVIOUS QSOs · ${record.callsign}", color = Amber, fontWeight = FontWeight.Black) },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Checking the configured log…")
            } },
            confirmButton = {}, dismissButton = { TextButton(dismiss) { Text("CLOSE") } },
        )
        PreviousQsoLoad.Failed -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("PREVIOUS QSOs · ${record.callsign}", color = Amber, fontWeight = FontWeight.Black) },
            text = { Text("The configured log could not be read. Try again.") },
            confirmButton = { Button({ retry++ }) { Text("RETRY") } },
            dismissButton = { TextButton(dismiss) { Text("CLOSE") } },
        )
        is PreviousQsoLoad.Ready -> {
            val insight = result.insight
            PreviousQsosWorkedDialog(insight, mergeCallbookRecords(liveProfile, insight.record), profileLoading, dismiss)
        }
    }
}

@Composable private fun PreviousQsosWorkedDialog(insight: StationInsight, profile: AndroidCallbookRecord,
    profileLoading: Boolean, dismiss: () -> Unit) {
    val call = insight.record.callsign
    val workedBefore = insight.history.total > 0
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val widthFraction = if (maxWidth >= 900.dp) PREVIOUS_QSO_DIALOG_WIDTH_FRACTION else .92f
            Surface(Modifier.fillMaxWidth(widthFraction).fillMaxHeight(.90f).widthIn(max = 840.dp)
                .testTag("previous_qso_dialog"), color = Panel, shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Healthy)) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.List, null, tint = Healthy, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("PREVIOUS QSOs · $call", color = Healthy, fontWeight = FontWeight.Black,
                            fontSize = PREVIOUS_QSO_TITLE_FONT_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        IconButton(dismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close previous QSOs") }
                    }
                    Surface(Modifier.fillMaxWidth(), color = Healthy.copy(alpha = .10f), shape = RoundedCornerShape(5.dp)) {
                        Text(if (workedBefore)
                            "${insight.history.total} ${if (insight.history.total == 1) "time" else "times"} worked before · newest ${insight.history.rows.size} shown"
                            else "NOT WORKED BEFORE · LIVE CALLBOOK PROFILE AND DXCC STATUS",
                            color = Healthy, fontWeight = FontWeight.Black, fontSize = PREVIOUS_QSO_SUMMARY_FONT_SP.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (workedBefore) {
                            PreviousQsoHistoryPanel(insight, Modifier.fillMaxHeight().weight(2f))
                            PreviousQsoProfilePanel(profile, profileLoading, Modifier.fillMaxHeight().weight(1f))
                        } else {
                            PreviousQsoProfilePanel(profile, profileLoading, Modifier.fillMaxSize(), wide = true)
                        }
                    }
                    Text("BAND / MODE MATRIX", color = Healthy, fontWeight = FontWeight.Black,
                        fontSize = PREVIOUS_QSO_SECTION_FONT_SP.sp)
                    DxccMatrix(insight.dxcc, Modifier.fillMaxWidth(), largeType = true)
                }
            }
        }
    }
}

@Composable private fun PreviousQsoHistoryPanel(insight: StationInsight, modifier: Modifier = Modifier) {
    val columns = listOf(
        InsightColumn("DATE / UTC", 1.35f), InsightColumn("FREQUENCY", 1.05f),
        InsightColumn("BAND / MODE", 1f), InsightColumn("RST S / R", .85f), InsightColumn("QSL / LoTW", .9f),
    )
    Surface(modifier, color = Color(0xFF161C20), shape = RoundedCornerShape(7.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF465159))) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(36.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
                columns.forEach { column -> PreviousQsoHistoryCell(column.label, column.weight, header = true) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(insight.history.rows.size, key = { insight.history.rows[it].id }) { index ->
                    val qso = insight.history.rows[index]
                    val time = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
                    Row(Modifier.fillMaxWidth().height(46.dp)
                        .background(if (index % 2 == 0) Color(0xFF1B2227) else Color(0xFF252D32)),
                        verticalAlignment = Alignment.CenterVertically) {
                        PreviousQsoHistoryCell(time.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")), columns[0].weight, bold = true)
                        PreviousQsoHistoryCell(formatSpotFrequency(qso.frequencyHz), columns[1].weight, color = Amber)
                        PreviousQsoHistoryCell("${qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }} / ${qso.submode.ifBlank { qso.mode }}",
                            columns[2].weight, color = Healthy, bold = true)
                        PreviousQsoHistoryCell("${qso.rstSent.ifBlank { "—" }} / ${qso.rstReceived.ifBlank { "—" }}", columns[3].weight)
                        PreviousQsoHistoryCell("${qso.qslReceived.confirmationGlyph()} / ${qso.lotwReceived.confirmationGlyph()}", columns[4].weight)
                    }
                }
            }
        }
    }
}

@Composable private fun RowScope.PreviousQsoHistoryCell(value: String, weight: Float, color: Color = Ink,
    bold: Boolean = false, header: Boolean = false) {
    Box(Modifier.weight(weight).fillMaxHeight().border(.5.dp, Color(0xFF465159)).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(value, color = if (header) Muted else color,
            fontWeight = if (header || bold) FontWeight.Black else FontWeight.Medium,
            fontSize = PREVIOUS_QSO_BODY_FONT_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun PreviousQsoProfilePanel(profile: AndroidCallbookRecord, loading: Boolean,
    modifier: Modifier = Modifier, wide: Boolean = false) {
    Surface(modifier.testTag("previous_qso_profile"), color = Color(0xFF161C20), shape = RoundedCornerShape(7.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF465159))) {
        if (wide) Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteCallbookImage(profile.imageUrl, profile.callsign, profile.source,
                Modifier.width(360.dp).fillMaxHeight(), largeType = true)
            PreviousQsoProfileDetails(profile, loading, Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (loading) "CALLBOOK · LOOKING UP…" else "CALLBOOK · ${profile.source.ifBlank { "LOG / CTY.DAT" }}",
                color = if (loading) Amber else Healthy, fontWeight = FontWeight.Black, fontSize = 15.sp)
            RemoteCallbookImage(profile.imageUrl, profile.callsign, profile.source,
                Modifier.fillMaxWidth().height(164.dp), largeType = true)
            PreviousQsoProfileDetails(profile, loading, Modifier.fillMaxWidth().weight(1f), showSource = false)
        }
    }
}

@Composable private fun PreviousQsoProfileDetails(profile: AndroidCallbookRecord, loading: Boolean,
    modifier: Modifier = Modifier, showSource: Boolean = true) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (showSource) Text(if (loading) "CALLBOOK · LOOKING UP…" else "CALLBOOK · ${profile.source.ifBlank { "LOG / CTY.DAT" }}",
            color = if (loading) Amber else Healthy, fontWeight = FontWeight.Black, fontSize = 15.sp)
        Text(profile.name.ifBlank { profile.callsign }, color = Ink, fontWeight = FontWeight.Black,
            fontSize = PREVIOUS_QSO_SECTION_FONT_SP.sp)
        PreviousQsoProfileLine("CALL", profile.callsign)
        PreviousQsoProfileLine("QTH", listOf(profile.address, profile.qth, profile.postalCode, profile.country)
            .filter(String::isNotBlank).distinct().joinToString(" · "))
        PreviousQsoProfileLine("GRID", profile.grid)
        PreviousQsoProfileLine("STATE", profile.state)
        PreviousQsoProfileLine("ENTITY", listOf(profile.country,
            profile.dxcc.takeIf(String::isNotBlank)?.let { "DXCC $it" }.orEmpty(), profile.continent)
            .filter(String::isNotBlank).distinct().joinToString(" · "))
        PreviousQsoProfileLine("ZONES", listOf(profile.cqZone.takeIf(String::isNotBlank)?.let { "CQ $it" }.orEmpty(),
            profile.ituZone.takeIf(String::isNotBlank)?.let { "ITU $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · "))
        PreviousQsoProfileLine("E-MAIL", profile.email)
        PreviousQsoProfileLine("BORN", profile.born)
    }
}

@Composable private fun PreviousQsoProfileLine(label: String, value: String) {
    if (value.isBlank()) return
    Text("$label  $value", color = Ink, fontWeight = FontWeight.SemiBold,
        fontSize = PREVIOUS_QSO_BODY_FONT_SP.sp, lineHeight = 21.sp)
}

private data class SpotColumn(val label: String, val width: Dp, val mono: Boolean = false, val centered: Boolean = false)

@Composable private fun SpotSearchPanel(query: String, updateQuery: (String) -> Unit, filters: SpotFilters,
    openFilter: (SpotFilterDimension) -> Unit, spots: List<AndroidDXSpot>, statuses: Map<String, SpotLogStatus>,
    cty: CtyController, app: AppController, send: (String) -> Unit, previousQsos: (AndroidDXSpot) -> Unit,
    modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        Column(Modifier.fillMaxSize()) {
            if (compact) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF171D20)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpotSearchField(query, updateQuery, Modifier.fillMaxWidth().height(48.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SpotFilterButtons(filters, openFilter, true)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(58.dp).background(Color(0xFF171D20))
                    .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SpotSearchField(query, updateQuery, Modifier.weight(1f).fillMaxHeight())
                    SpotFilterButtons(filters, openFilter, false)
                }
            }
            LiveSpotTable(spots, statuses, cty, app, send, previousQsos, openFilter,
                "No spots match this callsign/entity and filter combination", Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable private fun SpotSearchField(query: String, updateQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(query, { updateQuery(it.uppercase().take(32)) }, singleLine = true,
        label = { Text("Callsign or entity") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotBlank()) IconButton({ updateQuery("") }) {
                Icon(Icons.Outlined.Close, "Clear search")
            }
        }, modifier = modifier)
}

@Composable private fun RowScope.SpotFilterButtons(filters: SpotFilters,
    openFilter: (SpotFilterDimension) -> Unit, compact: Boolean) {
    SpotFilterDimension.entries.forEach { dimension ->
        OutlinedButton({ openFilter(dimension) }, modifier = Modifier.height(48.dp).then(
            if (compact) Modifier.weight(1f) else Modifier), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(if (filters.count(dimension) == 0) dimension.title.uppercase()
                else "${dimension.title.uppercase()} · ${filters.count(dimension)}",
                fontWeight = FontWeight.Black, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun SpotFilterOverlay(dimension: SpotFilterDimension, filters: SpotFilters,
    modeOptions: List<String>, dismiss: () -> Unit, apply: (SpotFilters) -> Unit,
    modifier: Modifier = Modifier, app: AppController? = null,
    bandOptions: List<String> = spotBandOptions,
    bandPresets: List<Pair<String, Set<String>>> = emptyList(),
) {
    val options = when (dimension) {
        SpotFilterDimension.BAND -> bandOptions
        SpotFilterDimension.MODE -> modeOptions
        SpotFilterDimension.CALL_STATUS -> spotCallStatusOptions
        SpotFilterDimension.DXCC_STATUS -> spotDxccStatusOptions
    }
    var draft by remember(dimension, filters) { mutableStateOf(filters.selected(dimension)) }
    BoxWithConstraints(modifier.background(Color.Black.copy(alpha = .62f)).clickable(onClick = dismiss),
        contentAlignment = Alignment.Center) {
        val modalInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val columns = if (dimension == SpotFilterDimension.BAND) 4 else 3
        val rowCount = (options.size + columns - 1) / columns
        val modalHeight = minOf((174 + rowCount * 52).dp, maxHeight * .84f)
        val modalWidth = minOf(if (dimension == SpotFilterDimension.BAND) 660.dp else 540.dp, maxWidth * .9f)
        Surface(color = Color(0xFF171D20), shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF59636A)), shadowElevation = 12.dp,
            modifier = Modifier.width(modalWidth).height(modalHeight)
                .clickable(interactionSource = modalInteraction, indication = null) {}) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FILTER BY ${dimension.title.uppercase()}", color = Amber, fontWeight = FontWeight.Black,
                            fontSize = 15.sp, letterSpacing = .5.sp)
                        Text("Choose one, several, or All. Filter categories combine together.", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(dismiss) { Icon(Icons.Outlined.Close, "Close filter") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpotFilterChoice("ALL", draft.isEmpty(), { draft = emptySet() }, Amber, Modifier.weight(1f))
                    if (dimension == SpotFilterDimension.BAND) bandPresets.forEach { (label, values) ->
                        SpotFilterChoice(label, draft == values, { draft = values },
                            spotFilterAccent(dimension, values.first(), app), Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = Color(0xFF3A4348))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    options.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                SpotFilterChoice(spotFilterOptionLabel(dimension, option), option in draft, {
                                    draft = if (draft.isEmpty()) setOf(option)
                                    else if (option in draft) (draft - option).ifEmpty { emptySet() }
                                    else draft + option
                                }, spotFilterAccent(dimension, option, app), Modifier.weight(1f))
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss) { Text("CANCEL") }
                    Spacer(Modifier.width(8.dp))
                    Button({ apply(filters.withSelection(dimension, draft)) }) { Text("APPLY FILTER") }
                }
            }
        }
    }
}

private fun spotFilterAccent(dimension: SpotFilterDimension, option: String, app: AppController?): Color = when (dimension) {
    SpotFilterDimension.BAND -> Color(android.graphics.Color.parseColor(hamClockBandColor(option)))
    SpotFilterDimension.MODE -> when (canonicalSpotMode(option)) {
        "CW" -> Color(0xFF43C7D9); "SSB" -> Color(0xFFE9A72B); "FM" -> Color(0xFF42C77B)
        "AM" -> Color(0xFFF58B3A); else -> Color(0xFF9C6ADE)
    }
    SpotFilterDimension.CALL_STATUS -> Color(app?.spotStatusColour(SPOT_STATUS_CS, option)
        ?: defaultSpotStatusColour(SPOT_STATUS_CS, option))
    SpotFilterDimension.DXCC_STATUS -> Color(app?.spotStatusColour(SPOT_STATUS_DS, option)
        ?: defaultSpotStatusColour(SPOT_STATUS_DS, option))
}

@Composable private fun SpotFilterChoice(label: String, checked: Boolean, select: () -> Unit, accent: Color,
    modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 48.dp).background(if (checked) accent.copy(alpha = .12f) else Color.Transparent,
            RoundedCornerShape(7.dp)).clickable(role = Role.Checkbox, onClick = select).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = accent,
            uncheckedColor = accent.copy(alpha = .72f)))
        Text(label, color = if (checked) accent else Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

private fun spotFilterOptionLabel(dimension: SpotFilterDimension, option: String): String = when (dimension) {
    SpotFilterDimension.CALL_STATUS -> when (option) {
        "NC" -> "NC · new call"; "NB" -> "NB · new band"; "NM" -> "NM · new mode"
        "W" -> "W · worked"; "C" -> "C · confirmed"; else -> option
    }
    SpotFilterDimension.DXCC_STATUS -> when (option) {
        "ATNO" -> "ATNO · new entity"; "W/NB" -> "W/NB · new band"; "C/NB" -> "C/NB · confirmed entity"
        "W" -> "W · worked"; "C" -> "C · confirmed"; else -> option
    }
    else -> option
}

@Composable private fun LiveSpotTable(spots: List<AndroidDXSpot>, statuses: Map<String, SpotLogStatus>,
    cty: CtyController, app: AppController, send: (String) -> Unit, previousQsos: (AndroidDXSpot) -> Unit,
    openFilter: (SpotFilterDimension) -> Unit, emptyMessage: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val minimumWidth = 760.dp
        val tableWidth = if (maxWidth > minimumWidth) maxWidth else minimumWidth
        val commentWidth = 107.dp + if (maxWidth > minimumWidth) maxWidth - minimumWidth else 0.dp
        val columns = listOf(
            SpotColumn("Date", 56.dp, true), SpotColumn("Time", 80.dp, true), SpotColumn("Band", 42.dp, centered = true),
            SpotColumn("Freq", 92.dp, true), SpotColumn("Callsign", 72.dp), SpotColumn("Mode", 42.dp),
            SpotColumn("Country", 99.dp), SpotColumn("CQ", 28.dp, true, centered = true), SpotColumn("DX de", 66.dp),
            SpotColumn("CS", 32.dp, true, centered = true), SpotColumn("DS", 44.dp, true, centered = true), SpotColumn("Comment", commentWidth))
        Column(Modifier.width(tableWidth).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(36.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
                columns.forEach { column ->
                    val dimension = when (column.label) {
                        "Band" -> SpotFilterDimension.BAND; "Mode" -> SpotFilterDimension.MODE
                        "CS" -> SpotFilterDimension.CALL_STATUS; "DS" -> SpotFilterDimension.DXCC_STATUS
                        else -> null
                    }
                    SpotTableCell(column.label, column, header = true,
                        onClick = dimension?.let { { openFilter(it) } },
                        actionLabel = dimension?.let { "Filter spots by ${it.title}" }.orEmpty())
                }
            }
            HorizontalDivider(color = Color(0xFF303940))
            if (spots.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(emptyMessage, color = Muted)
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(spots.size, key = { spots[it].id }) { index ->
                    val spot = spots[index]
                    val utc = Instant.ofEpochSecond(spot.receivedEpoch).atZone(ZoneOffset.UTC)
                    val entity = cty.lookup(spot.callsign)
                    val status = statuses[spot.id]
                    val cq = entity?.cqZone.orEmpty()
                        .ifBlank { spot.cqZone.takeIf { it > 0 }?.toString().orEmpty() }
                    Row(Modifier.fillMaxWidth().height(48.dp)
                        .background(if (index % 2 == 0) Color(0xFF181E22) else Color(0xFF22282C))
                        .clickable { send("FA%011d;".format(spot.frequencyHz)) }, verticalAlignment = Alignment.CenterVertically) {
                        SpotTableCell(utc.format(DateTimeFormatter.ofPattern("dd/MM")), columns[0])
                        SpotTableCell(utc.format(DateTimeFormatter.ofPattern("HH:mm:ss")), columns[1])
                        SpotTableCell(spot.band, columns[2])
                        SpotTableCell(formatSpotFrequency(spot.frequencyHz), columns[3], OperationalFrequency, true)
                        SpotTableCell(spot.callsign, columns[4], if (spot.watchlisted) Hold else OperationalCallsign, true,
                            onClick = { previousQsos(spot) }, actionLabel = "Previous QSOs for ${spot.callsign}")
                        SpotTableCell(spot.mode, columns[5])
                        SpotTableCell(entity?.country.orEmpty().ifBlank { spot.country }, columns[6], OperationalCountry)
                        SpotTableCell(cq, columns[7])
                        SpotTableCell(spot.spotter, columns[8])
                        SpotTableCell(status?.callStatus.orEmpty(), columns[9],
                            Color(app.spotStatusColour(SPOT_STATUS_CS, status?.callStatus)))
                        SpotTableCell(status?.dxccStatus.orEmpty(), columns[10],
                            Color(app.spotStatusColour(SPOT_STATUS_DS, status?.dxccStatus)))
                        SpotTableCell(spot.comment, columns[11])
                    }
                    HorizontalDivider(color = Color(0xFF303940))
                }
            }
        }
    }
}

private fun formatSpotFrequency(frequencyHz: Long): String = "%d.%03d.%02d".format(
    java.util.Locale.US, frequencyHz / 1_000_000, (frequencyHz / 1_000) % 1_000, (frequencyHz / 10) % 100)

@Composable private fun RowScope.SpotTableCell(value: String, column: SpotColumn, color: Color = Ink,
    bold: Boolean = false, header: Boolean = false, onClick: (() -> Unit)? = null, actionLabel: String = "") {
    val interaction = if (onClick == null) Modifier else Modifier
        .semantics { contentDescription = actionLabel }
        .clickable(role = Role.Button, onClick = onClick)
    Box(Modifier.width(column.width).fillMaxHeight().then(interaction).padding(horizontal = 4.dp),
        contentAlignment = if (column.centered) Alignment.Center else Alignment.CenterStart) {
        Text(value.ifBlank { "—" }, color = if (value.isBlank()) Muted.copy(alpha = .55f) else color,
            fontFamily = if (column.mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (header || bold) FontWeight.Black else FontWeight.Medium,
            fontSize = if (header) LIVE_SPOT_HEADER_FONT_SP.sp else LIVE_SPOT_ROW_FONT_SP.sp,
            maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun RadioLogTable(records: List<Qso>, previousQsos: (Qso) -> Unit,
    modifier: Modifier = Modifier) {
    val columns = listOf(
        "Date" to .88f, "Time" to .66f, "Call" to 1.05f, "Mode" to .72f, "RST (S)" to .67f,
        "RST (R)" to .67f, "Band" to .62f, "Country" to 1.65f, "LoTW" to .66f, "Clublog" to .82f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(42.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
            columns.forEach { (label, weight) -> RadioLogCell(label, weight, header = true) }
        }
        if (records.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No QSOs in the configured log yet.", color = Muted)
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(records.size, key = { records[it].id }) { index ->
                val qso = records[index]
                val utc = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
                Row(Modifier.fillMaxWidth().height(48.dp)
                    .background(if (index % 2 == 0) Color(0xFF181E22) else Color(0xFF22282C)),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioLogCell(utc.format(DateTimeFormatter.ofPattern("dd/MM/yy")), columns[0].second)
                    RadioLogCell(utc.format(DateTimeFormatter.ofPattern("HH:mm")), columns[1].second)
                    RadioLogCell(qso.callsign, columns[2].second, OperationalCallsign, true,
                        onClick = { previousQsos(qso) }, actionLabel = "Previous QSOs for ${qso.callsign}")
                    RadioLogCell(qso.submode.ifBlank { qso.mode }, columns[3].second)
                    RadioLogCell(qso.rstSent, columns[4].second); RadioLogCell(qso.rstReceived, columns[5].second)
                    RadioLogCell(qso.band, columns[6].second)
                    RadioLogCell(qso.country.ifBlank { qso.dxcc }, columns[7].second, OperationalCountry)
                    RadioLogQslCell(qso.lotwSent, qso.lotwReceived, columns[8].second)
                    RadioLogQslCell(qso.clublogSent, qso.clublogReceived, columns[9].second)
                }
            }
        }
    }
}

@Composable private fun RowScope.RadioLogCell(value: String, weight: Float, color: Color = Ink, bold: Boolean = false,
    header: Boolean = false, onClick: (() -> Unit)? = null, actionLabel: String = "") {
    val interaction = if (onClick == null) Modifier else Modifier
        .semantics { contentDescription = actionLabel }
        .clickable(role = Role.Button, onClick = onClick)
    Box(Modifier.weight(weight).fillMaxHeight().border(.5.dp, Color(0xFF3D474D))
        .then(interaction).padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(value.ifBlank { "—" }, color = if (value.isBlank()) Muted.copy(alpha = .55f) else color,
            fontWeight = if (header || bold) FontWeight.Black else FontWeight.Medium,
            fontSize = if (header) 15.sp else 16.sp, maxLines = 1)
    }
}

@Composable private fun RowScope.RadioLogQslCell(sent: String, received: String, weight: Float) {
    Row(Modifier.weight(weight).fillMaxHeight().border(.5.dp, Color(0xFF3D474D)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("▲", color = if (positiveLogStatus(sent)) Healthy else Danger, fontSize = 17.sp)
        Text("▼", color = if (positiveLogStatus(received)) Healthy else Danger, fontSize = 17.sp)
    }
}

@Composable private fun KxStatusRail(family: RadioFamily, state: RadioState, detail: String, connect: () -> Unit,
    direct: (String) -> Unit) {
    Surface(color = Color(0xFF15191B), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF363D40)), modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${family.displayName.uppercase()} TOUCH REMOTE", color = Amber, fontWeight = FontWeight.Black,
                letterSpacing = 1.sp)
            StatusChip(if (state.connected) "CAT LIVE" else "CAT OFFLINE", state.connected)
            Text(if (state.connected) "${state.model} · ${state.mode}" else detail, color = Muted,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Text(Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'")),
                color = Ink, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(connect, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(if (state.connected) "REFRESH" else "CONNECT")
            }
            Button({ direct("RX;") }, enabled = state.connected, modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Healthy, contentColor = Chassis),
                contentPadding = PaddingValues(horizontal = 14.dp)) { Text("EMERGENCY RX", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable private fun FaceplateScrew() {
    Canvas(Modifier.size(20.dp)) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8E969A), Color(0xFF24282A))), radius = size.minDimension / 2)
        rotate(45f) { drawLine(Color(0xFF111314), Offset(size.width * .25f, size.height / 2), Offset(size.width * .75f, size.height / 2), 2.dp.toPx()) }
    }
}

@Composable private fun Kx3Lcd(state: RadioState, app: AppController, modifier: Modifier = Modifier) {
    val lcd = Color(0xFFEFB323); val lcdInk = Color(0xFF291D03)
    Surface(shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF343839)),
        modifier = modifier) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF7C53B), lcd, Color(0xFFD7950D)))).padding(14.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("VFO A", color = lcdInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(app.stationCallsign, app.stationName, app.stationGrid).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "KX3 REMOTE" },
                        color = lcdInk, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(if (state.transmitting) "TX" else "RX", color = lcdInk, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    Text(if (state.connected) formatRadioFrequency(state.frequencyHz) else "--.---.---", color = lcdInk,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light,
                        fontSize = if (maxWidth >= 720.dp) 62.sp else 46.sp, modifier = Modifier.align(Alignment.Center))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("VFO B", color = lcdInk, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(14.dp))
                    Text(if (state.frequencyBHz > 0) formatRadioFrequency(state.frequencyBHz) else "--.---.---", color = lcdInk,
                        fontFamily = FontFamily.Monospace, fontSize = 24.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.model}  ${state.mode}  ${if (state.split) "SPLIT" else "SIMPLEX"}", color = lcdInk, fontWeight = FontWeight.SemiBold)
                    Text("BW ${state.bandwidthHz.takeIf { it > 0 } ?: "--"}  RIT ${if (state.rit) "ON" else "OFF"}  XIT ${if (state.xit) "ON" else "OFF"}", color = lcdInk)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AGC  PRE ${if (state.preamp) "ON" else "OFF"}  ATTN ${if (state.attenuator) "ON" else "OFF"}", color = lcdInk)
                    Text("PWR ${state.powerW.takeIf { it > 0 }?.let { "$it W" } ?: "--"}", color = lcdInk)
                }
                Kx3Meter(state, lcdInk)
                Surface(color = lcdInk.copy(alpha = .92f), modifier = Modifier.fillMaxWidth().height(26.dp)) {
                    Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CW DECODE", color = lcd, style = MaterialTheme.typography.labelSmall); Spacer(Modifier.width(12.dp))
                        Text("—", color = Color(0xFFF8D45D), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable private fun Kx3Meter(state: RadioState, ink: Color) {
    val fraction = if (state.transmitting) (state.rfOutputTenths / 120f).coerceIn(0f, 1f) else (state.meter / 30f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.transmitting) "SWR ${if (state.swrTenths >= 0) state.swrTenths / 10f else "--"}" else "S-METER ${state.meter}", color = ink, fontWeight = FontWeight.Bold)
            Text(if (state.transmitting) "RF ${if (state.rfOutputTenths >= 0) state.rfOutputTenths / 10f else "--"} W" else "S 1   3   5   7   9   +20   +40   +60", color = ink, fontFamily = FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxWidth().height(23.dp)) {
            val baseline = size.height * .78f
            drawLine(ink, Offset(0f, baseline), Offset(size.width, baseline), 2.dp.toPx())
            repeat(16) { index -> val x = size.width * index / 15f
                drawLine(ink, Offset(x, baseline), Offset(x, if (index % 2 == 0) 0f else size.height * .28f), 1.dp.toPx()) }
            drawRect(ink.copy(alpha = .86f), Offset(0f, size.height * .42f), androidx.compose.ui.geometry.Size(size.width * fraction, size.height * .22f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun FlightButton(main: String, secondary: String, enabled: Boolean, tap: () -> Unit, hold: () -> Unit,
    modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(Brush.verticalGradient(if (accent) listOf(Color(0xFF454123), Color(0xFF1E1D14)) else listOf(Color(0xFF3C4144), Color(0xFF17191A))), MaterialTheme.shapes.small)
        .border(1.dp, if (enabled) Color(0xFF666E72) else Color(0xFF292D2F), MaterialTheme.shapes.small)
        .combinedClickable(enabled = enabled, onClick = tap, onLongClick = hold)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(main, color = if (enabled) Ink else Muted.copy(alpha = .78f), fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
            Text(secondary, color = if (enabled) Hold else Muted.copy(alpha = .62f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun CompactLogger(state: RadioState, database: QsoDatabase, mutations: QsoMutationCoordinator,
    wavelog: WavelogController,
    callbook: CallbookController, cty: CtyController, app: AppController, send: (String) -> Unit,
    macroContext: MutableState<CwMacroContext>,
    portableDraft: PortableLogDraft?, consumePortableDraft: () -> Unit, onQsoSaved: () -> Unit,
    onInsight: (StationInsight) -> Unit, onInsightCleared: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(QsoEditorTab.QSO) }
    var call by remember { mutableStateOf("") }; var sent by remember { mutableStateOf("59") }; var received by remember { mutableStateOf("59") }
    var name by remember { mutableStateOf("") }; var qth by remember { mutableStateOf("") }; var grid by remember { mutableStateOf("") }
    var iota by remember { mutableStateOf("") }; var sota by remember { mutableStateOf("") }; var wwff by remember { mutableStateOf("") }; var pota by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }; var dxcc by remember { mutableStateOf("") }; var continent by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }; var cqZone by remember { mutableStateOf("") }; var ituZone by remember { mutableStateOf("") }
    var stateName by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    var propagation by remember { mutableStateOf("") }; var antennaPath by remember { mutableStateOf("") }
    var satelliteName by remember { mutableStateOf("") }; var satelliteMode by remember { mutableStateOf("") }
    var draftFrequencyRxHz by remember { mutableLongStateOf(0) }; var draftObserverGrid by remember { mutableStateOf("") }
    var qslSent by remember { mutableStateOf("N") }; var qslMethod by remember { mutableStateOf("") }
    var qslVia by remember { mutableStateOf("") }; var qslMessage by remember { mutableStateOf("") }
    var enrichment by remember { mutableStateOf("Enter a callsign") }; var status by remember { mutableStateOf("LOCAL FIRST") }
    var lookupGeneration by remember { mutableStateOf(0) }
    var logFrequencyMHz by remember { mutableStateOf(if (state.frequencyHz > 0) "%.6f".format(Locale.US, state.frequencyHz / 1_000_000.0) else "") }
    var logMode by remember { mutableStateOf(state.mode.takeUnless { it == "--" }.orEmpty()) }
    var portableChaseDraft by remember { mutableStateOf(false) }
    var satelliteDraft by remember { mutableStateOf(false) }
    val lookupScope = rememberCoroutineScope()
    val selectedStation = wavelog.selectedStation
    val utc = wavelog.synchronizedNow().atZone(ZoneOffset.UTC)
    LaunchedEffect(call, sent, received, name, qth, grid, logMode, state.frequencyHz, app.stationCallsign, app.stationGrid) {
        macroContext.value = CwMacroContext(myCall = app.stationCallsign, call = call, name = name, qth = qth,
            rst = received, rstSent = sent, rstRecv = received, grid = grid, myGrid = app.stationGrid,
            mode = logMode, band = bandForFrequency(state.frequencyHz))
    }
    fun clear() {
        lookupGeneration++
        call = ""; sent = "59"; received = "59"; name = ""; qth = ""; grid = ""; iota = ""; sota = ""; wwff = ""; pota = ""
        comment = ""; notes = ""; country = ""; dxcc = ""; continent = ""; region = ""; cqZone = ""; ituZone = ""
        stateName = ""; email = ""; propagation = ""; antennaPath = ""; satelliteName = ""; satelliteMode = ""; draftFrequencyRxHz = 0; draftObserverGrid = ""; qslSent = "N"; qslMethod = ""; qslVia = ""; qslMessage = ""
        logFrequencyMHz = if (state.frequencyHz > 0) "%.6f".format(Locale.US, state.frequencyHz / 1_000_000.0) else ""; logMode = state.mode.takeUnless { it == "--" }.orEmpty(); portableChaseDraft = false; satelliteDraft = false
        enrichment = "Enter a callsign"; tab = QsoEditorTab.QSO
        onInsightCleared()
    }
    LaunchedEffect(state.frequencyHz, state.mode, portableChaseDraft, satelliteDraft) {
        if (!portableChaseDraft && !satelliteDraft) {
            if (state.frequencyHz > 0) logFrequencyMHz = "%.6f".format(Locale.US, state.frequencyHz / 1_000_000.0)
            if (state.mode.isNotBlank() && state.mode != "--") logMode = state.mode
        }
    }
    LaunchedEffect(portableDraft?.token) {
        val draft = portableDraft ?: return@LaunchedEffect
        call = draft.callsign; pota = draft.potaRef; sota = draft.sotaRef; wwff = draft.wwffRef; qth = draft.referenceNames
        comment = draft.comment; logFrequencyMHz = draft.frequencyHz.takeIf { it > 0 }?.let { "%.6f".format(Locale.US, it / 1_000_000.0) }.orEmpty(); logMode = draft.mode
        propagation = draft.propagationMode; satelliteName = draft.satelliteName; satelliteMode = draft.satelliteMode
        draftFrequencyRxHz = draft.frequencyRxHz; draftObserverGrid = draft.observerGrid
        satelliteDraft = draft.satelliteName.isNotBlank(); portableChaseDraft = !satelliteDraft
        status = if (satelliteDraft) "SATELLITE DRAFT · REVIEW BEFORE SAVE" else "PORTABLE CHASE DRAFT · REVIEW BEFORE SAVE"; tab = QsoEditorTab.QSO
        consumePortableDraft()
    }
    fun applyCty(): Boolean {
        val row = cty.lookup(call) ?: return false
        val contributed = (country.isBlank() && row.country.isNotBlank()) || (dxcc.isBlank() && row.dxcc.isNotBlank()) ||
            (continent.isBlank() && row.continent.isNotBlank()) || (region.isBlank() && row.region.isNotBlank()) ||
            (cqZone.isBlank() && row.cqZone.isNotBlank()) || (ituZone.isBlank() && row.ituZone.isNotBlank())
            country = country.ifBlank { row.country }; dxcc = dxcc.ifBlank { row.dxcc }; continent = continent.ifBlank { row.continent }
            region = region.ifBlank { row.region }; cqZone = cqZone.ifBlank { row.cqZone }; ituZone = ituZone.ifBlank { row.ituZone }
        return contributed
    }
    fun enrich() {
        val requestedCall = call.trim().uppercase()
        if (requestedCall.isBlank()) return
        val generation = ++lookupGeneration
        enrichment = "Looking up $requestedCall…"
        callbook.lookup(requestedCall) { row ->
            if (generation != lookupGeneration || call.trim().uppercase() != requestedCall) return@lookup
            if (row == null) {
                applyCty(); enrichment = "CTY.DAT fallback"
            } else {
                name = row.name.ifBlank { name }; qth = row.qth.ifBlank { qth }; country = row.country.ifBlank { country }; grid = row.grid.ifBlank { grid }
                dxcc = row.dxcc.ifBlank { dxcc }; continent = row.continent.ifBlank { continent }; region = row.region.ifBlank { region }
                cqZone = row.cqZone.ifBlank { cqZone }; ituZone = row.ituZone.ifBlank { ituZone }; stateName = row.state.ifBlank { stateName }
                email = row.email.ifBlank { email }
                enrichment = row.source + if (applyCty()) " · CTY.DAT supplemented" else ""
            }
            val resolved = (row ?: AndroidCallbookRecord(requestedCall, name, qth, country, grid, dxcc, continent,
                region, cqZone, ituZone, stateName, email, "", "", source = "CTY.DAT")).copy(
                callsign = requestedCall, name = name, qth = qth, country = country, grid = grid, dxcc = dxcc,
                continent = continent, region = region, cqZone = cqZone, ituZone = ituZone, state = stateName,
                email = email, source = row?.source ?: "CTY.DAT")
            val stationId = if (wavelog.logMode == LogMode.LOCAL) null
                else wavelog.stationId.takeIf(String::isNotBlank) ?: "__NO_SELECTED_WAVELOG_STATION__"
            lookupScope.launch {
                val insight = withContext(Dispatchers.IO) { database.stationInsight(resolved, stationId) }
                if (generation == lookupGeneration && call.trim().uppercase() == requestedCall) onInsight(insight)
            }
        }
    }
    LaunchedEffect(call) {
        lookupGeneration++
        onInsightCleared()
        val candidate = call.trim()
        if (candidate.length >= 3) { delay(700); if (candidate == call.trim()) enrich() }
    }
    Surface(color = Color(0xFF121617), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444B4E)), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("LOG QSO", color = Amber, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                Text(status, color = if (status.startsWith("SAVED")) Healthy else Muted, style = MaterialTheme.typography.labelSmall)
            }
            PrimaryScrollableTabRow(tab.ordinal, containerColor = Color(0xFF202526), edgePadding = 0.dp, divider = {}) {
                QsoEditorTab.entries.forEach { item -> Tab(tab == item, { tab = item }, text = { Text(item.label, fontWeight = FontWeight.Bold) }) }
            }
            Column(Modifier.weight(1f).padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                when (tab) {
                    QsoEditorTab.QSO -> {
                        InstrumentStrip(Color(0xFF78909C)) {
                            LiveField("UTC date", utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), Modifier.width(116.dp))
                            LiveField("UTC time", utc.format(DateTimeFormatter.ofPattern("HH:mm:ss")), Modifier.width(100.dp))
                            OutlinedTextField(call, { call = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '/' } }, label = { Text("Callsign") },
                                singleLine = true, modifier = Modifier.width(178.dp), colors = logFieldColors())
                            OutlinedButton(::enrich, enabled = call.length >= 3, modifier = Modifier.width(92.dp).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 7.dp)) { Text("LOOKUP", style = MaterialTheme.typography.labelMedium) }
                        }
                        Surface(color = (if (enrichment.contains("QRZ") || enrichment.contains("HamQTH")) Healthy else Color(0xFF78909C)).copy(alpha = .14f),
                            shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("ENRICHMENT  ·  $enrichment", color = if (enrichment.contains("QRZ") || enrichment.contains("HamQTH")) Healthy else Muted,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                        InstrumentStrip(Amber) {
                            LogField("Mode", logMode, { logMode = it.uppercase().take(12) }, Modifier.width(82.dp)); LiveField("Band", bandForFrequency((logFrequencyMHz.toDoubleOrNull()?.times(1_000_000))?.toLong() ?: 0), Modifier.width(72.dp))
                            LogField("Frequency MHz", logFrequencyMHz, { value -> logFrequencyMHz = value.filter { it.isDigit() || it == '.' }.take(12) }, Modifier.width(132.dp))
                            LogField("RST S", sent, { sent = it.take(3) }, Modifier.width(68.dp)); LogField("RST R", received, { received = it.take(3) }, Modifier.width(68.dp))
                            LogField("Name", name, { name = it }, Modifier.weight(1f))
                        }
                        InstrumentStrip(Healthy) {
                            LogField("IOTA", iota, { iota = it.uppercase() }, Modifier.weight(1f)); LogField("SOTA", sota, { sota = it.uppercase() }, Modifier.weight(1f))
                            LogField("WWFF", wwff, { wwff = it.uppercase() }, Modifier.weight(1f)); LogField("POTA", pota, { pota = it.uppercase() }, Modifier.weight(1f))
                        }
                        InstrumentStrip(Color(0xFF65A6C7)) {
                            LogField("Location / QTH", qth, { qth = it }, Modifier.weight(1f)); LogField("Gridsquare", grid, { grid = it.uppercase() }, Modifier.weight(1f))
                            LogField("Comment", comment, { comment = it }, Modifier.weight(1f))
                        }
                    }
                    QsoEditorTab.STATION -> {
                        ChoiceField("Station location", selectedStation?.label ?: app.stationName.ifBlank { "Local station" },
                            wavelog.stations.map { it.id to it.label }, wavelog.stationId, wavelog::setStation)
                        LiveField("Radio", if (state.connected) "Elecraft KX3" else "Elecraft KX3 · CAT offline")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiveField("Frequency RX", if (state.frequencyHz > 0) formatRadioFrequency(state.frequencyHz) else "—", Modifier.weight(1f))
                            LiveField("Band RX", bandForFrequency(state.frequencyHz), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiveField("Transmit power", if (state.connected) "${state.powerW} W" else "—", Modifier.weight(1f))
                            LiveField("Operator callsign", app.stationCallsign.ifBlank { selectedStation?.callsign.orEmpty() }.ifBlank { "Not configured" }, Modifier.weight(1f))
                        }
                    }
                    QsoEditorTab.GENERAL -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LogField("DXCC", dxcc, { dxcc = it.filter(Char::isDigit) }, Modifier.weight(1f)); ChoiceField("Continent", continentName(continent),
                                continentChoices, continent, { continent = it }, Modifier.weight(1f)); LogField("Region", region, { region = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceField("CQ zone", cqZone, (0..40).map { it.toString() to it.toString() }, cqZone, { cqZone = it }, Modifier.weight(1f))
                            ChoiceField("ITU zone", ituZone, (0..90).map { it.toString() to it.toString() }, ituZone, { ituZone = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceField("Propagation mode", propagationLabel(propagation), propagationChoices, propagation, { propagation = it }, Modifier.weight(1f))
                            ChoiceField("Antenna path", antennaPathLabel(antennaPath), antennaPathChoices, antennaPath, { antennaPath = it }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LogField("Satellite name", satelliteName, { satelliteName = it.uppercase() }, Modifier.weight(1f))
                            LogField("Satellite mode", satelliteMode, { satelliteMode = it.uppercase() }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LogField("State", stateName, { stateName = it.uppercase() }, Modifier.weight(1f)); LogField("E-mail", email, { email = it }, Modifier.weight(1f))
                        }
                        LogField("Country", country, { country = it }, Modifier.fillMaxWidth())
                    }
                    QsoEditorTab.NOTES -> LogField("QSO note · exported to third-party services", notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 190.dp), singleLine = false)
                    QsoEditorTab.QSL -> {
                        ChoiceField("Sent", qslSentLabel(qslSent), qslSentChoices, qslSent, { qslSent = it })
                        ChoiceField("Method", qslMethodLabel(qslMethod), qslMethodChoices, qslMethod, { qslMethod = it })
                        LogField("Via", qslVia, { qslVia = it }, Modifier.fillMaxWidth())
                        LogField("QSL message · exported to third-party services", qslMessage, { qslMessage = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), singleLine = false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(::clear, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CLEAR") }
                Button({
                    val now = wavelog.synchronizedNow(); val qsoFrequency = (logFrequencyMHz.toDoubleOrNull()?.times(1_000_000))?.toLong() ?: 0L; val qsoMode = logMode.trim().uppercase()
                    val id = NativeCore.qsoIdentity(call, DateTimeFormatter.ISO_INSTANT.format(now), qsoFrequency, qsoMode)
                    applyCty(); val station = wavelog.selectedStation
                    val qso = Qso(id, call, qsoFrequency, qsoMode, sent, received, now.epochSecond, name, qth, notes, country,
                        band = bandForFrequency(qsoFrequency), grid = grid, iota = iota, sotaRef = sota, wwffRef = wwff, potaRef = pota,
                        comment = comment, frequencyRxHz = if (satelliteDraft) draftFrequencyRxHz else draftFrequencyRxHz.takeIf { it > 0 } ?: qsoFrequency,
                        bandRx = if (satelliteDraft) bandForFrequency(draftFrequencyRxHz) else bandForFrequency(draftFrequencyRxHz.takeIf { it > 0 } ?: qsoFrequency), txPowerW = state.powerW,
                        operatorCallsign = app.stationCallsign.ifBlank { station?.callsign.orEmpty() }, stationCallsign = station?.callsign ?: app.stationCallsign,
                        stationProfileId = if (wavelog.logMode == LogMode.WAVELOG) wavelog.stationId else "", stationLocation = station?.name ?: app.stationName,
                        myGrid = draftObserverGrid.ifBlank { station?.grid ?: app.stationGrid }, myCountry = station?.country.orEmpty(), myDxcc = station?.dxcc.orEmpty(),
                        myCqZone = station?.cqZone.orEmpty(), myItuZone = station?.ituZone.orEmpty(), myState = station?.state.orEmpty(),
                        myIota = station?.iota.orEmpty(), mySotaRef = if (portableChaseDraft) "" else station?.sotaRef.orEmpty(), myWwffRef = if (portableChaseDraft) "" else station?.wwffRef.orEmpty(), myPotaRef = if (portableChaseDraft) "" else station?.potaRef.orEmpty(),
                        radioModel = app.radioFamily.displayName, dxcc = dxcc, continent = continent, region = region, cqZone = cqZone,
                        ituZone = ituZone, state = stateName, email = email, propagationMode = propagation, antennaPath = antennaPath,
                        qslSent = qslSent, qslMethod = qslMethod, qslVia = qslVia, qslMessage = qslMessage,
                        syncState = if (wavelog.logMode == LogMode.WAVELOG) "pending" else "local",
                        extraAdifFields = buildMap { if (satelliteName.isNotBlank()) put("SAT_NAME", satelliteName); if (satelliteMode.isNotBlank()) put("SAT_MODE", satelliteMode) })
                    if (call.isBlank() || !state.connected || qsoFrequency <= 0 || qsoMode.isBlank()) status = "CALL / FREQUENCY / MODE / LIVE CAT REQUIRED"
                    else if (!mutations.save(qso)) status = "DUPLICATE NOT SAVED"
                    else {
                        if (mutations.isMapped(qso)) status = "SAVED · NATIVE WAVELOG OUTBOX QUEUED"
                        else if (wavelog.logMode == LogMode.WAVELOG) { wavelog.enqueue(id, database.toADIF(qso)); status = "SAVED · LEGACY WAVELOG QUEUED" }
                        else status = "SAVED · LOCAL ADIF"
                        onQsoSaved(); clear()
                    }
                }, enabled = state.connected && call.isNotBlank(), modifier = Modifier.weight(2f).heightIn(min = 48.dp)) {
                    Text(if (wavelog.logMode == LogMode.WAVELOG) "SAVE & SYNC QSO" else "SAVE LOCAL QSO", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable private fun InstrumentStrip(tint: Color, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) = Row(
    modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(tint.copy(alpha = .13f), Color.Transparent)), RoundedCornerShape(7.dp))
        .border(1.dp, tint.copy(alpha = .32f), RoundedCornerShape(7.dp)).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable private fun LogField(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true) =
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = singleLine, modifier = modifier,
        colors = logFieldColors())

@Composable private fun logFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber, unfocusedBorderColor = Color(0xFF58656C),
    focusedContainerColor = Color(0xFF26271F), unfocusedContainerColor = Color(0xFF1A2024),
    focusedLabelColor = Amber, unfocusedLabelColor = Muted)

@Composable private fun LiveField(label: String, value: String, modifier: Modifier = Modifier) = OutlinedTextField(value, {}, label = { Text(label) },
    readOnly = true, singleLine = true, modifier = modifier, colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF445057), unfocusedBorderColor = Color(0xFF445057),
        focusedContainerColor = Color(0xFF14191C), unfocusedContainerColor = Color(0xFF14191C),
        focusedLabelColor = Hold, unfocusedLabelColor = Hold, focusedTextColor = Ink, unfocusedTextColor = Ink))

@Composable private fun ChoiceField(label: String, display: String, choices: List<Pair<String, String>>, selected: String,
    change: (String) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp).background(Color(0xFF1A2024), MaterialTheme.shapes.extraLarge), contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(label, color = Muted, style = MaterialTheme.typography.labelSmall); Text(display.ifBlank { "None" }, maxLines = 1) }
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (value, text) -> DropdownMenuItem({ Text(text) }, onClick = { change(value); expanded = false },
                trailingIcon = { if (selected == value) Icon(Icons.Outlined.Check, null) }) }
        }
    }
}

private val continentChoices = listOf("AF" to "Africa", "AN" to "Antarctica", "AS" to "Asia", "EU" to "Europe", "NA" to "North America", "OC" to "Oceania", "SA" to "South America")
private fun continentName(code: String) = continentChoices.firstOrNull { it.first == code }?.second ?: code
private val propagationChoices = listOf("" to "None", "AS" to "Aircraft Scatter", "AUR" to "Aurora", "AUE" to "Aurora-E", "BS" to "Back scatter",
    "ECH" to "EchoLink", "EME" to "Earth-Moon-Earth", "ES" to "Sporadic E", "FAI" to "Field Aligned Irregularities", "F2" to "F2 Reflection",
    "GW" to "Ground Wave", "INET" to "Internet-assisted", "ION" to "Ionoscatter", "IRL" to "IRLP", "LOS" to "Line of Sight", "MS" to "Meteor scatter",
    "RPT" to "Repeater or transponder", "RS" to "Rain scatter", "SAT" to "Satellite", "TEP" to "Trans-equatorial", "TR" to "Tropospheric ducting")
private fun propagationLabel(code: String) = propagationChoices.firstOrNull { it.first == code }?.second ?: code
private val antennaPathChoices = listOf("" to "None", "G" to "Greyline", "O" to "Other", "S" to "Short Path", "L" to "Long Path")
private fun antennaPathLabel(code: String) = antennaPathChoices.firstOrNull { it.first == code }?.second ?: code
private val qslSentChoices = listOf("N" to "No", "Y" to "Yes", "R" to "Requested", "Q" to "Queued", "I" to "Ignore")
private fun qslSentLabel(code: String) = qslSentChoices.firstOrNull { it.first == code }?.second ?: code
private val qslMethodChoices = listOf("" to "None", "B" to "Bureau", "D" to "Direct", "E" to "Electronic", "M" to "Manager")
private fun qslMethodLabel(code: String) = qslMethodChoices.firstOrNull { it.first == code }?.second ?: code

@Composable private fun Kx3TuningDeck(state: RadioState, send: (String) -> Unit, direct: (String) -> Unit, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(100) }
    var af by remember(state.afGain) { mutableFloatStateOf(state.afGain.toFloat()) }
    var rf by remember(state.rfGain) { mutableFloatStateOf(state.rfGain.toFloat()) }
    var bw by remember(state.bandwidthHz) { mutableFloatStateOf(state.bandwidthHz.coerceIn(100, 4000).toFloat()) }
    var power by remember(state.powerW) { mutableFloatStateOf(state.powerW.coerceIn(0, 12).toFloat()) }
    Surface(color = Color(0xFF121617), shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444B4E)), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("TUNING DECK", color = Amber, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                Text("DRAG TO TUNE", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.9f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Kx3Knob("AF", af, 0f..255f, { af = it }, { send("AG%03d;".format(af.toInt())) }, Modifier.weight(1f))
                        Kx3Knob("RF", rf, 0f..255f, { rf = it }, { send("RG%03d;".format(rf.toInt())) }, Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Kx3Knob("BW", bw, 100f..4000f, { bw = it }, { send("BW%04d;".format(bw.toInt())) }, Modifier.weight(1f), "${bw.toInt()} Hz")
                        Kx3Knob("PWR", power, 0f..12f, { power = it }, { send("PC%03d;".format(power.toInt())) }, Modifier.weight(1f), "${power.toInt()} W")
                    }
                }
                Kx3VfoWheel(state, step, send, { step = when (step) { 10 -> 100; 100 -> 1000; 1000 -> 10000; else -> 10 } }, Modifier.weight(1.1f))
            }
            OutlinedButton({ direct("RX;") }, enabled = state.connected, modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Healthy)) { Text("EMERGENCY RX", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable private fun Kx3Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit,
    finish: () -> Unit, modifier: Modifier = Modifier, display: String = value.toInt().toString()) {
    val normalized = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Column(modifier.pointerInput(range) { detectDragGestures(onDragEnd = finish) { event, drag ->
        event.consume(); change((value - drag.y * (range.endInclusive - range.start) / 180f).coerceIn(range))
    } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.sizeIn(maxWidth = 68.dp, maxHeight = 68.dp).aspectRatio(1f)) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF777D80), Color(0xFF292D2F), Color(0xFF090A0B))))
            drawCircle(Color(0xFF8D9396), style = Stroke(1.dp.toPx()))
            rotate(-130f + normalized * 260f) {
                drawLine(Hold, Offset(size.width / 2, size.height * .12f), Offset(size.width / 2, size.height * .33f), 3.dp.toPx())
            }
        }
        Text(display, color = Hold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun Kx3VfoWheel(state: RadioState, step: Int, send: (String) -> Unit, cycleStep: () -> Unit, modifier: Modifier = Modifier) {
    var wheelTarget by remember { mutableFloatStateOf(0f) }
    var previousFrequency by remember { mutableLongStateOf(state.frequencyHz) }
    var pendingFrequency by remember { mutableLongStateOf(-1L) }
    var commandPixels by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentFrequency by rememberUpdatedState(state.frequencyHz)
    LaunchedEffect(state.frequencyHz, step) {
        if (state.frequencyHz <= 0) return@LaunchedEffect
        if (!dragging && previousFrequency > 0 && state.frequencyHz != previousFrequency) {
            val turns = ((state.frequencyHz - previousFrequency).toFloat() / step.toFloat()).coerceIn(-12f, 12f)
            wheelTarget += turns * 15f
        }
        previousFrequency = state.frequencyHz
    }
    val wheelRotation by animateFloatAsState(wheelTarget, tween(durationMillis = 60), label = "KX3 VFO rotation")
    Box(modifier.aspectRatio(1f).pointerInput(step, state.connected) {
        fun finishGesture() {
            dragging = false
            pendingFrequency = -1L
            commandPixels = 0f
        }
        detectDragGestures(
            onDragStart = {
                dragging = true
                pendingFrequency = currentFrequency
                commandPixels = 0f
            },
            onDragEnd = ::finishGesture,
            onDragCancel = ::finishGesture,
        ) { event, drag ->
            event.consume()
            wheelTarget += drag.x * .72f
            commandPixels += drag.x
            var changed = false
            while (abs(commandPixels) >= 12f && state.connected) {
                val direction = if (commandPixels > 0f) 1 else -1
                pendingFrequency = (pendingFrequency + direction * step).coerceAtLeast(0)
                commandPixels -= direction * 12f
                changed = true
            }
            if (changed) send("FA%011d;".format(pendingFrequency))
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF050607)); drawCircle(Color(0xFF697176), style = Stroke(5.dp.toPx()))
            drawCircle(Color(0xFF232729), radius = size.minDimension * .38f)
            rotate(wheelRotation) {
                repeat(24) { index -> rotate(index * 15f) {
                    val end = if (index % 3 == 0) size.height * .115f else size.height * .09f
                    drawLine(Color(0xFF929A9E), Offset(size.width / 2, size.height * .025f), Offset(size.width / 2, end),
                        if (index % 3 == 0) 3.dp.toPx() else 2.dp.toPx())
                } }
                drawCircle(Hold, radius = 5.dp.toPx(), center = Offset(size.width / 2, size.height * .145f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF777E82), Color(0xFF25292B), Color(0xFF0B0C0D))), radius = size.minDimension * .28f)
                drawLine(Color(0xFFAAB0B3), Offset(size.width / 2, size.height * .235f), Offset(size.width / 2, size.height * .33f), 3.dp.toPx())
            }
        }
        Surface(onClick = cycleStep, color = Color(0xFF1A1D1F), shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF666E72)), modifier = Modifier.size(94.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("VFO", color = Ink, fontWeight = FontWeight.Black)
                Text(if (step >= 1000) "${step / 1000} kHz" else "$step Hz", color = Hold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable private fun FrequencyCard(state: RadioState, send: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("TUNING", color = Amber, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { value = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("MHz") }, modifier = Modifier.weight(1f), singleLine = true)
            Button({ value.toDoubleOrNull()?.let { send("FA%011d;".format((it * 1_000_000).toLong())) } }, enabled = state.connected) { Text("SET VFO A") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ send("DN;") }, enabled = state.connected) { Text("VFO −") }
            OutlinedButton({ send("UP;") }, enabled = state.connected) { Text("VFO +") }
            listOf("LSB" to "1", "USB" to "2", "CW" to "3", "FM" to "4", "AM" to "5", "DATA" to "6").forEach { (label, code) ->
                FilterChip(state.mode == label, { send("MD$code;") }, { Text(label) }, enabled = state.connected)
            }
        }
    } }
}

@Composable private fun AdjustmentCard(state: RadioState, send: (String) -> Unit) {
    var af by remember(state.afGain) { mutableFloatStateOf(state.afGain.toFloat()) }
    var rf by remember(state.rfGain) { mutableFloatStateOf(state.rfGain.toFloat()) }
    var bw by remember(state.bandwidthHz) { mutableFloatStateOf(state.bandwidthHz.coerceAtLeast(100).toFloat()) }
    var power by remember(state.powerW) { mutableFloatStateOf(state.powerW.coerceIn(0, 12).toFloat()) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp)) {
        Text("RADIO CONTROLS", color = Amber, fontWeight = FontWeight.Bold)
        SliderLine("AF GAIN", af, KX3_AF_GAIN_MIN.toFloat()..KX3_AF_GAIN_MAX.toFloat(),
            { af = it }, { send(kx3AfGainCommand(af.toInt())) })
        SliderLine("RF GAIN", rf, KX3_RF_GAIN_MIN.toFloat()..KX3_RF_GAIN_MAX.toFloat(),
            { rf = it }, { send(kx3RfGainCommand(rf.toInt())) })
        SliderLine("BANDWIDTH", bw, 100f..4000f, { bw = it }, { send("BW%04d;".format(bw.toInt())) }, "${bw.toInt()} Hz")
        SliderLine("POWER LIMIT", power, 0f..12f, { power = it }, { send("PC%03d;".format(power.toInt())) }, "${power.toInt()} W")
    } }
}

@Composable private fun SliderLine(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    change: (Float) -> Unit, finish: () -> Unit, display: String = value.toInt().toString()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted); Text(display, fontFamily = FontFamily.Monospace) }
    Slider(value, change, valueRange = range, onValueChangeFinished = finish)
}

@Composable private fun AudioCard(audio: AudioMonitorController) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) audio.start() }
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("USB RECEIVE AUDIO", color = Amber, fontWeight = FontWeight.Bold); Text("StarTech input → tablet speaker", color = Muted) }
            Switch(audio.enabled, { if (it) permission.launch(Manifest.permission.RECORD_AUDIO) else audio.stop() })
        }
        Text("IN  ${audio.inputName}", color = Muted); Text("OUT ${audio.outputName}", color = Muted)
        LinearProgressIndicator({ audio.level }, Modifier.fillMaxWidth(), color = Healthy)
        SliderLine("MONITOR GAIN", audio.gain, 0f..12f, audio::updateGain, {}, "%.1fx".format(audio.gain))
        Text(audio.status, color = if (audio.enabled) Healthy else Muted)
        Text("Receive-only. Headphones are recommended to prevent feedback.", color = Hold)
    } }
}

@Composable private fun PresetsScreen(
    state: RadioState,
    app: AppController,
    send: (String) -> Unit,
    bandStacks: BandStackStore,
    scanner: ReceiveOnlyScannerController,
    panadapterFrame: PanadapterFrame?,
    tci: TciRuntimeSnapshot,
    dispatchPlatform: (RadioPlatformAction) -> Unit,
    recallBandStack: (BandStackEntry) -> Unit,
) {
    var editing by remember { mutableStateOf<RadioPreset?>(null) }; var adding by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var scannerOpen by remember { mutableStateOf(false) }
    var stackName by rememberSaveable { mutableStateOf("") }
    var stackStatus by remember { mutableStateOf("Tap to preview/recall · long-press to replace") }
    val ordered = app.presets.sortedBy { it.slot }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val actions: @Composable RowScope.() -> Unit = {
                StatusChip(if (state.connected) "RADIO LIVE" else "RADIO OFFLINE", state.connected)
                if (ordered.size > 1) OutlinedButton({ reordering = !reordering }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(if (reordering) Icons.Outlined.Check else Icons.Outlined.SwapHoriz, null)
                    Spacer(Modifier.width(7.dp)); Text(if (reordering) "DONE" else "REORDER")
                }
                Button({ adding = true }, enabled = app.nextPresetSlot() != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(7.dp)); Text("ADD PRESET")
                }
                OutlinedButton({ scannerOpen = !scannerOpen }, modifier = Modifier.heightIn(min = 48.dp)) { Text("SCANNER") }
            }
            if (maxWidth >= 820.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header("Radio presets")
                    Text("${ordered.size} / 12 memories · tap a preset to recall frequency, mode and filter", color = Muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header("Radio presets")
                Text("${ordered.size} / 12 memories · tap a preset to recall frequency, mode and filter", color = Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        val currentBand = radioPresetBandName(state.frequencyHz)
        val stackEntries = currentBand?.let { bandStacks.entries(it, state.mode) }.orEmpty()
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Raised)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                    Text("BAND STACK", color = Amber, fontWeight = FontWeight.Bold)
                    Text(
                        currentBand?.let { band -> "$band · ${stackEntries.size}/${bandStacks.depth} saved · ${if (bandStacks.perModeStacks) "${state.mode} only" else "all modes"} · ${bandStacks.cycleDirection}" }
                            ?: "Tune to an amateur band to record or cycle",
                        color = Muted,
                    )
                    }
                    OutlinedTextField(stackName, { stackName = it.take(40) }, label = { Text("Slot name") }, modifier = Modifier.widthIn(max = 190.dp))
                    OutlinedButton({
                    currentBand?.let { band ->
                        val now = Instant.now().epochSecond
                        bandStacks.record(
                            band,
                            BandStackEntry(state.frequencyHz, normalizeRadioPresetMode(state.mode), state.bandwidthHz, "active", now,
                                stackName.ifBlank { "$band ${stackEntries.size + 1}" }, now),
                        )
                        stackName = ""; stackStatus = "Named slot recorded"
                    }
                    }, enabled = state.connected && currentBand != null) { Text("RECORD") }
                    Button({
                        currentBand?.let { band -> bandStacks.cycle(band, state.frequencyHz, state.mode)?.let(recallBandStack) }
                    }, enabled = state.connected && currentBand != null && stackEntries.isNotEmpty()) { Text("CYCLE") }
                }
                if (stackEntries.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stackEntries.forEachIndexed { index, entry ->
                        Box(Modifier.background(Panel).combinedClickable(
                            onClick = { recallBandStack(entry); stackStatus = "Recalled ${entry.slotName.ifBlank { "slot ${index + 1}" }}" },
                            onLongClick = {
                                currentBand?.let { band ->
                                    val now = Instant.now().epochSecond
                                    bandStacks.replace(band, index, BandStackEntry(state.frequencyHz, normalizeRadioPresetMode(state.mode),
                                        state.bandwidthHz, "active", now, entry.slotName.ifBlank { "${band} ${index + 1}" }, now))
                                    stackStatus = "Replaced ${entry.slotName.ifBlank { "slot ${index + 1}" }}"
                                }
                            }).padding(8.dp)) {
                            Text("${entry.slotName.ifBlank { "SLOT ${index + 1}" }} · ${formatRadioFrequency(entry.frequencyHz)} · ${entry.mode} · heard ${entry.lastHeardEpoch}",
                                color = Ink, fontSize = 10.sp)
                        }
                    }
                }
                Text(stackStatus, color = Muted, fontSize = 9.sp)
            }
        }
        if (scannerOpen) ScannerPanel(scanner, app.presets, panadapterFrame, tci, dispatchPlatform)
        else if (app.presets.isEmpty()) Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Radio, null, tint = Amber, modifier = Modifier.size(38.dp))
                Text("NO RADIO PRESETS YET", color = Ink, fontWeight = FontWeight.Black)
                Text("Add a favourite frequency, mode and filter width for one-tap recall.", color = Muted)
            }
        } else LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(ordered, key = { it.slot }) { preset ->
                val index = ordered.indexOfFirst { it.slot == preset.slot }
                Card(modifier = Modifier.clickable(enabled = state.connected && !reordering) {
                    send("FA%011d;MD%s;BW%04d;".format(preset.frequencyHz, modeCode(preset.mode), preset.bandwidthHz / 10))
                }, colors = CardDefaults.cardColors(containerColor = Color(preset.color)), border = androidx.compose.foundation.BorderStroke(
                        if (reordering) 2.dp else 1.dp, if (reordering) Amber else Ink.copy(alpha = .2f))) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color.Black.copy(alpha = .2f), shape = MaterialTheme.shapes.small) {
                                Text(radioPresetBandName(preset.frequencyHz).orEmpty(), color = Ink,
                                    fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                            }
                            Spacer(Modifier.weight(1f)); Text("MEM ${index + 1}", color = Ink.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall)
                            IconButton({ editing = preset }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Edit, "Edit preset", tint = Ink)
                            }
                        }
                        Text(formatRadioFrequency(preset.frequencyHz), color = Ink, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black, fontSize = 24.sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(preset.mode, color = Hold, fontWeight = FontWeight.Black)
                            Box(Modifier.width(1.dp).height(18.dp).background(Ink.copy(alpha = .28f)))
                            Text(radioPresetFilterLabel(preset.bandwidthHz), color = Ink, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            if (!reordering) Text(if (state.connected) "TAP TO RECALL" else "RADIO OFFLINE", color = Ink.copy(alpha = .68f),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        if (reordering) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton({ app.movePreset(preset.slot, -1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Move left", tint = Ink) }
                            IconButton({ app.movePreset(preset.slot, 1) }, enabled = index < ordered.lastIndex) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Move right", tint = Ink) }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) PresetDialog(state, app, editing, onClose = { adding = false; editing = null })
}

@Composable private fun PresetDialog(state: RadioState, app: AppController, preset: RadioPreset?, onClose: () -> Unit) {
    val initialMode = preset?.mode ?: normalizeRadioPresetMode(state.mode)
    var frequency by remember(preset) { mutableStateOf(preset?.let { formatRadioFrequency(it.frequencyHz) }
        ?: state.frequencyHz.takeIf { radioPresetBandName(it) != null }?.let(::formatRadioFrequency).orEmpty()) }
    var mode by remember(preset) { mutableStateOf(initialMode) }
    var bandwidth by remember(preset) { mutableIntStateOf(preset?.bandwidthHz?.takeIf { it in radioPresetFilterWidths(initialMode) }
        ?: radioPresetFilterWidths(initialMode)[3]) }
    var colorIndex by remember(preset) { mutableIntStateOf(AppController.presetColors.indexOf(preset?.color).coerceAtLeast(0)) }
    var error by remember(preset) { mutableStateOf("") }; var confirmDelete by remember { mutableStateOf(false) }
    val parsed = parseRadioPresetFrequency(frequency); val band = parsed?.let(::radioPresetBandName)
    val filters = radioPresetFilterWidths(mode)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.64f).widthIn(max = 820.dp), color = Color(0xFF20282E), shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, Amber)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column { Text(if (preset == null) "ADD RADIO PRESET" else "EDIT RADIO PRESET", color = Amber,
                        fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text("One tap recalls frequency, mode and KX3 filter width.", color = Muted) }
                    Spacer(Modifier.weight(1f)); IconButton(onClose) { Icon(Icons.Outlined.Close, "Close") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(frequency, { value -> frequency = value.filter { it.isDigit() || it == '.' }.take(12); error = "" },
                        label = { Text("Frequency") }, placeholder = { Text("14.074.000") }, singleLine = true,
                        supportingText = { Text(if (band != null) "$band amateur band" else "Examples: 14.074.000 · 14.074.0 · 7.074.00") },
                        isError = frequency.isNotBlank() && (parsed == null || band == null), modifier = Modifier.weight(1.45f))
                    ChoiceField("Mode", mode, radioPresetModes.map { it to it }, mode, { value ->
                        mode = value; bandwidth = radioPresetFilterWidths(value)[3]; error = ""
                    }, Modifier.weight(.78f))
                    ChoiceField("Filter width", radioPresetFilterLabel(bandwidth), filters.map { it.toString() to radioPresetFilterLabel(it) },
                        bandwidth.toString(), { bandwidth = it.toInt(); error = "" }, Modifier.weight(.95f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("BUTTON COLOUR", color = Muted, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppController.presetColors.forEachIndexed { index, color ->
                            Surface(onClick = { colorIndex = index }, color = Color(color), shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(if (colorIndex == index) 4.dp else 1.dp,
                                    if (colorIndex == index) Amber else Ink.copy(alpha = .35f)), modifier = Modifier.size(64.dp, 50.dp)) {
                                if (colorIndex == index) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Check, "Selected colour", tint = Ink)
                                }
                            }
                        }
                    }
                }
                Surface(color = Color(AppController.presetColors[colorIndex]), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(parsed?.let(::formatRadioFrequency) ?: "--.---.---", color = Ink, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Spacer(Modifier.weight(1f)); Text("$mode  ·  ${radioPresetFilterLabel(bandwidth)}", color = Ink, fontWeight = FontWeight.Bold)
                    }
                }
                Text(when {
                    error.isNotBlank() -> error
                    frequency.isBlank() -> "Enter a frequency inside an amateur allocation from 160 m through 6 m."
                    parsed == null -> "Use MHz notation such as 14.074.000, 14.074.0, 14.074 or 14074000."
                    band == null -> "That frequency is outside the supported 160–6 m amateur bands."
                    else -> "Ready to save · $band · filter choices follow $mode"
                }, color = if (frequency.isNotBlank() && (parsed == null || band == null) || error.isNotBlank()) Danger else Healthy,
                    fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (preset != null) TextButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, null, tint = Danger)
                        Spacer(Modifier.width(5.dp)); Text("DELETE", color = Danger) }
                    Spacer(Modifier.weight(1f)); TextButton(onClose) { Text("CANCEL") }
                    Spacer(Modifier.width(8.dp)); Button({
                        val slot = preset?.slot ?: app.nextPresetSlot()
                        when {
                            parsed == null -> error = "Enter a valid frequency."
                            band == null -> error = "Choose a frequency inside an amateur band from 160 m through 6 m."
                            slot == null -> error = "All 12 preset memories are full."
                            else -> { app.savePreset(slot, parsed, mode, bandwidth, colorIndex); onClose() }
                        }
                    }) { Text(if (preset == null) "ADD PRESET" else "SAVE CHANGES") }
                }
            }
        }
    }
    if (confirmDelete && preset != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("DELETE RADIO PRESET?", color = Danger) },
        text = { Text("${formatRadioFrequency(preset.frequencyHz)} · ${preset.mode} · ${radioPresetFilterLabel(preset.bandwidthHz)}\nThis cannot be undone.") },
        confirmButton = { Button({ app.deletePreset(preset.slot); confirmDelete = false; onClose() },
            colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("DELETE") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("KEEP PRESET") } })
}

private fun modeCode(mode: String) = when (mode.uppercase()) { "LSB" -> "1"; "USB" -> "2"; "CW" -> "3"; "FM" -> "4"; "AM" -> "5"; else -> "6" }
private enum class DXView { LIVE, SMART, BANDMAP, PULSE, WORLD, WATCH }

@Composable private fun DXScreen(neuralDx: NeuralDxController, features: FeatureController, database: QsoDatabase,
    wavelog: WavelogController, callbook: CallbookController, cty: CtyController, app: AppController,
    clusterPreference: app.shackcq.mobile.hamclock.HamClockClusterPreference,
    dxNewsPreference: app.shackcq.mobile.hamclock.HamClockDxNewsPreference,
    bandHealthPreference: app.shackcq.mobile.hamclock.HamClockBandHealthPreference,
    bandHealthSnapshot: app.shackcq.mobile.hamclock.HamClockBandHealthSnapshot,
    updateDxNewsPreference: (app.shackcq.mobile.hamclock.HamClockDxNewsPreference) -> Unit,
    updateBandHealthPreference: (app.shackcq.mobile.hamclock.HamClockBandHealthPreference) -> Unit,
    needs: List<ProgressNeed>, operations: OperationsController, openOperations: () -> Unit, send: (String) -> Unit,
    requestReceiveTune: (Long, String?, String, String) -> Unit, openHistory: (String) -> Unit) {
    val calendarByCall = operations.dxItems.associateBy { it.callsign.uppercase(Locale.US) }
    val calendarNeeds = features.liveSpots.mapNotNull { spot -> calendarByCall[spot.callsign.uppercase(Locale.US)]?.let { row ->
        spot.id to listOf("DX CALENDAR · ${row.status.replace('_', ' ')}") } }.toMap()
    val dxNeeds = needs.mapNotNull { need -> need.dxSpot?.id?.let { it to need.reasons } }.toMap() + calendarNeeds
    Column(Modifier.fillMaxSize()) {
        Surface(color = Panel, modifier = Modifier.fillMaxWidth().clickable(onClick = openOperations)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("DX CALENDAR · ${calendarNeeds.size} live matches", color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(openOperations) { Text("OPEN CALENDAR") }
            }
        }
        Box(Modifier.weight(1f)) {
            NeuralDxScreen(neuralDx, features, database, wavelog, callbook, cty, app, clusterPreference,
                dxNewsPreference, updateDxNewsPreference, send, requestReceiveTune, dxNeeds,
                bandHealthPreference, bandHealthSnapshot, updateBandHealthPreference, { call -> openHistory(call) }) { spot ->
                openHistory(spot.callsign)
            }
        }
    }
}

@Composable private fun TableRow(title: String, detail: String, trailing: String, alert: Boolean) {
    ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Bold) }, supportingContent = { Text(detail) },
        trailingContent = { Text(trailing, color = if (alert) Hold else Muted) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable private fun LogbookScreen(state: RadioState, database: QsoDatabase, logbookController: LogbookController, mutations: QsoMutationCoordinator,
    wavelog: WavelogController, wavelogNative: WavelogNativeController,
    syncHub: SyncHubController, callbook: CallbookController, app: AppController, openSync: () -> Unit, openProgress: () -> Unit,
    initialFilter: LogbookFilter? = null, consumeInitialFilter: () -> Unit = {},
    initialQsoId: String? = null, consumeInitialQso: () -> Unit = {}) {
    var showFilters by remember { mutableStateOf(false) }
    var showFastEntry by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(LogbookFilter()) }
    var applied by rememberSaveable { mutableStateOf(LogbookFilter()) }
    var fromDate by rememberSaveable { mutableStateOf("") }; var toDate by rememberSaveable { mutableStateOf("") }
    var filterError by rememberSaveable { mutableStateOf("") }; var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteQso by remember { mutableStateOf<Qso?>(null) }
    var editingQso by remember { mutableStateOf<Qso?>(null) }
    var exportRequest by remember { mutableStateOf<Pair<LogbookFilter?,List<String>?>?>(null) }
    var actionStatus by remember { mutableStateOf("") }
    var previousQsoRecord by remember { mutableStateOf<AndroidCallbookRecord?>(null) }
    val context = LocalContext.current
    val logbookScope = rememberCoroutineScope()
    val queryState=logbookController.state
    val ready=queryState as? LogbookQueryState.Ready
    val pageRows=ready?.rows.orEmpty()
    val approximateTotal = logbookController.pageIndex * applied.limit + pageRows.size + if (ready?.hasMore == true) 1 else 0
    val pageData=QsoPage(pageRows,ready?.exactTotal?:approximateTotal,logbookController.pageIndex,applied.limit)
    val pageLoading=queryState is LogbookQueryState.LoadingFirstPage||queryState is LogbookQueryState.LoadingAnotherPage
    val pageError=(queryState as? LogbookQueryState.RecoverableError)?.message.orEmpty()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-adif")) { uri ->
        val request=exportRequest;exportRequest=null
        if(uri!=null&&request!=null)logbookScope.launch { runCatching { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output -> database.streamExportADIF(output,request.first?:LogbookFilter(),
                wavelog.stationId.takeIf { wavelog.logMode==LogMode.WAVELOG },request.second) } ?: error("Export destination could not be opened")
        } }.onSuccess { actionStatus="ADIF export saved." }.onFailure { actionStatus="ADIF export failed: ${it.message}" } }
    }

    LaunchedEffect(initialFilter) {
        initialFilter?.let { requested ->
            draft = requested; applied = requested; selectedId = null
            fromDate = requested.fromEpochSeconds?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()
            toDate = requested.toEpochSecondsExclusive?.let { Instant.ofEpochSecond(it - 1).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()
            consumeInitialFilter()
        }
    }
    LaunchedEffect(initialQsoId) {
        initialQsoId?.let { id ->
            withContext(Dispatchers.IO) { database.qso(id) }?.let { editingQso = it; selectedId = id }
            consumeInitialQso()
        }
    }

    LaunchedEffect(wavelog.logMode, wavelog.stationId) {
        if (wavelog.logMode == LogMode.WAVELOG) {
            if (wavelog.configured && wavelog.stations.isEmpty()) wavelog.loadStations()
            wavelog.syncTwoWay()
        }
    }
    LaunchedEffect(wavelog.status) {
        if (wavelog.status.startsWith("Two-way sync complete")) logbookController.refresh()
    }
    LaunchedEffect(applied, wavelog.logMode, wavelog.stationId) {
        logbookController.apply(applied,wavelog.stationId.takeIf { wavelog.logMode==LogMode.WAVELOG })
    }
    val selected = pageData.rows.firstOrNull { it.id == selectedId }
    val stationLabel = wavelog.selectedStation?.label?.ifBlank { null }
        ?: wavelog.stationId.takeIf { it.isNotBlank() }?.let { "Station $it" }
        ?: "Station not selected"

    Column(Modifier.fillMaxSize().navigationBarsPadding().clipToBounds().padding(16.dp).testTag("logbook-safe-content"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Header(if (wavelog.logMode == LogMode.WAVELOG) "Wavelog logbook" else "Local logbook", state) }
            StatusChip(if (wavelog.logMode == LogMode.WAVELOG) "WAVELOG · TWO-WAY" else "LOCAL · TABLET", true)
            if (wavelog.logMode == LogMode.WAVELOG) Text(stationLabel, color = Hold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button({ showFilters = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.FilterAlt, null); Spacer(Modifier.width(6.dp))
                Text("FILTERS${activeLogbookFilterCount(applied).takeIf { it > 0 }?.let { " · $it" }.orEmpty()}", fontWeight = FontWeight.Black)
            }
            OutlinedButton({ showFastEntry = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("FAST ENTRY") }
            QuickFilterMenu(selected) { key ->
                val updated = quickFilter(applied, selected ?: return@QuickFilterMenu, key)
                if (key == "date") {
                    val date = Instant.ofEpochSecond(selected.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    fromDate = date; toDate = date
                }
                draft = updated; applied = updated; selectedId = null
            }
            OutlinedButton({ selected?.let { deleteQso = it } }, enabled = selected != null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("DELETE QSO") }
            OutlinedButton({ selected?.let { editingQso = it } }, enabled = selected != null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("EDIT QSO") }
            OutlinedButton({ selected?.let { qso -> exportRequest=null to listOf(qso.id); exportLauncher.launch("shackcq-${qso.callsign}.adi") } },
                enabled = selected != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("EXPORT SELECTED") }
            OutlinedButton({
                exportRequest = applied to null
                exportLauncher.launch("shackcq-filtered-${LocalDate.now(ZoneOffset.UTC)}.adi")
            }, enabled = pageData.total > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("EXPORT FILTERED") }
            OutlinedButton({ selected?.let { qso -> callbook.lookup(qso.callsign) { record ->
                if (record == null) actionStatus = "No callbook record found for ${qso.callsign}." else {
                    mutations.update(qso.copy(name = record.name, qth = record.qth, country = record.country, grid = record.grid,
                        dxcc = record.dxcc, continent = record.continent, cqZone = record.cqZone, ituZone = record.ituZone,
                        state = record.state, email = record.email))
                    actionStatus = "Callbook fields updated locally; eligible Wavelog update queued."
                    logbookController.refresh()
                }
            } } }, enabled = selected != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("UPDATE CALLBOOK") }
            val selectedOutbox = selected?.let { qso -> wavelogNative.outbox.firstOrNull { it.entry.localQsoId == qso.id && it.entry.state != WavelogOutboxState.ACCEPTED } }
            OutlinedButton({ selectedOutbox?.let(wavelogNative::retryOutbox) }, enabled = selectedOutbox != null && !wavelogNative.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("RETRY WAVELOG") }
            OutlinedButton(wavelogNative::fullReconcile, enabled = wavelogNative.binding != null && !wavelogNative.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("RECONCILE") }
            LogbookColumnMenu(app)
            if (activeLogbookFilterCount(applied) > 0) OutlinedButton({
                val cleared = LogbookFilter(limit = applied.limit)
                draft = cleared; applied = cleared; selectedId = null; fromDate = ""; toDate = ""; filterError = ""
            }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Clear, null); Spacer(Modifier.width(5.dp)); Text("CLEAR FILTERS") }
            OutlinedButton({ if (wavelog.logMode == LogMode.WAVELOG) wavelog.syncTwoWay() else logbookController.refresh() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (wavelog.logMode == LogMode.WAVELOG) "SYNC" else "REFRESH")
            }
            Button(openSync, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.CloudSync, null); Spacer(Modifier.width(6.dp)); Text("SYNC HUB")
            }
            OutlinedButton(openProgress, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Insights, null); Spacer(Modifier.width(6.dp)); Text("PROGRESS")
            }
            Text(if (ready?.exactTotal == null) "${pageData.rows.size}+ RESULTS · COUNTING…" else "${pageData.rows.size} / ${pageData.total} RESULTS",
                color = Ink, fontWeight = FontWeight.Black, fontSize = 16.sp)
            if (logbookController.refreshing) Text("UPDATING…", color = Hold, fontWeight = FontWeight.Bold)
            CompactPager(pageData, applied.limit, { limit ->
                draft = draft.copy(limit = limit); applied = applied.copy(limit = limit); selectedId = null
            }, { logbookController.loadPrevious(); selectedId = null },
                { logbookController.loadNext(); selectedId = null })
        }
        if (actionStatus.isNotBlank()) Text(actionStatus, color = Hold, style = MaterialTheme.typography.bodySmall)
        if (logbookController.refreshError.isNotBlank()) Text("Background refresh: ${logbookController.refreshError}. Existing rows remain visible.", color = Danger, style = MaterialTheme.typography.bodySmall)
        val deliveryStates = syncHub.records.filter { record -> pageData.rows.any { it.id == record.qsoId } }
            .groupBy { it.qsoId }.mapValues { entry -> entry.value.associate { it.provider to it.state } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (pageLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (pageError.isNotBlank()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pageError, color = Danger); TextButton(logbookController::retry) { Text("RETRY") }
            }
            else if(queryState is LogbookQueryState.ProjectionOptimising) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){
                CircularProgressIndicator(progress={queryState.progress});Text("Optimising local log index · ${(queryState.progress*100).toInt()}%",color=Muted)
            }
            else if (maxWidth < 720.dp) CompactLogbookList(pageData.rows, deliveryStates, selectedId, { selectedId = it },
                { previousQsoRecord = it.previousQsoRecord() }, Modifier.fillMaxSize())
            else LogbookTable(pageData.rows, deliveryStates, selectedId, { selectedId = it }, applied, app.visibleLogbookColumns,
                { previousQsoRecord = it.previousQsoRecord() }, Modifier.fillMaxSize()) { sort ->
                val direction = if (applied.sort == sort && applied.direction == LogbookSortDirection.DESCENDING)
                    LogbookSortDirection.ASCENDING else LogbookSortDirection.DESCENDING
                draft = draft.copy(sort = sort, direction = direction)
                applied = applied.copy(sort = sort, direction = direction); selectedId = null
            }
        }
        if (showFilters) LogbookFilterDialog(draft, { draft = it }, fromDate, { fromDate = it }, toDate, { toDate = it },
            filterError, onPreset = { preset ->
                val (from, to) = logbookDatePreset(preset); fromDate = from.toString(); toDate = to.toString(); filterError = ""
            }, onApply = {
                    val from = fromDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val to = toDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if ((fromDate.isNotBlank() && from == null) || (toDate.isNotBlank() && to == null)) {
                        filterError = "Use ISO dates: YYYY-MM-DD"
                    } else if (from != null && to != null && from > to) {
                        filterError = "From date must not be after To date"
                    } else {
                        filterError = ""
                        applied = draft.copy(fromEpochSeconds = from?.atStartOfDay()?.toEpochSecond(ZoneOffset.UTC),
                            toEpochSecondsExclusive = to?.plusDays(1)?.atStartOfDay()?.toEpochSecond(ZoneOffset.UTC))
                        draft = applied; selectedId = null; showFilters = false
                    }
                }, onClear = {
                    val cleared = LogbookFilter(limit = applied.limit)
                    draft = cleared; applied = cleared; selectedId = null; fromDate = ""; toDate = ""; filterError = ""
                }, onDismiss = { showFilters = false })
    if (showFastEntry) FastEntryDialog(mutations, wavelog, callbook, app.stationCallsign, { _, _ -> logbookController.refresh() }) {
            showFastEntry = false
        }
        deleteQso?.let { qso -> DeleteQsoDialog(qso, mutations, onDeleted = {
            deleteQso = null; selectedId = null; logbookController.refresh()
        }, onDismiss = { deleteQso = null }) }
        editingQso?.let { qso -> QsoCorrectionDialog(qso, mutations, syncHub) {
            editingQso = null; selectedId = null; logbookController.refresh()
        } }
    }
    previousQsoRecord?.let { record ->
        PreviousQsosDialog(record, database, wavelog, callbook) { previousQsoRecord = null }
    }
}

@Composable private fun DeleteQsoDialog(qso: Qso, mutations: QsoMutationCoordinator,
    onDeleted: () -> Unit, onDismiss: () -> Unit) {
    val unavailable = mutations.remoteDeleteUnavailableReason(qso)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DELETE ${qso.callsign} QSO?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the deletion intent explicitly. Both choices remove the local QSO.")
            Text("LOCAL ONLY keeps remote identity metadata so Wavelog can be shown, re-imported, or relinked later.")
            Text(if (unavailable == null)
                "DELETE REMOTE IF UNCHANGED verifies the Wavelog QSO still matches its accepted baseline before deleting it. A changed remote QSO becomes a conflict."
            else "Remote deletion unavailable: $unavailable", color = if (unavailable == null) Ink else Muted)
        } },
        confirmButton = {
            Button({ mutations.delete(qso.id, QsoDeleteIntent.LOCAL_ONLY); onDeleted() }) { Text("LOCAL ONLY") }
        },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (unavailable == null) OutlinedButton({
                mutations.delete(qso.id, QsoDeleteIntent.DELETE_REMOTE_IF_UNCHANGED); onDeleted()
            }) { Text("DELETE REMOTE IF UNCHANGED") }
            TextButton(onDismiss) { Text("CANCEL") }
        } },
    )
}

@Composable private fun LogbookColumnMenu(app: AppController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.ViewColumn, null); Spacer(Modifier.width(6.dp))
            Text("COLUMNS · ${app.visibleLogbookColumns.size}/${LogbookColumn.entries.size}", fontWeight = FontWeight.Black)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 290.dp).heightIn(max = 640.dp)) {
            LogbookColumn.entries.forEach { column ->
                val checked = column in app.visibleLogbookColumns
                DropdownMenuItem(text = { Text(column.label, fontSize = 16.sp) },
                    leadingIcon = { Checkbox(checked, null) }, enabled = !checked || app.visibleLogbookColumns.size > 1,
                    onClick = { app.setLogbookColumnVisible(column, !checked) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Show all columns", fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, null) }, onClick = { app.showAllLogbookColumns() })
        }
    }
}

@Composable private fun CompactSelect(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("$label $value", fontWeight = FontWeight.Bold); Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem({ Text(choice) }, onClick = { change(choice); expanded = false },
                trailingIcon = { if (choice == value) Icon(Icons.Outlined.Check, null) }) }
        }
    }
}

@Composable private fun CompactPager(page: QsoPage, pageSize: Int, changeSize: (Int) -> Unit,
    previous: () -> Unit, next: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box {
            TextButton({ expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("$pageSize ROWS", fontWeight = FontWeight.Bold); Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                LOGBOOK_PAGE_SIZES.forEach { choice -> DropdownMenuItem({ Text("$choice rows") }, onClick = {
                    changeSize(choice); expanded = false
                }, trailingIcon = { if (choice == pageSize) Icon(Icons.Outlined.Check, null) }) }
            }
        }
        TextButton(previous, enabled = page.page > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("PREV") }
        Text("${page.page + 1}/${page.pageCount}", color = Ink, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1)
        TextButton(next, enabled = page.page + 1 < page.pageCount, modifier = Modifier.heightIn(min = 48.dp)) { Text("NEXT") }
    }
}

@Composable private fun QuickFilterMenu(selected: Qso?, apply: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, enabled = selected != null, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.FilterAlt, null); Spacer(Modifier.width(6.dp)); Text("QUICK FILTER"); Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("date" to "Date", "callsign" to "Callsign", "dxcc" to "DXCC", "state" to "State",
                "grid" to "Gridsquare", "mode" to "Mode", "band" to "Band", "iota" to "IOTA", "sota" to "SOTA",
                "pota" to "POTA", "wwff" to "WWFF", "operator" to "Operator").forEach { (key, label) ->
                val enabled = selected != null && (key == "date" || quickFilterValue(selected, key).isNotBlank())
                DropdownMenuItem({ Text("Search $label") }, enabled = enabled, onClick = { apply(key); expanded = false })
            }
        }
    }
}

private fun quickFilterValue(qso: Qso, key: String) = when (key) {
    "callsign" -> qso.callsign; "dxcc" -> qso.dxcc; "state" -> qso.state; "grid" -> qso.grid
    "mode" -> qso.mode; "band" -> qso.band; "iota" -> qso.iota; "sota" -> qso.sotaRef
    "pota" -> qso.potaRef; "wwff" -> qso.wwffRef; "operator" -> qso.operatorCallsign; else -> ""
}

private fun quickFilter(base: LogbookFilter, qso: Qso, key: String): LogbookFilter = when (key) {
    "date" -> {
        val day = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalDate()
        base.copy(fromEpochSeconds = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
            toEpochSecondsExclusive = day.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC))
    }
    "callsign", "dxcc", "state", "grid", "mode", "band", "iota", "sota", "pota", "wwff" ->
        logbookFilterForDimension(key, quickFilterValue(qso, key), base)
    "operator" -> base.copy(operator = qso.operatorCallsign); else -> base
}

private fun logbookDatePreset(preset: String, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Pair<LocalDate, LocalDate> = when (preset) {
    "Today" -> today to today; "Yesterday" -> today.minusDays(1) to today.minusDays(1)
    "Last 7 Days" -> today.minusDays(6) to today; "Last 30 Days" -> today.minusDays(29) to today
    "This Month" -> today.withDayOfMonth(1) to today
    "Last Month" -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
    "This Year" -> today.withDayOfYear(1) to today
    "Last Year" -> today.minusYears(1).withDayOfYear(1) to today.withDayOfYear(1).minusDays(1)
    else -> today to today
}

@Composable private fun CompactLogbookList(records: List<Qso>, deliveries: Map<String, Map<SyncProvider, DeliveryState>>,
    selectedId: String?, select: (String) -> Unit, previousQsos: (Qso) -> Unit, modifier: Modifier = Modifier) {
    if (records.isEmpty()) Box(modifier, contentAlignment = Alignment.Center) {
        Text("No QSOs match the current station and filters.", color = Muted)
    } else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(records, key = Qso::id) { qso ->
            ElevatedCard(Modifier.fillMaxWidth().combinedClickable(onClick = { select(qso.id) }, onDoubleClick = { previousQsos(qso) }),
                colors = CardDefaults.elevatedCardColors(containerColor = if (qso.id == selectedId) Raised else Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(qso.callsign, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("${qso.band} ${qso.submode.ifBlank { qso.mode }}", color = Hold, fontWeight = FontWeight.Bold)
                    }
                    Text("${Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} UTC · ${"%.6f".format(Locale.US, qso.frequencyHz / 1_000_000.0)} MHz")
                    Text(listOf(qso.name, qso.qth, qso.grid, qso.country).filter(String::isNotBlank).joinToString(" · ").ifBlank { "No station detail" },
                        color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sync = deliveries[qso.id].orEmpty().entries.joinToString(" · ") { "${it.key.name}: ${it.value.name}" }
                    Text(listOf(qso.syncState, sync).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun LogbookTable(records: List<Qso>, deliveries: Map<String, Map<SyncProvider, DeliveryState>>,
    selectedId: String?, select: (String) -> Unit,
    filter: LogbookFilter, visibleColumns: List<LogbookColumn>, previousQsos: (Qso) -> Unit,
    modifier: Modifier = Modifier, sort: (LogbookSort) -> Unit) {
    val horizontal = rememberScrollState()
    Box(modifier.fillMaxWidth().border(1.dp, Color(0xFF465159), RoundedCornerShape(8.dp)).horizontalScroll(horizontal)) {
        Column(Modifier.width(visibleColumns.sumOf { it.width }.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(LOGBOOK_HEADER_HEIGHT_DP.dp).background(Raised), verticalAlignment = Alignment.CenterVertically) {
                visibleColumns.forEach { column -> LogbookHeaderCell(column.label, column.width, column.sort, filter, sort) }
            }
            if (records.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.SearchOff, null, tint = Muted, modifier = Modifier.size(34.dp))
                    Text("No QSOs match these filters", color = Ink, fontWeight = FontWeight.Bold)
                    Text("Adjust the filters above to widen the search.", color = Muted, fontSize = 17.sp)
                }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(records.size, key = { records[it].id }) { index ->
                    val qso = records[index]; val selected = selectedId == qso.id
                    Row(Modifier.fillMaxWidth().height(LOGBOOK_ROW_HEIGHT_DP.dp)
                        .background(if (selected) Amber.copy(alpha = .16f) else if (index % 2 == 0) Color(0xFF181E22) else Color(0xFF222A2F))
                        .clickable { select(qso.id) }, verticalAlignment = Alignment.CenterVertically) {
                        visibleColumns.forEach { column -> when (column) {
                            LogbookColumn.DATE_TIME -> LogbookCell(Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")), column.width)
                            LogbookColumn.CALLSIGN -> LogbookCell(qso.callsign, column.width, OperationalCallsign, true,
                                onClick = { previousQsos(qso) }, actionLabel = "Previous QSOs for ${qso.callsign}")
                            LogbookColumn.MODE -> LogbookCell(qso.submode.ifBlank { qso.mode }, column.width, centered = true)
                            LogbookColumn.RST_SENT -> LogbookCell(qso.rstSent, column.width, centered = true)
                            LogbookColumn.RST_RECEIVED -> LogbookCell(qso.rstReceived, column.width, centered = true)
                            LogbookColumn.BAND -> LogbookCell(qso.band, column.width, centered = true)
                            LogbookColumn.FREQUENCY -> LogbookCell("${qso.frequencyHz / 1_000} kHz", column.width, OperationalFrequency)
                            LogbookColumn.GRID -> LogbookCell(qso.grid, column.width)
                            LogbookColumn.QSL -> LogbookQslCell(qso.qslSent, qso.qslReceived, column.width)
                            LogbookColumn.EQSL -> LogbookQslCell(qso.eqslSent, qso.eqslReceived, column.width, deliveries[qso.id]?.get(SyncProvider.EQSL))
                            LogbookColumn.LOTW -> LogbookQslCell(qso.lotwSent, qso.lotwReceived, column.width)
                            LogbookColumn.CLUBLOG -> LogbookQslCell(qso.clublogSent, qso.clublogReceived, column.width, deliveries[qso.id]?.get(SyncProvider.CLUB_LOG))
                            LogbookColumn.QRZ -> LogbookQslCell(qso.qrzSent, qso.qrzReceived, column.width, deliveries[qso.id]?.get(SyncProvider.QRZ))
                            LogbookColumn.DXCC -> LogbookCell(qso.country.ifBlank { qso.dxcc }, column.width, OperationalCountry, centered = true)
                            LogbookColumn.STATE -> LogbookCell(qso.state, column.width)
                            LogbookColumn.COUNTY -> LogbookCell(qso.county, column.width)
                            LogbookColumn.IOTA -> LogbookCell(qso.iota, column.width, Healthy)
                            LogbookColumn.POTA -> LogbookCell(qso.potaRef, column.width, Healthy)
                            LogbookColumn.SOTA -> LogbookCell(qso.sotaRef, column.width, Healthy)
                            LogbookColumn.WWFF -> LogbookCell(qso.wwffRef, column.width, Healthy)
                            LogbookColumn.REGION -> LogbookCell(qso.region, column.width)
                        } }
                    }
                }
            }
        }
    }
}

@Composable private fun RowScope.LogbookHeaderCell(label: String, width: Int, column: LogbookSort?, filter: LogbookFilter,
    sort: (LogbookSort) -> Unit) {
    Row(Modifier.width(width.dp).fillMaxHeight().then(if (column != null) Modifier.clickable { sort(column) } else Modifier)
        .border(width = 0.5.dp, color = Color(0xFF515A60)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Text(label, color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1)
        if (column != null && filter.sort == column) Icon(
            if (filter.direction == LogbookSortDirection.DESCENDING) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
            null, tint = Amber, modifier = Modifier.size(20.dp))
    }
}

@Composable private fun RowScope.LogbookCell(value: String, width: Int, color: Color = Ink, bold: Boolean = false,
    centered: Boolean = false, onClick: (() -> Unit)? = null, actionLabel: String = "") {
    val interaction = if (onClick == null) Modifier else Modifier
        .semantics { contentDescription = actionLabel }
        .clickable(role = Role.Button, onClick = onClick)
    Box(Modifier.width(width.dp).height(LOGBOOK_ROW_HEIGHT_DP.dp).border(width = 0.5.dp, color = Color(0xFF39434A))
        .then(interaction).padding(horizontal = 6.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        Text(value.ifBlank { "—" }, color = if (value.isBlank()) Muted.copy(alpha = .55f) else color,
            fontWeight = if (bold) FontWeight.Black else FontWeight.Medium, fontSize = LOGBOOK_ROW_FONT_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun RowScope.LogbookQslCell(sent: String, received: String, width: Int, delivery: DeliveryState? = null) {
    Row(Modifier.width(width.dp).height(LOGBOOK_ROW_HEIGHT_DP.dp).border(width = 0.5.dp, color = Color(0xFF39434A)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("▲", color = if (positiveLogStatus(sent)) Healthy else Danger, fontSize = LOGBOOK_ROW_FONT_SP.sp)
        Text("▼", color = if (positiveLogStatus(received)) Healthy else Danger, fontSize = LOGBOOK_ROW_FONT_SP.sp)
        delivery?.let {
            val attention = it in setOf(DeliveryState.REJECTED, DeliveryState.AUTH_BLOCKED, DeliveryState.BATCH_AUTH_BLOCKED, DeliveryState.PROFILE_REQUIRED,
                DeliveryState.CONFIG_REQUIRED, DeliveryState.LOCAL_CHANGED)
            Text(if (attention) "!" else if (it in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH)) "✓" else "•",
                color = if (attention) Danger else if (it in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE,
                    DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH)) Healthy else Amber,
                fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}

private fun positiveLogStatus(value: String) = value.trim().uppercase() in setOf("Y", "YES", "S", "SENT", "UPLOADED", "1", "TRUE")

@Composable private fun LogbookFilterDialog(
    draft: LogbookFilter, update: (LogbookFilter) -> Unit, fromDate: String, updateFrom: (String) -> Unit,
    toDate: String, updateTo: (String) -> Unit, error: String, onPreset: (String) -> Unit,
    onApply: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var compactTab by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), color = Panel, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A555D))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("FILTER LOGBOOK", color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text("General and QSL criteria apply together", color = Muted, fontSize = 15.sp) }
                    Spacer(Modifier.weight(1f)); IconButton(onDismiss) { Icon(Icons.Outlined.Close, "Close filters") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    if (maxWidth >= 850.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("GENERAL", color = Hold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            GeneralLogbookFilters(draft, update, fromDate, updateFrom, toDate, updateTo, onPreset, Modifier.weight(1f))
                        }
                        VerticalDivider(color = Color(0xFF465159))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("QSL & SERVICES", color = Hold, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            QslLogbookFilters(draft, update, Modifier.weight(1f))
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        PrimaryTabRow(compactTab) {
                            Tab(compactTab == 0, { compactTab = 0 }, text = { Text("GENERAL") })
                            Tab(compactTab == 1, { compactTab = 1 }, text = { Text("QSL & SYNC") })
                        }
                        if (compactTab == 0) GeneralLogbookFilters(draft, update, fromDate, updateFrom, toDate, updateTo, onPreset, Modifier.weight(1f))
                        else QslLogbookFilters(draft, update, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onApply, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(6.dp)); Text("APPLY FILTERS") }
                    OutlinedButton(onClear, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Clear, null); Spacer(Modifier.width(6.dp)); Text("CLEAR ALL") }
                    if (error.isNotBlank()) Text(error, color = Danger, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); Text("Numeric: 500, >500, <=500, or 100-500", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun GeneralLogbookFilters(filter: LogbookFilter, update: (LogbookFilter) -> Unit,
    fromDate: String, updateFrom: (String) -> Unit, toDate: String, updateTo: (String) -> Unit,
    preset: (String) -> Unit, modifier: Modifier = Modifier) {
    val modes = listOf("" to "All") + listOf("CW", "SSB", "USB", "LSB", "AM", "FM", "RTTY", "DATA", "FT8", "FT4", "JS8", "JT65", "MFSK", "PSK", "PSK31", "PSK63", "DSTAR", "FREEDV", "SSTV")
        .map { it to it }
    val bands = listOf("" to "All") + listOf("2190m", "630m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "1.25m", "70cm", "33cm", "23cm", "13cm", "sat")
        .map { it to it }
    LazyVerticalGrid(GridCells.Adaptive(210.dp), modifier, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DATE PRESETS", color = Hold, fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Today", "Yesterday", "Last 7 Days", "Last 30 Days", "This Month", "Last Month", "This Year", "Last Year")
                        .forEach { label -> OutlinedButton({ preset(label) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) } }
                }
            }
        }
        item { FilterText("From · YYYY-MM-DD", fromDate, updateFrom) }; item { FilterText("To · YYYY-MM-DD", toDate, updateTo) }
        item { FilterText("Dx / callsign", filter.callsign) { update(filter.copy(callsign = it.uppercase())) } }
        item { FilterText("Station profile", filter.stationProfile) { update(filter.copy(stationProfile = it)) } }
        item { FilterText("Station callsign", filter.stationCallsign) { update(filter.copy(stationCallsign = it.uppercase())) } }
        item { FilterChoice("Provenance", filter.provenance, listOf("" to "All", "LOCAL" to "Local", "REMOTE" to "Remote / linked")) { update(filter.copy(provenance = it)) } }
        item { FilterText("Name", filter.name) { update(filter.copy(name = it)) } }
        item { FilterText("QTH", filter.qth) { update(filter.copy(qth = it)) } }
        item { FilterText("Email", filter.email) { update(filter.copy(email = it)) } }
        item { FilterText("DXCC", filter.dxcc) { update(filter.copy(dxcc = it)) } }
        item { FilterText("Country", filter.country) { update(filter.copy(country = it)) } }
        item { FilterText("State", filter.state) { update(filter.copy(state = it.uppercase())) } }
        item { FilterText("Gridsquare", filter.grid) { update(filter.copy(grid = it.uppercase())) } }
        item { FilterText("CQ zone", filter.cqZone) { update(filter.copy(cqZone = it)) } }
        item { FilterText("ITU zone", filter.ituZone) { update(filter.copy(ituZone = it)) } }
        item { FilterChoice("Mode", filter.mode, modes) { update(filter.copy(mode = it)) } }
        item { FilterText("Submode", filter.submode) { update(filter.copy(submode = it.uppercase())) } }
        item { FilterChoice("Band", filter.band, bands) { update(filter.copy(band = it)) } }
        item { FilterText("Frequency · MHz", filter.frequency) { update(filter.copy(frequency = it)) } }
        item { FilterText("RX frequency · MHz", filter.frequencyRx) { update(filter.copy(frequencyRx = it)) } }
        item { FilterChoice("RX band", filter.bandRx, bands) { update(filter.copy(bandRx = it)) } }
        item { FilterChoice("Propagation", filter.propagation, listOf("" to "All") + propagationChoices.drop(1)) { update(filter.copy(propagation = it)) } }
        item { FilterText("County", filter.county) { update(filter.copy(county = it)) } }
        item { FilterText("DOK", filter.dok) { update(filter.copy(dok = it.uppercase())) } }
        item { FilterText("SOTA", filter.sota) { update(filter.copy(sota = it.uppercase())) } }
        item { FilterText("POTA", filter.pota) { update(filter.copy(pota = it.uppercase())) } }
        item { FilterText("IOTA", filter.iota) { update(filter.copy(iota = it.uppercase())) } }
        item { FilterText("WWFF", filter.wwff) { update(filter.copy(wwff = it.uppercase())) } }
        item { FilterText("Operator", filter.operator) { update(filter.copy(operator = it.uppercase())) } }
        item { FilterText("Contest", filter.contest) { update(filter.copy(contest = it.uppercase())) } }
        item { FilterChoice("Continent", filter.continent, listOf("" to "All") + continentChoices) { update(filter.copy(continent = it)) } }
        item { FilterText("Satellite", filter.satellite) { update(filter.copy(satellite = it)) } }
        item { FilterText("Satellite mode", filter.satelliteMode) { update(filter.copy(satelliteMode = it)) } }
        item { FilterText("Orbit", filter.orbit) { update(filter.copy(orbit = it)) } }
        item { FilterText("Comment", filter.comment) { update(filter.copy(comment = it)) } }
        item { FilterText("Notes", filter.notes) { update(filter.copy(notes = it)) } }
        item { FilterText("Distance · km", filter.distance) { update(filter.copy(distance = it)) } }
        item { FilterText("Duration · minutes", filter.duration) { update(filter.copy(duration = it)) } }
        item { FilterChoice("Sort column", filter.sort.name, LogbookSort.entries.map { it.name to it.name.lowercase().replaceFirstChar { char -> char.uppercase() } }) {
            update(filter.copy(sort = LogbookSort.valueOf(it))) } }
        item { FilterChoice("Sort direction", filter.direction.name, LogbookSortDirection.entries.map { it.name to it.name.lowercase().replaceFirstChar { char -> char.uppercase() } }) {
            update(filter.copy(direction = LogbookSortDirection.valueOf(it))) } }
    }
}

@Composable private fun QslLogbookFilters(filter: LogbookFilter, update: (LogbookFilter) -> Unit, modifier: Modifier = Modifier) {
    val qslStatuses = listOf("" to "All") + qslSentChoices
    val serviceStatuses = listOf("" to "All", "Y" to "Yes", "N" to "No")
    val methods = listOf("" to "All") + qslMethodChoices.drop(1)
    LazyVerticalGrid(GridCells.Adaptive(210.dp), modifier, horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
        item { FilterChoice("QSL sent", filter.qslSent, qslStatuses) { update(filter.copy(qslSent = it)) } }
        item { FilterChoice("QSL received", filter.qslReceived, qslStatuses) { update(filter.copy(qslReceived = it)) } }
        item { FilterChoice("QSL send method", filter.qslSentMethod, methods) { update(filter.copy(qslSentMethod = it)) } }
        item { FilterChoice("QSL receive method", filter.qslReceivedMethod, methods) { update(filter.copy(qslReceivedMethod = it)) } }
        item { FilterChoice("LoTW sent", filter.lotwSent, serviceStatuses) { update(filter.copy(lotwSent = it)) } }
        item { FilterChoice("LoTW received", filter.lotwReceived, serviceStatuses) { update(filter.copy(lotwReceived = it)) } }
        item { FilterChoice("Clublog sent", filter.clublogSent, serviceStatuses) { update(filter.copy(clublogSent = it)) } }
        item { FilterChoice("Clublog received", filter.clublogReceived, serviceStatuses) { update(filter.copy(clublogReceived = it)) } }
        item { FilterChoice("eQSL sent", filter.eqslSent, serviceStatuses) { update(filter.copy(eqslSent = it)) } }
        item { FilterChoice("eQSL received", filter.eqslReceived, serviceStatuses) { update(filter.copy(eqslReceived = it)) } }
        item { FilterChoice("DCL sent", filter.dclSent, serviceStatuses) { update(filter.copy(dclSent = it)) } }
        item { FilterChoice("DCL received", filter.dclReceived, serviceStatuses) { update(filter.copy(dclReceived = it)) } }
        item { FilterChoice("QRZ sent", filter.qrzSent, serviceStatuses) { update(filter.copy(qrzSent = it)) } }
        item { FilterChoice("QRZ received", filter.qrzReceived, serviceStatuses) { update(filter.copy(qrzReceived = it)) } }
        item { FilterText("QSL via", filter.qslVia) { update(filter.copy(qslVia = it)) } }
        item { FilterChoice("QSL images", filter.qslImages, listOf("" to "All", "Y" to "Has images", "N" to "No images")) {
            update(filter.copy(qslImages = it)) } }
        item { FilterText("QSL message", filter.qslMessage) { update(filter.copy(qslMessage = it)) } }
        item { FilterChoice("Record validity", filter.recordState, listOf("" to "All", "VALID" to "Valid", "INCOMPLETE" to "Incomplete / invalid")) {
            update(filter.copy(recordState = it)) } }
        item { FilterChoice("Duplicate candidates", filter.duplicateState, listOf("" to "All", "CANDIDATE" to "Same call/frequency/mode within 15s")) {
            update(filter.copy(duplicateState = it)) } }
        item { FilterChoice("Wavelog relation", filter.syncRelation, listOf("" to "All", "LOCAL_ONLY" to "Local only", "LINKED" to "Remote linked",
            "OUTBOX" to "Queued / retry", "CONFLICT" to "Conflict")) {
            update(filter.copy(syncRelation = it)) } }
    }
}

@Composable private fun FilterText(label: String, value: String, change: (String) -> Unit) =
    LogField(label, value, change, Modifier.fillMaxWidth())

@Composable private fun FilterChoice(label: String, value: String, choices: List<Pair<String, String>>, change: (String) -> Unit) =
    ChoiceField(label, choices.firstOrNull { it.first == value }?.second ?: value, choices, value, change, Modifier.fillMaxWidth())

private data class StatusColourSwatch(val name: String, val argb: Int)

private val statusColourPalette = listOf(
    StatusColourSwatch("Ink", 0xFFF4F0E7.toInt()), StatusColourSwatch("Silver", 0xFFA5ADB2.toInt()),
    StatusColourSwatch("Slate", 0xFF6F7B83.toInt()), StatusColourSwatch("Graphite", 0xFF4A555D.toInt()),
    StatusColourSwatch("Red", 0xFFE4544D.toInt()), StatusColourSwatch("Coral", 0xFFF06F5B.toInt()),
    StatusColourSwatch("Orange", 0xFFF58B3A.toInt()), StatusColourSwatch("Amber", 0xFFE9A72B.toInt()),
    StatusColourSwatch("Yellow", 0xFFF4C94E.toInt()), StatusColourSwatch("Lime", 0xFFA9D451.toInt()),
    StatusColourSwatch("Green", 0xFF42C77B.toInt()), StatusColourSwatch("Teal", 0xFF35B7A6.toInt()),
    StatusColourSwatch("Cyan", 0xFF43C7D9.toInt()), StatusColourSwatch("Sky", 0xFF5DADE2.toInt()),
    StatusColourSwatch("Blue", 0xFF5B8FF9.toInt()), StatusColourSwatch("Indigo", 0xFF6977D8.toInt()),
    StatusColourSwatch("Violet", 0xFF9C6ADE.toInt()), StatusColourSwatch("Purple", 0xFFC481D8.toInt()),
    StatusColourSwatch("Magenta", 0xFFE06BB1.toInt()), StatusColourSwatch("Pink", 0xFFF08BA7.toInt()),
)

internal fun spotColourHex(argb: Int): String = String.format(Locale.US, "%06X", argb and 0xFFFFFF)

internal fun parseSpotColourHex(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) return null
    return (normalized.toLong(16) or 0xFF000000L).toInt()
}

private fun statusColourForeground(argb: Int): Color {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return if ((red * 299 + green * 587 + blue * 114) / 1000 >= 155) Color.Black else Color.White
}

@Composable private fun StatusColourSettingsGroup(app: AppController, dimension: String, options: List<String>) {
    val filterDimension = if (dimension == SPOT_STATUS_CS) SpotFilterDimension.CALL_STATUS else SpotFilterDimension.DXCC_STATUS
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 900.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { status ->
                        StatusColourEditor(app, dimension, status, spotFilterOptionLabel(filterDimension, status), Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun StatusColourEditor(app: AppController, dimension: String, status: String, label: String,
    modifier: Modifier = Modifier) {
    val current = app.spotStatusColour(dimension, status)
    var hex by remember(current) { mutableStateOf(spotColourHex(current)) }
    val parsed = parseSpotColourHex(hex)
    Column(modifier.padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Surface(color = Color(current), shape = RoundedCornerShape(7.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .35f))) {
                Text(status, color = statusColourForeground(current), fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            statusColourPalette.forEach { swatch ->
                Surface(onClick = { app.setSpotStatusColour(dimension, status, swatch.argb) },
                    color = Color(swatch.argb), shape = RoundedCornerShape(9.dp),
                    border = androidx.compose.foundation.BorderStroke(if (current == swatch.argb) 3.dp else 1.dp,
                        if (current == swatch.argb) Ink else Color.White.copy(alpha = .28f)),
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Set $dimension $status colour to ${swatch.name}"
                    }) {
                    if (current == swatch.argb) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, tint = statusColourForeground(swatch.argb), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hex, { hex = it.removePrefix("#").uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(6) },
                label = { Text("Custom hex") }, prefix = { Text("#") }, singleLine = true,
                isError = hex.isNotBlank() && parsed == null, modifier = Modifier.width(150.dp))
            Button({ parsed?.let { app.setSpotStatusColour(dimension, status, it) } }, enabled = parsed != null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("SET") }
        }
        HorizontalDivider(color = Color(0xFF354047))
    }
}

@Composable private fun SettingsScreen(state: RadioState, detail: String, database: QsoDatabase,
    mutations: QsoMutationCoordinator, features: FeatureController, neuralDx: NeuralDxController, wavelog: WavelogController,
    syncHub: SyncHubController, callbook: CallbookController, cty: CtyController,
    audio: AudioMonitorController, panadapter: PanadapterController, app: AppController, transport: UsbRadioTransport,
    flex: FlexRadioController, digi: DigiController, voiceStore: VoiceMacroStore,
    voiceAudio: VoiceMacroAudioController, voiceTx: VoiceMacroTransmitController, groupsIo: GroupsIoController,
    operatingContext: OperatingContextSnapshot, keyerProfiles: KeyerProfileStore, keyer: KeyerController, repeatCq: RepeatCqController,
    bandMaps: BandMapController, contestRuntime: ContestRuntime, chaserRuntime: DxChaserRuntime,
    hamlibModels: List<HamlibModelDescriptor>, rotator: AndroidRotatorRuntime,
    tciRuntime: TciRuntimeState, tciTransmit: TciTransmitAuthority, tciRxAudio: TciRxAudioController, scanner: ReceiveOnlyScannerController,
    sdrOperationalV2: SdrOperationalV2, sdrWorkbenchV4: AndroidSdrWorkbenchV4,
    localReceivers: LocalReceiverController, rfObservations: RfObservationController,
    bandStacks: BandStackStore, announcements: SpokenAnnouncementController, debugSdrLab: DebugSdrLab?,
    controlSurfaces: ControlSurfaceController,
    openEq: () -> Unit, openContest: () -> Unit, openSync: () -> Unit, openDigi: () -> Unit, openGroupsIo: () -> Unit,
    openRotator: () -> Unit, disconnectRadio: () -> Unit,
    reconnect: () -> Unit, direct: (String) -> Unit) {
    val inAppBrowser = LocalInAppBrowserState.current
    var section by remember { mutableStateOf(SettingsSection.RADIO) }
    var hamlibSearch by remember { mutableStateOf("") }
    var showRadioWizard by remember { mutableStateOf(false) }
    var showTciWizard by remember { mutableStateOf(false) }
    var showRotatorWizard by remember { mutableStateOf(false) }
    var showAlertProfileHelp by remember { mutableStateOf(false) }
    var radioProfilesExpanded by rememberSaveable { mutableStateOf(false) }
    var radioAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var host by remember { mutableStateOf(features.clusterHost) }; var port by remember { mutableStateOf(features.clusterPort.toString()) }
    var fallbackHost by remember { mutableStateOf(features.fallbackHost) }; var fallbackPort by remember { mutableStateOf(features.fallbackPort.toString()) }
    var fallback2Host by remember { mutableStateOf(features.fallback2Host) }; var fallback2Port by remember { mutableStateOf(features.fallback2Port.toString()) }
    var callsign by remember { mutableStateOf(features.clusterCallsign) }; var watch by remember { mutableStateOf(features.watchlistText) }; var raw by remember { mutableStateOf("") }
    var historyCount by remember { mutableIntStateOf(features.clusterDiagnostics.historyCount) }
    var stationCall by remember { mutableStateOf(app.stationCallsign) }; var stationName by remember { mutableStateOf(app.stationName) }
    var stationGrid by remember { mutableStateOf(app.stationGrid) }; var repeatSeconds by remember { mutableIntStateOf(app.cqRepeatSeconds) }
    var qrzEnabled by remember { mutableStateOf(callbook.qrzEnabled) }; var qrzUser by remember { mutableStateOf(callbook.qrzUsername) }
    var qrzPassword by remember { mutableStateOf(callbook.qrzPassword) }
    var hamQthEnabled by remember { mutableStateOf(callbook.hamQthEnabled) }; var hamQthUser by remember { mutableStateOf(callbook.hamQthUsername) }
    var hamQthPassword by remember { mutableStateOf(callbook.hamQthPassword) }
    LaunchedEffect(stationCall, stationName, stationGrid) {
        delay(300); app.updateStationIdentity(stationCall, stationName, stationGrid)
    }
    LaunchedEffect(host, port, callsign, fallbackHost, fallbackPort, fallback2Host, fallback2Port) {
        delay(300); features.saveClusterConfiguration(host, port.toIntOrNull() ?: features.clusterPort, callsign,
            fallbackHost, fallbackPort.toIntOrNull() ?: features.fallbackPort,
            fallback2Host, fallback2Port.toIntOrNull() ?: features.fallback2Port)
    }
    LaunchedEffect(qrzEnabled, qrzUser, qrzPassword) {
        delay(300); callbook.configureQrz(qrzEnabled, qrzUser, qrzPassword)
    }
    LaunchedEffect(hamQthEnabled, hamQthUser, hamQthPassword) {
        delay(300); callbook.configureHamQth(hamQthEnabled, hamQthUser, hamQthPassword)
    }
    var systemMessage by remember { mutableStateOf("No recovery operation run this session") }
    var dxNotifications by remember { mutableStateOf(neuralDx.notificationsEnabled) }
    var ntfyUrl by remember { mutableStateOf(neuralDx.ntfyUrl) }; var ntfyToken by remember { mutableStateOf(neuralDx.ntfyToken) }
    var perplexityKey by remember { mutableStateOf(neuralDx.perplexityKey) }; var briefingDxMode by remember { mutableStateOf(neuralDx.briefingDxMode) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemMessage = if (granted) "Neural DX notifications enabled" else "Notification permission was not granted"
    }
    val macroLabels = remember { mutableStateListOf(*app.macroLabels.toTypedArray()) }
    val macroTexts = remember { mutableStateListOf(*app.macroTexts.toTypedArray()) }
    val voiceLabels = remember { mutableStateListOf(*app.voiceMacroLabels.toTypedArray()) }
    var macroKind by remember { mutableStateOf("CW") }
    var pendingImportSlot by remember { mutableStateOf<Int?>(null) }
    var pendingRecordSlot by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteSlot by remember { mutableStateOf<Int?>(null) }
    val settingsScope = rememberCoroutineScope()
    var pendingCatKey by remember { mutableStateOf<String?>(null) }
    var catSelectionDirty by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(app.fieldProfile) }; var brightness by remember { mutableFloatStateOf(app.brightness.toFloat()) }
    var autoDim by remember { mutableStateOf(app.autoDim) }; var tones by remember { mutableStateOf(app.alertTones) }
    var quiet by remember { mutableStateOf(app.quietAlerts) }; var program by remember { mutableStateOf(app.activationProgram) }
    var activation by remember { mutableStateOf(app.activationReference) }
    var manualFlexIp by remember { mutableStateOf(app.manualFlexIp) }
    LaunchedEffect(profile, brightness, autoDim, tones, quiet, program, activation) {
        delay(300)
        app.saveFieldSettings(profile, brightness.toInt(), autoDim, tones, quiet, program, activation)
    }
    LaunchedEffect(transport.selected?.sessionKey, transport.candidates) {
        if (!catSelectionDirty) pendingCatKey = transport.selected?.sessionKey
    }
    var restorePayload by remember { mutableStateOf<String?>(null) }
    var recoveryPreview by remember { mutableStateOf<ConfigurationPreview?>(null) }
    val selectedRecoverySections = remember { mutableStateListOf<String>() }
    var supportBundleBytes by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    var stability by remember { mutableStateOf<StabilitySnapshot?>(null) }
    var confirmProjectionRebuild by remember { mutableStateOf(false) }
    fun refreshStability() { settingsScope.launch { stability = withContext(Dispatchers.IO) { StabilityDiagnostics.snapshot(context, database) } } }
    LaunchedEffect(section) { if (section in setOf(SettingsSection.HEALTH, SettingsSection.DIAG)) refreshStability() }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(app.recoveryText()) } }
            .onSuccess { systemMessage = "Recovery file exported" }.onFailure { error -> systemMessage = "Export failed: ${error.message}" } }
    }
    val exportSupport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = supportBundleBytes
        supportBundleBytes = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Support destination unavailable") }
            .onSuccess { systemMessage = "Sanitized support bundle exported" }
            .onFailure { systemMessage = "Support export failed: ${it.message}" }
    }
    val openRecovery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty() }
            .onSuccess { payload ->
                runCatching { app.previewRecovery(payload) }.onSuccess { preview ->
                    recoveryPreview = preview
                    selectedRecoverySections.clear(); selectedRecoverySections.addAll(preview.selectedByDefault)
                    restorePayload = payload
                    systemMessage = app.reviewRecovery(payload)
                }.onFailure { error -> systemMessage = "Restore review failed: ${error.message}" }
            }
            .onFailure { error -> systemMessage = "Restore review failed: ${error.message}" } }
    }
    val exportAdif = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-adif")) { uri ->
        uri?.let { target -> settingsScope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(target)?.use { database.streamExportADIF(it) } ?: error("Export destination could not be opened") } }
            .onSuccess { systemMessage = "ADIF exported from tablet database" }.onFailure { error -> systemMessage = "ADIF export failed: ${error.message}" } } }
    }
    val importAdif = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { target -> settingsScope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(target)?.use { mutations.importADIF(it) } ?: AdifImportProgress(0,0,0,1) } }
            .onSuccess { result -> systemMessage = "ADIF import · ${result.inserted} added · ${result.duplicates+result.invalid} skipped" }
            .onFailure { error -> systemMessage = "ADIF import failed: ${error.message}" } } }
    }
    val importVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pendingImportSlot
        pendingImportSlot = null
        if (uri != null && slot != null) runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBoundedVoiceWave() } ?: error("WAV could not be opened") }
            .onSuccess { voiceAudio.importWave(slot, it) }.onFailure { systemMessage = "WAV import failed: ${it.message}" }
    }
    val recordVoicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val slot = pendingRecordSlot
        pendingRecordSlot = null
        if (granted && slot != null) voiceAudio.startRecording(slot) else if (!granted) systemMessage = "Microphone permission was not granted"
    }
    if (confirmProjectionRebuild) AlertDialog(
        onDismissRequest = { confirmProjectionRebuild = false },
        title = { Text("Rebuild QSO projection?") },
        text = { Text("Canonical QSOs are preserved. The indexed projection will be reset and rebuilt in resumable batches.") },
        confirmButton = { Button({
            confirmProjectionRebuild = false
            settingsScope.launch {
                withContext(Dispatchers.IO) { database.rebuildProjection() }
                systemMessage = "Projection rebuild started; canonical QSOs preserved"
                while (withContext(Dispatchers.IO) { database.backfillProjectionBatch() }) {
                    val progress = withContext(Dispatchers.IO) { database.projectionHealth() }
                    systemMessage = "Projection rebuild · ${progress.processedRows}/${progress.canonicalRows}"
                    delay(25)
                }
                val completed = withContext(Dispatchers.IO) { database.verifyProjection() }
                systemMessage = if (completed.state == ProjectionState.READY) "Projection rebuild complete and verified"
                    else "Projection rebuild stopped · ${completed.state} · ${completed.lastError}"
                refreshStability()
            }
        }) { Text("REBUILD") } },
        dismissButton = { TextButton({ confirmProjectionRebuild = false }) { Text("CANCEL") } },
    )
    if (showRadioWizard) AlertDialog(
        onDismissRequest = { showRadioWizard = false },
        title = { Text("Add radio · choose backend") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RadioProfileCatalog.nativeProfiles, key = { it.id.value }) { candidate ->
                OutlinedButton({ app.selectRadioProfile(candidate); showRadioWizard = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(candidate.name, fontWeight = FontWeight.Bold)
                        Text("Native recommended · ${candidate.transport.name.replace('_', ' ')} · selection stays disconnected",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedButton({ showRadioWizard = false; showTciWizard = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("TCI radio", fontWeight = FontWeight.Bold)
                        Text("Native WebSocket · multi-receiver · receive-only and TX locked · selection stays disconnected",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedButton({
                    hamlibModels.firstOrNull()?.let { app.selectHamlibModel(it.id, it.manufacturer, it.model) }
                    hamlibSearch = ""
                    showRadioWizard = false
                }, enabled = hamlibModels.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Hamlib radio", fontWeight = FontWeight.Bold)
                        Text("Generic compatibility · searchable ${hamlibModels.size}-model registry · selection stays disconnected",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton({ showRadioWizard = false }) { Text("CANCEL") } },
    )
    if (showTciWizard) TciProfileDialog(app) { showTciWizard = false }
    if (showRotatorWizard) AlertDialog(
        onDismissRequest = { showRotatorWizard = false },
        title = { Text("Add rotator") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Profile creation never connects, moves, parks, or arms automation.")
            Button({
                rotator.upsertProfile(RotatorDeviceProfile(
                    name = "rotctld (review endpoint)", backend = RotatorBackend.REMOTE_ROTCTLD,
                    protocol = RotatorProtocolKind.ROTCTLD, transport = RotatorTransportKind.ROTCTLD,
                    tcp = TcpSettings("127.0.0.1", 4533, lanOptIn = false),
                ))
                showRotatorWizard = false
            }, modifier = Modifier.fillMaxWidth()) { Text("ADD ROTCTLD PROFILE") }
            listOf("NATIVE · GS-232 / EASYCOMM / SPID", "EMBEDDED HAMLIB ROTATOR", "ARCO · PUBLISHED COMPATIBILITY MODE").forEach { family ->
                OutlinedButton({ systemMessage = "$family selected · open the Rotator workspace to choose the required model and stable transport identity"; showRotatorWizard = false },
                    modifier = Modifier.fillMaxWidth()) { Text(family) }
            }
            Text("Native GS-232/EasyComm/SPID and ARCO compatibility require selecting a stable attached serial identity. Embedded Hamlib requires an explicit model/transport. Complete those reviewed parameters in the Rotator workspace.",
                color = Muted)
        } },
        confirmButton = {},
        dismissButton = { TextButton({ showRotatorWizard = false }) { Text("CANCEL") } },
    )
    if (showAlertProfileHelp) AlertDialog(
        onDismissRequest = { showAlertProfileHelp = false },
        title = { Text("Day, Night and Field profiles") },
        text = { Text("Profiles apply brightness, auto dimming, audible tones and quieting of non-critical alerts immediately. They never change radio, Digi, cluster, provider, Contest, transmit or rotator state. Restore remains disconnected and disarmed.") },
        confirmButton = { TextButton({ showAlertProfileHelp = false }) { Text("CLOSE") } },
    )
    SettingsPage {
        Header("Complete station settings", state)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SettingsSection.entries.forEach { item -> FilterChip(section == item, { section = item }, { Text(item.label) }) }
        }
        if (section == SettingsSection.RADIO) SettingsCard("RADIO PROFILES") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ showRadioWizard = true }) { Text("ADD RADIO") }
                OutlinedButton({ showRadioWizard = true }) { Text("CHOOSE ACTIVE PROFILE") }
            }
            Text("Select the single active radio backend. Switching closes the previous connection before the next backend starts.", color = Muted)
            val activeRadioProfile = app.selectedRadioProfile
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Healthy.copy(alpha = .12f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ACTIVE RADIO", color = Healthy, fontWeight = FontWeight.Black)
                    Text(activeRadioProfile.name, fontWeight = FontWeight.Bold)
                    Text("${activeRadioProfile.backendKind} · ${activeRadioProfile.model} · ${if (state.connected) "CONNECTED" else "DISCONNECTED"}", color = Muted)
                }
            }
            OutlinedButton({ radioProfilesExpanded = !radioProfilesExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (radioProfilesExpanded) "HIDE PROFILE LIST" else "MANAGE RADIO PROFILES")
            }
            if (radioProfilesExpanded) {
            (RadioProfileCatalog.nativeProfiles + app.tciProfiles).forEach { candidate ->
                val active = app.selectedRadioProfileId == candidate.id
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (active) Healthy.copy(alpha = .12f) else Panel)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(candidate.name, fontWeight = FontWeight.Bold)
                            Text(if (active && state.connected) "CONNECTED" else if (active) "ACTIVE · DISCONNECTED" else "DISCONNECTED",
                                color = if (active && state.connected) Healthy else Muted)
                        }
                        Text("${candidate.backendKind} · ${candidate.model} · ${candidate.transport} · ${if (candidate.readOnly) "READ ONLY" else "operator controlled"}${if (active) " · DEFAULT" else ""}", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton({ app.selectRadioProfile(candidate) }) { Text(if (active) "ACTIVE" else "CHOOSE") }
                            TextButton({
                                if (candidate.backendKind == RadioBackendKind.NATIVE_TCI) showTciWizard = true
                                else systemMessage = "Edit ${candidate.name} · select transport parameters in the profile wizard"
                            }) { Text("EDIT") }
                            if (active && state.connected) TextButton({ settingsScope.launch {
                                disconnectRadio(); transport.disconnect(); flex.disconnect()
                            } }) { Text("DISCONNECT") }
                            else TextButton(reconnect, enabled = active) { Text("CONNECT") }
                            TextButton({
                                if (candidate.backendKind == RadioBackendKind.NATIVE_TCI) app.deleteTciProfile(candidate.id)
                            }, enabled = candidate.backendKind == RadioBackendKind.NATIVE_TCI && !active) { Text("DELETE") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (RadioProfileCatalog.nativeProfiles + app.tciProfiles).forEach { profile ->
                    FilterChip(app.selectedRadioProfileId == profile.id, { app.selectRadioProfile(profile) }, { Text(profile.name) })
                }
            }
            }
            OutlinedButton({ radioAdvancedExpanded = !radioAdvancedExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (radioAdvancedExpanded) "HIDE ADVANCED RADIO OPTIONS" else "SHOW ADVANCED RADIO OPTIONS")
            }
            if (radioAdvancedExpanded) {
            SdrSettingsPanel(tciRuntime, tciRxAudio, scanner, sdrOperationalV2, sdrWorkbenchV4, localReceivers, rfObservations, announcements, bandStacks, debugSdrLab)
            Text("Native profiles are preferred when ShackCQ has a dedicated integration. Unknown or future stored identifiers restore disconnected.", color = Muted)
            OutlinedTextField(
                hamlibSearch,
                { hamlibSearch = it.take(80) },
                label = { Text("Search ${hamlibModels.size} embedded Hamlib models") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val modelMatches = remember(hamlibSearch, hamlibModels) {
                val needle = hamlibSearch.trim()
                hamlibModels.asSequence().filter { needle.isBlank() || it.label.contains(needle, true) || it.id.toString() == needle }
                    .take(24).toList()
            }
            if (hamlibSearch.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                modelMatches.forEach { model ->
                    FilterChip(
                        app.selectedRadioProfile.hamlibModelId == model.id,
                        { app.selectHamlibModel(model.id, model.manufacturer, model.model) },
                        { Text("${model.label} · ${model.id} · ${model.status}") },
                    )
                }
                if (modelMatches.isEmpty()) Text("No configured Hamlib model matches this search.", color = Muted)
            }
            Text("EQ DESTINATION", color = Amber, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqVisibilityPolicy.entries.forEach { policy ->
                    FilterChip(app.eqVisibilityPolicy == policy, { app.updateEqVisibilityPolicy(policy) }, { Text(policy.name) })
                }
            }
            val nativeElecraft = app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT
            val eqShown = nativeElecraft && eqDestinationVisible(app.eqVisibilityPolicy, app.radioFamily)
            Text(when (app.eqVisibilityPolicy) {
                EqVisibilityPolicy.AUTO -> if (eqShown) "AUTO · supported ${app.radioFamily.displayName} selected" else "AUTO · hidden for ${app.radioFamily.displayName}"
                EqVisibilityPolicy.SHOW -> if (nativeElecraft) "SHOW · supported ${app.radioFamily.displayName}" else "SHOW · unavailable for ${app.selectedRadioProfile.name}"
                EqVisibilityPolicy.HIDE -> "HIDE · active EQ route redirects to Radio"
            }, color = if (eqShown) Healthy else Muted)
            OutlinedButton(openEq, enabled = eqShown, modifier = Modifier.heightIn(min = 48.dp)) { Text("OPEN EQ") }
            if (app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_FLEX) {
                Text(flex.connectionState.label, color = Hold, fontWeight = FontWeight.Bold)
                Text("KX USB polling, KX EQ and the physical-I/Q panadapter are disabled while FlexRadio is selected. Flex uses its own SmartLink/LAN, VITA panafall, PC audio and session-gated transmit cockpit.", color = Muted)
                val invalidManualFlexIp = manualFlexIp.isNotBlank() && manualFlexDiscovery(manualFlexIp) == null
                OutlinedTextField(
                    value = manualFlexIp,
                    onValueChange = { manualFlexIp = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
                    label = { Text("Manual radio IPv4 address") },
                    supportingText = { Text("For routed LAN or VPN access when UDP discovery is unavailable. Flex TCP/UDP port 4992 is used.") },
                    singleLine = true,
                    isError = invalidManualFlexIp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button({
                    if (app.saveManualFlexIp(manualFlexIp)) {
                        manualFlexIp = app.manualFlexIp
                        systemMessage = if (manualFlexIp.isBlank()) "Manual FlexRadio address cleared" else "Manual FlexRadio address saved"
                    } else systemMessage = "Enter a valid IPv4 address"
                }, enabled = !invalidManualFlexIp) { Text("SAVE FLEX ADDRESS") }
            } else Text("${app.selectedRadioProfile.name} · ${app.selectedRadioProfile.backendKind} · ${if (app.selectedRadioProfile.readOnly) "READ ONLY" else "operator-controlled"}.", color = Muted)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("KX2 / KX3 PANADAPTER · IN DEVELOPMENT", color = Amber, fontWeight = FontWeight.Bold)
                    Text("Android can be unreliable with inexpensive USB sound cards and hubs. Leave this off unless your I/Q route is known-good.", color = Hold)
                }
                Switch(app.panadapterEnabled, app::updatePanadapterEnabled,
                    enabled = app.selectedRadioProfile.backendKind == RadioBackendKind.NATIVE_ELECRAFT)
            }
            }
        }
        if (section == SettingsSection.BAND_MAPS) SettingsCard("INTELLIGENT BAND MAPS") { BandMapSettingsPanel(bandMaps) }
        if (section == SettingsSection.CONTEST) SettingsCard("CONTEST VISIBILITY & N1MM") {
            Text("Contest history, sessions and QSOs are preserved when the destination is hidden.", color = Muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(app.contestEnabled, { enabled ->
                    if (!enabled) contestRuntime.pause("DESTINATION DISABLED")
                    app.updateContestEnabled(enabled)
                })
                Column(Modifier.weight(1f)) {
                    Text(if (app.contestEnabled) "CONTEST DESTINATION ENABLED" else "CONTEST DESTINATION HIDDEN",
                        fontWeight = FontWeight.Bold)
                    Text("N1MM remains default-off and unarmed; hiding Contest stops the active runtime without deleting data.", color = Muted)
                }
            }
            val contestPrefs = app.contestGlobalPreferences
            Text("GLOBAL CONTEST PREFERENCES", color = Amber, fontWeight = FontWeight.Bold)
            Text("Session definition, edition, name, dates, station/operator, exchange, serial and live role remain in the Contest setup.", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("RUN", "SEARCH_AND_POUNCE").forEach { role -> FilterChip(contestPrefs.defaultRole == role,
                    { app.updateContestGlobalPreferences(contestPrefs.copy(defaultRole = role)) }, { Text(if (role == "RUN") "DEFAULT RUN" else "DEFAULT S&P") }) }
                FilterChip(contestPrefs.esmEnabled, { app.updateContestGlobalPreferences(contestPrefs.copy(esmEnabled = !contestPrefs.esmEnabled)) }, { Text("ESM") })
                FilterChip(contestPrefs.dupeWarnings, { app.updateContestGlobalPreferences(contestPrefs.copy(dupeWarnings = !contestPrefs.dupeWarnings)) }, { Text("DUPE WARNINGS") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("MANUAL", "WEEKLY", "MONTHLY").forEach { policy -> FilterChip(contestPrefs.scpUpdatePolicy == policy,
                    { app.updateContestGlobalPreferences(contestPrefs.copy(scpUpdatePolicy = policy)) }, { Text("SCP $policy") }) }
                listOf(5, 8, 12).forEach { count -> FilterChip(contestPrefs.suggestionCount == count,
                    { app.updateContestGlobalPreferences(contestPrefs.copy(suggestionCount = count)) }, { Text("$count SUGGESTIONS") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("REVIEW", "AUTO_SAFE", "KEEP_TEMPORARY").forEach { policy -> FilterChip(contestPrefs.mergePolicy == policy,
                    { app.updateContestGlobalPreferences(contestPrefs.copy(mergePolicy = policy)) }, { Text("MERGE ${policy.replace('_', ' ')}") }) }
                listOf("DENSE", "STANDARD", "WIDE").forEach { preset -> FilterChip(contestPrefs.panelPreset == preset,
                    { app.updateContestGlobalPreferences(contestPrefs.copy(panelPreset = preset)) }, { Text(preset) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(contestPrefs.defaultCategory, { app.updateContestGlobalPreferences(contestPrefs.copy(defaultCategory = it.take(40))) }, label = { Text("Default category/operator") }, modifier = Modifier.weight(1f))
                OutlinedTextField(contestPrefs.temporaryLogRetentionDays.toString(), { app.updateContestGlobalPreferences(contestPrefs.copy(temporaryLogRetentionDays = it.toIntOrNull() ?: contestPrefs.temporaryLogRetentionDays)) }, label = { Text("Temporary log days") }, modifier = Modifier.weight(.7f))
                OutlinedTextField(contestPrefs.keyerProfileDefault, { app.updateContestGlobalPreferences(contestPrefs.copy(keyerProfileDefault = it.take(40))) }, label = { Text("Default keyer profile") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(contestPrefs.cabrilloOperator, { app.updateContestGlobalPreferences(contestPrefs.copy(cabrilloOperator = it)) }, label = { Text("Cabrillo operator") }, modifier = Modifier.weight(1f))
                OutlinedTextField(contestPrefs.cabrilloAddress, { app.updateContestGlobalPreferences(contestPrefs.copy(cabrilloAddress = it)) }, label = { Text("Cabrillo address") }, modifier = Modifier.weight(2f))
            }
            Text("N1MM default · DISABLED · loopback-only unless trusted-LAN is separately reviewed · current ${contestPrefs.n1mmPolicy}", color = Hold)
            Text("Session ${contestRuntime.activeSession.state} · N1MM ${if (contestRuntime.snapshot().n1mmEnabled) "configured" else "disabled"} · " +
                if (contestRuntime.snapshot().n1mmArmed) "ARMED" else "SAFE", color = Hold)
            Button(openContest, enabled = app.contestEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("OPEN CONTEST SETUP / NETWORK")
            }
        }
        if (section == SettingsSection.DIGI) SettingsCard("NEXUS DIGI") {
            Button(openDigi, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("OPEN DIGI / DX CHASER") }
            Text("DX Chaser ${chaserRuntime.snapshot.session.state} · detailed controls live in the DX Chaser tab.", color = Muted)
            Text("Digital-mode setup is shared with the Digi cockpit so mode capabilities, USB audio health, waterfall calibration, UDP interoperability, and storage diagnostics always describe the active session.", color = Muted)
            Button(onClick = openDigi) { Text("OPEN DIGI SETUP") }
            Text("Opening setup never enables or arms transmit. Digi TX remains a separate session switch plus one-shot arm.", color = Muted)
        }
        if (section == SettingsSection.ROTATOR) SettingsCard("ROTATOR PROFILES") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ROTATOR WORKSPACE", color = Amber, fontWeight = FontWeight.Bold)
                    Text("Opt-in navigation. Restoring this setting does not connect, arm automation, park, or move hardware.", color = Muted)
                }
                Switch(app.rotatorEnabled, app::updateRotatorEnabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ showRotatorWizard = true }) { Text("ADD ROTATOR") }
                OutlinedButton(openRotator) { Text("OPEN ROTATOR WORKSPACE") }
                OutlinedButton({ systemMessage = "Band assignments are managed per profile; creation never moves hardware" },
                    enabled = rotator.profiles.isNotEmpty()) { Text("MANAGE BAND ASSIGNMENTS") }
            }
            Text("Configured profiles · ${rotator.profiles.size}", color = if (rotator.profiles.isNotEmpty()) Healthy else Muted)
            if (rotator.profiles.isEmpty()) Text("No rotator is configured. Add a reviewed native, rotctld, embedded-Hamlib, or ARCO compatibility profile. Creating it remains disconnected and disarmed.", color = Muted)
            rotator.profiles.forEach { profile ->
                val connected = rotator.state?.profileId == profile.id && rotator.state?.connected == true
                Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(profile.name, fontWeight = FontWeight.Bold)
                        Text(if (connected) "CONNECTED" else "DISCONNECTED", color = if (connected) Healthy else Muted)
                    }
                    Text("${profile.backend} · ${profile.protocol} · ${profile.transport} · position ${rotator.state?.azimuthDeg?.let { "%.1f°".format(it) } ?: "unknown"}", color = Muted)
                    Text("${rotator.store.snapshot().bandAssignments.count { it.rotatorProfileId == profile.id }} band assignments · automation OFF until explicitly armed", color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ systemMessage = "Edit ${profile.name} in the Rotator workspace" }) { Text("EDIT") }
                        if (connected) TextButton({ settingsScope.launch { rotator.disconnect() } }) { Text("DISCONNECT") }
                        else TextButton({ settingsScope.launch { rotator.connect(profile.id) } }) { Text("CONNECT") }
                        TextButton({ settingsScope.launch { rotator.deleteProfile(profile.id) } }) { Text("DELETE") }
                    }
                } }
            }
            Text("Serial identities are stored as hashes. LAN endpoints are excluded from ordinary recovery exports. Automation arm and satellite tracking are session-only.", color = Hold)
        }
        if (section == SettingsSection.LOG || section == SettingsSection.MACROS) SettingsCard(if (section == SettingsSection.LOG) "LOCAL STATION" else "MACROS") {
            if (section == SettingsSection.LOG) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(stationCall, { stationCall = it.uppercase() }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(stationName, { stationName = it }, label = { Text("Operator / station") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(stationGrid, { stationGrid = it.uppercase() }, label = { Text("Grid") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button({ app.saveLocalSettings(stationCall, stationName, stationGrid, repeatSeconds, macroLabels, macroTexts) },
                        modifier = Modifier.heightIn(min = 56.dp)) { Text("SAVE DEFAULTS") }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(stationCall, { stationCall = it.uppercase() }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(stationName, { stationName = it }, label = { Text("Operator / station") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(stationGrid, { stationGrid = it.uppercase() }, label = { Text("Grid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button({ app.saveLocalSettings(stationCall, stationName, stationGrid, repeatSeconds, macroLabels, macroTexts) }) { Text("SAVE DEFAULTS") }
                }
            }
            Text("Station identity is saved automatically and retained across app updates.", color = Muted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(macroKind == "CW", { macroKind = "CW" }, { Text("CW") })
                    FilterChip(macroKind == "VOICE", { macroKind = "VOICE" }, { Text("VOICE") })
                }
                if (macroKind == "CW") {
                    val configuredMacros = macroTexts.count(String::isNotBlank)
                    Text("$configuredMacros of $CW_MACRO_COUNT configured · blank messages stay hidden on the Radio screen.", color = Muted)
                    Text("Templates use the current logging fields. Example: <HIS CALL> TU 599 BK. Available fields: {CALL}, {MYCALL}, {NAME}, {QTH}, {RST_SENT}, {RST_RECV}, {GRID}, {MYGRID}, {BAND}, {MODE}, {SERIAL}, {EXCHANGE}. Add ? before } for an optional field, for example {NAME?}. Contest Run and S&P profiles resolve the same fields from the active Contest entry. The expanded message must fit the verified $CW_MACRO_TEXT_MAX-character radio limit.", color = Hold)
                    BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-cw-macro-grid")) {
                        val wideMacros = maxWidth >= 600.dp
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (wideMacros) Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Spacer(Modifier.width(42.dp))
                                Text("BUTTON LABEL", color = Amber, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text("CW MESSAGE", color = Amber, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(3f))
                            }
                            repeat(CW_MACRO_COUNT) { index ->
                                if (wideMacros) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    MacroNumber(index, macroTexts[index].isNotBlank())
                                    OutlinedTextField(macroLabels[index], { macroLabels[index] = sanitizeCwMacroLabel(it) },
                                        label = { Text("Button label") }, placeholder = { Text("M${index + 1}") },
                                        singleLine = true, modifier = Modifier.weight(1f))
                                    OutlinedTextField(macroTexts[index], { macroTexts[index] = sanitizeCwMacroTemplate(it) },
                                        label = { Text("CW message") },
                                        trailingIcon = { Text("${macroTexts[index].length}/$CW_MACRO_TEMPLATE_MAX", color = Muted,
                                            style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true, modifier = Modifier.weight(3f))
                                } else Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Raised)) {
                                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MacroNumber(index, macroTexts[index].isNotBlank())
                                            OutlinedTextField(macroLabels[index], { macroLabels[index] = sanitizeCwMacroLabel(it) },
                                                label = { Text("Button label") }, placeholder = { Text("M${index + 1}") },
                                                singleLine = true, modifier = Modifier.weight(1f))
                                        }
                                        OutlinedTextField(macroTexts[index], { macroTexts[index] = sanitizeCwMacroTemplate(it) },
                                            label = { Text("CW message") },
                                            trailingIcon = { Text("${macroTexts[index].length}/$CW_MACRO_TEMPLATE_MAX", color = Muted,
                                                style = MaterialTheme.typography.labelSmall) },
                                            singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CQ repeat"); Slider(repeatSeconds.toFloat(), { repeatSeconds = it.toInt() },
                            valueRange = CQ_REPEAT_MIN_SECONDS.toFloat()..CQ_REPEAT_MAX_SECONDS.toFloat(), steps = 3,
                            modifier = Modifier.weight(1f)); Text("$repeatSeconds s")
                        FilterChip(app.cwMacrosArmed, { app.updateCwMacrosArmed(!app.cwMacrosArmed) }, { Text(if (app.cwMacrosArmed) "CW ARMED" else "CW SAFE") })
                    }
                    Button({ app.saveLocalSettings(stationCall, stationName, stationGrid, repeatSeconds, macroLabels, macroTexts) }) { Text("SAVE CW MACROS") }
                } else {
                    Text("Six private, device-local WAV slots · recordings are not included in JSON settings recovery.", color = Hold)
                    repeat(VOICE_MACRO_COUNT) { index ->
                        val slot = voiceStore.slots.getOrNull(index) ?: VoiceMacroSlot(index, voiceLabels[index], false)
                        val recording = (voiceAudio.state as? VoiceAudioState.Recording)?.slot == index
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Raised)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).heightIn(min = 58.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${index + 1}", color = if (slot.exists) Hold else Muted, fontWeight = FontWeight.Black,
                                    modifier = Modifier.width(18.dp))
                                OutlinedTextField(voiceLabels[index], { voiceLabels[index] = sanitizeVoiceMacroLabel(it, index) },
                                    label = { Text("Voice label") }, singleLine = true, modifier = Modifier.width(164.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    VoiceWaveform(slot.waveform, Modifier.fillMaxWidth().height(30.dp))
                                    if (recording) LinearProgressIndicator({ voiceAudio.level }, Modifier.fillMaxWidth().height(4.dp), color = Danger)
                                }
                                Text(if (slot.exists) "%.1fs".format(slot.durationMillis / 1_000f) else "EMPTY", color = Muted,
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(54.dp))
                                if (recording) Button(voiceAudio::stopCurrent, modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Danger), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("STOP") }
                                else OutlinedButton({ pendingRecordSlot = index; recordVoicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                                    enabled = !voiceTx.isBusy && voiceAudio.state is VoiceAudioState.Idle,
                                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("RECORD") }
                                OutlinedButton({ voiceAudio.preview(index) }, enabled = slot.exists && !voiceTx.isBusy && voiceAudio.state is VoiceAudioState.Idle,
                                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("PREVIEW") }
                                OutlinedButton({ pendingImportSlot = index; importVoice.launch(arrayOf("audio/wav", "audio/x-wav", "application/octet-stream")) },
                                    enabled = !voiceTx.isBusy && voiceAudio.state is VoiceAudioState.Idle,
                                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("IMPORT") }
                                OutlinedButton({ pendingDeleteSlot = index }, enabled = slot.exists && !voiceTx.isBusy,
                                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("DELETE") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ app.saveVoiceMacroLabels(voiceLabels); voiceStore.refresh(); systemMessage = "Voice macro labels saved" }) { Text("SAVE VOICE LABELS") }
                        Text(voiceAudio.status, color = if (voiceAudio.state is VoiceAudioState.Idle) Muted else Hold)
                    }
                }
                KeyerSettingsUi(keyerProfiles, repeatCq, voiceAudio::previewPlan, { keyer.stop(); voiceTx.forceRx() })
            }
        }
        if (section == SettingsSection.ALERTS) SettingsCard("FIELD / DISPLAY / ALERTS") {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { FieldProfile.entries.forEach { value -> FilterChip(profile == value, {
                app.setProfile(value); profile = value
                val selected = app.alertDisplayProfile(value)
                brightness = selected.brightness.toFloat(); autoDim = selected.autoDim
                tones = selected.audibleTones; quiet = selected.quietNonCritical
            }, { Text(value.name) }) }; TextButton({ showAlertProfileHelp = true }) { Text("HELP") } }
            Text("${profile.name} · ${brightness.toInt()}% · auto dim ${if (autoDim) "on" else "off"} · tones ${if (tones) "on" else "off"} · quiet ${if (quiet) "on" else "off"}", color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Brightness", modifier = Modifier.width(90.dp)); Slider(brightness, { brightness = it }, valueRange = 10f..100f, modifier = Modifier.weight(1f)); Text("${brightness.toInt()}%") }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val screenOptions = listOf(
                    Triple("Auto dim", "Reduce display brightness using the active display policy", autoDim),
                    Triple("Audible tones", "Allow supported non-transmit alert tones", tones),
                    Triple("Quiet non-critical alerts", "Suppress non-critical alert presentation", quiet),
                )
                val columns = when { maxWidth >= 1_000.dp -> 3; maxWidth >= 620.dp -> 2; else -> 1 }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { screenOptions.withIndex().toList().chunked(columns).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { rowItems.forEach { indexed ->
                    val index = indexed.index; val item = indexed.value
                    Card(Modifier.weight(1f)) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(item.first, fontWeight = FontWeight.Bold)
                        Text(item.second, color = Muted, style = MaterialTheme.typography.bodySmall)
                        Switch(item.third, { enabled -> when (index) { 0 -> autoDim = enabled; 1 -> tones = enabled; else -> quiet = enabled } })
                    } }
                    }; repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) } }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("NONE", "POTA", "SOTA", "WWFF").forEach { value -> FilterChip(program == value, { program = value }, { Text(value) }) } }
            if (program != "NONE") OutlinedTextField(activation, { activation = it.uppercase() }, label = { Text("Activation reference") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved automatically", color = Healthy, modifier = Modifier.align(Alignment.CenterVertically))
                OutlinedButton({ systemMessage = "Non-critical alerts snoozed for 15 minutes" }) { Text("SNOOZE 15") }
                OutlinedButton({ systemMessage = "Non-critical alerts snoozed for 60 minutes" }) { Text("SNOOZE 60") }
            }
            HorizontalDivider()
            Text("NEURAL DX WATCHER", color = Amber, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(dxNotifications, { dxNotifications = !dxNotifications }, { Text("ANDROID ALERTS") })
                FilterChip(briefingDxMode, { briefingDxMode = !briefingDxMode }, { Text("BRIEFING DX MODE") })
                Text("Watchlist, new DXCC and 6m opening alerts use a 15-minute cooldown.", color = Muted)
            }
            OutlinedTextField(ntfyUrl, { ntfyUrl = it }, label = { Text("Optional ntfy HTTPS topic URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ntfyToken, { ntfyToken = it }, label = { Text("Optional ntfy bearer token") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(perplexityKey, { perplexityKey = it }, label = { Text("Optional Perplexity API key · AI Insight") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    neuralDx.saveSettings(dxNotifications, ntfyUrl, ntfyToken, perplexityKey, briefingDxMode)
                    if (dxNotifications && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    systemMessage = "Neural DX alert and insight settings saved"
                }) { Text("SAVE NEURAL DX") }
                OutlinedButton(neuralDx::testNtfy, enabled = ntfyUrl.startsWith("https://")) { Text("TEST NTFY") }
            }
        }
        if (section == SettingsSection.RADIO) SettingsCard("TRANSMIT SAFETY & CAT") {
            Text("ATU/TX, CW and voice macro arms are session-only. Voice also clears on exact mode, route, focus, lifecycle, CAT, USB or audio failure.", color = Hold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                @Composable fun SafetyActions() {
                    FilterChip(app.transmitArmed, { app.updateTransmitArmed(!app.transmitArmed) }, { Text(if (app.transmitArmed) "ATU / TX ARMED" else "ATU / TX SAFE") })
                    FilterChip(app.cwMacrosArmed, { app.updateCwMacrosArmed(!app.cwMacrosArmed) }, { Text(if (app.cwMacrosArmed) "CW MACROS ARMED" else "CW MACROS SAFE") })
                    FilterChip(app.voiceMacrosArmed, {}, { Text(if (app.voiceMacrosArmed) "VOICE MACROS ARMED" else "VOICE MACROS SAFE") }, enabled = false)
                    Button({ voiceTx.forceRx(); direct("RX;"); app.disarmAll() }, enabled = state.connected,
                        colors = ButtonDefaults.buttonColors(containerColor = Healthy, contentColor = Chassis)) { Text("FORCE RX & DISARM") }
                }
                if (maxWidth >= 720.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) { SafetyActions() }
                else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { SafetyActions() }
            }
            HorizontalDivider()
            Text("CAT ADAPTER", color = Amber, fontWeight = FontWeight.Bold)
            if (transport.candidates.isEmpty()) Text("No supported serial adapter detected", color = Muted)
            else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    @Composable fun AdapterCard(candidate: SerialDeviceDescriptor, modifier: Modifier) {
                        val chosen = candidate.sessionKey == pendingCatKey
                        Card(
                            modifier.clickable {
                                pendingCatKey = candidate.sessionKey
                                catSelectionDirty = candidate.sessionKey != transport.selected?.sessionKey
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (chosen) Amber else MaterialTheme.colorScheme.outline),
                            colors = CardDefaults.cardColors(containerColor = if (chosen) Amber.copy(alpha = .10f) else Raised),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RadioButton(chosen, {
                                    pendingCatKey = candidate.sessionKey
                                    catSelectionDirty = candidate.sessionKey != transport.selected?.sessionKey
                                })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(candidate.displayName, color = Ink, fontWeight = FontWeight.Bold)
                                    Text(candidate.identityLine, color = Muted, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(candidate.routeLine, color = if (chosen) Hold else Muted,
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (candidate.sessionKey == transport.selected?.sessionKey)
                                    Text("SAVED", color = Healthy, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (maxWidth >= 700.dp && transport.candidates.size > 1) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            transport.candidates.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { candidate -> AdapterCard(candidate, Modifier.weight(1f)) }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        transport.candidates.forEach { candidate -> AdapterCard(candidate, Modifier.fillMaxWidth()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val key = pendingCatKey ?: return@Button
                    settingsScope.launch {
                        voiceTx.stop("CAT adapter selection changed"); app.disarmAll()
                        if (transport.selectCandidate(key)) {
                            pendingCatKey = transport.selected?.sessionKey
                            catSelectionDirty = false
                            systemMessage = "CAT adapter selection saved · reconnecting"
                            reconnect()
                        } else {
                            transport.refreshCandidates()
                            systemMessage = "CAT adapter changed or detached before it could be saved · rescan and select again"
                        }
                    }
                }, enabled = catSelectionDirty && pendingCatKey != null) { Text("SAVE CAT ADAPTER") }
                OutlinedButton({ transport.refreshCandidates() }) { Text("RESCAN CAT") }
                Text(if (catSelectionDirty) "Unsaved CAT selection" else if (transport.selected != null) "Saved · ${transport.controlLineStatus}" else transport.controlLineStatus,
                    color = if (catSelectionDirty) Hold else Muted, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        if (section == SettingsSection.CLUSTER) SettingsCard("DX CLUSTER") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wideCluster = maxWidth >= 700.dp
                if (wideCluster) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it }, label = { Text("Primary host") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(callsign, { callsign = it.uppercase() }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(fallbackHost, { fallbackHost = it }, label = { Text("Fallback 1") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(fallbackPort, { fallbackPort = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(fallback2Host, { fallback2Host = it }, label = { Text("Fallback 2") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(fallback2Port, { fallback2Port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Primary host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(callsign, { callsign = it.uppercase() }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(fallbackHost, { fallbackHost = it }, label = { Text("Fallback 1 host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fallbackPort, { fallbackPort = it.filter(Char::isDigit) }, label = { Text("Fallback 1 port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fallback2Host, { fallback2Host = it }, label = { Text("Fallback 2 host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fallback2Port, { fallback2Port = it.filter(Char::isDigit) }, label = { Text("Fallback 2 port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(watch, { watch = it.uppercase() }, label = { Text("Priority calls · up to 32") }, modifier = Modifier.fillMaxWidth())
            Text("This list only highlights and alerts matching calls; it never limits incoming cluster spots. The Radio spots window shows 50 spots per page.", color = Muted)
            val clusterState = features.clusterConnection.state
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (clusterState) {
                    ClusterConnectionState.CONNECTING, ClusterConnectionState.RETRYING -> {
                        Button({}, enabled = false) { Text("Connecting…") }
                        OutlinedButton(features::disconnectCluster) { Text("Cancel") }
                    }
                    ClusterConnectionState.CONNECTED -> {
                        Text("CONNECTED", color = Color(0xFF52CB82), fontWeight = FontWeight.Black)
                        OutlinedButton(features::disconnectCluster, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE45B5B))) {
                            Text("Disconnect")
                        }
                    }
                    else -> Button({ features.setWatchlist(watch); features.connectCluster(host, port.toIntOrNull() ?: 7300, callsign,
                        fallbackHost, fallbackPort.toIntOrNull() ?: 7300, fallback2Host, fallback2Port.toIntOrNull() ?: 7300) },
                        enabled = callsign.isNotBlank()) { Text("Connect") }
                }
                OutlinedButton(features::refreshSolar) { Text("Refresh NOAA") }
            }
            if (clusterState == ClusterConnectionState.CONNECTED) {
                val nowEpoch = Instant.now().epochSecond
                Text("${features.clusterConnection.activeEndpoint} · connected ${clusterAge((nowEpoch - features.clusterConnection.connectedSinceEpoch).coerceAtLeast(0))} · last line ${features.clusterDiagnostics.lastLineEpoch.takeIf { it > 0 }?.let { clusterAge(nowEpoch - it) } ?: "never"} ago",
                    color = Muted)
                Text("${features.clusterDiagnostics.acceptedSpots} accepted spots · ${features.clusterDiagnostics.receivedLines} lines · ${features.clusterDiagnostics.rejectedLines} unrecognised",
                    color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(10, 20, 50, 100, 200).forEach { count ->
                        FilterChip(historyCount == count, { historyCount = count }, { Text(count.toString()) })
                    }
                    Button({ features.requestClusterHistory(historyCount) }, enabled = !features.clusterDiagnostics.historyPending) { Text("SH/DX") }
                }
                Text(features.clusterDiagnostics.historyStatus, color = Muted)
            }
            Text(features.clusterStatus, color = if (clusterState == ClusterConnectionState.ERROR) MaterialTheme.colorScheme.error else Muted)
            features.clusterConnection.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Cluster fields are saved automatically; Connect only controls the live connection.", color = Muted)
        }
        if (section == SettingsSection.LOG) SettingsCard("LOCAL LOG & WAVELOG") {
            Button(openSync, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.CloudSync, null); Spacer(Modifier.width(7.dp)); Text("OPEN LOG SERVICES · SYNC HUB")
            }
            Text("LOG DESTINATION", color = Amber, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(wavelog.logMode == LogMode.LOCAL, { wavelog.updateLogMode(LogMode.LOCAL) }, { Text("LOCAL ADIF") })
                FilterChip(wavelog.logMode == LogMode.WAVELOG, { wavelog.updateLogMode(LogMode.WAVELOG) }, { Text("WAVELOG · TWO-WAY") })
            }
            Text(if (wavelog.logMode == LogMode.LOCAL) "QSOs stay in the tablet database and export as ADIF."
                else "Every QSO is saved on the tablet first, queued offline, then uploaded and remote changes are downloaded when connectivity returns.", color = Muted)
            if (wavelog.logMode == LogMode.LOCAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ importAdif.launch(arrayOf("application/x-adif", "text/plain", "*/*")) }) { Text("IMPORT ADIF") }
                    OutlinedButton({ exportAdif.launch("shackcq-local.adi") }) { Text("EXPORT ADIF") }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wideLog = maxWidth >= 760.dp
                    if (wideLog) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(wavelog.baseURL, wavelog::updateBaseURL, label = { Text("HTTPS base URL") },
                            singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(wavelog.apiKey, wavelog::updateApiKey, label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            modifier = Modifier.weight(1.25f))
                        if (wavelog.stations.isEmpty()) OutlinedTextField(wavelog.stationId, wavelog::setStation,
                            label = { Text("Station ID") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        else ChoiceField("Station", wavelog.selectedStation?.label.orEmpty(),
                            wavelog.stations.map { it.id to it.label }, wavelog.stationId, wavelog::setStation,
                            Modifier.weight(1.45f), compact = true)
                    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(wavelog.baseURL, wavelog::updateBaseURL, label = { Text("HTTPS base URL") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(wavelog.apiKey, wavelog::updateApiKey, label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (wavelog.stations.isEmpty()) OutlinedTextField(wavelog.stationId, wavelog::setStation,
                            label = { Text("Station ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        else ChoiceField("Station", wavelog.selectedStation?.label.orEmpty(),
                            wavelog.stations.map { it.id to it.label }, wavelog.stationId, wavelog::setStation,
                            Modifier.fillMaxWidth(), compact = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CredentialState("WAVELOG API KEY", wavelog.apiKey.isNotBlank(), Modifier.testTag("wavelog-api-key-state"))
                    CredentialState("WAVELOG STATION", wavelog.stationId.isNotBlank(), Modifier.testTag("wavelog-station-state"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ wavelog.loadStations() }) { Text("Load stations") }; Button({ wavelog.syncQueue() }) { Text("Sync queue") }; Button({ wavelog.fullSync() }) { Text("Full log") } }
                Text(wavelog.status, color = Muted)
                Text("Wavelog URL, encrypted API key, selected station, and station list are retained across app updates.", color = Muted)
            }
            Text("QRZ.COM / HAMQTH ENRICHMENT", color = Amber, fontWeight = FontWeight.Bold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wideCallbook = maxWidth >= 900.dp
                @Composable fun QrzFields(modifier: Modifier) {
                    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(qrzEnabled, { qrzEnabled = !qrzEnabled }, { Text("QRZ.COM") })
                        OutlinedTextField(qrzUser, { qrzUser = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(qrzPassword, { qrzPassword = it }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                    }
                }
                @Composable fun HamQthFields(modifier: Modifier) {
                    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(hamQthEnabled, { hamQthEnabled = !hamQthEnabled }, { Text("HAMQTH") })
                        OutlinedTextField(hamQthUser, { hamQthUser = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(hamQthPassword, { hamQthPassword = it }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                    }
                }
                if (wideCallbook) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    QrzFields(Modifier.weight(1f)); HamQthFields(Modifier.weight(1f))
                    Button({ callbook.configureQrz(qrzEnabled, qrzUser, qrzPassword)
                        callbook.configureHamQth(hamQthEnabled, hamQthUser, hamQthPassword) }) { Text("SAVE") }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrzFields(Modifier.fillMaxWidth()); HamQthFields(Modifier.fillMaxWidth())
                    Button({ callbook.configureQrz(qrzEnabled, qrzUser, qrzPassword)
                        callbook.configureHamQth(hamQthEnabled, hamQthUser, hamQthPassword) }) { Text("SAVE") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CredentialState("QRZ PASSWORD", qrzPassword.isNotBlank(), Modifier.testTag("qrz-password-state"))
                if (hamQthEnabled || hamQthUser.isNotBlank() || hamQthPassword.isNotBlank())
                    CredentialState("HAMQTH PASSWORD", hamQthPassword.isNotBlank(), Modifier.testTag("hamqth-password-state"))
            }
            Text("Automatic lookup order: QRZ.COM → HamQTH → CTY.DAT. Email-style QRZ accounts use the configured station callsign for XML access; CTY.DAT supplements missing entity and zone fields.", color = Muted)
            Text("Callbook settings are saved automatically. Blank password fields keep the previously saved encrypted password.", color = Muted)
            Text("SAVED means a credential is present in encrypted app-private storage; use the test buttons in Diag to verify that it is valid.", color = Muted)
        }
        if (section == SettingsSection.AUDIO) SettingsCard("USB AUDIO ROUTES") {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-audio-layout")) {
                val expandedAudioLayout = maxWidth >= 700.dp
                @Composable fun ReceivePanel(modifier: Modifier) {
                    Card(modifier, colors = CardDefaults.cardColors(containerColor = Raised)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("INPUT & MONITOR", color = Amber, fontWeight = FontWeight.Bold)
                            AudioCard(audio)
                            Text("RX MONITOR INPUT", color = Amber, fontWeight = FontWeight.Bold)
                            if (audio.inputCandidates.isEmpty()) Text("No eligible USB input", color = Muted)
                            else ChoiceField("USB input", audio.selectedRx?.label ?: "Selection required",
                                audio.inputCandidates.map { it.sessionId.toString() to it.label }, audio.selectedRx?.sessionId?.toString().orEmpty(), {
                                voiceTx.stop("RX audio route changed"); app.updateVoiceMacrosArmed(false)
                                panadapter.stop("RX audio route selection changed"); audio.selectRxInput(it.toInt())
                            }, compact = true)
                            OutlinedButton(audio::refreshDevices, modifier = Modifier.heightIn(min = 44.dp)) { Text("RESCAN DEVICES") }
                            Text(audio.routeStatus, color = Muted)
                        }
                    }
                }
                @Composable fun CapturePanel(modifier: Modifier) {
                    Card(modifier, colors = CardDefaults.cardColors(containerColor = Raised)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("CAPTURE & VOICE TX", color = Amber, fontWeight = FontWeight.Bold)
                            Text("VOICE MACRO TX OUTPUT", color = Amber, fontWeight = FontWeight.Bold)
                            if (audio.txOutputCandidates.isEmpty()) Text("No eligible USB output", color = Muted)
                            else ChoiceField("DigiRig USB output", audio.selectedTx?.label ?: "Selection required",
                                audio.txOutputCandidates.map { it.sessionId.toString() to it.label }, audio.selectedTx?.sessionId?.toString().orEmpty(), {
                                voiceTx.stop("Voice TX route changed"); app.updateVoiceMacrosArmed(false); audio.selectTxOutput(it.toInt())
                            }, compact = true)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("RECORD  Tablet mic", color = Muted, modifier = Modifier.weight(1f))
                                Text("PREVIEW  Tablet speaker", color = Muted, modifier = Modifier.weight(1f))
                            }
                            SliderLine("VOICE MACRO TX LEVEL", app.voiceTxLevel, 0.02f..1f, app::updateVoiceTxLevel, {}, "${(app.voiceTxLevel * 100).toInt()}%")
                            Text("Controls PCM sent to DigiRig, not RF power. Start into a dummy load at minimum safe RF power and adjust for clean ALC.", color = Hold)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(openEq, modifier = Modifier.heightIn(min = 44.dp)) { Text("OPEN EQ STUDIO") }
                        Text("EQ capture uses the selected USB input or a clearly labelled built-in reference mic.", color = Muted,
                            modifier = Modifier.weight(1f))
                    }
                    if (expandedAudioLayout) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReceivePanel(Modifier.weight(1f))
                        CapturePanel(Modifier.weight(1f))
                    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReceivePanel(Modifier.fillMaxWidth())
                        CapturePanel(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (section == SettingsSection.HEALTH) SettingsCard("SYSTEM HEALTH") {
            SdrHealthPanel(tciRuntime, tciTransmit, tciRxAudio, panadapter, scanner, sdrOperationalV2, sdrWorkbenchV4, localReceivers, rfObservations, announcements)
            val health = buildSystemHealthSnapshot(context, operatingContext, stability, wavelog.status, wavelog.pendingCount,
                syncHub.records.count { it.state !in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED) },
                features.clusterStatus, neuralDx.status, groupsIo.status, groupsIo.cacheStats.messages, groupsIo.homeSummary.needsAttention,
                digi.mode.name, digi.rxActive, digi.status, keyer.snapshot(), contestRuntime.snapshot(), chaserRuntime.snapshot)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthTile("UI", "RUNNING", true, Modifier.weight(1f)); HealthTile("CAT / USB", if (state.connected) "LIVE" else "OFFLINE", state.connected, Modifier.weight(1f))
                HealthTile("NETWORK / DX", features.clusterStatus, features.liveSpots.isNotEmpty(), Modifier.weight(1f)); HealthTile("WAVELOG", wavelog.status, wavelog.pendingCount == 0, Modifier.weight(1f))
            }
            @Composable fun HealthCard(card: SystemHealthCard, modifier: Modifier) {
                Card(modifier, colors = CardDefaults.cardColors(containerColor = Raised)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${card.title.uppercase()} · ${card.state}", color = if (card.state == HealthState.HEALTHY) Healthy else Hold, fontWeight = FontWeight.Bold)
                        Text(card.summary, color = Muted)
                        if (card.counts.isNotEmpty()) Text(card.counts.entries.joinToString(" · ") { "${it.key} ${it.value}" }, color = Muted)
                        Text(card.safeActions.joinToString(" · "), color = Amber)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-health-grid")) {
                if (maxWidth >= 700.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    health.cards.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { card -> HealthCard(card, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    } }
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    health.cards.forEach { card -> HealthCard(card, Modifier.fillMaxWidth()) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) {
                    Text("RADIO PLATFORM · ${app.selectedRadioProfile.name}", color = if (state.connected) Healthy else Hold, fontWeight = FontWeight.Bold)
                    Text("${app.selectedRadioProfile.backendKind} · ${if (state.connected) "connected" else "disconnected"} · profile selection never connects", color = Muted)
                } }
                Card(Modifier.weight(1f)) { Column(Modifier.padding(10.dp)) {
                    Text("ROTATOR PLATFORM · ${if (rotator.state?.connected == true) "CONNECTED" else "DISCONNECTED"}", color = if (rotator.state?.connected == true) Healthy else Hold, fontWeight = FontWeight.Bold)
                    Text("${rotator.profiles.size} profiles · automation ${if (rotator.automation.armed) "ARMED" else "DISARMED"} · no movement from Settings", color = Muted)
                } }
            }
            Text("CAT · $detail", color = Muted); Text("AUDIO IN · ${audio.inputName}", color = Muted); Text("AUDIO OUT · ${audio.outputName}", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ systemMessage = app.backupNow() }) { Text("BACKUP NOW") }
                OutlinedButton({ systemMessage = app.verifyBackup() }) { Text("VERIFY BACKUP") }
                OutlinedButton({ systemMessage = app.backupNow(); if (systemMessage.startsWith("Backup")) exportRecovery.launch("shackcq-recovery.json") }) { Text("EXPORT RECOVERY") }
                OutlinedButton({ openRecovery.launch(arrayOf("application/json", "text/plain")) }) { Text("RESTORE REVIEW") }
                OutlinedButton({
                    supportBundleBytes = SanitizedSupportBundle.build(health,
                        mapOf("Wavelog" to "3.1.0@af325614", "OpenHamClock" to "v26.5.0@cc2415e7", "Nexus reviewed" to "1.7.5@57d11fd"), emptyMap())
                    exportSupport.launch("shackcq-sanitized-support.zip")
                }) { Text("EXPORT SUPPORT") }
                OutlinedButton(audio::refreshDevices) { Text("RESCAN AUDIO") }
            }
            Text(systemMessage, color = Muted)
        }
        if (section == SettingsSection.DIAG) SettingsCard("COUNTRY DATA") {
            Column(Modifier.testTag("settings-default-cty"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CtyUpdatePanel(cty)
            }
        }
        if (section == SettingsSection.DIAG) SettingsCard("DATABASE & STABILITY") {
            val snapshot = stability
            if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                val health = snapshot.projection
                val visibleProjectionRows = if (health.state == ProjectionState.READY) health.projectionRows else health.processedRows
                Text("DB · ${"%.1f".format(snapshot.databaseBytes / 1_048_576.0)} MiB", color = Muted)
                Text("QSO · ${health.canonicalRows} canonical · ${health.projectionRows} projected · ${health.referenceRows} references", color = Muted)
                Text("PROJECTION · ${health.state} · $visibleProjectionRows/${health.canonicalRows}", color = if (health.state == ProjectionState.READY) Healthy else Hold)
                if (health.lastError.isNotBlank()) Text("LAST PROJECTION ERROR · ${health.lastError.take(160)}", color = Danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ settingsScope.launch { withContext(Dispatchers.IO) { database.verifyProjection() }; systemMessage = "Projection verified"; refreshStability() } }) { Text("VERIFY") }
                    OutlinedButton({ settingsScope.launch { systemMessage = "Projection repair running…"; val repaired = withContext(Dispatchers.IO) { database.repairProjectionFully() }; systemMessage = if (repaired.state == ProjectionState.READY) "Projection repair complete and verified" else "Projection repair incomplete · ${repaired.lastError}"; refreshStability() } }) { Text("REPAIR & VERIFY") }
                    OutlinedButton({ confirmProjectionRebuild = true }) { Text("REBUILD") }
                    OutlinedButton({ StabilityDiagnostics.clear(context); systemMessage = "Diagnostic history cleared"; refreshStability() }) { Text("CLEAR HISTORY") }
                }
                Text("SLOW QUERIES · category/hash/timing only", color = Amber, fontWeight = FontWeight.Bold)
                if (snapshot.slowQueries.isEmpty()) Text("No slow queries recorded", color = Muted)
                snapshot.slowQueries.takeLast(6).asReversed().forEach { row ->
                    Text("${row.category} · ${row.elapsedMs} ms · ${row.rowCount} rows · ${row.planLabel}${if (row.cancelled) " · CANCELLED" else ""}", color = Muted, fontFamily = FontFamily.Monospace)
                }
                Text("LAST CRASH · sanitised local summary", color = Amber, fontWeight = FontWeight.Bold)
                snapshot.crashes.lastOrNull()?.let { crash ->
                    Text("${crash.exceptionClass} · ${crash.projectionState.ifBlank { "state unavailable" }} · memory ${crash.freeMemoryCategory}", color = Muted, fontFamily = FontFamily.Monospace)
                    crash.frames.take(3).forEach { Text(it, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } ?: Text("No crash summary recorded", color = Muted)
                Text("Private bounded journal: no credentials, notes, comments, QSL messages, raw ADIF, SQL text, or provider payloads.", color = Muted)
            }
            Text(systemMessage, color = Muted)
        }
        if (section == SettingsSection.DIAG) SettingsCard("CAT DIAGNOSTICS") {
            Text("CAT · ${transport.selected?.label ?: "not selected"}", color = Muted)
            Text("RTS/DTR · ${transport.controlLineStatus}", color = Muted)
            Text("RX USB · ${audio.selectedRx?.label ?: "not selected"}", color = Muted)
            Text("VOICE TX USB · ${audio.selectedTx?.label ?: "not selected"}", color = Muted)
            Text("VOICE STATE · ${voiceTx.state} · ${voiceTx.status}", color = Muted)
            voiceTx.diagnostics.takeLast(8).forEach { Text(it, color = Muted, fontFamily = FontFamily.Monospace) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(wavelog::testConnection) { Text("TEST WAVELOG") }
                OutlinedButton({ callbook.configureQrz(qrzEnabled, qrzUser, qrzPassword)
                    callbook.configureHamQth(hamQthEnabled, hamQthUser, hamQthPassword); callbook.test() }) { Text("TEST QRZ / HAMQTH") }
                OutlinedButton(wavelog::loadStations) { Text("LOAD STATIONS") }
                if (BuildConfig.DEBUG) OutlinedButton(groupsIo::injectNewMessageAlertForDebug) { Text("INJECT GROUPS.IO ALERT") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(wavelog.ntpServer, wavelog::updateNtpServer, label = { Text("NTP server") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(wavelog::checkTime, modifier = Modifier.heightIn(min = 48.dp)) { Text("SYNC NTP TIME") }
            }
            Text(wavelog.status, color = Muted); Text(callbook.status, color = Muted); Text(wavelog.timeStatus, color = Muted)
            val command = raw.trim().let { if (it.endsWith(';')) it else "$it;" }
            Row { OutlinedTextField(raw, { raw = it.uppercase() }, label = { Text("Safe CAT command") }, modifier = Modifier.weight(1f))
                Button({ direct(command); raw = "" }, enabled = state.connected && NativeCore.classify(command) in 1..2) { Text("Send") } }
        }
        if (section == SettingsSection.INTEGRATIONS) SettingsCard("GROUPS.IO") {
            GroupsIoSettingsPanel(groupsIo, openGroupsIo)
        }
        if (section == SettingsSection.COLOURS) SettingsCard("SPOT STATUS COLOURS") {
            Text("Choose CS and DS colours independently. The same palette is used in live spots and the DX feed; text labels remain the source of truth.",
                color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(app::resetSpotStatusColours, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("RESET DEFAULTS")
                }
            }
        }
        if (section == SettingsSection.COLOURS) SettingsCard("CALL STATUS · CS") {
            Text("NC new call · NB new band · NM new mode · W worked · C confirmed", color = Muted)
            StatusColourSettingsGroup(app, SPOT_STATUS_CS, spotCallStatusOptions)
        }
        if (section == SettingsSection.COLOURS) SettingsCard("DXCC STATUS · DS") {
            Text("ATNO new entity · W/NB worked on another band · C/NB confirmed on another band · W worked · C confirmed",
                color = Muted)
            StatusColourSettingsGroup(app, SPOT_STATUS_DS, spotDxccStatusOptions)
        }
        if (section == SettingsSection.ABOUT) SettingsCard("ABOUT SHACKCQ") {
            Text("SHACKCQ", color = Amber, fontWeight = FontWeight.Black)
            Text("ShackCQ is an original, GPL-3.0-only integrated application combining radio control, logging, intelligence, Digi, contesting, portable operations, maps, rotators and connected services into one coherent shack application. Compatible open-source components are incorporated only where recorded below; behavioural references contributed ideas, not copied source or artwork.", color = Muted)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val provenanceColumns = when { maxWidth >= 1_050.dp -> 3; maxWidth >= 680.dp -> 2; else -> 1 }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvenanceClass.entries.toList().chunked(provenanceColumns).forEach { classificationRow ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    classificationRow.forEach { classification -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(when (classification) {
                    ProvenanceClass.INCORPORATED -> "INCORPORATED / ADAPTED SOFTWARE"
                    ProvenanceClass.BEHAVIOURAL_REFERENCE -> "BEHAVIOURAL INSPIRATION · NO COPIED SOURCE"
                    ProvenanceClass.DATA_SERVICE -> "DATA AND SERVICE PROVIDERS"
                }, color = Amber, fontWeight = FontWeight.Bold)
                shackCQProvenance.filter { it.classification == classification }.forEach { entry ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.name, fontWeight = FontWeight.Bold)
                        Text("${entry.maintainers} · ${entry.purpose}", color = Muted)
                        Text("${entry.licence} · ${entry.pin}", color = Muted, fontFamily = FontFamily.Monospace)
                        TextButton({ inAppBrowser?.open(entry.sourceUrl) }) { Text("SOURCE / LICENCE") }
                    } }
                }
                    } }
                    repeat(provenanceColumns - classificationRow.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                }
            }
            Text("THANKS", color = Amber, fontWeight = FontWeight.Bold)
            Text("Thank you to the authors, maintainers, radio amateurs, testers, standards communities and data providers whose careful work makes interoperable amateur-radio software possible.", color = Muted)
            val buildSummary = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Build SHA ${BuildConfig.BUILD_SHA}\nBuild channel ${BuildConfig.BUILD_CHANNEL}\n" +
                "QSO schema 17 · projection contract 6\n" +
                "OpenHamClock stable 26.5.0 · d4a50eaaa61d · checked 2026-08-22"
            Surface(color = Raised, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BUILD & DATA CONTRACT", color = Amber, fontWeight = FontWeight.Bold)
                    Text(buildSummary, fontFamily = FontFamily.Monospace, color = Ink)
                    OutlinedButton({
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("ShackCQ build information", buildSummary)
                        )
                        systemMessage = "Build information copied"
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null); Spacer(Modifier.width(7.dp)); Text("COPY BUILD INFORMATION")
                    }
                }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-developer-information")) {
                @Composable fun DeveloperCopy(modifier: Modifier) {
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ABOUT THE DEVELOPER", color = Amber, fontWeight = FontWeight.Bold)
                        Text("Oliver Bross, OM0RX", color = Ink, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text("Amateur radio operator licensed since 2000 · JN88TQ", color = Muted)
                        Text("ShackCQ grew from real remote-station, DXing, CW, digital-mode, contesting, and award-chasing workflows.", color = Muted)
                        Text("stationpilot.app", color = Hold, fontWeight = FontWeight.Bold)
                    }
                }
                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.oliver_bross_om0rx), "Oliver Bross, OM0RX", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(124.dp).clip(RoundedCornerShape(12.dp)))
                    DeveloperCopy(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.oliver_bross_om0rx), "Oliver Bross, OM0RX", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)))
                    DeveloperCopy(Modifier.fillMaxWidth())
                }
            }
        }
        if (section == SettingsSection.CONTROLS) SettingsCard("MIDI / HID CONTROL SURFACES") {
            ControlSurfaceSettingsPanel(controlSurfaces)
        }
    }
    restorePayload?.let { payload -> AlertDialog(onDismissRequest = { restorePayload = null; recoveryPreview = null }, title = { Text("RESTORE REVIEW") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(app.reviewRecovery(payload))
            recoveryPreview?.sections?.forEach { row ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (row.name in selectedRecoverySections) selectedRecoverySections.remove(row.name) else selectedRecoverySections.add(row.name)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.name in selectedRecoverySections, { checked ->
                        if (checked) { if (row.name !in selectedRecoverySections) selectedRecoverySections.add(row.name) }
                        else selectedRecoverySections.remove(row.name)
                    })
                    Column(Modifier.weight(1f)) {
                        Text(row.name.replace('_', ' ').uppercase(), fontWeight = FontWeight.Bold)
                        Text("${row.changedKeys.size} changed · ${row.changedKeys.take(6).joinToString().ifBlank { "no changes" }}", color = Muted)
                        row.mappingTasks.forEach { Text(it, color = Hold) }
                    }
                }
            }
            Text("Credentials, QSO data, private messages/drafts, provider bodies, and TX/PTT/arming state are excluded. Restore is transactional and always clears transmit authority.", color = Muted)
        } },
        confirmButton = { Button({
            systemMessage = app.restoreRecovery(payload, selectedRecoverySections.toSet())
            restorePayload = null; recoveryPreview = null
        }, enabled = selectedRecoverySections.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("RESTORE SELECTED") } },
        dismissButton = { TextButton({ restorePayload = null; recoveryPreview = null }) { Text("CANCEL") } }) }
    pendingDeleteSlot?.let { slot -> AlertDialog(onDismissRequest = { pendingDeleteSlot = null }, title = { Text("Delete voice macro?") },
        text = { Text("Delete ${voiceStore.slots.getOrNull(slot)?.label ?: "M${slot + 1}"} from private tablet storage? This cannot be undone.") },
        confirmButton = { Button({ voiceAudio.delete(slot); pendingDeleteSlot = null }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("DELETE") } },
        dismissButton = { TextButton({ pendingDeleteSlot = null }) { Text("CANCEL") } }) }
}

private fun clusterAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> "${seconds / 60}m"
    else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
}

private fun InputStream.readBoundedVoiceWave(maximumBytes: Int = 32 * 1024 * 1024): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "WAV file exceeds the safe import limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable private fun MacroNumber(index: Int, configured: Boolean) {
    Surface(color = if (configured) Hold.copy(alpha = .18f) else Color(0xFF303638),
        shape = RoundedCornerShape(6.dp), modifier = Modifier.size(42.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("${index + 1}", color = if (configured) Hold else Muted, fontWeight = FontWeight.Black)
        }
    }
}

@Composable private fun VoiceWaveform(peaks: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF111519), RoundedCornerShape(4.dp))) {
        if (peaks.isEmpty()) {
            drawLine(Muted.copy(alpha = .4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        } else {
            val step = size.width / peaks.size
            peaks.forEachIndexed { index, peak ->
                val height = peak.coerceIn(0f, 1f) * size.height
                drawLine(Hold, Offset((index + .5f) * step, (size.height - height) / 2),
                    Offset((index + .5f) * step, (size.height + height) / 2), maxOf(1f, step * .55f))
            }
        }
    }
}

@Composable private fun CredentialState(label: String, configured: Boolean, modifier: Modifier = Modifier) {
    val color = if (configured) Healthy else Danger
    Surface(modifier, color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text("$label · ${if (configured) "SAVED · ENCRYPTED" else "NOT SAVED"}", color = color,
            fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable private fun CtyUpdatePanel(cty: CtyController) {
    val (stateLabel, stateColor) = when (cty.updateState) {
        CtyUpdateState.NOT_INSTALLED -> "NOT INSTALLED" to Danger
        CtyUpdateState.CHECKING -> "CHECKING" to Amber
        CtyUpdateState.CURRENT -> "UP TO DATE" to Healthy
        CtyUpdateState.AVAILABLE -> "UPDATE AVAILABLE" to Amber
        CtyUpdateState.UPDATING -> "INSTALLING" to Amber
        CtyUpdateState.FAILED -> "CHECK UNAVAILABLE" to Muted
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("CTY.DAT COUNTRY FILE", color = Amber, fontWeight = FontWeight.Bold)
            Text("DXCC, country, continent, CQ and ITU zone source", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Surface(color = stateColor.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (cty.isBusy) CircularProgressIndicator(Modifier.size(14.dp), color = stateColor, strokeWidth = 2.dp)
                Text(stateLabel, color = stateColor, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Text("INSTALLED", color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("${cty.installedVersion} · ${cty.prefixCount} prefixes", color = Ink, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(1f)) {
            Text("LATEST AVAILABLE", color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(cty.latestVersion, color = if (cty.updateAvailable) Amber else Ink, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(1f)) {
            Text("LAST AUTOMATIC CHECK", color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(cty.lastChecked, color = Ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(cty.updateMessage, color = if (cty.updateState == CtyUpdateState.FAILED) Amber else Muted,
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cty.updateAvailable) {
            Button(cty::update, enabled = !cty.isBusy) { Text("INSTALL UPDATE") }
        } else {
            OutlinedButton(cty::update, enabled = !cty.isBusy) { Text("UPDATE NOW") }
        }
        OutlinedButton(cty::checkForUpdates, enabled = !cty.isBusy) { Text("CHECK NOW") }
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Amber, fontWeight = FontWeight.Bold); content()
    } }
}

@Composable private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = if (maxWidth < 600.dp) 10.dp else 16.dp
        val spacing = if (maxHeight < 650.dp) 8.dp else 10.dp
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(spacing)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(spacing), content = content) }
        }
    }
}

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
    }
}
