package app.shackcq.mobile.contest

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

interface ContestCabrilloQsoFormatter { fun format(session: ContestSession, qso: ContestQsoDraft): String }

private object StandardCabrilloQsoFormatter : ContestCabrilloQsoFormatter {
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)
    private val time = DateTimeFormatter.ofPattern("HHmm", Locale.US).withZone(ZoneOffset.UTC)
    override fun format(session: ContestSession, qso: ContestQsoDraft): String {
        val instant = Instant.ofEpochSecond(qso.createdAt)
        val mode = when (qso.mode) { ContestMode.CW -> "CW"; ContestMode.SSB -> "PH"; else -> "DG" }
        val sent = qso.sent.values.joinToString(" ").ifBlank { qso.rstSent }
        val received = qso.received.values.joinToString(" ").ifBlank { qso.rstReceived }
        return "QSO: %5d %-2s %s %s %-13s %-3s %-12s %-13s %-3s %-12s".format(Locale.US,
            qso.frequencyHz / 1000, mode, date.format(instant), time.format(instant), session.stationCallsign,
            qso.rstSent, sent, qso.callsign, qso.rstReceived, received).trimEnd()
    }
}

object ContestExport {
    fun cabrillo(session: ContestSession, definition: ContestDefinition, score: ContestScoreSnapshot, qsos: Sequence<ContestQsoDraft>): ContestExportResult {
        val issues = buildList {
            if (session.stationCallsign.isBlank()) add(ContestExportIssue(ContestTruth.INVALID, "CALLSIGN", "Station callsign is required"))
            if (session.category.operator.isBlank()) add(ContestExportIssue(ContestTruth.INVALID, "CATEGORY-OPERATOR", "Operator category is required"))
            definition.ambiguities.forEach { add(ContestExportIssue(ContestTruth.REVIEW_REQUIRED, "RULES", it)) }
        }
        val state = when { issues.any { it.truth == ContestTruth.INVALID } -> ContestExportState.BLOCKED; issues.isNotEmpty() -> ContestExportState.VALID_WITH_WARNINGS; else -> ContestExportState.VALID }
        if (state == ContestExportState.BLOCKED) return ContestExportResult(state, issues, emptySequence())
        val header = sequenceOf(
            "START-OF-LOG: 3.0", "CREATED-BY: ShackCQ Contest Core Android v1", "CONTEST: ${definition.cabrilloContestName}",
            "CALLSIGN: ${session.stationCallsign}", "LOCATION: ${session.station.stateProvince.ifBlank { "DX" }}",
            "CATEGORY-OPERATOR: ${session.category.operator}", "CATEGORY-ASSISTED: ${session.category.assisted}",
            "CATEGORY-BAND: ${session.category.band}", "CATEGORY-MODE: ${session.category.mode.name}",
            "CATEGORY-POWER: ${session.category.power}", "CATEGORY-STATION: ${session.category.station}",
            "CATEGORY-TRANSMITTER: ${session.category.transmitter}", "CLAIMED-SCORE: ${score.claimedScore}",
            "OPERATORS: ${session.operators.joinToString(",")}", "GRID-LOCATOR: ${session.stationGrid}",
        )
        val body = qsos.sortedWith(compareBy<ContestQsoDraft> { it.createdAt }.thenBy { it.qsoId }).map { StandardCabrilloQsoFormatter.format(session, it) }
        return ContestExportResult(state, issues, header + body + sequenceOf("END-OF-LOG:"))
    }

    fun adif(session: ContestSession, definition: ContestDefinition, qsos: Sequence<ContestQsoDraft>): Sequence<String> = sequence {
        yield("<ADIF_VER:5>3.1.4 <PROGRAMID:8>ShackCQ <EOH>")
        qsos.forEach { qso ->
            val fields = linkedMapOf(
                "QSO_DATE" to Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""),
                "TIME_ON" to Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalTime().format(DateTimeFormatter.ofPattern("HHmmss")),
                "CALL" to qso.callsign, "BAND" to qso.band.label.lowercase(), "MODE" to qso.mode.name,
                "RST_SENT" to qso.rstSent, "RST_RCVD" to qso.rstReceived, "CONTEST_ID" to definition.adifContestId,
                "APP_SHACKCQ_CONTEST_SESSION" to session.id.value,
            )
            qso.sent[ContestExchangeField.SERIAL]?.let { fields["STX"] = it }
            qso.received[ContestExchangeField.SERIAL]?.let { fields["SRX"] = it }
            val record = fields.entries.joinToString(" ") { (key, value) -> "<$key:${value.toByteArray(Charsets.UTF_8).size}>$value" } + " <EOR>"
            yield(record)
        }
    }
}
