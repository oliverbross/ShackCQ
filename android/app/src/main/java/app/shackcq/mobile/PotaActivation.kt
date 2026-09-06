package app.shackcq.mobile

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal enum class PotaActivationState { ACTIVE, FINISHED }

internal data class PotaActivationSetup(
    val stationCallsign: String,
    val operatorCallsign: String,
    val references: List<String>,
    val primaryReference: String,
    val parkNames: Map<String, String> = emptyMap(),
    val stationGrid: String = "",
    val location: String = "",
    val state: String = "",
    val stationProfileId: String = "",
    val radioModel: String = "Elecraft KX3",
    val txPowerW: Int = 0,
    val antenna: String = "",
    val notes: String = "",
    val startAt: Long = Instant.now().epochSecond,
    val boundaryAcknowledged: Boolean = false,
)

internal data class PotaActivationSession(
    val id: String = UUID.randomUUID().toString(),
    val setup: PotaActivationSetup,
    val state: PotaActivationState = PotaActivationState.ACTIVE,
    val currentUtcDay: String = utcDay(setup.startAt),
    val lastFrequencyHz: Long = 0,
    val lastMode: String = "",
    val qsoIds: List<String> = emptyList(),
    val lastSavedAt: Long = Instant.now().epochSecond,
)

internal data class PotaActivationProgress(
    val total: Int,
    val currentDay: Int,
    val uniqueCalls: Int,
    val p2p: Int,
    val bands: Map<String, Int>,
    val modes: Map<String, Int>,
    val utcDay: String,
) {
    val thresholdReached: Boolean get() = currentDay >= 10
}

internal data class PotaAdifFile(val filename: String, val ownReference: String, val utcDay: String, val contents: String)
internal data class PotaExportResult(val files: List<PotaAdifFile>, val corrections: List<String>)
internal data class PotaP2pDraft(val token: Long, val callsign: String, val frequencyHz: Long, val mode: String, val references: List<String>)

internal fun utcDay(epoch: Long): String = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate().toString()

internal fun normalizePotaReferences(values: Iterable<String>): List<String> = values
    .map(::normalizePotaReference).filter(String::isNotBlank).distinct()

internal fun validPotaAdifMode(raw: String): Boolean = raw.trim().uppercase(Locale.US) in setOf(
    "AM", "ARDOP", "ATV", "CHIP", "CLO", "CONTESTI", "CW", "DIGITALVOICE", "DOMINO", "DYNAMIC", "FAX", "FM",
    "FSK441", "FT8", "FT4", "HELL", "ISCAT", "JT4", "JT6M", "JT9", "JT44", "JT65", "MFSK", "MSK144", "MT63",
    "OLIVIA", "OPERA", "PAC", "PAX", "PKT", "PSK", "PSK2K", "Q15", "QRA64", "ROS", "RTTY", "RTTYM", "SSB",
    "SSTV", "T10", "THOR", "THRB", "TOR", "V4", "VOI", "WINMOR", "WSPR", "USB", "LSB", "CWR")

internal fun activationProgress(session: PotaActivationSession, qsos: List<Qso>, now: Long = Instant.now().epochSecond): PotaActivationProgress {
    val rows = qsos.filter { it.activationSessionId == session.id }
    val day = utcDay(now)
    val current = rows.filter { utcDay(it.createdAt) == day }
    return PotaActivationProgress(rows.size, current.size, rows.map { it.callsign.uppercase(Locale.US) }.distinct().size,
        rows.count { it.potaRefs.isNotEmpty() || it.potaRef.isNotBlank() },
        rows.groupingBy { it.band.ifBlank { bandForFrequency(it.frequencyHz) } }.eachCount().filterKeys(String::isNotBlank),
        rows.groupingBy { it.submode.ifBlank { it.mode }.uppercase(Locale.US) }.eachCount().filterKeys(String::isNotBlank), day)
}

