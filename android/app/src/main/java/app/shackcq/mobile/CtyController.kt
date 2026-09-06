package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AndroidCtyRecord(
    val country: String = "", val dxcc: String = "", val continent: String = "",
    val region: String = "", val cqZone: String = "", val ituZone: String = "",
    val latitude: Double = 0.0, val longitude: Double = 0.0,
)

enum class CtyUpdateState { NOT_INSTALLED, CHECKING, CURRENT, AVAILABLE, UPDATING, FAILED }

internal enum class CtyUpdateDecision { CURRENT, AVAILABLE, VERIFY_CONTENT }

internal fun decideCtyUpdate(
    installedModified: Long,
    installedLength: Long,
    remoteModified: Long,
    remoteLength: Long,
): CtyUpdateDecision {
    if (installedModified > 0L && remoteModified > 0L) {
        if (remoteModified != installedModified) {
            return if (remoteModified > installedModified) CtyUpdateDecision.AVAILABLE else CtyUpdateDecision.CURRENT
        }
        return if (installedLength > 0L && remoteLength > 0L && installedLength != remoteLength) {
            CtyUpdateDecision.AVAILABLE
        } else CtyUpdateDecision.CURRENT
    }
    return if (installedLength > 0L && remoteLength > 0L && installedLength != remoteLength) {
        CtyUpdateDecision.AVAILABLE
    } else CtyUpdateDecision.VERIFY_CONTENT
}

private data class CtyPrefix(val value: String, val exact: Boolean, val entity: AndroidCtyRecord)
private data class RemoteCtyFile(val modified: Long, val length: Long)
private data class DownloadedCtyFile(val data: ByteArray, val modified: Long, val length: Long)

class CtyController(context: Context) {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val datFile = File(context.filesDir, "cty.dat")
    private val csvFile = File(context.filesDir, "cty.csv")
    private val preferences = context.getSharedPreferences("cty_update_state", Context.MODE_PRIVATE)
    @Volatile private var prefixes = emptyList<CtyPrefix>()
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var installedSourceModified = preferences.getLong("installed_modified", 0L)
    private var latestSourceModified = preferences.getLong("latest_modified", 0L)
    private var lastCheckedAt = preferences.getLong("last_checked", 0L)
    private var knownUpdateAvailable = preferences.getBoolean("update_available", false)

    var status by mutableStateOf("CTY.DAT not installed"); private set
    var updateState by mutableStateOf(CtyUpdateState.NOT_INSTALLED); private set
    var updateMessage by mutableStateOf("Automatic update check has not run yet."); private set
    var prefixCount by mutableStateOf(0); private set
    var dataRevision by mutableLongStateOf(0L); private set
    var installedVersion by mutableStateOf(versionLabel(installedSourceModified, "Unknown (legacy file)")); private set
    var latestVersion by mutableStateOf(versionLabel(latestSourceModified, "Not checked yet")); private set
    var lastChecked by mutableStateOf(checkedLabel(lastCheckedAt)); private set

    val isBusy get() = updateState == CtyUpdateState.CHECKING || updateState == CtyUpdateState.UPDATING
    val updateAvailable get() = updateState == CtyUpdateState.AVAILABLE

    init {
        prefixCount = load()
        if (prefixCount == 0) {
            update()
        } else {
            dataRevision = 1L
            status = readyStatus()
            updateState = if (knownUpdateAvailable) CtyUpdateState.AVAILABLE else CtyUpdateState.CURRENT
            updateMessage = if (updateState == CtyUpdateState.AVAILABLE) {
                "A newer CTY.DAT was found during the last automatic check."
            } else "Installed entity and zone data remains active while the automatic check runs."
            checkForUpdates()
        }
    }

