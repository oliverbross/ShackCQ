package app.rigweave.mobile.morse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DeckPanel = Color(0xFF1B2228)
private val DeckRaised = Color(0xFF283139)
private val DeckLine = Color(0xFF4A555D)
private val DeckInk = Color(0xFFF4F0E7)
private val DeckMuted = Color(0xFFA5ADB2)
private val DeckAmber = Color(0xFFE9A72B)
private val DeckAmberDark = Color(0xFF201708)
private val DeckHold = Color(0xFFF4C94E)
private val DeckHealthy = Color(0xFF42C77B)
private val DeckDanger = Color(0xFFE4544D)

@Composable
private fun sessionClock(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    return now
}

private fun formatElapsed(milliseconds: Long): String {
    val tenths = (milliseconds / 100) % 10
    val seconds = (milliseconds / 1_000) % 60
    val minutes = milliseconds / 60_000
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}

@Composable
internal fun AdvancedTxTrainer(store: MorseTrainerStore, corpus: MorseCorpusRepository, m32: M32PocketController, openSettings: () -> Unit) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<TxPracticeSession?>(null) }
    var loading by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("Choose a content mode in Settings, then start a sending session.") }
    var promptStartedAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var showStatistics by remember { mutableStateOf(false) }
    val active = session
    val now = sessionClock(active != null && !ended && !active.isComplete && !active.paused)

    fun saveResult(running: TxPracticeSession) {
        if (saved) return
        store.saveSession(MorseSessionRecord(MorseTrainerKind.TX, System.currentTimeMillis(), running.elapsedMillis(), running.completedItems,
            running.eventuallyCorrect, running.itemAccuracyPercent, detail = "${settings.txMode.label}; character ${running.characterAccuracyPercent}%"))
        saved = true
    }

    fun submitAnswer(value: String = answer) {
        val running = session ?: return
        val result = running.submit(value, System.currentTimeMillis() - promptStartedAt) ?: return
        answer = ""
        feedback = when {
            result.correct -> "Correct on attempt ${result.attemptsUsed}."
            result.advanced -> "Three attempts used. Expected ${result.expected}; advancing."
            else -> "Not exact. Retry ${result.attemptsUsed + 1}/${settings.txMaxAttempts}."
        }
        if (result.advanced && !running.isComplete) promptStartedAt = System.currentTimeMillis()
        if (running.isComplete) saveResult(running)
    }

    fun start() {
        scope.launch {
            loading = true
            feedback = "Loading the ${settings.txMode.label.lowercase()} deck…"
            val targets = withContext(Dispatchers.IO) { corpus.sampleTx(settings) }
            loading = false
            if (targets.isEmpty()) {
                feedback = "No items match these settings. Change the content or length filter."
                return@launch
            }
            session = TxPracticeSession(targets, settings.txMaxAttempts)
            answer = ""
            ended = false
            saved = false
            promptStartedAt = System.currentTimeMillis()
            feedback = "Session live. Send the displayed item exactly."
        }
    }

    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L || active == null || active.paused || ended) return@LaunchedEffect
        var buffer = answer
        m32.inputChunk.forEach { character ->
            if (character.isWhitespace()) {
                if (buffer.isNotBlank()) { submitAnswer(buffer); buffer = "" }
            } else if (character.isLetterOrDigit() || character in ".?/=+,@-") {
                buffer += character
                val expectedLength = session?.current?.expected?.length ?: Int.MAX_VALUE
                if (buffer.length >= expectedLength) { submitAnswer(buffer); buffer = "" }
            }
        }
        answer = buffer
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        AdvancedTitle(Icons.Outlined.Keyboard, "Morserino TX trainer", "Full MorseTrainerPro content decks with exact, three-attempt sending analysis.")
        TrainerTools(openSettings, showStatistics) { showStatistics = !showStatistics }
        if (showStatistics) SavedStatistics(store, MorseTrainerKind.TX)
        Spacer(Modifier.height(14.dp))
        AdvancedInstrument(
            state = when { loading -> "LOADING"; active == null -> "READY"; ended -> "ENDED"; active.paused -> "PAUSED"; active.isComplete -> "COMPLETE"; else -> "ITEM ${active.index + 1} / ${active.items.size}" },
            value = when { loading -> "…"; active == null -> "— — —"; active.isComplete || ended -> "${active.itemAccuracyPercent}%"; else -> active.current?.expected.orEmpty() },
            footer = if (active == null) "${settings.txMode.label} · ${settings.sessionSize} items · up to ${settings.txMaxAttempts} attempts"
                else "${formatElapsed(active.elapsedMillis(now))} · ${active.eventuallyCorrect}/${active.completedItems} eventually correct · ${active.characterAccuracyPercent}% characters",
        )
        Spacer(Modifier.height(12.dp))
        if (active != null) StatStrip(listOf("Letters" to active.letters, "Numbers" to active.numbers, "Signs" to active.signs, "Errors" to active.characterErrors))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it.uppercase().filter { character -> character.isLetterOrDigit() || character in ".?/=+,@-" }.take(24) },
            enabled = active != null && !active.isComplete && !active.paused && !ended,
            modifier = Modifier.fillMaxWidth().testTag("tx-answer"),
            singleLine = true,
            label = { Text("Sent item / M32 input") },
            supportingText = { Text("Enter submits. A wrong item remains for up to three attempts.") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitAnswer() }),
            trailingIcon = { IconButton(onClick = { submitAnswer() }, enabled = answer.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Submit") } },
        )
        Spacer(Modifier.height(8.dp))
        Text(feedback, color = when { feedback.startsWith("Correct") -> DeckHealthy; feedback.startsWith("Not") || feedback.startsWith("Three") || feedback.startsWith("No items") -> DeckDanger; else -> DeckMuted }, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::start, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = DeckAmber, contentColor = DeckAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (active == null) "Start" else "New session")
            }
            if (active != null && !active.isComplete && !ended) {
                OutlinedButton(onClick = { if (active.paused) active.resume() else active.pause(); feedback = if (active.paused) "Session paused." else "Session resumed." }) {
                    Icon(if (active.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null); Spacer(Modifier.width(6.dp)); Text(if (active.paused) "Resume" else "Pause")
                }
                OutlinedButton(onClick = { val skipped = active.skip(); answer = ""; promptStartedAt = System.currentTimeMillis(); feedback = "Skipped $skipped." }, enabled = !active.paused) {
                    Icon(Icons.Outlined.SkipNext, null); Spacer(Modifier.width(6.dp)); Text("Skip")
                }
                TextButton(onClick = { ended = true; saveResult(active); feedback = "Session ended and statistics saved with ${active.completedItems} completed items." }) { Text("End session") }
            }
        }
        if (active != null && (active.isComplete || ended)) {
            Spacer(Modifier.height(16.dp))
            SessionReport("TX session report", listOf(
                "First try" to active.firstTryCorrect.toString(), "Second try" to active.secondTryCorrect.toString(),
                "Third try" to active.thirdTryCorrect.toString(), "Failed / skipped" to active.failed.toString(),
                "Item accuracy" to "${active.itemAccuracyPercent}%", "Character accuracy" to "${active.characterAccuracyPercent}%",
                "Raw speed" to txSpeed(active, now, false), "Effective speed" to txSpeed(active, now, true),
            ))
        }
    }
}

