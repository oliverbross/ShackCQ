package app.rigweave.mobile.morse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MorseGraphite = Color(0xFF111519)
private val MorsePanel = Color(0xFF1B2228)
private val MorseRaised = Color(0xFF283139)
private val MorseLine = Color(0xFF4A555D)
private val MorseInk = Color(0xFFF4F0E7)
private val MorseMuted = Color(0xFFA5ADB2)
private val MorseAmber = Color(0xFFE9A72B)
private val MorseAmberDark = Color(0xFF201708)
private val MorseHold = Color(0xFFF4C94E)
private val MorseHealthy = Color(0xFF42C77B)
private val MorseDanger = Color(0xFFE4544D)

@Composable
fun MorseScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MorseTrainerStore(context.applicationContext) }
    val corpus = remember { MorseCorpusRepository(context.applicationContext) }
    val audio = remember { MorseAudioPlayer() }
    val m32 = remember { M32PocketController(context.applicationContext) }
    var trainer by rememberSaveable { mutableStateOf(MorseTrainerKind.TX) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var audioJob by remember { mutableStateOf<Job?>(null) }

    fun stopPlayback() {
        audioJob?.cancel()
        audioJob = null
        audio.stop()
    }
    fun play(text: String, repeats: Int = 1) {
        stopPlayback()
        audioJob = scope.launch { audio.play(text, store.settings, repeats) }
    }
    DisposableEffect(Unit) { onDispose { stopPlayback(); m32.dispose() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(MorseGraphite).testTag("morse-screen")) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 20.dp else 12.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MORSE", color = MorseAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Training deck", color = MorseInk, fontSize = if (wide) 28.sp else 23.sp, fontWeight = FontWeight.SemiBold)
                    Text("Three focused trainers · local progress · M32 Pocket ready", color = MorseMuted, fontSize = 13.sp)
                }
                FilledTonalButton(onClick = { showSettings = true }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MorseRaised)) {
                    Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(8.dp)); Text("Settings")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MorseTrainerKind.entries.forEach { item ->
                    FilterChip(selected = trainer == item, onClick = { stopPlayback(); trainer = item },
                        label = { Text(item.label) }, leadingIcon = {
                            Icon(when (item) {
                                MorseTrainerKind.TX -> Icons.Outlined.Keyboard
                                MorseTrainerKind.CALLSIGN -> Icons.Outlined.RecordVoiceOver
                                MorseTrainerKind.MACHINE -> Icons.Outlined.Psychology
                            }, null)
                        }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MorseAmberDark,
                            selectedLabelColor = MorseAmber, selectedLeadingIconColor = MorseAmber))
                }
            }
            Spacer(Modifier.height(12.dp))
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1.7f).fillMaxHeight()) {
                    TrainerSurface(trainer, store, corpus, m32, ::play, ::stopPlayback)
                }
                M32Panel(m32, store.settings, Modifier.weight(0.8f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1.55f).fillMaxWidth()) { TrainerSurface(trainer, store, corpus, m32, ::play, ::stopPlayback) }
                Spacer(Modifier.height(12.dp))
                M32Panel(m32, store.settings, Modifier.weight(0.85f).fillMaxWidth())
            }
        }
    }
    if (showSettings) AdvancedMorseSettingsDialog(store.settings, store::update) { showSettings = false }
}

@Composable
private fun TrainerSurface(kind: MorseTrainerKind, store: MorseTrainerStore, corpus: MorseCorpusRepository, m32: M32PocketController, play: (String, Int) -> Unit, stopPlayback: () -> Unit) {
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp), color = MorsePanel, tonalElevation = 0.dp) {
        when (kind) {
            MorseTrainerKind.TX -> AdvancedTxTrainer(store.settings, corpus, m32)
            MorseTrainerKind.CALLSIGN -> AdvancedCallsignTrainer(store.settings, corpus, m32, play, stopPlayback)
            MorseTrainerKind.MACHINE -> AdvancedMorseMachineTrainer(store, m32, play, stopPlayback)
        }
    }
}

