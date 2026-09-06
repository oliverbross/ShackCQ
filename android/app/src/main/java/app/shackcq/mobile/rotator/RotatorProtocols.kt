package app.shackcq.mobile.rotator

import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface RotatorWireCommand {
    val bytes: ByteArray
    val expectsResponse: Boolean
    val physicalMotion: Boolean
}
data class WireCommand(
    override val bytes: ByteArray,
    override val expectsResponse: Boolean,
    override val physicalMotion: Boolean,
) : RotatorWireCommand
data class ProtocolPosition(val azimuthDeg: Double, val elevationDeg: Double? = null)

interface RotatorProtocol {
    val kind: RotatorProtocolKind
    val maxResponseBytes: Int get() = MAX_ROTATOR_RESPONSE_BYTES
    fun queryPosition(): RotatorWireCommand
    fun setPosition(azimuthDeg: Double, elevationDeg: Double? = null): List<RotatorWireCommand>
    fun stop(): RotatorWireCommand
    fun park(): RotatorWireCommand? = null
    fun parsePosition(response: ByteArray): ProtocolPosition
}

private fun ascii(value: String, response: Boolean = false, motion: Boolean = false) =
    WireCommand(value.toByteArray(StandardCharsets.US_ASCII), response, motion)

class Gs232Protocol(private val supportsElevation: Boolean) : RotatorProtocol {
    override val kind = RotatorProtocolKind.GS232
    override fun queryPosition() = ascii(if (supportsElevation) "C2\r" else "C\r", response = true)
    override fun setPosition(azimuthDeg: Double, elevationDeg: Double?): List<RotatorWireCommand> {
        require(azimuthDeg in 0.0..450.0)
        val az = azimuthDeg.toInt()
        val command = if (supportsElevation) {
            require(elevationDeg != null && elevationDeg in 0.0..180.0)
            "W%03d %03d\r".format(Locale.US, az, elevationDeg.toInt())
        } else "M%03d\r".format(Locale.US, az)
        return listOf(ascii(command, motion = true))
    }
    override fun stop() = ascii("S\r", motion = true)
    override fun parsePosition(response: ByteArray): ProtocolPosition {
        require(response.size <= maxResponseBytes)
        val text = response.toString(StandardCharsets.US_ASCII).trim()
        val match = Regex("^(?:AZ=)?([+-]?\\d{1,3})(?:[ \\r\\n,]+(?:EL=)?([+-]?\\d{1,3}))?$", RegexOption.IGNORE_CASE).matchEntire(text)
            ?: throw IllegalArgumentException("invalid GS-232 position frame")
        return ProtocolPosition(match.groupValues[1].toDouble(), match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDouble())
    }
}

class DcuRotorEzProtocol : RotatorProtocol {
    override val kind = RotatorProtocolKind.DCU_ROTOREZ
    override fun queryPosition() = ascii("AI1;", response = true)
    override fun setPosition(azimuthDeg: Double, elevationDeg: Double?): List<RotatorWireCommand> {
        require(elevationDeg == null && azimuthDeg in 0.0..359.4999)
        val target = "AP1%03d;".format(Locale.US, azimuthDeg.toInt())
        return listOf(ascii(target, response = false, motion = false), ascii("AM1;", response = false, motion = true))
    }
    override fun stop() = ascii("AS1;", motion = true)
    override fun parsePosition(response: ByteArray): ProtocolPosition {
        require(response.size <= maxResponseBytes)
        val text = response.toString(StandardCharsets.US_ASCII).trim().removeSuffix(";")
        val match = Regex("^(?:AI1)?([0-9]{1,3}(?:\\.[0-9])?)$").matchEntire(text)
            ?: throw IllegalArgumentException("invalid DCU position frame")
        return ProtocolPosition(match.groupValues[1].toDouble())
    }
}

