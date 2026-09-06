// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ScannerMode { MEMORY, RANGE, FFT_SPAN }
enum class ScannerState { STOPPED, ARMED, TUNING, DWELLING, HOLDING, ERROR }
enum class ScannerResumePolicy { CARRIER_DROP, TIMED, MANUAL }

data class ScannerConfig(
    val mode: ScannerMode = ScannerMode.MEMORY,
    val startHz: Long = 14_000_000,
    val endHz: Long = 14_350_000,
    val stepHz: Long = 1_000,
    val radioMode: String = "USB",
    val filterHz: Int = 2_700,
    val thresholdDb: Float = -90f,
    val dwellMillis: Long = 1_500,
    val resumePolicy: ScannerResumePolicy = ScannerResumePolicy.CARRIER_DROP,
    val skipHz: Set<Long> = emptySet(),
) {
    fun validated() = copy(
        startHz = startHz.coerceIn(100_000, 10_500_000_000),
        endHz = endHz.coerceIn(startHz + 1, 10_500_000_000),
        stepHz = stepHz.coerceIn(10, 1_000_000),
        filterHz = filterHz.coerceIn(50, 100_000),
        thresholdDb = thresholdDb.coerceIn(-140f, 0f),
        dwellMillis = dwellMillis.coerceIn(100, 60_000),
        skipHz = skipHz.take(256).toSet(),
    )
}

data class ScannerSnapshot(
    val state: ScannerState = ScannerState.STOPPED,
    val mode: ScannerMode = ScannerMode.MEMORY,
    val currentHz: Long = 0,
    val signalDb: Float? = null,
    val candidateCount: Int = 0,
    val cycles: Long = 0,
    val stopReason: String = "Explicit Start required",
    val activeBankId: String? = null,
    val priorityChecks: Long = 0,
)

data class ScannerDwellEvent(
    val bankId: String?,
    val frequencyHz: Long,
    val mode: String,
    val peakDb: Float?,
    val dwellMillis: Long,
    val priority: Boolean,
)

class ReceiveOnlyScannerController(
    private val tuneReceive: suspend (Long, String, Int) -> Boolean,
    private val signalLevel: () -> Float? = { null },
    private val onDwell: (ScannerDwellEvent) -> Unit = {},
    private val orderEntries: (List<ScanMemory>, String?) -> List<ScanMemory> = { rows, _ -> rows },
    private val adaptiveDwell: (Long, Long, Boolean) -> Long = { _, minimum, _ -> minimum },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private var activeBankId: String? = null
    var config by mutableStateOf(ScannerConfig()); private set
    var snapshot by mutableStateOf(ScannerSnapshot()); private set

    fun updateConfig(value: ScannerConfig) {
        require(snapshot.state == ScannerState.STOPPED) { "Stop scanning before changing its operating context" }
        config = value.validated()
    }

    fun startMemory(memories: List<RadioPreset>) = start(memories.map { it.frequencyHz to it.mode })

    fun startEntries(memories: List<ScanMemory>, bankId: String? = null, priority: ScanMemory? = null) {
        activeBankId = bankId
        val ordered = orderEntries(memories.filter { it.scanEnabled }, bankId)
        start(ordered.map { it.frequencyHz to it.mode }, priority?.let { it.frequencyHz to it.mode })
    }

    fun startBank(bank: ScanBank) {
        val value = bank.validated()
        if (!value.enabled) return
        updateConfig(config.copy(thresholdDb = value.thresholdDb, dwellMillis = value.dwellMillis,
            resumePolicy = value.resumePolicy, filterHz = value.memories.firstOrNull()?.filterHz ?: config.filterHz))
        startEntries(value.memories, value.id, value.priority)
    }

    fun startRange() {
        val value = config.validated()
        val channels = generateSequence(value.startHz) { it + value.stepHz }
            .takeWhile { it <= value.endHz }.filterNot(value.skipHz::contains).take(10_000)
            .map { it to value.radioMode }.toList()
        start(channels)
    }

    fun startFft(trace: FloatArray, centerHz: Long, spanHz: Long) {
        val candidates = fftCandidates(trace, centerHz, spanHz, config.thresholdDb, 10_000)
            .filterNot(config.skipHz::contains).map { it to config.radioMode }
        start(candidates)
    }

    private fun start(channels: List<Pair<Long, String>>, priority: Pair<Long, String>? = null) {
        if (scanJob != null || channels.isEmpty()) {
            if (channels.isEmpty()) snapshot = snapshot.copy(state = ScannerState.ERROR, stopReason = "No receive-only scan candidates")
            return
        }
        snapshot = ScannerSnapshot(ScannerState.ARMED, config.mode, candidateCount = channels.size, stopReason = "", activeBankId = activeBankId)
        scanJob = scope.launch {
            var cycles = 0L
            var visited = 0L
            var priorityChecks = 0L
            while (isActive) {
                for ((frequency, mode) in channels) {
                    if (!isActive) break
                    val priorityDue = priority != null && visited > 0 && visited % 5L == 0L
                    val target = if (priorityDue) priority else frequency to mode
                    if (priorityDue) priorityChecks++
                    snapshot = snapshot.copy(state = ScannerState.TUNING, currentHz = target.first, cycles = cycles,
                        priorityChecks = priorityChecks)
                    if (!tuneReceive(target.first, target.second, config.filterHz)) {
                        stop("Receive tune unavailable")
                        return@launch
                    }
                    val peak = signalLevel()
                    snapshot = snapshot.copy(state = ScannerState.DWELLING, currentHz = target.first, signalDb = peak)
                    val dwell = adaptiveDwell(target.first, config.dwellMillis, priorityDue).coerceIn(config.dwellMillis, config.dwellMillis * 2)
                    delay(dwell)
                    onDwell(ScannerDwellEvent(activeBankId, target.first, target.second, peak, dwell, priorityDue))
                    visited++
                    if (config.resumePolicy == ScannerResumePolicy.MANUAL) {
                        snapshot = snapshot.copy(state = ScannerState.HOLDING, stopReason = "Manual resume selected")
                        return@launch
                    }
                }
                cycles++
            }
        }
    }

    fun stop(reason: String = "Stopped by operator") {
        scanJob?.cancel(); scanJob = null
        snapshot = snapshot.copy(state = ScannerState.STOPPED, stopReason = reason, activeBankId = null)
        activeBankId = null
    }

    fun onBackground() = stop("Background safety stop")
    fun onRadioDisconnect() = stop("Radio disconnected")
    fun onProfileChange() = stop("Radio profile changed")
    fun onManualTune() { if (snapshot.state != ScannerState.STOPPED) stop("Manual operator tune") }
    fun globalStop() = stop("Global Stop")
    override fun close() { stop("Controller closed"); scope.cancel() }
}

