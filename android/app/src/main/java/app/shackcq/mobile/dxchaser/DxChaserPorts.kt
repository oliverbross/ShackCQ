package app.shackcq.mobile.dxchaser

import java.util.concurrent.Executors
import java.security.MessageDigest

interface DxChaserLocalDecodePort { fun snapshot(): DxChaserInputSnapshot }
interface DxChaserNeedsPort { fun needsFor(baseCallsign: String, band: String, mode: String): DxChaserNeedFacts }
interface DxChaserProviderEvidencePort { fun evidence(): List<DxChaserProviderEvidence> }
interface DxChaserOperatingContextPort { fun generation(): Long; fun foreground(): Boolean }
interface DxChaserRarityPort { fun rarity(): Map<String, DxChaserRarity> }
fun interface DxChaserClock { fun epochSeconds(): Long }
fun interface DxChaserActionPort { fun emit(intent: DxChaserActionIntent) }
fun interface DxChaserSessionEventPort { fun emit(event: DxChaserIntegrationEvent) }
fun interface DxChaserReadOnlyPort { fun snapshot(): DxChaserReadOnlySnapshot }
interface DxChaserJournalPort {
    fun start(session: DxChaserSessionSnapshot, stationScope: String, bands: Set<String>)
    fun record(sessionId: String, epochSeconds: Long, candidate: DxChaserCandidateSnapshot, disposition: String)
    fun cooldown(value: DxChaserCooldownSnapshot)
    fun finish(session: DxChaserSessionSnapshot)
    fun compact(nowEpochSeconds: Long, settings: DxChaserSettingsDocument)
}

/** Integrated semantic adapter: prepares an existing exact live FT decode through Digi safety. */
fun interface DxChaserDigiPort { fun prepare(intent: DxChaserActionIntent) }
/** Integrated semantic adapter: routes receive-review only; it must not issue CAT directly. */
fun interface DxChaserReviewPort { fun requestReceiveReview(intent: DxChaserActionIntent) }
/** Integrated semantic adapter: reports canonical QSO mutation outcomes without logging here. */
fun interface DxChaserQsoOutcomePort { fun report(event: DxChaserIntegrationEvent) }

data class DxChaserDependencies(
    val input: DxChaserLocalDecodePort,
    val action: DxChaserActionPort,
    val sessionEvents: DxChaserSessionEventPort = DxChaserSessionEventPort {},
    val clock: DxChaserClock = DxChaserClock { System.currentTimeMillis() / 1_000 },
    val settings: () -> DxChaserSettingsDocument = { DxChaserSettingsDocument() },
    val journal: DxChaserJournalPort? = null,
)

class DxChaserController(private val dependencies: DxChaserDependencies) : DxChaserReadOnlyPort, AutoCloseable {
    private val reducer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shackcq-dx-chaser-reducer").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var state = DxChaserEngineState()
    @Volatile private var readOnly = DxChaserReadOnlySnapshot()

    @Synchronized
    fun start(mode: DxChaserMode, sessionId: String) {
        if (closed) return
        require(sessionId.isNotBlank())
        apply(DxChaserEvent.OperatorStart(mode, sessionId, dependencies.clock.epochSeconds()))
        val input = dependencies.input.snapshot()
        val startedSession = state.session
        reducer.execute { dependencies.journal?.start(startedSession, input.stationProfileId, dependencies.settings().selectedBands) }
    }

    @Synchronized fun startAssist(sessionId: String) = start(DxChaserMode.ASSIST, sessionId)
    @Synchronized fun startChase(sessionId: String) = start(DxChaserMode.CHASE_SESSION, sessionId)
    @Synchronized fun startDryRun(sessionId: String) = start(DxChaserMode.DRY_RUN, sessionId)

    fun refresh() {
        if (closed) return
        val snapshot = dependencies.input.snapshot()
        val settings = dependencies.settings()
        reducer.execute {
            synchronized(this) {
                if (!closed) apply(DxChaserEvent.SnapshotUpdated(snapshot, settings))
            }
        }
    }