private fun txSpeed(session: TxPracticeSession, now: Long, effective: Boolean): String {
    val minutes = session.elapsedMillis(now) / 60_000.0
    if (minutes <= 0.0) return "0.0 WPM"
    val characters = if (effective) (session.totalCharacters - session.characterErrors).coerceAtLeast(0) else session.totalCharacters
    return "%.1f WPM".format((characters / 5.0) / minutes)
}

@Composable
internal fun AdvancedCallsignTrainer(store: MorseTrainerStore, corpus: MorseCorpusRepository, m32: M32PocketController, play: (String, Int) -> Unit, stopPlayback: () -> Unit, openSettings: () -> Unit) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<CallsignSession?>(null) }
    var loading by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("Listen, copy, and submit the callsign.") }
    var promptStartedAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var showStatistics by remember { mutableStateOf(false) }
    val active = session
    val now = sessionClock(active != null && !ended && !active.isComplete && !active.paused)

    fun saveResult(running: CallsignSession) {
        if (saved) return
        store.saveSession(MorseSessionRecord(MorseTrainerKind.CALLSIGN, System.currentTimeMillis(), running.elapsedMillis(), running.attempts.size,
            running.correct, running.accuracyPercent, running.score, "${settings.callsignDifficulty.label}; ${settings.callsignCharacterWpm}/${settings.callsignEffectiveWpm} WPM"))
        saved = true
    }

    fun playCurrent() {
        val value = active?.current ?: return
        promptStartedAt = System.currentTimeMillis()
        play(value, settings.callsignRepeats)
    }
    fun submitAnswer(value: String = answer) {
        val running = session ?: return
        val expected = running.current ?: return
        val result = running.submit(value, System.currentTimeMillis() - promptStartedAt) ?: return
        store.recordCallsign(expected, result)
        answer = ""
        feedback = if (result) "Correct: $expected" else "Copied ${running.attempts.last().received}; sent $expected"
        if (!running.isComplete) playCurrent() else saveResult(running)
    }
    fun start() {
        scope.launch {
            loading = true
            feedback = "Sampling the full callsign corpus…"
            val sampled = withContext(Dispatchers.IO) { corpus.sampleCallsigns(settings) }
            val calls = if (settings.callsignSourceMode == CallsignSourceMode.REVIEW) {
                (store.reviewCallsigns() + sampled).distinct().take(settings.callsignSessionSize)
            } else sampled
            loading = false
            val created = CallsignSession(calls, settings.callsignCharacterWpm)
            session = created
            ended = false
            saved = false
            answer = ""
            feedback = "Listen…"
            created.current?.let { promptStartedAt = System.currentTimeMillis(); play(it, settings.callsignRepeats) }
        }
    }

    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L || active == null || active.paused || ended) return@LaunchedEffect
        var buffer = answer
        m32.inputChunk.forEach { character ->
            if (character.isWhitespace()) {
                if (buffer.isNotBlank()) { submitAnswer(buffer); buffer = "" }
            } else if (character.isLetterOrDigit() || character == '/') buffer = (buffer + character).take(16)
        }
        answer = buffer
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        AdvancedTitle(Icons.Outlined.RecordVoiceOver, "Callsign trainer", "Full callsign database, Farnsworth timing, repeats, noise, pause, and item-level results.")
        TrainerTools(openSettings, showStatistics) { showStatistics = !showStatistics }
        if (showStatistics) SavedStatistics(store, MorseTrainerKind.CALLSIGN)
        Spacer(Modifier.height(14.dp))
        AdvancedInstrument(
            state = when { loading -> "LOADING"; active == null -> "READY"; ended -> "ENDED"; active.paused -> "PAUSED"; active.isComplete -> "COMPLETE"; else -> "CALL ${active.index + 1} / ${active.callsigns.size}" },
            value = when { active == null -> "LISTEN"; active.isComplete || ended -> "${active.accuracyPercent}%"; active.paused -> "PAUSED"; else -> "COPY" },
            footer = if (active == null) "${settings.callsignCharacterWpm}/${settings.callsignEffectiveWpm} WPM · ${settings.callsignDifficulty.label} · ${settings.callsignSessionSize} calls · ${settings.callsignRepeats}×"
                else "${formatElapsed(active.elapsedMillis(now))} · ${active.correct}/${active.attempts.size} correct · score ${active.score}",
        )
        if (active != null) {
            Spacer(Modifier.height(10.dp))
            StatStrip(listOf("Correct" to active.correct, "Incorrect" to (active.attempts.size - active.correct), "Total" to active.attempts.size, "Accuracy" to active.accuracyPercent))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(answer, { answer = it.uppercase().filter { character -> character.isLetterOrDigit() || character == '/' }.take(16) },
            enabled = active != null && !active.isComplete && !active.paused && !ended, modifier = Modifier.fillMaxWidth().testTag("callsign-answer"), singleLine = true,
            label = { Text("Copied callsign") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitAnswer() }),
            trailingIcon = { IconButton(onClick = { submitAnswer() }, enabled = answer.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Submit") } })
        Spacer(Modifier.height(8.dp))
        Text(feedback, color = if (feedback.startsWith("Correct")) DeckHealthy else if (feedback.startsWith("Copied")) DeckDanger else DeckMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::start, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = DeckAmber, contentColor = DeckAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (active == null) "Start" else "New session")
            }
            OutlinedButton(onClick = ::playCurrent, enabled = active != null && !active.isComplete && !active.paused && !ended) { Icon(Icons.Outlined.Replay, null); Spacer(Modifier.width(6.dp)); Text("Repeat") }
            if (active != null && !active.isComplete && !ended) {
                OutlinedButton(onClick = { if (active.paused) { active.resume(); playCurrent() } else { active.pause(); stopPlayback() }; feedback = if (active.paused) "Session paused." else "Session resumed." }) {
                    Icon(if (active.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null); Spacer(Modifier.width(6.dp)); Text(if (active.paused) "Resume" else "Pause")
                }
                TextButton(onClick = { stopPlayback(); ended = true; saveResult(active); feedback = "Session ended and statistics saved." }) { Text("End session") }
            }
        }
        if (active != null && active.attempts.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("SESSION RESULTS", color = DeckAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            active.attempts.asReversed().forEach { attempt ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (attempt.correct) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel, null, tint = if (attempt.correct) DeckHealthy else DeckDanger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(attempt.expected, color = DeckInk, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                    Text(if (attempt.correct) "correct" else "${attempt.received} · ${attempt.differences.size} character errors", color = DeckMuted, fontSize = 12.sp)
                }
            }
            val lastCall = active.attempts.last().expected
            val learning = store.callsignProgress()[lastCall] ?: CallsignProgress()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { store.setCallsignHard(lastCall, !learning.hard) }, label = { Text(if (learning.hard) "Unmark hard" else "Mark hard") })
                AssistChip(onClick = { store.setCallsignSuspended(lastCall, !learning.suspended) }, label = { Text(if (learning.suspended) "Unsuspend" else "Suspend") })
                AssistChip(onClick = { store.resetCallsign(lastCall) }, label = { Text("Reset item") })
                AssistChip(onClick = { store.setCallsignHard(lastCall, false); store.setCallsignSuspended(lastCall, false) }, label = { Text("Promote") })
            }
        }
        if (active != null && (active.isComplete || ended)) {
            Spacer(Modifier.height(12.dp))
            SessionReport("Callsign session report", listOf("Correct" to "${active.correct}/${active.attempts.size}", "Accuracy" to "${active.accuracyPercent}%", "Score" to active.score.toString(), "Average response" to "${active.averageResponseMillis} ms"))
        }
    }
}

