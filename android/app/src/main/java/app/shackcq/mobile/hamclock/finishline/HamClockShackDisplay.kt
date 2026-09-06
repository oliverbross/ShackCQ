package app.shackcq.mobile.hamclock.finishline

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.os.Build
import android.content.pm.PackageManager
import android.view.WindowManager
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shackcq.mobile.*
import app.shackcq.mobile.hamclock.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

internal data class HamClockIdTimerState(
    val remainingSeconds: Long,
    val due: Boolean,
    val overdueSeconds: Long,
    val clockChanged: Boolean,
)

internal class HamClockIdTimerRuntime {
    private var lastWall = 0L
    private var lastElapsed = 0L

    fun snapshot(preference: HamClockIdReminderPreference, wallNow: Long, elapsedNow: Long): HamClockIdTimerState {
        val changed = lastWall > 0 && ((wallNow - lastWall) - (elapsedNow - lastElapsed) / 1_000).absoluteValue > 2
        lastWall = wallNow
        lastElapsed = elapsedNow
        if (!preference.enabled || !preference.running || preference.paused || preference.lastResetEpochSeconds <= 0) {
            return HamClockIdTimerState(preference.intervalMinutes * 60L, false, 0, changed)
        }
        val remaining = preference.lastResetEpochSeconds + preference.intervalMinutes * 60L - wallNow
        return HamClockIdTimerState(remaining.coerceAtLeast(0), remaining <= 0, (-remaining).coerceAtLeast(0), changed)
    }
}

