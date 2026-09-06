package app.shackcq.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SyncProvider(val label: String, val shortLabel: String) {
    QRZ("QRZ Logbook", "QRZ"),
    CLUB_LOG("Club Log", "CL"),
    EQSL("eQSL.cc", "eQSL"),
}

enum class DeliveryState {
    QUEUED, BATCH_QUEUED, BATCH_RETRY_WAIT, BATCH_PAUSED_AUTHORITY, BATCH_AUTH_BLOCKED,
    SENDING, ACCEPTED, ACCEPTED_DUPLICATE, ACCEPTED_MODIFIED,
    SUBMITTED_BATCH, RETRY_WAIT, REJECTED, AUTH_BLOCKED, PROFILE_REQUIRED,
    CONFIG_REQUIRED, PAUSED_AUTHORITY, LOCAL_CHANGED,
}

data class DeliveryRecord(
    val qsoId: String,
    val provider: SyncProvider,
    val state: DeliveryState,
    val createdAt: Long,
    val updatedAt: Long,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val payloadHash: String = "",
    val remoteId: String = "",
    val providerMessage: String = "",
    val httpStatus: Int? = null,
)

data class QrzConfig(val callsign: String = "", val apiKey: String = "")
data class QrzLogbookStatus(
    val callsign: String = "",
    val name: String = "",
    val owner: String = "",
    val startDate: String = "",
    val endDate: String = "",
)
data class ClubLogConfig(val email: String = "", val password: String = "", val callsign: String = "", val apiKey: String = "")
data class EqslConfig(val username: String = "", val password: String = "", val qthNickname: String = "")

data class SyncHttpRequest(
    val url: String,
    val contentType: String,
    val body: ByteArray,
    val userAgent: String = "ShackCQ/0.1.0",
)

data class SyncHttpResponse(val status: Int, val body: String)

fun interface SyncHttpTransport {
    fun post(request: SyncHttpRequest): SyncHttpResponse
}

class DefaultSyncHttpTransport : SyncHttpTransport {
    override fun post(request: SyncHttpRequest): SyncHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", request.contentType)
        connection.setRequestProperty("User-Agent", request.userAgent.take(128))
        connection.outputStream.use { it.write(request.body) }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return SyncHttpResponse(status, body)
    }
}

data class ProviderOutcome(
    val state: DeliveryState,
    val message: String,
    val remoteId: String = "",
    val transient: Boolean = false,
    val authenticationBlocked: Boolean = false,
)

internal val retryDelaysSeconds = listOf(60L, 300L, 900L, 3_600L, 21_600L)

internal fun shouldAutoEnqueue(origin: QsoOrigin, mode: LogMode, enabled: Boolean, resumed: Boolean,
    configured: Boolean, authenticationBlocked: Boolean) =
    origin == QsoOrigin.OPERATOR && mode == LogMode.LOCAL && enabled && resumed && configured && !authenticationBlocked

internal fun qrzStationMatches(qso: Qso, configuredCallsign: String) =
    qso.stationCallsign.trim().equals(configuredCallsign.trim(), true)

internal fun requiresEqslProfile(qso: Qso, homeGrid: String, qthNickname: String): Boolean {
    val portable = qso.activationProgram.isNotBlank() || qso.myPotaRefs.isNotEmpty() || qso.myPotaRef.isNotBlank() ||
        qso.mySotaRef.isNotBlank() || qso.myWwffRef.isNotBlank()
    val differentGrid = qso.myGrid.isNotBlank() && homeGrid.isNotBlank() && !qso.myGrid.equals(homeGrid, true)
    return (portable || differentGrid || qso.stationProfileId.isNotBlank()) && qthNickname.isBlank()
}

internal fun applyAcceptedFlag(qso: Qso, provider: SyncProvider) = when (provider) {
    SyncProvider.QRZ -> qso.copy(qrzSent = "Y")
    SyncProvider.CLUB_LOG -> qso.copy(clublogSent = "Y")
    SyncProvider.EQSL -> qso.copy(eqslSent = "Y")
}

internal fun retryAt(attemptCount: Int, now: Long): Long? =
    retryDelaysSeconds.getOrNull(attemptCount - 1)?.let { now + it }

internal fun redactSecrets(value: String, secrets: List<String>): String {
    var safe = value
    secrets.filter(String::isNotBlank).forEach { safe = safe.replace(it, "••••") }
    return sanitizeProviderMessage(safe)
}

