package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressChartDataTest {
    @Test fun chronologicalChartsShowTheLatestBucketsInDateOrder() {
        val rows = (1..24).map { ProgressBucket("2026-${it.toString().padStart(2, '0')}", it) }.reversed()

        val visible = visibleProgressChartRows(rows, ProgressChartOrder.CHRONOLOGICAL)

        assertEquals((7..24).map { "2026-${it.toString().padStart(2, '0')}" }, visible.map(ProgressBucket::label))
    }

    @Test fun rankedChartsShowTheDominantBucketsNotTheTail() {
        val rows = (1..24).map { ProgressBucket("B$it", it) }

        val visible = visibleProgressChartRows(rows, ProgressChartOrder.RANKED)

        assertEquals((24 downTo 7).toList(), visible.map(ProgressBucket::count))
    }

    @Test fun fixedRangeChartsPreserveTheirSemanticOrder() {
        val rows = listOf("<500", "500–2k", "2k–5k", "5k–10k", "10k+").mapIndexed { index, label ->
            ProgressBucket(label, 100 - index)
        }

        assertEquals(rows, visibleProgressChartRows(rows, ProgressChartOrder.PRESERVE))
    }
}
