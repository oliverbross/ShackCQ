package app.shackcq.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class WavelogV2Request(
    val method: String,
    val url: String,
    val bearerToken: String,
    val body: String? = null,
)

data class WavelogV2Response(val status: Int, val body: String, val retryAfter: String? = null)

fun interface WavelogV2Transport { fun execute(request: WavelogV2Request): WavelogV2Response }

class DefaultWavelogV2Transport : WavelogV2Transport {
    override fun execute(request: WavelogV2Request): WavelogV2Response {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
        request.body?.let { body ->
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return WavelogV2Response(status, text, connection.getHeaderField("Retry-After"))
    }
}

data class WavelogTokenMetadata(
    val id: Long,
    val name: String,
    val owner: String,
    val userId: Long,
    val scopes: Set<String>,
    val expiresAt: String,
) {
    val capabilities = WavelogCapabilities(
        scopes = scopes,
        canReadQsos = "qso:read" in scopes,
        canWriteQsos = "qso:write" in scopes,
        canDeleteQsos = "qso:delete" in scopes,
        canReadStations = "station:read" in scopes,
        canReadStatistics = "statistic:read" in scopes,
    )
}

data class WavelogV2Station(
    val id: String,
    val uuid: String,
    val name: String,
    val callsign: String,
    val grid: String,
    val active: Boolean,
)

data class WavelogV2Page(
    val rows: List<JSONObject>,
    val page: Int,
    val totalPages: Int,
    val total: Int,
    val hasMore: Boolean,
)

class WavelogApiException(
    val errorClass: WavelogErrorClass,
    val status: Int,
    val code: String,
    val retryAfterSeconds: Long?,
    message: String,
) : Exception(message)

class WavelogApiV2Client(
    baseUrl: String,
    private val token: String,
    private val transport: WavelogV2Transport = DefaultWavelogV2Transport(),
) {
    val apiRoot = normalizeWavelogV2Root(baseUrl)

    init { require(token.startsWith("wl2_")) { "API v2 requires a wl2_ token" } }

    fun tokenMetadata(): WavelogTokenMetadata {
        val data = request("GET", "token").getJSONObject("data")
        val scopes = data.optJSONArray("scopes").jsonStringList().toSet()
        return WavelogTokenMetadata(
            id = data.optLong("id"), name = data.optString("name"), owner = data.optString("owner"),
            userId = data.optLong("user_id"), scopes = scopes, expiresAt = data.optString("expires_at"),
        )
    }

    fun stations(): List<WavelogV2Station> {
        val rows = request("GET", "station").optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
                add(WavelogV2Station(
                    id = first(row, "id", "station_id"), uuid = first(row, "uuid", "station_uuid"),
                    name = first(row, "name", "station_profile_name"), callsign = first(row, "callsign", "station_callsign"),
                    grid = first(row, "grid", "gridsquare", "station_gridsquare"),
                    active = truth(row.opt("active")) || truth(row.opt("station_active")),
                ))
            }
        }
    }

    fun qsoPage(stationId: String, page: Int, perPage: Int = 250, sinceId: String = "0"): WavelogV2Page {
        require(page >= 1)
        val query = "qso?station_id=${encode(stationId)}&page=$page&per_page=${perPage.coerceIn(1, 5000)}&since_id=${encode(sinceId)}"
        val root = request("GET", query)
        val data = root.optJSONArray("data") ?: JSONArray()
        val meta = root.optJSONObject("meta") ?: JSONObject()
        return WavelogV2Page(
            rows = buildList { for (index in 0 until data.length()) data.optJSONObject(index)?.let(::add) },
            page = meta.optInt("page", page), totalPages = meta.optInt("total_pages", page),
            total = meta.optInt("total"), hasMore = meta.optBoolean("has_more"),
        )
    }

    fun qso(remoteId: String): JSONObject = request("GET", "qso/${encode(remoteId)}").getJSONObject("data")

    fun createQso(stationId: String, canonical: CanonicalQso): JSONObject =
        request("POST", "qso", jsonCreateBody(stationId, canonical).toString()).getJSONObject("data")

    fun patchQso(remoteId: String, baseline: CanonicalQso, canonical: CanonicalQso): JSONObject =
        request("PATCH", "qso/${encode(remoteId)}", jsonPatchBody(baseline, canonical).toString()).getJSONObject("data")

    fun deleteQso(remoteId: String) {
        request("DELETE", "qso/${encode(remoteId)}")
    }

    private fun request(method: String, resource: String, body: String? = null): JSONObject {
        val response = try {
            transport.execute(WavelogV2Request(method, "$apiRoot/$resource", token, body))
        } catch (error: Exception) {
            throw WavelogApiException(WavelogErrorClass.TEMPORARY, 0, "network_error", null,
                error.message?.take(300) ?: "Network request failed")
        }
        if (response.status == 204) return JSONObject()
        val root = runCatching { JSONObject(response.body) }.getOrElse {
            throw WavelogApiException(WavelogErrorClass.MALFORMED_RESPONSE, response.status, "malformed_response", null,
                "Wavelog returned an unexpected response")
        }
        if (response.status !in 200..299) {
            val error = root.optJSONObject("error") ?: JSONObject()
            val code = error.optString("code", "http_${response.status}")
            val safeMessage = error.optString("message", "Wavelog request failed").take(300)
            throw WavelogApiException(classify(response.status, code), response.status, code,
                parseRetryAfter(response.retryAfter), safeMessage)
        }
        return root
    }

    private fun classify(status: Int, code: String): WavelogErrorClass = when {
        code == "token_expired" -> WavelogErrorClass.EXPIRED_TOKEN
        status == 401 -> WavelogErrorClass.AUTHENTICATION
        status == 403 -> WavelogErrorClass.MISSING_SCOPE
        status == 404 -> WavelogErrorClass.NOT_FOUND
        status == 409 -> WavelogErrorClass.CONFLICT
        status == 429 -> WavelogErrorClass.RATE_LIMIT
        status in 400..499 -> WavelogErrorClass.VALIDATION
        else -> WavelogErrorClass.TEMPORARY
    }

    private fun first(row: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull { key ->
        row.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
    }.orEmpty()
    private fun truth(value: Any?) = when (value) { is Boolean -> value; is Number -> value.toInt() != 0; else -> value.toString() in setOf("1", "true") }
    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun parseRetryAfter(value: String?) = value?.trim()?.toLongOrNull()?.coerceAtLeast(0)
}

