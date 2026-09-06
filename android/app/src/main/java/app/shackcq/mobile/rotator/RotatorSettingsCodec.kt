package app.shackcq.mobile.rotator

import org.json.JSONArray
import org.json.JSONObject

object RotatorSettingsCodec {
    fun encode(document: RotatorSettingsDocument, includeLanEndpoints: Boolean = false): String {
        val root = JSONObject().put("version", document.version).put("reducedMotion", document.reducedMotion)
        root.put("profiles", JSONArray().apply { document.profiles.forEach { put(profileToJson(it, includeLanEndpoints)) } })
        root.put("bandAssignments", JSONArray().apply { document.bandAssignments.forEach { a ->
            put(JSONObject().put("radioProfileId", a.radioProfileId).put("bandId", a.bandId)
                .put("rotatorProfileId", a.rotatorProfileId).put("policy", a.policy.name)
                .put("headingMode", a.headingMode.name).put("offsetDeg", a.offsetDeg)
                .put("bidirectional", a.bidirectional).put("txPolicy", a.txPolicy.name))
        } })
        return root.toString()
    }

    fun decode(payload: String): RotatorSettingsDocument {
        require(payload.toByteArray().size <= 256 * 1024)
        val root = JSONObject(payload)
        require(root.getInt("version") == ROTATOR_SETTINGS_VERSION)
        val profiles = root.getJSONArray("profiles").objects().map(::profileFromJson)
        val assignments = root.optJSONArray("bandAssignments")?.objects()?.map { item ->
            RotatorBandAssignment(item.optStringOrNull("radioProfileId"), item.getString("bandId"),
                item.getString("rotatorProfileId"), RotatorBandPolicy.valueOf(item.getString("policy")),
                HeadingMode.valueOf(item.optString("headingMode", HeadingMode.SHORT_PATH.name)),
                item.optDouble("offsetDeg", 0.0), item.optBoolean("bidirectional", false),
                MovementDuringTxPolicy.valueOf(item.optString("txPolicy", MovementDuringTxPolicy.BLOCK_NEW_MOVE.name)))
        } ?: emptyList()
        return RotatorSettingsDocument(profiles = profiles, bandAssignments = assignments,
            reducedMotion = root.optBoolean("reducedMotion", false))
    }

    private fun profileToJson(profile: RotatorDeviceProfile, includeLan: Boolean) = JSONObject()
        .put("id", profile.id).put("name", profile.name).put("backend", profile.backend.name)
        .put("protocol", profile.protocol.name).put("transport", profile.transport.name)
        .put("serial", profile.serial?.let { JSONObject().put("stableIdentityHash", it.stableIdentityHash)
            .put("baud", it.baud).put("dataBits", it.dataBits).put("parity", it.parity).put("stopBits", it.stopBits)
            .put("dtr", it.dtr).put("rts", it.rts).put("readTimeoutMs", it.readTimeoutMs).put("writeTimeoutMs", it.writeTimeoutMs) })
        .put("tcp", profile.tcp?.let { if (includeLan) JSONObject().put("host", it.host).put("port", it.port)
            .put("connectTimeoutMs", it.connectTimeoutMs).put("readTimeoutMs", it.readTimeoutMs).put("lanOptIn", it.lanOptIn) else JSONObject.NULL })
        .put("hamlibModelId", profile.hamlibModelId).put("connectOnForeground", profile.connectOnForeground)
        .put("hamlibSerial", profile.hamlibSerial?.let { JSONObject().put("stableIdentityHash", it.stableIdentityHash)
            .put("baud", it.baud).put("dataBits", it.dataBits).put("parity", it.parity).put("stopBits", it.stopBits)
            .put("dtr", it.dtr).put("rts", it.rts).put("readTimeoutMs", it.readTimeoutMs).put("writeTimeoutMs", it.writeTimeoutMs) })
        .put("hamlibTcp", profile.hamlibTcp?.let { if (includeLan) JSONObject().put("host", it.host).put("port", it.port)
            .put("connectTimeoutMs", it.connectTimeoutMs).put("readTimeoutMs", it.readTimeoutMs).put("lanOptIn", it.lanOptIn) else JSONObject.NULL })
        .put("pollIntervalMs", profile.pollIntervalMs).put("limits", JSONObject().put("azMin", profile.limits.azMin)
            .put("azMax", profile.limits.azMax).put("elMin", profile.limits.elMin).put("elMax", profile.limits.elMax))
        .put("parkAzimuthDeg", profile.parkAzimuthDeg).put("parkElevationDeg", profile.parkElevationDeg)
        .put("headingOffsetOwner", profile.headingOffsetOwner.name).put("calibrationOffsetDeg", profile.calibrationOffsetDeg)
        .put("allowFlipOver", profile.allowFlipOver)
        .put("forbiddenSectors", JSONArray().apply { profile.forbiddenSectors.forEach { put(JSONObject().put("startDeg", it.startDeg)
            .put("endDeg", it.endDeg).put("reason", it.reason).put("policy", it.policy.name)) } })
        .put("presets", JSONArray().apply { profile.presets.forEach { put(JSONObject().put("name", it.name).put("azimuthDeg", it.azimuthDeg)
            .put("elevationDeg", it.elevationDeg).put("bandId", it.bandId)) } })

