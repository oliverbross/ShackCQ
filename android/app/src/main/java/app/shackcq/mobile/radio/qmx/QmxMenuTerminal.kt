// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

data class QmxTerminalCell(val character: Char = ' ', val foreground: Int = 0, val reverse: Boolean = false)
data class QmxTerminalSnapshot(
    val open: Boolean,
    val rows: List<String>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val unknownAnsiParameters: Set<Int>,
)

class QmxAnsiTerminal(private val columns: Int = 80, private val rowCount: Int = 24) {
    private val cells = Array(rowCount) { Array(columns) { QmxTerminalCell() } }
    private var row = 0
    private var column = 0
    private var foreground = 0
    private var reverse = false
    private var escapeState = 0
    private val escape = StringBuilder()
    private val unknownParameters = linkedSetOf<Int>()
    var cursorVisible = true
        private set

    init { require(columns in 20..160 && rowCount in 8..48) }

    @Synchronized
    fun feed(bytes: ByteArray) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            val character = value.toChar()
            if (escapeState == 1) {
                if (character == '[') { escapeState = 2; escape.clear() } else escapeState = 0
                continue
            }
            if (escapeState == 2) {
                if (value in 0x40..0x7e) { applyCsi(character); escapeState = 0 }
                else if (escape.length < 32) escape.append(character)
                continue
            }
            when (character) {
                '\u001b' -> escapeState = 1
                '\r' -> column = 0
                '\n' -> { column = 0; row = (row + 1).coerceAtMost(rowCount - 1) }
                '\b' -> column = (column - 1).coerceAtLeast(0)
                '\t' -> column = ((column + 8) and 7.inv()).coerceAtMost(columns)
                else -> if (value in 32..126 && row in cells.indices && column in 0 until columns) {
                    cells[row][column] = QmxTerminalCell(character, foreground, reverse)
                    column++
                }
            }
        }
    }

    private fun applyCsi(final: Char) {
        val parameters = escape.toString()
        if (parameters.startsWith("?25") && final in setOf('h', 'l')) { cursorVisible = final == 'h'; return }
        when (final) {
            'H', 'f' -> {
                val values = parameters.split(';')
                row = ((values.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, rowCount - 1)
                column = ((values.getOrNull(1)?.toIntOrNull() ?: 1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> resetScreen()
            'K' -> if (row in cells.indices) for (index in column.coerceAtMost(columns - 1) until columns) cells[row][index] = QmxTerminalCell()
            'm' -> {
                val values = if (parameters.isBlank()) listOf(0) else parameters.split(';').mapNotNull(String::toIntOrNull)
                values.forEach { value -> when {
                    value == 0 -> { foreground = 0; reverse = false }
                    value == 7 -> reverse = true
                    value == 27 -> reverse = false
                    value in 30..37 -> foreground = value
                    else -> if (unknownParameters.size < 16) unknownParameters += value
                } }
            }
        }
    }

    @Synchronized
    fun reset() { resetScreen(); row = 0; column = 0; foreground = 0; reverse = false; cursorVisible = true; escapeState = 0; escape.clear(); unknownParameters.clear() }

    private fun resetScreen() { for (r in cells.indices) for (c in cells[r].indices) cells[r][c] = QmxTerminalCell() }

    @Synchronized
    fun snapshot(open: Boolean): QmxTerminalSnapshot = QmxTerminalSnapshot(
        open,
        cells.map { line -> line.joinToString("") { it.character.toString() } },
        row,
        column.coerceAtMost(columns - 1),
        cursorVisible,
        unknownParameters.toSet(),
    )
}

enum class QmxTerminalKey(val bytes: ByteArray) {
    UP(byteArrayOf(0x1b, 0x5b, 0x41)),
    DOWN(byteArrayOf(0x1b, 0x5b, 0x42)),
    RIGHT(byteArrayOf(0x1b, 0x5b, 0x43)),
    LEFT(byteArrayOf(0x1b, 0x5b, 0x44)),
    ENTER(byteArrayOf(0x0d)),
    ESCAPE(byteArrayOf(0x1b)),
    BACKSPACE(byteArrayOf(0x7f));
}

interface QmxMenuTerminalPort {
    fun open(interfaceNumber: Int, onBytes: (ByteArray) -> Unit): Boolean
    fun write(bytes: ByteArray): Boolean
    /** Adapter must navigate the radio's own Exit terminal item before releasing the interface. */
    fun closeSafely(): Boolean
}

class QmxMenuTerminalController(
    private val port: QmxMenuTerminalPort,
    private val terminal: QmxAnsiTerminal = QmxAnsiTerminal(),
    private val maximumInputCharacters: Int = 128,
) : AutoCloseable {
    @Volatile private var open = false
    private var inputCharacters = 0

    init { require(maximumInputCharacters in 16..512) }

    @Synchronized
    fun open(profile: QmxUsbCompositeProfile, operatorExplicitlyOpened: Boolean): Boolean {
        if (open || !operatorExplicitlyOpened) return false
        val interfaceNumber = profile.extraCdcControlInterfaces.minOrNull() ?: return false
        terminal.reset()
        inputCharacters = 0
        open = port.open(interfaceNumber, terminal::feed)
        if (open && !port.write(byteArrayOf(0x0d))) { port.closeSafely(); open = false }
        return open
    }

    @Synchronized
    fun sendKey(key: QmxTerminalKey): Boolean {
        if (!open) return false
        if (key == QmxTerminalKey.BACKSPACE && inputCharacters > 0) inputCharacters--
        if (key == QmxTerminalKey.ENTER) inputCharacters = 0
        return port.write(key.bytes)
    }

    @Synchronized
    fun sendPrintable(text: String): Boolean {
        if (!open || text.isEmpty() || text.any { it.code !in 32..126 }) return false
        if (inputCharacters + text.length > maximumInputCharacters) return false
        val bytes = text.toByteArray(Charsets.US_ASCII)
        if (!port.write(bytes)) return false
        inputCharacters += text.length
        return true
    }

    fun snapshot(): QmxTerminalSnapshot = terminal.snapshot(open)
    fun includeTranscriptInSupportBundle(): Boolean = false

    @Synchronized
    override fun close() {
        if (!open) return
        port.closeSafely()
        open = false
        inputCharacters = 0
    }
}