internal fun fftCandidates(trace: FloatArray, centerHz: Long, spanHz: Long, thresholdDb: Float, maximum: Int): List<Long> {
    if (trace.size < 3 || spanHz <= 0 || maximum <= 0) return emptyList()
    return (1 until trace.lastIndex).asSequence()
        .filter { trace[it].isFinite() && trace[it] >= thresholdDb && trace[it] >= trace[it - 1] && trace[it] > trace[it + 1] }
        .sortedByDescending { trace[it] }.take(maximum)
        .map { centerHz - spanHz / 2 + spanHz * it / trace.size }.toList()
}

data class BandStackEntry(
    val frequencyHz: Long,
    val mode: String,
    val filterHz: Int,
    val receiverId: String,
    val epoch: Long,
    val slotName: String = "",
    val lastHeardEpoch: Long = epoch,
)

class BandStackStore(context: Context) {
    private val prefs = context.getSharedPreferences("shackcq-sdr", Context.MODE_PRIVATE)
    var depth by mutableStateOf(prefs.getInt("band_stack_depth", 3).coerceIn(1, 12)); private set
    var perModeStacks by mutableStateOf(prefs.getBoolean("band_stack_per_mode", false)); private set
    var cycleDirection by mutableStateOf(runCatching { BandStackCycleDirection.valueOf(prefs.getString("band_stack_direction", "") ?: "") }
        .getOrDefault(BandStackCycleDirection.FORWARD)); private set
    var stacks by mutableStateOf(load()); private set

    fun updateDepth(value: Int) {
        depth = value.coerceIn(1, 12)
        stacks = stacks.mapValues { it.value.take(depth) }
        persist()
    }

    fun updateOptions(perMode: Boolean, direction: BandStackCycleDirection) {
        perModeStacks = perMode
        cycleDirection = direction
        prefs.edit().putBoolean("band_stack_per_mode", perMode).putString("band_stack_direction", direction.name).apply()
    }

    fun record(band: String, entry: BandStackEntry) {
        if (band.isBlank() || entry.frequencyHz <= 0) return
        val validated = entry.copy(mode = entry.mode.uppercase().take(12), filterHz = entry.filterHz.coerceIn(0, 100_000),
            receiverId = entry.receiverId.take(40), slotName = entry.slotName.take(40), lastHeardEpoch = entry.lastHeardEpoch.coerceAtLeast(0))
        val key = if (perModeStacks) "$band|${validated.mode}" else band
        stacks = stacks + (key to (listOf(validated) + stacks[key].orEmpty().filterNot { it.frequencyHz == validated.frequencyHz && it.mode == validated.mode }).take(depth))
        persist()
    }

