package app.rigweave.mobile.morse

import android.content.Context
import kotlin.math.max
import kotlin.random.Random

enum class MorseTrainerKind(val label: String) {
    TX("TX Trainer"), CALLSIGN("Callsigns"), MACHINE("Morse Machine")
}

enum class TxContentMode(val label: String, val assetName: String?) {
    REAL_WORDS("Real words", "words.txt"),
    ABBREVIATIONS("Abbreviations", "abbreviations.txt"),
    CALLSIGNS("Callsigns", "callsigns.txt"),
    Q_CODES("Q-codes", "qr-codes.txt"),
    TOP_WORDS("Top CW words", "top-words-in-cw.txt"),
    MIXED("Mixed", null),
}

enum class TxWordLength(val label: String) {
    ANY("Any length"), THREE("3"), FOUR("4"), FIVE("5"), SIX_PLUS("6+");

    fun accepts(value: String): Boolean = when (this) {
        ANY -> true
        THREE -> value.length == 3
        FOUR -> value.length == 4
        FIVE -> value.length == 5
        SIX_PLUS -> value.length >= 6
    }
}

enum class CallsignDifficulty(val label: String) {
    THREE("3 chars"), FOUR("4 chars"), FIVE("5 chars"), SIX_PLUS("6+ chars"), PORTABLE("Portable"), ALL("All"), CWOPS("CWOps")
}

enum class RepeatDelayMode(val label: String) { AUTO("Automatic"), CUSTOM("Custom") }

enum class MorseNoiseLevel(val label: String, val amplitude: Double) {
    OFF("S0", 0.0), S1("S1", 0.015), S2("S2", 0.025), S3("S3", 0.035), S4("S4", 0.050),
    S5("S5", 0.065), S6("S6", 0.082), S7("S7", 0.10), S8("S8", 0.13), S9("S9", 0.16)
}

