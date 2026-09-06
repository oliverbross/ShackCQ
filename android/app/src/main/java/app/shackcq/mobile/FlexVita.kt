package app.shackcq.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

internal const val FLEX_VITA_OUI = 0x001C2D
internal const val FLEX_METER_CLASS = 0x8002
internal const val FLEX_FFT_CLASS = 0x8003
internal const val FLEX_WATERFALL_CLASS = 0x8004
internal const val FLEX_OPUS_CLASS = 0x8005
internal const val FLEX_FLOAT_AUDIO_CLASS = 0x03E3
internal const val FLEX_REDUCED_AUDIO_CLASS = 0x0123

internal data class FlexVitaPacket(
    val sequence: Int,
    val streamId: Long,
    val packetClass: Int,
    val payload: ByteArray,
) {
    companion object {
        fun parse(datagram: ByteArray, length: Int = datagram.size): FlexVitaPacket? {
            if (length !in 4..65_536 || length > datagram.size) return null
            val bytes = ByteBuffer.wrap(datagram, 0, length).order(ByteOrder.BIG_ENDIAN)
            val header = bytes.int
            val declared = (header and 0xffff) * 4
            if (declared < 4 || declared > length + 3) return null
            val packetType = header ushr 28 and 0xf
            val classPresent = header and (1 shl 27) != 0
            val trailerPresent = header and (1 shl 26) != 0
            val tsi = header ushr 22 and 0x3
            val tsf = header ushr 20 and 0x3
            val sequence = header ushr 16 and 0xf
            if (packetType !in setOf(1, 3, 5) || !classPresent || bytes.remaining() < 12) return null
            val streamId = bytes.int.toLong() and 0xffff_ffffL
            val oui = bytes.int and 0x00ff_ffff
            val classWord = bytes.int
            if (streamId == 0L || oui != FLEX_VITA_OUI) return null
            if (tsi != 0) {
                if (bytes.remaining() < 4) return null
                bytes.int
            }
            if (tsf != 0) {
                if (bytes.remaining() < 8) return null
                bytes.long
            }
            val packetEnd = min(declared, length)
            val payloadEnd = packetEnd - if (trailerPresent) 4 else 0
            if (bytes.position() > payloadEnd) return null
            val payload = datagram.copyOfRange(bytes.position(), payloadEnd)
            return FlexVitaPacket(sequence, streamId, classWord and 0xffff, payload)
        }
    }
}

enum class FlexStreamKind(val packetClass: Int) {
    METER(FLEX_METER_CLASS),
    PANADAPTER(FLEX_FFT_CLASS),
    WATERFALL(FLEX_WATERFALL_CLASS),
    REMOTE_AUDIO(FLEX_FLOAT_AUDIO_CLASS),
    REDUCED_AUDIO(FLEX_REDUCED_AUDIO_CLASS),
    OPUS_AUDIO(FLEX_OPUS_CLASS),
}

data class FlexSpectrumFrame(
    val streamId: Long,
    val frameIndex: Long,
    val binsDbm: List<Float>,
    val droppedFrames: Long,
)

data class FlexWaterfallRow(
    val streamId: Long,
    val timecode: Long,
    val lowMHz: Double,
    val highMHz: Double,
    val lineDurationMs: Long,
    val autoBlack: Long,
    val binsDbm: List<Float>,
)

data class FlexMeterValue(val id: Int, val raw: Int)

sealed interface FlexVitaEvent {
    data class Spectrum(val value: FlexSpectrumFrame) : FlexVitaEvent
    data class Waterfall(val value: FlexWaterfallRow) : FlexVitaEvent
    data class Meters(val values: List<FlexMeterValue>) : FlexVitaEvent
    data class FloatAudio(val streamId: Long, val sequence: Int, val samples: FloatArray, val channels: Int) : FlexVitaEvent
    data class OpusAudio(val streamId: Long, val sequence: Int, val payload: ByteArray) : FlexVitaEvent
}

private class CoverageAssembler<T>(private val empty: () -> T) {
    private var key: Long? = null
    private var total = 0
    private var values = mutableListOf<T>()
    private var covered = BooleanArray(0)
    var dropped: Long = 0
        private set

