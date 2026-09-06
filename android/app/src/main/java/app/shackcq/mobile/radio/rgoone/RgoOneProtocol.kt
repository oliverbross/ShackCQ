package app.shackcq.mobile.radio.rgoone

import java.security.MessageDigest
import java.util.Locale

object RgoOneProtocol {
    const val TERMINATOR: Byte = ';'.code.toByte()
    const val MAX_FRAME_BYTES = 256
    val V6_READ_COMMANDS = setOf(
        "AC", "AI", "EX", "FA", "FB", "FR", "FS", "FT", "FW", "GT", "ID", "IF", "KS", "KY", "LK",
        "MC", "MD", "MG", "ML", "MR", "NB", "NL", "PA", "PB", "PC", "PL", "RA", "RD", "RG", "RM",
        "RT", "RU", "SD", "SM", "SN", "VD", "VG", "XT",
    )
}

sealed interface RgoOneProtocolResponse {
    val command: String

    data class Frequency(override val command: String, val frequencyHz: Long) : RgoOneProtocolResponse
    data class Selection(override val command: String, val value: Int) : RgoOneProtocolResponse
    data class Numeric(override val command: String, val value: Int) : RgoOneProtocolResponse
    data class Toggle(override val command: String, val enabled: Boolean, val extra: Int? = null) : RgoOneProtocolResponse
    data class Firmware(val version: RgoOneFirmwareVersion) : RgoOneProtocolResponse { override val command = "FW" }
    data class ModelId(val value: String) : RgoOneProtocolResponse { override val command = "ID" }
    data class SerialDigest(val sha256: String) : RgoOneProtocolResponse { override val command = "SN" }
    data class Meter(val kind: RgoOneMeter?, val value: Int) : RgoOneProtocolResponse { override val command = if (kind == null) "SM" else "RM" }
    data class AntennaTuner(val enabled: Boolean, val tuning: Boolean) : RgoOneProtocolResponse { override val command = "AC" }
    data class ExtendedMenu(val menu: Int, val selector: Int, val value: Int) : RgoOneProtocolResponse { override val command = "EX" }
    data class MemoryChannel(val channel: Int) : RgoOneProtocolResponse { override val command = "MC" }
    data class Memory(val record: RgoOneMemoryRecord) : RgoOneProtocolResponse { override val command = "MR" }
    data class IfStatus(val boundedPayload: String) : RgoOneProtocolResponse { override val command = "IF" }
    data class Playback(val channel: Int, val queueCode: Int) : RgoOneProtocolResponse { override val command = "PB" }
    data class Raw(override val command: String, val boundedPayload: String) : RgoOneProtocolResponse
    data class Malformed(val reason: String) : RgoOneProtocolResponse { override val command = "" }
}

class RgoOneProtocolDecoder(private val maximumFrameBytes: Int = RgoOneProtocol.MAX_FRAME_BYTES) {
    private val frame = ArrayList<Byte>()
    private var discarding = false

    init { require(maximumFrameBytes in 16..4_096) }

    fun accept(bytes: ByteArray): List<String> {
        val frames = mutableListOf<String>()
        for (byte in bytes) {
            if (discarding) {
                if (byte == RgoOneProtocol.TERMINATOR) discarding = false
                continue
            }
            if (byte == RgoOneProtocol.TERMINATOR) {
                if (frame.isNotEmpty()) frames += frame.toByteArray().toString(Charsets.US_ASCII) + ";"
                frame.clear()
                continue
            }
            if (byte.toInt() !in 0x20..0x7e) {
                frame.clear()
                discarding = true
                continue
            }
            if (frame.size >= maximumFrameBytes - 1) {
                frame.clear()
                discarding = true
                continue
            }
            frame += byte
        }
        return frames
    }

    fun reset() {
        frame.clear()
        discarding = false
    }
}