@Composable
internal fun AdvancedMorseMachineTrainer(store: MorseTrainerStore, m32: M32PocketController, play: (String, Int) -> Unit, stopPlayback: () -> Unit, openSettings: () -> Unit) {
    val settings = store.settings
    var session by remember { mutableStateOf<MorseMachineSession?>(null) }
    var ended by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf<Char?>(null) }
    var feedback by remember { mutableStateOf("Choose a lesson, full set, custom set, weak-character deck, or confusable pair.") }
    var startedAt by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var showStatistics by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val active = session

    fun saveResult(running: MorseMachineSession) {
        if (saved) return
        store.saveSession(MorseSessionRecord(MorseTrainerKind.MACHINE, System.currentTimeMillis(),
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0), running.attempts, running.correct,
            running.accuracyPercent, detail = "${settings.machineSet.label}; lesson ${running.lesson}; ${settings.machineWpm} WPM"))
        saved = true
    }

    fun playCurrent() { active?.let { reveal = null; it.replay(); play(it.current.toString(), 1) } }
    fun answerCharacter(character: Char) {
        val running = session ?: return
        val result = running.answer(character) ?: return
        reveal = result.expected
        store.saveMachineProgress(running.progress)
        if (result.correct) {
            feedback = if (result.promoted != null) "Correct. Added ${result.promoted} to lesson ${running.lesson}." else "Correct. Next character."
            playCurrent()
        } else {
            feedback = "Incorrect. The character was ${result.expected}; it stays in the weighted deck."
            if (settings.machineBuzzer) play("HHHH", 1)
        }
    }

    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L || active == null || active.paused || ended) return@LaunchedEffect
        m32.inputChunk.firstOrNull { !it.isWhitespace() }?.let(::answerCharacter)
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        AdvancedTitle(Icons.Outlined.Psychology, "Morse Machine", "Seven character sets, lesson progression, custom decks, weak-item weighting, and confusable-pair drills.")
        TrainerTools(openSettings, showStatistics) { showStatistics = !showStatistics }
        if (showStatistics) SavedStatistics(store, MorseTrainerKind.MACHINE)
        Spacer(Modifier.height(14.dp))
        AdvancedInstrument(
            state = when { active == null -> "READY"; ended -> "ENDED"; active.paused -> "PAUSED"; else -> "${settings.machineDrillMode.label.uppercase()} · CHARACTER ${active.attempts + 1}" },
            value = reveal?.toString() ?: "?",
            footer = if (active == null) "${settings.machineSet.label} · ${settings.machineTrainingMode.label} · ${settings.machineWpm} WPM"
                else "${active.correct}/${active.attempts} correct · ${active.accuracyPercent}% · average ${active.averageResponseMillis} ms",
            signal = if (settings.machineShowMorse && active != null) MorseCode.pattern(active.current) else null,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField("", { value -> value.firstOrNull { !it.isWhitespace() }?.let(::answerCharacter) }, enabled = active != null && !active.paused && !ended,
            modifier = Modifier.fillMaxWidth().testTag("machine-answer"), singleLine = true, label = { Text("Type the character / M32 input") })
        Spacer(Modifier.height(8.dp))
        Text(feedback, color = when { feedback.startsWith("Correct") -> DeckHealthy; feedback.startsWith("Incorrect") -> DeckDanger; else -> DeckMuted }, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val created = MorseMachineSession(settings, store.loadMachineProgress())
                session = created; ended = false; reveal = null; feedback = "Listen…"; startedAt = System.currentTimeMillis(); saved = false
                created.replay(); play(created.current.toString(), 1)
            },
                colors = ButtonDefaults.buttonColors(containerColor = DeckAmber, contentColor = DeckAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (active == null) "Start" else "New session")
            }
            OutlinedButton(onClick = ::playCurrent, enabled = active != null && !active.paused && !ended) { Icon(Icons.Outlined.Replay, null); Spacer(Modifier.width(6.dp)); Text("Repeat") }
            OutlinedButton(onClick = { reveal = active?.current }, enabled = active != null && !ended) { Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Show") }
            OutlinedButton(onClick = {
                val running = active ?: return@OutlinedButton
                val missed = running.current
                running.answer('\u0000')
                running.skip()
                store.saveMachineProgress(running.progress)
                reveal = missed
                feedback = "Skipped $missed. It remains in the weighted deck."
                playCurrent()
            }, enabled = active != null && !active.paused && !ended) { Icon(Icons.Outlined.SkipNext, null); Spacer(Modifier.width(6.dp)); Text("Skip") }
            if (active != null && !ended) {
                OutlinedButton(onClick = { if (active.paused) { active.resume(); playCurrent() } else { active.pause(); stopPlayback() }; feedback = if (active.paused) "Session paused." else "Session resumed." }) {
                    Icon(if (active.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null); Spacer(Modifier.width(6.dp)); Text(if (active.paused) "Resume" else "Pause")
                }
                TextButton(onClick = { stopPlayback(); ended = true; store.saveMachineProgress(active.progress); saveResult(active); feedback = "Session ended; progress and statistics saved." }) { Text("End session") }
            }
            TextButton(onClick = { confirmReset = true }) { Text("Reset progress") }
        }
        if (active != null) {
            Spacer(Modifier.height(16.dp))
            Text("ACTIVE CHARACTERS", color = DeckAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                active.activeCharacters.forEach { character ->
                    val stat = active.progress[character] ?: CharacterProgress()
                    AssistChip(onClick = {}, label = { Text("$character ${stat.accuracyPercent}%") }, leadingIcon = { Icon(if (stat.incorrect > stat.correct) Icons.Outlined.PriorityHigh else Icons.Outlined.Check, null, Modifier.size(16.dp)) })
                }
            }
        }
        if (active != null && ended) {
            Spacer(Modifier.height(12.dp))
            SessionReport("Morse Machine report", listOf("Character set" to settings.machineSet.label, "Lesson" to active.lesson.toString(), "Total" to active.attempts.toString(), "Correct" to active.correct.toString(), "Incorrect" to active.incorrect.toString(), "Accuracy" to "${active.accuracyPercent}%"))
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset Morse Machine progress?") },
        text = { Text("This clears learned character accuracy and weak-character weighting. Saved session history is kept.") },
        confirmButton = { TextButton(onClick = { store.resetMachineProgress(); session = null; ended = false; feedback = "Character progress reset."; confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun TrainerTools(openSettings: () -> Unit, showingStatistics: Boolean, toggleStatistics: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = openSettings, label = { Text("Trainer settings") }, leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp)) })
        AssistChip(onClick = toggleStatistics, label = { Text(if (showingStatistics) "Hide statistics" else "Statistics") },
            leadingIcon = { Icon(Icons.Outlined.QueryStats, null, Modifier.size(17.dp)) })
    }
}

