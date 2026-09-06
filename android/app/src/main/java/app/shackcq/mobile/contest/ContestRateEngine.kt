package app.shackcq.mobile.contest

class ContestRateEngine {
    fun calculate(rows: List<Pair<ContestQsoDraft, ContestQsoEvaluation>>, nowEpochSeconds: Long): ContestRateSnapshot {
        val accepted = rows.filter { it.second.validity == ContestTruth.VALID && it.second.dupe != ContestDupeState.DUPLICATE }.sortedBy { it.first.createdAt }
        val last = accepted.lastOrNull()?.first?.createdAt
        val previous = accepted.dropLast(1).lastOrNull()?.first?.createdAt
        fun rate(seconds: Long) = accepted.count { it.first.createdAt > nowEpochSeconds - seconds } * 3600.0 / seconds
        val minuteBuckets = (11 downTo 0).map { offset ->
            val start = nowEpochSeconds - (offset + 1) * 300
            accepted.count { it.first.createdAt in start until start + 300 }
        }
        val best = accepted.map { anchor -> accepted.count { it.first.createdAt in anchor.first.createdAt until anchor.first.createdAt + 3600 }.toDouble() }.maxOrNull() ?: 0.0
        val lastHour = accepted.filter { it.first.createdAt > nowEpochSeconds - 3600 }
        return ContestRateSnapshot(
            lastQsoIntervalSeconds = if (last != null && previous != null) (last - previous).coerceAtLeast(0) else null,
            last10MinutesPerHour = rate(600), last60MinutesPerHour = rate(3600), best60MinutesPerHour = best,
            pointsPerHour = lastHour.sumOf { it.second.points.points ?: 0 }.toDouble(),
            multipliersPerHour = lastHour.sumOf { row -> row.second.multipliers.count { it.isNew } }.toDouble(), buckets = minuteBuckets,
        )
    }
}