object RgoOneProtocolParser {
    fun parse(frame: String, expectedCommand: String? = null): RgoOneProtocolResponse {
        if (!frame.endsWith(';') || frame.length !in 3..RgoOneProtocol.MAX_FRAME_BYTES || frame.dropLast(1).any { it.code !in 0x20..0x7e }) {
            return RgoOneProtocolResponse.Malformed("invalid framing")
        }
        val body = frame.dropLast(1)
        if (expectedCommand == "SN") {
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
            return RgoOneProtocolResponse.SerialDigest(digest)
        }
        if (body.length < 2) return RgoOneProtocolResponse.Malformed("missing command")
        val command = body.take(2)
        val payload = body.drop(2)
        if (expectedCommand != null && expectedCommand != command) return RgoOneProtocolResponse.Malformed("unexpected command")
        fun digits(length: Int? = null): Int? = payload.takeIf { (length == null || it.length == length) && it.all(Char::isDigit) }?.toIntOrNull()
        fun boolean(): Boolean? = digits(1)?.takeIf { it in 0..1 }?.let { it == 1 }
        return when (command) {
            "FA", "FB" -> payload.takeIf { it.length == 11 && it.all(Char::isDigit) }?.toLongOrNull()
                ?.let { RgoOneProtocolResponse.Frequency(command, it) } ?: RgoOneProtocolResponse.Malformed("invalid frequency")
            "FR", "FT", "MD", "FS", "RT", "XT" -> digits(1)?.let { RgoOneProtocolResponse.Selection(command, it) }
                ?: RgoOneProtocolResponse.Malformed("invalid selection")
            "FW" -> RgoOneFirmwareVersion.parse(payload)?.let(RgoOneProtocolResponse::Firmware)
                ?: RgoOneProtocolResponse.Malformed("invalid firmware")
            "ID" -> payload.takeIf { it.length == 3 && it.all(Char::isDigit) }?.let(RgoOneProtocolResponse::ModelId)
                ?: RgoOneProtocolResponse.Malformed("invalid model")
            "GT", "KS", "MG", "ML", "NL", "PC", "RG", "SD", "VD", "VG" -> digits()?.let { RgoOneProtocolResponse.Numeric(command, it) }
                ?: RgoOneProtocolResponse.Malformed("invalid numeric response")
            "NB" -> boolean()?.let { RgoOneProtocolResponse.Toggle(command, it) } ?: RgoOneProtocolResponse.Malformed("invalid toggle")
            "PA" -> if (payload.length == 2 && payload.all(Char::isDigit) && payload[1] == '0')
                RgoOneProtocolResponse.Toggle(command, payload[0] == '1', 0) else RgoOneProtocolResponse.Malformed("invalid preamp")
            "RA" -> if (payload.length == 4 && payload.all(Char::isDigit) && payload.takeLast(2) == "00")
                RgoOneProtocolResponse.Toggle(command, payload.take(2) == "01", 0) else RgoOneProtocolResponse.Malformed("invalid attenuator")
            "AC" -> if (payload.length == 3 && payload.all(Char::isDigit) && payload[0] == '1' && payload[1] in "01" && payload[2] in "01")
                RgoOneProtocolResponse.AntennaTuner(payload[1] == '1', payload[2] == '1') else RgoOneProtocolResponse.Malformed("invalid tuner")
            "SM" -> if (payload.length == 5 && payload[0] == '0' && payload.drop(1).all(Char::isDigit))
                RgoOneProtocolResponse.Meter(null, payload.drop(1).toInt().coerceIn(0, 15)) else RgoOneProtocolResponse.Malformed("invalid S meter")
            "RM" -> if (payload.length == 5 && payload.all(Char::isDigit)) {
                val kind = RgoOneMeter.entries.getOrNull(payload[0].digitToInt())
                if (kind == null) RgoOneProtocolResponse.Malformed("invalid meter kind") else RgoOneProtocolResponse.Meter(kind, payload.drop(1).toInt().coerceIn(0, 15))
            } else RgoOneProtocolResponse.Malformed("invalid meter")
            "EX" -> parseExtended(payload)
            "MC" -> if (payload.length == 3 && payload[0] == '0' && payload.drop(1).all(Char::isDigit))
                RgoOneProtocolResponse.MemoryChannel(payload.drop(1).toInt()) else RgoOneProtocolResponse.Malformed("invalid memory channel")
            "MR" -> parseMemory(payload)
            "PB" -> if (payload.length == 4 && payload.all(Char::isDigit))
                RgoOneProtocolResponse.Playback(payload[0].digitToInt(), payload.drop(1).toInt()) else RgoOneProtocolResponse.Malformed("invalid playback")
            "IF" -> payload.takeIf { it.length <= RgoOneProtocol.MAX_FRAME_BYTES - 3 }?.let(RgoOneProtocolResponse::IfStatus)
                ?: RgoOneProtocolResponse.Malformed("IF response too large")
            "AI", "KY", "LK", "PL", "RD", "RU" -> RgoOneProtocolResponse.Raw(command, payload)
            else -> RgoOneProtocolResponse.Raw(command, payload)
        }
    }

    private fun parseExtended(payload: String): RgoOneProtocolResponse {
        if (payload.length !in 8..12 || payload.any { !it.isDigit() }) return RgoOneProtocolResponse.Malformed("invalid EX response")
        return RgoOneProtocolResponse.ExtendedMenu(payload.take(3).toInt(), payload.substring(3, 7).toInt(), payload.drop(7).toInt())
    }

