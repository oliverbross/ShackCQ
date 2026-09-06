// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.contest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

internal const val SCP_FILES_URL = "https://www.supercheckpartial.com/api/v1/files"
internal const val SCP_DOWNLOAD_URL = "https://www.supercheckpartial.com/downloads/SCP.DB"
internal const val SCP_MAX_BYTES = 16L * 1024 * 1024
internal const val SCP_AUTO_REFRESH_SECONDS = 3L * 24 * 60 * 60

internal data class ScpFileMetadata(val name: String, val size: Long, val etag: String, val modified: String)
internal data class ScpDownload(val code: Int, val contentType: String, val etag: String?, val lastModified: String?, val bytes: ByteArray)
data class ScpStatus(
    val available: Boolean = false,
    val generatedAt: String = "",
    val sourceUrl: String = SCP_DOWNLOAD_URL,
    val sha256: String = "",
    val rowCount: Int = 0,
    val lastRefreshEpoch: Long = 0,
    val message: String = "database unavailable",
)
enum class ScpMatchState { MATCH, POSSIBLE, NOT_IN_CURRENT_SCP, DATABASE_UNAVAILABLE }
data class ScpSuggestion(
    val callsign: String,
    val state: ScpMatchState,
    val annualRate: Int = 0,
    val modes: Int = 0,
    val contests: Int = 0,
    val geo: Int = 0,
    val verified: Int = 0,
)

internal interface ScpTransport {
    fun discover(): String
    fun download(etag: String?, lastModified: String?): ScpDownload
}

internal class OfficialScpTransport(private val userAgent: String) : ScpTransport {
    override fun discover(): String = request(SCP_FILES_URL, null, null).bytes.decodeToString()
    override fun download(etag: String?, lastModified: String?) = request(SCP_DOWNLOAD_URL, etag, lastModified)

