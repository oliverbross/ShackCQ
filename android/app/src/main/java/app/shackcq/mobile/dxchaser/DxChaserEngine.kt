package app.shackcq.mobile.dxchaser

sealed interface DxChaserEvent {
    data class OperatorStart(val mode: DxChaserMode, val sessionId: String, val nowEpochSeconds: Long) : DxChaserEvent
    data class SnapshotUpdated(val snapshot: DxChaserInputSnapshot, val settings: DxChaserSettingsDocument) : DxChaserEvent
    data class CandidateSelected(val candidate: DxChaserCandidateSnapshot, val generation: Long, val nowEpochSeconds: Long) : DxChaserEvent
    data class PrepareAccepted(val nowEpochSeconds: Long) : DxChaserEvent
    data class PrepareRejected(val reason: String, val nowEpochSeconds: Long) : DxChaserEvent
    data class SequenceStarted(val nowEpochSeconds: Long) : DxChaserEvent
    data class AttemptCompleted(val nowEpochSeconds: Long) : DxChaserEvent
    data class RemoteEngaged(val nowEpochSeconds: Long) : DxChaserEvent
    data class QsoCompleted(val nowEpochSeconds: Long) : DxChaserEvent
    data class QsoFailed(val reason: String, val nowEpochSeconds: Long) : DxChaserEvent
    data class Stop(val reason: String, val nowEpochSeconds: Long) : DxChaserEvent
    data class ContextLost(val reason: String, val nowEpochSeconds: Long) : DxChaserEvent
    data class CrossBandReview(val opportunity: DxChaserCrossBandOpportunity, val generation: Long, val nowEpochSeconds: Long) : DxChaserEvent
    data class BandReviewAccepted(val nowEpochSeconds: Long) : DxChaserEvent
    data class BandReviewRejected(val nowEpochSeconds: Long) : DxChaserEvent
}

data class DxChaserEngineState(
    val session: DxChaserSessionSnapshot = DxChaserSessionSnapshot(),
    val ranked: List<DxChaserCandidateSnapshot> = emptyList(),
    val crossBand: List<DxChaserCrossBandOpportunity> = emptyList(),
    val generation: Long = 0,
    val cooldowns: List<DxChaserCooldownSnapshot> = emptyList(),
    val settings: DxChaserSettingsDocument = DxChaserSettingsDocument(),
    val providerFreshness: Map<String, DxChaserEvidenceState> = emptyMap(),
    val lastAction: DxChaserActionIntent? = null,
)

data class DxChaserTransition(val state: DxChaserEngineState, val actions: List<DxChaserActionIntent> = emptyList())