    fun entries(band: String, mode: String? = null): List<BandStackEntry> {
        val key = if (perModeStacks && !mode.isNullOrBlank()) "$band|${mode.uppercase()}" else band
        return stacks[key].orEmpty()
    }

    fun cycle(band: String, currentHz: Long, mode: String? = null): BandStackEntry? {
        val key = if (perModeStacks && !mode.isNullOrBlank()) "$band|${mode.uppercase()}" else band
        val rows = entries(band, mode)
        if (rows.isEmpty()) return null
        val index = rows.indexOfFirst { it.frequencyHz == currentHz }
        val step = if (cycleDirection == BandStackCycleDirection.FORWARD) 1 else -1
        return if (index < 0) rows[if (step > 0) 0 else rows.lastIndex] else rows[(index + step).mod(rows.size)]
    }

    fun replace(band: String, index: Int, entry: BandStackEntry) {
        val key = if (perModeStacks) "$band|${entry.mode.uppercase()}" else band
        val rows = stacks[key].orEmpty().toMutableList()
        if (index !in rows.indices) return
        rows[index] = entry
        stacks = stacks + (key to rows.take(depth))
        persist()
    }

    private fun load(): Map<String, List<BandStackEntry>> = runCatching {
        val root = JSONObject(prefs.getString("band_stacks_v1", "{}"))
        root.keys().asSequence().associateWith { band ->
            val rows = root.getJSONArray(band)
            List(rows.length().coerceAtMost(12)) { index -> rows.getJSONObject(index).let { row ->
                BandStackEntry(row.getLong("hz"), row.getString("mode"), row.getInt("filter"), row.optString("receiver"), row.getLong("epoch"),
                    row.optString("slot_name"), row.optLong("last_heard", row.getLong("epoch")))
            } }
        }
    }.getOrDefault(emptyMap())

    private fun persist() {
        val root = JSONObject()
        stacks.forEach { (band, entries) -> root.put(band, JSONArray(entries.map { JSONObject()
            .put("hz", it.frequencyHz).put("mode", it.mode).put("filter", it.filterHz)
            .put("receiver", it.receiverId).put("epoch", it.epoch).put("slot_name", it.slotName).put("last_heard", it.lastHeardEpoch) })) }
        prefs.edit().putInt("band_stack_depth", depth).putString("band_stacks_v1", root.toString()).apply()
    }
}

enum class RfEvidenceClass { OBSERVED, HISTORICAL, OUTLOOK }
enum class RfPrecision { EXACT, GRID, COARSE }

data class RfObservation(
    val id: String,
    val source: String,
    val evidence: RfEvidenceClass,
    val epoch: Long,
    val callsign: String,
    val band: String,
    val mode: String,
    val transmitterLatitude: Double,
    val transmitterLongitude: Double,
    val receiverLatitude: Double,
    val receiverLongitude: Double,
    val precision: RfPrecision,
    val snr: Int? = null,
    val worked: Boolean? = null,
    val confirmed: Boolean? = null,
    val needed: Set<String> = emptySet(),
    val contestMultiplier: Boolean? = null,
    val contestDuplicate: Boolean? = null,
    val chaserPriority: Int? = null,
    val transmitterRegion: String = "",
    val receiverRegion: String = "",
    val continent: String = "",
    val entity: String = "",
    val cqZone: Int? = null,
    val ituZone: Int? = null,
    val bandHealth: String = "",
)

data class RfFilters(
    val sources: Set<String> = emptySet(),
    val bands: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val evidence: Set<RfEvidenceClass> = emptySet(),
    val maximumAgeSeconds: Long = 3_600,
    val callsign: String = "",
    val worked: Boolean? = null,
    val confirmed: Boolean? = null,
    val needed: String? = null,
    val contestMultipliersOnly: Boolean = false,
    val hideContestDuplicates: Boolean = false,
    val minimumChaserPriority: Int? = null,
    val longPath: Boolean = false,
    val transmitterRegions: Set<String> = emptySet(),
    val receiverRegions: Set<String> = emptySet(),
    val continents: Set<String> = emptySet(),
    val entities: Set<String> = emptySet(),
    val cqZones: Set<Int> = emptySet(),
    val ituZones: Set<Int> = emptySet(),
    val minimumDistanceKm: Double? = null,
    val maximumDistanceKm: Double? = null,
    val minimumBearingDegrees: Double? = null,
    val maximumBearingDegrees: Double? = null,
    val maximumSourceAgeSeconds: Long? = null,
    val bandHealth: Set<String> = emptySet(),
    val outlookMinutes: Set<Int> = setOf(30, 60, 120),
)