enum class MachineCharacterSet(val label: String, val characters: String) {
    KOCH("Koch", "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"),
    CWOPS("CWops", "TEANOIS14RHDL25G79/BVKJ80=XQZ-"),
    ABC("ABC", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    SWEDISH("Swedish", "=+NLOEIXVT/?AZHM7495CGJ813620@"),
    DANISH("Danish", "ABCDEFGHIJKLMNOPQRSTUVWXYZÆØÅ"),
    HIRAGANA("Hiragana", "いろはにほへとちりぬるをわかよえてあさきゆめみしゑひもせすん"),
    KATAKANA("Katakana", "イロハニホヘトチリヌルヲワカヨエテアサキユメミシヱヒモセスン"),
}

enum class MachineTrainingMode(val label: String) { LESSON("Lesson"), ALL("All"), CUSTOM("Custom") }
enum class MachineDrillMode(val label: String) { ADAPTIVE("Adaptive"), DUE("Weak characters"), CONFUSABLES("Confusable pair") }

data class MorseTrainerSettings(
    val characterWpm: Int = 20,
    val effectiveWpm: Int = 12,
    val pitchHz: Int = 600,
    val volumePercent: Int = 70,
    val sessionSize: Int = 25,
    val callsignSessionSize: Int = 25,
    val txMode: TxContentMode = TxContentMode.REAL_WORDS,
    val txWordLength: TxWordLength = TxWordLength.ANY,
    val txMaxAttempts: Int = 3,
    val kochCharacters: Int = 12,
    val groupLength: Int = 5,
    val callsignDifficulty: CallsignDifficulty = CallsignDifficulty.FIVE,
    val callsignRepeats: Int = 1,
    val repeatDelayMode: RepeatDelayMode = RepeatDelayMode.AUTO,
    val repeatDelayMillis: Int = 1_500,
    val noiseLevel: MorseNoiseLevel = MorseNoiseLevel.OFF,
    val audioFilterHz: Int = 700,
    val machineSet: MachineCharacterSet = MachineCharacterSet.KOCH,
    val machineTrainingMode: MachineTrainingMode = MachineTrainingMode.LESSON,
    val machineDrillMode: MachineDrillMode = MachineDrillMode.ADAPTIVE,
    val machineLesson: Int = 1,
    val machineCharacters: String = "KM",
    val machineConfusablePair: String = "U/D",
    val machineBuzzer: Boolean = true,
    val machineShowMorse: Boolean = false,
) {
    fun normalized(): MorseTrainerSettings {
        val safeSet = machineSet.characters
        val custom = machineCharacters.uppercase().filter { it in ALL_MACHINE_CHARACTERS }.toList().distinct().joinToString("")
            .ifBlank { safeSet.take(2) }
        val pairCharacters = machineConfusablePair.uppercase()
            .split('/', '|', '-', ' ')
            .mapNotNull { token -> token.firstOrNull { it in ALL_MACHINE_CHARACTERS } }
            .distinct()
            .take(2)
        val pair = if (pairCharacters.size == 2) pairCharacters.joinToString("/") else "U/D"
        return copy(
            characterWpm = characterWpm.coerceIn(5, 60),
            effectiveWpm = effectiveWpm.coerceIn(5, characterWpm.coerceIn(5, 60)),
            pitchHz = pitchHz.coerceIn(300, 1_000),
            volumePercent = volumePercent.coerceIn(0, 100),
            sessionSize = sessionSize.coerceIn(5, 200),
            callsignSessionSize = callsignSessionSize.coerceIn(1, 200),
            txMaxAttempts = txMaxAttempts.coerceIn(1, 3),
            kochCharacters = kochCharacters.coerceIn(2, KOCH_SEQUENCE.length),
            groupLength = groupLength.coerceIn(1, 8),
            callsignRepeats = if (callsignRepeats >= 3) 3 else 1,
            repeatDelayMillis = repeatDelayMillis.coerceIn(500, 5_000),
            audioFilterHz = audioFilterHz.coerceIn(300, 1_200),
            machineLesson = machineLesson.coerceIn(1, max(1, safeSet.length - 1)),
            machineCharacters = custom,
            machineConfusablePair = pair,
        )
    }
}

const val KOCH_SEQUENCE = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"
val ALL_MACHINE_CHARACTERS: String = MachineCharacterSet.entries.joinToString("") { it.characters }.toList().distinct().joinToString("")

object MorseCode {
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..",
        '9' to "----.", '0' to "-----", '.' to ".-.-.-", '?' to "..--..", '/' to "-..-.",
        '=' to "-...-", '+' to ".-.-.", ',' to "--..--", '@' to ".--.-.", '-' to "-....-",
        'Æ' to ".-.-", 'Ø' to "---.", 'Å' to ".--.-",
    )
    private val japaneseToLatin = "いIろRはBにDほHへVとSちZりNぬFるLをOわWかKよYえEてTあAさCきGゆUめMみPしJゑQひXも.せ5す4ん9".chunked(2)
        .associate { it[0] to it[1] } +
        "イIロRハBニDホHヘVトSチZリNヌFルLヲOワWカKヨYエEテTアAサCキGユUメMミPシJヱQヒXモ.セ5ス4ン9".chunked(2).associate { it[0] to it[1] }

    fun normalizedCharacter(character: Char): Char = japaneseToLatin[character] ?: character.uppercaseChar()
    fun encode(text: String): List<String?> = text.map { character -> if (character == ' ') null else table[normalizedCharacter(character)] }
    fun pattern(character: Char): String = table[normalizedCharacter(character)].orEmpty()
}

data class CharacterDifference(val position: Int, val expected: Char?, val received: Char?)

fun compareText(expected: String, received: String): List<CharacterDifference> {
    val size = max(expected.length, received.length)
    return (0 until size).mapNotNull { index ->
        val wanted = expected.getOrNull(index)
        val actual = received.getOrNull(index)
        if (wanted == actual) null else CharacterDifference(index, wanted, actual)
    }
}

