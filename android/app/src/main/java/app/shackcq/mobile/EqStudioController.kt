package app.shackcq.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant

class EqStudioController(
    private val transport: UsbRadioTransport,
    private val radioState: () -> RadioState,
    val audio: EqAudioController,
    val profiles: EqProfileStore,
) {
    var path by mutableStateOf(EqPath.RX); private set
    var context by mutableStateOf(EqContext.UNKNOWN); private set
    var contextSource by mutableStateOf("Read CAT state to resolve the active EQ bucket"); private set
    var radioSnapshot by mutableStateOf<EqSnapshot?>(null); private set
    var draft by mutableStateOf<EqCurve?>(null); private set
    var loadedProfile by mutableStateOf<EqProfile?>(null); private set
    var operation by mutableStateOf(EqOperationState.STALE); private set
    var message by mutableStateOf("No live EQ curve has been read"); private set
    var trace by mutableStateOf(emptyList<String>()); private set
    var conflict by mutableStateOf<EqSnapshot?>(null); private set
    var lastSuggestion by mutableStateOf<EqSuggestion?>(null); private set
    var lastIntent by mutableStateOf<EqIntent?>(null); private set
    private var snapshotsByPath by mutableStateOf(emptyMap<EqPath, EqSnapshot>())
    private var tracesByPath by mutableStateOf(emptyMap<EqPath, List<String>>())

    val dirtyBands: Int get() = radioSnapshot?.curve?.let { baseline -> draft?.changedBands(baseline)?.size } ?: 0
    val canUseHardware: Boolean get() = radioState().connected && radioState().model.uppercase().contains("KX3")

    fun selectPath(value: EqPath) {
        if (path == value) return
        path = value; loadedProfile = null; conflict = null
        val cached = snapshotsByPath[value]
        radioSnapshot = cached; draft = cached?.curve; trace = tracesByPath[value].orEmpty()
        context = cached?.context ?: EqContext.UNKNOWN
        contextSource = cached?.contextSource ?: "Read CAT state to resolve the active EQ bucket"
        operation = when { !radioState().connected -> EqOperationState.DISCONNECTED; cached != null -> EqOperationState.LIVE_VERIFIED; else -> EqOperationState.STALE }
        message = if (cached != null) "LIVE / VERIFIED · ${value.name} loaded automatically from KX3" else "Read the ${value.name} curve from the radio"
    }

    suspend fun readBothFromRadio() {
        operation = EqOperationState.READING; message = "Automatically reading RX and TX EQ from the KX3…"; conflict = null
        runCatching {
            transport.exclusiveEqTransaction { io ->
                val firmware = runCatching { io.query("RVM;", "RVM") }.getOrNull()?.removeSuffix(";")
                val reads = linkedMapOf<EqPath, EqReadResult>()
                val unavailable = linkedMapOf<EqPath, String>()
                EqPath.entries.forEach { targetPath ->
                    runCatching {
                        val resolved = resolveLiveContext(io, targetPath, radioState())
                        require(resolved.first.writable) { resolved.first.label }
                        io.readKx3Eq(targetPath, radioState().model, firmware, resolved.first, resolved.second)
                    }.onSuccess { reads[targetPath] = it }
                        .onFailure { unavailable[targetPath] = it.message ?: "Unavailable" }
                }
                require(reads.isNotEmpty()) { unavailable.entries.joinToString(" · ") { "${it.key.name}: ${it.value}" } }
                reads to unavailable
            }
        }.onSuccess { (results, unavailable) ->
            snapshotsByPath = (snapshotsByPath - unavailable.keys) + results.mapValues { it.value.snapshot }
            tracesByPath = (tracesByPath - unavailable.keys) + results.mapValues { it.value.trace }
            loadVerifiedPath(path)
            operation = if (snapshotsByPath[path] != null) EqOperationState.LIVE_VERIFIED else EqOperationState.STALE
            message = if (unavailable.isEmpty()) "LIVE / VERIFIED · RX and TX exact values loaded automatically from KX3"
                else "LIVE / VERIFIED · ${results.keys.joinToString(" + ") { it.name }} loaded · " +
                    unavailable.entries.joinToString(" · ") { "${it.key.name} unavailable: ${it.value}" }
        }.onFailure { fail(it) }
    }

    suspend fun readFromRadio() {
        operation = EqOperationState.READING; message = "Reading all eight bands from the KX3…"; conflict = null
        runCatching {
            transport.exclusiveEqTransaction { io ->
                val resolved = resolveLiveContext(io, path, radioState())
                context = resolved.first; contextSource = resolved.second
                require(context.writable) { context.label }
                val firmware = runCatching { io.query("RVM;", "RVM") }.getOrNull()?.removeSuffix(";")
                io.readKx3Eq(path, radioState().model, firmware, context, contextSource)
            }
        }.onSuccess { result ->
            snapshotsByPath = snapshotsByPath + (path to result.snapshot); tracesByPath = tracesByPath + (path to result.trace)
            radioSnapshot = result.snapshot; draft = result.snapshot.curve; trace = result.trace
            operation = EqOperationState.LIVE_VERIFIED; message = "LIVE / VERIFIED · eight exact values read from KX3"
        }.onFailure { fail(it) }
    }

    fun setBand(index: Int, value: Int) {
        val base = draft ?: radioSnapshot?.curve ?: EqCurve.FLAT
        draft = base.withBand(index, value.coerceIn(-16, 16)); loadedProfile = null
        operation = if (draft == radioSnapshot?.curve) EqOperationState.LIVE_VERIFIED else EqOperationState.DRAFT_CHANGED
        message = if (operation == EqOperationState.DRAFT_CHANGED) "Local draft changed · radio untouched" else "Draft matches verified radio"
        audio.capture?.let { audio.rebuildPreview(requireNotNull(draft)) }
    }

    fun flatDraft() { draft = EqCurve.FLAT; loadedProfile = null; operation = EqOperationState.DRAFT_CHANGED; message = "Flat local draft · radio untouched"; audio.rebuildPreview(EqCurve.FLAT) }
    fun restoreRadioCurve() { radioSnapshot?.let { draft = it.curve; loadedProfile = null; operation = EqOperationState.LIVE_VERIFIED; message = "Draft restored to last verified radio curve"; audio.rebuildPreview(it.curve) } }
    fun undoDraft() = restoreRadioCurve()

    fun loadProfile(profile: EqProfile) {
        path = profile.path; context = profile.context; draft = profile.curve; loadedProfile = profile
        operation = EqOperationState.DRAFT_CHANGED; message = "PROFILE loaded as local draft · radio untouched"
        audio.capture?.let { audio.rebuildPreview(profile.curve) }
    }

    fun suggest(intent: EqIntent, cwPitchHz: Int? = null) {
        val baseline = draft ?: radioSnapshot?.curve ?: return
        val metrics = audio.capture?.metrics ?: run { message = "Capture a valid real sample before requesting a suggestion"; return }
        val suggestion = suggestEqCurve(baseline, metrics, intent, cwPitchHz)
        lastSuggestion = suggestion; lastIntent = intent; draft = suggestion.curve; loadedProfile = null
        operation = EqOperationState.DRAFT_CHANGED; message = "Starting-point suggestion applied to local draft · ${suggestion.confidence} confidence"
        audio.rebuildPreview(suggestion.curve)
    }

    fun makeHeadroom() {
        val current = draft ?: return
        val peak = current.values.maxOrNull() ?: return
        if (peak <= 0) { message = "Draft already has non-positive maximum gain"; return }
        val shift = minOf(peak, current.values.minOf { it + 16 })
        draft = EqCurve.of(current.values.map { it - shift }); operation = EqOperationState.DRAFT_CHANGED
        message = "Draft shifted down $shift dB for preview headroom · recheck RX AF gain or TX mic gain/compression"
        audio.rebuildPreview(requireNotNull(draft))
    }

    suspend fun applyAndVerify(overwriteConflict: Boolean = false) {
        val intended = draft ?: return fail(IllegalStateException("No draft curve exists"))
        val baseline = radioSnapshot ?: return fail(IllegalStateException("Read the radio before applying"))
        operation = EqOperationState.APPLYING; message = "Preflight and conflict check…"
        runCatching {
            transport.exclusiveEqTransaction { io ->
                val resolved = resolveLiveContext(io, path, radioState())
                require(resolved.first == baseline.context) { "Radio EQ context changed to ${resolved.first.label}; re-read before applying" }
                val live = io.readKx3Eq(path, baseline.model, baseline.firmware, baseline.context, resolved.second)
                if (live.snapshot.curve != baseline.curve && !overwriteConflict) {
                    conflict = live.snapshot
                    error("Radio curve changed since the draft baseline")
                }
                operation = EqOperationState.VERIFYING; message = "Writing bounded EQ curve and verifying all eight bands…"
                val result = io.applyKx3Eq(intended, live.snapshot)
                require(!eqTraceContainsTransmissionCommand(result.trace)) { "Transmission-capable command detected in EQ trace" }
                require(result.verified != null && result.failedBands.isEmpty()) {
                    "Readback mismatch at ${result.failedBands.joinToString { "${EQ_FREQUENCIES_HZ[it]} Hz" }}"
                }
                result
            }
        }.onSuccess { result ->
            radioSnapshot = result.verified; draft = result.verified?.curve; trace = result.trace; conflict = null
            result.verified?.let { snapshotsByPath = snapshotsByPath + (path to it); tracesByPath = tracesByPath + (path to result.trace) }
            operation = EqOperationState.LIVE_VERIFIED; message = "VERIFIED · all eight KX3 values match the intended curve"
        }.onFailure { fail(it) }
    }

    fun acceptConflictAsBaseline() {
        conflict?.let { radioSnapshot = it; context = it.context; contextSource = it.contextSource; conflict = null
            operation = EqOperationState.DRAFT_CHANGED; message = "Latest radio curve loaded as baseline; draft retained" }
    }

    fun cancelConflict() {
        conflict = null
        operation = EqOperationState.DRAFT_CHANGED
        message = "Apply cancelled · draft retained; read the radio again before applying"
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!connected) { operation = EqOperationState.DISCONNECTED; message = "CAT disconnected · draft retained; radio values are stale" }
        else if (operation == EqOperationState.DISCONNECTED) { operation = EqOperationState.STALE; message = "CAT connected · read the live KX3 EQ curve" }
    }

    fun closeSession() { audio.clear() }

    private fun loadVerifiedPath(value: EqPath) {
        val snapshot = snapshotsByPath[value] ?: return
        radioSnapshot = snapshot; draft = snapshot.curve; trace = tracesByPath[value].orEmpty()
        context = snapshot.context; contextSource = snapshot.contextSource
    }

    private fun fail(error: Throwable) {
        operation = EqOperationState.FAILED
        message = error.message ?: error.javaClass.simpleName
    }
}

private suspend fun resolveLiveContext(io: EqCatIo, path: EqPath, radio: RadioState): Pair<EqContext, String> {
    val tq = io.query("TQ;", "TQ")
    require(tq == "TQ0;") { "EQ operations are refused while transmitting" }
    val ft = io.query("FT;", "FT").removePrefix("FT").removeSuffix(";").toIntOrNull() ?: radio.txVfo
    val rxMode = parseMd(io.query("MD;", "MD")) ?: radio.mode
    val txMode = if (ft == 1) parseMd(io.query("MD$;", "MD$")) ?: radio.mode else rxMode
    val essb = runCatching { io.query("ES;", "ES") == "ES1;" }.getOrDefault(false)
    return resolveEqContext(path, EqModeState(rxMode, txMode, ft, ft == 1 || radio.split, essb))
}

fun parseMd(frame: String): String? = when (frame.removeSuffix(";").substringAfter("MD$").substringAfter("MD").toIntOrNull()) {
    1 -> "LSB"; 2 -> "USB"; 3 -> "CW"; 4 -> "FM"; 5 -> "AM"; 6 -> "DATA"; 7 -> "CW-R"; 9 -> "DATA-R"; else -> null
}