    fun push(frameKey: Long, first: Int, expected: Int, fragment: List<T>): List<T>? {
        if (expected !in 1..16_384 || first < 0 || first + fragment.size > expected) return null
        if (key != frameKey || total != expected) {
            if (key != null && covered.any { !it }) dropped++
            key = frameKey
            total = expected
            values = MutableList(expected) { empty() }
            covered = BooleanArray(expected)
        }
        fragment.forEachIndexed { offset, value ->
            val index = first + offset
            if (!covered[index]) {
                covered[index] = true
                values[index] = value
            }
        }
        if (covered.all { it }) {
            val result = values.toList()
            key = null
            covered = BooleanArray(0)
            return result
        }
        return null
    }
}

class FlexVitaEngine {
    private val streams = mutableMapOf<Long, FlexStreamKind>()
    private val fft = mutableMapOf<Long, CoverageAssembler<Int>>()
    private val waterfall = mutableMapOf<Long, CoverageAssembler<Float>>()
    private val lastSequence = mutableMapOf<Long, Int>()
    var packetCount: Long = 0
        private set
    var sequenceGaps: Long = 0
        private set
    var duplicatePackets: Long = 0
        private set

    @Synchronized
    fun register(streamId: Long, kind: FlexStreamKind): Boolean {
        if (streamId == 0L) return false
        streams[streamId] = kind
        return true
    }

    @Synchronized
    fun unregister(streamId: Long) {
        streams.remove(streamId)
        fft.remove(streamId)
        waterfall.remove(streamId)
        lastSequence.remove(streamId)
    }

    @Synchronized
    fun clear() {
        streams.clear()
        fft.clear()
        waterfall.clear()
        lastSequence.clear()
        packetCount = 0
        sequenceGaps = 0
        duplicatePackets = 0
    }

    @Synchronized
    fun feed(datagram: ByteArray, length: Int = datagram.size, minDbm: Float = -130f, maxDbm: Float = -30f, yPixels: Int = 100): FlexVitaEvent? {
        val packet = FlexVitaPacket.parse(datagram, length) ?: return null
        val kind = streams[packet.streamId] ?: return null
        if (kind.packetClass != packet.packetClass) return null
        packetCount++
        lastSequence.put(packet.streamId, packet.sequence)?.let { previous ->
            val distance = (packet.sequence - previous) and 0xf
            if (distance == 0) duplicatePackets++ else if (distance > 1) sequenceGaps += distance - 1
        }
        return when (kind) {
            FlexStreamKind.PANADAPTER -> parseFft(packet, minDbm, maxDbm, yPixels)
            FlexStreamKind.WATERFALL -> parseWaterfall(packet)
            FlexStreamKind.METER -> parseMeters(packet)
            FlexStreamKind.REMOTE_AUDIO -> parseFloatAudio(packet)
            FlexStreamKind.REDUCED_AUDIO -> parseReducedAudio(packet)
            FlexStreamKind.OPUS_AUDIO -> packet.payload.takeIf { it.isNotEmpty() }?.let {
                FlexVitaEvent.OpusAudio(packet.streamId, packet.sequence, it)
            }
        }
    }

    private fun parseFft(packet: FlexVitaPacket, minDbm: Float, maxDbm: Float, yPixels: Int): FlexVitaEvent? {
        if (packet.payload.size < 12 || yPixels < 2 || minDbm >= maxDbm) return null
        val bytes = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        val first = bytes.short.toInt() and 0xffff
        val count = bytes.short.toInt() and 0xffff
        val binSize = bytes.short.toInt() and 0xffff
        val total = bytes.short.toInt() and 0xffff
        val frame = bytes.int.toLong() and 0xffff_ffffL
        if (binSize != 2 || count == 0 || count > bytes.remaining() / 2) return null
        val bins = List(min(count, min(bytes.remaining() / 2, max(0, total - first)))) { bytes.short.toInt() and 0xffff }
        val assembler = fft.getOrPut(packet.streamId) { CoverageAssembler { 0 } }
        val complete = assembler.push(frame, first, total, bins) ?: return null
        val bottom = (yPixels - 1).toFloat()
        val scaled = complete.map { pixel ->
            (maxDbm - pixel.coerceIn(0, yPixels - 1) / bottom * (maxDbm - minDbm)).coerceIn(minDbm, maxDbm)
        }
        return FlexVitaEvent.Spectrum(FlexSpectrumFrame(packet.streamId, frame, scaled, assembler.dropped))
    }

