package app.rigweave.mobile.morse

import kotlin.math.max
import kotlin.random.Random

enum class MorseTrainerKind(val label: String) {
    TX("TX Trainer"), CALLSIGN("Callsigns"), MACHINE("Morse Machine")
}

enum class CallsignDifficulty(val label: String) {
    THREE("3 chars"), FOUR("4 chars"), FIVE("5 chars"), SIX_PLUS("6+ chars"), PORTABLE("Portable"), ALL("All")
}

data class MorseTrainerSettings(
    val characterWpm: Int = 20,
    val effectiveWpm: Int = 12,
    val pitchHz: Int = 600,
    val volumePercent: Int = 70,
    val sessionSize: Int = 25,
    val kochCharacters: Int = 12,
    val groupLength: Int = 5,
    val callsignDifficulty: CallsignDifficulty = CallsignDifficulty.FIVE,
    val callsignRepeats: Int = 1,
    val machineCharacters: String = "KMURESNAPTLWIOGJHFVBYXCQZ1234567890",
) {
    fun normalized() = copy(
        characterWpm = characterWpm.coerceIn(5, 60),
        effectiveWpm = effectiveWpm.coerceIn(5, characterWpm),
        pitchHz = pitchHz.coerceIn(300, 1_000),
        volumePercent = volumePercent.coerceIn(0, 100),
        sessionSize = sessionSize.coerceIn(5, 100),
        kochCharacters = kochCharacters.coerceIn(2, KOCH_SEQUENCE.length),
        groupLength = groupLength.coerceIn(1, 8),
        callsignRepeats = if (callsignRepeats >= 3) 3 else 1,
        machineCharacters = machineCharacters.uppercase().filter { it in MACHINE_CHARACTERS }.toList().distinct().joinToString("")
            .ifBlank { "KM" },
    )
}

const val KOCH_SEQUENCE = "KMURESNAPTLWIO.GJHFVBYXCQZ1234567890?/="
const val MACHINE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

object MorseCode {
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '1' to ".----", '2' to "..---", '3' to "...--",
        '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..",
        '9' to "----.", '0' to "-----", '.' to ".-.-.-", '?' to "..--..", '/' to "-..-.",
        '=' to "-...-", '+' to ".-.-.", ',' to "--..--",
    )

    fun encode(text: String): List<String?> = text.uppercase().map { character ->
        when (character) { ' ' -> null; else -> table[character] }
    }

    fun pattern(character: Char): String = table[character.uppercaseChar()].orEmpty()
}

data class TxPracticeItem(val expected: String, val received: String = "", val correct: Boolean? = null)

class TxPracticeSession(settings: MorseTrainerSettings, random: Random = Random.Default) {
    val items: List<TxPracticeItem>
    var index: Int = 0; private set
    var correctCharacters: Int = 0; private set
    var expectedCharacters: Int = 0; private set
    var perfectGroups: Int = 0; private set
    val isComplete: Boolean get() = index >= items.size
    val current: TxPracticeItem? get() = items.getOrNull(index)
    val accuracyPercent: Int get() = if (expectedCharacters == 0) 0 else (correctCharacters * 100) / expectedCharacters

    init {
        val safe = settings.normalized()
        val pool = KOCH_SEQUENCE.take(safe.kochCharacters)
        items = List(safe.sessionSize) {
            TxPracticeItem(buildString { repeat(safe.groupLength) { append(pool[random.nextInt(pool.length)]) } })
        }
    }

    fun submit(value: String): Boolean? {
        val expected = current?.expected ?: return null
        val received = value.trim().uppercase()
        if (received.isBlank()) return null
        val matches = expected == received
        val paired = max(expected.length, received.length)
        correctCharacters += (0 until paired).count { expected.getOrNull(it) == received.getOrNull(it) }
        expectedCharacters += expected.length
        if (matches) perfectGroups++
        index++
        return matches
    }
}

