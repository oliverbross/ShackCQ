// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shackcq.mobile.contest.*
import app.shackcq.mobile.dxchaser.*
import app.shackcq.mobile.keyer.*
import app.shackcq.mobile.n1mm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ContestReadOnlySnapshot(
    val activeSession: ContestSession? = null,
    val opportunityContractVersion: Int = 1,
    val claims: List<ContestClaimSnapshot> = emptyList(),
    val n1mmEnabled: Boolean = false,
    val n1mmArmed: Boolean = false,
    val n1mmPeers: Int = 0,
    val lastError: String = "",
)

fun interface ContestReadOnlyPort { fun snapshot(): ContestReadOnlySnapshot }

internal fun contestKeyerRole(value: ContestOperatingRole): KeyerOperatingRole = when (value) {
    ContestOperatingRole.RUN -> KeyerOperatingRole.CONTEST_RUN
    ContestOperatingRole.SEARCH_AND_POUNCE -> KeyerOperatingRole.CONTEST_S_AND_P
}

internal fun contestKeyerMode(value: ContestMode): KeyerMode? = when (value) {
    ContestMode.CW -> KeyerMode.CW
    ContestMode.SSB -> KeyerMode.VOICE
    ContestMode.DIGITAL, ContestMode.MIXED -> null
}

data class ContestMutationReceipt(
    val saved: Boolean,
    val qsoId: String = "",
    val serialCommitted: Boolean = false,
    val detail: String,
)

class ContestKeyerAdapter(
    private val dispatch: () -> KeyerDispatchPort?,
    private val profiles: KeyerProfileStore,
    private val operatingContext: () -> OperatingContextSnapshot,
) {
    fun selectProfile(session: ContestSession) {
        val role = contestKeyerRole(session.role)
        val mode = contestKeyerMode(session.category.mode) ?: return
        profiles.profiles.firstOrNull { it.role == role && it.mode == mode }?.let { profile ->
            if (profiles.activeProfileId != profile.id) {
                dispatch()?.stop(KeyerStopReason.ProfileChanged)
                profiles.activate(profile.id)
            }
        }
    }

    fun submit(
        intent: ContestKeyerIntent,
        session: ContestSession,
        callsign: String,
        rstSent: String,
        rstReceived: String,
        exchange: String,
        reservation: ContestSerialReservation? = null,
        qsoId: String = "",
    ): KeyerDispatchResult {
        val port = dispatch() ?: return KeyerDispatchResult.Rejected(KeyerFailureReason.BackendUnavailable, "Keyer runtime is unavailable")
        val snapshot = operatingContext()
        if (intent.sessionId != session.id || intent.expectedContextGeneration != snapshot.generation)
            return KeyerDispatchResult.Rejected(KeyerFailureReason.ContextChanged, "Contest or operating context generation changed")
        val mode = contestKeyerMode(intent.mode) ?: return KeyerDispatchResult.Rejected(
            KeyerFailureReason.ModeUnsupported, "Contest DIGITAL/MIXED has no CW/voice keyer fallback")
        val role = contestKeyerRole(intent.role)
        val serial = reservation?.takeIf {
            it.sessionId == session.id && it.state == ContestSerialState.RESERVED &&
                it.owner == reservationOwner(qsoId, snapshot.generation)
        }?.serial?.toString().orEmpty()
        val active = profiles.activeProfile()
        if (active.role != role || active.mode != mode)
            return KeyerDispatchResult.Rejected(KeyerFailureReason.ProfileChanged, "Select the matching Contest keyer profile")
        val messageId = "contest-${intent.type.name.lowercase(Locale.US).replace('_', '-')}"
        return port.submit(KeyerAction.SendMessage(messageId), KeyerContextSnapshot(
            operatingGeneration = snapshot.generation,
            foregroundGeneration = snapshot.generation,
            radioIdentity = snapshot.radioIdentity.value,
            connected = snapshot.connected.value,
            foreground = snapshot.foreground.value,
            mode = mode,
            profileId = active.id,
            modeSupported = snapshot.mode.value.uppercase(Locale.US).let { radioMode ->
                if (mode == KeyerMode.CW) radioMode.startsWith("CW") else radioMode in setOf("USB", "LSB", "SSB")
            },
            role = role,
            myCall = snapshot.stationCallsign.value,
            call = callsign,
            rst = rstReceived,
            rstSent = rstSent,
            rstRecv = rstReceived,
            serial = serial,
            exchange = exchange,
            grid = snapshot.stationGrid.value,
            band = snapshot.band.value,
        ))
    }

    companion object {
        fun reservationOwner(qsoId: String, generation: Long) = "qso:$qsoId:g$generation"
    }
}

class ContestQsoMutationAdapter(
    private val mutations: QsoMutationCoordinator,
    private val store: ContestSessionStore,
    private val serials: ContestSerialAuthority,
    private val onAccepted: (Qso) -> Unit = {},
) {
    private val exchange = ContestExchangeEngine()

    fun save(
        session: ContestSession,
        definition: ContestDefinition,
        draft: ContestQsoDraft,
        reservation: ContestSerialReservation?,
    ): ContestMutationReceipt {
        if (draft.callsign.isBlank() || draft.frequencyHz <= 0)
            return failure(reservation, session.id, "Callsign and exact frequency are required")
        val issues = exchange.validate(definition, draft.received)
        if (issues.any { it.truth in setOf(ContestTruth.INVALID, ContestTruth.INCOMPLETE) })
            return failure(reservation, session.id, issues.joinToString { it.reason })
        val canonical = ContestQsoMapper.toCanonical(session, definition, draft)
        if (!mutations.save(canonical, QsoOrigin.OPERATOR))
            return failure(reservation, session.id, "Canonical QSO mutation failed or matched an existing record")
        return runCatching {
            store.linkQso(session.id, canonical.id, "local:${canonical.createdAt}:${canonical.id}")
            val committed = reservation?.let { serials.commit(it.id, session.id, canonical.id); true } ?: false
            onAccepted(canonical)
            ContestMutationReceipt(true, canonical.id, committed,
                "Canonical QSO saved; existing Wavelog outbox owns delivery")
        }.getOrElse { error ->
            ContestMutationReceipt(false, canonical.id, false,
                "Canonical QSO saved but Contest link requires review: ${error.javaClass.simpleName}")
        }
    }

    private fun failure(reservation: ContestSerialReservation?, sessionId: ContestSessionId, detail: String): ContestMutationReceipt {
        if (reservation?.state == ContestSerialState.RESERVED) runCatching { serials.release(reservation.id, sessionId) }
        return ContestMutationReceipt(false, detail = detail)
    }
}

