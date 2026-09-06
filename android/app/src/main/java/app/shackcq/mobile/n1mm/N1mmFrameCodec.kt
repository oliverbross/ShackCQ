package app.shackcq.mobile.n1mm

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object N1mmFrameCodec {
    val prefix = "DATA__".toByteArray()
    val suffix = "~__DATA".toByteArray()

    fun encode(frame: N1mmFrame): ByteArray {
        require(frame.stationNumber in 0..99)
        val fields = listOf(frame.station, frame.command.uppercase()) + frame.fields
        require(fields.none { '%' in it }) { "N1MM percent-delimited fields cannot contain '%'" }
        val body = fields.joinToString("%") { it.replace('~', '!') }
        return "DATA__%02d%%%s%%~__DATA".format(frame.stationNumber, body).toByteArray(StandardCharsets.UTF_8)
    }

    internal fun decodePayload(payload: ByteArray): N1mmFrame {
        require(payload.size >= 9)
        val stationNumber = String(payload, 6, 2, StandardCharsets.US_ASCII).toIntOrNull() ?: error("Invalid station number")
        require(payload[8] == '%'.code.toByte())
        val bytes = payload.copyOfRange(9, payload.size)
        val malformed = runCatching { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }.isFailure
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString()
        val tokens = text.split('%')
        require(tokens.size >= 2)
        return N1mmFrame(stationNumber, tokens[0], tokens[1].uppercase(), tokens.drop(2).dropLastWhile(String::isEmpty), malformed)
    }
}

class N1mmStreamParser(
    private val maximumFrameBytes: Int = 64 * 1024,
    private val maximumBufferBytes: Int = 256 * 1024,
) {
    private var buffer = ByteArray(0)
    @Synchronized fun append(bytes: ByteArray): List<N1mmFrame> {
        if (bytes.isNotEmpty() && bytes.all { it == 0.toByte() }) return emptyList()
        require(bytes.size <= maximumBufferBytes) { "TCP chunk exceeds bounded stream buffer" }
        buffer += bytes
        require(buffer.size <= maximumBufferBytes) { "Incomplete N1MM stream exceeds bounded buffer" }
        val frames = mutableListOf<N1mmFrame>()
        while (true) {
            val start = buffer.indexOf(N1mmFrameCodec.prefix)
            if (start < 0) { buffer = buffer.takeLast(N1mmFrameCodec.prefix.size - 1).toByteArray(); break }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            val end = buffer.indexOf(N1mmFrameCodec.suffix, N1mmFrameCodec.prefix.size)
            if (end < 0) { require(buffer.size <= maximumFrameBytes) { "N1MM frame exceeds maximum size" }; break }
            val frameEnd = end + N1mmFrameCodec.suffix.size
            require(frameEnd <= maximumFrameBytes) { "N1MM frame exceeds maximum size" }
            val payload = buffer.copyOfRange(0, end)
            frames += N1mmFrameCodec.decodePayload(payload)
            buffer = buffer.copyOfRange(frameEnd, buffer.size)
        }
        return frames
    }
    fun retainedBytes(): Int = buffer.size
}

private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from.coerceAtMost(size)
    outer@ for (i in from..size - needle.size) { for (j in needle.indices) if (this[i+j] != needle[j]) continue@outer; return i }
    return -1
}