internal val wavelogJsonFields = mapOf(
    "CALL" to "call", "BAND" to "band", "BAND_RX" to "band_rx", "RST_SENT" to "rst_sent",
    "RST_RCVD" to "rst_rcvd", "GRIDSQUARE" to "gridsquare", "NAME" to "name", "COMMENT" to "comment",
    "NOTES" to "notes", "QTH" to "qth", "TX_PWR" to "tx_pwr", "PROP_MODE" to "prop_mode",
    "SAT_NAME" to "sat_name", "SAT_MODE" to "sat_mode", "SOTA_REF" to "sota_ref", "POTA_REF" to "pota_ref",
    "WWFF_REF" to "wwff_ref", "IOTA" to "iota", "SIG" to "sig", "SIG_INFO" to "sig_info",
    "DARC_DOK" to "darc_dok", "STATE" to "state", "CNTY" to "cnty", "CQZ" to "cqz", "ITUZ" to "ituz",
    "QSL_VIA" to "qsl_via", "SRX" to "srx", "STX" to "stx", "SRX_STRING" to "srx_string",
    "STX_STRING" to "stx_string",
)

internal val wavelogCreateOnlyFields = mapOf(
    "OPERATOR" to "operator", "STATION_CALLSIGN" to "station_callsign", "MY_GRIDSQUARE" to "my_gridsquare",
    "MY_COUNTRY" to "my_country", "MY_DXCC" to "my_dxcc", "MY_CQ_ZONE" to "my_cq_zone",
    "MY_ITU_ZONE" to "my_itu_zone", "MY_STATE" to "my_state", "MY_IOTA" to "my_iota",
    "MY_SOTA_REF" to "my_sota_ref", "MY_WWFF_REF" to "my_wwff_ref", "MY_POTA_REF" to "my_pota_ref",
    "RIG" to "rig", "DXCC" to "dxcc", "CONT" to "cont", "CONTEST_ID" to "contest_id",
    "QSL_SENT" to "qsl_sent", "QSL_RCVD" to "qsl_rcvd", "QSL_SENT_VIA" to "qsl_sent_via",
    "QSL_RCVD_VIA" to "qsl_rcvd_via", "QSLMSG" to "qslmsg", "LOTW_QSL_SENT" to "lotw_qsl_sent",
    "LOTW_QSL_RCVD" to "lotw_qsl_rcvd", "EQSL_QSL_SENT" to "eqsl_qsl_sent", "EQSL_QSL_RCVD" to "eqsl_qsl_rcvd",
)