internal fun parseQrzResponse(status: Int, body: String): ProviderOutcome {
    val values = body.split('&').mapNotNull { part ->
        val pieces = part.split('=', limit = 2)
        pieces.firstOrNull()?.takeIf(String::isNotBlank)?.uppercase(Locale.US)?.let { key ->
            key to URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
    }.toMap()
    val result = values["RESULT"].orEmpty().uppercase(Locale.US)
    val reason = values["REASON"].orEmpty().ifBlank { values["DATA"].orEmpty() }.ifBlank { "QRZ returned $result" }
    val remoteId = values["LOGID"].orEmpty().ifBlank { values["LOGIDS"].orEmpty() }
    return when {
        status == 429 || status >= 500 -> ProviderOutcome(DeliveryState.RETRY_WAIT, "QRZ HTTP $status", transient = true)
        status !in 200..299 -> ProviderOutcome(DeliveryState.REJECTED, "QRZ HTTP $status")
        result == "OK" -> ProviderOutcome(DeliveryState.ACCEPTED, reason.ifBlank { "Accepted by QRZ" }, remoteId)
        result == "REPLACE" -> ProviderOutcome(DeliveryState.ACCEPTED_MODIFIED, reason.ifBlank { "Accepted with provider replacement" }, remoteId)
        result == "AUTH" || reason.contains("key", true) && reason.contains("invalid", true) ->
            ProviderOutcome(DeliveryState.AUTH_BLOCKED, reason, authenticationBlocked = true)
        result == "FAIL" && (reason.contains("duplicate", true) || reason.contains("already", true)) ->
            ProviderOutcome(DeliveryState.ACCEPTED_DUPLICATE, reason, remoteId)
        result == "FAIL" -> ProviderOutcome(DeliveryState.REJECTED, reason)
        else -> ProviderOutcome(DeliveryState.RETRY_WAIT, reason.ifBlank { "Unrecognized QRZ response" }, transient = true)
    }
}

internal fun parseQrzStatus(body: String): QrzLogbookStatus {
    val outer = body.split('&').mapNotNull { part ->
        val pieces = part.split('=', limit = 2)
        pieces.firstOrNull()?.uppercase(Locale.US)?.let { it to URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name()) }
    }.toMap()
    val data = outer["DATA"].orEmpty()
    val inner = data.split('&', ';').mapNotNull { part ->
        val pieces = part.split('=', limit = 2)
        val key = pieces.firstOrNull()?.trim()?.uppercase(Locale.US)?.replace(Regex("[^A-Z0-9]"), "")
        key?.takeIf(String::isNotBlank)?.let { it to pieces.getOrElse(1) { "" }.trim() }
    }.toMap()
    val fields = outer.filterKeys { it !in setOf("RESULT", "REASON", "DATA") }
        .mapKeys { it.key.replace(Regex("[^A-Z0-9]"), "") } + inner
    fun value(vararg names: String) = names.firstNotNullOfOrNull { fields[it] } ?: ""
    return QrzLogbookStatus(
        callsign = value("CALLSIGN", "BOOKCALLSIGN", "LOGBOOKCALLSIGN", "CALL"),
        name = value("BOOKNAME", "LOGBOOKNAME", "NAME"),
        owner = value("BOOKOWNER", "LOGBOOKOWNER", "OWNER"),
        startDate = value("STARTDATE", "BOOKSTARTDATE", "LOGBOOKSTARTDATE"),
        endDate = value("ENDDATE", "BOOKENDDATE", "LOGBOOKENDDATE"),
    )
}

internal fun qrzDateAllowed(qso: Qso, status: QrzLogbookStatus): Boolean {
    val date = Instant.ofEpochSecond(qso.createdAt).atZone(ZoneOffset.UTC).toLocalDate()
    val start = runCatching { LocalDate.parse(status.startDate) }.getOrNull()
    val end = runCatching { LocalDate.parse(status.endDate) }.getOrNull()
    return (start == null || date >= start) && (end == null || date <= end)
}

internal fun parseClubLogResponse(status: Int, body: String): ProviderOutcome {
    val message = sanitizeProviderMessage(body).ifBlank { "Club Log HTTP $status" }
    return when {
        status == 403 -> ProviderOutcome(DeliveryState.AUTH_BLOCKED, message, authenticationBlocked = true)
        status == 400 -> ProviderOutcome(DeliveryState.REJECTED, message)
        status >= 500 -> ProviderOutcome(DeliveryState.RETRY_WAIT, message, transient = true)
        status == 200 && body.contains("QSO Duplicate", true) -> ProviderOutcome(DeliveryState.ACCEPTED_DUPLICATE, message)
        status == 200 && body.contains("QSO Modified", true) -> ProviderOutcome(DeliveryState.ACCEPTED_MODIFIED, message)
        status == 200 && body.contains("QSO OK", true) -> ProviderOutcome(DeliveryState.ACCEPTED, message)
        else -> ProviderOutcome(DeliveryState.REJECTED, message)
    }
}