data class GeoArcPoint(val latitude: Double, val longitude: Double)

class RfObservationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generation = 0L
    var observations by mutableStateOf<List<RfObservation>>(emptyList()); private set
    var filtered by mutableStateOf<List<RfObservation>>(emptyList()); private set
    var filters by mutableStateOf(RfFilters()); private set
    var filterMillis by mutableStateOf(0L); private set
    var selectedId by mutableStateOf<String?>(null); private set

    fun submit(rows: List<RfObservation>) {
        observations = rows.takeLast(100_000)
        refilter(filters)
    }

    fun updateFilters(value: RfFilters) { filters = value; refilter(value) }
    fun resetFilters() = updateFilters(RfFilters())
    fun select(id: String?) { selectedId = id?.takeIf { key -> filtered.any { it.id == key } } }

    private fun refilter(value: RfFilters) {
        val localGeneration = ++generation
        val source = observations
        scope.launch {
            val start = System.nanoTime()
            val now = Instant.now().epochSecond
            val needle = value.callsign.trim().uppercase(Locale.US)
            val result = source.asSequence().filter { row ->
                val distance = greatCircleDistanceKm(row.transmitterLatitude, row.transmitterLongitude,
                    row.receiverLatitude, row.receiverLongitude)
                val bearing = initialBearingDegrees(row.transmitterLatitude, row.transmitterLongitude,
                    row.receiverLatitude, row.receiverLongitude)
                (value.sources.isEmpty() || row.source in value.sources) &&
                    (value.bands.isEmpty() || row.band in value.bands) &&
                    (value.modes.isEmpty() || row.mode.uppercase(Locale.US) in value.modes) &&
                    (value.evidence.isEmpty() || row.evidence in value.evidence) &&
                    now - row.epoch <= value.maximumAgeSeconds &&
                    (needle.isBlank() || needle in row.callsign.uppercase(Locale.US)) &&
                    (value.worked == null || row.worked == value.worked) &&
                    (value.confirmed == null || row.confirmed == value.confirmed) &&
                    (value.needed == null || value.needed in row.needed) &&
                    (!value.contestMultipliersOnly || row.contestMultiplier == true) &&
                    (!value.hideContestDuplicates || row.contestDuplicate != true) &&
                    (value.minimumChaserPriority == null || (row.chaserPriority ?: Int.MIN_VALUE) >= value.minimumChaserPriority) &&
                    (value.transmitterRegions.isEmpty() || row.transmitterRegion in value.transmitterRegions) &&
                    (value.receiverRegions.isEmpty() || row.receiverRegion in value.receiverRegions) &&
                    (value.continents.isEmpty() || row.continent in value.continents) &&
                    (value.entities.isEmpty() || row.entity in value.entities) &&
                    (value.cqZones.isEmpty() || row.cqZone in value.cqZones) &&
                    (value.ituZones.isEmpty() || row.ituZone in value.ituZones) &&
                    (value.minimumDistanceKm == null || distance >= value.minimumDistanceKm) &&
                    (value.maximumDistanceKm == null || distance <= value.maximumDistanceKm) &&
                    bearingInRange(bearing, value.minimumBearingDegrees, value.maximumBearingDegrees) &&
                    (value.maximumSourceAgeSeconds == null || now - row.epoch <= value.maximumSourceAgeSeconds) &&
                    (value.bandHealth.isEmpty() || row.bandHealth in value.bandHealth) &&
                    (row.evidence != RfEvidenceClass.OUTLOOK || value.outlookMinutes.any { now - row.epoch <= it * 60L })
            }.take(100_000).toList()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (localGeneration == generation) {
                filtered = result
                filterMillis = elapsed
                if (selectedId !in result.map(RfObservation::id).toSet()) selectedId = null
            }
        }
    }

    fun close() = scope.cancel()
}

