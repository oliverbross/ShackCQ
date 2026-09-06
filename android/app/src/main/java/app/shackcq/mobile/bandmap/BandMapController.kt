// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shackcq.mobile.AndroidCtyRecord
import app.shackcq.mobile.AndroidDXSpot
import app.shackcq.mobile.ContestReadOnlySnapshot
import app.shackcq.mobile.OperatingContextSnapshot
import app.shackcq.mobile.PortableProgram
import app.shackcq.mobile.PortableSpot
import app.shackcq.mobile.QsoProjectionStore
import app.shackcq.mobile.SignalReport
import app.shackcq.mobile.WorkspaceAction
import app.shackcq.mobile.WorkspaceDestination
import app.shackcq.mobile.contest.ContestOpportunityState
import app.shackcq.mobile.contest.ContestTruth
import app.shackcq.mobile.dxchaser.DxChaserCandidateSnapshot
import app.shackcq.mobile.dxchaser.DxChaserReadOnlySnapshot
import app.shackcq.mobile.hamclock.HamClockRbnObservation
import app.shackcq.mobile.keyer.KeyerAvailability
import app.shackcq.mobile.keyer.KeyerQueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal data class BandMapNeedsSnapshot(
    val stationKey: String = "",
    val projectionVersion: Int = QsoProjectionStore.VERSION,
    val generation: Long = 0,
    val complete: Boolean = false,
    val workedEntities: Set<String> = emptySet(), val confirmedEntities: Set<String> = emptySet(),
    val workedBands: Set<String> = emptySet(), val confirmedBands: Set<String> = emptySet(),
    val workedModes: Set<String> = emptySet(), val confirmedModes: Set<String> = emptySet(),
    val workedBandModes: Set<String> = emptySet(), val confirmedBandModes: Set<String> = emptySet(),
    val workedGrids: Set<String> = emptySet(), val workedCqZones: Set<String> = emptySet(),
    val workedItuZones: Set<String> = emptySet(), val workedWpxPrefixes: Set<String> = emptySet(),
    val workedPortableReferences: Set<String> = emptySet(),
    val truncatedDimensions: Set<String> = emptySet(),
)

internal data class BandMapKeyerContext(
    val queue: KeyerQueueSnapshot = KeyerQueueSnapshot(),
    val availability: KeyerAvailability = KeyerAvailability(false),
    val contestRole: String = "",
)

internal data class BandMapInputs(
    val observations: List<BandMapSourceObservation>,
    val context: OperatingContextSnapshot,
    val needs: BandMapNeedsSnapshot,
    val contest: ContestReadOnlySnapshot,
    val contestOpportunity: (String, String, String) -> ContestOpportunityState?,
    val chaser: DxChaserReadOnlySnapshot,
    val keyer: BandMapKeyerContext,
    val ctyLookup: (String) -> AndroidCtyRecord?,
    val empiricalOutlookByBand: Map<String, BandMapEvidence> = emptyMap(),
    val historicalContextByBand: Map<String, BandMapEvidence> = emptyMap(),
    val providerHealth: Map<BandMapSource, Boolean> = emptyMap(),
)

internal data class BandMapDiagnostic(
    val generation: Long = 0, val sourceObservations: Int = 0, val canonicalSpots: Int = 0,
    val afterSourceFilter: Int = 0, val afterBandModeFilter: Int = 0, val afterIntelligenceFilter: Int = 0,
    val visibleSpots: Int = 0, val rebuildMillis: Long = 0, val cancelledGenerations: Long = 0,
    val providerStates: Map<String, String> = emptyMap(),
)

internal enum class BandMapSaveStatus { SAVING, SAVED, FAILED }
internal data class BandMapSaveTruth(
    val status: BandMapSaveStatus = BandMapSaveStatus.SAVED,
    val savedEpoch: Long = 0,
    val error: String = "",
)