internal fun parseEqslResponse(status: Int, body: String, expected: Int = 1): ProviderOutcome {
    val message = sanitizeProviderMessage(body)
    if (status != 200) return ProviderOutcome(DeliveryState.RETRY_WAIT, message.ifBlank { "eQSL HTTP $status" }, transient = status >= 500)
    if (!body.contains("Reply form eQSL.cc ADIF Real-time Interface", true))
        return ProviderOutcome(DeliveryState.REJECTED, "Unrecognized eQSL response")
    if (body.contains("No match on eQSL_User/eQSL_Pswd", true))
        return ProviderOutcome(DeliveryState.AUTH_BLOCKED, message, authenticationBlocked = true)
    if (body.contains("Bad record: Duplicate", true))
        return ProviderOutcome(DeliveryState.ACCEPTED_DUPLICATE, message)
    val added = Regex("Result:\\s*(\\d+)\\s+out of\\s+(\\d+)\\s+records added", RegexOption.IGNORE_CASE).find(body)
    if (added != null && added.groupValues[1].toIntOrNull() == expected && added.groupValues[2].toIntOrNull() == expected)
        return ProviderOutcome(DeliveryState.ACCEPTED, message.ifBlank { "Accepted by eQSL.cc" })
    if (body.contains("Error:", true) || body.contains("Warning:", true))
        return ProviderOutcome(DeliveryState.REJECTED, message)
    return ProviderOutcome(DeliveryState.RETRY_WAIT, message.ifBlank { "Unrecognized eQSL response" }, transient = true)
}

internal fun sanitizeProviderMessage(value: String): String = value
    .replace(Regex("(?is)<script.*?</script>"), " ")
    .replace(Regex("(?s)<[^>]+>"), " ")
    .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(1_000)

internal fun formBody(fields: List<Pair<String, String>>): ByteArray = fields.joinToString("&") { (key, value) ->
    URLEncoder.encode(key, Charsets.UTF_8.name()) + "=" + URLEncoder.encode(value, Charsets.UTF_8.name())
}.toByteArray()

internal fun multipartBody(fields: List<Pair<String, String>>, filename: String, adif: String, boundary: String,
    fileField: String = "file"): ByteArray {
    val line = "\r\n"
    val output = StringBuilder()
    fields.forEach { (name, value) ->
        output.append("--").append(boundary).append(line)
            .append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(line).append(line)
            .append(value).append(line)
    }
    output.append("--").append(boundary).append(line)
        .append("Content-Disposition: form-data; name=\"").append(fileField).append("\"; filename=\"").append(filename).append("\"").append(line)
        .append("Content-Type: application/x-adif").append(line).append(line)
        .append(adif).append(line).append("--").append(boundary).append("--").append(line)
    return output.toString().toByteArray()
}