internal fun greatCircle(aLat: Double, aLon: Double, bLat: Double, bLon: Double, longPath: Boolean = false, points: Int = 64): List<GeoArcPoint> {
    val aPhi = Math.toRadians(aLat); val aLambda = Math.toRadians(aLon)
    val bPhi = Math.toRadians(bLat); val bLambda = Math.toRadians(bLon)
    val av = doubleArrayOf(cos(aPhi) * cos(aLambda), cos(aPhi) * sin(aLambda), sin(aPhi))
    val bv = doubleArrayOf(cos(bPhi) * cos(bLambda), cos(bPhi) * sin(bLambda), sin(bPhi))
    var omega = acos((av[0] * bv[0] + av[1] * bv[1] + av[2] * bv[2]).coerceIn(-1.0, 1.0))
    if (omega < 1e-9) return listOf(GeoArcPoint(aLat, aLon), GeoArcPoint(bLat, bLon))
    if (longPath) omega -= 2 * PI
    val denominator = sin(omega)
    return List(points.coerceIn(2, 256)) { index ->
        val t = index.toDouble() / (points.coerceIn(2, 256) - 1)
        val first = sin((1 - t) * omega) / denominator
        val second = sin(t * omega) / denominator
        val x = first * av[0] + second * bv[0]
        val y = first * av[1] + second * bv[1]
        val z = first * av[2] + second * bv[2]
        GeoArcPoint(Math.toDegrees(atan2(z, sqrt(x * x + y * y))), normalizeLongitude(Math.toDegrees(atan2(y, x))))
    }
}

internal fun propagationControlPoints(row: RfObservation, longPath: Boolean): List<GeoArcPoint> {
    val distance = greatCircleDistanceKm(row.transmitterLatitude, row.transmitterLongitude, row.receiverLatitude, row.receiverLongitude)
    if (distance < 1_000) return emptyList()
    val hops = (distance / 3_500.0).toInt().coerceIn(1, 5)
    val path = greatCircle(row.transmitterLatitude, row.transmitterLongitude, row.receiverLatitude, row.receiverLongitude, longPath, hops * 2 + 1)
    return (1..hops).map { path[(it * (path.lastIndex.toDouble() / (hops + 1))).toInt().coerceIn(1, path.lastIndex - 1)] }
}

internal fun greatCircleDistanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
    val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 6371.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
}

internal fun initialBearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val p1 = Math.toRadians(aLat)
    val p2 = Math.toRadians(bLat)
    val dl = Math.toRadians(bLon - aLon)
    return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360.0) % 360.0
}

private fun bearingInRange(value: Double, minimum: Double?, maximum: Double?): Boolean {
    if (minimum == null && maximum == null) return true
    val low = minimum ?: 0.0
    val high = maximum ?: 360.0
    return if (low <= high) value in low..high else value >= low || value <= high
}

private fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0

data class AnnouncementSettings(
    val enabled: Boolean = false,
    val frequency: Boolean = true,
    val bandMode: Boolean = true,
    val allocationWarning: Boolean = true,
    val highSwr: Boolean = true,
    val addressedDigi: Boolean = false,
    val routeLoss: Boolean = true,
    val speechRate: Float = 1f,
    val voiceName: String = "",
)

