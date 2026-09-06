package app.shackcq.mobile.dxchaser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DxChaserEngineTest {
    private fun decode(call: String, epoch: Long, snr: Int = -10, needed: Boolean = false) = DxChaserLocalDecode(
        "d-$call-$epoch", "live", "slot", epoch * 1_000, epoch, DxChaserDecodeSource.LIVE_CAPTURE, true,
        "FT8", "20m", 14_074_000, 1_000, call, call, "FN31", "291", snr, "CQ $call FN31",
        DxChaserMessageType.CQ, needs = if (needed) DxChaserNeedFacts(mapOf(DxChaserNeedDimension.ATNO to DxChaserNeedState.NEEDED)) else DxChaserNeedFacts(),
        stationProfileId = "station", radioIdentity = "radio")

    private fun input(generation: Long, now: Long, rows: List<DxChaserLocalDecode>, band: String = "20m",
        frequency: Long = 14_074_000, evidence: List<DxChaserProviderEvidence> = emptyList()) =
        DxChaserInputSnapshot(generation, true, now, "station", "OM0RX", "JN88TQ", "radio", "KX3", frequency,
            band, "FT8", "live", now * 1_000, DxChaserSafetySnapshot(true, true, true, true), rows, evidence)

    private fun started(mode: DxChaserMode = DxChaserMode.CHASE_SESSION, now: Long = 1_000) =
        DxChaserEngine.reduce(DxChaserEngineState(), DxChaserEvent.OperatorStart(mode, "session", now)).state

    @Test fun preEngagementPreemptionRequiresDwellAndHysteresis() {
        val settings = DxChaserSettingsDocument(minimumTargetDwellSeconds = 30, preemptionHysteresisPercent = 25)
        var state = DxChaserEngine.reduce(started(), DxChaserEvent.SnapshotUpdated(input(1, 1_000,
            listOf(decode("K1AAA", 999))), settings)).state
        assertEquals("K1AAA", state.session.target?.candidate?.baseCallsign)
        state = DxChaserEngine.reduce(state, DxChaserEvent.SnapshotUpdated(input(2, 1_010,
            listOf(decode("K1AAA", 1_009), decode("K1BBB", 1_009, needed = true))), settings)).state
        assertEquals("K1AAA", state.session.target?.candidate?.baseCallsign)
        state = DxChaserEngine.reduce(state, DxChaserEvent.SnapshotUpdated(input(3, 1_040,
            listOf(decode("K1AAA", 1_039), decode("K1BBB", 1_039, needed = true))), settings)).state
        assertEquals("K1BBB", state.session.target?.candidate?.baseCallsign)
    }

    @Test fun engagedTargetCannotBePreempted() {
        val settings = DxChaserSettingsDocument(minimumTargetDwellSeconds = 0)
        var state = DxChaserEngine.reduce(started(), DxChaserEvent.SnapshotUpdated(input(1, 1_000,
            listOf(decode("K1AAA", 999))), settings)).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.RemoteEngaged(1_001)).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.SnapshotUpdated(input(2, 1_040,
            listOf(decode("K1AAA", 1_039), decode("K1BBB", 1_039, needed = true))), settings)).state
        assertEquals("K1AAA", state.session.target?.candidate?.baseCallsign)
        assertTrue(state.session.target?.engaged == true)
    }

    @Test fun allAttemptAndSessionLimitsRemainFinite() {
        val settings = DxChaserSettingsDocument(normalAttemptLimit = 999, scarceAttemptLimit = 999,
            atnoAttemptLimit = 999, sessionTimeoutSeconds = Long.MAX_VALUE).clamped()
        assertEquals(10, settings.normalAttemptLimit); assertEquals(12, settings.scarceAttemptLimit)
        assertEquals(20, settings.atnoAttemptLimit); assertEquals(7_200, settings.sessionTimeoutSeconds)
        var state = DxChaserEngine.reduce(started(), DxChaserEvent.SnapshotUpdated(input(1, 1_000,
            listOf(decode("K1AAA", 999))), DxChaserSettingsDocument(normalAttemptLimit = 1))).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.AttemptCompleted(1_001)).state
        assertNull(state.session.target)
        assertTrue(state.cooldowns.any { it.reason == "ATTEMPT_LIMIT" })
    }

    @Test fun crossBandEvidenceProducesReviewOnlyAndStillNeedsNewLocalDecode() {
        val oneSpotSource = listOf(
            DxChaserProviderEvidence("r", "K1ABC", "40m", DxChaserEvidenceKind.RBN, DxChaserEvidenceState.CURRENT, 999, "RBN"),
            DxChaserProviderEvidence("h", "K1ABC", "40m", DxChaserEvidenceKind.BAND_HEALTH, DxChaserEvidenceState.CURRENT, 999, "Band Health"))
        val start = started(DxChaserMode.ASSIST)
        assertTrue(DxChaserEngine.reduce(start, DxChaserEvent.SnapshotUpdated(input(1, 1_000,
            listOf(decode("K1ABC", 999, needed = true)), evidence = oneSpotSource), DxChaserSettingsDocument())).state.crossBand.isEmpty())
        val evidence = oneSpotSource + DxChaserProviderEvidence("p", "K1ABC", "40m", DxChaserEvidenceKind.PSK,
            DxChaserEvidenceState.CURRENT, 999, "PSK", frequencyHz = 7_074_000)
        var transition = DxChaserEngine.reduce(started(DxChaserMode.ASSIST), DxChaserEvent.SnapshotUpdated(
            input(1, 1_000, listOf(decode("K1ABC", 999, needed = true)), evidence = evidence), DxChaserSettingsDocument()))
        val opportunity = transition.state.crossBand.single()
        transition = DxChaserEngine.reduce(transition.state, DxChaserEvent.CrossBandReview(opportunity, 1, 1_001))
        assertEquals(listOf(DxChaserActionType.REQUEST_RECEIVE_BAND_REVIEW), transition.actions.map { it.type })
        assertTrue(transition.state.cooldowns.any { it.reason == "CROSS_BAND_REVIEW" })
        transition = DxChaserEngine.reduce(transition.state, DxChaserEvent.BandReviewAccepted(1_002))
        assertEquals(DxChaserSessionState.MONITORING, transition.state.session.state)
        val chase = started(DxChaserMode.CHASE_SESSION, 1_002)
        val noLocal = DxChaserEngine.reduce(chase, DxChaserEvent.SnapshotUpdated(
            input(2, 1_003, emptyList(), "40m", 7_074_000, evidence), DxChaserSettingsDocument()))
        assertFalse(noLocal.actions.any { it.type == DxChaserActionType.PREPARE_FT_CALL })
    }

    @Test fun backgroundRadioRouteModeFrequencyAndStationLossStopChase() {
        listOf("BACKGROUND", "RADIO_CHANGED", "ROUTE_LOST", "MODE_CHANGED", "FREQUENCY_CHANGED", "STATION_CHANGED").forEach { reason ->
            val transition = DxChaserEngine.reduce(started(), DxChaserEvent.ContextLost(reason, 1_001))
            assertEquals(DxChaserSessionState.STOPPED, transition.state.session.state)
            assertEquals(DxChaserActionType.STOP_CHASE, transition.actions.single().type)
            assertNull(transition.state.session.pendingIntent)
        }
    }

    @Test fun dryRunEmitsNoOperationalIntent() {
        val transition = DxChaserEngine.reduce(started(DxChaserMode.DRY_RUN), DxChaserEvent.SnapshotUpdated(
            input(1, 1_000, listOf(decode("K1ABC", 999))), DxChaserSettingsDocument()))
        assertEquals(listOf(DxChaserActionType.RECORD_DRY_RUN), transition.actions.map { it.type })
        assertFalse(transition.actions.any { it.type == DxChaserActionType.PREPARE_FT_CALL })
    }

    @Test fun deterministicFullSessionRetainsLockAndUsesCanonicalCompletionFeedback() {
        val settings = DxChaserSettingsDocument(minimumTargetDwellSeconds = 0)
        var transition = DxChaserEngine.reduce(started(), DxChaserEvent.SnapshotUpdated(
            input(1, 1_000, listOf(decode("K1AAA", 999))), settings))
        assertEquals(DxChaserActionType.PREPARE_FT_CALL, transition.actions.single().type)
        var state = DxChaserEngine.reduce(transition.state, DxChaserEvent.PrepareAccepted(1_001)).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.SequenceStarted(1_002)).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.RemoteEngaged(1_003)).state
        state = DxChaserEngine.reduce(state, DxChaserEvent.SnapshotUpdated(input(2, 1_040,
            listOf(decode("K1AAA", 1_039), decode("K1BBB", 1_039, needed = true))), settings)).state
        assertEquals("K1AAA", state.session.target?.candidate?.baseCallsign)
        transition = DxChaserEngine.reduce(state, DxChaserEvent.QsoCompleted(1_050))
        assertEquals(1, transition.state.session.completedQsos)
        assertNull(transition.state.session.target)
        assertTrue(transition.state.cooldowns.any { it.reason == "COMPLETED_QSO" })
        assertTrue(transition.actions.isEmpty())
        val next = DxChaserEngine.reduce(transition.state, DxChaserEvent.SnapshotUpdated(input(3, 1_051,
            listOf(decode("K1AAA", 1_050))), settings)).state
        assertFalse(next.ranked.first { it.baseCallsign == "K1AAA" }.eligible)
    }

    @Test fun wholeSessionTimeoutStopsAndClearsPendingIntent() {
        var state = DxChaserEngine.reduce(started(), DxChaserEvent.SnapshotUpdated(input(1, 1_000,
            listOf(decode("K1AAA", 999))), DxChaserSettingsDocument(sessionTimeoutSeconds = 60))).state
        assertTrue(state.session.pendingIntent != null)
        val transition = DxChaserEngine.reduce(state, DxChaserEvent.SnapshotUpdated(input(2, 1_061,
            listOf(decode("K1AAA", 1_060))), DxChaserSettingsDocument(sessionTimeoutSeconds = 60)))
        assertEquals(DxChaserSessionState.STOPPED, transition.state.session.state)
        assertNull(transition.state.session.pendingIntent)
    }
}