internal data class BandMapUiSnapshot(
    val generation: Long = 0,
    val contextGeneration: Long = 0,
    val createdEpoch: Long = 0,
    val layout: BandMapLayoutMode = BandMapLayoutMode.MULTI_VERTICAL,
    val selectedBands: List<String> = emptyList(),
    val filter: BandMapFilter = BandMapFilter(),
    val rankedSpots: List<BandMapRankedSpot> = emptyList(),
    val selectedSpotId: String? = null,
    val keyer: BandMapKeyerContext = BandMapKeyerContext(),
    val diagnostic: BandMapDiagnostic = BandMapDiagnostic(),
    val unavailableReasons: List<String> = emptyList(),
)

internal object BandMapSourceAdapters {
    fun cluster(rows: List<AndroidDXSpot>) = rows.map { row ->
        BandMapSourceObservation(BandMapSource.DX_CLUSTER, row.id, "Configured DX cluster", row.callsign,
            row.frequencyHz, row.receivedEpoch, row.spotter, mode = row.mode, comment = row.comment,
            targetDxcc = row.dxcc, targetContinent = row.continent, targetCqZone = row.cqZone.takeIf { it > 0 },
            targetItuZone = row.ituZone.takeIf { it > 0 }, distanceKm = row.distanceKm.takeIf { it > 0 },
            bearingDegrees = row.bearingDegrees.takeIf { it in 0..359 })
    }

    fun rbn(rows: List<HamClockRbnObservation>) = rows.map { row ->
        BandMapSourceObservation(BandMapSource.RBN, row.id, "RBN via configured cluster", row.dxCall,
            row.frequencyHz, row.observedEpoch, row.skimmerCall, mode = row.mode, snr = row.snr, comment = row.rawComment)
    }

    fun signal(rows: List<SignalReport>, personalWspr: Boolean) = rows.map { row ->
        val target = if (row.direction.name == "HEARING") row.senderCallsign.ifBlank { row.callsign } else row.receiverCallsign.ifBlank { row.callsign }
        val spotter = if (row.direction.name == "HEARING") row.receiverCallsign else row.senderCallsign
        BandMapSourceObservation(if (personalWspr) BandMapSource.PERSONAL_WSPR else BandMapSource.PSK_REPORTER,
            listOf(row.direction.name, target, row.epoch, row.frequencyHz).joinToString("|"),
            if (personalWspr) "Personal WSPR via PSK Reporter" else "PSK Reporter", target,
            row.frequencyHz, row.epoch, spotter, spotterContinent = row.continent, mode = row.mode, snr = row.snr,
            targetGrid = row.locator, distanceKm = row.distanceKm)
    }

    fun portable(rows: List<PortableSpot>) = rows.filter { !it.invalid && !it.qrt && !it.test }.map { row ->
        val program = when {
            PortableProgram.POTA in row.programs -> BandMapSource.POTA
            PortableProgram.SOTA in row.programs -> BandMapSource.SOTA
            else -> BandMapSource.WWFF
        }
        BandMapSourceObservation(program, row.id, row.source, row.callsign, row.frequencyHz, row.spottedAt,
            row.spotter, mode = row.mode, comment = row.comments, portableProgram = program.name,
            portableReference = row.references.joinToString(",") { it.code })
    }
}