data class TxAttempt(val received: String, val correct: Boolean, val responseMillis: Long, val differences: List<CharacterDifference>)
data class TxPracticeItem(val expected: String, val attempts: MutableList<TxAttempt> = mutableListOf(), var skipped: Boolean = false) {
    val complete: Boolean get() = skipped || attempts.lastOrNull()?.correct == true
    val eventuallyCorrect: Boolean get() = attempts.any(TxAttempt::correct)
}
data class TxSubmitResult(val correct: Boolean, val advanced: Boolean, val attemptsUsed: Int, val expected: String)

class TxPracticeSession(targets: List<String>, private val maxAttempts: Int = 3, val startedAtMillis: Long = System.currentTimeMillis()) {
    constructor(settings: MorseTrainerSettings, random: Random = Random.Default) : this(
        List(settings.normalized().sessionSize) {
            val safe = settings.normalized()
            val pool = KOCH_SEQUENCE.take(safe.kochCharacters)
            buildString { repeat(safe.groupLength) { append(pool[random.nextInt(pool.length)]) } }
        },
        settings.normalized().txMaxAttempts,
    )
    val items = targets.filter(String::isNotBlank).map { TxPracticeItem(it.trim().uppercase()) }
    var index: Int = 0; private set
    var paused: Boolean = false; private set
    var totalPausedMillis: Long = 0; private set
    private var pauseStartedAt: Long? = null
    val current: TxPracticeItem? get() = items.getOrNull(index)
    val isComplete: Boolean get() = index >= items.size
    val completedItems: Int get() = items.count { it.complete || it.attempts.size >= maxAttempts }
    val eventuallyCorrect: Int get() = items.count(TxPracticeItem::eventuallyCorrect)
    val perfectGroups: Int get() = eventuallyCorrect
    val firstTryCorrect: Int get() = items.count { it.attempts.firstOrNull()?.correct == true }
    val secondTryCorrect: Int get() = items.count { it.attempts.getOrNull(1)?.correct == true }
    val thirdTryCorrect: Int get() = items.count { it.attempts.getOrNull(2)?.correct == true }
    val failed: Int get() = items.count { !it.eventuallyCorrect && (it.skipped || it.attempts.size >= maxAttempts) }
    val totalCharacters: Int get() = items.take(completedItems).sumOf { it.expected.length }
    val characterErrors: Int get() = items.take(completedItems).sumOf { item ->
        when {
            item.eventuallyCorrect -> (item.attempts.size - 1).coerceAtMost(2)
            item.attempts.isNotEmpty() -> item.attempts.last().differences.size
            else -> item.expected.length
        }
    }
    val characterAccuracyPercent: Int get() = if (totalCharacters == 0) 100 else ((totalCharacters - characterErrors).coerceAtLeast(0) * 100) / totalCharacters
    val accuracyPercent: Int get() = characterAccuracyPercent
    val itemAccuracyPercent: Int get() = if (completedItems == 0) 0 else eventuallyCorrect * 100 / completedItems
    val letters: Int get() = items.take(completedItems).sumOf { it.expected.count(Char::isLetter) }
    val numbers: Int get() = items.take(completedItems).sumOf { it.expected.count(Char::isDigit) }
    val signs: Int get() = totalCharacters - letters - numbers

    fun submit(value: String, responseMillis: Long = 0): TxSubmitResult? {
        if (paused) return null
        val item = current ?: return null
        val received = value.trim().uppercase()
        if (received.isBlank()) return null
        val correct = item.expected == received
        item.attempts += TxAttempt(received, correct, responseMillis.coerceAtLeast(0), compareText(item.expected, received))
        val advanced = correct || item.attempts.size >= maxAttempts
        if (advanced) index++
        return TxSubmitResult(correct, advanced, item.attempts.size, item.expected)
    }