object DxChaserEngine {
    fun reduce(current: DxChaserEngineState, event: DxChaserEvent): DxChaserTransition {
        return when (event) {
        is DxChaserEvent.OperatorStart -> {
            val session = DxChaserSessionSnapshot(event.sessionId, event.mode, DxChaserSessionState.MONITORING,
                startedEpochSeconds = event.nowEpochSeconds, endsEpochSeconds = event.nowEpochSeconds + 1_800)
            DxChaserTransition(DxChaserEngineState(session = session))
        }
        is DxChaserEvent.SnapshotUpdated -> onSnapshot(current, event.snapshot, event.settings.clamped())
        is DxChaserEvent.CandidateSelected -> select(current, event.candidate, event.generation, event.nowEpochSeconds,
            current.session.mode == DxChaserMode.CHASE_SESSION)
        is DxChaserEvent.PrepareAccepted -> DxChaserTransition(current.copy(session = current.session.copy(
            state = DxChaserSessionState.WAITING_FOR_SEQUENCE_START, pendingIntent = null)))
        is DxChaserEvent.PrepareRejected -> failTarget(current, event.reason, event.nowEpochSeconds)
        is DxChaserEvent.SequenceStarted -> DxChaserTransition(current.copy(session = current.session.copy(state = DxChaserSessionState.CALLING)))
        is DxChaserEvent.AttemptCompleted -> {
            val target = current.session.target ?: return DxChaserTransition(current)
            val attempts = target.attempts + 1
            val limit = when {
                "ATNO" in target.candidate.needReasons -> current.settings.atnoAttemptLimit
                target.candidate.needReasons.isNotEmpty() -> current.settings.scarceAttemptLimit
                else -> current.settings.normalAttemptLimit
            }
            if (!target.engaged && attempts >= limit) return failTarget(current.copy(session = current.session.copy(
                target = target.copy(attempts = attempts))), "ATTEMPT_LIMIT", event.nowEpochSeconds)
            DxChaserTransition(current.copy(session = current.session.copy(state = DxChaserSessionState.WAITING_FOR_RESPONSE,
                target = target.copy(attempts = attempts))))
        }
        is DxChaserEvent.RemoteEngaged -> {
            val target = current.session.target ?: return DxChaserTransition(current)
            DxChaserTransition(current.copy(session = current.session.copy(state = DxChaserSessionState.ENGAGED,
                target = target.copy(engaged = true, timeoutEpochSeconds = event.nowEpochSeconds + current.settings.engagedTimeoutSeconds))))
        }
        is DxChaserEvent.QsoCompleted -> completeTarget(current, event.nowEpochSeconds)
        is DxChaserEvent.QsoFailed -> failTarget(current, event.reason, event.nowEpochSeconds)
        is DxChaserEvent.Stop -> stop(current, event.reason, event.nowEpochSeconds)
        is DxChaserEvent.ContextLost -> stop(current, event.reason, event.nowEpochSeconds)
        is DxChaserEvent.CrossBandReview -> {
            val intent = DxChaserActionIntent(DxChaserActionType.REQUEST_RECEIVE_BAND_REVIEW, current.session.id,
                event.generation, callsign = event.opportunity.callsign, band = event.opportunity.band,
                dialFrequencyHz = event.opportunity.suggestedDialFrequencyHz,
                reason = "RECEIVE REVIEW ONLY: ${event.opportunity.needReason}; a qualifying new local decode remains required")
            val cooldown = DxChaserCooldownSnapshot(event.opportunity.callsign.uppercase(), event.opportunity.band, "",
                "CROSS_BAND_REVIEW", event.nowEpochSeconds + current.settings.crossBandReviewCooldownSeconds)
            DxChaserTransition(current.copy(cooldowns = (current.cooldowns + cooldown).distinctBy {
                listOf(it.baseCallsign, it.band, it.mode, it.reason) }.takeLast(100), lastAction = intent), listOf(intent))
        }
        is DxChaserEvent.BandReviewAccepted -> DxChaserTransition(current.copy(session = current.session.copy(
            state = DxChaserSessionState.MONITORING)))
        is DxChaserEvent.BandReviewRejected -> DxChaserTransition(current.copy(session = current.session.copy(
            state = DxChaserSessionState.MONITORING)))
        }
    }

