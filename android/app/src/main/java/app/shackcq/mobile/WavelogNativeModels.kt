package app.shackcq.mobile

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

enum class WavelogApiGeneration { LEGACY, V2 }
enum class WavelogBindingState { ENABLED, PAUSED, READ_ONLY }
enum class WavelogOperation { CREATE, UPDATE, DELETE }
enum class WavelogOutboxState { PENDING, RETRY_WAIT, PAUSED, BLOCKED, ACCEPTED }
enum class WavelogConflictState { OPEN, KEEP_LOCAL, KEEP_REMOTE, MERGED }
enum class QsoDeleteIntent { LOCAL_ONLY, DELETE_REMOTE_IF_UNCHANGED, REMOTE_SYNC }
enum class WavelogErrorClass {
    NONE, AUTHENTICATION, EXPIRED_TOKEN, MISSING_SCOPE, NOT_FOUND, VALIDATION,
    CONFLICT, RATE_LIMIT, TEMPORARY, MALFORMED_RESPONSE, AMBIGUOUS_WRITE
}

data class WavelogCapabilities(
    val scopes: Set<String> = emptySet(),
    val canReadQsos: Boolean = false,
    val canWriteQsos: Boolean = false,
    val canDeleteQsos: Boolean = false,
    val canReadStations: Boolean = false,
    val canReadStatistics: Boolean = false,
)

data class WavelogBinding(
    val id: String = UUID.randomUUID().toString(),
    val baseUrl: String,
    val credentialAlias: String,
    val apiGeneration: WavelogApiGeneration,
    val capabilities: WavelogCapabilities = WavelogCapabilities(),
    val tokenOwner: String = "",
    val remoteStationId: String = "",
    val remoteStationUuid: String = "",
    val remoteStationName: String = "",
    val localStationProfileId: String = "",
    val state: WavelogBindingState = WavelogBindingState.ENABLED,
    val downstreamPolicy: String = "WAVELOG_AUTHORITY",
    val lastQuickSync: Long? = null,
    val lastFullReconcile: Long? = null,
    val highWater: String = "",
    val lastErrorClass: WavelogErrorClass = WavelogErrorClass.NONE,
    val lastErrorSummary: String = "",
    val testedRelease: String = "",
)

data class WavelogRemoteLink(
    val bindingId: String,
    val localQsoId: String,
    val remoteQsoId: String,
    val baselineHash: String,
    val baselineCanonical: String,
    val remoteUpdatedAt: String = "",
)

data class WavelogOutboxEntry(
    val id: String,
    val bindingId: String,
    val localQsoId: String,
    val operation: WavelogOperation,
    val operationKey: String,
    val payloadHash: String,
    val canonicalPayload: String,
    val state: WavelogOutboxState,
    val attemptCount: Int,
    val nextAttemptAt: Long?,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
    val errorClass: WavelogErrorClass = WavelogErrorClass.NONE,
)

data class WavelogSyncCheckpoint(
    val bindingId: String,
    val kind: String,
    val page: Int = 1,
    val highWater: String = "",
    val overlapHash: String = "",
    val completed: Boolean = false,
    val updatedAt: Long,
)

data class WavelogConflict(
    val id: String,
    val bindingId: String,
    val localQsoId: String,
    val remoteQsoId: String,
    val baselineCanonical: String,
    val localCanonical: String,
    val remoteCanonical: String,
    val conflictingFields: Set<String>,
    val state: WavelogConflictState,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val resolutionIntent: WavelogConflictState? = null,
    val resolutionCanonical: String = "",
    val resolutionOutboxId: String = "",
)

data class WavelogTombstone(
    val bindingId: String,
    val localQsoId: String,
    val remoteQsoId: String,
    val canonicalHash: String,
    val deletedAt: Long,
    val acknowledgedAt: Long? = null,
    val intent: QsoDeleteIntent = QsoDeleteIntent.LOCAL_ONLY,
)

