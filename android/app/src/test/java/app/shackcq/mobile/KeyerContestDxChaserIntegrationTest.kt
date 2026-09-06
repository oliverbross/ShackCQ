// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import app.shackcq.mobile.contest.*
import app.shackcq.mobile.dxchaser.*
import app.shackcq.mobile.keyer.*
import org.junit.Assert.*
import org.junit.Test

class KeyerContestDxChaserIntegrationTest {
    @Test fun runMapsToContestRun() = assertEquals(KeyerOperatingRole.CONTEST_RUN,
        contestKeyerRole(ContestOperatingRole.RUN))

    @Test fun searchAndPounceMapsToContestSandP() = assertEquals(KeyerOperatingRole.CONTEST_S_AND_P,
        contestKeyerRole(ContestOperatingRole.SEARCH_AND_POUNCE))

    @Test fun cwMapsToCwAndSsbMapsToVoice() {
        assertEquals(KeyerMode.CW, contestKeyerMode(ContestMode.CW))
        assertEquals(KeyerMode.VOICE, contestKeyerMode(ContestMode.SSB))
    }

    @Test fun digitalAndMixedHaveNoKeyerFallback() {
        assertNull(contestKeyerMode(ContestMode.DIGITAL))
        assertNull(contestKeyerMode(ContestMode.MIXED))
    }

    @Test fun serialReservationOwnerIsBoundToQsoAndGeneration() {
        assertEquals("qso:q1:g42", ContestKeyerAdapter.reservationOwner("q1", 42))
        assertNotEquals(ContestKeyerAdapter.reservationOwner("q1", 42), ContestKeyerAdapter.reservationOwner("q1", 43))
    }

    @Test fun chaseSafetyRequiresOperatorTxEnable() {
        assertFalse(readySafety().copy(txEnabledByOperator = false).preparationPermitted)
    }

    @Test fun chaseSafetyRequiresLocalModemAndConfirmedRx() {
        assertFalse(readySafety().copy(localModemAuthority = false).preparationPermitted)
        assertFalse(readySafety().copy(rxConfirmed = false).preparationPermitted)
    }

    @Test fun chaseSafetyRejectsKeyerAndContestConflicts() {
        assertFalse(readySafety().copy(keyerIdle = false).preparationPermitted)
        assertFalse(readySafety().copy(contestCompatible = false).preparationPermitted)
    }

    @Test fun allExplicitInterlocksPermitPreparation() = assertTrue(readySafety().preparationPermitted)

    @Test fun incompatibleRunningContestMakesCandidateIneligible() {
        val result = DxChaserScorer.score(snapshot(contest = DxChaserContestSnapshot("c", true, false)), settings(), decode())
        assertFalse(result.eligible)
        assertTrue("CONTEST_MODE_INCOMPATIBLE" in result.ineligibleReasons)
    }

    @Test fun contestDuplicateMakesCandidateIneligible() {
        val context = DxChaserContestOpportunity(validBandMode = true, duplicate = true)
        val result = DxChaserScorer.score(snapshot(contest = DxChaserContestSnapshot("c", true, true, mapOf("K1ABC" to context))), settings(), decode())
        assertTrue("CONTEST_DUPLICATE" in result.ineligibleReasons)
    }

    @Test fun trustedPeerClaimMakesCandidateIneligibleAndVisible() {
        val context = DxChaserContestOpportunity(validBandMode = true, duplicate = false, claimedBy = "trusted-node")
        val result = DxChaserScorer.score(snapshot(contest = DxChaserContestSnapshot("c", true, true, mapOf("K1ABC" to context))), settings(), decode())
        assertTrue("CLAIMED_BY_TRUSTED_PEER" in result.ineligibleReasons)
    }

    @Test fun contestMultiplierAddsBoundedTransparentValue() {
        val base = DxChaserScorer.score(snapshot(), settings(), decode())
        val context = DxChaserContestOpportunity(validBandMode = true, duplicate = false, newMultipliers = setOf("DXCC", "WPX"))
        val adjusted = DxChaserScorer.score(snapshot(contest = DxChaserContestSnapshot("c", true, true, mapOf("K1ABC" to context))), settings(), decode())
        assertEquals(base.breakdown.total + 24, adjusted.breakdown.total)
        assertTrue("CONTEST_NEW_MULTIPLIER" in adjusted.breakdown.reasons)
    }

    private fun readySafety() = DxChaserSafetySnapshot(true, true, true, true, false, false,
        foreground = true, digiModeEligible = true, localModemAuthority = true, txEnabledByOperator = true,
        contestCompatible = true, keyerIdle = true, rxConfirmed = true)

    private fun settings() = DxChaserSettingsDocument(selectedBands = setOf("20m"), selectedModes = setOf("FT8"), minimumSnr = -30)

    private fun snapshot(contest: DxChaserContestSnapshot = DxChaserContestSnapshot()) = DxChaserInputSnapshot(
        generation = 1, foreground = true, nowEpochSeconds = 1_010, stationProfileId = "station",
        stationCallsign = "OM0RX", stationGrid = "JN88", radioIdentity = "radio", radioFamily = "KX3",
        receiveFrequencyHz = 14_074_000, band = "20m", mode = "FT8", digiSessionId = "session",
        capturedSlotStartMillis = 1_000, safety = readySafety(), localDecodes = listOf(decode()), contest = contest)

    private fun decode() = DxChaserLocalDecode("d1", "session", "session:1000:FT8", 1_000, 1_000,
        DxChaserDecodeSource.LIVE_CAPTURE, true, "FT8", "20m", 14_074_000, 1_000,
        "K1ABC", grid = "FN31", entity = "291", snr = -10, message = "CQ K1ABC FN31",
        messageType = DxChaserMessageType.CQ, stationProfileId = "station", radioIdentity = "radio")
}
