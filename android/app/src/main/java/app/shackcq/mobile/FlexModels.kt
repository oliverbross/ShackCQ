package app.shackcq.mobile

import org.json.JSONObject

data class FlexDiscovery(
    val model: String, val nickname: String, val ip: String, val port: Int, val serial: String,
    val callsign: String, val version: String, val status: String, val guiStations: String,
)

fun manualFlexDiscovery(address: String): FlexDiscovery? {
    val ip = address.trim()
    val octets = ip.split('.')
    if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { ch -> !ch.isDigit() } || it.toIntOrNull() !in 0..255 }) return null
    return FlexDiscovery("FlexRadio", "Manual LAN / VPN", ip, 4992, "", "", "", "Configured", "")
}

data class FlexClient(
    val handle: Long, val clientId: String, val program: String, val station: String,
    val connected: Boolean, val gui: Boolean,
)

data class FlexSlice(
    val index: Int, val letter: String, val inUse: Boolean, val active: Boolean, val tx: Boolean,
    val clientHandle: Long, val frequencyHz: Long, val mode: String, val filterLowHz: Int,
    val filterHighHz: Int, val rxAntenna: String,
) { val filterWidthHz get() = filterHighHz - filterLowHz }

data class FlexSnapshot(
    val connected: Boolean = false, val handle: Long = 0, val version: String = "", val model: String = "",
    val nickname: String = "", val callsign: String = "", val serial: String = "", val firmware: String = "",
    val clients: List<FlexClient> = emptyList(), val slices: List<FlexSlice> = emptyList(),
) {
    val hasCommandChannel get() = connected && handle != 0L
    fun selected(index: Int?): FlexSlice? = slices.firstOrNull { it.index == index && it.inUse && !it.tx }
    fun toRadioState(index: Int?): RadioState {
        val slice = selected(index)
        return RadioState(identity = callsign.ifBlank { nickname.ifBlank { serial.ifBlank { "FLEX" } } },
            model = model.ifBlank { "FlexRadio" }, mode = slice?.mode ?: "--", frequencyHz = slice?.frequencyHz ?: 0,
            connected = connected && handle != 0L && slice != null, transmitting = slice?.tx == true,
            bandwidthHz = slice?.filterWidthHz ?: 0, revision = System.nanoTime(),
            effectiveRxHz = slice?.frequencyHz ?: 0, updatedMonotonicMs = System.nanoTime() / 1_000_000)
    }
}

fun parseFlexDiscovery(data: ByteArray): FlexDiscovery? = NativeCore.flexParseDiscovery(data).takeIf(String::isNotBlank)?.let { text ->
    runCatching { JSONObject(text) }.getOrNull()?.let { value ->
        FlexDiscovery(value.optString("model"), value.optString("nickname"), value.optString("ip"), value.optInt("port", 4992),
            value.optString("serial"), value.optString("callsign"), value.optString("version"), value.optString("status"),
            value.optString("guiClientStations"))
    }
}

fun parseFlexSnapshot(text: String): FlexSnapshot = runCatching {
    val value = JSONObject(text)
    val clients = value.optJSONArray("clients")?.let { values -> (0 until values.length()).map { index -> values.getJSONObject(index).let {
        FlexClient(it.optLong("handle"), it.optString("clientId"), it.optString("program"), it.optString("station"), it.optBoolean("connected"), it.optBoolean("gui"))
    } } }.orEmpty()
    val slices = value.optJSONArray("slices")?.let { values -> (0 until values.length()).map { index -> values.getJSONObject(index).let {
        FlexSlice(it.optInt("index"), it.optString("letter"), it.optBoolean("inUse"), it.optBoolean("active"), it.optBoolean("tx"),
            it.optLong("clientHandle"), it.optLong("frequencyHz"), it.optString("mode"), it.optInt("filterLowHz"),
            it.optInt("filterHighHz"), it.optString("rxAntenna"))
    } } }.orEmpty()
    FlexSnapshot(value.optBoolean("connected"), value.optLong("handle"), value.optString("version"), value.optString("model"),
        value.optString("nickname"), value.optString("callsign"), value.optString("serial"), value.optString("firmware"), clients, slices)
}.getOrDefault(FlexSnapshot())