class ContestRuntime(
    context: Context,
    private val database: QsoDatabase,
    private val mutations: QsoMutationCoordinator,
    private val profiles: KeyerProfileStore,
    private val keyer: () -> KeyerDispatchPort?,
    private val operatingContext: () -> OperatingContextSnapshot,
    private val wavelogBinding: () -> String = { "LOCAL LOG · canonical mutation adapter" },
) : ContestReadOnlyPort, AutoCloseable {
    private val prefs = context.getSharedPreferences("shackcq-contest-settings", Context.MODE_PRIVATE)
    val store = ContestSessionStore(context)
    val sessionController = ContestSessionController(store)
    private val serials = ContestSerialAuthority(store)
    private val registry = ContestRuleRegistry()
    private val initialDefinition = registry.all().first().definition
    private val opportunityEvaluator = DefaultContestOpportunityEvaluator(registry)
    private val canonicalReader = ContestCanonicalQsoReader(store, database)
    private val scp = SuperCheckPartialStore(context)
    private val keyerAdapter = ContestKeyerAdapter(keyer, profiles, operatingContext)
    private val mutationAdapter = ContestQsoMutationAdapter(mutations, store, serials)
    private val n1mmBridge = N1mmQsoBridge(N1mmContestStagingPort { session, draft, revision ->
        store.stageQso(session, draft, networkRevision = revision)
    })
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scoreGeneration = 0L
    private var scpGeneration = 0L
    private var closed = false
    private var foreground = true
    private var networkArmed = false
    private val trustedPeers = loadTrusts().associateByTo(linkedMapOf(), N1mmPeerTrust::station)
    private var peerCache = emptyList<N1mmPeerSnapshot>()

    var activeSession by mutableStateOf(loadOrCreateSession(initialDefinition)); private set
    var definition by mutableStateOf(runCatching { registry.require(activeSession.definitionId).definition }.getOrDefault(initialDefinition)); private set
    var workspacePage by mutableStateOf(ContestWorkspacePage.SETUP); private set
    var callsign by mutableStateOf(""); private set
    var rstSent by mutableStateOf(if (definition.mode == ContestMode.CW) "599" else "59"); private set
    var rstReceived by mutableStateOf(if (definition.mode == ContestMode.CW) "599" else "59"); private set
    var receivedExchange by mutableStateOf<Map<ContestExchangeField, String>>(emptyMap()); private set
    var exchangeText by mutableStateOf(""); private set
    var panelLayout by mutableStateOf(loadPanelLayout()); private set
    var exportPreview by mutableStateOf(""); private set
    var lastMessage by mutableStateOf("Contest setup ready; nothing is armed"); private set
    var scpStatus by mutableStateOf(scp.status()); private set
    var scpSuggestions by mutableStateOf<List<ScpSuggestion>>(emptyList()); private set

    private var n1mmConfig = loadN1mmConfig()
    var n1mm = newN1mmController(); private set

    val definitions: List<ContestDefinition> get() = registry.all().map(ContestRulePack::definition)

    private fun loadOrCreateSession(fallback: ContestDefinition): ContestSession {
        val stored = prefs.getString("last_session_id", null)?.let { store.loadSession(ContestSessionId(it)) }
        if (stored != null) return sessionController.restored(stored).also(store::saveSession)
        val now = Instant.now().epochSecond
        return ContestSession(
            id = ContestSessionId(UUID.randomUUID().toString()), definitionId = fallback.id,
            ruleVersion = fallback.version, name = fallback.humanName, utcStart = now, utcEnd = now + 86_400,
            stationCallsign = operatingContext().stationCallsign.value,
            stationGrid = operatingContext().stationGrid.value, station = ContestEntityInfo(),
            category = ContestCategory(mode = fallback.mode), operators = listOf(operatingContext().operatorCallsign.value),
        ).also { session -> store.saveSession(session); prefs.edit().putString("last_session_id", session.id.value).apply() }
    }

    private fun loadN1mmConfig() = N1mmNetworkConfig(
        enabled = prefs.getBoolean("n1mm_enabled", false),
        mode = runCatching { N1mmMode.valueOf(prefs.getString("n1mm_mode", N1mmMode.OFF.name).orEmpty()) }.getOrDefault(N1mmMode.OFF),
        stationName = "ShackCQ", operatorCall = operatingContext().operatorCallsign.value,
        contestName = definition.cabrilloContestName, ruleVersion = definition.version.value,
        bindAddress = if (prefs.getBoolean("n1mm_lan_opt_in", false)) prefs.getString("n1mm_bind_address", "127.0.0.1").orEmpty() else "127.0.0.1",
        interfaceName = if (prefs.getBoolean("n1mm_lan_opt_in", false)) "operator-selected" else "loopback",
        lanBroadcastOptIn = prefs.getBoolean("n1mm_lan_opt_in", false),
    )

    private fun newN1mmController() = N1mmNetworkController(
        n1mmConfig,
        trusts = trustedPeers.values.toList(),
        onCommand = { station, command, policy, decision ->
            val result = when (command.command) {
                N1mmCommand.QSO, N1mmCommand.RESYNCQSO ->
                    n1mmBridge.receiveAdd(command, station, policy, activeSession, definition, "n1mm:$station")
                N1mmCommand.REEDITQSO, N1mmCommand.QSODELETE, N1mmCommand.DELETEQS ->
                    n1mmBridge.receiveEditOrDelete(command)
                else -> null
            }
            lastMessage = result?.let { "N1MM ${it.state.name}: ${it.reason}" }
                ?: "N1MM ${command.command.name} ${decision.name}; no radio, keyer, Digi, time, file, or arbitrary payload authority"
        },
    )

    private fun rebuildN1mmController() {
        peerCache = (n1mm.peerSnapshots() + peerCache).distinctBy(N1mmPeerSnapshot::station)
        n1mm.close()
        networkArmed = false
        n1mm = newN1mmController()
    }

    private fun loadTrusts(): List<N1mmPeerTrust> = runCatching {
        val rows = JSONArray(prefs.getString("n1mm_trusts_v1", "[]"))
        List(rows.length()) { index -> rows.getJSONObject(index) }.map { row ->
            N1mmPeerTrust(
                row.getString("station"), row.getString("operator"), row.getString("interface"),
                row.getString("subnet"), row.optString("pinned").ifBlank { null },
                row.getString("contest"), row.getString("rule"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistTrusts() {
        val rows = JSONArray()
        trustedPeers.values.forEach { trust -> rows.put(JSONObject().apply {
            put("station", trust.station)
            put("operator", trust.expectedOperatorCall)
            put("interface", trust.interfaceName)
            put("subnet", trust.subnet)
            put("pinned", trust.pinnedAddress.orEmpty())
            put("contest", trust.contestName)
            put("rule", trust.ruleVersion)
        }) }
        prefs.edit().putString("n1mm_trusts_v1", rows.toString()).apply()
    }

    private fun loadPanelLayout(): ContestPanelLayout {
        val panels = prefs.getString("panel_order", null)?.split(',')?.mapNotNull { value ->
            runCatching { ContestPanel.valueOf(value) }.getOrNull()
        }.orEmpty().let { saved -> (saved + ContestPanel.entries).distinct() }
        val density = runCatching { ContestPanelDensity.valueOf(prefs.getString("panel_density", ContestPanelDensity.NORMAL.name).orEmpty()) }
            .getOrDefault(ContestPanelDensity.NORMAL)
        return ContestPanelLayout(panels, density)
    }

    private fun validation(): List<ContestValidationIssue> = buildList {
        if (activeSession.name.isBlank()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Session name is required"))
        if (activeSession.stationCallsign.isBlank()) add(ContestValidationIssue(ContestTruth.INVALID, null, "Station callsign is required"))
        if (activeSession.operators.isEmpty()) add(ContestValidationIssue(ContestTruth.INVALID, null, "At least one operator callsign is required"))
        if (activeSession.utcEnd <= activeSession.utcStart) add(ContestValidationIssue(ContestTruth.INVALID, null, "UTC end must be after UTC start"))
        if (activeSession.stationGrid.isNotBlank() && !Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$", RegexOption.IGNORE_CASE).matches(activeSession.stationGrid))
            add(ContestValidationIssue(ContestTruth.INVALID, ContestExchangeField.GRID, "Station grid must be a valid 4- or 6-character Maidenhead locator"))
        registry.require(definition.id).let { pack -> addAll(ContestRuleValidator.validate(pack)) }
    }

    fun setPage(value: ContestWorkspacePage) { workspacePage = value }
    fun updateCallsign(value: String) {
        callsign = value.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '/' }.take(16)
        val requested = callsign
        val generation = ++scpGeneration
        if (requested.length < 2) { scpSuggestions = emptyList(); return }
        scoreScope.launch {
            val rows = runCatching { scp.suggest(requested) }.getOrElse { listOf(ScpSuggestion(requested, ScpMatchState.DATABASE_UNAVAILABLE)) }
            withContext(Dispatchers.Main.immediate) {
                if (!closed && generation == scpGeneration && callsign == requested) scpSuggestions = rows
            }
        }
    }

    fun refreshScp() {
        scoreScope.launch {
            val result = runCatching { scp.refresh(manual = true) }
            withContext(Dispatchers.Main.immediate) {
                result.onSuccess { scpStatus = it; lastMessage = "SCP ${it.rowCount} calls · ${it.generatedAt.ifBlank { "generation unavailable" }}" }
                    .onFailure { lastMessage = "SCP refresh failed; last-good retained: ${it.message.orEmpty().take(120)}" }
                updateCallsign(callsign)
            }
        }
    }

    fun deleteScp() { scp.delete(); scpStatus = scp.status(); scpSuggestions = emptyList(); lastMessage = "SCP private cache deleted; no Contest or QSO data changed" }
    fun updateRstSent(value: String) { rstSent = value.filter(Char::isDigit).take(3) }
    fun updateRstReceived(value: String) { rstReceived = value.filter(Char::isDigit).take(3) }
    fun setExchange(value: String) {
        exchangeText = value.take(80)
        receivedExchange = ContestExchangeEngine().parse(definition, exchangeText)
    }
    fun setExchangeField(field: ContestExchangeField, value: String) {
        receivedExchange = receivedExchange.toMutableMap().apply { put(field, value.take(24)) }
        exchangeText = definition.receivedExchange.mapNotNull(receivedExchange::get).joinToString(" ")
    }

    fun selectDefinition(id: ContestDefinitionId) {
        if (activeSession.state !in setOf(ContestSessionState.DRAFT, ContestSessionState.READY, ContestSessionState.STOPPED)) return
        definition = registry.require(id).definition
        activeSession = activeSession.copy(definitionId = definition.id, ruleVersion = definition.version,
            name = definition.humanName, category = activeSession.category.copy(mode = definition.mode))
        rstSent = if (definition.mode == ContestMode.CW) "599" else "59"
        rstReceived = rstSent
        saveSession()
        n1mmConfig = loadN1mmConfig()
        rebuildN1mmController()
    }

    fun newSession() {
        pause("NEW SESSION")
        val now = Instant.now().epochSecond
        activeSession = ContestSession(ContestSessionId(UUID.randomUUID().toString()), definition.id, definition.version,
            definition.humanName, now, now + 86_400, operatingContext().stationCallsign.value,
            operatingContext().stationGrid.value, ContestEntityInfo(), ContestCategory(mode = definition.mode),
            listOf(operatingContext().operatorCallsign.value).filter(String::isNotBlank))
        saveSession()
        clearEntry()
    }

    fun cloneSession() {
        pause("CLONE SESSION")
        activeSession = activeSession.copy(id = ContestSessionId(UUID.randomUUID().toString()),
            name = "${activeSession.name} copy", state = ContestSessionState.DRAFT, networkArmed = false,
            keyerArmed = false, score = ContestScoreSnapshot())
        saveSession()
        clearEntry()
    }

    fun updateSession(value: ContestSession) {
        if (activeSession.state in setOf(ContestSessionState.DRAFT, ContestSessionState.READY, ContestSessionState.STOPPED)) {
            activeSession = value.copy(id = activeSession.id, definitionId = definition.id, ruleVersion = definition.version,
                networkArmed = false, keyerArmed = false)
        }
    }

    fun saveSession() {
        store.saveSession(activeSession.copy(networkArmed = false, keyerArmed = false))
        prefs.edit().putString("last_session_id", activeSession.id.value).apply()
        lastMessage = if (validation().any { it.truth in setOf(ContestTruth.INVALID, ContestTruth.INCOMPLETE) })
            "Session saved with validation items; START remains blocked" else "Contest session saved; nothing was armed"
    }

    fun closeSession() {
        if (activeSession.state == ContestSessionState.RUNNING) return
        activeSession = when (activeSession.state) {
            ContestSessionState.PAUSED -> sessionController.transition(sessionController.transition(activeSession, ContestSessionState.STOPPED), ContestSessionState.CLOSED)
            ContestSessionState.DRAFT, ContestSessionState.READY, ContestSessionState.STOPPED -> sessionController.transition(activeSession, ContestSessionState.CLOSED)
            ContestSessionState.CLOSED -> activeSession
            ContestSessionState.RUNNING -> activeSession
        }
        networkArmed = false
        n1mm.close()
        lastMessage = "Contest session closed"
    }

    fun startSession() {
        if (!foreground) { lastMessage = "Return to foreground before starting Contest"; return }
        val invalid = validation().filter { it.truth in setOf(ContestTruth.INVALID, ContestTruth.INCOMPLETE) }
        if (invalid.isNotEmpty()) { lastMessage = "Start blocked: ${invalid.first().reason}"; return }
        activeSession = when (activeSession.state) {
            ContestSessionState.DRAFT -> sessionController.transition(
                sessionController.transition(activeSession, ContestSessionState.READY), ContestSessionState.RUNNING)
            ContestSessionState.READY, ContestSessionState.PAUSED, ContestSessionState.STOPPED ->
                sessionController.transition(activeSession, ContestSessionState.RUNNING)
            else -> activeSession
        }
        keyerAdapter.selectProfile(activeSession)
        lastMessage = "Contest running; Keyer and N1MM remain separately armed"
        reconcileNetwork()
    }

    fun pause(reason: String = "OPERATOR") {
        if (activeSession.state == ContestSessionState.RUNNING)
            activeSession = sessionController.transition(activeSession, ContestSessionState.PAUSED)
        networkArmed = false
        n1mm.close()
        keyer()?.stop(KeyerStopReason.ContextChanged)
        lastMessage = "Contest paused · $reason"
    }

    fun changeRole(role: ContestOperatingRole) {
        if (activeSession.role == role) return
        keyer()?.stop(KeyerStopReason.ProfileChanged)
        activeSession = sessionController.changeRole(activeSession, role)
        keyerAdapter.selectProfile(activeSession)
        lastMessage = "Contest role changed; pending Keyer and repeat-CQ work cleared"
    }

    fun setNetworkArmed(value: Boolean) {
        networkArmed = value && n1mmConfig.enabled && activeSession.state == ContestSessionState.RUNNING && foreground
        if (!networkArmed) n1mm.close() else reconcileNetwork()
        lastMessage = if (networkArmed) "N1MM explicitly armed under ${n1mmConfig.mode}" else "N1MM stopped"
    }

    fun configureNetwork(enabled: Boolean, mode: String, lan: Boolean, bindAddress: String) {
        val selectedMode = runCatching { N1mmMode.valueOf(mode) }.getOrDefault(N1mmMode.OFF)
        val effectiveEnabled = enabled && selectedMode != N1mmMode.OFF
        val effectiveBind = if (lan) bindAddress.trim().take(64).ifBlank { "127.0.0.1" } else "127.0.0.1"
        prefs.edit().putBoolean("n1mm_enabled", effectiveEnabled).putString("n1mm_mode", selectedMode.name)
            .putBoolean("n1mm_lan_opt_in", lan).putString("n1mm_bind_address", effectiveBind).apply()
        n1mmConfig = loadN1mmConfig()
        rebuildN1mmController()
        lastMessage = "N1MM configuration saved unarmed · ${n1mmConfig.mode} · ${n1mmConfig.bindAddress}"
    }

    fun setPeerTrust(station: String, trusted: Boolean) {
        val peer = (n1mm.peerSnapshots() + peerCache).firstOrNull { it.station == station } ?: return
        if (trusted) trustedPeers[station] = N1mmPeerTrust(peer.station, peer.operatorCall, n1mmConfig.interfaceName,
            peer.address, peer.address, definition.cabrilloContestName, definition.version.value)
        else trustedPeers.remove(station)
        persistTrusts()
        rebuildN1mmController()
        lastMessage = "Peer $station ${if (trusted) "trusted for this exact contest/address contract" else "trust removed"}; network remains unarmed"
    }

    fun reviewTrustedMode() { lastMessage = "Trusted LAN requires exact peer, operator, interface, contest, rule and address match" }

    private fun reconcileNetwork() {
        if (networkArmed && foreground && activeSession.state == ContestSessionState.RUNNING && !n1mm.active)
            runCatching { n1mm.start() }.onFailure { lastMessage = "N1MM start rejected: ${it.message}" }
        if ((!networkArmed || !foreground || activeSession.state != ContestSessionState.RUNNING) && n1mm.active) n1mm.close()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) pause("BACKGROUND") else reconcileNetwork()
    }

    fun reserveSerial(qsoId: String): ContestSerialReservation? = if (definition.serialRequired)
        serials.reserve(activeSession, ContestKeyerAdapter.reservationOwner(qsoId, operatingContext().generation)) else null

    fun logCurrent(): ContestMutationReceipt {
        if (activeSession.state != ContestSessionState.RUNNING)
            return ContestMutationReceipt(false, detail = "Start the Contest session before logging")
        val context = operatingContext()
        val band = ContestBand.entries.firstOrNull { it.label.equals(context.band.value, true) }
            ?: return ContestMutationReceipt(false, detail = "Current band is outside the Contest rule")
        val mode = when {
            context.mode.value.startsWith("CW", true) -> ContestMode.CW
            context.mode.value in setOf("USB", "LSB", "SSB") -> ContestMode.SSB
            context.mode.value in setOf("FT8", "FT4") -> ContestMode.DIGITAL
            else -> return ContestMutationReceipt(false, detail = "Current radio mode is outside the Contest rule")
        }
        val qsoId = UUID.randomUUID().toString()
        val reservation = reserveSerial(qsoId)
        val received = ContestExchangeEngine().parse(definition, exchangeText)
        val sent = buildMap {
            if (reservation != null) put(ContestExchangeField.SERIAL, reservation.serial.toString())
        }
        val draft = ContestQsoDraft(qsoId, callsign, Instant.now().epochSecond, context.receiveFrequencyHz.value,
            band, mode, if (mode == ContestMode.CW) "599" else "59", if (mode == ContestMode.CW) "599" else "59",
            sent = sent, received = received)
        val staged = runCatching { store.stageQso(activeSession, draft, context.mode.value) }.getOrDefault(false)
        val receipt = if (staged) ContestMutationReceipt(true, qsoId, false,
            "Saved to the temporary Contest session log; merge to Logbook remains explicit")
        else {
            reservation?.let { runCatching { serials.release(it.id, activeSession.id) } }
            ContestMutationReceipt(false, qsoId, false, "Temporary Contest log rejected a duplicate or invalid entry")
        }
        if (receipt.saved) {
            rebuildScore()
            callsign = ""; exchangeText = ""
        }
        lastMessage = receipt.detail
        return receipt
    }

    fun dispatchKeyer(intent: ContestKeyerIntent): KeyerDispatchResult = keyerAdapter.submit(
        intent, activeSession, callsign, if (intent.mode == ContestMode.CW) "599" else "59",
        if (intent.mode == ContestMode.CW) "599" else "59", exchangeText).also { result ->
        lastMessage = when (result) {
            is KeyerDispatchResult.Accepted -> "Contest keyer intent accepted by the one Keyer controller"
            is KeyerDispatchResult.Rejected -> "Contest keyer intent blocked: ${result.detail}"
        }
    }

    fun opportunity(callsign: String, band: String, mode: String): ContestOpportunityState? {
        if (activeSession.state != ContestSessionState.RUNNING) return null
        val contestBand = ContestBand.entries.firstOrNull { it.label.equals(band, true) } ?: return null
        val contestMode = when (mode.uppercase(Locale.US)) { "FT8", "FT4" -> ContestMode.DIGITAL; "CW" -> ContestMode.CW; "SSB", "USB", "LSB" -> ContestMode.SSB; else -> return null }
        return opportunityEvaluator.evaluate(ContestOpportunityInput(activeSession, callsign, contestBand, contestMode,
            ContestEntityInfo(), priorDrafts(), snapshot().claims, Instant.now().epochSecond))
    }

    private fun priorDrafts(sessionId: ContestSessionId = activeSession.id): List<ContestQsoDraft> {
        return store.stagedQsos(sessionId, includeMerged = true, limit = 10_000).map(ContestSessionStore.StagedQso::draft)
    }

    fun digitalCompatible(): Boolean = activeSession.state != ContestSessionState.RUNNING ||
        definition.allowedModes.any { it in setOf(ContestMode.DIGITAL, ContestMode.MIXED) }

    fun sendCurrentMessage() {
        val type = when { callsign.isBlank() -> if (activeSession.role == ContestOperatingRole.RUN) ContestKeyerIntentType.CQ else ContestKeyerIntentType.MY_CALL
            exchangeText.isBlank() -> ContestKeyerIntentType.SEND_EXCHANGE
            else -> { logCurrent(); return }
        }
        dispatchKeyer(ContestKeyerIntent(type, activeSession.id, definition.id, activeSession.role,
            activeSession.category.mode, operatingContext().generation,
            mapOf("CALL" to callsign, "EXCHANGE" to exchangeText), false))
    }

    fun clearEntry() { callsign = ""; exchangeText = ""; receivedExchange = emptyMap() }

    fun updateLayout(value: ContestPanelLayout) {
        panelLayout = value.copy(panels = value.panels.distinct())
        prefs.edit().putString("panel_order", panelLayout.panels.joinToString(",", transform = ContestPanel::name))
            .putString("panel_density", panelLayout.density.name).apply()
    }

    fun updateQso(id: String, call: String, sent: String, received: String) {
        val row = store.stagedQsos(activeSession.id, true, 10_000).firstOrNull { it.draft.qsoId == id }
            ?: run { lastMessage = "Contest session entry was not found"; return }
        if (!store.updateStagedQso(activeSession.id, row.draft.copy(callsign = call.uppercase(Locale.US).take(24), rstSent = sent.take(4), rstReceived = received.take(4)))) {
            lastMessage = "Merged Contest entries are immutable; repair the canonical QSO in Logbook"
            return
        }
        rebuildScore()
        lastMessage = "Temporary Contest entry updated; canonical Logbook remains unchanged"
    }

    fun deleteQso(id: String) {
        if (!store.deleteStagedQso(activeSession.id, id)) {
            lastMessage = "Merged Contest entries cannot be deleted from the temporary log"
            return
        }
        rebuildScore()
        lastMessage = "Temporary Contest entry deleted; canonical Logbook remains unchanged"
    }

    fun mergeToLogbook(): Int {
        val candidates = store.stagedQsos(activeSession.id, includeMerged = false, limit = 10_000)
        var merged = 0
        candidates.forEach { staged ->
            val reservation = store.reservations(activeSession.id).firstOrNull {
                it.state == ContestSerialState.RESERVED && it.owner.contains(staged.draft.qsoId)
            }
            val receipt = mutationAdapter.save(activeSession, definition, staged.draft, reservation)
            store.markMergeResult(activeSession.id, staged.draft.qsoId,
                receipt.qsoId.takeIf { receipt.saved }, receipt.qsoId.takeIf { receipt.saved }?.let { "local:${staged.draft.createdAt}:$it" }, receipt.detail)
            if (receipt.saved) merged++
        }
        rebuildScore()
        lastMessage = if (candidates.isEmpty()) "No unmerged Contest entries" else
            "Merged $merged/${candidates.size} Contest entries through canonical QSO authority; failures remain retryable"
        return merged
    }

    private fun rebuildScore() {
        val generation = ++scoreGeneration
        val requestedSession = activeSession
        val requestedDefinition = definition
        scoreScope.launch {
            val score = ContestScoreEngine().rebuild(
                requestedDefinition,
                priorDrafts(requestedSession.id),
                Instant.now().epochSecond,
            )
            val updated = withContext(Dispatchers.Main.immediate) {
                if (closed || generation != scoreGeneration || activeSession.id != requestedSession.id) null
                else activeSession.copy(score = score).also { activeSession = it }
            }
            if (updated != null) withContext(Dispatchers.IO) { store.saveSession(updated) }
        }
    }

    fun previewExport(kind: String) {
        val rows = priorDrafts().asSequence()
        exportPreview = if (kind.equals("ADIF", true)) ContestExport.adif(activeSession, definition, rows).take(30).joinToString("\n")
        else ContestExport.cabrillo(activeSession, definition, activeSession.score, rows).let { result ->
            "${result.state}\n" + result.issues.joinToString("\n") { "${it.truth}: ${it.field} · ${it.reason}" } +
                result.lines.take(30).joinToString("\n", prefix = if (result.issues.isEmpty()) "" else "\n")
        }
        lastMessage = "$kind preview generated locally; no file or upload was created"
    }

    fun workspaceState(bandMapRows: List<ContestBandMapRow> = emptyList(), clusterRows: List<ContestBandMapRow> = emptyList()): ContestWorkspaceState {
        val context = operatingContext()
        val staged = store.stagedQsos(activeSession.id, includeMerged = true, limit = 10_000)
        val calls = staged.groupingBy { it.draft.callsign.uppercase(Locale.US) }.eachCount()
        val reviewRows = staged.take(100).map { row ->
            val qso = row.draft
            val invalid = qso.callsign.isBlank() || qso.frequencyHz <= 0
            ContestReviewRow(qso.qsoId, qso.callsign, qso.createdAt, qso.frequencyHz, qso.band.label, qso.mode.name,
                qso.rstSent, qso.rstReceived, networkOrigin = qso.networkOriginId.isNotBlank(),
                duplicate = (calls[qso.callsign.uppercase(Locale.US)] ?: 0) > 1, invalid = invalid,
                reviewRequired = invalid || row.mergeState == "FAILED", zeroPoint = false, mergeState = row.mergeState, issue = row.issue)
        }
        val livePeers = n1mm.peerSnapshots()
        if (livePeers.isNotEmpty()) peerCache = livePeers
        val peers = (livePeers + peerCache).distinctBy(N1mmPeerSnapshot::station).map { peer ->
            ContestNetworkPeer(peer.station, peer.address, peer.operatorCall, peer.version, peer.contestName,
                peer.lastSeen, peer.station in trustedPeers)
        }
        val opportunity = callsign.takeIf(String::isNotBlank)?.let { opportunity(it, context.band.value, context.mode.value) }
        return ContestWorkspaceState(activeSession, definition, definitions, workspacePage, callsign, rstSent, rstReceived,
            receivedExchange, exchangeText, opportunity?.dupe ?: ContestDupeState.UNKNOWN,
            opportunity?.newMultipliers.orEmpty(), activeSession.score, validation(), wavelogBinding(),
            context.band.value.ifBlank { "UNAVAILABLE" }, context.mode.value.ifBlank { "UNAVAILABLE" }, context.receiveFrequencyHz.value,
            profiles.activeProfile().let { "${it.name} · ${keyer()?.snapshot()?.state ?: "UNAVAILABLE"}" },
            panelLayout, bandMapRows.take(40), clusterRows.take(40), reviewRows, staged.size > 100,
            ContestNetworkState(n1mmConfig.enabled, networkArmed, n1mm.active, n1mmConfig.mode.name,
                n1mmConfig.bindAddress, n1mmConfig.tcpPort, n1mmConfig.stationName, n1mmConfig.lanBroadcastOptIn,
                peers, n1mm.diagnosticCounters(), n1mm.diagnosticEvents().lastOrNull()?.safeReason.orEmpty()),
            exportPreview, lastMessage, scpStatus, scpSuggestions)
    }

    override fun snapshot() = ContestReadOnlySnapshot(activeSession, claims = emptyList(),
        n1mmEnabled = n1mmConfig.enabled, n1mmArmed = networkArmed, n1mmPeers = n1mm.peerSnapshots().size,
        lastError = lastMessage.takeIf { it.contains("error", true) || it.contains("reject", true) }.orEmpty())

    override fun close() {
        if (closed) return
        closed = true
        scoreScope.cancel()
        networkArmed = false
        n1mm.close()
        keyer()?.stop(KeyerStopReason.ContextChanged)
        store.close()
    }
}

class DxChaserInputAdapter(
    private val digi: DigiController,
    private val operatingContext: () -> OperatingContextSnapshot,
    private val foreground: () -> Boolean,
    private val keyer: () -> KeyerQueueSnapshot,
    private val contest: ContestRuntime,
    private val cooldowns: () -> List<DxChaserCooldownSnapshot>,
    private val rarity: () -> Map<String, DxChaserRarity>,
    private val evidence: () -> List<DxChaserProviderEvidence> = { emptyList() },
) : DxChaserLocalDecodePort {
    override fun snapshot(): DxChaserInputSnapshot {
        val context = operatingContext()
        val digiState = digi.integrationSnapshot()
        val now = Instant.now().epochSecond
        val contestRows = digiState.decodes.asSequence().map(DigiDecodeEvent::callsign).filter(String::isNotBlank).distinct().take(100)
            .associate { call ->
                val state = contest.opportunity(call, bandForFrequency(digiState.dialFrequencyHz), digiState.mode.name)
                call.uppercase(Locale.US) to DxChaserContestOpportunity(
                    validBandMode = state?.validBandMode?.let { it == ContestTruth.VALID },
                    duplicate = state?.dupe?.let { it == ContestDupeState.DUPLICATE },
                    newMultipliers = state?.newMultipliers?.map { it.name }?.toSet().orEmpty(),
                    workedMultipliers = state?.workedMultipliers?.map { it.name }?.toSet().orEmpty(),
                    unknownMultipliers = state?.unknownMultipliers?.map { it.name }?.toSet().orEmpty(),
                    expectedExchangeHint = state?.expectedExchangeHint.orEmpty(), claimedBy = state?.claimedBy,
                )
            }
        val contestSnapshot = DxChaserContestSnapshot(contest.activeSession.id.value,
            contest.activeSession.state == ContestSessionState.RUNNING, contest.digitalCompatible(), contestRows)
        val queue = keyer()
        val safety = DxChaserSafetySnapshot(
            radioConnected = context.connected.value, receiveActive = digiState.rxActive && !digiState.txActive,
            routeHealthy = digiState.audioHealthy, digiAudioHealthy = digiState.audioHealthy,
            txActive = digiState.txActive, sequenceActive = digiState.exchange.state !in setOf(
                FtExchangeState.IDLE, FtExchangeState.COMPLETE, FtExchangeState.FAILED, FtExchangeState.STOPPED),
            foreground = foreground(), digiModeEligible = digiState.mode in setOf(DigiMode.FT8, DigiMode.FT4),
            localModemAuthority = digiState.localModemAuthority, txEnabledByOperator = digiState.txEnabled,
            contestCompatible = contest.digitalCompatible(), keyerIdle = queue.active == null && queue.pending == null,
            rxConfirmed = digiState.txPhase != DigiTxPhase.RX_UNCONFIRMED,
        )
        val local = digiState.decodes.filter {
            it.automaticFtEligible(digiState.mode.name, digiState.dialFrequencyHz, digiState.sessionId, digiState.capturedSlotStartMillis)
        }.takeLast(500).map { event ->
            val needs = buildMap {
                if (!event.worked) put(DxChaserNeedDimension.DXCC, DxChaserNeedState.NEEDED)
                if (!event.confirmed) put(DxChaserNeedDimension.BAND_ENTITY, DxChaserNeedState.NEEDED)
            }
            val text = event.text.uppercase(Locale.US)
            DxChaserLocalDecode(
                id = event.id, sessionId = event.sessionId,
                slotIdentity = "${event.sessionId}:${event.slotStartMillis}:${event.mode}",
                slotStartMillis = event.slotStartMillis, epochSeconds = event.epoch,
                source = when (event.decodeSource) {
                    DigiDecodeSource.LIVE_CAPTURE -> DxChaserDecodeSource.LIVE_CAPTURE
                    DigiDecodeSource.REDECODE_LIVE_SLOT -> DxChaserDecodeSource.REDECODE_LIVE_SLOT
                    DigiDecodeSource.REFERENCE_RECORDING -> DxChaserDecodeSource.REFERENCE_RECORDING
                    DigiDecodeSource.COMPANION -> DxChaserDecodeSource.COMPANION
                },
                exactSlotTiming = event.exactSlotTiming, mode = event.mode,
                band = bandForFrequency(event.dialFrequencyHz), dialFrequencyHz = event.dialFrequencyHz,
                audioFrequencyHz = event.audioHz.toInt(), callsign = event.callsign,
                baseCallsign = DigiFtParser.baseCall(event.callsign), grid = event.grid, entity = event.country,
                snr = event.snr.toInt(), message = event.text,
                messageType = when {
                    text.startsWith("CQ ") -> DxChaserMessageType.CQ
                    text.contains(context.stationCallsign.value.uppercase(Locale.US)) -> DxChaserMessageType.ADDRESSED_TO_OPERATOR
                    else -> DxChaserMessageType.BYSTANDER
                },
                stationProfileId = context.stationProfileId.value, radioIdentity = context.radioIdentity.value,
                needs = DxChaserNeedFacts(needs, event.worked, event.confirmed, event.watchlisted),
            )
        }.toList()
        return DxChaserInputSnapshot(context.generation, foreground(), now, context.stationProfileId.value,
            context.stationCallsign.value, context.stationGrid.value, context.radioIdentity.value,
            context.radioFamily.value, digiState.dialFrequencyHz, bandForFrequency(digiState.dialFrequencyHz),
            digiState.mode.name, digiState.sessionId, digiState.capturedSlotStartMillis, safety, local,
            evidence().take(500), rarity(), cooldowns().take(100), contest = contestSnapshot)
    }
}

class DxChaserRuntime(
    context: Context,
    private val database: QsoDatabase,
    private val digi: DigiController,
    operatingContext: () -> OperatingContextSnapshot,
    foreground: () -> Boolean,
    keyer: () -> KeyerQueueSnapshot,
    private val contest: ContestRuntime,
    private val requestReceiveReview: (DxChaserActionIntent) -> Unit,
    private val openWorkspaceAction: (DxChaserActionIntent) -> Unit,
    evidence: () -> List<DxChaserProviderEvidence> = { emptyList() },
) : AutoCloseable {
    internal val store = DxChaserStore(context)
    private val settingsStore = DxChaserSettingsStore(context)
    var settings by mutableStateOf(settingsStore.load()); private set
    private lateinit var controller: DxChaserController
    private val input = DxChaserInputAdapter(digi, operatingContext, foreground, keyer, contest,
        { store.activeCooldowns(Instant.now().epochSecond) }, store::rarity, evidence)
    private var closed = false
    private var lastExchangeState = FtExchangeState.IDLE
    private var lastLoggedQsoId = ""
    var snapshot by mutableStateOf(DxChaserReadOnlySnapshot()); private set
    var lastMessage by mutableStateOf("DX Chaser inactive; opening this page never starts a session"); private set

    init {
        controller = DxChaserController(DxChaserDependencies(input, DxChaserActionPort(::routeAction),
            settings = { settings }, journal = store))
        snapshot = controller.snapshot()
    }

    private fun routeAction(intent: DxChaserActionIntent) {
        when (intent.type) {
            DxChaserActionType.PREPARE_FT_CALL -> {
                val result = digi.prepareExactLiveCall(DigiExactCallRequest(intent.localDecodeId,
                    intent.slotIdentity.substringBefore(':'), intent.slotIdentity.split(':').getOrNull(1)?.toLongOrNull() ?: 0,
                    intent.mode, intent.dialFrequencyHz ?: 0, intent.callsign))
                when (result) {
                    is DigiPrepareResult.Accepted -> { controller.prepareAccepted(); lastMessage = "Exact local decode prepared through Digi" }
                    is DigiPrepareResult.Rejected -> { controller.prepareRejected(result.reason); lastMessage = result.reason }
                }
            }
            DxChaserActionType.REQUEST_RECEIVE_BAND_REVIEW -> requestReceiveReview(intent)
            DxChaserActionType.OPEN_DX_DETAILS, DxChaserActionType.OPEN_LOGBOOK_HISTORY,
            DxChaserActionType.ADD_WATCH, DxChaserActionType.REMOVE_WATCH -> openWorkspaceAction(intent)
            DxChaserActionType.STOP_CHASE -> digi.stopSequence()
            else -> Unit
        }
        snapshot = controller.snapshot().copy(databaseCounts = store.counts())
    }

    fun startAssist() = start(DxChaserMode.ASSIST)
    fun startDryRun() = start(DxChaserMode.DRY_RUN)
    fun startChase(): Boolean {
        if (!input.snapshot().safety.preparationPermitted) {
            lastMessage = "Chase blocked by the visible Digi/Contest/Keyer safety interlocks"
            snapshot = controller.snapshot().copy(databaseCounts = store.counts())
            return false
        }
        start(DxChaserMode.CHASE_SESSION)
        return true
    }

    private fun start(mode: DxChaserMode) {
        if (closed) return
        controller.start(mode, UUID.randomUUID().toString())
        controller.refresh()
        lastMessage = "$mode started by explicit operator action"
        snapshot = controller.snapshot().copy(databaseCounts = store.counts())
    }

    fun select(candidate: DxChaserCandidateSnapshot) { controller.select(candidate); snapshot = controller.snapshot().copy(databaseCounts = store.counts()) }
    fun review(value: DxChaserCrossBandOpportunity) { controller.review(value); snapshot = controller.snapshot().copy(databaseCounts = store.counts()) }
    fun stop(reason: String = "OPERATOR_STOP") { controller.stop(reason); digi.stopSequence(); snapshot = controller.snapshot().copy(databaseCounts = store.counts()) }
    fun contextLost(reason: String) { controller.contextLost(reason); snapshot = controller.snapshot().copy(databaseCounts = store.counts()) }

    fun updateSettings(value: DxChaserSettingsDocument) {
        settings = value.clamped(); settingsStore.save(settings)
        controller.contextLost("SETTINGS_CHANGED")
        snapshot = controller.snapshot().copy(databaseCounts = store.counts())
    }

    fun poll() {
        if (closed) return
        controller.refresh()
        val state = digi.integrationSnapshot()
        val exchange = state.exchange
        if (lastExchangeState != exchange.state) {
            when {
                exchange.state !in setOf(FtExchangeState.IDLE, FtExchangeState.STOPPED, FtExchangeState.FAILED) &&
                    lastExchangeState in setOf(FtExchangeState.IDLE, FtExchangeState.STOPPED, FtExchangeState.FAILED) -> controller.sequenceStarted()
                exchange.state in setOf(FtExchangeState.R_REPORT_TX_PENDING, FtExchangeState.WAIT_RR73,
                    FtExchangeState.RR73_TX_PENDING, FtExchangeState.WAIT_FINAL_73, FtExchangeState.FINAL_73_TX_PENDING) -> controller.remoteEngaged()
                exchange.state == FtExchangeState.FAILED -> controller.qsoFailed(exchange.completionReason.ifBlank { "DIGI_SEQUENCE_FAILED" })
                exchange.state == FtExchangeState.STOPPED -> controller.stop("DIGI_OPERATOR_STOP")
            }
            lastExchangeState = exchange.state
        }
        if (state.txPhase == DigiTxPhase.RX_UNCONFIRMED) controller.qsoFailed("RX_UNCONFIRMED")
        if (state.lastLoggedQsoId.isNotBlank() && state.lastLoggedQsoId != lastLoggedQsoId) {
            val qso = database.qso(state.lastLoggedQsoId)
            val target = controller.snapshot().currentTarget?.candidate?.baseCallsign
            if (qso != null && target != null && DigiFtParser.baseCall(qso.callsign) == target) controller.qsoCompleted()
            lastLoggedQsoId = state.lastLoggedQsoId
        }
        snapshot = controller.snapshot().copy(databaseCounts = store.counts())
    }

    override fun close() {
        if (closed) return
        closed = true
        controller.close()
        store.close()
    }
}

class OperatorStopRouter(
    private val digi: DigiController,
    private val keyer: KeyerDispatchPort,
    private val repeatCq: RepeatCqController,
    private val contest: ContestRuntime,
    private val chaser: DxChaserRuntime,
    private val stopRotator: () -> Unit = {},
) {
    fun stopAll(reason: String = "OPERATOR_STOP") {
        digi.stopSequence()
        keyer.stop(KeyerStopReason.Operator)
        repeatCq.stop()
        chaser.stop(reason)
        contest.setNetworkArmed(false)
        stopRotator()
    }
}