    private fun parseWaterfall(packet: FlexVitaPacket): FlexVitaEvent? {
        if (packet.payload.size < 36) return null
        val bytes = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        val lowMHz = bytes.long / 1_048_576.0 / 1_000_000.0
        val widthMHz = bytes.long / 1_048_576.0 / 1_000_000.0
        val duration = bytes.int.toLong() and 0xffff_ffffL
        val tileWidth = bytes.short.toInt() and 0xffff
        val tileHeight = bytes.short.toInt() and 0xffff
        val timecode = bytes.int.toLong() and 0xffff_ffffL
        val autoBlack = bytes.int.toLong() and 0xffff_ffffL
        val total = bytes.short.toInt() and 0xffff
        val first = bytes.short.toInt() and 0xffff
        if (tileWidth == 0 || tileHeight == 0 || tileWidth > bytes.remaining() / 2) return null
        val bins = List(min(tileWidth, min(bytes.remaining() / 2, max(0, total - first)))) { bytes.short / 128f }
        val complete = waterfall.getOrPut(packet.streamId) { CoverageAssembler { 0f } }
            .push(timecode, first, total, bins) ?: return null
        return FlexVitaEvent.Waterfall(FlexWaterfallRow(packet.streamId, timecode, lowMHz, lowMHz + widthMHz * total, duration, autoBlack, complete))
    }

    private fun parseMeters(packet: FlexVitaPacket): FlexVitaEvent.Meters? {
        val bytes = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        val values = buildList {
            while (bytes.remaining() >= 4) add(FlexMeterValue(bytes.short.toInt() and 0xffff, bytes.short.toInt()))
        }
        return values.takeIf { it.isNotEmpty() }?.let(FlexVitaEvent::Meters)
    }

    private fun parseFloatAudio(packet: FlexVitaPacket): FlexVitaEvent.FloatAudio? {
        if (packet.payload.size < 8 || packet.payload.size % 8 != 0) return null
        val bytes = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        val samples = FloatArray(packet.payload.size / 4) { Float.fromBits(bytes.int) }
        if (samples.any { !it.isFinite() }) return null
        return FlexVitaEvent.FloatAudio(packet.streamId, packet.sequence, samples, 2)
    }

    private fun parseReducedAudio(packet: FlexVitaPacket): FlexVitaEvent.FloatAudio? {
        if (packet.payload.size < 2 || packet.payload.size % 2 != 0) return null
        val bytes = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
        val samples = FloatArray(packet.payload.size / 2) { bytes.short / 32768f }
        return FlexVitaEvent.FloatAudio(packet.streamId, packet.sequence, samples, 1)
    }
}

internal data class FlexMeterDefinition(
    val id: Int,
    var source: String = "",
    var name: String = "",
    var unit: String = "",
)

class FlexMeterBank {
    private val definitions = TreeMap<Int, FlexMeterDefinition>()
    private val readings = TreeMap<Int, Float>()

    @Synchronized
    fun applyStatus(body: String) {
        if (!body.startsWith("meter ")) return
        val tokens = body.removePrefix("meter ").split(Regex("\\s+"))
        val id = tokens.firstOrNull()?.toIntOrNull() ?: return
        if (tokens.drop(1).any { it == "removed" }) {
            definitions.remove(id)
            readings.remove(id)
            return
        }
        val fields = tokens.drop(1).mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
            .associate { it[0] to it[1].trim('"') }
        val definition = definitions.getOrPut(id) { FlexMeterDefinition(id) }
        fields["src"]?.let { definition.source = it }
        fields["nam"]?.let { definition.name = it }
        fields["unit"]?.let { definition.unit = it }
    }

    @Synchronized
    fun apply(values: List<FlexMeterValue>) {
        values.forEach { value ->
            val unit = definitions[value.id]?.unit.orEmpty().lowercase()
            readings[value.id] = when (unit) {
                "db", "dbm", "dbfs", "swr" -> value.raw / 128f
                "volts", "amps" -> value.raw / 256f
                "degf", "degc" -> value.raw / 64f
                else -> value.raw.toFloat()
            }
        }
    }

    @Synchronized
    fun named(name: String): Float? {
        val id = definitions.values.firstOrNull { it.name.equals(name, true) }?.id ?: return null
        return readings[id]
    }

    @Synchronized
    fun snapshot(): Map<String, Float> = definitions.values.mapNotNull { definition ->
        readings[definition.id]?.let { (definition.name.ifBlank { definition.id.toString() }) to it }
    }.toMap()

    @Synchronized
    fun clear() {
        definitions.clear()
        readings.clear()
    }
}