internal fun encodePotaSession(session: PotaActivationSession): String = JSONObject().apply {
    put("id", session.id); put("state", session.state.name); put("currentUtcDay", session.currentUtcDay)
    put("lastFrequencyHz", session.lastFrequencyHz); put("lastMode", session.lastMode)
    put("qsoIds", JSONArray(session.qsoIds)); put("lastSavedAt", session.lastSavedAt)
    put("setup", JSONObject().apply {
        val setup = session.setup
        put("stationCallsign", setup.stationCallsign); put("operatorCallsign", setup.operatorCallsign)
        put("references", JSONArray(setup.references)); put("primaryReference", setup.primaryReference)
        put("parkNames", JSONObject(setup.parkNames)); put("stationGrid", setup.stationGrid); put("location", setup.location)
        put("state", setup.state); put("stationProfileId", setup.stationProfileId); put("radioModel", setup.radioModel)
        put("txPowerW", setup.txPowerW); put("antenna", setup.antenna); put("notes", setup.notes)
        put("startAt", setup.startAt); put("boundaryAcknowledged", setup.boundaryAcknowledged)
    })
}.toString()

internal fun decodePotaSession(raw: String): PotaActivationSession {
    val root = JSONObject(raw); val setup = root.getJSONObject("setup")
    fun JSONArray.strings() = buildList { for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(::add) }
    val names = setup.optJSONObject("parkNames") ?: JSONObject()
    val parkNames = buildMap { names.keys().forEach { key -> put(key, names.optString(key)) } }
    val references = normalizePotaReferences(setup.optJSONArray("references")?.strings().orEmpty())
    require(references.isNotEmpty()) { "Activation has no valid POTA reference" }
    val primary = normalizePotaReference(setup.optString("primaryReference")).takeIf { it in references } ?: references.first()
    return PotaActivationSession(
        id = root.getString("id"),
        setup = PotaActivationSetup(setup.optString("stationCallsign"), setup.optString("operatorCallsign"), references, primary,
            parkNames, setup.optString("stationGrid"), setup.optString("location"), setup.optString("state"),
            setup.optString("stationProfileId"), setup.optString("radioModel", "Elecraft KX3"), setup.optInt("txPowerW"),
            setup.optString("antenna"), setup.optString("notes"), setup.optLong("startAt"), setup.optBoolean("boundaryAcknowledged")),
        state = runCatching { PotaActivationState.valueOf(root.optString("state")) }.getOrDefault(PotaActivationState.ACTIVE),
        currentUtcDay = root.optString("currentUtcDay").ifBlank { utcDay(setup.optLong("startAt")) },
        lastFrequencyHz = root.optLong("lastFrequencyHz"), lastMode = root.optString("lastMode"),
        qsoIds = root.optJSONArray("qsoIds")?.strings().orEmpty(), lastSavedAt = root.optLong("lastSavedAt"),
    )
}

internal fun buildPotaExports(session: PotaActivationSession, qsos: List<Qso>, now: Long = Instant.now().epochSecond): PotaExportResult {
    val rows = qsos.filter { it.activationSessionId == session.id }.sortedBy(Qso::createdAt)
    val corrections = linkedSetOf<String>()
    if (rows.isEmpty()) corrections += "No QSOs are available for export."
    val station = session.setup.stationCallsign.trim().uppercase(Locale.US)
    val operator = session.setup.operatorCallsign.trim().uppercase(Locale.US)
    if (station.isBlank() && operator.isBlank()) corrections += "Station or operator callsign is required."
    rows.forEach { qso ->
        if (qso.callsign.isBlank()) corrections += "${qso.id}: worked callsign is missing."
        if (qso.createdAt > now) corrections += "${qso.id}: QSO time is in the future."
        if (qso.callsign.equals(station, true) || qso.callsign.equals(operator, true)) corrections += "${qso.id}: station and worked callsign must differ."
        if (qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }.isBlank()) corrections += "${qso.id}: band is missing or invalid."
        if (!validPotaAdifMode(qso.submode.ifBlank { qso.mode })) corrections += "${qso.id}: mode is missing or not an ADIF mode/submode."
    }
    if (corrections.isNotEmpty()) return PotaExportResult(emptyList(), corrections.toList())
    val safeCall = station.ifBlank { operator }.replace(Regex("[^A-Z0-9_-]"), "_")
    val days = rows.map { utcDay(it.createdAt) }.distinct()
    val files = buildList {
        for (own in normalizePotaReferences(session.setup.references)) for (day in days) {
            val dayRows = rows.filter { utcDay(it.createdAt) == day }
            val body = buildString {
                append("ShackCQ POTA Activate\n<PROGRAMID:8>ShackCQ <PROGRAMVERSION:5>0.1.0 <EOH>\n")
                dayRows.forEach { qso ->
                    val others = normalizePotaReferences(qso.potaRefs.ifEmpty { listOf(qso.potaRef) })
                    (others.ifEmpty { listOf("") }).forEach { other -> append(potaAdifRecord(qso, session.setup, own, other)).append('\n') }
                }
            }
            add(PotaAdifFile("$safeCall@$own-${day.replace("-", "")}.adi", own, day, body))
        }
    }
    return PotaExportResult(files, emptyList())
}