    private fun onSnapshot(current: DxChaserEngineState, snapshot: DxChaserInputSnapshot,
        settings: DxChaserSettingsDocument): DxChaserTransition {
        if (snapshot.generation < current.generation) return DxChaserTransition(current)
        if (current.session.state in setOf(DxChaserSessionState.DISABLED, DxChaserSessionState.COMPLETE,
                DxChaserSessionState.FAILED, DxChaserSessionState.STOPPED)) return DxChaserTransition(current.copy(generation = snapshot.generation))
        if (!snapshot.foreground) return stop(current, "BACKGROUND", snapshot.nowEpochSeconds)
        if (snapshot.nowEpochSeconds >= current.session.startedEpochSeconds + settings.sessionTimeoutSeconds) {
            return stop(current, "SESSION_TIMEOUT", snapshot.nowEpochSeconds)
        }
        val activeCooldowns = (current.cooldowns + snapshot.cooldowns)
            .filter { it.expiresEpochSeconds > snapshot.nowEpochSeconds }
            .distinctBy { listOf(it.baseCallsign, it.band, it.mode, it.reason) }.takeLast(100)
        val effectiveSnapshot = snapshot.copy(cooldowns = activeCooldowns)
        val ranked = DxChaserScorer.rank(effectiveSnapshot, settings)
        val crossBand = if (current.session.target?.engaged == true) emptyList() else crossBand(effectiveSnapshot, settings)
        val freshness = snapshot.evidence.asSequence().take(500).groupBy(DxChaserProviderEvidence::sourceLabel)
            .mapValues { (_, rows) -> rows.minByOrNull { it.state.ordinal }?.state ?: DxChaserEvidenceState.UNAVAILABLE }
        val refreshed = current.copy(ranked = ranked, crossBand = crossBand, generation = snapshot.generation, settings = settings,
            cooldowns = activeCooldowns, providerFreshness = freshness,
            session = current.session.copy(profile = settings.profile,
                endsEpochSeconds = current.session.startedEpochSeconds + settings.sessionTimeoutSeconds))
        val target = refreshed.session.target
        if (target != null) {
            if (snapshot.nowEpochSeconds >= target.timeoutEpochSeconds) return failTarget(refreshed, "TARGET_TIMEOUT", snapshot.nowEpochSeconds)
            val heard = ranked.firstOrNull { it.localDecodeId == target.candidate.localDecodeId || it.baseCallsign == target.candidate.baseCallsign }
            val held = if (heard != null) target.copy(lastHeardEpochSeconds = snapshot.nowEpochSeconds) else target
            val retained = refreshed.copy(session = refreshed.session.copy(target = held))
            if (target.engaged || refreshed.session.state == DxChaserSessionState.ENGAGED) return DxChaserTransition(retained)
            val best = ranked.firstOrNull { it.eligible }
            val dwellMet = snapshot.nowEpochSeconds - target.selectedEpochSeconds >= settings.minimumTargetDwellSeconds
            val threshold = target.candidate.breakdown.total * (100 + settings.preemptionHysteresisPercent) / 100
            if (settings.preemptionEnabled && dwellMet && best != null && best.baseCallsign != target.candidate.baseCallsign &&
                best.breakdown.total > threshold && snapshot.safety.preparationPermitted) {
                return select(retained, best, snapshot.generation, snapshot.nowEpochSeconds, refreshed.session.mode == DxChaserMode.CHASE_SESSION)
            }
            return DxChaserTransition(retained)
        }
        val best = ranked.asSequence().filter(DxChaserCandidateSnapshot::eligible).take(12).firstOrNull()
            ?: return DxChaserTransition(refreshed.copy(session = refreshed.session.copy(state = DxChaserSessionState.MONITORING)))
        return when (refreshed.session.mode) {
            DxChaserMode.CHASE_SESSION -> select(refreshed, best, snapshot.generation, snapshot.nowEpochSeconds, true)
            DxChaserMode.DRY_RUN -> {
                val intent = DxChaserActionIntent(DxChaserActionType.RECORD_DRY_RUN, refreshed.session.id, snapshot.generation,
                    callsign = best.callsign, baseCallsign = best.baseCallsign, score = best.breakdown.total,
                    reason = best.breakdown.reasons.joinToString(","))
                DxChaserTransition(refreshed.copy(session = refreshed.session.copy(state = DxChaserSessionState.MONITORING)), listOf(intent))
            }
            DxChaserMode.ASSIST -> DxChaserTransition(refreshed.copy(session = refreshed.session.copy(state = DxChaserSessionState.MONITORING)))
        }
    }

    private fun select(current: DxChaserEngineState, candidate: DxChaserCandidateSnapshot, generation: Long,
        now: Long, operational: Boolean): DxChaserTransition {
        if (!candidate.eligible) return DxChaserTransition(current)
        val target = DxChaserTargetSnapshot(candidate, now, lastHeardEpochSeconds = now,
            timeoutEpochSeconds = now + current.settings.preEngagementTimeoutSeconds)
        val type = if (operational) DxChaserActionType.PREPARE_FT_CALL else DxChaserActionType.SHOW_RECOMMENDATION
        val intent = DxChaserActionIntent(type, current.session.id, generation, candidate.callsign, candidate.baseCallsign,
            candidate.grid, candidate.band, candidate.mode, candidate.dialFrequencyHz, candidate.audioFrequencyHz,
            candidate.slotIdentity, candidate.localDecodeId, candidate.breakdown.total,
            candidate.breakdown.reasons.joinToString(","))
        val state = if (operational) DxChaserSessionState.PREPARE_CALL_PENDING else DxChaserSessionState.TARGET_SELECTED
        return DxChaserTransition(current.copy(generation = generation, lastAction = intent,
            session = current.session.copy(state = state, target = target, pendingIntent = intent,
                attemptedTargets = current.session.attemptedTargets + 1)), listOf(intent))
    }

    private fun completeTarget(current: DxChaserEngineState, now: Long): DxChaserTransition {
        val target = current.session.target ?: return DxChaserTransition(current)
        val cooldown = DxChaserCooldownSnapshot(target.candidate.baseCallsign, target.candidate.band, target.candidate.mode,
            "COMPLETED_QSO", now + current.settings.completedQsoCooldownSeconds)
        return DxChaserTransition(current.copy(cooldowns = (current.cooldowns + cooldown).takeLast(100),
            session = current.session.copy(state = DxChaserSessionState.COOLDOWN, target = null, pendingIntent = null,
                completedQsos = current.session.completedQsos + 1)))
    }

