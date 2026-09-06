package app.shackcq.mobile.dxchaser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DxChaserScorerTest {
    private val settings = DxChaserSettingsDocument()

    private fun decode(
        call: String = "K1ABC", source: DxChaserDecodeSource = DxChaserDecodeSource.LIVE_CAPTURE,
        mode: String = "FT8", messageType: DxChaserMessageType = DxChaserMessageType.CQ,
        epoch: Long = 990, snr: Int = -10, count: Int = 1, needs: DxChaserNeedFacts = DxChaserNeedFacts(),
    ) = DxChaserLocalDecode("decode-$call-$source", "live", "slot", 990_000, epoch, source, true,
        mode, "20m", 14_074_000, 1_200, call, call, "FN31", "291", snr, "CQ $call FN31", messageType,
        count, true, false, "station", "radio", needs)

    private fun snapshot(
        rows: List<DxChaserLocalDecode> = listOf(decode()), mode: String = "FT8",
        evidence: List<DxChaserProviderEvidence> = emptyList(), rarity: Map<String, DxChaserRarity> = emptyMap(),
        cooldowns: List<DxChaserCooldownSnapshot> = emptyList(), now: Long = 1_000,
    ) = DxChaserInputSnapshot(1, true, now, "station", "OM0RX", "JN88TQ", "radio", "KX3",
        14_074_000, "20m", mode, "live", 990_000, DxChaserSafetySnapshot(true, true, true, true),
        rows, evidence, rarity, cooldowns)

    @Test fun externalSpotWithoutLocalDecodeNeverCreatesCandidate() {
        val evidence = listOf(DxChaserProviderEvidence("e", "K1ABC", "20m", DxChaserEvidenceKind.CLUSTER,
            DxChaserEvidenceState.CURRENT, 999, "cluster"))
        assertTrue(DxChaserScorer.rank(snapshot(emptyList(), evidence = evidence), settings).isEmpty())
    }

    @Test fun liveLocalFt8CqIsEligible() {
        assertTrue(DxChaserScorer.rank(snapshot(), settings).single().eligible)
    }

    @Test fun liveLocalFt4DirectedCqIsEligible() {
        val row = decode(mode = "FT4", messageType = DxChaserMessageType.DIRECTED_CQ)
        assertTrue(DxChaserScorer.rank(snapshot(listOf(row), mode = "FT4"), settings).single().eligible)
    }

    @Test fun referenceCompanionHistoryAndLegacyTimingAreNotEligible() {
        val sources = listOf(DxChaserDecodeSource.REFERENCE_RECORDING, DxChaserDecodeSource.COMPANION,
            DxChaserDecodeSource.HISTORY, DxChaserDecodeSource.LEGACY_TIMING)
        sources.forEach { source -> assertFalse(DxChaserScorer.rank(snapshot(listOf(decode(source = source))), settings).single().eligible) }
    }

    @Test fun bystanderMessageIsNotSelected() {
        assertFalse(DxChaserScorer.rank(snapshot(listOf(decode(messageType = DxChaserMessageType.BYSTANDER))), settings).single().eligible)
    }

    @Test fun directedMessageToOperatorOutranksEqualCq() {
        val cq = decode("K1AAA")
        val directed = decode("K1BBB", messageType = DxChaserMessageType.ADDRESSED_TO_OPERATOR)
        assertEquals("K1BBB", DxChaserScorer.rank(snapshot(listOf(cq, directed)), settings).first().baseCallsign)
    }

    @Test fun atnoAndNeedsUseProvidedAuthority() {
        val needed = DxChaserNeedFacts(mapOf(DxChaserNeedDimension.ATNO to DxChaserNeedState.NEEDED,
            DxChaserNeedDimension.BAND_ENTITY to DxChaserNeedState.NEEDED))
        val scored = DxChaserScorer.rank(snapshot(listOf(decode(needs = needed))), settings).single()
        assertTrue("ATNO" in scored.needReasons)
        assertTrue(scored.breakdown.value >= 145)
    }

    @Test fun missingNeedsDataIsNotInterpretedAsNeeded() {
        val scored = DxChaserScorer.rank(snapshot(), settings).single()
        assertTrue(scored.needReasons.isEmpty())
        assertFalse(scored.breakdown.reasons.any { it == "ATNO" || it.startsWith("NEEDED_") })
    }

    @Test fun missingRarityDoesNotFabricateRank() {
        val scored = DxChaserScorer.rank(snapshot(), settings).single()
        assertEquals(DxChaserRarityOrigin.UNAVAILABLE, scored.rarity.origin)
        assertEquals(0, scored.breakdown.rarity)
    }

    @Test fun validatedManualRarityChangesOnlyRarityComponent() {
        val base = DxChaserScorer.rank(snapshot(), settings).single()
        val rarity = DxChaserRarity("291", rank = 1, origin = DxChaserRarityOrigin.USER_IMPORTED,
            sourceLabel = "operator", sourceDate = "2026-08-01", digest = "abc")
        val withRarity = DxChaserScorer.rank(snapshot(rarity = mapOf("291" to rarity)), settings).single()
        assertEquals(base.breakdown.value, withRarity.breakdown.value)
        assertEquals(base.breakdown.localQuality, withRarity.breakdown.localQuality)
        assertEquals(withRarity.breakdown.rarity, withRarity.breakdown.total - base.breakdown.total)
    }

    @Test fun staleExternalEvidenceAddsNoCurrentSupport() {
        val evidence = DxChaserProviderEvidence("e", "K1ABC", "20m", DxChaserEvidenceKind.RBN,
            DxChaserEvidenceState.STALE, 100, "RBN")
        val scored = DxChaserScorer.rank(snapshot(evidence = listOf(evidence)), settings).single()
        assertTrue(scored.eligible)
        assertEquals(0, scored.breakdown.currentObservedSupport)
    }

    @Test fun repeatConfidenceIsCapped() {
        val capped = DxChaserScorer.rank(snapshot(listOf(decode(count = settings.repeatDecodeCap))), settings).single()
        val excessive = DxChaserScorer.rank(snapshot(listOf(decode(count = 999))), settings).single()
        assertEquals(capped.breakdown.localQuality, excessive.breakdown.localQuality)
        assertEquals(settings.repeatDecodeCap, excessive.decodeCount)
    }

    @Test fun rankingTieBreakIsDeterministicAndCooldownBlocksSelection() {
        val rows = listOf(decode("K1BBB"), decode("K1AAA"))
        val cooldown = DxChaserCooldownSnapshot("K1AAA", "20m", "FT8", "ATTEMPT", 2_000)
        val first = DxChaserScorer.rank(snapshot(rows), settings)
        val second = DxChaserScorer.rank(snapshot(rows), settings)
        assertEquals(listOf("K1AAA", "K1BBB"), first.map { it.baseCallsign })
        assertEquals(first, second)
        assertFalse(DxChaserScorer.rank(snapshot(rows, cooldowns = listOf(cooldown)), settings)
            .first { it.baseCallsign == "K1AAA" }.eligible)
    }
}