private fun potaAdifRecord(qso: Qso, setup: PotaActivationSetup, own: String, other: String): String {
    fun tag(name: String, value: String): String = if (value.isBlank()) "" else "<$name:${value.length}>$value "
    val instant = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC)
    val mode = qso.submode.ifBlank { qso.mode }.uppercase(Locale.US)
    return buildString {
        append(tag("STATION_CALLSIGN", setup.stationCallsign.uppercase(Locale.US)))
        append(tag("OPERATOR", setup.operatorCallsign.uppercase(Locale.US)))
        append(tag("CALL", qso.callsign.uppercase(Locale.US)))
        append(tag("QSO_DATE", instant.format(DateTimeFormatter.ofPattern("yyyyMMdd"))))
        append(tag("TIME_ON", instant.format(DateTimeFormatter.ofPattern("HHmmss"))))
        append(tag("BAND", qso.band.ifBlank { bandForFrequency(qso.frequencyHz) }.uppercase(Locale.US)))
        append(tag(if (qso.submode.isNotBlank() || mode in setOf("USB", "LSB", "CWR", "FT8", "FT4")) "SUBMODE" else "MODE", mode))
        append(tag("FREQ", if (qso.frequencyHz > 0) "%.6f".format(Locale.US, qso.frequencyHz / 1_000_000.0) else ""))
        append(tag("RST_SENT", qso.rstSent)); append(tag("RST_RCVD", qso.rstReceived))
        append(tag("MY_GRIDSQUARE", qso.myGrid.ifBlank { setup.stationGrid }.uppercase(Locale.US)))
        append(tag("TX_PWR", qso.txPowerW.takeIf { it > 0 }?.toString().orEmpty()))
        append(tag("MY_STATE", qso.myState.ifBlank { setup.state }.uppercase(Locale.US)))
        append(tag("COMMENT", qso.notes.ifBlank { qso.comment }))
        append(tag("MY_SIG", "POTA")); append(tag("MY_SIG_INFO", own))
        if (other.isNotBlank()) { append(tag("SIG", "POTA")); append(tag("SIG_INFO", other)) }
        append("<EOR>")
    }.trim()
}

internal class PotaActivationController(context: Context, private val database: QsoDatabase) {
    private val activeFile = AtomicFile(File(context.filesDir, "pota-activation-active.json"))
    private val finishedFile = AtomicFile(File(context.filesDir, "pota-activation-finished.json"))
    var message by mutableStateOf(""); private set
    private val restoredActive = load(activeFile)
    var session by mutableStateOf(restoredActive ?: load(finishedFile)); private set
    var recovered by mutableStateOf(restoredActive != null); private set
    var pendingP2p by mutableStateOf<PotaP2pDraft?>(null); private set
    var pendingPlan by mutableStateOf<PotaActivationSetup?>(null); private set
    var openToken by mutableStateOf(0L); private set

