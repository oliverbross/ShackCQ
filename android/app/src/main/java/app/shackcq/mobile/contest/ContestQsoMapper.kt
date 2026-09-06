package app.shackcq.mobile.contest

import app.shackcq.mobile.Qso
import java.util.UUID

object ContestQsoMapper {
    fun toCanonical(session: ContestSession, definition: ContestDefinition, draft: ContestQsoDraft): Qso {
        val stx = draft.sent[ContestExchangeField.SERIAL].orEmpty()
        val srx = draft.received[ContestExchangeField.SERIAL].orEmpty()
        val sentString = exchangeString(definition.sentExchange, draft.sent)
        val receivedString = exchangeString(definition.receivedExchange, draft.received)
        return Qso(
            id = draft.qsoId.ifBlank { UUID.randomUUID().toString() }, callsign = draft.callsign.trim().uppercase(), frequencyHz = draft.frequencyHz,
            mode = when (draft.mode) { ContestMode.SSB -> "SSB"; ContestMode.CW -> "CW"; ContestMode.DIGITAL -> "DIGITAL"; ContestMode.MIXED -> "" },
            rstSent = draft.rstSent, rstReceived = draft.rstReceived, createdAt = draft.createdAt, band = draft.band.label,
            operatorCallsign = session.operators.firstOrNull().orEmpty(), stationCallsign = session.stationCallsign, myGrid = session.stationGrid,
            dxcc = draft.worked.dxcc, continent = draft.worked.continent, cqZone = draft.worked.cqZone, ituZone = draft.worked.ituZone,
            state = draft.worked.stateProvince, contestId = definition.adifContestId,
            extraAdifFields = buildMap {
                if (stx.isNotBlank()) put("STX", stx); if (srx.isNotBlank()) put("SRX", srx)
                if (sentString.isNotBlank()) put("STX_STRING", sentString); if (receivedString.isNotBlank()) put("SRX_STRING", receivedString)
                put("APP_SHACKCQ_CONTEST_SESSION", session.id.value)
                if (draft.explicitDupeOverride) put("APP_SHACKCQ_CONTEST_DUPE_OVERRIDE", "Y")
                if (draft.networkOriginId.isNotBlank()) put("APP_SHACKCQ_N1MM_ORIGIN", draft.networkOriginId)
            }
        )
    }

    private fun exchangeString(fields: List<ContestExchangeField>, values: Map<ContestExchangeField, String>) =
        fields.filterNot { it == ContestExchangeField.RST }.mapNotNull { values[it]?.takeIf(String::isNotBlank) }.joinToString(" ")
}