class SpokenAnnouncementController(
    context: Context,
    private val transmitting: () -> Boolean,
    private val voiceMacroBusy: () -> Boolean,
    private val quietProfile: () -> Boolean,
) : TextToSpeech.OnInitListener, AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = appContext.getSharedPreferences("shackcq-sdr", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = TextToSpeech(appContext, this)
    private var tuneJob: Job? = null
    private var lastHighSwrAt = 0L
    var available by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var settings by mutableStateOf(load()); private set

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { scope.launch { speaking = true } }
            override fun onDone(utteranceId: String?) = releaseAudioFocus()
            @Deprecated("Android TTS callback")
            override fun onError(utteranceId: String?) = releaseAudioFocus()
        })
    }

    override fun onInit(status: Int) {
        available = status == TextToSpeech.SUCCESS
        if (available) applyEngineSettings()
    }

    fun update(value: AnnouncementSettings) {
        settings = value.copy(speechRate = value.speechRate.coerceIn(.5f, 2f))
        prefs.edit().putString("announcements_v1", JSONObject()
            .put("enabled", settings.enabled).put("frequency", settings.frequency).put("band_mode", settings.bandMode)
            .put("allocation", settings.allocationWarning).put("swr", settings.highSwr).put("digi", settings.addressedDigi)
            .put("route", settings.routeLoss).put("rate", settings.speechRate.toDouble()).put("voice", settings.voiceName).toString()).apply()
        applyEngineSettings()
        if (!settings.enabled) stop()
    }

    fun announceTuning(frequencyHz: Long, band: String, mode: String) {
        if (!settings.frequency || frequencyHz <= 0) return
        tuneJob?.cancel()
        tuneJob = scope.launch { delay(650); speak(tuningAnnouncementText(frequencyHz, band, mode)) }
    }

    fun announceBandMode(band: String, mode: String) { if (settings.bandMode) speak("$band, $mode") }
    fun announceAllocationWarning(message: String) { if (settings.allocationWarning) speak(message) }
    fun announceHighSwr(swr: Double?) {
        val now = System.currentTimeMillis()
        if (settings.highSwr && swr != null && swr >= 3.0 && now - lastHighSwrAt >= 10_000) {
            lastHighSwrAt = now
            speak("Warning. High S W R, ${"%.1f".format(Locale.US, swr)}")
        }
    }
    fun announceAddressedDigi(message: String) { if (settings.addressedDigi) speak(message.take(180)) }
    fun announceRouteLoss(message: String) { if (settings.routeLoss) speak(message.take(180)) }

    private fun speak(text: String) {
        if (!announcementAllowed(settings.enabled, available, quietProfile(), transmitting(), voiceMacroBusy(), text)) return
        @Suppress("DEPRECATION")
        val focus = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shackcq-${UUID.randomUUID()}") != TextToSpeech.SUCCESS) {
            releaseAudioFocus()
        }
    }

    fun stop() { tuneJob?.cancel(); tuneJob = null; engine.stop(); releaseAudioFocus() }
    fun globalStop() = stop()
    override fun close() { stop(); engine.shutdown(); scope.cancel() }

    private fun applyEngineSettings() {
        if (!available) return
        engine.setSpeechRate(settings.speechRate)
        if (settings.voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == settings.voiceName }?.let { engine.voice = it }
    }

    private fun releaseAudioFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
        scope.launch { speaking = false }
    }

    private fun load(): AnnouncementSettings = runCatching {
        val row = JSONObject(prefs.getString("announcements_v1", "{}"))
        AnnouncementSettings(row.optBoolean("enabled"), row.optBoolean("frequency", true), row.optBoolean("band_mode", true),
            row.optBoolean("allocation", true), row.optBoolean("swr", true), row.optBoolean("digi"), row.optBoolean("route", true),
            row.optDouble("rate", 1.0).toFloat(), row.optString("voice"))
    }.getOrDefault(AnnouncementSettings())
}

private fun spokenFrequency(hz: Long): String = "%.6f megahertz".format(Locale.US, hz / 1_000_000.0)

internal fun tuningAnnouncementText(frequencyHz: Long, band: String, mode: String): String =
    "$band, $mode, ${spokenFrequency(frequencyHz)}"

internal fun announcementAllowed(enabled: Boolean, available: Boolean, quiet: Boolean, transmitting: Boolean,
    voiceMacroBusy: Boolean, text: String): Boolean =
    enabled && available && !quiet && !transmitting && !voiceMacroBusy && text.isNotBlank()

enum class DebugLocalFixture {
    USB, LSB, CW, AM, SAM_OFFSET, NFM, NFM_CTCSS, NFM_DCS_NORMAL, NFM_DCS_INVERTED,
    WFM_MONO, WFM_STEREO_RDS, DUAL_RECEIVERS, SCANNER_HIT, RECORDING_TIME_SHIFT,
    DRIFTING_CARRIERS, CLOSE_CARRIERS, RTTY_PAIR, BURSTY_TRAFFIC, CHANGING_NOISE,
}