    private fun failTarget(current: DxChaserEngineState, reason: String, now: Long): DxChaserTransition {
        val target = current.session.target
        val cooldown = target?.let { DxChaserCooldownSnapshot(it.candidate.baseCallsign, it.candidate.band,
            it.candidate.mode, reason, now + current.settings.recentAttemptCooldownSeconds) }
        return DxChaserTransition(current.copy(cooldowns = (current.cooldowns + listOfNotNull(cooldown)).takeLast(100),
            session = current.session.copy(state = DxChaserSessionState.MONITORING, target = null, pendingIntent = null,
                failures = current.session.failures + 1)))
    }

    private fun stop(current: DxChaserEngineState, reason: String, now: Long): DxChaserTransition {
        val intent = DxChaserActionIntent(DxChaserActionType.STOP_CHASE, current.session.id, current.generation,
            reason = reason)
        return DxChaserTransition(current.copy(session = current.session.copy(state = DxChaserSessionState.STOPPED,
            endsEpochSeconds = now, target = null, pendingIntent = null, stopReason = reason)), listOf(intent))
    }

    private fun crossBand(snapshot: DxChaserInputSnapshot, settings: DxChaserSettingsDocument): List<DxChaserCrossBandOpportunity> {
        if (!settings.crossBandRecommendations) return emptyList()
        val material = snapshot.localDecodes.filter { decode -> decode.needs.states.any { (dimension, state) ->
            state == DxChaserNeedState.NEEDED && dimension in setOf(DxChaserNeedDimension.ATNO, DxChaserNeedDimension.DXCC, DxChaserNeedDimension.BAND_ENTITY)
        } }.associateBy { it.baseCallsign }
        return snapshot.evidence.asSequence().take(500).filter { it.state == DxChaserEvidenceState.CURRENT && it.band != snapshot.band }
            .groupBy { it.callsign.uppercase() to it.band }
            .mapNotNull { (key, rows) ->
                val local = material[key.first] ?: return@mapNotNull null
                val sources = rows.map(DxChaserProviderEvidence::kind).filter { it in setOf(DxChaserEvidenceKind.CLUSTER,
                    DxChaserEvidenceKind.RBN, DxChaserEvidenceKind.PSK, DxChaserEvidenceKind.PERSONAL_WSPR) }.distinct()
                if (sources.size < settings.crossBandSourceAgreementThreshold || key.second !in settings.selectedBands) return@mapNotNull null
                if (snapshot.receivableBands.isNotEmpty() && key.second !in snapshot.receivableBands) return@mapNotNull null
                if (rows.any { it.kind in setOf(DxChaserEvidenceKind.BAND_HEALTH, DxChaserEvidenceKind.EMPIRICAL_OUTLOOK) &&
                        it.state == DxChaserEvidenceState.UNAVAILABLE }) return@mapNotNull null
                if (snapshot.cooldowns.any { it.baseCallsign == key.first && it.band == key.second &&
                        it.reason == "CROSS_BAND_REVIEW" && it.expiresEpochSeconds > snapshot.nowEpochSeconds }) return@mapNotNull null
                DxChaserCrossBandOpportunity(local.callsign, key.second,
                    suggestedDialFrequencyHz = rows.mapNotNull(DxChaserProviderEvidence::frequencyHz).firstOrNull(), sourceCount = sources.size,
                    spotterGeography = rows.map(DxChaserProviderEvidence::spotterRegion).filter(String::isNotBlank).distinct().take(8),
                    needReason = local.needs.states.filterValues { it == DxChaserNeedState.NEEDED }.keys.joinToString(","),
                    currentEvidence = "${sources.size} current sources", outlook = rows.firstOrNull {
                        it.kind == DxChaserEvidenceKind.EMPIRICAL_OUTLOOK }?.supportLabel ?: "UNAVAILABLE",
                    ageSeconds = rows.minOf { snapshot.nowEpochSeconds - it.observedEpochSeconds }.coerceAtLeast(0),
                    confidenceLabel = if (sources.size >= 3) "MULTI-SOURCE" else "CORROBORATED")
            }.take(20).toList()
    }
}