    private fun parseMemory(payload: String): RgoOneProtocolResponse {
        if (payload.length != 22 || payload.any { !it.isDigit() } || payload[1] != '0') return RgoOneProtocolResponse.Malformed("invalid memory response")
        val agc = when (payload[20]) { '2' -> RgoOneAgc.FAST; '3' -> RgoOneAgc.SLOW; else -> return RgoOneProtocolResponse.Malformed("invalid memory AGC") }
        val mode = RgoOneMode.entries.firstOrNull { it.wireValue == payload[15].digitToInt() }
            ?: return RgoOneProtocolResponse.Malformed("invalid memory mode")
        return RgoOneProtocolResponse.Memory(RgoOneMemoryRecord(
            tx = payload[0] == '1', channel = payload.substring(2, 4).toInt(), frequencyHz = payload.substring(4, 15).toLong(),
            mode = mode, stepCode = payload[16].digitToInt(), noiseBlanker = payload[17] == '1', preamp = payload[18] == '1',
            attenuator = payload[19] == '1', agc = agc, filterEnabled = payload[21] == '1',
        ))
    }
}

object RgoOneCommandBuilder {
    fun read(command: String): String? = command.uppercase(Locale.US).takeIf { it in RgoOneProtocol.V6_READ_COMMANDS }?.let {
        when (it) {
            "SM" -> "SM0;"
            "EX", "MR" -> null
            else -> "$it;"
        }
    }
    fun frequency(vfo: RgoOneVfo, frequencyHz: Long): String? = frequencyHz.takeIf { it in 1..99_999_999_999L }
        ?.let { "F${vfo.name}${it.toString().padStart(11, '0')};" }
    fun rxVfo(vfo: RgoOneVfo) = "FR${if (vfo == RgoOneVfo.A) 0 else 1};"
    fun txVfo(vfo: RgoOneVfo) = "FT${if (vfo == RgoOneVfo.A) 0 else 1};"
    fun mode(mode: RgoOneMode) = "MD${mode.wireValue};"
    fun agc(agc: RgoOneAgc) = "GT${when (agc) { RgoOneAgc.OFF -> 0; RgoOneAgc.FAST -> 1; RgoOneAgc.SLOW -> 2 }.toString().padStart(3, '0')};"
    fun toggle(command: String, enabled: Boolean): String? = command.uppercase(Locale.US).takeIf { it in setOf("FS", "NB", "RT", "XT") }
        ?.let { "$it${if (enabled) 1 else 0};" }
    fun level(command: String, value: Int): String? {
        val range = when (command.uppercase(Locale.US)) {
            "KS" -> 5..45; "MG", "ML", "VG" -> 0..10; "NL" -> 0..16; "PC" -> 0..50; "RG" -> 0..100; else -> return null
        }
        return value.takeIf { it in range }?.let { command.uppercase(Locale.US) + it.toString().padStart(3, '0') + ";" }
    }
    fun meter(kind: RgoOneMeter) = "RM${kind.ordinal};"
    fun extendedMenuRead(menu: Int, selector: Int = 0): String? = menu.takeIf { it in 0..83 && it !in setOf(8, 14, 15, 16, 17, 37, 38, 39, 40, 82) }
        ?.let { "EX${it.toString().padStart(3, '0')}${selector.coerceIn(0, 9_999).toString().padStart(4, '0')};" }
    fun memoryRead(tx: Boolean, channel: Int): String? = channel.takeIf { it in 0..99 }
        ?.let { "MR${if (tx) 1 else 0}0${it.toString().padStart(2, '0')};" }
    fun memoryWrite(record: RgoOneMemoryRecord): String = buildString {
        append("MW"); append(if (record.tx) 1 else 0); append('0'); append(record.channel.toString().padStart(2, '0'))
        append(record.frequencyHz.toString().padStart(11, '0')); append(record.mode.wireValue); append(record.stepCode)
        append(if (record.noiseBlanker) 1 else 0); append(if (record.preamp) 1 else 0); append(if (record.attenuator) 1 else 0)
        append(if (record.agc == RgoOneAgc.FAST) 2 else 3); append(if (record.filterEnabled) 1 else 0); append(';')
    }
    fun forAction(action: RgoOneAction): String? = when (action) {
        is RgoOneAction.Read -> read(action.command)
        is RgoOneAction.SetFrequency -> frequency(action.vfo, action.frequencyHz)
        is RgoOneAction.SelectRxVfo -> rxVfo(action.vfo)
        is RgoOneAction.SelectTxVfo -> txVfo(action.vfo)
        is RgoOneAction.SetMode -> mode(action.mode)
        is RgoOneAction.SetAgc -> agc(action.agc)
        is RgoOneAction.SetToggle -> toggle(action.command, action.enabled)
        is RgoOneAction.SetLevel -> level(action.command, action.value)
        RgoOneAction.ClearRit -> "RC;"
        is RgoOneAction.NudgeRit -> if (action.up) "RU;" else "RD;"
        is RgoOneAction.RecallMemory -> action.channel.takeIf { it in 0..99 }?.let { "MC0${it.toString().padStart(2, '0')};" }
        RgoOneAction.Receive -> "RX;"
        RgoOneAction.Transmit -> "TX0;"
        RgoOneAction.Tune -> "TX2;"
        is RgoOneAction.WriteMemory -> memoryWrite(action.memory)
    }
}