    fun skip(): String? {
        val item = current ?: return null
        item.skipped = true
        index++
        return item.expected
    }

    fun pause(now: Long = System.currentTimeMillis()) { if (!paused) { paused = true; pauseStartedAt = now } }
    fun resume(now: Long = System.currentTimeMillis()) { if (paused) { totalPausedMillis += now - (pauseStartedAt ?: now); pauseStartedAt = null; paused = false } }
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long = (now - startedAtMillis - totalPausedMillis - if (paused) now - (pauseStartedAt ?: now) else 0).coerceAtLeast(0)
}

object CallsignFactory {
    private val prefixes = listOf("K", "N", "W", "G", "F", "I", "E", "O", "OM", "OK", "DL", "SP", "HA", "S5", "9A", "PA", "ON", "EA", "VK", "ZL", "JA", "VE", "LU", "PY", "ZS")
    private const val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    fun generate(difficulty: CallsignDifficulty, random: Random = Random.Default): String {
        val length = when (difficulty) {
            CallsignDifficulty.THREE -> 3; CallsignDifficulty.FOUR -> 4; CallsignDifficulty.FIVE -> 5
            CallsignDifficulty.SIX_PLUS -> random.nextInt(6, 9); CallsignDifficulty.PORTABLE -> random.nextInt(4, 7); CallsignDifficulty.ALL -> random.nextInt(3, 9)
            CallsignDifficulty.CWOPS -> 5
        }
        val prefix = prefixes.filter { it.length <= length - 2 && (length < 6 || it.length >= 2) }.random(random)
        val base = buildString { append(prefix); append(random.nextInt(10)); repeat((length - prefix.length - 1).coerceIn(1, 3)) { append(letters.random(random)) } }
        return if (difficulty == CallsignDifficulty.PORTABLE) "$base/${listOf("P", "M", "QRP").random(random)}" else base
    }
}

fun callsignMatchesDifficulty(value: String, difficulty: CallsignDifficulty): Boolean = when (difficulty) {
    CallsignDifficulty.THREE -> value.length == 3 && '/' !in value
    CallsignDifficulty.FOUR -> value.length == 4 && '/' !in value
    CallsignDifficulty.FIVE -> value.length == 5 && '/' !in value
    CallsignDifficulty.SIX_PLUS -> value.length >= 6 && '/' !in value
    CallsignDifficulty.PORTABLE -> '/' in value
    CallsignDifficulty.ALL, CallsignDifficulty.CWOPS -> true
}

data class CallsignAttempt(val expected: String, val received: String, val responseMillis: Long, val correct: Boolean, val differences: List<CharacterDifference>)
class CallsignSession(callsigns: List<String>, private val speedWpm: Int, val startedAtMillis: Long = System.currentTimeMillis()) {
    constructor(settings: MorseTrainerSettings, random: Random = Random.Default) : this(
        List(settings.normalized().sessionSize) { CallsignFactory.generate(settings.normalized().callsignDifficulty, random) },
        settings.normalized().characterWpm,
    )
    val callsigns = callsigns.filter(String::isNotBlank).map { it.uppercase() }
    val attempts = mutableListOf<CallsignAttempt>()
    var index: Int = 0; private set
    var paused: Boolean = false; private set
    private var pauseStartedAt: Long? = null
    var totalPausedMillis: Long = 0; private set
    val current: String? get() = callsigns.getOrNull(index)
    val isComplete: Boolean get() = index >= callsigns.size
    val correct: Int get() = attempts.count(CallsignAttempt::correct)
    val accuracyPercent: Int get() = if (attempts.isEmpty()) 0 else correct * 100 / attempts.size
    val score: Int get() = speedWpm * accuracyPercent
    val averageResponseMillis: Long get() = if (attempts.isEmpty()) 0 else attempts.sumOf { it.responseMillis } / attempts.size