    fun start(setup: PotaActivationSetup): Boolean {
        val refs = normalizePotaReferences(setup.references)
        if (session?.state == PotaActivationState.ACTIVE) { message = "Finish or abandon the active session first."; return false }
        if (!setup.boundaryAcknowledged || refs.isEmpty() || setup.stationCallsign.isBlank()) {
            message = "Station callsign, a valid park, and the boundary acknowledgement are required."; return false
        }
        val normalized = setup.copy(references = refs, primaryReference = normalizePotaReference(setup.primaryReference).takeIf { it in refs } ?: refs.first())
        session = PotaActivationSession(setup = normalized); recovered = false; persist(activeFile, session!!); message = "Activation started locally"
        return true
    }

    fun updateRadio(frequencyHz: Long, mode: String) {
        val current = session?.takeIf { it.state == PotaActivationState.ACTIVE } ?: return
        if (frequencyHz == current.lastFrequencyHz && mode == current.lastMode && utcDay(Instant.now().epochSecond) == current.currentUtcDay) return
        session = current.copy(currentUtcDay = utcDay(Instant.now().epochSecond), lastFrequencyHz = frequencyHz, lastMode = mode, lastSavedAt = Instant.now().epochSecond)
        persist(activeFile, session!!)
    }

    fun recordQso(id: String) {
        val current = session?.takeIf { it.state == PotaActivationState.ACTIVE } ?: return
        session = current.copy(qsoIds = (current.qsoIds + id).distinct(), lastSavedAt = Instant.now().epochSecond)
        persist(activeFile, session!!)
    }

    fun prepareP2p(spot: PortableSpot) {
        if (session?.state != PotaActivationState.ACTIVE || PortableProgram.POTA !in spot.programs) return
        val refs = normalizePotaReferences(spot.references.filter { it.program == PortableProgram.POTA }.map { it.code })
        pendingP2p = PotaP2pDraft(Instant.now().toEpochMilli(), spot.callsign, spot.frequencyHz, spot.mode, refs)
    }

    fun consumeP2p() { pendingP2p = null }
    fun preparePlan(setup: PotaActivationSetup) {
        pendingPlan = setup.copy(boundaryAcknowledged = false)
        openToken = Instant.now().toEpochMilli()
        message = "Planner details loaded — review the setup and boundary acknowledgement before starting"
    }
    fun consumePlan() { pendingPlan = null }
    fun requestOpen() { openToken = Instant.now().toEpochMilli() }
    fun resume() { recovered = false; message = "Activation resumed locally — no radio or transmit action was started" }

    fun finish() {
        val current = session?.takeIf { it.state == PotaActivationState.ACTIVE } ?: return
        val finished = current.copy(state = PotaActivationState.FINISHED, lastSavedAt = Instant.now().epochSecond)
        persist(finishedFile, finished); activeFile.delete(); session = finished; recovered = false; message = "Activation stopped — review retained locally"
    }

    fun abandon() { activeFile.delete(); session = null; recovered = false; message = "Session state removed; saved QSOs were preserved" }
    fun dismissReview() { if (session?.state == PotaActivationState.FINISHED) { finishedFile.delete(); session = null } }
    fun qsos(): List<Qso> = session?.let { database.activationQsos(it.id) }.orEmpty()
    fun progress(now: Long = Instant.now().epochSecond) = session?.let { activationProgress(it, database.activationQsos(it.id), now) }

    private fun load(file: AtomicFile): PotaActivationSession? = runCatching {
        if (!file.baseFile.exists()) null else decodePotaSession(file.openRead().bufferedReader().use { it.readText() })
    }.onFailure { message = "Saved activation could not be recovered; QSO journal was not changed" }.getOrNull()

    private fun persist(file: AtomicFile, value: PotaActivationSession) {
        val stream = file.startWrite()
        try { stream.writer().apply { write(encodePotaSession(value)); flush() }; file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); message = "Session save failed: ${error.message}"; throw error }
    }
}