    private fun request(url: String, etag: String?, lastModified: String?): ScpDownload {
        require(url.startsWith("https://www.supercheckpartial.com/"))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", userAgent.take(120))
        connection.setRequestProperty("Accept", if (url.endsWith(".DB")) "application/octet-stream, application/vnd.sqlite3" else "application/json")
        etag?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-None-Match", it) }
        lastModified?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("If-Modified-Since", it) }
        return connection.useConnection { code ->
            require(code == 200 || code == 304) { "SCP HTTP $code" }
            val length = connection.contentLengthLong
            require(length < 0 || length <= SCP_MAX_BYTES) { "SCP response is oversized" }
            val bytes = if (code == 304) byteArrayOf() else connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= SCP_MAX_BYTES) { "SCP response is oversized" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            ScpDownload(code, connection.contentType.orEmpty(), connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"), bytes)
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (Int) -> T): T = try { block(responseCode) } finally { disconnect() }
}

internal class SuperCheckPartialStore(
    context: Context,
    private val transport: ScpTransport = OfficialScpTransport("ShackCQ Android/1 SCP runtime cache"),
) {
    private val directory = File(context.filesDir, "contest/scp").apply { mkdirs() }
    private val databaseFile = File(directory, "SCP.DB")
    private val preferences = context.getSharedPreferences("shackcq-scp-v1", Context.MODE_PRIVATE)

    fun status(): ScpStatus = if (!databaseFile.isFile) ScpStatus() else ScpStatus(
        true, preferences.getString("generated", "").orEmpty(), SCP_DOWNLOAD_URL,
        preferences.getString("sha256", "").orEmpty(), preferences.getInt("rows", 0),
        preferences.getLong("refreshed", 0), "offline last-good ready",
    )

    fun refresh(manual: Boolean, nowEpoch: Long = Instant.now().epochSecond): ScpStatus {
        val current = status()
        if (!manual && current.available && nowEpoch - current.lastRefreshEpoch < SCP_AUTO_REFRESH_SECONDS) return current
        val fileMetadata = discoverDatabase(transport.discover())
        require(fileMetadata.size in 1..SCP_MAX_BYTES) { "SCP metadata size is invalid" }
        val response = transport.download(preferences.getString("etag", null), preferences.getString("last_modified", null))
        if (response.code == 304) return current.copy(lastRefreshEpoch = nowEpoch, message = "official database unchanged").also {
            preferences.edit().putLong("refreshed", nowEpoch).apply()
        }
        require(response.contentType.substringBefore(';').lowercase(Locale.US) in setOf("application/octet-stream", "application/vnd.sqlite3", "application/x-sqlite3")) {
            "Unexpected SCP content type"
        }
        require(response.bytes.size.toLong() in 1..SCP_MAX_BYTES) { "SCP download size is invalid" }
        require(response.bytes.copyOfRange(0, 16).decodeToString() == "SQLite format 3\u0000") { "SCP download is not SQLite" }
        val pending = File(directory, "SCP.DB.pending")
        pending.outputStream().use { it.write(response.bytes); it.fd.sync() }
        val validation = validate(pending)
        val backup = File(directory, "SCP.DB.last-good")
        if (databaseFile.exists()) {
            backup.delete()
            require(databaseFile.renameTo(backup)) { "Unable to preserve SCP last-good" }
        }
        if (!pending.renameTo(databaseFile)) {
            backup.renameTo(databaseFile)
            error("Unable to replace SCP database")
        }
        backup.delete()
        return ScpStatus(true, validation.first, SCP_DOWNLOAD_URL, sha256(response.bytes), validation.second, nowEpoch, "official database refreshed").also { value ->
            preferences.edit().putString("etag", response.etag ?: fileMetadata.etag).putString("last_modified", response.lastModified ?: fileMetadata.modified)
                .putString("generated", value.generatedAt).putString("sha256", value.sha256).putInt("rows", value.rowCount)
                .putLong("refreshed", nowEpoch).apply()
        }
    }

    fun suggest(input: String, limit: Int = 12): List<ScpSuggestion> {
        require(limit in 1..30)
        val query = input.trim().uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '/' }.take(16)
        if (!databaseFile.isFile) return listOf(ScpSuggestion(query, ScpMatchState.DATABASE_UNAVAILABLE))
        if (query.length < 2) return emptyList()
        return SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT callsign,annual_rate,modes,contests,geo,verified FROM callsigns
                WHERE callsign=? OR callsign LIKE ? ORDER BY callsign=? DESC,annual_rate DESC,callsign LIMIT ?""".trimIndent(),
                arrayOf(query, "$query%", query, limit.toString())).use { cursor -> buildList {
                while (cursor.moveToNext()) add(ScpSuggestion(cursor.getString(0), if (cursor.getString(0) == query) ScpMatchState.MATCH else ScpMatchState.POSSIBLE,
                    cursor.getInt(1), cursor.getInt(2), cursor.getInt(3), cursor.getInt(4), cursor.getInt(5)))
            } }
        }.ifEmpty { listOf(ScpSuggestion(query, ScpMatchState.NOT_IN_CURRENT_SCP)) }
    }

    fun delete() {
        databaseFile.delete(); File(directory, "SCP.DB.pending").delete(); File(directory, "SCP.DB.last-good").delete()
        preferences.edit().clear().apply()
    }

    private fun discoverDatabase(document: String): ScpFileMetadata {
        require(document.toByteArray().size <= 256_000) { "SCP discovery document is oversized" }
        val rows = JSONArray(document)
        return (0 until rows.length()).asSequence().map(rows::getJSONObject).firstOrNull { it.getString("name") == "SCP.DB" }?.let {
            ScpFileMetadata(it.getString("name"), it.getLong("size"), it.getString("etag"), it.getString("modified"))
        } ?: error("Official SCP.DB metadata is unavailable")
    }

    private fun validate(file: File): Pair<String, Int> = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        require(tables.containsAll(setOf("callsigns", "metadata", "examples"))) { "SCP schema tables are invalid" }
        val columns = db.rawQuery("PRAGMA table_info(callsigns)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        require(columns.containsAll(setOf("callsign", "annual_rate", "modes", "contests", "geo", "verified"))) { "SCP callsigns schema is invalid" }
        val count = db.rawQuery("SELECT COUNT(*) FROM callsigns", null).use { c -> c.moveToFirst(); c.getInt(0) }
        require(count in 1..1_000_000) { "SCP row count is invalid" }
        val generated = db.rawQuery("SELECT value FROM metadata WHERE key='generated_at' LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        generated to count
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