    fun submit(value: String, responseMillis: Long): Boolean? {
        if (paused) return null
        val expected = current ?: return null
        val received = value.trim().uppercase()
        if (received.isBlank()) return null
        val matches = expected == received
        attempts += CallsignAttempt(expected, received, responseMillis.coerceAtLeast(0), matches, compareText(expected, received))
        index++
        return matches
    }
    fun pause(now: Long = System.currentTimeMillis()) { if (!paused) { paused = true; pauseStartedAt = now } }
    fun resume(now: Long = System.currentTimeMillis()) { if (paused) { totalPausedMillis += now - (pauseStartedAt ?: now); pauseStartedAt = null; paused = false } }
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long = (now - startedAtMillis - totalPausedMillis - if (paused) now - (pauseStartedAt ?: now) else 0).coerceAtLeast(0)
}

data class CharacterProgress(val correct: Int = 0, val incorrect: Int = 0, val responseMillis: Long = 0, val samples: Int = 0) {
    val weight: Double get() { val total = correct + incorrect; return if (total == 0) 1.0 else (incorrect + 1.0) / (total + 1.0) }
    val accuracyPercent: Int get() = if (correct + incorrect == 0) 0 else correct * 100 / (correct + incorrect)
    val averageResponseMillis: Long get() = if (samples == 0) 0 else responseMillis / samples
}
data class MachineAnswerResult(val expected: Char, val correct: Boolean, val promoted: Char?)

class MorseMachineSession(settings: MorseTrainerSettings, persisted: Map<Char, CharacterProgress> = emptyMap(), private val random: Random = Random.Default) {
    private val safe = settings.normalized()
    private val fullSet = safe.machineSet.characters.toList()
    var lesson: Int = safe.machineLesson; private set
    private val custom = safe.machineCharacters.toList()
    val progress = (fullSet + custom).distinct().associateWith { persisted[it] ?: CharacterProgress() }.toMutableMap()
    val activeCharacters = mutableListOf<Char>()
    var current: Char = 'K'; private set
    var attempts: Int = 0; private set
    var correct: Int = 0; private set
    var incorrect: Int = 0; private set
    var startedAtMillis: Long = System.currentTimeMillis(); private set
    var lastPromptAtMillis: Long = startedAtMillis; private set
    var paused = false; private set
    val accuracyPercent: Int get() = if (attempts == 0) 0 else correct * 100 / attempts
    val averageResponseMillis: Long get() {
        val samples = progress.values.sumOf { it.samples }
        return if (samples == 0) 0 else progress.values.sumOf { it.responseMillis } / samples
    }

    init { rebuildActive(); current = choose(); lastPromptAtMillis = System.currentTimeMillis() }

    fun answer(value: Char, now: Long = System.currentTimeMillis()): MachineAnswerResult? {
        if (paused) return null
        val expected = current
        val matches = MorseCode.normalizedCharacter(value) == MorseCode.normalizedCharacter(expected)
        val prior = progress.getValue(expected)
        val response = (now - lastPromptAtMillis).coerceAtLeast(0)
        progress[expected] = if (matches) prior.copy(correct = prior.correct + 1, responseMillis = prior.responseMillis + response, samples = prior.samples + 1)
            else prior.copy(incorrect = prior.incorrect + 1, responseMillis = prior.responseMillis + response, samples = prior.samples + 1)
        attempts++
        var promoted: Char? = null
        if (matches) {
            correct++
            if (safe.machineTrainingMode == MachineTrainingMode.LESSON && progress.getValue(expected).correct >= 10 && activeCharacters.size < fullSet.size) {
                promoted = fullSet[activeCharacters.size]
                activeCharacters += promoted
                lesson = activeCharacters.size - 1
            }
            current = choose()
            lastPromptAtMillis = now
        } else incorrect++
        return MachineAnswerResult(expected, matches, promoted)
    }