internal fun jsonCreateBody(stationId: String, canonical: CanonicalQso): JSONObject {
    val body = JSONObject().put("station_profile_id", stationId.toLong()).put("import_type", "json")
    (wavelogJsonFields + wavelogCreateOnlyFields).forEach { (adif, json) ->
        canonical.fields[adif]?.let { body.put(json, it) }
    }
    canonical.fields["QSO_DATE"]?.let { body.put("qso_date", isoDate(it)) }
    canonical.fields["TIME_ON"]?.let { body.put("time_on", it) }
    canonical.fields["TIME_OFF"]?.let { body.put("time_off", it) }
    canonical.fields["FREQ"]?.let { body.put("freq", mhzToHz(it)) }
    canonical.fields["FREQ_RX"]?.let { body.put("freq_rx", mhzToHz(it)) }
    canonical.fields["SUBMODE"].orEmpty().ifBlank { canonical.fields["MODE"].orEmpty() }
        .takeIf(String::isNotBlank)?.let { body.put("mode", it) }
    return body
}

internal fun jsonPatchBody(baseline: CanonicalQso, canonical: CanonicalQso): JSONObject {
    val changed = baseline.changedFields(canonical)
    val body = JSONObject()
    wavelogJsonFields.forEach { (adif, json) -> if (adif in changed) body.put(json, canonical.fields[adif].orEmpty()) }
    if (changed.any { it == "QSO_DATE" || it == "TIME_ON" || it == "TIME_OFF" }) {
        body.put("qso_date", isoDate(canonical.fields["QSO_DATE"].orEmpty()))
        body.put("time_on", canonical.fields["TIME_ON"].orEmpty())
        canonical.fields["TIME_OFF"]?.let { body.put("time_off", it) }
    }
    if ("FREQ" in changed) body.put("freq", mhzToHz(canonical.fields["FREQ"].orEmpty()))
    if ("FREQ_RX" in changed) body.put("freq_rx", mhzToHz(canonical.fields["FREQ_RX"].orEmpty()))
    if (changed.any { it == "MODE" || it == "SUBMODE" }) {
        body.put("mode", canonical.fields["SUBMODE"].orEmpty().ifBlank { canonical.fields["MODE"].orEmpty() })
    }
    require(body.length() > 0) { "No Wavelog-editable fields changed" }
    return body
}

private fun isoDate(value: String): String = value.filter(Char::isDigit).let {
    require(it.length == 8) { "Invalid QSO date" }; "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}"
}

private fun mhzToHz(value: String): Long {
    val mhz = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid frequency")
    return mhz.movePointRight(6).longValueExact()
}

fun normalizeWavelogV2Root(raw: String): String {
    var value = raw.trim().trimEnd('/')
    if (!value.contains("://")) value = "https://$value"
    val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid Wavelog URL") }
    require(uri.scheme.equals("https", true)) { "Wavelog API v2 requires HTTPS" }
    require(!uri.host.isNullOrBlank()) { "Invalid Wavelog host" }
    val clean = URI("https", uri.userInfo, uri.host, uri.port, uri.path, null, null).toString().trimEnd('/')
    return when {
        clean.endsWith("/index.php/api/v2") || clean.endsWith("/api/v2") -> clean
        clean.endsWith("/index.php") -> "$clean/api/v2"
        else -> "$clean/index.php/api/v2"
    }
}
