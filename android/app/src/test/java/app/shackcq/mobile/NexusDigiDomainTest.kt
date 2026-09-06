package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusDigiDomainTest {
    @Test fun everyModeHasOneVisibleTruthfulCapability() {
        assertEquals(DigiMode.entries.size, DigiCapabilities.all.map { it.mode }.distinct().size)
        assertTrue(DigiCapabilities.all.all { it.visible && it.rxEngine })
    }

    @Test fun onlyFt8AndFt4ClaimAutomaticSequencing() {
        val automatic = DigiCapabilities.all.filter { it.sequencer == DigiSequencerSupport.FT8_FT4 }.map { it.mode }.toSet()
        assertEquals(setOf(DigiMode.FT8, DigiMode.FT4), automatic)
    }

    @Test fun clickToNetBehaviorMatchesModeFamily() {
        assertEquals(DigiWaterfallBehavior.MARK_SPACE_CENTER, DigiCapabilities.forMode(DigiMode.RTTY).waterfall)
        assertEquals(DigiWaterfallBehavior.CARRIER, DigiCapabilities.forMode(DigiMode.PSK31).waterfall)
        assertEquals(DigiWaterfallBehavior.CW_PITCH, DigiCapabilities.forMode(DigiMode.CW).waterfall)
    }

    @Test fun parserRetainsPortableDisplayAndLocksBaseCall() {
        val parsed = DigiFtParser.parse("OM0RX EA8/K1ABC -12")
        assertEquals("EA8/K1ABC", parsed.from)
        assertEquals("K1ABC", DigiFtParser.baseCall(parsed.from))
        assertEquals(FtMessageKind.REPORT, parsed.kind)
    }

    @Test fun cqParserFindsCallAndGrid() {
        val parsed = DigiFtParser.parse("CQ DX K1ABC FN31")
        assertEquals("K1ABC", parsed.from)
        assertEquals("FN31", parsed.grid)
        assertEquals(FtMessageKind.CQ, parsed.kind)
    }

    @Test fun fullSearchAndPounceExchangeUsesSelectedSnrAndCompletes() {
        val engine = engine()
        assertEquals(FtTxMessageKind.GRID, startSp(engine).messageKind)
        assertEquals(FtExchangeState.WAIT_REPORT, tx(engine, 30_000).let { engine.snapshot.state })
        assertEquals(FtTxMessageKind.R_REPORT, decode(engine, "OM0RX EA8/K1ABC -07", -4f, 45_000).messageKind)
        assertEquals(FtTxMessageKind.FINAL_73, tx(engine, 60_000).let {
            decode(engine, "OM0RX EA8/K1ABC RR73", -3f, 75_000).messageKind
        })
        assertEquals(FtEngineActionKind.COMPLETE_DRAFT, tx(engine, 90_000).kind)
        assertEquals("-12", engine.snapshot.sentReport)
        assertEquals("-07", engine.snapshot.receivedReport)
        assertTrue(engine.snapshot.completeDraftEligible)
    }

    @Test fun fullCqRunnerExchangeUsesCallerSnrAndCompletes() {
        val engine = engine()
        engine.operatorStartCq("FT8", 14_074_000, "s", 100, 0, 3, false, 3)
        tx(engine, 30_000)
        assertEquals(FtTxMessageKind.REPORT, decode(engine, "OM0RX K1ABC FN31", -11.6f, 45_000).messageKind)
        tx(engine, 60_000)
        assertEquals(FtTxMessageKind.RR73, decode(engine, "OM0RX K1ABC R-08", -5f, 75_000).messageKind)
        tx(engine, 90_000)
        assertEquals(FtEngineActionKind.COMPLETE_DRAFT, decode(engine, "OM0RX K1ABC 73", -5f, 105_000).kind)
        assertEquals("-12", engine.snapshot.sentReport)
        assertEquals("-08", engine.snapshot.receivedReport)
    }

    @Test fun bystanderIsIgnoredAfterStationLock() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.NONE, decode(engine, "OM0RX W9XYZ -05", -5f, 45_000).kind)
        assertEquals("K1ABC", engine.snapshot.lockedCall)
    }

    @Test fun cqAcceptsOnlyMessagesAddressedToOperator() {
        val engine = engine(); engine.operatorStartCq("FT8", 14_074_000, "s", 100, 0, 3, false, 3); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.NONE, decode(engine, "OM1ABC K1ABC -10", -10f, 45_000).kind)
        assertEquals(FtTxMessageKind.REPORT, decode(engine, "OM0RX K1ABC -10", -10f, 45_000).messageKind)
    }

    @Test fun duplicateDecodeIsIdempotent() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        assertEquals(FtTxMessageKind.R_REPORT, decode(engine, "OM0RX K1ABC -07", -7f, 45_000).messageKind)
        assertEquals(FtEngineActionKind.NONE, decode(engine, "OM0RX K1ABC -07", -7f, 45_000).kind)
    }

    @Test fun wrongParityAndStaleSlotsAreIgnored() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.NONE, decode(engine, "OM0RX K1ABC -07", -7f, 30_000).kind)
        assertEquals(FtEngineActionKind.NONE, decode(engine, "OM0RX K1ABC -07", -7f, 60_000).kind)
    }

    @Test fun modeFrequencyAndSessionChangesAreRejected() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        val message = DigiFtParser.parse("OM0RX K1ABC -07")
        assertEquals(FtEngineActionKind.NONE, engine.decoded(FtDecodeInput(message, -7f, 45_000, "FT4", 14_074_000, "s"), 101).kind)
        assertEquals(FtEngineActionKind.NONE, engine.decoded(FtDecodeInput(message, -7f, 45_000, "FT8", 7_074_000, "s"), 101).kind)
        assertEquals(FtEngineActionKind.NONE, engine.decoded(FtDecodeInput(message, -7f, 45_000, "FT8", 14_074_000, "other"), 101).kind)
    }

    @Test fun failedOrRxUnconfirmedTxNeverAdvances() {
        listOf(DigiTxFailure.PTT_UNCONFIRMED, DigiTxFailure.AUDIO_WRITE_FAILED, DigiTxFailure.RX_UNCONFIRMED).forEach { failure ->
            val engine = engine(); startSp(engine)
            val action = engine.txOutcome(DigiTxOutcome.failed(failure, failure.name), 101)
            assertEquals(FtEngineActionKind.FAIL, action.kind)
            assertEquals(FtExchangeState.FAILED, engine.snapshot.state)
        }
    }

    @Test fun successfulOutcomeRequiresEveryPhysicalConfirmation() {
        assertFalse(DigiTxOutcome(true, true, true, false).successful)
        assertFalse(DigiTxOutcome(true, true, false, true).successful)
        assertTrue(DigiTxOutcome.success().successful)
    }

    @Test fun retryLimitZeroFailsWithoutRetransmission() {
        val engine = engine(); engine.operatorCallSelected("K1ABC", "FN31", -12f, 15_000, 15_000, "FT8", 14_074_000, "s", 100, 0); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.FAIL, engine.timeout(102).kind)
    }

    @Test fun retryLimitIsBoundedAndCountsRetries() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.RETRY, engine.timeout(102).kind)
        tx(engine, 60_000)
        assertEquals(1, engine.snapshot.retryCount)
    }

    @Test fun autoCqDefaultsOffAndStopsAfterOneUnansweredCq() {
        val engine = engine(); engine.operatorStartCq("FT8", 14_074_000, "s", 100, 0, 3, false, 3); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.FAIL, engine.timeout(102).kind)
    }

    @Test fun autoCqHonorsExplicitTransmissionLimit() {
        val engine = engine(); engine.operatorStartCq("FT8", 14_074_000, "s", 100, 0, 3, true, 2); tx(engine, 30_000)
        assertEquals(FtEngineActionKind.RETRY, engine.timeout(102).kind)
        tx(engine, 60_000)
        assertEquals(FtEngineActionKind.FAIL, engine.timeout(104).kind)
    }

    @Test fun reportFormattingRoundsClampsAndKeepsSign() {
        assertEquals("-50", formatFtReport(-99f))
        assertEquals("-01", formatFtReport(-1.4f))
        assertEquals("+00", formatFtReport(.4f))
        assertEquals("+49", formatFtReport(99f))
    }

    @Test fun rrrIsParsedAsExplicitAcknowledgement() {
        assertEquals(FtMessageKind.RRR, DigiFtParser.parse("OM0RX K1ABC RRR").kind)
    }

    @Test fun ft8SchedulerSelectsRequestedParity() {
        val plan = FtSlotScheduler.plan(15_000, 1, 31_000, 1_000, 120)
        assertEquals(1, plan.parity)
        assertEquals(45_000, plan.targetWallSlotStartMillis)
    }

    @Test fun ft4SchedulerUsesSevenPointFiveSecondPeriods() {
        val plan = FtSlotScheduler.plan(7_500, 0, 8_000, 1_000, 120)
        assertEquals(15_000, plan.targetWallSlotStartMillis)
        assertEquals(0, plan.parity)
    }

    @Test fun boundedLateStartAcceptsInsideAndRejectsOutsideWindow() {
        assertTrue(FtSlotScheduler.plan(15_000, 0, 30_080, 1_000, 120).lateStart)
        assertEquals(60_000, FtSlotScheduler.plan(15_000, 0, 30_121, 1_000, 120).targetWallSlotStartMillis)
    }

    @Test fun schedulerDetectsWallClockJumpBeforePtt() {
        val plan = FtSlotScheduler.plan(15_000, 1, 31_000, 1_000, 120)
        assertFalse(plan.remainsValid(45_500, 15_000))
    }

    @Test fun incompleteExchangeCannotProduceAutomaticDraft() {
        val engine = engine(); startSp(engine); tx(engine, 30_000)
        assertFalse(engine.snapshot.completeDraftEligible)
    }

    @Test fun legacyParitySettingsMigrateToOneAuthority() {
        assertEquals(1, DigiSettingsDocument.parse("{\"txPeriod\":\"ODD\",\"txFirst\":true}").ftTxParity)
        assertEquals(1, DigiSettingsDocument.parse("{\"txFirst\":false}").ftTxParity)
    }

    @Test fun retryLimitIsClampedToDocumentedRange() {
        assertEquals(10, DigiSettingsDocument.parse("{\"ftRetryLimit\":99}").ftRetryLimit)
        assertEquals(0, DigiSettingsDocument.parse("{\"ftRetryLimit\":-2}").ftRetryLimit)
    }

    @Test fun ft4ProductionSlotPrecisionKeepsOddParity() {
        assertEquals(1, slotParity(7_500L, 7_500L))
        assertEquals(1, slotParity(22_500L, 7_500L))
    }

    @Test fun wsjtFt4TimestampPreservesHalfSecond() {
        assertEquals(7_500, wsjtMillisSinceMidnight(7_500L))
        assertEquals(22_500, wsjtMillisSinceMidnight(86_422_500L))
    }

    @Test fun runtimeCountdownUsesOnlyMonotonicTarget() {
        val plan = FtSlotScheduler.plan(7_500L, 1, 1_000L, 5_000L, 120L)
        assertEquals(6_500L, FtRuntimeWait.remainingMillis(plan, 5_000L))
        assertEquals(1_500L, FtRuntimeWait.remainingMillis(plan, 10_000L))
    }

    @Test fun runtimeClockJumpIsDetectedSeparatelyFromCountdown() {
        val plan = FtSlotScheduler.plan(15_000L, 1, 1_000L, 2_000L, 120L)
        assertTrue(FtRuntimeWait.wallClockStable(plan, 2_000L, 3_000L))
        assertFalse(FtRuntimeWait.wallClockStable(plan, 2_400L, 3_000L))
        assertEquals(FtRuntimeWait.remainingMillis(plan, 3_000L), FtRuntimeWait.remainingMillis(plan, 3_000L))
    }

    @Test fun rxUnconfirmedLatchesAndNeverResumesDecoder() {
        val outcome = DigiTxOutcome.failed(DigiTxFailure.RX_UNCONFIRMED, pttConfirmed = true, pttAttempted = true)
        val decision = decidePostTxRecovery(true, outcome, false)
        assertFalse(decision.resumeDecoder)
        assertTrue(decision.latchRxUnconfirmed)
    }

    @Test fun failureBeforePttMayResumeExactPriorDecoder() {
        val outcome = DigiTxOutcome.failed(DigiTxFailure.ROUTE_MISSING, pttAttempted = false)
        val decision = decidePostTxRecovery(true, outcome, false)
        assertTrue(decision.resumeDecoder)
        assertFalse(decision.latchRxUnconfirmed)
    }

    @Test fun rxRecheckClearsLatchOnlyWhenConfirmed() {
        assertEquals(DigiTxPhase.SAFE, phaseAfterRxRecheck(true))
        assertEquals(DigiTxPhase.RX_UNCONFIRMED, phaseAfterRxRecheck(false))
    }

    @Test fun flexSuccessRequiresConfirmedPostStopReceiveState() {
        assertTrue(flexPostStopOutcome(FlexTxState.READY, 30_000L).successful)
        assertFalse(flexPostStopOutcome(FlexTxState.STOPPING, 30_000L).successful)
        assertFalse(flexPostStopOutcome(FlexTxState.TRANSMITTING, 30_000L).successful)
        assertEquals(DigiTxFailure.RX_UNCONFIRMED, flexPostStopOutcome(FlexTxState.FAULT, 30_000L).failure)
    }

    @Test fun referenceAndCompanionDecodesCannotAdvanceAutomation() {
        assertFalse(decodeEvent(DigiDecodeSource.REFERENCE_RECORDING).automaticFtEligible("FT4", 14_080_000, "session", 7_500L))
        assertFalse(decodeEvent(DigiDecodeSource.COMPANION).automaticFtEligible("FT4", 14_080_000, "session", 7_500L))
    }

    @Test fun legacyTimingCannotAdvanceAutomation() {
        assertFalse(decodeEvent(DigiDecodeSource.LIVE_CAPTURE, exact = false).automaticFtEligible("FT4", 14_080_000, "session", 7_500L))
    }

    @Test fun exactLiveCaptureRequiresModeFrequencyAndSession() {
        val event = decodeEvent(DigiDecodeSource.LIVE_CAPTURE)
        assertTrue(event.automaticFtEligible("FT4", 14_080_000, "session", 7_500L))
        assertFalse(event.automaticFtEligible("FT8", 14_080_000, "session", 7_500L))
        assertFalse(event.automaticFtEligible("FT4", 14_074_000, "session", 7_500L))
        assertFalse(event.automaticFtEligible("FT4", 14_080_000, "other", 7_500L))
    }

    @Test fun liveRedecodeRequiresExactActiveCapturedSlot() {
        val event = decodeEvent(DigiDecodeSource.REDECODE_LIVE_SLOT)
        assertTrue(event.automaticFtEligible("FT4", 14_080_000, "other-session", 7_500L))
        assertFalse(event.automaticFtEligible("FT4", 14_080_000, "other-session", 22_500L))
    }

    @Test fun automaticSequenceOffCannotQueueNextMessage() {
        assertFalse(shouldQueueFtAction(false, true, FtTxMessageKind.REPORT, "OM0RX K1ABC -10"))
        assertTrue(shouldQueueFtAction(true, true, FtTxMessageKind.REPORT, "OM0RX K1ABC -10"))
    }

    @Test fun autoCqCannotOperateWithoutAutomaticSequence() {
        assertFalse(effectiveAutoCq(false, true))
        assertFalse(DigiSettingsDocument.parse("{\"ftAutoSequence\":false,\"ftAutoCq\":true}").ftAutoCq)
        assertTrue(effectiveAutoCq(true, true))
    }

    private fun engine() = DigiFtExchangeEngine({ "OM0RX" }, { "JN88TQ" })

    private fun startSp(engine: DigiFtExchangeEngine) = engine.operatorCallSelected(
        "EA8/K1ABC", "FN31", -12f, 15_000, 15_000, "FT8", 14_074_000, "s", 100, 3,
    )

    private fun tx(engine: DigiFtExchangeEngine, slot: Long) =
        engine.txOutcome(DigiTxOutcome.success(slot), slot / 1_000)

    private fun decode(engine: DigiFtExchangeEngine, text: String, snr: Float, slot: Long) =
        engine.decoded(FtDecodeInput(DigiFtParser.parse(text), snr, slot, "FT8", 14_074_000, "s"), slot / 1_000)

    private fun decodeEvent(source: DigiDecodeSource, exact: Boolean = true) = DigiDecodeEvent(
        id = "decode", sessionId = "session", epoch = 100L, mode = "FT4", slotStartMillis = 7_500L,
        decodeSource = source, exactSlotTiming = exact, dialFrequencyHz = 14_080_000L,
        snr = -10f, dt = .1f, audioHz = 1_000f, text = "CQ K1ABC FN31", callsign = "K1ABC",
    )

    @Test fun wsjtPacketsUseCanonicalMagicSchemaAndType() {
        assertEquals(0, WsjtDatagram.headerType(WsjtDatagram.heartbeat("ShackCQ", "test")))
        assertEquals(1, WsjtDatagram.headerType(WsjtDatagram.status("ShackCQ", 14_074_000, "FT8", "", "", "FT8", false, false, true, 1_000, 1_000, "OM0RX", "JN88TQ")))
        assertEquals(2, WsjtDatagram.headerType(WsjtDatagram.decode("ShackCQ", true, 1_000, -10, .1, 250, "FT8", "CQ K1ABC FN31")))
    }

    @Test fun wsjtHeaderRejectsTruncationAndUnknownMagic() {
        assertEquals(null, WsjtDatagram.headerType(ByteArray(11)))
        val packet = WsjtDatagram.heartbeat("ShackCQ", "test")
        packet[0] = 0
        assertEquals(null, WsjtDatagram.headerType(packet))
    }

    @Test fun allTransmitPathsHaveFiniteHardCaps() {
        DigiCapabilities.all.filter { it.txEngine }.forEach {
            assertTrue(it.mode.name, it.maximumTxMillis in 1..600_000)
            assertNotNull(it.adifMode)
        }
    }
}
