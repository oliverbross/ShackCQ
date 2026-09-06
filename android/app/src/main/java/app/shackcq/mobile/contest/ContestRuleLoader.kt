package app.shackcq.mobile.contest

interface ContestRuleSource { fun load(): List<ContestRulePack> }

class ContestRuleLoader(private val source: ContestRuleSource = object : ContestRuleSource { override fun load() = InitialContestRulePacks.all }) {
    fun loadValidated(): List<ContestRulePack> = source.load().map { pack ->
        val invalid = ContestRuleValidator.validate(pack).filter { it.truth == ContestTruth.INVALID }
        require(invalid.isEmpty()) { invalid.joinToString { it.reason } }
        pack.copy(definition = pack.definition.copy(
            officialSources = pack.definition.officialSources.toList(), allowedBands = pack.definition.allowedBands.toSet(),
            allowedModes = pack.definition.allowedModes.toSet(), sentExchange = pack.definition.sentExchange.toList(), receivedExchange = pack.definition.receivedExchange.toList()))
    }.toList()
}
