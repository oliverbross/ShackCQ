package app.shackcq.mobile.contest

class ContestScoringEngine {
    fun points(definition: ContestDefinition, qso: ContestQsoDraft): ContestPointResult {
        if (qso.band !in definition.allowedBands || qso.mode !in definition.allowedModes)
            return ContestPointResult(ContestTruth.INVALID, null, "Band or mode is not allowed by this rule pack")
        if (qso.callsign.isBlank()) return ContestPointResult(ContestTruth.INCOMPLETE, null, "Callsign is missing")
        return when (definition.family) {
            ContestRuleFamily.CQ_WW -> cqWw(qso)
            ContestRuleFamily.CQ_WPX -> cqWpx(qso)
            ContestRuleFamily.ARRL_DX -> arrlDx(qso)
            ContestRuleFamily.IARU_HF -> iaru(qso)
            ContestRuleFamily.ARRL_FIELD_DAY -> ContestPointResult(ContestTruth.VALID, if (qso.mode == ContestMode.SSB) 1 else 2, "Field Day mode QSO points; entry power and bonus points are separate claim inputs")
            ContestRuleFamily.CQ_160 -> cq160(qso)
            ContestRuleFamily.OCEANIA_DX -> oceania(qso)
            ContestRuleFamily.GENERAL_SERIAL -> ContestPointResult(ContestTruth.VALID, 1, "Non-award general session")
        }
    }

    private fun cqWw(q: ContestQsoDraft): ContestPointResult {
        if (q.station.continent.isBlank() || q.worked.continent.isBlank() || q.station.dxcc.isBlank() || q.worked.dxcc.isBlank()) return unknown("Country/continent data is required")
        val points = when {
            q.station.dxcc == q.worked.dxcc -> 0
            q.station.continent != q.worked.continent -> 3
            q.station.continent == "NA" && q.worked.continent == "NA" -> 2
            else -> 1
        }
        return valid(points, "CQ WW country/continent relationship")
    }

    private fun cqWpx(q: ContestQsoDraft): ContestPointResult {
        if (q.station.continent.isBlank() || q.worked.continent.isBlank() || q.station.dxcc.isBlank() || q.worked.dxcc.isBlank()) return unknown("Country/continent data is required")
        val lowBand = q.band in setOf(ContestBand.B160, ContestBand.B80, ContestBand.B40)
        val points = when {
            q.station.continent != q.worked.continent -> if (lowBand) 6 else 3
            q.station.dxcc != q.worked.dxcc -> if (lowBand) 2 else 1
            else -> 1
        }
        return valid(points, "CQ WPX band and country/continent relationship")
    }

    private fun arrlDx(q: ContestQsoDraft): ContestPointResult {
        val local = q.station.isWve ?: return unknown("Entrant W/VE side is unknown")
        val remote = q.worked.isWve ?: return unknown("Worked station W/VE side is unknown")
        return if (local == remote) ContestPointResult(ContestTruth.INVALID, null, "ARRL DX requires a W/VE-to-DX contact") else valid(3, "Valid opposite-side ARRL DX contact")
    }

    private fun iaru(q: ContestQsoDraft): ContestPointResult {
        val localZone = q.station.ituZone.toIntOrNull() ?: return unknown("Entrant ITU zone is unknown")
        val remoteZone = q.worked.ituZone.toIntOrNull() ?: return unknown("Worked ITU zone is unknown")
        if (q.station.continent.isBlank() || q.worked.continent.isBlank()) return unknown("Continent data is required")
        return valid(when { localZone == remoteZone -> 1; q.station.continent == q.worked.continent -> 3; else -> 5 }, "IARU ITU-zone/continent relationship")
    }

    private fun cq160(q: ContestQsoDraft): ContestPointResult {
        if (q.station.continent.isBlank() || q.worked.continent.isBlank() || q.station.dxcc.isBlank() || q.worked.dxcc.isBlank()) return unknown("Country/continent data is required")
        return valid(when { q.station.dxcc == q.worked.dxcc -> 2; q.station.continent == q.worked.continent -> 5; else -> 10 }, "CQ 160 country/continent relationship")
    }

    private fun oceania(q: ContestQsoDraft): ContestPointResult {
        val local = q.station.isOceania ?: return unknown("Entrant Oceania status is unknown")
        val remote = q.worked.isOceania ?: return unknown("Worked Oceania status is unknown")
        if (!local && !remote) return valid(0, "Non-Oceania to non-Oceania QSO has no credit")
        val points = mapOf(ContestBand.B160 to 20, ContestBand.B80 to 10, ContestBand.B40 to 5, ContestBand.B20 to 1, ContestBand.B15 to 2, ContestBand.B10 to 3)[q.band]
            ?: return ContestPointResult(ContestTruth.INVALID, null, "Unsupported Oceania DX band")
        return valid(points, "Oceania DX band weighting")
    }

    private fun valid(points: Int, reason: String) = ContestPointResult(ContestTruth.VALID, points, reason)
    private fun unknown(reason: String) = ContestPointResult(ContestTruth.UNKNOWN, null, reason)
}