internal fun addAdifField(adif: String, name: String, value: String): String {
    if (value.isBlank()) return adif
    val field = "<$name:${value.toByteArray().size}>$value"
    return adif.replace("<EOR>", "$field<EOR>", ignoreCase = true)
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

class SyncHubController(
    context: Context,
    private val database: QsoDatabase,
    private val authority: () -> LogMode,
    private val homeGrid: () -> String,
    private val transport: SyncHttpTransport = DefaultSyncHttpTransport(),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sync-hub", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var foregroundActive = true
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (foregroundActive && authority() == LogMode.LOCAL) syncNow()
        }
    }

    var qrzConfig by mutableStateOf(QrzConfig(
        callsign = prefs.getString("qrz_callsign", "").orEmpty(),
        apiKey = decrypt(prefs.getString("qrz_key", "").orEmpty())))
        private set
    var qrzStatus by mutableStateOf(QrzLogbookStatus(
        callsign = prefs.getString("qrz_status_callsign", "").orEmpty(),
        name = prefs.getString("qrz_status_name", "").orEmpty(),
        owner = prefs.getString("qrz_status_owner", "").orEmpty(),
        startDate = prefs.getString("qrz_status_start", "").orEmpty(),
        endDate = prefs.getString("qrz_status_end", "").orEmpty()))
        private set
    var clubLogConfig by mutableStateOf(ClubLogConfig(
        email = prefs.getString("club_email", "").orEmpty(),
        password = decrypt(prefs.getString("club_password", "").orEmpty()),
        callsign = prefs.getString("club_callsign", "").orEmpty(),
        apiKey = decrypt(prefs.getString("club_api", "").orEmpty())))
        private set
    var eqslConfig by mutableStateOf(EqslConfig(
        username = prefs.getString("eqsl_user", "").orEmpty(),
        password = decrypt(prefs.getString("eqsl_password", "").orEmpty()),
        qthNickname = prefs.getString("eqsl_qth", "").orEmpty()))
        private set
    var records by mutableStateOf(database.deliveries()); private set
    var status by mutableStateOf("Direct services are disabled by default"); private set
    var busy by mutableStateOf(false); private set

    init {
        database.recoverInterruptedDeliveries()
        database.operatorSaveHandler = ::operatorQsoSaved
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (foregroundActive && authority() == LogMode.LOCAL) processQueues()
            }
        }
    }

    fun isEnabled(provider: SyncProvider) = prefs.getBoolean("enabled_${provider.name}", false)
    fun isResumed(provider: SyncProvider) = prefs.getBoolean("resumed_${provider.name}", false)
    fun isAuthBlocked(provider: SyncProvider) = prefs.getBoolean("auth_${provider.name}", false)
    fun isConfigured(provider: SyncProvider) = when (provider) {
        SyncProvider.QRZ -> qrzConfig.callsign.isNotBlank() && qrzConfig.apiKey.isNotBlank()
        SyncProvider.CLUB_LOG -> clubLogConfig.email.isNotBlank() && clubLogConfig.password.isNotBlank() &&
            clubLogConfig.callsign.isNotBlank() && clubLogConfig.apiKey.isNotBlank()
        SyncProvider.EQSL -> eqslConfig.username.isNotBlank() && eqslConfig.password.isNotBlank()
    }

    fun saveQrz(callsign: String, apiKey: String) {
        qrzConfig = QrzConfig(callsign.trim().uppercase(Locale.US), apiKey.trim())
        qrzStatus = QrzLogbookStatus()
        prefs.edit().putString("qrz_callsign", qrzConfig.callsign).putString("qrz_key", encrypt(qrzConfig.apiKey))
            .remove("qrz_status_callsign").remove("qrz_status_name").remove("qrz_status_owner")
            .remove("qrz_status_start").remove("qrz_status_end")
            .putBoolean("auth_QRZ", false).putBoolean("resumed_QRZ", false).apply()
        status = "QRZ configuration saved · test or resume to send"
        pause(SyncProvider.QRZ)
    }

    fun saveClubLog(email: String, password: String, callsign: String, apiKey: String) {
        clubLogConfig = ClubLogConfig(email.trim(), password, callsign.trim().uppercase(Locale.US), apiKey.trim())
        prefs.edit().putString("club_email", clubLogConfig.email).putString("club_password", encrypt(clubLogConfig.password))
            .putString("club_callsign", clubLogConfig.callsign).putString("club_api", encrypt(clubLogConfig.apiKey))
            .putBoolean("auth_CLUB_LOG", false).putBoolean("resumed_CLUB_LOG", false).apply()
        status = if (clubLogConfig.apiKey.isBlank()) "Club Log · APP API KEY REQUIRED" else "Club Log saved · resume to send"
        pause(SyncProvider.CLUB_LOG)
    }

    fun saveEqsl(username: String, password: String, qthNickname: String) {
        eqslConfig = EqslConfig(username.trim().uppercase(Locale.US), password, qthNickname.trim())
        prefs.edit().putString("eqsl_user", eqslConfig.username).putString("eqsl_password", encrypt(eqslConfig.password))
            .putString("eqsl_qth", eqslConfig.qthNickname).putBoolean("auth_EQSL", false)
            .putBoolean("resumed_EQSL", false).apply()
        status = "eQSL configuration saved · resume to send"
        pause(SyncProvider.EQSL)
    }

    fun clearProvider(provider: SyncProvider) {
        val editor = prefs.edit().putBoolean("enabled_${provider.name}", false)
            .putBoolean("resumed_${provider.name}", false).putBoolean("auth_${provider.name}", false)
        when (provider) {
            SyncProvider.QRZ -> {
                qrzConfig = QrzConfig()
                qrzStatus = QrzLogbookStatus()
                editor.remove("qrz_callsign").remove("qrz_key").remove("qrz_status_callsign")
                    .remove("qrz_status_name").remove("qrz_status_owner").remove("qrz_status_start").remove("qrz_status_end")
            }
            SyncProvider.CLUB_LOG -> {
                clubLogConfig = ClubLogConfig()
                editor.remove("club_email").remove("club_password").remove("club_callsign").remove("club_api")
            }
            SyncProvider.EQSL -> {
                eqslConfig = EqslConfig()
                editor.remove("eqsl_user").remove("eqsl_password").remove("eqsl_qth")
            }
        }
        editor.apply()
        pause(provider)
        status = "${provider.label} credentials cleared"
    }

    fun setEnabled(provider: SyncProvider, enabled: Boolean) {
        if (enabled && authority() != LogMode.LOCAL) {
            status = "Direct services cannot be enabled under Wavelog authority"
            return
        }
        if (enabled && !isConfigured(provider)) {
            status = if (provider == SyncProvider.CLUB_LOG && clubLogConfig.apiKey.isBlank()) "APP API KEY REQUIRED" else "Configure ${provider.label} first"
            return
        }
        if (enabled && isAuthBlocked(provider)) {
            status = "Update ${provider.label} credentials before resuming"
            return
        }
        prefs.edit().putBoolean("enabled_${provider.name}", enabled).putBoolean("resumed_${provider.name}", enabled).apply()
        if (enabled) {
            status = "${provider.label} enabled for future operator QSOs"
            syncNow()
        } else {
            pause(provider)
            status = "${provider.label} automatic delivery disabled"
        }
        refresh()
    }

    fun setAuthority(mode: LogMode) {
        if (mode == LogMode.WAVELOG) {
            SyncProvider.entries.forEach { provider ->
                prefs.edit().putBoolean("resumed_${provider.name}", false).apply()
                database.setProviderQueueState(provider, setOf(DeliveryState.QUEUED, DeliveryState.RETRY_WAIT), DeliveryState.PAUSED_AUTHORITY)
                database.setProviderQueueState(provider, setOf(DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT), DeliveryState.BATCH_PAUSED_AUTHORITY)
            }
            status = "Wavelog authority · direct destinations paused to prevent duplicates"
        } else {
            status = "Local authority · direct queues remain paused until explicitly resumed"
        }
        refresh()
    }

    fun resume(provider: SyncProvider) {
        if (authority() != LogMode.LOCAL || !isEnabled(provider) || !isConfigured(provider)) {
            status = "Local authority, enabled service, and complete configuration are required"
            return
        }
        if (isAuthBlocked(provider)) {
            status = "Authentication remains blocked · save changed credentials or pass the QRZ STATUS test"
            return
        }
        prefs.edit().putBoolean("resumed_${provider.name}", true).apply()
        database.setProviderQueueState(provider, setOf(DeliveryState.PAUSED_AUTHORITY, DeliveryState.AUTH_BLOCKED), DeliveryState.QUEUED)
        database.setProviderQueueState(provider, setOf(DeliveryState.BATCH_PAUSED_AUTHORITY, DeliveryState.BATCH_AUTH_BLOCKED), DeliveryState.BATCH_QUEUED)
        status = "${provider.label} resumed"
        syncNow()
    }

    fun testQrz() = scope.launch {
        if (qrzConfig.apiKey.isBlank()) {
            publish("QRZ API key is required")
            return@launch
        }
        val response = runCatching {
            transport.post(SyncHttpRequest(QRZ_URL, FORM, formBody(listOf("KEY" to qrzConfig.apiKey, "ACTION" to "STATUS"))))
        }.getOrElse {
            publish("QRZ test failed: ${it.message}")
            return@launch
        }
        val outcome = parseQrzResponse(response.status, response.body)
        if (outcome.state == DeliveryState.ACCEPTED) {
            qrzStatus = parseQrzStatus(response.body)
            prefs.edit().putBoolean("auth_QRZ", false).putBoolean("resumed_QRZ", isEnabled(SyncProvider.QRZ) && authority() == LogMode.LOCAL).apply()
            prefs.edit().putString("qrz_status_callsign", qrzStatus.callsign).putString("qrz_status_name", qrzStatus.name)
                .putString("qrz_status_owner", qrzStatus.owner).putString("qrz_status_start", qrzStatus.startDate)
                .putString("qrz_status_end", qrzStatus.endDate).apply()
            publish("QRZ STATUS accepted · ${listOf(qrzStatus.callsign, qrzStatus.name, qrzStatus.startDate, qrzStatus.endDate).filter(String::isNotBlank).joinToString(" · ").ifBlank { outcome.message }}")
            if (isResumed(SyncProvider.QRZ)) syncNow()
        } else publish("QRZ STATUS failed · ${outcome.message}")
    }

    fun queueExisting(qsoIds: Collection<String>, providers: Set<SyncProvider>) {
        val now = System.currentTimeMillis() / 1_000
        qsoIds.forEach { qsoId ->
            providers.forEach { provider ->
                val state = if (provider == SyncProvider.QRZ) DeliveryState.QUEUED else DeliveryState.BATCH_QUEUED
                database.enqueueDelivery(qsoId, provider, if (authority() == LogMode.LOCAL && isResumed(provider)) state else DeliveryState.PAUSED_AUTHORITY, now)
            }
        }
        status = "${qsoIds.size} existing QSOs selected · ${providers.joinToString { it.shortLabel }}"
        refresh()
        syncNow()
    }

    fun retry(record: DeliveryRecord) {
        if (record.state in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED, DeliveryState.SUBMITTED_BATCH))
            return
        val batch = record.state in setOf(DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT,
            DeliveryState.BATCH_PAUSED_AUTHORITY, DeliveryState.BATCH_AUTH_BLOCKED)
        database.updateDelivery(record.copy(state = if (batch) DeliveryState.BATCH_QUEUED else DeliveryState.QUEUED,
            updatedAt = System.currentTimeMillis() / 1_000, nextAttemptAt = null, providerMessage = "Manual retry queued"))
        refresh()
        syncNow()
    }

    fun requeueCurrent(record: DeliveryRecord) {
        database.updateDelivery(record.copy(state = DeliveryState.QUEUED, updatedAt = System.currentTimeMillis() / 1_000,
            attemptCount = 0, nextAttemptAt = null, providerMessage = "Current local version queued"))
        refresh()
        syncNow()
    }

    fun removeUnsent(record: DeliveryRecord) {
        database.removeUnsentDelivery(record.qsoId, record.provider)
        refresh()
    }

    fun syncNow() = scope.launch { processQueues() }
    fun refreshNow() = refresh()
    fun setForeground(value: Boolean) { foregroundActive = value }

    private fun operatorQsoSaved(qso: Qso) {
        if (authority() != LogMode.LOCAL) return
        SyncProvider.entries.filter { shouldAutoEnqueue(QsoOrigin.OPERATOR, authority(), isEnabled(it), isResumed(it), isConfigured(it), isAuthBlocked(it)) }
            .forEach { database.enqueueDelivery(qso.id, it) }
        refresh()
        syncNow()
    }

    private suspend fun processQueues() = mutex.withLock {
        if (!foregroundActive || authority() != LogMode.LOCAL) return@withLock
        setBusy(true)
        try {
            SyncProvider.entries.forEach { provider ->
                if (!isEnabled(provider) || !isResumed(provider) || !isConfigured(provider) || isAuthBlocked(provider)) return@forEach
                if (provider != SyncProvider.QRZ) processBatch(provider)
                while (foregroundActive && authority() == LogMode.LOCAL) {
                    val record = database.nextDelivery(provider, System.currentTimeMillis() / 1_000) ?: break
                    if (record.state == DeliveryState.BATCH_QUEUED) break
                    if (!processOne(record)) break
                    if (isAuthBlocked(provider)) break
                }
            }
        } finally {
            refresh()
            setBusy(false)
        }
    }

    private fun processBatch(provider: SyncProvider) {
        val current = now()
        val batch = database.deliveries(provider).filter {
            it.state == DeliveryState.BATCH_QUEUED ||
                (it.state == DeliveryState.BATCH_RETRY_WAIT && it.nextAttemptAt != null && it.nextAttemptAt <= current)
        }
        if (batch.isEmpty()) return
        val qsos = batch.mapNotNull { database.qso(it.qsoId) }
        if (qsos.isEmpty()) return
        if (provider == SyncProvider.EQSL && qsos.any(::eqslProfileRequired)) {
            batch.forEach { record ->
                val qso = database.qso(record.qsoId) ?: return@forEach
                if (eqslProfileRequired(qso)) database.updateDelivery(record.copy(
                    state = DeliveryState.PROFILE_REQUIRED, updatedAt = now(), providerMessage = "Portable or different-QTH QSO requires an eQSL QTH nickname"))
            }
            return
        }
        val adif = qsos.joinToString("") { providerAdif(it, provider) }
        val response = runCatching {
            when (provider) {
                SyncProvider.CLUB_LOG -> postClubBatch(adif)
                SyncProvider.EQSL -> postEqsl(adif, qsos.size)
                SyncProvider.QRZ -> error("QRZ historical delivery remains sequential")
            }
        }.getOrElse { error ->
            batch.forEach { scheduleBatchRetry(it.copy(attemptCount = it.attemptCount + 1,
                updatedAt = now(), lastAttemptAt = now(), providerMessage = redact(error.message ?: "Network unavailable"))) }
            return
        }
        val safeResponse = response.copy(message = redact(response.message))
        if (safeResponse.authenticationBlocked) blockProvider(provider, safeResponse.message)
        batch.forEach { record ->
            val accepted = safeResponse.state in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED)
            val updated = record.copy(
                state = if (accepted) DeliveryState.SUBMITTED_BATCH
                    else if (safeResponse.authenticationBlocked) DeliveryState.BATCH_AUTH_BLOCKED else safeResponse.state,
                updatedAt = now(), attemptCount = record.attemptCount + 1, lastAttemptAt = now(),
                payloadHash = sha256(adif), providerMessage = safeResponse.message)
            if (safeResponse.transient) scheduleBatchRetry(updated) else database.updateDelivery(updated)
        }
    }

    private fun processOne(record: DeliveryRecord): Boolean {
        val qso = database.qso(record.qsoId) ?: return true
        if (record.provider == SyncProvider.QRZ && !qrzStationMatches(qso, qrzConfig.callsign)) {
            database.updateDelivery(record.copy(state = DeliveryState.CONFIG_REQUIRED, updatedAt = now(),
                providerMessage = "STATION_CALLSIGN does not match the configured QRZ logbook callsign"))
            return true
        }
        if (record.provider == SyncProvider.QRZ && qrzStatus.callsign.isNotBlank() &&
            !qso.stationCallsign.equals(qrzStatus.callsign, true)) {
            database.updateDelivery(record.copy(state = DeliveryState.CONFIG_REQUIRED, updatedAt = now(),
                providerMessage = "STATION_CALLSIGN does not match the callsign returned by QRZ STATUS"))
            return true
        }
        if (record.provider == SyncProvider.QRZ && !qrzDateAllowed(qso, qrzStatus)) {
            database.updateDelivery(record.copy(state = DeliveryState.CONFIG_REQUIRED, updatedAt = now(),
                providerMessage = "QSO date is outside the active range returned by QRZ STATUS"))
            return true
        }
        if (record.provider == SyncProvider.EQSL && eqslProfileRequired(qso)) {
            database.updateDelivery(record.copy(state = DeliveryState.PROFILE_REQUIRED, updatedAt = now(),
                providerMessage = "Portable or different-QTH QSO requires an eQSL QTH nickname"))
            return true
        }
        val adif = providerAdif(qso, record.provider)
        database.updateDelivery(record.copy(state = DeliveryState.SENDING, updatedAt = now(), lastAttemptAt = now(),
            attemptCount = record.attemptCount + 1, payloadHash = sha256(adif)))
        val response = runCatching {
            when (record.provider) {
                SyncProvider.QRZ -> postQrz(adif)
                SyncProvider.CLUB_LOG -> postClubRealtime(adif)
                SyncProvider.EQSL -> postEqsl(adif, 1)
            }
        }.getOrElse {
            transientFailure(record.copy(attemptCount = record.attemptCount + 1, payloadHash = sha256(adif)), it.message ?: "Network unavailable")
            return false
        }
        val safeResponse = response.copy(message = redact(response.message))
        if (safeResponse.authenticationBlocked) blockProvider(record.provider, safeResponse.message)
        val finished = record.copy(state = safeResponse.state, updatedAt = now(), attemptCount = record.attemptCount + 1,
            lastAttemptAt = now(), nextAttemptAt = null, payloadHash = sha256(adif), remoteId = response.remoteId,
            providerMessage = safeResponse.message)
        database.updateDelivery(finished)
        if (safeResponse.state in setOf(DeliveryState.ACCEPTED, DeliveryState.ACCEPTED_DUPLICATE, DeliveryState.ACCEPTED_MODIFIED))
            database.markProviderAccepted(record.qsoId, record.provider)
        if (safeResponse.transient) scheduleRetry(finished)
        return !safeResponse.transient && !safeResponse.authenticationBlocked
    }

    private fun postQrz(adif: String): ProviderOutcome {
        val response = transport.post(SyncHttpRequest(QRZ_URL, FORM,
            formBody(listOf("KEY" to qrzConfig.apiKey, "ACTION" to "INSERT", "ADIF" to adif))))
        return parseQrzResponse(response.status, response.body)
    }

    private fun postClubRealtime(adif: String): ProviderOutcome {
        val response = transport.post(SyncHttpRequest(CLUB_REALTIME_URL, FORM, formBody(listOf(
            "email" to clubLogConfig.email, "password" to clubLogConfig.password, "callsign" to clubLogConfig.callsign,
            "adif" to adif, "api" to clubLogConfig.apiKey))))
        return parseClubLogResponse(response.status, response.body)
    }

    private fun postClubBatch(adif: String): ProviderOutcome {
        val boundary = "ShackCQ-${System.currentTimeMillis()}"
        val response = transport.post(SyncHttpRequest(CLUB_BATCH_URL, "multipart/form-data; boundary=$boundary",
            multipartBody(listOf("email" to clubLogConfig.email, "password" to clubLogConfig.password,
                "callsign" to clubLogConfig.callsign, "api" to clubLogConfig.apiKey), "shackcq-catch-up.adi", adif, boundary)))
        return if (response.status == 200) ProviderOutcome(DeliveryState.ACCEPTED,
            sanitizeProviderMessage(response.body).ifBlank { "Club Log batch submitted; provider processing may take time" })
        else parseClubLogResponse(response.status, response.body)
    }

    private fun postEqsl(adif: String, count: Int): ProviderOutcome {
        val boundary = "ShackCQ-${System.currentTimeMillis()}"
        val response = transport.post(SyncHttpRequest(EQSL_URL, "multipart/form-data; boundary=$boundary",
            multipartBody(listOf("EQSL_USER" to eqslConfig.username, "EQSL_PSWD" to eqslConfig.password),
                "shackcq.adi", eqslHeader() + adif, boundary, "Filename")))
        return parseEqslResponse(response.status, response.body, count)
    }

    private fun providerAdif(qso: Qso, provider: SyncProvider): String {
        var adif = database.toADIF(qso)
        if (provider == SyncProvider.EQSL) adif = addAdifField(adif, "APP_EQSL_QTH_NICKNAME", eqslConfig.qthNickname)
        return adif
    }

    private fun eqslHeader() = "<ADIF_VER:5>3.1.4<PROGRAMID:8>ShackCQ<PROGRAMVERSION:5>0.1.0<EOH>"

    private fun eqslProfileRequired(qso: Qso): Boolean {
        return requiresEqslProfile(qso, homeGrid(), eqslConfig.qthNickname)
    }

    private fun transientFailure(record: DeliveryRecord, message: String) {
        scheduleRetry(record.copy(state = DeliveryState.RETRY_WAIT, updatedAt = now(), lastAttemptAt = now(),
            providerMessage = sanitizeProviderMessage(message)))
    }

    private fun scheduleRetry(record: DeliveryRecord) {
        val next = retryAt(record.attemptCount, now())
        database.updateDelivery(record.copy(state = DeliveryState.RETRY_WAIT, nextAttemptAt = next,
            providerMessage = if (next == null) "${record.providerMessage} · automatic retry limit reached" else record.providerMessage))
    }

    private fun scheduleBatchRetry(record: DeliveryRecord) {
        val next = retryAt(record.attemptCount, now())
        database.updateDelivery(record.copy(state = DeliveryState.BATCH_RETRY_WAIT, nextAttemptAt = next,
            providerMessage = if (next == null) "${record.providerMessage} · automatic retry limit reached" else record.providerMessage))
    }

    private fun blockProvider(provider: SyncProvider, message: String) {
        prefs.edit().putBoolean("auth_${provider.name}", true).putBoolean("resumed_${provider.name}", false).apply()
        database.setProviderQueueState(provider, setOf(DeliveryState.QUEUED, DeliveryState.RETRY_WAIT), DeliveryState.AUTH_BLOCKED)
        database.setProviderQueueState(provider, setOf(DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT), DeliveryState.BATCH_AUTH_BLOCKED)
        status = if (provider == SyncProvider.CLUB_LOG)
            "Club Log authentication blocked · traffic stopped to protect against an IP ban"
        else "${provider.label} authentication blocked · update credentials"
    }

    private fun pause(provider: SyncProvider) {
        database.setProviderQueueState(provider, setOf(DeliveryState.QUEUED, DeliveryState.RETRY_WAIT), DeliveryState.PAUSED_AUTHORITY)
        database.setProviderQueueState(provider, setOf(DeliveryState.BATCH_QUEUED, DeliveryState.BATCH_RETRY_WAIT), DeliveryState.BATCH_PAUSED_AUTHORITY)
        refresh()
    }

    private fun refresh() {
        records = database.deliveries()
    }

    private fun redact(value: String): String {
        return redactSecrets(value, listOf(qrzConfig.apiKey, clubLogConfig.password, clubLogConfig.apiKey, eqslConfig.password))
    }

    private suspend fun publish(value: String) = kotlinx.coroutines.withContext(Dispatchers.Main) { status = value }
    private suspend fun setBusy(value: Boolean) = kotlinx.coroutines.withContext(Dispatchers.Main) { busy = value }
    private fun now() = System.currentTimeMillis() / 1_000

    fun close() {
        database.operatorSaveHandler = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        scope.cancel()
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrDefault("")

    companion object {
        private const val KEY_ALIAS = "app.shackcq.mobile.sync-hub"
        private const val FORM = "application/x-www-form-urlencoded"
        private const val QRZ_URL = "https://logbook.qrz.com/api"
        private const val CLUB_REALTIME_URL = "https://clublog.org/realtime.php"
        private const val CLUB_BATCH_URL = "https://clublog.org/putlogs.php"
        private const val EQSL_URL = "https://www.eqsl.cc/qslcard/ImportADIF.cfm"
    }
}