@Composable
internal fun HamClockShackDisplay(
    stationCall: String,
    stationGrid: String,
    radio: RadioState,
    pathPrediction: HamClockPropagationSnapshot,
    nativeStatus: HamClockNativeStatus?,
    spaceWeather: HamClockSpaceWeatherSnapshot,
    solarMetrics: AndroidSolar,
    sunspotNumber: Float?,
    xray: HamClockXraySeries,
    aurora: HamClockAuroraSnapshot,
    solarImage: HamClockSolarImageSnapshot,
    contests: List<HamClockContest>,
    selectedContestId: String,
    contestQsos: List<HamClockContestQso>,
    profiles: List<HamClockNamedProfile>,
    satellitePositions: List<HamClockSatellitePosition>,
    satelliteTracks: List<HamClockSatelliteTrack>,
    settings: HamClockUserSettings,
    updateSettings: ((HamClockUserSettings) -> HamClockUserSettings) -> Unit,
    selectContest: (String) -> Unit,
    applyProfile: (String) -> Unit,
    refreshSolarImage: (HamClockSolarChannel, Boolean) -> Unit,
    openExactQso: (String) -> Unit,
    exit: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = context.activity()
    val display = settings.shackDisplay
    val palette = when (display.theme) {
        HamClockShackTheme.STANDARD_DARK -> ShackPalette(Color(0xff07131c), Color(0xff112432), Color(0xffd9edf5), Color(0xff53cce6), Color(0xfff0ad35))
        HamClockShackTheme.AMBER_SHACK -> ShackPalette(Color(0xff120c04), Color(0xff281a08), Color(0xffffd98a), Color(0xffffb43b), Color(0xffffd36a))
        HamClockShackTheme.RED_NIGHT -> ShackPalette(Color(0xff100303), Color(0xff260707), Color(0xffffa0a0), Color(0xffff5959), Color(0xffff7777))
    }
    DisposableEffect(activity, view, display.keepScreenOn, lifecycle) {
        fun apply(active: Boolean) {
            activity?.window?.let { window ->
                if (active && display.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, !active)
                WindowInsetsControllerCompat(window, view).let { controller ->
                    if (active) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        apply(true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_STOP -> apply(false); Lifecycle.Event.ON_START -> apply(true); else -> Unit }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); apply(false) }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var lastTouchElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(display.rotationEnabled, display.rotationSeconds, display.reducedMotion) {
        while (display.rotationEnabled && !display.reducedMotion) {
            delay(display.rotationSeconds * 1_000L)
            if (SystemClock.elapsedRealtime() - lastTouchElapsed >= display.rotationSeconds * 1_000L) page = (page + 1) % 4
        }
    }
    val timerRuntime = remember { HamClockIdTimerRuntime() }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var timer by remember { mutableStateOf(timerRuntime.snapshot(settings.idReminder, now, SystemClock.elapsedRealtime())) }
    var notifiedResetEpoch by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(settings.idReminder) {
        while (true) {
            now = Instant.now().epochSecond
            timer = timerRuntime.snapshot(settings.idReminder, now, SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }
    LaunchedEffect(timer.due, settings.idReminder.notificationEnabled, settings.idReminder.lastResetEpochSeconds) {
        if (timer.due && settings.idReminder.notificationEnabled &&
            notifiedResetEpoch != settings.idReminder.lastResetEpochSeconds) {
            postIdReminder(context)
            notifiedResetEpoch = settings.idReminder.lastResetEpochSeconds
        }
    }
    LaunchedEffect(timer.clockChanged) {
        if (timer.clockChanged && settings.idReminder.running) {
            updateSettings { it.copy(idReminder = it.idReminder.copy(lastResetEpochSeconds = Instant.now().epochSecond)) }
        }
    }
    Dialog(exit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(palette.background).padding(WindowInsets.safeDrawing.asPaddingValues())
            .clickable { lastTouchElapsed = SystemClock.elapsedRealtime() }) {
            Row(Modifier.fillMaxWidth().background(palette.panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("$stationCall · $stationGrid", color = palette.ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("UTC ${Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm:ss"))} · ${if (radio.connected) "CAT LIVE" else "CAT OFFLINE"} · ${if (radio.transmitting) "TX VERIFIED" else "RX"}",
                        color = palette.accent, fontFamily = FontFamily.Monospace)
                }
                IdTimerPill(timer, settings.idReminder, palette) { transform ->
                    updateSettings { it.copy(idReminder = transform(it.idReminder)) }
                }
                Button(exit, colors = ButtonDefaults.buttonColors(containerColor = palette.alert), modifier = Modifier.heightIn(min = 54.dp)) {
                    Text("EXIT", color = Color.Black, fontWeight = FontWeight.Black)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OVERVIEW", "PROPAGATION", "SPACE WEATHER", "OPERATIONS").forEachIndexed { index, label ->
                    FilterChip(page == index, { page = index; lastTouchElapsed = SystemClock.elapsedRealtime() }, { Text(label) })
                }
            }
            Box(Modifier.fillMaxSize().padding(10.dp)) {
                when (page) {
                    0 -> ShackOverview(pathPrediction, spaceWeather, aurora, satellitePositions, contestQsos, palette)
                    1 -> ShackPropagation(pathPrediction, nativeStatus, settings.propagation, palette) { value ->
                        updateSettings { it.copy(propagation = value) }
                    }
                    2 -> ShackSpaceWeather(spaceWeather, solarMetrics, sunspotNumber, xray, aurora, solarImage, palette, refreshSolarImage)
                    else -> ShackOperations(contests, selectedContestId, contestQsos, satellitePositions, satelliteTracks,
                        profiles, settings.shackDisplay, settings.satellites, palette, selectContest, applyProfile, openExactQso,
                        { value -> updateSettings { it.copy(shackDisplay = value) } },
                        { value -> updateSettings { it.copy(satellites = value) } })
                }
            }
        }
    }
}

private data class ShackPalette(val background: Color, val panel: Color, val ink: Color, val accent: Color, val alert: Color)

@Composable private fun IdTimerPill(state: HamClockIdTimerState, preference: HamClockIdReminderPreference,
    palette: ShackPalette, update: ((HamClockIdReminderPreference) -> HamClockIdReminderPreference) -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        Text(if (!preference.enabled) "ID REMINDER OFF" else if (state.due) "ID OVERDUE ${formatDuration(state.overdueSeconds)}" else "ID ${formatDuration(state.remainingSeconds)}",
            color = if (state.due) palette.alert else palette.accent, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton({ update { it.copy(enabled = true, running = true, paused = false, lastResetEpochSeconds = Instant.now().epochSecond) } }) { Text("START") }
            TextButton({ update { it.copy(enabled = true, running = true, paused = false, lastResetEpochSeconds = Instant.now().epochSecond) } }) { Text("ID SENT") }
            TextButton({ update { it.copy(paused = !it.paused) } }) { Text(if (preference.paused) "RESUME" else "PAUSE") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 15, 20, 30).forEach { minutes ->
                FilterChip(preference.intervalMinutes == minutes, { update { it.copy(intervalMinutes = minutes) } }, { Text("${minutes}m") })
            }
            FilterChip(preference.startOnVerifiedTx, { update { it.copy(startOnVerifiedTx = !it.startOnVerifiedTx) } }, { Text("START ON TX") })
            FilterChip(preference.notificationEnabled, { update { it.copy(notificationEnabled = !it.notificationEnabled) } }, { Text("NOTIFY") })
        }
    }
}

