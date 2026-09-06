// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

sealed interface QmxResponse {
    val raw: String
    data class Frequency(val vfo: QmxVfo, val hertz: Long, override val raw: String) : QmxResponse
    data class Mode(val mode: QmxMode, override val raw: String) : QmxResponse
    data class Filter(val hertz: Int, override val raw: String) : QmxResponse
    data class AfGain(val quarterDbSteps: Int, override val raw: String) : QmxResponse { val decibels = quarterDbSteps / 4.0 }
    data class RfGain(val decibels: Int, override val raw: String) : QmxResponse
    data class RitEnabled(val enabled: Boolean, override val raw: String) : QmxResponse
    data class Split(val enabled: Boolean, override val raw: String) : QmxResponse
    data class ReceiveVfo(val vfo: QmxVfo, override val raw: String) : QmxResponse
    data class TransmitVfo(val vfo: QmxVfo, override val raw: String) : QmxResponse
    data class Firmware(val value: QmxFirmwareVersion, override val raw: String) : QmxResponse
    data class ModelId(val id: String, override val raw: String) : QmxResponse
    data class IqMode(val enabled: Boolean, override val raw: String) : QmxResponse
    data class Vox(val enabled: Boolean, override val raw: String) : QmxResponse
    data class Power(val watts: Double, override val raw: String) : QmxResponse
    data class Swr(val ratio: Double?, override val raw: String) : QmxResponse
    data class SignalMeter(val value: Int, override val raw: String) : QmxResponse
    data class TransmitState(val state: QmxTxState, override val raw: String) : QmxResponse
    data class IfState(override val raw: String) : QmxResponse
    data class MenuValue(val value: String, override val raw: String) : QmxResponse
    data class Unsupported(override val raw: String = "?;") : QmxResponse
    data class Malformed(override val raw: String, val reason: String) : QmxResponse
    data class Unrecognised(override val raw: String) : QmxResponse
}

class QmxProtocolDecoder(private val maximumBufferBytes: Int = 256) {
    private val buffer = StringBuilder()
    var lastError: String? = null
        private set

    init { require(maximumBufferBytes in 32..4_096) }