internal object BandMapChaserAdapter {
    fun state(spot: BandMapSpot, snapshot: DxChaserReadOnlySnapshot, nowEpoch: Long): BandMapChaserState {
        if (snapshot.generatedEpochSeconds <= 0) return BandMapChaserState()
        val candidate = snapshot.rankedCandidates.firstOrNull { it.baseCallsign.equals(spot.callsign, true) && it.band.equals(spot.band, true) }
        val current = snapshot.currentTarget?.candidate?.baseCallsign.equals(spot.callsign, true)
        val engaged = snapshot.engagedCall.equals(spot.callsign, true)
        val cooldown = snapshot.cooldowns.firstOrNull { it.baseCallsign.equals(spot.callsign, true) && it.band.equals(spot.band, true) && it.expiresEpochSeconds > nowEpoch }
        return BandMapChaserState(
            available = candidate != null, eligible = candidate?.eligible, priorityTier = candidate?.priorityTier?.name.orEmpty(),
            suppliedScore = candidate?.breakdown?.total, positiveReasons = candidate?.breakdown?.reasons.orEmpty(),
            penalties = candidate?.breakdown?.penaltyReasons.orEmpty(),
            ineligibilityReason = candidate?.ineligibleReasons?.joinToString(" · ").orEmpty(), currentTarget = current,
            engagedTarget = engaged, cooldownUntilEpoch = cooldown?.expiresEpochSeconds ?: 0,
            currentEvidenceLabel = candidate?.currentEvidenceLabel.orEmpty(), outlookLabel = candidate?.outlookLabel.orEmpty(),
            generatedEpoch = snapshot.generatedEpochSeconds, generation = snapshot.generation,
        )
    }
}

internal object BandMapNeedsAdapter {
    fun state(spot: BandMapSpot, snapshot: BandMapNeedsSnapshot, cty: AndroidCtyRecord?): BandMapNeedState {
        if (!snapshot.complete) return BandMapNeedState(missingReasons = listOf("QSO projection needs snapshot unavailable or incomplete"))
        fun truth(key: String, worked: Set<String>, confirmed: Set<String>) = when {
            key.isBlank() -> BandMapNeedTruth.UNKNOWN
            key in confirmed -> BandMapNeedTruth.CONFIRMED
            key in worked -> BandMapNeedTruth.WORKED
            else -> BandMapNeedTruth.NEEDED
        }
        val mode = spot.modeFamily.name
        val entity = cty?.dxcc.orEmpty().uppercase()
        val grid = spot.observations.firstNotNullOfOrNull { it.targetGrid.takeIf(String::isNotBlank) }.orEmpty().uppercase()
        val cq = cty?.cqZone.orEmpty(); val itu = cty?.ituZone.orEmpty()
        val reference = spot.observations.firstNotNullOfOrNull { it.portableReference.takeIf(String::isNotBlank) }.orEmpty().uppercase()
        return BandMapNeedState(
            entity = truth(entity, snapshot.workedEntities, snapshot.confirmedEntities),
            band = truth(spot.band.uppercase(), snapshot.workedBands, snapshot.confirmedBands),
            mode = truth(mode, snapshot.workedModes, snapshot.confirmedModes),
            bandMode = truth("${spot.band.uppercase()}|$mode", snapshot.workedBandModes, snapshot.confirmedBandModes),
            grid = truth(grid, snapshot.workedGrids, emptySet()), cqZone = truth(cq, snapshot.workedCqZones, emptySet()),
            ituZone = truth(itu, snapshot.workedItuZones, emptySet()), portableReference = truth(reference, snapshot.workedPortableReferences, emptySet()),
            missingReasons = buildList { if (cty == null) add("CTY/entity unresolved"); if (snapshot.truncatedDimensions.isNotEmpty()) add("Some bounded need dimensions are truncated") },
        )
    }
}