@Composable
private fun SavedStatistics(store: MorseTrainerStore, kind: MorseTrainerKind) {
    val history = store.sessionHistory(kind)
    val total = history.sumOf(MorseSessionRecord::total)
    val correct = history.sumOf(MorseSessionRecord::correct)
    val accuracy = if (total == 0) 0 else correct * 100 / total
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(DeckRaised, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text("SAVED STATISTICS", color = DeckAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) Text("No completed or ended sessions yet.", color = DeckMuted)
        else {
            StatStrip(listOf("Sessions" to history.size, "Items" to total, "Correct" to correct, "Accuracy" to accuracy))
            val formatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
            history.take(8).forEach { record ->
                Text("${formatter.format(Date(record.endedAtMillis))} · ${record.correct}/${record.total} · ${record.accuracyPercent}% · ${formatElapsed(record.durationMillis)}${if (record.detail.isBlank()) "" else " · ${record.detail}"}",
                    color = DeckMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun AdvancedTitle(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(10.dp), color = DeckAmberDark, modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = DeckAmber) } }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, color = DeckInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = DeckMuted, fontSize = 13.sp) }
    }
}

@Composable
private fun AdvancedInstrument(state: String, value: String, footer: String, signal: String? = null) {
    Column(Modifier.fillMaxWidth().background(DeckAmberDark, RoundedCornerShape(12.dp)).border(1.dp, DeckAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state, color = DeckAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp)); Text(value, color = DeckHold, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (!signal.isNullOrBlank()) Text(signal, color = DeckAmber, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp)); Text(footer, color = DeckInk, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatStrip(values: List<Pair<String, Int>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (label, value) -> Column(Modifier.weight(1f).background(DeckRaised, RoundedCornerShape(8.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), color = DeckInk, fontWeight = FontWeight.Bold); Text(label, color = DeckMuted, fontSize = 10.sp) } }
    }
}

