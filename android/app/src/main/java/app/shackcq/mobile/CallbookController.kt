package app.shackcq.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

data class AndroidCallbookRecord(
    val callsign: String, val name: String, val qth: String, val country: String, val grid: String,
    val dxcc: String, val continent: String, val region: String, val cqZone: String, val ituZone: String,
    val state: String, val email: String, val latitude: String, val longitude: String,
    val address: String = "", val postalCode: String = "", val born: String = "", val imageUrl: String = "",
    val qslManager: String = "", val qslText: String = "", val lotw: String = "", val eqsl: String = "",
    val source: String = "",
)

internal fun callbookRecordFromFields(fields: Map<String, String>, requestedCall: String, source: String): AndroidCallbookRecord {
    val continent = (fields["continent"] ?: fields["cont"] ?: "").uppercase()
    return AndroidCallbookRecord(
        callsign = fields["call"] ?: fields["callsign"] ?: requestedCall,
        name = listOfNotNull(fields["fname"], fields["name"], fields["nick"]).filter(String::isNotBlank).joinToString(" ").trim(),
        qth = fields["addr2"] ?: fields["qth"] ?: "", country = fields["country"] ?: fields["land"] ?: "",
        grid = fields["grid"] ?: "", dxcc = fields["dxcc"] ?: fields["adif"] ?: "", continent = continent,
        region = continentNameForCallbook(continent), cqZone = fields["cqzone"] ?: fields["cq"] ?: "",
        ituZone = fields["ituzone"] ?: fields["itu"] ?: "", state = fields["state"] ?: "", email = fields["email"] ?: "",
        latitude = fields["lat"] ?: "", longitude = fields["lon"] ?: "", address = fields["addr1"] ?: fields["adr_name"] ?: "",
        postalCode = fields["zip"] ?: fields["adr_zip"] ?: "", born = fields["born"] ?: "",
        imageUrl = fields["image"] ?: fields["picture"] ?: "", qslManager = fields["qslmgr"] ?: fields["qsl_manager"] ?: "",
        qslText = fields["mqsl"] ?: fields["qsl"] ?: "", lotw = fields["lotw"] ?: "", eqsl = fields["eqsl"] ?: "",
        source = if (source == "QRZ") "QRZ.COM" else source,
    )
}

internal fun mergeCallbookRecords(primary: AndroidCallbookRecord?, fallback: AndroidCallbookRecord): AndroidCallbookRecord {
    if (primary == null) return fallback
    fun choose(first: String, second: String) = first.ifBlank { second }
    return AndroidCallbookRecord(
        choose(primary.callsign, fallback.callsign), choose(primary.name, fallback.name), choose(primary.qth, fallback.qth),
        choose(primary.country, fallback.country), choose(primary.grid, fallback.grid), choose(primary.dxcc, fallback.dxcc),
        choose(primary.continent, fallback.continent), choose(primary.region, fallback.region), choose(primary.cqZone, fallback.cqZone),
        choose(primary.ituZone, fallback.ituZone), choose(primary.state, fallback.state), choose(primary.email, fallback.email),
        choose(primary.latitude, fallback.latitude), choose(primary.longitude, fallback.longitude), choose(primary.address, fallback.address),
        choose(primary.postalCode, fallback.postalCode), choose(primary.born, fallback.born), choose(primary.imageUrl, fallback.imageUrl),
        choose(primary.qslManager, fallback.qslManager), choose(primary.qslText, fallback.qslText), choose(primary.lotw, fallback.lotw),
        choose(primary.eqsl, fallback.eqsl), primary.source.ifBlank { fallback.source },
    )
}

private fun continentNameForCallbook(code: String) = mapOf("AF" to "Africa", "AN" to "Antarctica", "AS" to "Asia",
    "EU" to "Europe", "NA" to "North America", "OC" to "Oceania", "SA" to "South America")[code].orEmpty()