data class CanonicalQso(val fields: Map<String, String>) {
    val encoded: String = fields.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
        "${JSONObject.quote(key)}:${JSONObject.quote(value)}"
    }
    val hash: String = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun changedFields(other: CanonicalQso): Set<String> =
        (fields.keys + other.fields.keys).filterTo(sortedSetOf()) { fields[it] != other.fields[it] }

    fun asAdif(): String = fields.toSortedMap().entries.joinToString("") { (key, value) ->
        "<$key:${value.toByteArray(Charsets.UTF_8).size}>$value"
    } + "<EOR>"

    companion object {
        fun decode(value: String): CanonicalQso {
            if (value.trimStart().startsWith('{')) {
                val json = JSONObject(value)
                return CanonicalQso(buildMap {
                    json.keys().forEach { key -> put(key, json.optString(key)) }
                }.toSortedMap())
            }
            val fields = linkedMapOf<String, String>()
            var cursor = 0
            while (cursor < value.length) {
                while (cursor < value.length && (value[cursor] == '\n' || value[cursor] == '\r')) cursor++
                val first = value.indexOf(':', cursor); val second = value.indexOf(':', first + 1)
                if (first <= cursor || second <= first) break
                val length = value.substring(first + 1, second).toIntOrNull() ?: break
                val extracted = takeUtf8(value, second + 1, length) ?: break
                fields[value.substring(cursor, first)] = extracted.first
                cursor = extracted.second
            }
            return CanonicalQso(fields.toSortedMap())
        }

        private fun takeUtf8(text: String, start: Int, byteLength: Int): Pair<String, Int>? {
            var index = start; var bytes = 0
            while (index < text.length && bytes < byteLength) {
                val codePoint = Character.codePointAt(text, index)
                val chars = Character.charCount(codePoint)
                val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > byteLength) return null
                bytes += size; index += chars
            }
            return if (bytes == byteLength) text.substring(start, index) to index else null
        }
    }
}

data class ThreeWayMergeResult(
    val merged: CanonicalQso?,
    val conflictingFields: Set<String>,
    val disposition: String,
)

object WavelogCanonicalizer {
    val excludedFields = setOf(
        "APP_KX3TOUCH_UUID", "APP_SHACKCQ_SYNC_STATE", "APP_SHACKCQ_REMOTE_ID", "APP_SHACKCQ_OUTBOX_ID",
        "APP_SHACKCQ_LAST_SYNC", "APP_SHACKCQ_CREDENTIAL_ALIAS",
    )

    val shackCQFields = setOf(
        "APP_KX3TOUCH_UUID", "CALL", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "FREQ", "FREQ_RX",
        "MODE", "SUBMODE", "BAND", "BAND_RX", "RST_SENT", "RST_RCVD", "TX_PWR", "NAME", "QTH", "COUNTRY",
        "GRIDSQUARE", "IOTA", "SOTA_REF", "WWFF_REF", "POTA_REF", "COMMENT", "NOTES", "OPERATOR",
        "STATION_CALLSIGN", "MY_GRIDSQUARE", "MY_COUNTRY", "MY_DXCC", "MY_CQ_ZONE", "MY_ITU_ZONE", "MY_STATE",
        "MY_IOTA", "MY_SOTA_REF", "MY_WWFF_REF", "MY_POTA_REF", "RIG", "DXCC", "CONT", "APP_SHACKCQ_REGION",
        "CQZ", "ITUZ", "STATE", "EMAIL", "PROP_MODE", "ANT_PATH", "QSL_SENT", "QSL_RCVD", "QSL_SENT_VIA",
        "QSL_RCVD_VIA", "QSL_VIA", "QSLMSG", "CNTY", "DARC_DOK", "CONTEST_ID", "DISTANCE",
        "APP_SHACKCQ_DURATION_SECONDS", "LOTW_QSL_SENT", "LOTW_QSL_RCVD", "CLUBLOG_QSO_UPLOAD_STATUS",
        "CLUBLOG_QSO_DOWNLOAD_STATUS", "EQSL_QSL_SENT", "EQSL_QSL_RCVD", "DCL_QSL_SENT", "DCL_QSL_RCVD",
        "QRZCOM_QSO_UPLOAD_STATUS", "QRZCOM_QSO_DOWNLOAD_STATUS", "APP_SHACKCQ_QSL_IMAGES",
    )