@Composable
private fun SessionReport(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().background(DeckRaised, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(title, color = DeckInk, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        rows.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { pair.forEach { (label, value) -> Column(Modifier.weight(1f)) { Text(label, color = DeckMuted, fontSize = 11.sp); Text(value, color = DeckHold, fontFamily = FontFamily.Monospace) } } } }
    }
}

@Composable
internal fun AdvancedMorseSettingsDialog(settings: MorseTrainerSettings, initialTrainer: MorseTrainerKind, save: (MorseTrainerSettings) -> Unit, close: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var selectedTrainer by remember(initialTrainer) { mutableStateOf(initialTrainer) }
    AlertDialog(onDismissRequest = close, title = { Text("${selectedTrainer.label} settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            ChoiceChips(MorseTrainerKind.entries, selectedTrainer, { it.label }) { selectedTrainer = it }
            when (selectedTrainer) {
                MorseTrainerKind.TX -> {
                    SettingsHeading("Session")
                    AdvancedSlider("M32 sending speed", "${draft.characterWpm} WPM", draft.characterWpm.toFloat(), 5f..60f) { draft = draft.copy(characterWpm = it.toInt()) }
                    AdvancedSlider("Session items", draft.sessionSize.toString(), draft.sessionSize.toFloat(), 5f..200f) { draft = draft.copy(sessionSize = (it / 5).toInt() * 5) }
                    Text("Content", color = DeckMuted, fontSize = 12.sp)
                    ChoiceChips(TxContentMode.entries, draft.txMode, { it.label }) { draft = draft.copy(txMode = it) }
                    if (draft.txMode == TxContentMode.REAL_WORDS || draft.txMode == TxContentMode.MIXED) {
                        Text("Word length", color = DeckMuted, fontSize = 12.sp)
                        ChoiceChips(TxWordLength.entries, draft.txWordLength, { it.label }) { draft = draft.copy(txWordLength = it) }
                    }
                    AdvancedSlider("Maximum attempts", draft.txMaxAttempts.toString(), draft.txMaxAttempts.toFloat(), 1f..3f) { draft = draft.copy(txMaxAttempts = it.toInt()) }
                }
                MorseTrainerKind.CALLSIGN -> {
                    SettingsHeading("Source and session")
                    ChoiceChips(CallsignSourceMode.entries, draft.callsignSourceMode, { it.label }) { draft = draft.copy(callsignSourceMode = it) }
                    AdvancedSlider("Callsigns per session", draft.callsignSessionSize.toString(), draft.callsignSessionSize.toFloat(), 1f..200f) { draft = draft.copy(callsignSessionSize = it.toInt()) }
                    ChoiceChips(CallsignDifficulty.entries, draft.callsignDifficulty, { it.label }) { draft = draft.copy(callsignDifficulty = it) }
                    SettingsHeading("Callsign audio")
                    AdvancedSlider("Character speed", "${draft.callsignCharacterWpm} WPM", draft.callsignCharacterWpm.toFloat(), 5f..60f) { draft = draft.copy(callsignCharacterWpm = it.toInt(), callsignEffectiveWpm = minOf(draft.callsignEffectiveWpm, it.toInt())) }
                    AdvancedSlider("Effective speed", "${draft.callsignEffectiveWpm} WPM", draft.callsignEffectiveWpm.toFloat(), 5f..draft.callsignCharacterWpm.toFloat()) { draft = draft.copy(callsignEffectiveWpm = it.toInt()) }
                    AdvancedSlider("Pitch", "${draft.callsignPitchHz} Hz", draft.callsignPitchHz.toFloat(), 300f..1_000f) { draft = draft.copy(callsignPitchHz = (it / 10).toInt() * 10) }
                    AdvancedSlider("Volume", "${draft.callsignVolumePercent}%", draft.callsignVolumePercent.toFloat(), 0f..100f) { draft = draft.copy(callsignVolumePercent = it.toInt()) }
                    Text("Play each callsign", color = DeckMuted, fontSize = 12.sp)
                    ChoiceChips(listOf(1, 3), draft.callsignRepeats, { "${it}×" }) { draft = draft.copy(callsignRepeats = it) }
                    if (draft.callsignRepeats == 3) {
                        ChoiceChips(RepeatDelayMode.entries, draft.repeatDelayMode, { it.label }) { draft = draft.copy(repeatDelayMode = it) }
                        if (draft.repeatDelayMode == RepeatDelayMode.CUSTOM) AdvancedSlider("Repeat delay", "%.1f s".format(draft.repeatDelayMillis / 1_000.0), draft.repeatDelayMillis.toFloat(), 500f..5_000f) { draft = draft.copy(repeatDelayMillis = (it / 100).toInt() * 100) }
                    }
                    Text("Atmospheric noise", color = DeckMuted, fontSize = 12.sp)
                    ChoiceChips(MorseNoiseLevel.entries, draft.callsignNoiseLevel, { it.label }) { draft = draft.copy(callsignNoiseLevel = it) }
                    AdvancedSlider("Noise filter", "${draft.callsignAudioFilterHz} Hz", draft.callsignAudioFilterHz.toFloat(), 300f..1_200f) { draft = draft.copy(callsignAudioFilterHz = (it / 10).toInt() * 10) }
                }
                MorseTrainerKind.MACHINE -> {
                    SettingsHeading("Morse Machine audio")
                    AdvancedSlider("Speed", "${draft.machineWpm} WPM", draft.machineWpm.toFloat(), 5f..40f) { draft = draft.copy(machineWpm = it.toInt()) }
                    AdvancedSlider("Pitch", "${draft.machinePitchHz} Hz", draft.machinePitchHz.toFloat(), 300f..1_000f) { draft = draft.copy(machinePitchHz = (it / 50).toInt() * 50) }
                    AdvancedSlider("Volume", "${draft.machineVolumePercent}%", draft.machineVolumePercent.toFloat(), 10f..100f) { draft = draft.copy(machineVolumePercent = (it / 5).toInt() * 5) }
                    SettingsHeading("Character deck")
                    ChoiceChips(MachineCharacterSet.entries, draft.machineSet, { it.label }) { draft = draft.copy(machineSet = it, machineLesson = 1) }
                    ChoiceChips(MachineTrainingMode.entries, draft.machineTrainingMode, { it.label }) { draft = draft.copy(machineTrainingMode = it) }
                    ChoiceChips(MachineDrillMode.entries, draft.machineDrillMode, { it.label }) { draft = draft.copy(machineDrillMode = it) }
                    if (draft.machineTrainingMode == MachineTrainingMode.LESSON) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { draft = draft.copy(machineLesson = (draft.machineLesson - 1).coerceAtLeast(1)) }) { Text("−") }
                            Text("Lesson ${draft.machineLesson} · ${draft.machineLesson + 1} characters", Modifier.weight(1f), textAlign = TextAlign.Center)
                            OutlinedButton(onClick = { draft = draft.copy(machineLesson = (draft.machineLesson + 1).coerceAtMost(maxOf(1, draft.machineSet.characters.length - 1))) }) { Text("+") }
                            TextButton(onClick = { draft = draft.copy(machineTrainingMode = MachineTrainingMode.ALL) }) { Text("All") }
                        }
                    }
                    if (draft.machineTrainingMode == MachineTrainingMode.CUSTOM) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { draft = draft.copy(machineCharacters = draft.machineSet.characters) }) { Text("Select all") }
                            TextButton(onClick = { draft = draft.copy(machineCharacters = "") }) { Text("Clear all") }
                            Text("${draft.machineCharacters.length} selected", color = DeckMuted, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            draft.machineSet.characters.toList().distinct().forEach { character ->
                                FilterChip(character in draft.machineCharacters, onClick = {
                                    val chosen = draft.machineCharacters.toMutableList()
                                    if (character in chosen) chosen.remove(character) else chosen.add(character)
                                    draft = draft.copy(machineCharacters = chosen.joinToString(""))
                                }, label = { Text(character.toString()) })
                            }
                        }
                    }
                    if (draft.machineDrillMode == MachineDrillMode.CONFUSABLES) OutlinedTextField(draft.machineConfusablePair, { draft = draft.copy(machineConfusablePair = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Pair, for example U/D") })
                    SwitchSetting("Error buzzer", draft.machineBuzzer) { draft = draft.copy(machineBuzzer = it) }
                    SwitchSetting("Show Morse pattern", draft.machineShowMorse) { draft = draft.copy(machineShowMorse = it) }
                }
            }
        }
    }, confirmButton = { Button(onClick = { save(draft); close() }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun SettingsHeading(value: String) { HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 10.dp), color = DeckLine); Text(value.uppercase(), color = DeckAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp)) }

@Composable
private fun <T> ChoiceChips(values: List<T>, selected: T, label: (T) -> String, select: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value -> FilterChip(selected == value, { select(value) }, { Text(label(value)) }) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AdvancedSlider(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Text(value, color = DeckAmber, fontFamily = FontFamily.Monospace) }
    Slider(position.coerceIn(range.start, range.endInclusive), update, valueRange = range)
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, update) }
}