@Composable
private fun TxTrainer(settings: MorseTrainerSettings, m32: M32PocketController) {
    var session by remember(settings.sessionSize, settings.groupLength, settings.kochCharacters) { mutableStateOf<TxPracticeSession?>(null) }
    var answer by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("Press Start, then send each group with a paddle or keyboard.") }
    val active = session
    fun submitAnswer(value: String = answer) {
        val result = session?.submit(value) ?: return
        feedback = if (result.correct) "Correct · clean group" else "Check spacing and character order"
        answer = ""
    }
    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L) return@LaunchedEffect
        var buffer = answer
        m32.inputChunk.forEach { character ->
            if (character.isWhitespace()) {
                if (buffer.isNotBlank()) { submitAnswer(buffer); buffer = "" }
            } else if (character.isLetterOrDigit() || character in ".?/=") {
                buffer = (buffer + character).take(settings.groupLength)
                if (buffer.length >= (session?.current?.expected?.length ?: Int.MAX_VALUE)) { submitAnswer(buffer); buffer = "" }
            }
        }
        answer = buffer
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TrainerTitle(Icons.Outlined.Keyboard, "Morserino TX trainer", "Send what you see. Enter completes each group.")
        Spacer(Modifier.height(18.dp))
        InstrumentDisplay(
            eyebrow = if (active == null) "READY" else "GROUP ${minOf(active.index + 1, active.items.size)} / ${active.items.size}",
            value = active?.current?.expected ?: "— — — — —",
            footer = if (active?.isComplete == true) "Session complete · ${active.accuracyPercent}% character accuracy"
                else "${settings.characterWpm} WPM · Koch ${settings.kochCharacters} · ${settings.groupLength}-character groups",
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(answer, { value -> answer = value.uppercase().filter { it.isLetterOrDigit() || it in ".?/=" }.take(settings.groupLength) },
            enabled = active != null && !active.isComplete, modifier = Modifier.fillMaxWidth().testTag("tx-answer"), singleLine = true,
            label = { Text("Sent group / M32 USB or HID input") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitAnswer() }),
            trailingIcon = { IconButton(onClick = ::submitAnswer, enabled = answer.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Submit group") } })
        Spacer(Modifier.height(10.dp))
        Text(feedback, color = when { feedback.startsWith("Correct") -> MorseHealthy; feedback.startsWith("Check") -> MorseDanger; else -> MorseMuted },
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { session = TxPracticeSession(settings); answer = ""; feedback = "Session live · send the displayed group" },
                colors = ButtonDefaults.buttonColors(containerColor = MorseAmber, contentColor = MorseAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Text(if (active == null) "Start" else "Restart")
            }
            if (active != null) AssistChip(onClick = {}, label = { Text("${active.perfectGroups} clean · ${active.accuracyPercent}%") })
        }
    }
}

@Composable
private fun CallsignTrainer(settings: MorseTrainerSettings, m32: M32PocketController, play: (String, Int) -> Unit) {
    var session by remember(settings.sessionSize, settings.callsignDifficulty) { mutableStateOf<CallsignSession?>(null) }
    var answer by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf("Listen, copy, then submit the callsign.") }
    val active = session
    fun playCurrent() { active?.current?.let { startedAt = System.currentTimeMillis(); play(it, settings.callsignRepeats) } }
    fun submitAnswer(value: String = answer) {
        val result = session?.submit(value, System.currentTimeMillis() - startedAt) ?: return
        feedback = if (result) "Correct" else "Sent ${session?.attempts?.lastOrNull()?.expected}"
        answer = ""
        if (session?.isComplete == false) playCurrent()
    }
    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L) return@LaunchedEffect
        var buffer = answer
        m32.inputChunk.forEach { character ->
            if (character.isWhitespace()) {
                if (buffer.isNotBlank()) { submitAnswer(buffer); buffer = "" }
            } else if (character.isLetterOrDigit() || character == '/') buffer = (buffer + character).take(12)
        }
        answer = buffer
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TrainerTitle(Icons.Outlined.RecordVoiceOver, "Callsign trainer", "Contest-style copy with Farnsworth spacing and instant scoring.")
        Spacer(Modifier.height(18.dp))
        InstrumentDisplay(
            eyebrow = if (active == null) "READY" else "CALL ${minOf(active.index + 1, active.callsigns.size)} / ${active.callsigns.size}",
            value = if (active?.isComplete == true) "${active.accuracyPercent}%" else "· · ·   — — —",
            footer = if (active?.isComplete == true) "${active.correct}/${active.attempts.size} correct · score ${active.score}"
                else "${settings.characterWpm} / ${settings.effectiveWpm} WPM · ${settings.callsignDifficulty.label}",
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(answer, { answer = it.uppercase().filter { character -> character.isLetterOrDigit() || character == '/' }.take(12) },
            enabled = active != null && !active.isComplete, modifier = Modifier.fillMaxWidth().testTag("callsign-answer"), singleLine = true,
            label = { Text("Copied callsign") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitAnswer() }),
            trailingIcon = { IconButton(onClick = ::submitAnswer, enabled = answer.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Submit callsign") } })
        Spacer(Modifier.height(10.dp))
        Text(feedback, color = when { feedback == "Correct" -> MorseHealthy; feedback.startsWith("Sent") -> MorseDanger; else -> MorseMuted })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                session = CallsignSession(settings); answer = ""; feedback = "Listen…"
                session?.current?.let { startedAt = System.currentTimeMillis(); play(it, settings.callsignRepeats) }
            }, colors = ButtonDefaults.buttonColors(containerColor = MorseAmber, contentColor = MorseAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Text(if (active == null) "Start" else "Restart")
            }
            OutlinedButton(onClick = ::playCurrent, enabled = active?.isComplete == false) {
                Icon(Icons.Outlined.Replay, null); Text("Repeat")
            }
        }
    }
}

