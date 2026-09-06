package app.shackcq.mobile.dxchaser

import java.util.Locale

object DxChaserScorer {
    fun rank(snapshot: DxChaserInputSnapshot, settings: DxChaserSettingsDocument): List<DxChaserCandidateSnapshot> {
        val bounded = snapshot.localDecodes.asSequence().take(500)
            .filter { snapshot.nowEpochSeconds - it.epochSeconds <= settings.localDecodeTtlSeconds }
            .groupBy { "${it.baseCallsign.uppercase(Locale.US)}|${it.band}|${it.mode}|${it.audioFrequencyHz / 25}" }
            .values.map { rows -> rows.maxByOrNull(DxChaserLocalDecode::epochSeconds)!! }
            .map { score(snapshot, settings, it) }
            .sortedWith(candidateComparator)
            .take(50)
        return bounded.toList()
    }

    fun score(
        snapshot: DxChaserInputSnapshot,
        settings: DxChaserSettingsDocument,
        decode: DxChaserLocalDecode,
    ): DxChaserCandidateSnapshot {
        val call = decode.baseCallsign.uppercase(Locale.US)
        val age = (snapshot.nowEpochSeconds - decode.epochSeconds).coerceAtLeast(0)
        val cooldownActive = snapshot.cooldowns.any { it.baseCallsign == call && it.expiresEpochSeconds > snapshot.nowEpochSeconds &&
            (it.band.isBlank() || it.band == decode.band) && (it.mode.isBlank() || it.mode == decode.mode) }
        val contest = snapshot.contest.opportunities[call]
        val blockers = buildList {
            if (decode.mode !in setOf("FT8", "FT4")) add("MODE_NOT_FT8_FT4")
            if (decode.mode !in settings.selectedModes) add("MODE_DISABLED")
            if (decode.band !in settings.selectedBands) add("BAND_DISABLED")
            if (decode.source !in setOf(DxChaserDecodeSource.LIVE_CAPTURE, DxChaserDecodeSource.REDECODE_LIVE_SLOT)) add("NOT_LIVE_LOCAL_DECODE")
            if (!decode.exactSlotTiming) add("TIMING_NOT_EXACT")
            if (decode.source == DxChaserDecodeSource.LIVE_CAPTURE && decode.sessionId != snapshot.digiSessionId) add("DECODE_SESSION_MISMATCH")
            if (decode.source == DxChaserDecodeSource.REDECODE_LIVE_SLOT && decode.slotStartMillis != snapshot.capturedSlotStartMillis) add("LIVE_SLOT_MISMATCH")
            if (decode.stationProfileId != snapshot.stationProfileId) add("STATION_MISMATCH")
            if (decode.radioIdentity != snapshot.radioIdentity) add("RADIO_MISMATCH")
            if (decode.band != snapshot.band || decode.mode != snapshot.mode || decode.dialFrequencyHz != snapshot.receiveFrequencyHz) add("OPERATING_CONTEXT_MISMATCH")
            if (age > settings.localDecodeTtlSeconds) add("STALE_LOCAL_DECODE")
            if (!Regex("^(?=.*[A-Z])(?=.*\\d)[A-Z0-9]{1,8}$").matches(call)) add("INVALID_CALLSIGN")
            if (call == snapshot.stationCallsign.uppercase(Locale.US)) add("OPERATOR_CALLSIGN")
            if (decode.messageType !in setOf(DxChaserMessageType.CQ, DxChaserMessageType.DIRECTED_CQ, DxChaserMessageType.ADDRESSED_TO_OPERATOR)) add("NOT_CALLABLE_MESSAGE")
            if (decode.engagedWithOtherStation) add("ENGAGED_WITH_OTHER_STATION")
            if (decode.snr < settings.minimumSnr) add("BELOW_MINIMUM_SNR")
            if (!snapshot.safety.preparationPermitted) add("DIGI_RADIO_AUDIO_NOT_READY")
            if (!snapshot.foreground) add("APP_NOT_FOREGROUND")
            if (snapshot.contest.running && !snapshot.contest.digitalCompatible) add("CONTEST_MODE_INCOMPATIBLE")
            if (snapshot.contest.running && contest?.validBandMode == false) add("CONTEST_BAND_MODE_INVALID")
            if (snapshot.contest.running && contest?.duplicate == true) add("CONTEST_DUPLICATE")
            if (snapshot.contest.running && !contest?.claimedBy.isNullOrBlank()) add("CLAIMED_BY_TRUSTED_PEER")
            if (cooldownActive) add("COOLDOWN_ACTIVE")
        }

        val reasons = mutableListOf<String>()
        val penalties = mutableListOf<String>()
        var value = 0
        fun needed(dimension: DxChaserNeedDimension, points: Int, label: String) {
            if (decode.needs.state(dimension) == DxChaserNeedState.NEEDED) { value += points; reasons += label }
        }
        needed(DxChaserNeedDimension.ATNO, profileWeight(settings.profile, 100, 130), "ATNO")
        needed(DxChaserNeedDimension.DXCC, profileWeight(settings.profile, 70, 95), "NEEDED_DXCC")
        needed(DxChaserNeedDimension.BAND_ENTITY, profileWeight(settings.profile, 45, 70), "NEW_BAND_ENTITY")
        needed(DxChaserNeedDimension.MODE, 26, "NEW_MODE")
        needed(DxChaserNeedDimension.BAND_MODE_SLOT, profileWeight(settings.profile, 36, 65), "NEW_BAND_MODE_SLOT")
        needed(DxChaserNeedDimension.GRID, if (settings.profile == DxChaserProfile.GRIDS) 70 else 28, "NEW_GRID")
        listOf(DxChaserNeedDimension.CQ_ZONE, DxChaserNeedDimension.ITU_ZONE, DxChaserNeedDimension.WPX,
            DxChaserNeedDimension.IOTA, DxChaserNeedDimension.POTA, DxChaserNeedDimension.SOTA, DxChaserNeedDimension.WWFF)
            .forEach { needed(it, 12, "NEEDED_${it.name}") }
        if (decode.needs.watchlisted) { value += if (settings.profile == DxChaserProfile.WATCHLIST) 90 else 42; reasons += "WATCHLIST" }
        if (decode.messageType == DxChaserMessageType.ADDRESSED_TO_OPERATOR) { value += 38; reasons += "DIRECTED_TO_OPERATOR" }
        else if (decode.messageType == DxChaserMessageType.DIRECTED_CQ) { value += 26; reasons += "DIRECTED_CQ" }
        else if (decode.messageType == DxChaserMessageType.CQ) { value += 20; reasons += "CALLING_CQ" }
        if (snapshot.contest.running && contest != null) {
            val multiplierPoints = (contest.newMultipliers.size * 12).coerceAtMost(36)
            if (multiplierPoints > 0) { value += multiplierPoints; reasons += "CONTEST_NEW_MULTIPLIER" }
            if (contest.validBandMode == null) reasons += "CONTEST_BAND_MODE_UNKNOWN"
        }

        val snrPoints = ((decode.snr.coerceIn(-24, 5) + 24) * (if (settings.profile == DxChaserProfile.LOCAL_SIGNAL) 2 else 1)).coerceAtMost(38)
        val recencyPoints = (20 - age.toInt() / 6).coerceIn(0, 20)
        val repeatPoints = ((decode.decodeCount.coerceAtMost(settings.repeatDecodeCap) - 1) * 4).coerceAtMost(20)
        val stabilityPoints = if (decode.stableAudioFrequency) 5 else 0
        val localQuality = snrPoints + recencyPoints + repeatPoints + stabilityPoints
        if (repeatPoints > 0) reasons += "REPEATED_LOCAL_DECODE"

        val matchingEvidence = snapshot.evidence.filter { it.callsign.equals(call, true) && it.band == decode.band }
        val currentKinds = matchingEvidence.filter { it.state == DxChaserEvidenceState.CURRENT &&
            it.kind != DxChaserEvidenceKind.EMPIRICAL_OUTLOOK }.map { it.kind }.distinct()
        val currentSupport = if (settings.currentEvidenceContribution) (currentKinds.size * 4).coerceAtMost(16) else 0
        if (currentSupport > 0) reasons += "CURRENT_OBSERVED_SUPPORT"
        val futureSupport = if (settings.empiricalOutlookContribution && matchingEvidence.any {
                it.kind == DxChaserEvidenceKind.EMPIRICAL_OUTLOOK && it.state == DxChaserEvidenceState.CURRENT }) 5 else 0
        if (futureSupport > 0) reasons += "FUTURE_EMPIRICAL_SUPPORT"
        val historicalValue = if (decode.needs.worked) -12 else 0
        if (historicalValue < 0) penalties += "RECENT_OR_PREVIOUSLY_WORKED"

        val rarity = snapshot.rarity[decode.entity] ?: DxChaserRarity(decode.entity)
        val rarityPoints = if (settings.rarityContribution && rarity.origin != DxChaserRarityOrigin.UNAVAILABLE) {
            when { rarity.rank != null -> (22 - rarity.rank.coerceIn(1, 500) / 25).coerceIn(2, 22)
                rarity.tier != null -> (24 - rarity.tier.coerceIn(1, 10) * 2).coerceAtLeast(2)
                else -> 0 }
        } else 0
        reasons += if (rarity.origin == DxChaserRarityOrigin.UNAVAILABLE) "RARITY_UNAVAILABLE" else "RARITY_${rarity.origin.name}"

        var penaltyPoints = 0
        if (decode.snr < -18) { penaltyPoints += 8; penalties += "WEAK_SNR" }
        if (age > settings.localDecodeTtlSeconds / 2) { penaltyPoints += 8; penalties += "AGING_LOCAL_DECODE" }
        if (matchingEvidence.any { it.state in setOf(DxChaserEvidenceState.STALE, DxChaserEvidenceState.DEGRADED) }) {
            penaltyPoints += 3; penalties += "PROVIDER_DEGRADED"
        }
        penaltyPoints += blockers.size * 30
        val total = value + localQuality + currentSupport + futureSupport + historicalValue + rarityPoints - penaltyPoints
        val eligible = blockers.isEmpty()
        val tier = when {
            !eligible -> DxChaserPriorityTier.INELIGIBLE
            total >= 150 -> DxChaserPriorityTier.CRITICAL
            total >= 100 -> DxChaserPriorityTier.HIGH
            total >= 60 -> DxChaserPriorityTier.MEDIUM
            else -> DxChaserPriorityTier.LOW
        }
        val breakdown = DxChaserPriorityBreakdown(value = value, localQuality = localQuality,
            currentObservedSupport = currentSupport, futureEmpiricalSupport = futureSupport,
            historicalPersonalValue = historicalValue, rarity = rarityPoints, penalties = penaltyPoints,
            total = total, reasons = reasons.distinct(), penaltyReasons = penalties.distinct())
        return DxChaserCandidateSnapshot(decode.callsign, call, decode.entity, decode.grid, decode.band, decode.mode,
            decode.dialFrequencyHz, decode.audioFrequencyHz, decode.id, decode.slotIdentity, decode.snr,
            decode.decodeCount.coerceAtMost(settings.repeatDecodeCap), age, eligible, blockers.distinct(), tier,
            breakdown, decode.needs.watchlisted, decode.needs.worked, decode.needs.confirmed,
            reasons.filter { it.startsWith("ATNO") || it.startsWith("NEEDED") || it.startsWith("NEW_") },
            if (currentSupport > 0) "CURRENT OBSERVED SUPPORT" else "NO CURRENT CORROBORATION",
            if (futureSupport > 0) "FUTURE EMPIRICAL SUPPORT" else "OUTLOOK UNAVAILABLE", rarity, cooldownActive)
    }

    private fun profileWeight(profile: DxChaserProfile, normal: Int, preferred: Int) = when (profile) {
        DxChaserProfile.DXCC_FIRST -> preferred
        DxChaserProfile.BAND_SLOTS -> if (normal < 60) preferred else normal
        else -> normal
    }

    val candidateComparator = compareByDescending<DxChaserCandidateSnapshot> { it.eligible }
        .thenByDescending { it.breakdown.total }
        .thenByDescending { it.needReasons.firstOrNull() == "ATNO" }
        .thenByDescending { it.currentEvidenceLabel == "CURRENT OBSERVED SUPPORT" }
        .thenByDescending(DxChaserCandidateSnapshot::snr)
        .thenByDescending(DxChaserCandidateSnapshot::decodeCount)
        .thenBy(DxChaserCandidateSnapshot::localDecodeAgeSeconds)
        .thenBy(DxChaserCandidateSnapshot::baseCallsign)
}
