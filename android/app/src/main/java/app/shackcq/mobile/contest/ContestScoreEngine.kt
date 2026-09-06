package app.shackcq.mobile.contest

class ContestScoreEngine(
    private val dupes: ContestDupeEngine = ContestDupeEngine(),
    private val scoring: ContestScoringEngine = ContestScoringEngine(),
    private val multipliers: ContestMultiplierEngine = ContestMultiplierEngine(),
    private val rate: ContestRateEngine = ContestRateEngine(),
) {
    fun evaluate(definition: ContestDefinition, draft: ContestQsoDraft, prior: List<ContestQsoDraft>): ContestQsoEvaluation {
        val dupe = dupes.evaluate(definition, draft, prior)
        val points = scoring.points(definition, draft)
        val mults = multipliers.evaluate(definition, draft, prior)
        val validity = when {
            dupe == ContestDupeState.DUPLICATE -> ContestTruth.INVALID
            points.truth != ContestTruth.VALID -> points.truth
            mults.any { it.state == ContestTruth.REVIEW_REQUIRED } -> ContestTruth.REVIEW_REQUIRED
            else -> ContestTruth.VALID
        }
        return ContestQsoEvaluation(validity, points, mults, dupe)
    }

    fun rebuild(definition: ContestDefinition, qsos: List<ContestQsoDraft>, nowEpochSeconds: Long): ContestScoreSnapshot {
        val ordered = qsos.sortedWith(compareBy<ContestQsoDraft> { it.createdAt }.thenBy { it.qsoId })
        val evaluated = mutableListOf<Pair<ContestQsoDraft, ContestQsoEvaluation>>()
        val seenDupes = hashSetOf<String>()
        val seenMultipliers = hashSetOf<String>()
        ordered.forEach { qso ->
            val dupeKey = dupes.key(definition, qso)
            val dupe = when { dupeKey == null -> ContestDupeState.UNKNOWN; dupeKey in seenDupes -> if(qso.explicitDupeOverride) ContestDupeState.OVERRIDDEN else ContestDupeState.DUPLICATE; else -> ContestDupeState.NEW }
            dupeKey?.let(seenDupes::add)
            val point = scoring.points(definition, qso)
            val mult = multipliers.keys(definition,qso).map { (type,key) ->
                if(key.isBlank()) ContestMultiplierResult(type,"",ContestTruth.UNKNOWN,false,"Required multiplier data is unknown") else {
                    val scoped="${type.name}|${multipliers.scope(definition,qso)}|$key";val fresh=scoped !in seenMultipliers;seenMultipliers+=scoped
                    ContestMultiplierResult(type,key,ContestTruth.VALID,fresh,if(fresh)"New ${type.name.lowercase()} multiplier" else "Already worked in multiplier scope")
                }
            }
            val validity=when{dupe==ContestDupeState.DUPLICATE->ContestTruth.INVALID;point.truth!=ContestTruth.VALID->point.truth;else->ContestTruth.VALID}
            evaluated += qso to ContestQsoEvaluation(validity,point,mult,dupe)
        }
        val valid = evaluated.filter { it.second.validity == ContestTruth.VALID }
        val points = valid.sumOf { it.second.points.points ?: 0 }
        val multiplierSets = ContestMultiplierType.entries.associateWith { type ->
            valid.flatMap { (qso,evaluation) -> evaluation.multipliers.filter { it.type == type && it.state == ContestTruth.VALID }.map { "${multipliers.scope(definition,qso)}|${it.key}" } }.filter { !it.endsWith('|') }.toSet().size
        }.filterValues { it > 0 }
        val multiplierTotal = multiplierSets.values.sum().coerceAtLeast(1)
        return ContestScoreSnapshot(
            acceptedQsos = evaluated.size, scoredQsos = valid.size,
            duplicates = evaluated.count { it.second.dupe == ContestDupeState.DUPLICATE },
            zeroPointValidQsos = valid.count { it.second.points.points == 0 },
            reviewQsos = evaluated.count { it.second.validity in setOf(ContestTruth.REVIEW_REQUIRED, ContestTruth.UNKNOWN, ContestTruth.INCOMPLETE) },
            points = points, multipliers = multiplierSets, claimedScore = points.toLong() * multiplierTotal,
            bandModeBreakdown = valid.groupingBy { "${it.first.band.label}/${it.first.mode.name}" }.eachCount(),
            rate = rate.calculate(evaluated, nowEpochSeconds), generatedAt = nowEpochSeconds,
            status = if (evaluated.any { it.second.validity == ContestTruth.REVIEW_REQUIRED }) ContestScoreStatus.REVIEW_REQUIRED else ContestScoreStatus.CURRENT,
        )
    }

    fun incremental(definition: ContestDefinition, currentRows: List<ContestQsoDraft>, added: ContestQsoDraft, nowEpochSeconds: Long) =
        rebuild(definition, currentRows + added, nowEpochSeconds)
}