enum class EasyCommVersion { I, II, III }
class EasyCommProtocol(private val version: EasyCommVersion) : RotatorProtocol {
    override val kind = RotatorProtocolKind.EASYCOMM
    override fun queryPosition() = ascii(if (version == EasyCommVersion.I) "AZ EL\r" else "AZ EL\n", response = true)
    override fun setPosition(azimuthDeg: Double, elevationDeg: Double?): List<RotatorWireCommand> {
        require(azimuthDeg in 0.0..450.0)
        val el = elevationDeg ?: 0.0
        require(el in -90.0..180.0)
        val suffix = if (version == EasyCommVersion.I) "\r" else "\n"
        return listOf(ascii("AZ%.1f EL%.1f%s".format(Locale.US, azimuthDeg, el, suffix), motion = true))
    }
    override fun stop() = ascii(if (version == EasyCommVersion.I) "SA SE\r" else "SA SE\n", motion = true)
    override fun parsePosition(response: ByteArray): ProtocolPosition {
        require(response.size <= maxResponseBytes)
        val text = response.toString(StandardCharsets.US_ASCII).trim()
        val match = Regex("^AZ[ =]?([+-]?\\d{1,3}(?:\\.\\d+)?)\\s+EL[ =]?([+-]?\\d{1,3}(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).matchEntire(text)
            ?: throw IllegalArgumentException("invalid EasyComm position frame")
        return ProtocolPosition(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    }
}

class SpidProtocol(private val rot2: Boolean, private val resolution: Int = if (rot2) 2 else 1) : RotatorProtocol {
    override val kind = if (rot2) RotatorProtocolKind.SPID_ROT2 else RotatorProtocolKind.SPID_ROT1
    init { require(resolution in setOf(1, 2, 4)) }
    override fun queryPosition() = frame(0x1f, 0.0, 0.0, false)
    override fun setPosition(azimuthDeg: Double, elevationDeg: Double?): List<RotatorWireCommand> =
        listOf(frame(0x2f, azimuthDeg, elevationDeg ?: 0.0, true))
    override fun stop() = frame(0x0f, 0.0, 0.0, true)
    private fun frame(command: Int, azimuth: Double, elevation: Double, motion: Boolean): WireCommand {
        require(azimuth in -360.0..639.0 && (!rot2 || elevation in -360.0..639.0))
        val result = ByteArray(13)
        result[0] = 0x57; result[11] = command.toByte(); result[12] = 0x20
        if (command == 0x2f) {
            if (rot2) {
                encode4((resolution * (360.0 + azimuth)).toInt(), result, 1)
                result[5] = resolution.toByte()
                encode4((resolution * (360.0 + elevation)).toInt(), result, 6)
                result[10] = resolution.toByte()
            } else {
                val value = (360.0 + azimuth).toInt()
                result[1] = ('0'.code + value / 100).toByte(); result[2] = ('0'.code + value % 100 / 10).toByte()
                result[3] = ('0'.code + value % 10).toByte(); result[4] = '0'.code.toByte()
            }
        }
        return WireCommand(result, command != 0x2f, motion)
    }
    private fun encode4(value: Int, target: ByteArray, offset: Int) {
        require(value in 0..9999)
        for (index in 0..3) target[offset + index] = ('0'.code + value / intArrayOf(1000, 100, 10, 1)[index] % 10).toByte()
    }
    override fun parsePosition(response: ByteArray): ProtocolPosition {
        val expected = if (rot2) 12 else 5
        require(response.size == expected && response[0] == 0x57.toByte() && response.last() == 0x20.toByte())
        return if (rot2) {
            require(response[5].toInt() in setOf(1, 2, 4) && response[10].toInt() in setOf(1, 2, 4))
            ProtocolPosition(response[1].toInt() * 100 + response[2].toInt() * 10 + response[3].toInt() + response[4].toInt() / 10.0 - 360.0,
                response[6].toInt() * 100 + response[7].toInt() * 10 + response[8].toInt() + response[9].toInt() / 10.0 - 360.0)
        } else ProtocolPosition(response[1].toInt() * 100 + response[2].toInt() * 10 + response[3].toInt() - 360.0)
    }
}

object RotctldProtocolCodec {
    fun getPosition() = ascii("+p\n", response = true)
    fun setPosition(azimuthDeg: Double, elevationDeg: Double) = ascii("+P %.6f %.6f\n".format(Locale.US, azimuthDeg, elevationDeg), response = true, motion = true)
    fun stop() = ascii("+S\n", response = true, motion = true)
    fun park() = ascii("+K\n", response = true, motion = true)
    fun move(direction: Int, speed: Int) = ascii("+M $direction $speed\n", response = true, motion = true)

    data class Response(val code: Int, val values: Map<String, String>, val lines: List<String>)
    fun parse(response: ByteArray): Response {
        require(response.size <= MAX_ROTATOR_RESPONSE_BYTES)
        val lines = response.toString(StandardCharsets.US_ASCII).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val marker = lines.lastOrNull { it.startsWith("RPRT ") } ?: throw IllegalArgumentException("missing RPRT")
        val code = marker.removePrefix("RPRT ").toIntOrNull() ?: throw IllegalArgumentException("invalid RPRT")
        val values = lines.filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Response(code, values, lines)
    }
}
