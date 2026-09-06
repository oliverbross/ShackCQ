package app.shackcq.mobile.contest

interface ContestOpportunityEvaluator {
    fun evaluate(input: ContestOpportunityInput): ContestOpportunityState
}

class DefaultContestOpportunityEvaluator(
    private val registry: ContestRuleRegistry,
    private val exchange: ContestExchangeEngine = ContestExchangeEngine(),
    private val engine: ContestScoreEngine = ContestScoreEngine(),
) : ContestOpportunityEvaluator {
    override fun evaluate(input: ContestOpportunityInput): ContestOpportunityState {
        val definition = registry.require(input.session.definitionId).definition
        val draft = ContestQsoDraft("opportunity", input.callsign, input.session.utcStart, 1, input.band, input.mode, "", "", worked = input.entity, station = input.session.station)
        val result = engine.evaluate(definition, draft, input.priorQsos)
        val hint = exchange.expectedHint(definition, input.entity)
        val activeClaim = input.claims.firstOrNull { it.callsign.equals(input.callsign, true) && it.expiresAt > input.evaluatedAt }
        return ContestOpportunityState(
            validBandMode = result.points.truth, dupe = result.dupe,
            newMultipliers = result.multipliers.filter { it.isNew && it.state == ContestTruth.VALID }.map { it.type }.toSet(),
            workedMultipliers = result.multipliers.filter { !it.isNew && it.state == ContestTruth.VALID }.map { it.type }.toSet(),
            unknownMultipliers = result.multipliers.filter { it.state == ContestTruth.UNKNOWN }.map { it.type }.toSet(),
            expectedExchangeHint = hint.first, hintSource = hint.second, claimedBy = activeClaim?.station,
            priorityReasons = buildList { if (result.dupe == ContestDupeState.NEW) add("Not worked in current dupe scope"); if (result.multipliers.any { it.isNew }) add("Potential new multiplier") },
        )
    }
}