class DebugSdrLab(
    private val runtime: TciRuntimeState,
    private val panadapter: PanadapterController,
    private val rf: RfObservationController,
    private val operational: SdrOperationalV2? = null,
    private val localReceivers: LocalReceiverController? = null,
    private val workbench: AndroidSdrWorkbenchV4? = null,
    private val txDebug: DebugTciTransmitter? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var active by mutableStateOf(false); private set
    var localFixture by mutableStateOf(DebugLocalFixture.DUAL_RECEIVERS); private set
    var txScenario by mutableStateOf(DebugTciTxScenario.PTT_SUCCESS); private set
    private var job: Job? = null

    fun start() {
        check(BuildConfig.DEBUG) { "Debug SDR lab is unavailable in release builds" }
        if (active) return
        active = true
        txDebug?.start()
        val now = Instant.now().epochSecond
        val receivers = listOf(
            TciReceiverSnapshot("tci:0", 0, "DEMO RX 1", active = true, listening = true, vfoAHz = 14_074_000, vfoBHz = 14_095_600,
                mode = "DIGU", passbandHz = 3_000, sampleRate = 96_000, iqRunning = true),
            TciReceiverSnapshot("tci:1", 1, "DEMO RX 2", vfoAHz = 7_074_000, vfoBHz = 7_038_600,
                mode = "DIGU", passbandHz = 3_000, sampleRate = 96_000, iqRunning = true),
        )
        runtime.publish(TciRuntimeSnapshot(1, TciConnectionState.READY, "TCI", "DEBUG", "DEMO · NO RADIO", true, 2, 2,
            "tci:0", "tci:0", receivers))
        operational?.seedDebug()
        workbench?.seedDebugSurvey()
        workbench?.measurement?.apply {
            upsertMonitor(ChannelMonitor(name = "DEMO REPEATER", frequencyHz = 14_074_000, mode = "NFM", expectedCtcssHz = 88.5f))
            upsertMonitor(ChannelMonitor(name = "DEMO BEACON", frequencyHz = 14_075_250, mode = "CW"))
            upsertMonitor(ChannelMonitor(name = "DEMO CALLING", frequencyHz = 14_076_000, mode = "USB"))
            upsertMonitor(ChannelMonitor(name = "DEMO PRIORITY", frequencyHz = 14_077_000, mode = "NFM", expectedDcs = 23))
            selectTracker(14_075_100)
        }
        localReceivers?.let { local ->
            if (local.snapshot.receivers.isEmpty()) {
                local.add("DEBUG FIXTURE", 0, 14_074_000, 96_000)
                local.add("DEBUG FIXTURE", 1, 7_074_000, 96_000)
            }
            local.listen("local:A", true)
        }
        operational?.timeShift?.configure(TimeShiftLength.SECONDS_30)
        rf.submit((0 until 120).map { index -> RfObservation("demo-$index", listOf("PSK", "WSPR", "RBN")[index % 3],
            RfEvidenceClass.OBSERVED, now - index * 20L, "DEMO${index + 1}", listOf("20m", "40m", "15m")[index % 3],
            if (index % 2 == 0) "FT8" else "WSPR", -12.0, 130.0, -70.0 + index % 35 * 4.0,
            -170.0 + index * 17.0 % 340.0, RfPrecision.GRID, -24 + index % 30) })
        job = scope.launch {
            var phase = 0.0
            while (isActive && active) {
                val rate = if (localFixture in setOf(DebugLocalFixture.WFM_MONO, DebugLocalFixture.WFM_STEREO_RDS)) 192_000 else 96_000
                repeat(if (localFixture == DebugLocalFixture.DUAL_RECEIVERS) 2 else 1) { receiver ->
                    val samples = debugLocalIq(localFixture, receiver, rate, 2_048, phase)
                    val processed = workbench?.ingestLive("DEBUG FIXTURE", receiver,
                        if (receiver == 0) 14_074_000 else 7_074_000, rate, samples) ?: samples
                    panadapter.pushTciIq(receiver, rate, processed)
                    localReceivers?.pushIq("DEBUG FIXTURE", receiver,
                        if (receiver == 0) 14_074_000 else 7_074_000, rate, processed)
                }
                if (localFixture == DebugLocalFixture.WFM_STEREO_RDS) localReceivers?.debugRds("local:A")
                if (localFixture == DebugLocalFixture.RECORDING_TIME_SHIFT && localReceivers?.snapshot?.recordingState != "RECORDING")
                    localReceivers?.startRecording("local:A", "DEMO · NO RADIO time-shift fixture")
                phase += 2_048
                delay(22)
            }
        }
    }

    fun selectLocalFixture(value: DebugLocalFixture) {
        check(BuildConfig.DEBUG)
        localFixture = value
        val mode = when (value) {
            DebugLocalFixture.USB, DebugLocalFixture.DUAL_RECEIVERS, DebugLocalFixture.SCANNER_HIT, DebugLocalFixture.RECORDING_TIME_SHIFT -> LocalReceiverMode.USB
            DebugLocalFixture.DRIFTING_CARRIERS, DebugLocalFixture.CLOSE_CARRIERS, DebugLocalFixture.RTTY_PAIR,
            DebugLocalFixture.BURSTY_TRAFFIC, DebugLocalFixture.CHANGING_NOISE -> LocalReceiverMode.USB
            DebugLocalFixture.LSB -> LocalReceiverMode.LSB
            DebugLocalFixture.CW -> LocalReceiverMode.CW
            DebugLocalFixture.AM -> LocalReceiverMode.AM
            DebugLocalFixture.SAM_OFFSET -> LocalReceiverMode.SAM
            DebugLocalFixture.NFM, DebugLocalFixture.NFM_CTCSS, DebugLocalFixture.NFM_DCS_NORMAL, DebugLocalFixture.NFM_DCS_INVERTED -> LocalReceiverMode.NFM
            DebugLocalFixture.WFM_MONO, DebugLocalFixture.WFM_STEREO_RDS -> LocalReceiverMode.WFM
        }
        localReceivers?.setMode("local:A", mode)
    }

    fun selectTxScenario(value: DebugTciTxScenario) {
        check(BuildConfig.DEBUG); txScenario = value; txDebug?.select(value)
    }

    fun fakeTransmit() { check(BuildConfig.DEBUG && active); scope.launch { txDebug?.transmit() } }
    fun fakeTune() { check(BuildConfig.DEBUG && active); scope.launch { txDebug?.tune() } }

    fun stop() {
        txDebug?.stop()
        active = false; job?.cancel(); job = null
        panadapter.detachTciSources("Debug SDR lab stopped")
        runtime.publish(TciRuntimeSnapshot(lastError = "DEMO · NO RADIO stopped"))
        rf.submit(emptyList())
        operational?.stopActive("Debug SDR lab stopped")
        localReceivers?.stopActive("Debug SDR lab stopped")
        workbench?.capture?.captures?.filter { it.metadata.source == "DEBUG FIXTURE" }?.forEach { workbench.capture.delete(it.metadata.id) }
    }

    override fun close() { stop(); scope.cancel() }
}

