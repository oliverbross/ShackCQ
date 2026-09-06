package app.shackcq.mobile.contest

enum class ContestRuleFamily { CQ_WW, CQ_WPX, ARRL_DX, IARU_HF, ARRL_FIELD_DAY, CQ_160, OCEANIA_DX, GENERAL_SERIAL }

data class ContestDefinition(
    val id: ContestDefinitionId,
    val adifContestId: String,
    val cabrilloContestName: String,
    val humanName: String,
    val mode: ContestMode,
    val version: ContestRuleVersion,
    val family: ContestRuleFamily,
    val officialSources: List<ContestOfficialSource>,
    val entrantRegions: Set<ContestEntryRegion>,
    val allowedBands: Set<ContestBand>,
    val allowedModes: Set<ContestMode>,
    val sentExchange: List<ContestExchangeField>,
    val receivedExchange: List<ContestExchangeField>,
    val dupeScope: ContestDupeScope,
    val multiplierTypes: Set<ContestMultiplierType>,
    val serialRequired: Boolean = false,
    val ambiguities: List<String> = emptyList(),
)

data class ContestRulePack(val definition: ContestDefinition, val testVectorIds: List<String>)

object ContestRuleValidator {
    fun validate(pack: ContestRulePack): List<ContestValidationIssue> = buildList {
        val d = pack.definition
        if (d.id.value.isBlank()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Internal ID is required"))
        if (d.adifContestId.isBlank()) add(ContestValidationIssue(ContestTruth.INVALID, null, "ADIF CONTEST_ID is required"))
        if (d.cabrilloContestName.isBlank()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Cabrillo contest name is required"))
        if (d.officialSources.isEmpty()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Official rule source is required"))
        d.officialSources.forEach {
            if (!it.url.startsWith("https://")) add(ContestValidationIssue(ContestTruth.INVALID, null, "Official source must use HTTPS"))
            if (it.sha256.length != 64 || it.sha256.any { c -> c !in "0123456789abcdef" })
                add(ContestValidationIssue(ContestTruth.INVALID, null, "Official source digest must be SHA-256"))
        }
        if (d.allowedBands.isEmpty() || d.allowedModes.isEmpty()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Band and mode sets are required"))
        if (pack.testVectorIds.isEmpty()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Golden vector IDs are required"))
        d.ambiguities.forEach { add(ContestValidationIssue(ContestTruth.REVIEW_REQUIRED, null, it)) }
    }
}

class ContestRuleRegistry(packs: Iterable<ContestRulePack> = InitialContestRulePacks.all) {
    private val values = packs.associateBy { it.definition.id }
    init { require(values.isNotEmpty()); values.values.forEach { require(ContestRuleValidator.validate(it).none { issue -> issue.truth == ContestTruth.INVALID }) } }
    fun all(): List<ContestRulePack> = values.values.sortedBy { it.definition.humanName }
    fun require(id: ContestDefinitionId): ContestRulePack = requireNotNull(values[id]) { "Unknown contest definition ${id.value}" }
}