object CallsignFactory {
    private val oneLetterPrefixes = listOf("K", "N", "W", "G", "F", "I", "E", "O")
    private val twoLetterPrefixes = listOf("OM", "OK", "DL", "SP", "HA", "S5", "9A", "PA", "ON", "EA", "VK", "ZL", "JA", "VE", "LU", "PY", "ZS")
    private const val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(difficulty: CallsignDifficulty, random: Random = Random.Default): String {
        val targetLength = when (difficulty) {
            CallsignDifficulty.THREE -> 3
            CallsignDifficulty.FOUR -> 4
            CallsignDifficulty.FIVE -> 5
            CallsignDifficulty.SIX_PLUS -> 6
            CallsignDifficulty.PORTABLE -> random.nextInt(4, 7)
            CallsignDifficulty.ALL -> random.nextInt(3, 7)
        }
        val baseLength = if (difficulty == CallsignDifficulty.PORTABLE) targetLength else targetLength
        val prefix = if (baseLength >= 6) twoLetterPrefixes.random(random)
            else if (baseLength <= 4 || random.nextBoolean()) oneLetterPrefixes.random(random) else twoLetterPrefixes.random(random)
        val suffixLength = (baseLength - prefix.length - 1).coerceIn(1, 3)
        val base = buildString {
            append(prefix)
            append(random.nextInt(10))
            repeat(suffixLength) { append(letters[random.nextInt(letters.length)]) }
        }
        return if (difficulty == CallsignDifficulty.PORTABLE) "$base/${listOf("P", "M", "QRP").random(random)}" else base
    }
}

data class CallsignAttempt(val expected: String, val received: String, val responseMillis: Long, val correct: Boolean)

class CallsignSession(settings: MorseTrainerSettings, random: Random = Random.Default) {
    private val safe = settings.normalized()
    val callsigns = List(safe.sessionSize) { CallsignFactory.generate(safe.callsignDifficulty, random) }
    val attempts = mutableListOf<CallsignAttempt>()
    var index: Int = 0; private set
    val current: String? get() = callsigns.getOrNull(index)
    val isComplete: Boolean get() = index >= callsigns.size
    val correct: Int get() = attempts.count(CallsignAttempt::correct)
    val accuracyPercent: Int get() = if (attempts.isEmpty()) 0 else correct * 100 / attempts.size
    val score: Int get() = safe.characterWpm * accuracyPercent

    fun submit(value: String, responseMillis: Long): Boolean? {
        val expected = current ?: return null
        val normalized = value.trim().uppercase()
        if (normalized.isBlank()) return null
        val matches = expected == normalized
        attempts += CallsignAttempt(expected, normalized, responseMillis.coerceAtLeast(0), matches)
        index++
        return matches
    }
}

data class CharacterProgress(val correct: Int = 0, val incorrect: Int = 0) {
    val weight: Double get() {
        val total = correct + incorrect
        return if (total == 0) 1.0 else (incorrect + 1.0) / (total + 1.0)
    }
}

class MorseMachineSession(settings: MorseTrainerSettings, private val random: Random = Random.Default) {
    private val characters = settings.normalized().machineCharacters.toList()
    val progress = characters.associateWith { CharacterProgress() }.toMutableMap()
    var current: Char = choose(); private set
    var attempts: Int = 0; private set
    var correct: Int = 0; private set
    val accuracyPercent: Int get() = if (attempts == 0) 0 else correct * 100 / attempts

    fun answer(value: Char): Boolean {
        val matches = value.uppercaseChar() == current
        val prior = progress.getValue(current)
        progress[current] = if (matches) prior.copy(correct = prior.correct + 1) else prior.copy(incorrect = prior.incorrect + 1)
        attempts++
        if (matches) correct++
        current = choose()
        return matches
    }

    private fun choose(): Char {
        val total = characters.sumOf { progress[it]?.weight ?: 1.0 }
        var target = random.nextDouble() * total
        for (character in characters) {
            target -= progress[character]?.weight ?: 1.0
            if (target < 0.0) return character
        }
        return characters.last()
    }
}