internal fun debugLocalIq(fixture: DebugLocalFixture, receiver: Int, rate: Int, frames: Int, startFrame: Double): FloatArray {
    val output = FloatArray(frames * 2)
    var fmPhase = 0.0
    repeat(frames) { index ->
        val t = startFrame + index
        val complexHz = when (fixture) {
            DebugLocalFixture.LSB -> -1_100.0
            DebugLocalFixture.CW -> 600.0
            DebugLocalFixture.SAM_OFFSET -> 73.0
            DebugLocalFixture.DUAL_RECEIVERS -> if (receiver == 0) 1_100.0 else -2_400.0
            DebugLocalFixture.DRIFTING_CARRIERS -> 900.0 + (t / rate / 4.0) % 500.0
            DebugLocalFixture.CLOSE_CARRIERS -> if ((t.toLong() / 256L) % 2L == 0L) 1_000.0 else 1_125.0
            DebugLocalFixture.RTTY_PAIR -> if ((t.toLong() / 240L) % 2L == 0L) 900.0 else 1_070.0
            DebugLocalFixture.BURSTY_TRAFFIC -> 1_500.0
            DebugLocalFixture.CHANGING_NOISE -> 500.0 + (t / rate * 50.0) % 2_000.0
            else -> 1_100.0
        }
        val amplitude = when (fixture) {
            DebugLocalFixture.AM, DebugLocalFixture.SAM_OFFSET -> .48 * (1.0 + .55 * sin(2.0 * PI * 1_000.0 * t / rate))
            DebugLocalFixture.BURSTY_TRAFFIC -> if ((t.toLong() / rate) % 3L == 0L) .48 else .01
            DebugLocalFixture.CHANGING_NOISE -> .08 + .35 * (1.0 + sin(2.0 * PI * t / rate / 8.0)) / 2.0
            else -> .45
        }
        val phase = when (fixture) {
            DebugLocalFixture.NFM, DebugLocalFixture.SCANNER_HIT -> { fmPhase += 2.0 * PI * 2_000.0 * sin(2.0 * PI * 1_000.0 * t / rate) / rate; fmPhase }
            DebugLocalFixture.NFM_CTCSS -> { fmPhase += 2.0 * PI * (2_000.0 * sin(2.0 * PI * 1_000.0 * t / rate) + 600.0 * sin(2.0 * PI * 88.5 * t / rate)) / rate; fmPhase }
            DebugLocalFixture.NFM_DCS_NORMAL, DebugLocalFixture.NFM_DCS_INVERTED -> {
                val bit = ((t * 134.4 / rate).toLong() % 23L) in setOf(0L, 1L, 4L, 7L, 8L, 12L, 17L, 21L)
                val sign = if (bit xor (fixture == DebugLocalFixture.NFM_DCS_INVERTED)) 1.0 else -1.0
                fmPhase += 2.0 * PI * 650.0 * sign / rate; fmPhase
            }
            DebugLocalFixture.WFM_MONO, DebugLocalFixture.WFM_STEREO_RDS -> {
                val multiplex = 12_000.0 * sin(2.0 * PI * 1_000.0 * t / rate) +
                    if (fixture == DebugLocalFixture.WFM_STEREO_RDS) 3_000.0 * sin(2.0 * PI * 19_000.0 * t / rate) else 0.0
                fmPhase += 2.0 * PI * multiplex / rate; fmPhase
            }
            else -> 2.0 * PI * complexHz * t / rate
        }
        output[index * 2] = (amplitude * sin(phase)).toFloat()
        output[index * 2 + 1] = (amplitude * cos(phase)).toFloat()
    }
    return output
}