    private val upperValueFields = setOf(
        "CALL", "MODE", "SUBMODE", "BAND", "BAND_RX", "GRIDSQUARE", "MY_GRIDSQUARE",
        "STATION_CALLSIGN", "OPERATOR", "CONT", "PROP_MODE", "SAT_NAME", "SAT_MODE",
        "QSL_SENT", "QSL_RCVD", "LOTW_QSL_SENT", "LOTW_QSL_RCVD", "EQSL_QSL_SENT",
        "EQSL_QSL_RCVD", "CLUBLOG_QSO_UPLOAD_STATUS", "QRZCOM_QSO_UPLOAD_STATUS",
    )

    fun fromAdif(adif: String): CanonicalQso {
        val fields = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < adif.length) {
            val open = adif.indexOf('<', cursor); if (open < 0) break
            val close = adif.indexOf('>', open + 1); if (close < 0) break
            val header = adif.substring(open + 1, close)
            if (header.equals("EOR", true)) break
            val parts = header.split(':', limit = 3)
            val length = parts.getOrNull(1)?.toIntOrNull()
            if (parts.isEmpty() || length == null) { cursor = close + 1; continue }
            val extracted = takeUtf8(adif, close + 1, length) ?: break
            val name = parts[0].uppercase(Locale.US)
            if (name !in excludedFields) fields[name] = normalize(name, extracted.first)
            cursor = extracted.second
        }
        return CanonicalQso(fields.toSortedMap())
    }

    fun merge(base: CanonicalQso, local: CanonicalQso, remote: CanonicalQso): ThreeWayMergeResult {
        if (local.hash == base.hash && remote.hash == base.hash) return ThreeWayMergeResult(base, emptySet(), "UNCHANGED")
        if (local.hash != base.hash && remote.hash == base.hash) return ThreeWayMergeResult(local, emptySet(), "PUSH_LOCAL")
        if (local.hash == base.hash && remote.hash != base.hash) return ThreeWayMergeResult(remote, emptySet(), "PULL_REMOTE")
        if (local.hash == remote.hash) return ThreeWayMergeResult(local, emptySet(), "CONVERGED")
        val localChanged = base.changedFields(local)
        val remoteChanged = base.changedFields(remote)
        val conflicts = localChanged.intersect(remoteChanged).filterTo(sortedSetOf()) { local.fields[it] != remote.fields[it] }
        if (conflicts.isNotEmpty()) return ThreeWayMergeResult(null, conflicts, "CONFLICT")
        val merged = base.fields.toMutableMap().apply {
            localChanged.forEach { key -> local.fields[key]?.let { put(key, it) } ?: remove(key) }
            remoteChanged.forEach { key -> remote.fields[key]?.let { put(key, it) } ?: remove(key) }
        }
        return ThreeWayMergeResult(CanonicalQso(merged.toSortedMap()), emptySet(), "SAFE_MERGE")
    }

    private fun normalize(name: String, value: String): String {
        val lineSafe = value.replace("\r\n", "\n").replace('\r', '\n')
        return when {
            name in upperValueFields -> lineSafe.trim().uppercase(Locale.US)
            name in setOf("QSO_DATE", "QSO_DATE_OFF") -> lineSafe.filter(Char::isDigit).take(8)
            name in setOf("TIME_ON", "TIME_OFF") -> lineSafe.filter(Char::isDigit).padEnd(6, '0').take(6)
            else -> lineSafe
        }
    }

    private fun takeUtf8(text: String, start: Int, byteLength: Int): Pair<String, Int>? {
        var index = start; var bytes = 0
        while (index < text.length && bytes < byteLength) {
            val codePoint = Character.codePointAt(text, index)
            val chars = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > byteLength) return null
            bytes += size; index += chars
        }
        return if (bytes == byteLength) text.substring(start, index) to index else null
    }
}