class CallbookController(context: Context, private val operatorCallsign: () -> String = { "" }) {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("callbook", Context.MODE_PRIVATE)
    private val legacyProvider = prefs.getString("provider", "QRZ") ?: "QRZ"
    private val legacyUsername = prefs.getString("username", "") ?: ""
    private val legacyPassword = decrypt(prefs.getString("password", "") ?: "")
    var qrzEnabled by mutableStateOf(prefs.getBoolean("qrz_enabled", legacyProvider == "QRZ")); private set
    var qrzUsername by mutableStateOf(prefs.getString("qrz_username", if (legacyProvider == "QRZ") legacyUsername.ifBlank { ShackCQDefaults.QRZ_USERNAME } else ShackCQDefaults.QRZ_USERNAME) ?: ShackCQDefaults.QRZ_USERNAME); private set
    var qrzPassword by mutableStateOf(decrypt(prefs.getString("qrz_password", "") ?: "").ifBlank { if (legacyProvider == "QRZ") legacyPassword else "" }); private set
    var hamQthEnabled by mutableStateOf(prefs.getBoolean("hamqth_enabled", legacyProvider == "HamQTH" && legacyUsername.isNotBlank())); private set
    var hamQthUsername by mutableStateOf(prefs.getString("hamqth_username", if (legacyProvider == "HamQTH") legacyUsername else "") ?: ""); private set
    var hamQthPassword by mutableStateOf(decrypt(prefs.getString("hamqth_password", "") ?: "").ifBlank { if (legacyProvider == "HamQTH") legacyPassword else "" }); private set
    var status by mutableStateOf("Callbook not tested"); private set
    val provider get() = listOfNotNull("QRZ".takeIf { qrzEnabled }, "HamQTH".takeIf { hamQthEnabled }).joinToString(" + ").ifBlank { "CTY.DAT" }
    val configured get() = (qrzEnabled && qrzUsername.isNotBlank() && qrzPassword.isNotBlank()) ||
        (hamQthEnabled && hamQthUsername.isNotBlank() && hamQthPassword.isNotBlank())
    private var qrzSession = ""
    private var hamQthSession = ""
    private val lookupCache = LruCache<String, AndroidCallbookRecord>(128)

    init {
        val defaults = prefs.edit()
        if (!prefs.contains("qrz_enabled")) defaults.putBoolean("qrz_enabled", qrzEnabled)
        if (!prefs.contains("qrz_username")) defaults.putString("qrz_username", qrzUsername)
        defaults.apply()
    }

    fun configureQrz(enabled: Boolean, username: String, password: String) {
        val retainedPassword = password.ifBlank { qrzPassword }
        qrzEnabled = enabled; qrzUsername = username.trim(); qrzPassword = retainedPassword; qrzSession = ""; lookupCache.evictAll()
        prefs.edit().putBoolean("qrz_enabled", enabled).putString("qrz_username", qrzUsername)
            .putString("qrz_password", encrypt(retainedPassword)).apply()
    }

    fun configureHamQth(enabled: Boolean, username: String, password: String) {
        val retainedPassword = password.ifBlank { hamQthPassword }
        hamQthEnabled = enabled; hamQthUsername = username.trim(); hamQthPassword = retainedPassword; hamQthSession = ""; lookupCache.evictAll()
        prefs.edit().putBoolean("hamqth_enabled", enabled).putString("hamqth_username", hamQthUsername)
            .putString("hamqth_password", encrypt(retainedPassword)).apply()
    }

    fun test() = scope.launch {
        val results = enabledSources().map { source -> source to runCatching { login(source) } }
        results.forEach { (source, result) -> result.onSuccess { if (source == "QRZ") qrzSession = it else hamQthSession = it } }
        val passed = results.filter { it.second.isSuccess }.map { it.first }
        val failed = results.filter { it.second.isFailure }.map { (source, result) ->
            "$source: ${safeFailure(result.exceptionOrNull())}"
        }
        publish(buildString {
            if (passed.isNotEmpty()) append(passed.joinToString(" + ")).append(" connection passed")
            if (failed.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(failed.joinToString(" · ")) }
            if (isEmpty()) append("Enable QRZ or HamQTH first")
        })
    }

    fun lookup(callsign: String, completion: (AndroidCallbookRecord?) -> Unit) = scope.launch {
        val call = callsign.trim().uppercase()
        lookupCache.get(call)?.let { cached ->
            return@launch withContext(Dispatchers.Main) { status = "Cached ${cached.source} result"; completion(cached) }
        }
        val sources = enabledSources()
        if (sources.isEmpty()) return@launch withContext(Dispatchers.Main) { status = "Callbook disabled · CTY.DAT fallback"; completion(null) }
        var record: AndroidCallbookRecord? = null
        val failures = mutableListOf<String>()
        for (source in sources) {
            val result = runCatching { lookupSource(source, call) }
            record = result.getOrNull()
            if (record != null) break
            failures += "$source: ${safeFailure(result.exceptionOrNull())}"
        }
        withContext(Dispatchers.Main) {
            status = if (record == null) failures.joinToString(" · ").ifBlank { "Callbook lookup failed" } + " · CTY.DAT fallback"
                else "Live ${record.source} result"
            record?.let { lookupCache.put(call, it) }
            completion(record)
        }
    }