@Composable private fun ShackOverview(prediction: HamClockPropagationSnapshot, weather: HamClockSpaceWeatherSnapshot,
    aurora: HamClockAuroraSnapshot, satellites: List<HamClockSatellitePosition>, qsos: List<HamClockContestQso>, palette: ShackPalette) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShackCard("PROPAGATION", "${prediction.model.ifBlank { "Unavailable" }} · MUF ${prediction.mufMHz?.let { "%.1f MHz".format(it) } ?: "—"}", palette) {
                prediction.bands.take(10).forEach { Text("${it.band.padEnd(5)} ${it.reliability}% · ${it.snr} · ${it.status}", color = palette.ink, fontFamily = FontFamily.Monospace) }
            }
            ShackCard("SPACE WEATHER", weather.truth.name, palette) {
                Text("Wind ${weather.solarWindSpeedKmS?.let { "%.0f km/s".format(it) } ?: "—"} · Bz ${weather.imfBzNt?.let { "%.1f nT".format(it) } ?: "—"} · Proton ${weather.protonFluxPfu?.let { "%.1f pfu".format(it) } ?: "—"}", color = palette.ink)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShackCard("AURORA", "${aurora.truth} · ${aurora.cells.size} bounded cells", palette) {
                Text("Peak ${aurora.cells.maxOfOrNull { it.probability } ?: 0}% · forecast ${aurora.forecastAtEpoch}", color = palette.ink)
            }
            ShackCard("OPERATIONS", "Authoritative local owners", palette) {
                Text("Satellites ${satellites.size} · contest QSOs ${qsos.size}", color = palette.ink)
                satellites.take(8).forEach { Text("${it.name} · ${it.elevationDeg.toInt()}° · ${it.altitudeKm.toInt()} km", color = palette.ink) }
            }
        }
    }
}