    @Synchronized fun checkForUpdates(): Job {
        updateJob?.takeIf(Job::isActive)?.let { return it }
        checkJob?.takeIf(Job::isActive)?.let { return it }
        val job = scope.launch {
            publishCheck(CtyUpdateState.CHECKING, "Checking country-files.com for a newer CTY.DAT…")
            try {
                val remote = inspect(DAT_URL)
                val decision = decideCtyUpdate(installedSourceModified, datFile.length(), remote.modified, remote.length)
                val available = when (decision) {
                    CtyUpdateDecision.AVAILABLE -> true
                    CtyUpdateDecision.CURRENT -> false
                    CtyUpdateDecision.VERIFY_CONTENT -> !download(DAT_URL).data.contentEquals(datFile.readBytes())
                }
                val now = System.currentTimeMillis()
                latestSourceModified = remote.modified
                lastCheckedAt = now
                knownUpdateAvailable = available
                if (!available && installedSourceModified == 0L && remote.modified > 0L) {
                    installedSourceModified = remote.modified
                }
                preferences.edit()
                    .putLong("installed_modified", installedSourceModified)
                    .putLong("latest_modified", latestSourceModified)
                    .putLong("last_checked", lastCheckedAt)
                    .putBoolean("update_available", knownUpdateAvailable)
                    .apply()
                publishCheck(
                    if (available) CtyUpdateState.AVAILABLE else CtyUpdateState.CURRENT,
                    if (available) "New CTY.DAT available · tap Install update when ready."
                    else "CTY.DAT is up to date. Automatic checks run whenever ShackCQ starts.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastCheckedAt = System.currentTimeMillis()
                preferences.edit().putLong("last_checked", lastCheckedAt).apply()
                publishCheck(
                    if (knownUpdateAvailable) CtyUpdateState.AVAILABLE else CtyUpdateState.FAILED,
                    if (knownUpdateAvailable) "A newer CTY.DAT is still available from the last successful check."
                    else "Automatic update check unavailable · installed CTY.DAT remains active.",
                )
            }
        }
        checkJob = job
        job.invokeOnCompletion { synchronized(this) { if (checkJob === job) checkJob = null } }
        return job
    }

    @Synchronized fun update(): Job {
        updateJob?.takeIf(Job::isActive)?.let { return it }
        checkJob?.cancel()
        val job = scope.launch {
            publishCheck(CtyUpdateState.UPDATING, "Downloading current CTY.DAT entity and zone data…")
            try {
                val dat = download(DAT_URL)
                val csv = download(CSV_URL)
                require(dat.data.size > 50_000 && csv.data.size > 70_000)
                require(dat.data.toString(Charsets.UTF_8).contains(';') && csv.data.toString(Charsets.UTF_8).contains(','))
                commit(datFile, dat.data)
                commit(csvFile, csv.data)
                val count = load()
                val now = System.currentTimeMillis()
                installedSourceModified = dat.modified
                latestSourceModified = dat.modified
                lastCheckedAt = now
                knownUpdateAvailable = false
                preferences.edit()
                    .putLong("installed_modified", installedSourceModified)
                    .putLong("latest_modified", latestSourceModified)
                    .putLong("last_checked", lastCheckedAt)
                    .putBoolean("update_available", false)
                    .apply()
                withContext(Dispatchers.Main) {
                    prefixCount = count
                    dataRevision++
                    status = readyStatus()
                    updateState = CtyUpdateState.CURRENT
                    updateMessage = "Current CTY.DAT installed successfully. Automatic checks remain enabled."
                    refreshLabels()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishCheck(
                    if (prefixes.isEmpty()) CtyUpdateState.NOT_INSTALLED
                    else if (knownUpdateAvailable) CtyUpdateState.AVAILABLE else CtyUpdateState.FAILED,
                    if (prefixes.isEmpty()) "CTY.DAT download failed · check the connection and try again."
                    else "CTY.DAT update failed · the previous installed file remains active.",
                )
            }
        }
        updateJob = job
        job.invokeOnCompletion { synchronized(this) { if (updateJob === job) updateJob = null } }
        return job
    }

    fun lookup(callsign: String): AndroidCtyRecord? {
        val call = callsign.trim().uppercase()
        if (call.isBlank()) return null
        return prefixes.firstOrNull { if (it.exact) call == it.value else call.startsWith(it.value) }?.entity
    }

    fun country(callsign: String): String = lookup(callsign)?.country.orEmpty()

    suspend fun nativeCtyText(): String? = withContext(Dispatchers.IO) {
        if (!datFile.isFile || datFile.length() !in 1..MAX_CTY_BYTES.toLong()) return@withContext null
        runCatching { datFile.readText() }.getOrNull()
            ?.takeIf { it.contains(':') && it.contains(';') }
    }

    fun close() = scope.cancel()

    private fun load(): Int {
        prefixes = when {
            csvFile.exists() -> parseCsv(csvFile.readText())
            datFile.exists() -> parseDat(datFile.readText())
            else -> emptyList()
        }.sortedWith(compareByDescending<CtyPrefix> { it.exact }.thenByDescending { it.value.length })
        return prefixes.size
    }

    private fun parseCsv(text: String): List<CtyPrefix> = text.lineSequence().flatMap { line ->
        val fields = line.split(',', limit = 10)
        if (fields.size != 10) return@flatMap emptySequence()
        val continent = fields[3].trim().uppercase()
        val entity = AndroidCtyRecord(fields[1].trim(), fields[2].trim(), continent,
            continentName(continent), fields[4].trim(), fields[5].trim(),
            fields[6].trim().toDoubleOrNull() ?: 0.0, -(fields[7].trim().toDoubleOrNull() ?: 0.0))
        val primary = fields[0].trim()
        sequenceOf(primary).plus(fields[9].removeSuffix(";").trim().split(Regex("\\s+")).asSequence())
            .mapNotNull { prefix(it, entity) }
    }.toList()

    private fun parseDat(text: String): List<CtyPrefix> = text.split(';').flatMap { record ->
        val fields = record.split(':', limit = 9)
        if (fields.size != 9) emptyList() else {
            val continent = fields[3].trim().uppercase()
            val entity = AndroidCtyRecord(fields[0].trim(), "", continent, continentName(continent),
                fields[1].trim(), fields[2].trim(), fields[4].trim().toDoubleOrNull() ?: 0.0,
                -(fields[5].trim().toDoubleOrNull() ?: 0.0))
            (listOf(fields[7]) + fields[8].split(',')).mapNotNull { prefix(it, entity) }
        }
    }

    private fun prefix(raw: String, entity: AndroidCtyRecord): CtyPrefix? {
        val trimmed = raw.trim().uppercase(); val exact = trimmed.startsWith('=')
        val value = trimmed.removePrefix("=").substringBeforeAny("(", "[", "<", "{", "~")
            .removePrefix("*").trim()
        return value.takeIf(String::isNotBlank)?.let { CtyPrefix(it, exact, entity) }
    }

    private fun inspect(value: String): RemoteCtyFile {
        val connection = URL(value).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            require(connection.responseCode in 200..299)
            RemoteCtyFile(connection.getHeaderFieldDate("Last-Modified", 0L), connection.contentLengthLong)
        } finally { connection.disconnect() }
    }

    private fun download(value: String): DownloadedCtyFile {
        val connection = URL(value).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            require(connection.responseCode in 200..299)
            require(connection.contentLengthLong <= MAX_CTY_BYTES || connection.contentLengthLong < 0L)
            val data = connection.inputStream.use { it.readBoundedBytes(MAX_CTY_BYTES + 1) }
            require(data.size <= MAX_CTY_BYTES)
            DownloadedCtyFile(data, connection.getHeaderFieldDate("Last-Modified", 0L), connection.contentLengthLong)
        } finally { connection.disconnect() }
    }