    /** Read-only bounded cache access for map geometry; never initiates a provider request. */
    internal fun cachedRecord(callsign: String): AndroidCallbookRecord? =
        lookupCache.get(callsign.trim().uppercase())

    fun close() = scope.cancel()

    private fun enabledSources() = listOfNotNull("QRZ".takeIf { qrzEnabled }, "HamQTH".takeIf { hamQthEnabled })

    private fun login(source: String): String {
        val username = if (source == "HamQTH") hamQthUsername else qrzXmlUsername()
        val password = if (source == "HamQTH") hamQthPassword else qrzPassword
        require(username.isNotBlank() && password.isNotBlank()) { "$source username and password required" }
        val fields = if (source == "HamQTH") xml("https://www.hamqth.com/xml.php?u=${encode(username)}&p=${encode(password)}")
            else xml("https://xmldata.qrz.com/xml/current/?username=${encode(username)}&password=${encode(password)}&agent=ShackCQ-0.1")
        fields["error"]?.let { error(it) }
        return fields[if (source == "HamQTH") "session_id" else "key"]?.takeIf(String::isNotBlank)
            ?: error("Callbook login returned no session")
    }

    private fun lookupSource(source: String, call: String): AndroidCallbookRecord {
        var session = if (source == "HamQTH") hamQthSession else qrzSession
        if (session.isBlank()) session = login(source).also { if (source == "HamQTH") hamQthSession = it else qrzSession = it }
        val fields = if (source == "HamQTH")
            xml("https://www.hamqth.com/xml.php?id=${encode(session)}&callsign=${encode(call)}&prg=ShackCQ")
        else xml("https://xmldata.qrz.com/xml/current/?s=${encode(session)}&callsign=${encode(call)}")
        fields["error"]?.let { error(it) }
        return callbookRecordFromFields(fields, call, source)
    }

    private fun xml(value: String): Map<String, String> {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray()))
        val fields = linkedMapOf<String, String>()
        fun collect(node: Node) {
            val children = (0 until node.childNodes.length).map(node.childNodes::item)
            val elements = children.filter { it.nodeType == Node.ELEMENT_NODE }
            if (elements.isEmpty() && node.nodeType == Node.ELEMENT_NODE) {
                fields[(node.localName ?: node.nodeName).lowercase()] = node.textContent.trim()
            } else {
                elements.forEach(::collect)
            }
        }
        collect(document.documentElement)
        return fields
    }

    private suspend fun publish(value: String) = withContext(Dispatchers.Main) { status = value }
    private fun qrzXmlUsername(): String = if ('@' !in qrzUsername) qrzUsername else
        operatorCallsign().trim().uppercase(java.util.Locale.US).ifBlank { qrzUsername }
    private fun safeFailure(error: Throwable?): String {
        val message = error?.message.orEmpty().replace(Regex("[\r\n]+"), " ")
        return when {
            message.contains("username/password incorrect", ignoreCase = true) ->
                message.substringAfter("Username/password incorrect", "").let { "Username/password incorrect$it" }.take(120)
            message.contains("blocked until", ignoreCase = true) -> "Provider temporarily blocked repeated login attempts"
            message.contains("subscription", ignoreCase = true) -> "Callbook subscription does not permit this lookup"
            message.contains("callsign not found", ignoreCase = true) -> "Callsign not found"
            error is java.net.SocketTimeoutException -> "Request timed out"
            error is java.net.UnknownHostException -> "Network unavailable"
            error is javax.net.ssl.SSLException -> "Secure connection failed"
            else -> "Request failed (${error?.javaClass?.simpleName ?: "unknown error"})"
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault("")
    private fun decrypt(value: String): String = if (value.isEmpty()) "" else runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }.getOrDefault("")
    companion object { private const val keyAlias = "app.shackcq.mobile.callbook" }
}
