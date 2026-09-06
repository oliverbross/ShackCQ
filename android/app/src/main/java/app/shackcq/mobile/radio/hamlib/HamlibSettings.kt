package app.shackcq.mobile.radio.hamlib

import org.json.JSONArray
import org.json.JSONObject

data class HamlibSavedProfile(
    val name: String,
    val modelId: Int,
    val serial: HamlibSerialProfile? = null,
    val network: HamlibNetworkProfile? = null,
    val pollIntervalMs: Long = 500,
    val readOnly: Boolean = true,
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require((serial == null) != (network == null))
        require(pollIntervalMs in 100..60_000)
    }
}

data class HamlibSettingsDocument(
    val profiles: List<HamlibSavedProfile> = emptyList(),
    val favoriteModelIds: Set<Int> = emptySet(),
    val recentModelIds: List<Int> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("schema", 1)
        put("favorites", JSONArray(favoriteModelIds.sorted()))
        put("recents", JSONArray(recentModelIds.distinct().take(20)))
        put("profiles", JSONArray(profiles.take(MAX_PROFILES).map { profile ->
            JSONObject().apply {
                put("name", profile.name); put("modelId", profile.modelId)
                put("pollIntervalMs", profile.pollIntervalMs); put("readOnly", profile.readOnly)
                profile.serial?.let { serial -> put("serial", JSONObject().apply {
                    put("stableDeviceId", serial.stableDeviceId); put("baud", serial.baud)
                    put("dataBits", serial.dataBits); put("stopBits", serial.stopBits)
                    put("parity", serial.parity); put("handshake", serial.handshake)
                    put("timeoutMs", serial.timeoutMs); put("rts", serial.rts); put("dtr", serial.dtr)
                }) }
                profile.network?.let { network -> put("network", JSONObject().apply {
                    put("host", network.host); put("port", network.port)
                    put("timeoutMs", network.timeoutMs); put("enabled", network.enabled)
                }) }
            }
        }))
    }.toString()

    companion object {
        const val MAX_PROFILES = 32
        fun parse(json: String): HamlibSettingsDocument {
            require(json.length <= 1024 * 1024) { "Hamlib settings exceed bound" }
            val root = JSONObject(json)
            require(root.getInt("schema") == 1)
            val profiles = root.getJSONArray("profiles")
            require(profiles.length() <= MAX_PROFILES)
            return HamlibSettingsDocument(
                profiles = (0 until profiles.length()).map { parseProfile(profiles.getJSONObject(it)) },
                favoriteModelIds = root.getJSONArray("favorites").ints().take(256).toSet(),
                recentModelIds = root.getJSONArray("recents").ints().take(20),
            )
        }

        private fun parseProfile(value: JSONObject): HamlibSavedProfile {
            val serial = value.optJSONObject("serial")?.let {
                HamlibSerialProfile(
                    stableDeviceId = it.getString("stableDeviceId"), baud = it.getInt("baud"),
                    dataBits = it.getInt("dataBits"), stopBits = it.getInt("stopBits"),
                    parity = it.getInt("parity"), handshake = it.getInt("handshake"),
                    timeoutMs = it.getInt("timeoutMs"), rts = it.getInt("rts"), dtr = it.getInt("dtr"),
                )
            }
            val network = value.optJSONObject("network")?.let {
                HamlibNetworkProfile(it.getString("host"), it.getInt("port"),
                    it.getInt("timeoutMs"), it.getBoolean("enabled"))
            }
            return HamlibSavedProfile(value.getString("name"), value.getInt("modelId"), serial, network,
                value.getLong("pollIntervalMs"), value.getBoolean("readOnly"))
        }
        private fun JSONArray.ints() = (0 until length()).map(::getInt)
    }
}