    fun replay(now: Long = System.currentTimeMillis()) { lastPromptAtMillis = now }
    fun skip(now: Long = System.currentTimeMillis()) { current = choose(); lastPromptAtMillis = now }
    fun pause() { paused = true }
    fun resume(now: Long = System.currentTimeMillis()) { paused = false; lastPromptAtMillis = now }

    private fun rebuildActive() {
        activeCharacters.clear()
        activeCharacters += when (safe.machineTrainingMode) {
            MachineTrainingMode.LESSON -> fullSet.take((lesson + 1).coerceAtMost(fullSet.size))
            MachineTrainingMode.ALL -> fullSet
            MachineTrainingMode.CUSTOM -> custom
        }
        if (activeCharacters.isEmpty()) activeCharacters += fullSet.take(2)
    }

    private fun choose(): Char {
        if (safe.machineDrillMode == MachineDrillMode.CONFUSABLES) {
            val pair = safe.machineConfusablePair.split('/').mapNotNull(String::firstOrNull).distinct()
            if (pair.size == 2) return pair[random.nextInt(pair.size)]
        }
        val pool = if (safe.machineDrillMode == MachineDrillMode.DUE) {
            val weak = activeCharacters.filter { (progress[it]?.incorrect ?: 0) >= (progress[it]?.correct ?: 0) }
            if (weak.isNotEmpty() && random.nextDouble() < 0.8) weak else activeCharacters
        } else activeCharacters
        val total = pool.sumOf { progress[it]?.weight ?: 1.0 }
        var target = random.nextDouble() * total
        pool.forEach { character -> target -= progress[character]?.weight ?: 1.0; if (target < 0) return character }
        return pool.last()
    }
}

class MorseCorpusRepository(context: Context) {
    private val assets = context.applicationContext.assets

    fun sampleTx(settings: MorseTrainerSettings, random: Random = Random.Default): List<String> {
        val safe = settings.normalized()
        if (safe.txMode == TxContentMode.MIXED) {
            val modes = TxContentMode.entries.filter { it.assetName != null }
            val perMode = (safe.sessionSize + modes.size - 1) / modes.size
            return modes.flatMap { mode -> sampleAsset(mode.assetName!!, perMode, random) { value -> mode != TxContentMode.REAL_WORDS || safe.txWordLength.accepts(value) } }
                .shuffled(random).take(safe.sessionSize)
        }
        return sampleAsset(safe.txMode.assetName!!, safe.sessionSize, random) { value -> safe.txMode != TxContentMode.REAL_WORDS || safe.txWordLength.accepts(value) }
    }

    fun sampleCallsigns(settings: MorseTrainerSettings, random: Random = Random.Default): List<String> {
        val safe = settings.normalized()
        val asset = if (safe.callsignDifficulty == CallsignDifficulty.CWOPS) "cwops.txt" else "callsigns.txt"
        val values = sampleAsset(asset, safe.callsignSessionSize, random) { callsignMatchesDifficulty(it, safe.callsignDifficulty) }
        return if (values.size >= safe.callsignSessionSize) values else values + List(safe.callsignSessionSize - values.size) { CallsignFactory.generate(safe.callsignDifficulty, random) }
    }

    private fun sampleAsset(name: String, count: Int, random: Random, accept: (String) -> Boolean): List<String> {
        val reservoir = ArrayList<String>(count)
        var accepted = 0
        assets.open("morse/$name").bufferedReader().use { reader ->
            reader.forEachLine { raw ->
                val value = raw.trim().uppercase()
                if (value.isEmpty() || !accept(value) || value.any { !it.isLetterOrDigit() && it !in "/?=+,.@-" }) return@forEachLine
                accepted++
                if (reservoir.size < count) reservoir += value else {
                    val replacement = random.nextInt(accepted)
                    if (replacement < count) reservoir[replacement] = value
                }
            }
        }
        return reservoir.shuffled(random)
    }
}