@Composable
private fun MorseMachineTrainer(settings: MorseTrainerSettings, m32: M32PocketController, play: (String, Int) -> Unit) {
    var session by remember(settings.machineCharacters) { mutableStateOf<MorseMachineSession?>(null) }
    var answer by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf<Char?>(null) }
    var feedback by remember { mutableStateOf("Adaptive weighting gives missed characters more airtime.") }
    val active = session
    fun playCurrent() { active?.let { play(it.current.toString(), 1) } }
    fun answerCharacter(character: Char) {
        val running = session ?: return
        val expected = running.current
        val matches = running.answer(character)
        reveal = expected
        feedback = if (matches?.correct == true) "Correct · next character" else "That was $expected · it will return more often"
        playCurrent()
    }
    LaunchedEffect(m32.inputRevision) {
        if (m32.inputRevision == 0L) return@LaunchedEffect
        m32.inputChunk.firstOrNull { it.isLetterOrDigit() }?.let(::answerCharacter)
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TrainerTitle(Icons.Outlined.Psychology, "Morse Machine", "Recognise one character at a time; the mix adapts to you.")
        Spacer(Modifier.height(18.dp))
        InstrumentDisplay(
            eyebrow = if (active == null) "READY" else "ADAPTIVE CHARACTER ${active.attempts + 1}",
            value = reveal?.toString() ?: "?",
            footer = if (active == null) "${settings.machineCharacters.length} active characters · ${settings.characterWpm} WPM"
                else "${active.correct}/${active.attempts} correct · ${active.accuracyPercent}% accuracy",
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(answer, { value ->
            val character = value.uppercase().firstOrNull { it.isLetterOrDigit() }
            answer = ""
            if (character != null) answerCharacter(character)
        }, enabled = active != null, modifier = Modifier.fillMaxWidth().testTag("machine-answer"), singleLine = true,
            label = { Text("Type the character / M32 USB or HID input") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        Spacer(Modifier.height(10.dp))
        Text(feedback, color = when { feedback.startsWith("Correct") -> MorseHealthy; feedback.startsWith("That") -> MorseDanger; else -> MorseMuted },
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                session = MorseMachineSession(settings); reveal = null; feedback = "Listen…"
                session?.let { play(it.current.toString(), 1) }
            }, colors = ButtonDefaults.buttonColors(containerColor = MorseAmber, contentColor = MorseAmberDark)) {
                Icon(Icons.Outlined.PlayArrow, null); Text(if (active == null) "Start" else "Restart")
            }
            OutlinedButton(onClick = { reveal = null; playCurrent() }, enabled = active != null) {
                Icon(Icons.Outlined.Replay, null); Text("Repeat")
            }
        }
    }
}