    @Synchronized fun select(candidate: DxChaserCandidateSnapshot) =
        apply(DxChaserEvent.CandidateSelected(candidate, state.generation, dependencies.clock.epochSeconds()))
    @Synchronized fun prepareAccepted() = apply(DxChaserEvent.PrepareAccepted(dependencies.clock.epochSeconds()))
    @Synchronized fun prepareRejected(reason: String) = apply(DxChaserEvent.PrepareRejected(reason, dependencies.clock.epochSeconds()))
    @Synchronized fun sequenceStarted() = apply(DxChaserEvent.SequenceStarted(dependencies.clock.epochSeconds()))
    @Synchronized fun attemptCompleted() = apply(DxChaserEvent.AttemptCompleted(dependencies.clock.epochSeconds()))
    @Synchronized fun remoteEngaged() = apply(DxChaserEvent.RemoteEngaged(dependencies.clock.epochSeconds()))
    @Synchronized fun qsoCompleted() = apply(DxChaserEvent.QsoCompleted(dependencies.clock.epochSeconds()))
    @Synchronized fun qsoFailed(reason: String) = apply(DxChaserEvent.QsoFailed(reason, dependencies.clock.epochSeconds()))
    @Synchronized fun stop(reason: String = "OPERATOR_STOP") = apply(DxChaserEvent.Stop(reason, dependencies.clock.epochSeconds()))
    @Synchronized fun contextLost(reason: String) = apply(DxChaserEvent.ContextLost(reason, dependencies.clock.epochSeconds()))
    @Synchronized fun review(opportunity: DxChaserCrossBandOpportunity) =
        apply(DxChaserEvent.CrossBandReview(opportunity, state.generation, dependencies.clock.epochSeconds()))
    @Synchronized fun bandReviewAccepted() = apply(DxChaserEvent.BandReviewAccepted(dependencies.clock.epochSeconds()))
    @Synchronized fun bandReviewRejected() = apply(DxChaserEvent.BandReviewRejected(dependencies.clock.epochSeconds()))

    override fun snapshot(): DxChaserReadOnlySnapshot = readOnly

    private fun apply(event: DxChaserEvent) {
        if (closed) return
        val previous = state
        val transition = DxChaserEngine.reduce(state, event)
        val next = transition.state
        state = next
        transition.actions.forEach(dependencies.action::emit)
        val now = dependencies.clock.epochSeconds()
        val journal = dependencies.journal
        if (journal != null) reducer.execute {
            transition.actions.filter { it.type in setOf(DxChaserActionType.PREPARE_FT_CALL,
                DxChaserActionType.SHOW_RECOMMENDATION, DxChaserActionType.RECORD_DRY_RUN) }.forEach { intent ->
                next.ranked.firstOrNull { it.localDecodeId == intent.localDecodeId }?.let { candidate ->
                    journal.record(next.session.id, now, candidate, intent.type.name)
                }
            }
            next.cooldowns.filterNot { it in previous.cooldowns }.forEach(journal::cooldown)
            if (next.session.state in setOf(DxChaserSessionState.STOPPED, DxChaserSessionState.COMPLETE,
                    DxChaserSessionState.FAILED)) {
                journal.finish(next.session)
                journal.compact(now, next.settings)
            }
        }
        readOnly = DxChaserReadOnlySnapshot(generatedEpochSeconds = now, generation = state.generation,
            session = state.session, rankedCandidates = state.ranked.take(50), currentTarget = state.session.target,
            engagedCall = state.session.target?.takeIf(DxChaserTargetSnapshot::engaged)?.candidate?.baseCallsign.orEmpty(),
            cooldowns = state.cooldowns.take(100), crossBandOpportunities = state.crossBand.take(20),
            providerFreshness = state.providerFreshness, safety = dependencies.input.snapshot().safety,
            contest = dependencies.input.snapshot().contest, settingsDigest = MessageDigest.getInstance("SHA-256")
                .digest(state.settings.toJson().toByteArray()).take(8).joinToString("") { "%02x".format(it) },
            lastAction = transition.actions.lastOrNull() ?: state.lastAction)
        dependencies.sessionEvents.emit(DxChaserIntegrationEvent(state.session.id, state.generation,
            event::class.simpleName ?: "EVENT", state.session.state.name, now))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (state.session.state !in setOf(DxChaserSessionState.DISABLED, DxChaserSessionState.STOPPED,
                DxChaserSessionState.COMPLETE, DxChaserSessionState.FAILED)) {
            apply(DxChaserEvent.Stop("CONTROLLER_CLOSE", dependencies.clock.epochSeconds()))
        }
        closed = true
        reducer.shutdown()
    }
}