    private fun commit(destination: File, data: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        val previous = File(destination.parentFile, destination.name + ".previous")
        temporary.writeBytes(data); previous.delete()
        if (destination.exists()) require(destination.renameTo(previous))
        if (!temporary.renameTo(destination)) { previous.renameTo(destination); error("${destination.name} commit failed") }
        previous.delete()
    }

    private suspend fun publishCheck(state: CtyUpdateState, message: String) = withContext(Dispatchers.Main) {
        updateState = state
        updateMessage = message
        if (prefixes.isNotEmpty()) status = readyStatus()
        refreshLabels()
    }

    private fun refreshLabels() {
        installedVersion = versionLabel(installedSourceModified, "Unknown (legacy file)")
        latestVersion = versionLabel(latestSourceModified, "Not checked yet")
        lastChecked = checkedLabel(lastCheckedAt)
    }

    private fun readyStatus() = "CTY.DAT ready · ${prefixes.size} prefixes with DXCC/zones"
    private fun String.substringBeforeAny(vararg markers: String): String = markers.fold(this) { value, marker -> value.substringBefore(marker) }
    private fun continentName(code: String) = mapOf("AF" to "Africa", "AN" to "Antarctica", "AS" to "Asia",
        "EU" to "Europe", "NA" to "North America", "OC" to "Oceania", "SA" to "South America")[code].orEmpty()

    private companion object {
        const val DAT_URL = "https://www.country-files.com/cty/cty.dat"
        const val CSV_URL = "https://www.country-files.com/cty/cty.csv"
        const val MAX_CTY_BYTES = 2 * 1024 * 1024
        val VERSION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US).withZone(ZoneOffset.UTC)
        val CHECK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.US)
        fun versionLabel(value: Long, fallback: String) = if (value > 0L) VERSION_FORMAT.format(Instant.ofEpochMilli(value)) else fallback
        fun checkedLabel(value: Long) = if (value > 0L) CHECK_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value)) else "Not checked yet"
    }
}
