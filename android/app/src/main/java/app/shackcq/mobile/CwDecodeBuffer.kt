package app.shackcq.mobile

internal class CwDecodeBuffer(private val historyLimit: Int = 192) {
    private companion object {
        const val MAX_DB_WINDOW = 15
        val CONTROL_STATUS = Regex(
            "^(?:[-+]?\\d+(?:\\.\\d+)?\\s*)?" +
                "(?:AF|RF|MON|MIC|PWR|BW|KEYER|WIDTH|SHIFT|I/WID|II/SHT|AGC|ATT|PRE|NR|NB|NCH|APF|VOX|DLY|CMP|PITCH|RIT|RTQ|XIT)" +
                "(?:\\s*[-+]?\\d*(?:\\.\\d+)?\\s*(?:W|HZ|DB|ON|OFF)?)?$",
            RegexOption.IGNORE_CASE,
        )
        val TELEMETRY_VALUE = Regex(
            "^[-+]?\\d+(?:\\.\\d+)?\\s*(?:W|DB|HZ|V|A|MA|SWR)$",
            RegexOption.IGNORE_CASE,
        )
        val NESTED_DB_TELEMETRY = Regex(
            "^DB\\s*[-+]?\\d+(?:[.:]\\d+)+(?:\\s*(?:W|DB|HZ|V|A|MA|SWR))?$",
            RegexOption.IGNORE_CASE,
        )
    }
    private val pending = StringBuilder()
    private var lastWindow = ""
    private var mutedWindows = 0

    var text: String = ""
        private set

    fun feed(bytes: ByteArray): Boolean {
        bytes.forEach { pending.append((it.toInt() and 0xff).toChar()) }
        var changed = false
        while (true) {
            val end = pending.indexOf(";")
            if (end < 0) break
            val frame = resynchronizeDbFrame(pending.substring(0, end))
            pending.delete(0, end + 1)
            if (frame.startsWith("DB") && acceptWindow(frame.drop(2))) changed = true
        }
        if (pending.length > 512) pending.delete(0, pending.length - 96)
        return changed
    }

    private fun resynchronizeDbFrame(frame: String): String {
        val nestedDb = frame.lastIndexOf("DB")
        if (nestedDb <= 2) return frame
        val truncatedPayload = frame.substring(2, nestedDb)
        val nestedPayload = frame.substring(nestedDb + 2)
        val normalizedNestedPayload = nestedPayload.map { if (it == 'c') '0' else it }.joinToString("")
        val nestedIsControlDisplay =
            nestedPayload.any { it.isLowerCase() && it != 'c' } ||
                looksLikeFrequencyDisplay(normalizedNestedPayload) ||
                looksLikeKx3Status(normalizedNestedPayload)
        // A short USB read can end halfway through DB's display response. The next
        // request then arrives as DB2DB21.148.73; (for example). Re-anchor only
        // when both sides prove it is a control display, never when the nested DB
        // could be genuine CW text such as "73 DB1ABC".
        return if (
            truncatedPayload.isNotEmpty() &&
            truncatedPayload.all { it.isDigit() || it == ' ' || it in ".:+-/" } &&
            nestedIsControlDisplay
        ) frame.substring(nestedDb) else frame
    }

    fun clear() {
        text = ""
        lastWindow = ""
        mutedWindows = 0
        pending.clear()
    }

    private fun acceptWindow(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_DB_WINDOW) return false
        // KX3 segment-encoded VFO-B screens use lower-case glyph codes (for example
        // k/d/b). Decoded CW is upper-case; only lower-case c is meaningful there,
        // where the radio uses it as its slashed-zero placeholder.
        if (raw.any { it.isLowerCase() && it != 'c' }) return muteControlDisplay()
        val window = buildString(raw.length) {
            raw.forEach { character ->
                append(when {
                    character == 'c' -> '0'
                    character.code in 0x20..0x7e -> character
                    else -> ' '
                })
            }
        }
        if (looksLikeFrequencyDisplay(window) || looksLikeKx3Status(window) || looksLikeTelemetryDisplay(window)) {
            return muteControlDisplay()
        }
        val decodedWindow = suppressDecoderNoise(window)
        if (decodedWindow.isEmpty()) return false
        if (mutedWindows > 0) {
            if (looksLikeControlFragment(window)) {
                mutedWindows--
                return false
            }
            // A real text-bearing CW window ends the holdoff immediately. This keeps
            // knob fragments out without delaying decoded RX/TX text after a turn.
            mutedWindows = 0
        }

        var overlap = 0
        if (lastWindow.isNotEmpty()) {
            for (count in minOf(lastWindow.length, decodedWindow.length) downTo 1) {
                if (lastWindow.endsWith(decodedWindow.take(count))) {
                    overlap = count
                    break
                }
            }
        } else {
            overlap = decodedWindow.indexOfFirst { it != ' ' }
                .let { if (it < 0) decodedWindow.length else it }
        }

        val delta = decodedWindow.drop(overlap)
        if (delta.isEmpty()) return false
        val before = text
        text = (text + delta).takeLast(historyLimit)
        lastWindow = decodedWindow
        return text != before
    }

    private fun looksLikeFrequencyDisplay(value: String): Boolean {
        var digits = 0
        var separator = false
        value.forEach { character ->
            when {
                character.isDigit() -> digits++
                character == '.' || character == ':' -> separator = true
                character == ' ' -> Unit
                else -> return false
            }
        }
        return digits >= 4 && separator
    }

    private fun looksLikeKx3Status(value: String): Boolean =
        value.trim().let { display ->
            display.matches(Regex("(?:RX|TX)\\s+THR\\d+", RegexOption.IGNORE_CASE)) ||
                display.matches(CONTROL_STATUS) ||
                display.matches(
                    Regex(
                        "(?:[-+]?\\d+(?:\\.\\d+)?\\s+WPM|WPM(?:[A-Z]{0,3})?\\s*[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))",
                        RegexOption.IGNORE_CASE,
                    ),
                ) ||
                display.matches(
                    Regex(
                        "(?:TR|FC)\\s*[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:\\s*(?:HZ|W|DB))?",
                        RegexOption.IGNORE_CASE,
                    ),
                )
        }

    private fun looksLikeTelemetryDisplay(value: String): Boolean = value.trim().let { display ->
        if (display.equals("DB", ignoreCase = true) || display.matches(TELEMETRY_VALUE) || display.matches(NESTED_DB_TELEMETRY)) {
            return@let true
        }
        val embeddedDb = display.indexOf("DB", ignoreCase = true)
        if (embeddedDb > 0) {
            val nested = display.drop(embeddedDb + 2).trimStart()
            if (nested.isNotEmpty() && (looksLikeFrequencyDisplay(nested) || looksLikeKx3Status(nested) || nested.matches(TELEMETRY_VALUE))) {
                return@let true
            }
        }
        if (!display.startsWith("DB", ignoreCase = true)) return@let false
        val nested = display.drop(2).trimStart()
        nested.isNotEmpty() && (looksLikeFrequencyDisplay(nested) || looksLikeKx3Status(nested) || nested.matches(TELEMETRY_VALUE))
    }

    private fun looksLikeControlFragment(value: String): Boolean =
        value.trim().matches(Regex("[-+.]*\\d+(?:\\.\\d+)*"))

    private fun muteControlDisplay(): Boolean {
        mutedWindows = 30
        return false
    }

    private fun suppressDecoderNoise(value: String): String {
        if (value.trim().matches(Regex("(?:E{3,}|T{3,})(?:\\s+0)?|0"))) return ""
        return value.replace(Regex(" {2,}"), " ").trimStart()
    }
}