@Composable
private fun M32Panel(controller: M32PocketController, settings: MorseTrainerSettings, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var usbMenuOpen by remember { mutableStateOf(false) }
    var bleMenuOpen by remember { mutableStateOf(false) }
    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val scanBle = { scope.launch { controller.scanBle(); bleMenuOpen = true } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (bluetoothPermissions.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) scanBle()
        else controller.bluetoothPermissionDenied()
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MorsePanel) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cable, null, tint = MorseAmber)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("M32 Pocket", color = MorseInk, fontWeight = FontWeight.SemiBold)
                    Text(controller.state.name.lowercase().replaceFirstChar(Char::uppercase), color = when (controller.state) {
                        M32ConnectionState.READY -> MorseHealthy
                        M32ConnectionState.ERROR -> MorseDanger
                        M32ConnectionState.CONNECTING -> MorseHold
                        M32ConnectionState.DISCONNECTED -> MorseMuted
                    }, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(controller.detail, color = MorseMuted, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { controller.scanUsb(); usbMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Usb, null); Spacer(Modifier.width(8.dp)); Text(controller.devices.firstOrNull { it.sessionKey == controller.selectedSessionKey }?.displayName ?: "Choose USB device")
            }
            DropdownMenu(usbMenuOpen, { usbMenuOpen = false }) {
                if (controller.devices.isEmpty()) DropdownMenuItem({ Text("No serial devices") }, onClick = { usbMenuOpen = false }, enabled = false)
                controller.devices.forEach { device -> DropdownMenuItem({ Column { Text(device.displayName); Text(device.identityLine, fontSize = 11.sp) } },
                    onClick = { controller.selectUsb(device.sessionKey); usbMenuOpen = false }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (bluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) scanBle()
                else permissionLauncher.launch(bluetoothPermissions)
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null); Spacer(Modifier.width(8.dp))
                Text(controller.bleDevices.firstOrNull { it.address == controller.selectedBleAddress }?.name ?: "Find M32 via Bluetooth")
            }
            DropdownMenu(bleMenuOpen, { bleMenuOpen = false }) {
                if (controller.bleDevices.isEmpty()) DropdownMenuItem({ Text("No Morserino-32 found") }, onClick = { bleMenuOpen = false }, enabled = false)
                controller.bleDevices.forEach { device -> DropdownMenuItem({ Column { Text(device.name); Text("BLE Serial", fontSize = 11.sp) } },
                    onClick = { controller.selectBle(device.address); bleMenuOpen = false }) }
            }
            Spacer(Modifier.height(8.dp))
            if (controller.state == M32ConnectionState.READY) OutlinedButton(onClick = { scope.launch { controller.disconnect() } },
                modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            else Button(onClick = { scope.launch { controller.connect(settings.characterWpm) } },
                enabled = controller.state != M32ConnectionState.CONNECTING, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MorseAmber, contentColor = MorseAmberDark)) { Text("Connect & start keyer") }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MorseLine)
            Text("INPUT ROUTES", color = MorseAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            InputRoute("BLE Serial", "Full M32 protocol: device setup, WPM control, keyer start and keyed characters")
            InputRoute("USB serial", "Configures protocol v1.4 and streams keyed characters at 115,200 baud")
            InputRoute("Bluetooth HID", "Paired keyboard characters go directly into the active answer field")
            InputRoute("On-screen keyboard", "Always available when no device is connected")
            Spacer(Modifier.height(12.dp))
            Text("M32 connections never key a radio. They only open the M32 training keyer.", color = MorseMuted, fontSize = 12.sp)
        }
    }
}