    private fun profileFromJson(item: JSONObject): RotatorDeviceProfile {
        val limits = item.getJSONObject("limits")
        val serial = item.optJSONObject("serial")?.let { SerialSettings(it.getString("stableIdentityHash"), it.optInt("baud", 9600),
            it.optInt("dataBits", 8), it.optString("parity", "N"), it.optInt("stopBits", 1), it.optBoolean("dtr"),
            it.optBoolean("rts"), it.optInt("readTimeoutMs", 1500), it.optInt("writeTimeoutMs", 1500)) }
        val tcp = item.optJSONObject("tcp")?.let { TcpSettings(it.getString("host"), it.getInt("port"),
            it.optInt("connectTimeoutMs", 2000), it.optInt("readTimeoutMs", 1500), it.optBoolean("lanOptIn")) }
        fun parseSerial(name: String) = item.optJSONObject(name)?.let { SerialSettings(it.getString("stableIdentityHash"), it.optInt("baud", 9600),
            it.optInt("dataBits", 8), it.optString("parity", "N"), it.optInt("stopBits", 1), it.optBoolean("dtr"),
            it.optBoolean("rts"), it.optInt("readTimeoutMs", 1500), it.optInt("writeTimeoutMs", 1500)) }
        fun parseTcp(name: String) = item.optJSONObject(name)?.let { TcpSettings(it.getString("host"), it.getInt("port"),
            it.optInt("connectTimeoutMs", 2000), it.optInt("readTimeoutMs", 1500), it.optBoolean("lanOptIn")) }
        return RotatorDeviceProfile(item.getString("id"), item.getString("name"), RotatorBackend.valueOf(item.getString("backend")),
            RotatorProtocolKind.valueOf(item.getString("protocol")), RotatorTransportKind.valueOf(item.getString("transport")),
            serial, tcp, item.optIntOrNull("hamlibModelId"), parseSerial("hamlibSerial"), parseTcp("hamlibTcp"),
            item.optBoolean("connectOnForeground"), item.optInt("pollIntervalMs", 1000),
            RotatorLimits(limits.getDouble("azMin"), limits.getDouble("azMax"), limits.getDouble("elMin"), limits.getDouble("elMax")),
            item.optDoubleOrNull("parkAzimuthDeg"), item.optDoubleOrNull("parkElevationDeg"),
            HeadingOffsetOwner.valueOf(item.optString("headingOffsetOwner", HeadingOffsetOwner.NONE.name)), item.optDouble("calibrationOffsetDeg", 0.0),
            item.optBoolean("allowFlipOver"), item.optJSONArray("forbiddenSectors")?.objects()?.map { ForbiddenSector(it.getDouble("startDeg"),
                it.getDouble("endDeg"), it.getString("reason"), ForbiddenSectorPolicy.valueOf(it.getString("policy"))) } ?: emptyList(),
            presets = item.optJSONArray("presets")?.objects()?.map { RotatorPreset(it.getString("name"), it.getDouble("azimuthDeg"),
                it.optDoubleOrNull("elevationDeg"), it.optStringOrNull("bandId")) } ?: emptyList())
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optStringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optDoubleOrNull(key: String) = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.optIntOrNull(key: String) = if (has(key) && !isNull(key)) getInt(key) else null
}