@Composable private fun ShackPropagation(prediction: HamClockPropagationSnapshot, native: HamClockNativeStatus?,
    preference: HamClockPropagationPreference, palette: ShackPalette, update: (HamClockPropagationPreference) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Native ITU-R P.533", color = palette.accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("${native?.status ?: "NOT RUN"} · ${native?.errors?.joinToString().orEmpty()}", color = palette.alert)
        Text("The current-band fallback below is the bounded reviewed OpenHamClock REST response; it is not a 24-hour native P.533 matrix.", color = palette.ink)
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(palette.panel)) {
            val rows = prediction.bands.take(12)
            val width = size.width / rows.size.coerceAtLeast(1)
            rows.forEachIndexed { index, row ->
                val height = size.height * row.reliability.coerceIn(0, 100) / 100f
                drawRect(palette.accent, Offset(index * width + 3, size.height - height), androidx.compose.ui.geometry.Size(width - 6, height))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 50, 100, 500).forEach { watts -> FilterChip(preference.txPowerWatts == watts, { update(preference.copy(txPowerWatts = watts)) }, { Text("${watts}W") }) }
            listOf(80, 90, 95, 99).forEach { value -> FilterChip(preference.requiredReliability == value, { update(preference.copy(requiredReliability = value)) }, { Text("$value%") }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HamClockNoiseEnvironment.entries.forEach { value -> FilterChip(preference.noiseEnvironment == value, { update(preference.copy(noiseEnvironment = value)) }, { Text(value.name.replace('_', ' ')) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!preference.longPath, { update(preference.copy(longPath = false)) }, { Text("SHORT PATH") })
            FilterChip(preference.longPath, { update(preference.copy(longPath = true)) }, { Text("LONG PATH") })
            FilterChip(!preference.digital, { update(preference.copy(digital = false)) }, { Text("ANALOG") })
            FilterChip(preference.digital, { update(preference.copy(digital = true)) }, { Text("DIGITAL") })
            FilterChip(preference.coverageResolution == 288, { update(preference.copy(coverageResolution = 288)) }, { Text("GRID 288") })
            FilterChip(preference.coverageResolution == 720, { update(preference.copy(coverageResolution = 720)) }, { Text("GRID 720 MANUAL") })
        }
    }
}

@Composable private fun ShackSpaceWeather(weather: HamClockSpaceWeatherSnapshot, solarMetrics: AndroidSolar,
    sunspotNumber: Float?, xray: HamClockXraySeries, aurora: HamClockAuroraSnapshot,
    solar: HamClockSolarImageSnapshot, palette: ShackPalette, refresh: (HamClockSolarChannel, Boolean) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NOAA SPACE WEATHER", color = palette.accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("State ${weather.truth} · observed ${weather.observedAtEpoch} · fetched ${weather.fetchedAtEpoch}", color = palette.ink)
            Text("SFI ${if (solarMetrics.valid) "%.0f".format(solarMetrics.flux) else "—"} · SSN ${sunspotNumber?.let { "%.0f".format(it) } ?: "—"} · A ${if (solarMetrics.valid) "%.0f".format(solarMetrics.aIndex) else "—"} · Kp ${if (solarMetrics.valid) "%.1f".format(solarMetrics.kpIndex) else "—"}", color = palette.ink)
            Text("GOES X-ray ${xray.currentClass} · 24h peak ${xray.peakClass}", color = palette.ink)
            val cutoff = Instant.now().epochSecond - 24 * 60 * 60
            val chart = xray.points.filter { it.epoch >= cutoff }.takeLast(288)
            Canvas(Modifier.fillMaxWidth().height(110.dp).background(palette.panel)) {
                if (chart.size > 1) {
                    val minLog = -9.0; val maxLog = -3.0
                    chart.zipWithNext().forEachIndexed { index, pair ->
                        fun y(value: Double) = size.height * (1f - ((kotlin.math.log10(value.coerceAtLeast(1e-9)) - minLog) / (maxLog - minLog)).toFloat().coerceIn(0f, 1f))
                        drawLine(palette.accent, Offset(index * size.width / (chart.size - 1), y(pair.first.fluxWattsPerSquareMetre)),
                            Offset((index + 1) * size.width / (chart.size - 1), y(pair.second.fluxWattsPerSquareMetre)), 2f)
                    }
                }
            }
            Text("Solar wind ${weather.solarWindSpeedKmS?.let { "%.0f km/s".format(it) } ?: "—"}", color = palette.ink)
            Text("IMF Bz ${weather.imfBzNt?.let { "%.1f nT".format(it) } ?: "—"}", color = palette.ink)
            Text("Proton flux ${weather.protonFluxPfu?.let { "%.2f pfu".format(it) } ?: "—"} · ${weather.radiationState.ifBlank { "state —" }}", color = palette.ink)
            weather.alerts.forEach { Text(it, color = palette.alert) }
            Text("OVATION ${aurora.truth} · forecast ${aurora.forecastAtEpoch} · peak ${aurora.cells.maxOfOrNull { it.probability } ?: 0}%", color = palette.ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                HamClockSolarChannel.entries.forEach { channel -> FilterChip(solar.channel == channel, { refresh(channel, false) }, { Text(channel.name) }) }
                Button({ refresh(solar.channel, true) }) { Text("REFRESH") }
            }
            solar.bitmap?.let { Image(it.asImageBitmap(), solar.channel.title, Modifier.fillMaxWidth().weight(1f)) }
                ?: Box(Modifier.fillMaxWidth().weight(1f).background(palette.panel), contentAlignment = Alignment.Center) { Text(solar.error.ifBlank { "No last-good solar image" }, color = palette.ink) }
            Text("${solar.channel.source} · ${solar.truth} · ${solar.fetchedAtEpoch}", color = palette.ink)
        }
    }
}

