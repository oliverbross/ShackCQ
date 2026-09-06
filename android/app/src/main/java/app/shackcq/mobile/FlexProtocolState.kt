package app.shackcq.mobile

data class FlexPanState(
    val id: Long,
    val streamId: Long = 0,
    val clientHandle: Long = 0,
    val centerHz: Long = 0,
    val bandwidthHz: Long = 0,
    val minDbm: Int = -130,
    val maxDbm: Int = -30,
    val fps: Int = 0,
    val xPixels: Int = 0,
    val yPixels: Int = 0,
    val band: String = "",
    val rfGain: Int = 0,
)

data class FlexWaterfallState(
    val id: Long,
    val streamId: Long = 0,
    val panId: Long = 0,
    val clientHandle: Long = 0,
)

data class FlexNetworkStream(
    val id: Long,
    val type: String,
    val clientHandle: Long = 0,
    val compression: String = "",
)

data class FlexTransmitState(
    val interlock: String = "",
    val source: String = "",
    val txClientHandle: Long = 0,
    val mox: Boolean = false,
    val tune: Boolean = false,
    val rfPower: Int = 0,
    val tunePower: Int = 0,
    val txAntenna: String = "",
) {
    val interlockReady get() = interlock.uppercase() in setOf("READY", "UNKEYED")
}

data class FlexCapabilities(
    val maxSlices: Int = 1,
    val maxPanadapters: Int = 1,
    val model: String = "",
)

data class FlexExtendedSnapshot(
    val pans: List<FlexPanState> = emptyList(),
    val waterfalls: List<FlexWaterfallState> = emptyList(),
    val streams: List<FlexNetworkStream> = emptyList(),
    val transmit: FlexTransmitState = FlexTransmitState(),
    val capabilities: FlexCapabilities = FlexCapabilities(),
    val profiles: Map<String, List<String>> = emptyMap(),
)

internal sealed interface FlexProtocolLine {
    data class Status(val body: String) : FlexProtocolLine
    data class Reply(val sequence: Int, val code: Long, val body: String) : FlexProtocolLine
    data class Handle(val value: Long) : FlexProtocolLine
    data class Version(val value: String) : FlexProtocolLine
}

internal fun parseFlexProtocolLine(line: String): FlexProtocolLine? {
    if (line.isEmpty()) return null
    return when (line[0]) {
        'S' -> line.substring(1).substringAfter('|', "").takeIf(String::isNotBlank)?.let(FlexProtocolLine::Status)
        'R' -> {
            val fields = line.substring(1).split('|', limit = 3)
            val sequence = fields.getOrNull(0)?.toIntOrNull() ?: return null
            val code = fields.getOrNull(1)?.toLongOrNull(16) ?: return null
            FlexProtocolLine.Reply(sequence, code, fields.getOrNull(2).orEmpty())
        }
        'H' -> parseFlexNumber(line.substring(1))?.takeIf { it != 0L }?.let(FlexProtocolLine::Handle)
        'V' -> FlexProtocolLine.Version(line.substring(1))
        else -> null
    }
}