@Composable private fun InputRoute(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, null, tint = MorseHealthy, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp)); Column { Text(title, color = MorseInk, fontSize = 13.sp); Text(detail, color = MorseMuted, fontSize = 11.sp) }
    }
}

@Composable private fun TrainerTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(10.dp), color = MorseAmberDark, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MorseAmber) }
        }
        Spacer(Modifier.width(12.dp)); Column { Text(title, color = MorseInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MorseMuted, fontSize = 13.sp) }
    }
}

@Composable private fun InstrumentDisplay(eyebrow: String, value: String, footer: String) {
    Column(Modifier.fillMaxWidth().background(MorseAmberDark, RoundedCornerShape(12.dp)).border(1.dp, MorseAmber.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
        .padding(horizontal = 16.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(eyebrow, color = MorseAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp)); Text(value, color = MorseHold, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center); Spacer(Modifier.height(10.dp)); Text(footer, color = MorseInk, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MorseSettingsDialog(settings: MorseTrainerSettings, save: (MorseTrainerSettings) -> Unit, close: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    AlertDialog(onDismissRequest = close, title = { Text("Morse trainer settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            SettingSlider("Character speed", "${draft.characterWpm} WPM", draft.characterWpm.toFloat(), 5f..60f) {
                draft = draft.copy(characterWpm = it.toInt(), effectiveWpm = minOf(draft.effectiveWpm, it.toInt()))
            }
            SettingSlider("Effective speed", "${draft.effectiveWpm} WPM", draft.effectiveWpm.toFloat(), 5f..draft.characterWpm.toFloat()) { draft = draft.copy(effectiveWpm = it.toInt()) }
            SettingSlider("Pitch", "${draft.pitchHz} Hz", draft.pitchHz.toFloat(), 300f..1_000f) { draft = draft.copy(pitchHz = (it / 10).toInt() * 10) }
            SettingSlider("Volume", "${draft.volumePercent}%", draft.volumePercent.toFloat(), 0f..100f) { draft = draft.copy(volumePercent = it.toInt()) }
            SettingSlider("Session length", "${draft.sessionSize}", draft.sessionSize.toFloat(), 5f..100f) { draft = draft.copy(sessionSize = (it / 5).toInt() * 5) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("TX TRAINER", color = MorseAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SettingSlider("Koch characters", "${draft.kochCharacters}", draft.kochCharacters.toFloat(), 2f..KOCH_SEQUENCE.length.toFloat()) { draft = draft.copy(kochCharacters = it.toInt()) }
            SettingSlider("Group length", "${draft.groupLength}", draft.groupLength.toFloat(), 1f..8f) { draft = draft.copy(groupLength = it.toInt()) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("CALLSIGNS", color = MorseAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CallsignDifficulty.entries.forEach { option -> FilterChip(draft.callsignDifficulty == option, { draft = draft.copy(callsignDifficulty = option) }, { Text(option.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repeat each call", Modifier.weight(1f)); SingleChoiceSegmentedButtonRow {
                    listOf(1, 3).forEachIndexed { index, value -> SegmentedButton(draft.callsignRepeats == value,
                        { draft = draft.copy(callsignRepeats = value) }, SegmentedButtonDefaults.itemShape(index, 2)) { Text("${value}×") } }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("MORSE MACHINE", color = MorseAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(draft.machineCharacters, { draft = draft.copy(machineCharacters = it.uppercase().filter(Char::isLetterOrDigit)) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Active characters") }, supportingText = { Text("Missed characters are automatically weighted higher.") })
        }
    }, confirmButton = { Button(onClick = { save(draft); close() }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun SettingSlider(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Text(value, color = MorseAmber, fontFamily = FontFamily.Monospace) }
    Slider(position.coerceIn(range.start, range.endInclusive), update, valueRange = range)
}
