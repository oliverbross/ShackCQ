package app.shackcq.mobile.contest

class ContestMultiplierEngine {
    fun keys(definition: ContestDefinition, qso: ContestQsoDraft): List<Pair<ContestMultiplierType, String>> =
        definition.multiplierTypes.map { it to key(it, qso, definition) }

    fun scope(definition: ContestDefinition, qso: ContestQsoDraft): String = when (definition.family) {
        ContestRuleFamily.CQ_WW, ContestRuleFamily.ARRL_DX, ContestRuleFamily.CQ_160, ContestRuleFamily.OCEANIA_DX, ContestRuleFamily.IARU_HF -> qso.band.name
        else -> "ALL"
    }
    fun evaluate(definition: ContestDefinition, qso: ContestQsoDraft, prior: Iterable<ContestQsoDraft>): List<ContestMultiplierResult> =
        definition.multiplierTypes.map { type ->
            val key = key(type, qso, definition)
            if (key.isBlank()) ContestMultiplierResult(type, "", ContestTruth.UNKNOWN, false, "Required multiplier data is unknown")
            else {
                val worked = prior.any { old -> old.qsoId != qso.qsoId && multiplierScope(definition, old, qso) && key(type, old, definition) == key }
                ContestMultiplierResult(type, key, ContestTruth.VALID, !worked, if (worked) "Already worked in multiplier scope" else "New ${type.name.lowercase()} multiplier")
            }
        }

    private fun key(type: ContestMultiplierType, q: ContestQsoDraft, definition: ContestDefinition): String = when (type) {
        ContestMultiplierType.CQ_ZONE -> q.worked.cqZone
        ContestMultiplierType.ITU_ZONE -> q.worked.ituZone
        ContestMultiplierType.DXCC -> if (definition.family == ContestRuleFamily.ARRL_DX && q.station.isWve == false) "" else q.worked.dxcc
        ContestMultiplierType.PREFIX -> q.worked.wpxPrefix
        ContestMultiplierType.STATE_PROVINCE -> if (definition.family == ContestRuleFamily.ARRL_DX && q.station.isWve == true) "" else q.worked.stateProvince
        ContestMultiplierType.ARRL_SECTION -> q.worked.arrlSection
        ContestMultiplierType.HQ_SOCIETY -> q.worked.hqSociety
    }.trim().uppercase()

    private fun multiplierScope(definition: ContestDefinition, old: ContestQsoDraft, current: ContestQsoDraft): Boolean = when (definition.family) {
        ContestRuleFamily.CQ_WW, ContestRuleFamily.ARRL_DX, ContestRuleFamily.CQ_160, ContestRuleFamily.OCEANIA_DX -> old.band == current.band
        ContestRuleFamily.IARU_HF -> old.band == current.band
        else -> true
    }
}
