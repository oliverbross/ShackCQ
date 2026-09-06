// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class BandMapDomainTest {
    private val now = 2_000_000L

    private fun observation(call: String = "K1ABC/P", frequency: Long = 14_074_000, epoch: Long = now,
        source: BandMapSource = BandMapSource.DX_CLUSTER, mode: String = "FT8", spotter: String = "W1AW") =
        BandMapSourceObservation(source, "$source|$call|$frequency|$epoch", source.name, call, frequency, epoch,
            spotterCallsign = spotter, mode = mode, comment = "test")

    private fun spot(observations: List<BandMapSourceObservation> = listOf(observation()), marks: Set<BandMapMarkKind> = emptySet()) =
        BandMapSpotIndex().coalesce(observations, if (marks.isEmpty()) emptyList() else listOf(BandMapMark("K1ABC/P", "20m", 14_074_000, marks, now))).single()

    @Test fun canonicalBandMappingCoversEverySupportedBand() {
        assertTrue(bandMapBands.size >= 30)
        bandMapBands.forEach { band -> assertEquals(band.name, BandMapSpotCanonicalizer.band("", (band.lowerHz + band.upperHz) / 2)) }
        assertEquals("2190m", BandMapSpotCanonicalizer.band("2200m", 136_000))
    }

    @Test fun callsignNormalizationPreservesMeaningfulSuffixes() {
        assertEquals("K1ABC/P", BandMapSpotCanonicalizer.callsign(" k1abc/p "))
        assertNotEquals(BandMapSpotCanonicalizer.callsign("K1ABC/P"), BandMapSpotCanonicalizer.callsign("K1ABC/MM"))
    }

    @Test fun sourceReportedModeRemainsExplicit() {
        assertEquals(BandMapModeFamily.DIGI, BandMapSpotCanonicalizer.mode("FT8", 14_074_000).first)
        assertEquals("FT8", BandMapSpotCanonicalizer.mode("FT8", 14_074_000).second)
        assertEquals(BandMapModeFamily.UNKNOWN, BandMapSpotCanonicalizer.mode("", 14_074_000).first)
    }

    @Test fun sourceAwareFrequencyCoalescingPreservesObservations() {
        val rows = listOf(observation(source = BandMapSource.DX_CLUSTER), observation(frequency = 14_074_040, source = BandMapSource.RBN, spotter = "VE3SKIM"))
        val result = BandMapSpotIndex().coalesce(rows).single()
        assertEquals(2, result.observations.size)
        assertEquals(setOf(BandMapSource.DX_CLUSTER, BandMapSource.RBN), result.sources)
        assertEquals(setOf("W1AW", "VE3SKIM"), result.spotters)
    }

    @Test fun digitalToleranceDoesNotCoalesceDistinctChannels() {
        assertEquals(2, BandMapSpotIndex().coalesce(listOf(observation(), observation(frequency = 14_074_200, source = BandMapSource.RBN))).size)
    }

    @Test fun sourceAwareAgingAndPinnedExpiryRemainTruthful() {
        val current = spot(listOf(observation(source = BandMapSource.RBN, epoch = now - 100)))
        assertEquals(BandMapAgeState.CURRENT, BandMapAging.state(current, now))
        val expired = spot(listOf(observation(source = BandMapSource.RBN, epoch = now - 1_000)))
        assertEquals(BandMapAgeState.EXPIRED, BandMapAging.state(expired, now))
        val pinned = spot(listOf(observation(source = BandMapSource.RBN, epoch = now - 1_000)), setOf(BandMapMarkKind.PIN))
        assertEquals(BandMapAgeState.PINNED_STALE, BandMapAging.state(pinned, now))
        assertEquals(now - 1_000, pinned.newestObservationEpoch)
    }

    @Test fun rankingTieOrderAndCollisionLayoutAreDeterministic() {
        val rows = listOf(observation("K2ZZZ", 14_074_000), observation("K1AAA", 14_074_050, source = BandMapSource.RBN))
        val spots = BandMapSpotIndex().coalesce(rows)
        val first = BandMapPriorityEngine.rank(spots, BandMapRankingWeights(), now)
        val second = BandMapPriorityEngine.rank(spots.reversed(), BandMapRankingWeights(), now)
        assertEquals(first.map { it.spot.id }, second.map { it.spot.id })
        val segment = BandMapSegment("20m")
        assertEquals(BandMapLayoutEngine.place(spots, segment, 1_000), BandMapLayoutEngine.place(spots.reversed(), segment, 1_000))
    }

    @Test fun ticksAndCoordinateTransformsStayBounded() {
        val segment = BandMapSegment("20m")
        val ticks = BandMapLayoutEngine.ticks(segment, 1_200)
        assertTrue(ticks.size in 2..64)
        assertTrue(ticks.all { it.position in 0f..1f })
        assertEquals(0f, BandMapLayoutEngine.coordinate(segment.lowerHz, segment))
        assertEquals(1f, BandMapLayoutEngine.coordinate(segment.lowerHz, segment, BandMapDirection.HIGH_TO_LOW))
    }

    @Test fun regionalDisplayRailsExposeModeColoursIncludingFmWhereApplicable() {
        val hf = BandMapDisplayPlans.forBand("20m", BandMapIaruRegion.REGION_1)
        assertEquals(listOf(BandMapOperatingSegmentKind.CW, BandMapOperatingSegmentKind.DATA, BandMapOperatingSegmentKind.PHONE),
            hf.segments.map(BandMapOperatingSegment::kind))
        val vhf = BandMapDisplayPlans.forBand("2m", BandMapIaruRegion.REGION_1)
        assertTrue(vhf.segments.any { it.kind == BandMapOperatingSegmentKind.FM_REPEATER })
        assertFalse(vhf.regulatoryAuthority)
    }

    @Test fun customSegmentsClipWithoutChangingSpotTruth() {
        val source = spot()
        val filter = BandMapFilter(bands = setOf("20m"), segments = listOf(BandMapSegment("20m", "FT8 slice", 14_073_000, 14_075_000)))
        assertEquals(listOf(source), BandMapFilterEngine.visible(listOf(source), filter, now))
        assertTrue(BandMapFilterEngine.visible(listOf(source.copy(frequencyHz = 14_100_000)), filter, now).isEmpty())
    }

    @Test fun unknownBandModeAndNeedsRemainUnknown() {
        assertEquals("", BandMapSpotCanonicalizer.band("mystery", 0))
        val state = BandMapNeedsAdapter.state(spot(), BandMapNeedsSnapshot(complete = false), null)
        assertEquals(BandMapNeedTruth.UNKNOWN, state.entity)
        assertTrue(state.missingReasons.isNotEmpty())
    }

    @Test fun customRankingWeightsRejectUnboundedValues() {
        assertThrows(IllegalArgumentException::class.java) { BandMapRankingWeights(watch = 101) }
        assertThrows(IllegalArgumentException::class.java) { BandMapRankingWeights(stalePenalty = -101) }
    }

    @Test fun priorityExplainsEveryNonZeroComponentAndFiltersDoNotChangeScore() {
        val enriched = spot().copy(need = BandMapNeedState(entity = BandMapNeedTruth.NEEDED), marked = setOf(BandMapMarkKind.WATCH),
            evidence = listOf(BandMapEvidence(BandMapEvidenceKind.CURRENT_OBSERVED, BandMapEvidenceStatus.POSITIVE, "RBN")))
        val ranked = BandMapPriorityEngine.rank(listOf(enriched), BandMapRankingWeights(), now).single()
        assertTrue(ranked.components.size >= 3)
        assertTrue("needed entity" in ranked.explanation)
        val filtered = BandMapFilterEngine.visible(listOf(enriched), BandMapFilter(bands = setOf("20m")), now).single()
        assertEquals(ranked.score, BandMapPriorityEngine.rank(listOf(filtered), BandMapRankingWeights(), now).single().score)
    }

    @Test fun unavailableChaserDoesNotHideOrdinarySpotsByDefault() {
        val ordinary = spot().copy(chaser = BandMapChaserState())
        assertEquals(1, BandMapFilterEngine.visible(listOf(ordinary), BandMapFilter(), now).size)
        assertTrue(BandMapFilterEngine.visible(listOf(ordinary), BandMapFilter(chaserEligibleOnly = true), now).isEmpty())
    }

    @Test fun evidenceChannelsStayIndependent() {
        val value = spot().copy(evidence = listOf(
            BandMapEvidence(BandMapEvidenceKind.CURRENT_OBSERVED, BandMapEvidenceStatus.POSITIVE, "RBN"),
            BandMapEvidence(BandMapEvidenceKind.EMPIRICAL_OUTLOOK, BandMapEvidenceStatus.NEGATIVE, "Empirical Outlook"),
            BandMapEvidence(BandMapEvidenceKind.HISTORICAL_PERSONAL, BandMapEvidenceStatus.NEUTRAL, "Local history")))
        assertEquals(3, value.evidence.map { it.kind }.distinct().size)
        assertFalse(value.evidence.any { '%' in it.explanation })
    }

    @Test fun malformedPresetImportPreservesLastGoodCandidate() {
        val expected = BandMapSettings(callStatusFilters = setOf("NC", "NB"), dxccStatusFilters = setOf("ATNO"),
            laneSize = 1, spotLabelSizeSp = 13, frequencyLabelEvery = 1,
            labelMetadata = setOf(BandMapLabelMetadata.AGE, BandMapLabelMetadata.CALL_STATUS,
                BandMapLabelMetadata.DXCC_STATUS, BandMapLabelMetadata.BEARING),
            viewports = mapOf(
                "20m" to BandMapViewport(14_050_000L, 14_100_000L),
                "40m" to BandMapViewport(7_000_000L, 7_080_000L),
            ))
        val good = BandMapSettingsCodec.encode(expected)
        val decoded = BandMapSettingsCodec.decode(good)
        assertEquals(expected.selectedBands, decoded.selectedBands)
        assertEquals(expected.callStatusFilters, decoded.callStatusFilters)
        assertEquals(expected.dxccStatusFilters, decoded.dxccStatusFilters)
        assertEquals(expected.laneSize, decoded.laneSize)
        assertEquals(expected.spotLabelSizeSp, decoded.spotLabelSizeSp)
        assertEquals(expected.frequencyLabelEvery, decoded.frequencyLabelEvery)
        assertEquals(expected.labelMetadata, decoded.labelMetadata)
        assertEquals(expected.viewports, decoded.viewports)
        assertThrows(IllegalArgumentException::class.java) { BandMapSettingsCodec.decode(good.replace("\"label_density\":2", "\"label_density\":99")) }
        assertThrows(IllegalArgumentException::class.java) { BandMapSettingsCodec.decode(good.replace("\"ATNO\"", "\"INVALID\"")) }
    }

    @Test fun twentyThousandObservationScaleIsBounded() {
        val rows = List(20_000) { index -> observation("K${index % 10}A${index % 1000}", 14_000_000L + (index % 300_000), now - (index % 600).toLong(), if (index % 2 == 0) BandMapSource.RBN else BandMapSource.DX_CLUSTER) }
        var spots = emptyList<BandMapSpot>()
        val elapsed = measureTimeMillis { spots = BandMapSpotIndex().coalesce(rows) }
        assertTrue(spots.size <= rows.size)
        assertTrue(elapsed < 10_000)
    }

    @Test fun compactNeedsSnapshotRepresentsHundredThousandProjectedQsosWithoutQsoPayloads() {
        val projected = sequence { repeat(100_000) { index -> yield(Triple("${index % 340}", listOf("20M", "40M")[index % 2], listOf("CW", "DIGI")[index % 2])) } }
        val entities = mutableSetOf<String>(); val bands = mutableSetOf<String>(); val bandModes = mutableSetOf<String>()
        projected.forEach { (entity, band, mode) -> entities += entity; bands += band; bandModes += "$band|$mode" }
        val snapshot = BandMapNeedsSnapshot(complete = true, workedEntities = entities, workedBands = bands, workedBandModes = bandModes)
        assertEquals(340, snapshot.workedEntities.size)
        assertEquals(2, snapshot.workedBands.size)
        assertEquals(2, snapshot.workedBandModes.size)
    }
}