    @Synchronized
    fun feed(bytes: ByteArray): List<QmxResponse> {
        val responses = mutableListOf<QmxResponse>()
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value !in 0x20..0x7e) continue
            if (buffer.length >= maximumBufferBytes) {
                val discarded = buffer.toString()
                buffer.clear()
                lastError = "CAT response exceeded $maximumBufferBytes bytes"
                responses += QmxResponse.Malformed(discarded.take(64), lastError!!)
            }
            buffer.append(value.toChar())
            if (value.toChar() == ';') {
                val frame = buffer.toString()
                buffer.clear()
                responses += decodeFrame(frame)
            }
        }
        return responses
    }

    fun decodeFrame(frame: String): QmxResponse {
        if (!frame.endsWith(';')) return QmxResponse.Malformed(frame, "missing terminator")
        if (frame == "?;") return QmxResponse.Unsupported()
        Regex("^F([AB])(\\d{11});$").matchEntire(frame)?.let { match ->
            val hertz = match.groupValues[2].toLongOrNull()
                ?: return QmxResponse.Malformed(frame, "invalid frequency")
            return QmxResponse.Frequency(if (match.groupValues[1] == "A") QmxVfo.A else QmxVfo.B, hertz, frame)
        }
        Regex("^MD([1-9]);$").matchEntire(frame)?.let { return QmxResponse.Mode(QmxMode.fromCat(it.groupValues[1][0]), frame) }
        Regex("^FW(\\d{4,5});$").matchEntire(frame)?.let { return QmxResponse.Filter(it.groupValues[1].toInt(), frame) }
        Regex("^AG0?(\\d{1,3});$").matchEntire(frame)?.let {
            val value = it.groupValues[1].toInt()
            return if (value <= 799) QmxResponse.AfGain(value, frame) else QmxResponse.Malformed(frame, "AF gain outside 0..799 quarter-dB steps")
        }
        Regex("^RG(\\d{3});$").matchEntire(frame)?.let {
            val value = it.groupValues[1].toInt()
            return if (value <= 99) QmxResponse.RfGain(value, frame) else QmxResponse.Malformed(frame, "RF gain outside 0..99 dB")
        }
        Regex("^RT([01]);$").matchEntire(frame)?.let { return QmxResponse.RitEnabled(it.groupValues[1] == "1", frame) }
        Regex("^SP([01]);$").matchEntire(frame)?.let { return QmxResponse.Split(it.groupValues[1] == "1", frame) }
        Regex("^FR([012]);$").matchEntire(frame)?.let { return QmxResponse.ReceiveVfo(vfo(it.groupValues[1][0]), frame) }
        Regex("^FT([01]);$").matchEntire(frame)?.let { return QmxResponse.TransmitVfo(vfo(it.groupValues[1][0]), frame) }
        Regex("^VN([^;]{1,21});$").matchEntire(frame)?.let {
            val version = QmxFirmwareVersion.parse(it.groupValues[1])
                ?: return QmxResponse.Malformed(frame, "invalid firmware version")
            return QmxResponse.Firmware(version, frame)
        }
        Regex("^ID([A-Za-z0-9]{1,12});$").matchEntire(frame)?.let { return QmxResponse.ModelId(it.groupValues[1], frame) }
        Regex("^Q9([01]);$").matchEntire(frame)?.let { return QmxResponse.IqMode(it.groupValues[1] == "1", frame) }
        Regex("^Q3([01]);$").matchEntire(frame)?.let { return QmxResponse.Vox(it.groupValues[1] == "1", frame) }
        Regex("^PC(\\d{1,3});$").matchEntire(frame)?.let { return QmxResponse.Power(it.groupValues[1].toInt() / 10.0, frame) }
        if (frame == "SW;") return QmxResponse.Swr(null, frame)
        Regex("^SW(\\d{1,3});$").matchEntire(frame)?.let { return QmxResponse.Swr(it.groupValues[1].toInt() / 100.0, frame) }
        Regex("^SM0?(\\d{1,4});$").matchEntire(frame)?.let { return QmxResponse.SignalMeter(it.groupValues[1].toInt(), frame) }
        Regex("^TQ([01]);$").matchEntire(frame)?.let { return QmxResponse.TransmitState(if (it.groupValues[1] == "1") QmxTxState.TX else QmxTxState.RX, frame) }
        Regex("^MM([^;]{0,61});$").matchEntire(frame)?.let { return QmxResponse.MenuValue(it.groupValues[1], frame) }
        if (frame.startsWith("IF") && frame.length in 4..64) return QmxResponse.IfState(frame)
        return QmxResponse.Unrecognised(frame)
    }

    @Synchronized fun reset() { buffer.clear(); lastError = null }

    private fun vfo(value: Char) = when (value) { '0' -> QmxVfo.A; '1' -> QmxVfo.B; '2' -> QmxVfo.SPLIT; else -> QmxVfo.UNKNOWN }
}

enum class QmxCommandClass { QUERY, SAFE_CONTROL, EDGE_TRIGGERED, TRANSMIT }
data class QmxCommand(
    val text: String,
    val commandClass: QmxCommandClass,
    val coalesceKey: String? = null,
    val expectedReadback: QmxReadback? = null,
    val timeoutMillis: Long = 400,
) {
    init {
        require(text.endsWith(';') && text.length <= 64)
        require(timeoutMillis in 50..5_000)
        require(commandClass == QmxCommandClass.SAFE_CONTROL || coalesceKey == null)
    }
    val mayRetry: Boolean get() = commandClass == QmxCommandClass.QUERY
}