internal class BandMapController(
    initialSettings: BandMapSettings,
    private val saveSettings: (BandMapSettings) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val issuedGeneration = AtomicLong()
    private val index = BandMapSpotIndex()
    private var rebuildJob: Job? = null
    private var saveJob: Job? = null
    private var cancelledGenerations = 0L
    private var closed = false
    private var latestInputs: BandMapInputs? = null
    var settings by mutableStateOf(initialSettings)
        private set
    var snapshot by mutableStateOf(BandMapUiSnapshot(layout = initialSettings.selectedLayout, selectedBands = initialSettings.selectedBands))
        private set
    var saveTruth by mutableStateOf(BandMapSaveTruth())
        private set

    fun submit(inputs: BandMapInputs) {
        if (closed) return
        latestInputs = inputs
        val generation = issuedGeneration.incrementAndGet()
        if (rebuildJob?.isActive == true) cancelledGenerations++
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            delay(90)
            val started = System.nanoTime(); val now = Instant.now().epochSecond
            val contestActive = inputs.contest.activeSession?.id?.value?.isNotBlank() == true
            val requestedPreset = settings.presets.firstOrNull { it.id == settings.activePresetId }
            val preset = requestedPreset?.takeUnless { it.id == "contest" && !contestActive }
                ?: settings.presets.firstOrNull { it.id == "all-current" }
                ?: settings.presets.firstOrNull { it.id != "contest" }
                ?: builtInBandMapPresets.first()
            val base = index.coalesce(inputs.observations, settings.marks)
            val enriched = base.map { spot ->
                val cty = inputs.ctyLookup(spot.callsign)
                val opportunity = inputs.contestOpportunity(spot.callsign, spot.band, spot.submode.ifBlank { spot.modeFamily.name })
                val contest = opportunity?.let(::contestState) ?: BandMapContestState(active = inputs.contest.activeSession?.id?.value?.isNotBlank() == true)
                val evidence = buildList {
                    if (spot.sources.any { it in setOf(BandMapSource.RBN, BandMapSource.PSK_REPORTER, BandMapSource.PERSONAL_WSPR) })
                        add(BandMapEvidence(BandMapEvidenceKind.CURRENT_OBSERVED, BandMapEvidenceStatus.POSITIVE,
                            spot.sources.filter { it in setOf(BandMapSource.RBN, BandMapSource.PSK_REPORTER, BandMapSource.PERSONAL_WSPR) }.joinToString("+") { it.name },
                            spot.newestObservationEpoch, "Current observation; no propagation probability inferred"))
                    inputs.empiricalOutlookByBand[spot.band]?.let(::add)
                    inputs.historicalContextByBand[spot.band]?.let(::add)
                }
                spot.copy(need = BandMapNeedsAdapter.state(spot, inputs.needs, cty), contest = contest,
                    chaser = BandMapChaserAdapter.state(spot, inputs.chaser, now), evidence = evidence)
            }
            val finalFilter = preset.filter.copy(bands = settings.selectedBands.toSet())
            val sourceFilter = BandMapFilter(
                bands = bandMapVisibleBands.toSet(), segments = bandMapVisibleBands.map { BandMapSegment(it) },
                sources = finalFilter.sources, maximumAgeSeconds = finalFilter.maximumAgeSeconds,
                minimumSpotters = finalFilter.minimumSpotters, minimumSourceDiversity = finalFilter.minimumSourceDiversity,
                spotterContinents = finalFilter.spotterContinents, targetContinents = finalFilter.targetContinents,
                search = finalFilter.search, showStale = finalFilter.showStale,
            )
            val afterSource = BandMapFilterEngine.visible(enriched, sourceFilter, now)
            val afterBandMode = BandMapFilterEngine.visible(afterSource,
                sourceFilter.copy(bands = finalFilter.bands, segments = finalFilter.segments, modes = finalFilter.modes), now)
            val visible = BandMapFilterEngine.visible(afterBandMode, finalFilter.copy(
                sources = emptySet(), maximumAgeSeconds = Long.MAX_VALUE, minimumSpotters = 0,
                minimumSourceDiversity = 0, spotterContinents = emptySet(), targetContinents = emptySet(),
                bands = bandMapVisibleBands.toSet(), segments = emptyList(), modes = emptySet(), search = "",
            ), now)
            val ranked = BandMapPriorityEngine.rank(visible, preset.weights, now)
            val result = BandMapUiSnapshot(generation, inputs.context.generation, now, settings.selectedLayout,
                settings.selectedBands, preset.filter, ranked, snapshot.selectedSpotId?.takeIf { id -> ranked.any { it.spot.id == id } }, inputs.keyer,
                BandMapDiagnostic(generation, inputs.observations.size, enriched.size, afterSource.size,
                    afterBandMode.size, visible.size, ranked.size,
                    (System.nanoTime() - started) / 1_000_000, cancelledGenerations,
                    inputs.providerHealth.mapKeys { it.key.name }.mapValues { if (it.value) "AVAILABLE" else "UNAVAILABLE" }),
                buildList {
                    if (!inputs.needs.complete) add("Needs projection unavailable")
                    if (inputs.observations.isEmpty()) add(if (inputs.providerHealth[BandMapSource.DX_CLUSTER] == false)
                        "Cluster disconnected · no current source observations" else "Connected but no spots received")
                    else if (ranked.isEmpty()) add(if (afterBandMode.isEmpty()) "Unsupported band or mode · reset filters"
                        else "Spots received but all filtered · reset filters")
                    if (inputs.providerHealth.values.any { !it }) add("One or more sources degraded")
                })
            if (generation == issuedGeneration.get() && !closed) withContext(Dispatchers.Main.immediate) { if (generation == issuedGeneration.get()) snapshot = result }
        }
    }

    fun select(id: String?) { snapshot = snapshot.copy(selectedSpotId = id) }

    fun updateSettings(transform: (BandMapSettings) -> BandMapSettings) {
        val candidate = transform(settings).let { value ->
            value.copy(selectedBands = value.selectedBands.filter { it in bandMapVisibleBands }.distinct()
                .ifEmpty { listOf("40m", "20m", "15m", "10m") })
        }
        BandMapSettingsCodec.decode(BandMapSettingsCodec.encode(candidate))
        settings = candidate
        scheduleSave(candidate)
        latestInputs?.let(::submit)
    }

    fun retrySave() = scheduleSave(settings, immediate = true)

    private fun scheduleSave(candidate: BandMapSettings, immediate: Boolean = false) {
        saveJob?.cancel()
        saveTruth = saveTruth.copy(status = BandMapSaveStatus.SAVING, error = "")
        saveJob = scope.launch {
            if (!immediate) delay(350)
            runCatching { saveSettings(candidate) }
                .onSuccess { withContext(Dispatchers.Main.immediate) {
                    saveTruth = BandMapSaveTruth(BandMapSaveStatus.SAVED, Instant.now().epochSecond)
                } }
                .onFailure { error -> withContext(Dispatchers.Main.immediate) {
                    saveTruth = BandMapSaveTruth(BandMapSaveStatus.FAILED, saveTruth.savedEpoch,
                        error.message.orEmpty().take(120))
                } }
        }
    }

    fun visibleSegment(band: String): BandMapSegment {
        settings.viewports[band]?.let { return it.asSegment(band) }
        val preset = settings.presets.firstOrNull { it.id == settings.activePresetId }
        return preset?.filter?.segments?.firstOrNull { it.band == band } ?: BandMapSegment(band)
    }

    fun zoom(band: String, factor: Double, anchorHz: Long? = null) {
        val targets = if (settings.linkedZoom) settings.selectedBands else listOf(band)
        updateSettings { current ->
            val next = current.viewports.toMutableMap()
            targets.forEach { target ->
                val definition = bandMapBands.firstOrNull { it.name == target } ?: return@forEach
                val old = next[target] ?: BandMapViewport(definition.lowerHz, definition.upperHz)
                val minimum = ((definition.upperHz - definition.lowerHz) / 200L).coerceAtLeast(1_000L)
                val span = (old.spanHz * factor).toLong().coerceIn(minimum, definition.upperHz - definition.lowerHz)
                val anchor = (anchorHz ?: ((old.lowerHz + old.upperHz) / 2L)).coerceIn(old.lowerHz, old.upperHz)
                val ratio = (anchor - old.lowerHz).toDouble() / old.spanHz.toDouble()
                var lower = anchor - (span * ratio).toLong(); var upper = lower + span
                if (lower < definition.lowerHz) { lower = definition.lowerHz; upper = lower + span }
                if (upper > definition.upperHz) { upper = definition.upperHz; lower = upper - span }
                next[target] = BandMapViewport(lower, upper)
            }
            current.copy(viewports = next)
        }
    }

    fun pan(band: String, fraction: Double) {
        updateSettings { current ->
            val definition = bandMapBands.first { it.name == band }
            val old = current.viewports[band] ?: BandMapViewport(definition.lowerHz, definition.upperHz)
            val shift = (old.spanHz * fraction).toLong()
            var lower = old.lowerHz + shift; var upper = old.upperHz + shift
            if (lower < definition.lowerHz) { lower = definition.lowerHz; upper = lower + old.spanHz }
            if (upper > definition.upperHz) { upper = definition.upperHz; lower = upper - old.spanHz }
            current.copy(viewports = current.viewports + (band to BandMapViewport(lower, upper)))
        }
    }

    fun resetViewport(band: String) = updateSettings { current -> current.copy(viewports = current.viewports - band) }

    fun prepare(action: WorkspaceAction) {
        val selectedBand = action.band.takeIf { band -> bandMapVisibleBands.any { it.equals(band, true) } }
        val requestedPreset = action.presetId.takeIf { id -> settings.presets.any { it.id == id } }
        updateSettings { current ->
            val activeId = requestedPreset ?: current.activePresetId
            current.copy(
                selectedBands = selectedBand?.let(::listOf) ?: current.selectedBands,
                activePresetId = activeId,
                presets = current.presets.map { preset ->
                    if (preset.id == activeId && action.callsign.isNotBlank())
                        preset.copy(filter = preset.filter.copy(search = action.callsign.uppercase()))
                    else preset
                },
            )
        }
    }

    fun toggleMark(spot: BandMapSpot, kind: BandMapMarkKind) = updateSettings { current ->
        val existing = current.marks.firstOrNull { it.callsign == spot.callsign && it.band == spot.band }
        val nextKinds = (existing?.kinds.orEmpty()).let { if (kind in it) it - kind else it + kind }
        val remaining = current.marks.filterNot { it.callsign == spot.callsign && it.band == spot.band }
        current.copy(marks = if (nextKinds.isEmpty()) remaining else remaining + BandMapMark(spot.callsign, spot.band, spot.frequencyHz, nextKinds, Instant.now().epochSecond))
    }

    fun workspaceAction(spot: BandMapSpot, destination: WorkspaceDestination, currentContextGeneration: Long): WorkspaceAction? {
        if (closed || snapshot.contextGeneration != currentContextGeneration || snapshot.generation != issuedGeneration.get()) return null
        return WorkspaceAction(destination, callsign = spot.callsign,
            frequencyHz = spot.frequencyHz.takeIf { destination == WorkspaceDestination.RADIO }, band = spot.band,
            mode = spot.submode.ifBlank { spot.modeFamily.name }, portableProgram = spot.portablePrograms.firstOrNull().orEmpty(),
            portableReference = spot.observations.firstNotNullOfOrNull { it.portableReference.takeIf(String::isNotBlank) }.orEmpty(),
            expectedContextGeneration = currentContextGeneration,
            source = "Intelligent Band Maps", reason = "Operator selected ${spot.callsign}; prepare reviewed receive action")
    }

    override fun close() {
        if (closed) return
        closed = true; issuedGeneration.incrementAndGet(); rebuildJob?.cancel(); saveJob?.cancel(); scope.cancel()
    }

    private fun contestState(value: ContestOpportunityState) = BandMapContestState(
        active = true, validBandMode = when (value.validBandMode) { ContestTruth.VALID -> true; ContestTruth.INVALID -> false; else -> null },
        duplicate = when (value.dupe.name) { "DUPLICATE" -> true; "NEW" -> false; else -> null }, newMultipliers = value.newMultipliers.map { it.name }.toSet(),
        expectedExchange = value.expectedExchangeHint, claimedBy = value.claimedBy.orEmpty(), explanations = value.priorityReasons,
    )
}