@Composable private fun ShackOperations(contests: List<HamClockContest>, selected: String, qsos: List<HamClockContestQso>,
    satellites: List<HamClockSatellitePosition>, tracks: List<HamClockSatelliteTrack>, profiles: List<HamClockNamedProfile>,
    display: HamClockShackDisplayPreference,
    satellitePreference: HamClockSatellitePreference, palette: ShackPalette, selectContest: (String) -> Unit,
    applyProfile: (String) -> Unit, openQso: (String) -> Unit, updateDisplay: (HamClockShackDisplayPreference) -> Unit,
    updateSatellite: (HamClockSatellitePreference) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("CONTEST QSOS · bounded ${qsos.size}/200", color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
            contests.take(8).forEach { contest -> FilterChip(contest.id == selected, { selectContest(contest.id) }, { Text(contest.name) }) }
            qsos.forEach { qso -> Text("${qso.callsign} · ${qso.band} ${qso.mode} · ${qso.grid}${if (qso.confirmed) " · ✓" else ""}",
                color = palette.ink, modifier = Modifier.fillMaxWidth().clickable { openQso(qso.id) }.padding(5.dp)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("SATELLITE OPERATIONS", color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("${satellites.size} positions · ${tracks.size.coerceAtMost(4)} bounded tracks · local pinned SGP4", color = palette.ink)
            satellites.take(12).forEach { Text("${it.name} · ${it.elevationDeg.toInt()}° · age ${Instant.now().epochSecond - it.generatedAtEpoch}s", color = palette.ink) }
            Row {
                FilterChip(satellitePreference.showTracks, { updateSatellite(satellitePreference.copy(showTracks = !satellitePreference.showTracks)) }, { Text("TRACKS ≤4") })
                Spacer(Modifier.width(6.dp))
                FilterChip(satellitePreference.showFootprints, { updateSatellite(satellitePreference.copy(showFootprints = !satellitePreference.showFootprints)) }, { Text("FOOTPRINTS ≤4") })
            }
            Text("DISPLAY", color = palette.accent, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp))
            profiles.take(8).forEach { profile -> FilterChip(display.selectedProfileId == profile.id, {
                applyProfile(profile.id); updateDisplay(display.copy(selectedProfileId = profile.id))
            }, { Text("PROFILE ${profile.name}") }) }
            HamClockShackTheme.entries.forEach { theme -> FilterChip(display.theme == theme, { updateDisplay(display.copy(theme = theme)) }, { Text(theme.name) }) }
            Row { FilterChip(display.keepScreenOn, { updateDisplay(display.copy(keepScreenOn = !display.keepScreenOn)) }, { Text("KEEP SCREEN ON") }); Spacer(Modifier.width(6.dp)); FilterChip(display.rotationEnabled, { updateDisplay(display.copy(rotationEnabled = !display.rotationEnabled)) }, { Text("ROTATE") }) }
            Row { FilterChip(display.reducedMotion, { updateDisplay(display.copy(reducedMotion = !display.reducedMotion)) }, { Text("REDUCED MOTION") }); Spacer(Modifier.width(6.dp)); listOf(15, 30, 60, 120).forEach { seconds -> FilterChip(display.rotationSeconds == seconds, { updateDisplay(display.copy(rotationSeconds = seconds)) }, { Text("${seconds}s") }) } }
        }
    }
}

@Composable private fun ShackCard(title: String, status: String, palette: ShackPalette, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(palette.panel, RoundedCornerShape(8.dp)).border(1.dp, palette.accent, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text(title, color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(status, color = palette.ink, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp)); content()
    }
}

private fun formatDuration(seconds: Long) = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun postIdReminder(context: Context) {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = "hamclock_id_reminder"
    manager.createNotificationChannel(NotificationChannel(channel, "HamClock ID reminder", NotificationManager.IMPORTANCE_DEFAULT))
    runCatching { manager.notify(0x1d533, NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle("Station ID reminder")
        .setContentText("Identification interval is due. Reminder only; ShackCQ will not transmit.")
        .setAutoCancel(true).build()) }
}
private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