object QmxCommandBuilder {
    fun query(code: String, readback: QmxReadback) = QmxCommand("$code;", QmxCommandClass.QUERY, expectedReadback = readback)
    fun frequency(hertz: Long, vfo: QmxVfo = QmxVfo.A): QmxCommand {
        require(hertz in 100_000L..60_000_000L)
        require(vfo == QmxVfo.A || vfo == QmxVfo.B)
        return QmxCommand("${if (vfo == QmxVfo.A) "FA" else "FB"}%011d;".format(hertz), QmxCommandClass.SAFE_CONTROL, "frequency-${vfo.name}", if (vfo == QmxVfo.A) QmxReadback.FA else QmxReadback.FB)
    }
    fun mode(mode: QmxMode): QmxCommand {
        val digit = requireNotNull(mode.catDigit) { "unknown mode cannot be sent" }
        return QmxCommand("MD$digit;", QmxCommandClass.SAFE_CONTROL, "mode", QmxReadback.MD)
    }
    fun filter(hertz: Int): QmxCommand {
        require(hertz in 50..9_999)
        return QmxCommand("FW%04d;".format(hertz), QmxCommandClass.SAFE_CONTROL, "filter", QmxReadback.FW)
    }
    fun afGain(quarterDbSteps: Int): QmxCommand {
        require(quarterDbSteps in 0..799)
        return QmxCommand("AG0%03d;".format(quarterDbSteps), QmxCommandClass.SAFE_CONTROL, "af-gain", QmxReadback.AG)
    }
    fun rfGain(decibels: Int): QmxCommand {
        require(decibels in 0..99)
        return QmxCommand("RG%03d;".format(decibels), QmxCommandClass.SAFE_CONTROL, "rf-gain", QmxReadback.RG)
    }
    fun rit(hertz: Int): List<QmxCommand> {
        require(hertz in -9_999..9_999)
        val commands = mutableListOf(QmxCommand("RC;", QmxCommandClass.SAFE_CONTROL, "rit-clear", QmxReadback.RIT))
        if (hertz == 0) commands += QmxCommand("RT0;", QmxCommandClass.SAFE_CONTROL, "rit-mode", QmxReadback.RIT)
        else {
            commands += QmxCommand("${if (hertz > 0) "RU" else "RD"}%03d;".format(kotlin.math.abs(hertz)), QmxCommandClass.SAFE_CONTROL, "rit-offset", QmxReadback.RIT)
            commands += QmxCommand("RT1;", QmxCommandClass.SAFE_CONTROL, "rit-mode", QmxReadback.RIT)
        }
        return commands
    }
    fun split(enabled: Boolean) = QmxCommand("SP${if (enabled) 1 else 0};", QmxCommandClass.SAFE_CONTROL, "split", QmxReadback.SPLIT)
    fun menuQuery(path: String, readback: QmxReadback): QmxCommand {
        require(path.isNotBlank() && path.length <= 58 && ';' !in path)
        require(readback == QmxReadback.CW_OFFSET || readback == QmxReadback.GPS_SOURCE)
        return QmxCommand("MM$path;", QmxCommandClass.QUERY, expectedReadback = readback)
    }
    fun transmit() = QmxCommand("TX;", QmxCommandClass.TRANSMIT)
    fun tone(hertz: Double): QmxCommand {
        require(hertz in 10.0..5_000.0)
        return QmxCommand("TA%.2f;".format(java.util.Locale.ROOT, hertz), QmxCommandClass.TRANSMIT)
    }
    fun toneOff() = QmxCommand("TA0;", QmxCommandClass.TRANSMIT)
    fun receive() = QmxCommand("RX;", QmxCommandClass.TRANSMIT)
}

data class QmxCwSplitPlan(val receiveFrequencyHz: Long, val transmitFrequencyHz: Long, val commands: List<QmxCommand>)

object QmxCwSplitWorkaround {
    fun engage(receiveFrequencyHz: Long, cwOffsetHz: Int): QmxCwSplitPlan {
        require(cwOffsetHz in -2_000..2_000 && cwOffsetHz != 0)
        val transmit = receiveFrequencyHz + cwOffsetHz
        return QmxCwSplitPlan(
            receiveFrequencyHz,
            transmit,
            listOf(QmxCommandBuilder.frequency(transmit, QmxVfo.B), QmxCommandBuilder.split(true)),
        )
    }

    fun disengage() = listOf(QmxCommandBuilder.split(false))
}

class QmxCommandQueue(private val maximumDepth: Int = 64) {
    private val pending = ArrayDeque<QmxCommand>()
    init { require(maximumDepth in 8..256) }

    @Synchronized fun enqueue(command: QmxCommand): Boolean {
        command.coalesceKey?.let { key -> pending.removeAll { it.coalesceKey == key } }
        if (pending.size >= maximumDepth) return false
        pending += command
        return true
    }
    @Synchronized fun enqueue(commands: Iterable<QmxCommand>): Boolean = commands.all(::enqueue)
    @Synchronized fun next(): QmxCommand? = pending.removeFirstOrNull()
    @Synchronized fun size(): Int = pending.size
    @Synchronized fun clear() = pending.clear()
}