class FlexExtendedStateTracker(
    private val registerStream: (Long, FlexStreamKind) -> Unit,
    private val meterBank: FlexMeterBank,
) {
    private val pans = linkedMapOf<Long, FlexPanState>()
    private val waterfalls = linkedMapOf<Long, FlexWaterfallState>()
    private val streams = linkedMapOf<Long, FlexNetworkStream>()
    private val profiles = linkedMapOf<String, MutableSet<String>>()
    private var transmit = FlexTransmitState()
    private var capabilities = FlexCapabilities()

    @Synchronized
    fun apply(body: String) {
        meterBank.applyStatus(body)
        streamFromStatus(body)?.let { (id, kind) -> registerStream(id, kind) }
        when {
            body.startsWith("display pan ") -> applyPan(body.removePrefix("display pan "))
            body.startsWith("display waterfall ") -> applyWaterfall(body.removePrefix("display waterfall "))
            body.startsWith("stream ") -> applyStream(body.removePrefix("stream "))
            body.startsWith("interlock ") -> applyInterlock(flexFields(body))
            body.startsWith("transmit ") -> applyTransmit(flexFields(body))
            body.startsWith("radio ") -> applyCapabilities(flexFields(body))
            body.startsWith("profile ") -> applyProfile(body.removePrefix("profile "))
        }
    }

    private fun applyPan(rest: String) {
        val tokens = rest.split(Regex("\\s+"))
        val id = parseFlexNumber(tokens.firstOrNull()) ?: return
        if (tokens.drop(1).contains("removed")) {
            pans.remove(id)
            return
        }
        val fields = flexFields(rest)
        val previous = pans[id] ?: FlexPanState(id)
        pans[id] = previous.copy(
            streamId = parseFlexNumber(fields["stream_id"]) ?: previous.streamId,
            clientHandle = parseFlexNumber(fields["client_handle"]) ?: previous.clientHandle,
            centerHz = fields["center"]?.toDoubleOrNull()?.times(1_000_000)?.toLong() ?: previous.centerHz,
            bandwidthHz = fields["bandwidth"]?.toDoubleOrNull()?.times(1_000_000)?.toLong() ?: previous.bandwidthHz,
            minDbm = fields["min_dbm"]?.toIntOrNull() ?: previous.minDbm,
            maxDbm = fields["max_dbm"]?.toIntOrNull() ?: previous.maxDbm,
            fps = fields["fps"]?.toIntOrNull() ?: previous.fps,
            xPixels = fields["xpixels"]?.toIntOrNull() ?: previous.xPixels,
            yPixels = fields["ypixels"]?.toIntOrNull() ?: previous.yPixels,
            band = fields["band"] ?: previous.band,
            rfGain = fields["rfgain"]?.toIntOrNull() ?: previous.rfGain,
        )
        pans[id]?.streamId?.takeIf { it != 0L }?.let { registerStream(it, FlexStreamKind.PANADAPTER) }
    }

    private fun applyWaterfall(rest: String) {
        val tokens = rest.split(Regex("\\s+"))
        val id = parseFlexNumber(tokens.firstOrNull()) ?: return
        if (tokens.drop(1).contains("removed")) {
            waterfalls.remove(id)
            return
        }
        val fields = flexFields(rest)
        val previous = waterfalls[id] ?: FlexWaterfallState(id)
        waterfalls[id] = previous.copy(
            streamId = parseFlexNumber(fields["stream_id"]) ?: previous.streamId,
            panId = parseFlexNumber(fields["panadapter"]) ?: parseFlexNumber(fields["pan"]) ?: previous.panId,
            clientHandle = parseFlexNumber(fields["client_handle"]) ?: previous.clientHandle,
        )
        waterfalls[id]?.streamId?.takeIf { it != 0L }?.let { registerStream(it, FlexStreamKind.WATERFALL) }
    }

    private fun applyStream(rest: String) {
        val tokens = rest.split(Regex("\\s+"))
        val id = parseFlexNumber(tokens.firstOrNull()) ?: return
        if (tokens.drop(1).contains("removed")) {
            streams.remove(id)
            return
        }
        val fields = flexFields(rest)
        val previous = streams[id] ?: FlexNetworkStream(id, fields["type"].orEmpty())
        streams[id] = previous.copy(
            type = fields["type"] ?: previous.type,
            clientHandle = parseFlexNumber(fields["client_handle"]) ?: previous.clientHandle,
            compression = fields["compression"] ?: previous.compression,
        )
        val type = streams[id]?.type.orEmpty()
        val kind = when {
            type == "remote_audio_rx" && streams[id]?.compression == "opus" -> FlexStreamKind.OPUS_AUDIO
            type == "remote_audio_rx" -> FlexStreamKind.REMOTE_AUDIO
            type == "dax_rx" -> FlexStreamKind.REDUCED_AUDIO
            else -> null
        }
        if (kind != null) registerStream(id, kind)
    }

    private fun applyInterlock(fields: Map<String, String>) {
        transmit = transmit.copy(
            interlock = fields["state"] ?: transmit.interlock,
            source = fields["source"] ?: transmit.source,
            txClientHandle = parseFlexNumber(fields["tx_client_handle"]) ?: transmit.txClientHandle,
        )
    }

    private fun applyTransmit(fields: Map<String, String>) {
        transmit = transmit.copy(
            mox = fields["mox"]?.let(::flexBool) ?: transmit.mox,
            tune = fields["tune"]?.let(::flexBool) ?: transmit.tune,
            rfPower = fields["rfpower"]?.toIntOrNull() ?: transmit.rfPower,
            tunePower = fields["tunepower"]?.toIntOrNull() ?: transmit.tunePower,
            txAntenna = fields["tx_ant"] ?: fields["txant"] ?: transmit.txAntenna,
        )
    }

    private fun applyCapabilities(fields: Map<String, String>) {
        capabilities = capabilities.copy(
            maxSlices = fields["num_scu"]?.toIntOrNull()?.times(2)
                ?: fields["max_slices"]?.toIntOrNull() ?: capabilities.maxSlices,
            maxPanadapters = fields["num_scu"]?.toIntOrNull()
                ?: fields["max_panadapters"]?.toIntOrNull() ?: capabilities.maxPanadapters,
            model = fields["model"] ?: capabilities.model,
        )
    }

    private fun applyProfile(rest: String) {
        val tokens = rest.split(Regex("\\s+"))
        val kind = tokens.firstOrNull()?.lowercase()?.takeIf { it in setOf("global", "tx", "mic") } ?: return
        val fields = flexFields(rest)
        val name = fields["name"] ?: fields["current"] ?: tokens.drop(1).joinToString(" ").trim('"')
        if (name.isNotBlank() && name !in setOf("list", "info")) profiles.getOrPut(kind) { linkedSetOf() } += name
    }

    @Synchronized
    fun snapshot() = FlexExtendedSnapshot(
        pans.values.toList(),
        waterfalls.values.toList(),
        streams.values.toList(),
        transmit,
        capabilities.copy(maxSlices = capabilities.maxSlices.coerceAtLeast(1), maxPanadapters = capabilities.maxPanadapters.coerceAtLeast(1)),
        profiles.mapValues { it.value.toList() },
    )

    @Synchronized
    fun clear() {
        pans.clear()
        waterfalls.clear()
        streams.clear()
        profiles.clear()
        transmit = FlexTransmitState()
        capabilities = FlexCapabilities()
        meterBank.clear()
    }
}

private fun flexBool(value: String): Boolean = value.lowercase() in setOf("1", "true", "yes", "on")
