package app.shackcq.mobile

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class SmartLinkConfig(
    val clientId: String,
    val authDomain: String,
    val redirectUri: String,
    val server: String,
) {
    val complete: Boolean
        get() = clientId.isNotBlank() && authDomain.isNotBlank() && server.isNotBlank() &&
            runCatching { URI(redirectUri) }.getOrNull().let { it?.scheme == "https" && !it.host.isNullOrBlank() }

    companion object {
        fun issued() = SmartLinkConfig(
            BuildConfig.FLEX_SMARTLINK_CLIENT_ID,
            BuildConfig.FLEX_SMARTLINK_AUTH_DOMAIN.ifBlank { "frtest.auth0.com" },
            BuildConfig.FLEX_SMARTLINK_REDIRECT_URI.ifBlank { "https://frtest.auth0.com/mobile" },
            BuildConfig.FLEX_SMARTLINK_SERVER.ifBlank { "smartlink.flexradio.com:443" },
        )
    }
}

data class SmartLinkAuthSession(val state: String, val authorizationUri: Uri)
data class SmartLinkTokens(val idToken: String, val refreshToken: String, val expiresAtEpochSeconds: Long)

object SmartLinkAuth {
    private val random = SecureRandom()
    private const val SCOPE = "openid offline_access email given_name family_name picture"
    private const val REFRESH_SCOPE = "openid email given_name family_name picture"

    private fun randomUrlToken(bytes: Int) = ByteArray(bytes).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun domain(config: SmartLinkConfig) = config.authDomain.removePrefix("https://").trimEnd('/')
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    fun authorizationUrl(config: SmartLinkConfig, state: String): String {
        require(config.complete && state.isNotBlank())
        return "https://${domain(config)}/authorize?" + listOf(
            "client_id" to config.clientId,
            "response_type" to "token",
            "redirect_uri" to config.redirectUri,
            "scope" to SCOPE,
            "state" to state,
            "device" to "ShackCQ",
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    fun begin(config: SmartLinkConfig): SmartLinkAuthSession? {
        if (!config.complete) return null
        val state = randomUrlToken(32)
        return SmartLinkAuthSession(state, Uri.parse(authorizationUrl(config, state)))
    }

    fun validateRedirect(config: SmartLinkConfig, session: SmartLinkAuthSession, redirect: Uri): SmartLinkTokens? =
        validateRedirectString(config, session.state, redirect.toString())

    fun validateRedirectString(config: SmartLinkConfig, expectedState: String, redirectValue: String): SmartLinkTokens? {
        val expected = runCatching { URI(config.redirectUri) }.getOrNull() ?: return null
        val redirect = runCatching { URI(redirectValue) }.getOrNull() ?: return null
        if (redirect.scheme != "https" || redirect.scheme != expected.scheme || redirect.host != expected.host ||
            redirect.port != expected.port || redirect.path != expected.path
        ) return null
        val values = redirect.rawFragment?.split('&')?.mapNotNull {
            it.split('=', limit = 2).takeIf { parts -> parts.size == 2 }?.let { parts ->
                URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to URLDecoder.decode(parts[1], Charsets.UTF_8.name())
            }
        }?.toMap().orEmpty()
        val state = values["state"] ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return null
        val idToken = values["id_token"]?.takeIf(String::isNotBlank) ?: return null
        val refreshToken = values["refresh_token"]?.takeIf(String::isNotBlank) ?: return null
        return SmartLinkTokens(idToken, refreshToken, jwtExpiry(idToken))
    }

    fun refresh(config: SmartLinkConfig, refreshToken: String): SmartLinkTokens? {
        if (!config.complete || refreshToken.isBlank()) return null
        val endpoint = URL("https://${domain(config)}/delegation")
        val body = listOf(
            "client_id" to config.clientId,
            "target" to config.clientId,
            "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "refresh_token" to refreshToken,
            "scope" to REFRESH_SCOPE,
        ).joinToString("&") { (key, value) -> "${Uri.encode(key)}=${Uri.encode(value)}" }
        val connection = endpoint.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val idToken = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("id_token").takeIf(String::isNotBlank) ?: return null
            SmartLinkTokens(idToken, refreshToken, jwtExpiry(idToken))
        } finally {
            connection.disconnect()
        }
    }

    private fun jwtExpiry(jwt: String): Long = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
        JSONObject(json).optLong("exp", System.currentTimeMillis() / 1_000 + 60)
    }.getOrDefault(System.currentTimeMillis() / 1_000 + 60)
}

class SmartLinkRefreshStore(context: Context) {
    private val preferences = context.getSharedPreferences("shackcq-flex-secure", Context.MODE_PRIVATE)
    private val alias = "shackcq.flex.smartlink.refresh"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun save(value: String) {
        if (value.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? = runCatching {
        val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString("token", null), Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(doFinal(encrypted), StandardCharsets.UTF_8)
        }
    }.getOrNull()

    fun clear() { preferences.edit().clear().apply() }
}

enum class BrokerStage { NEW, REGISTERED, CONNECTION_REQUESTED, READY }

class SmartLinkBrokerProtocol {
    var stage = BrokerStage.NEW
        private set

    fun registration(idToken: String): String? = if (stage == BrokerStage.NEW && idToken.isNotBlank() && '\n' !in idToken && '\r' !in idToken) {
        stage = BrokerStage.REGISTERED
        "application register name=ShackCQ platform=Android token=$idToken"
    } else null

    fun connect(serial: String): String? = if (stage == BrokerStage.REGISTERED && serial.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
        stage = BrokerStage.CONNECTION_REQUESTED
        "application connect serial=$serial hole_punch_port=0"
    } else null

    fun ready() { if (stage == BrokerStage.CONNECTION_REQUESTED) stage = BrokerStage.READY }
}

fun wanValidationFirst(handle: String): String? = handle.takeIf {
    it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && it != "0" && !it.equals("0x0", ignoreCase = true)
}?.let { "wan validate handle=$it\n" }

data class SmartLinkRadio(
    val serial: String,
    val model: String,
    val nickname: String,
    val callsign: String,
    val status: String,
    val publicIp: String,
    val publicTlsPort: Int,
    val publicUpnpTlsPort: Int,
) {
    val tlsPort: Int get() = publicTlsPort.takeIf { it in 1..65535 }
        ?: publicUpnpTlsPort.takeIf { it in 1..65535 }
        ?: -1
}

data class WanEndpoint(val host: String, val port: Int, val handle: String, val serial: String)

private fun brokerFields(value: String): Map<String, String> = value.split(Regex("\\s+"))
    .mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim('"') } }
    .toMap()

fun parseSmartLinkRadios(line: String): List<SmartLinkRadio> {
    val trimmed = line.trim()
    if (trimmed != "radio list" && !trimmed.startsWith("radio list ")) return emptyList()
    val payload = trimmed.removePrefix("radio list").trim()
    if (payload.isBlank()) return emptyList()
    return payload.split('|').mapNotNull { value ->
        val fields = brokerFields(value)
        val serial = fields["serial"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SmartLinkRadio(
            serial,
            fields["model"].orEmpty(),
            (fields["nickname"] ?: fields["radio_name"] ?: fields["name"]).orEmpty().replace('_', ' '),
            (fields["callsign"] ?: fields["station"]).orEmpty(),
            fields["status"].orEmpty(),
            fields["public_ip"].orEmpty(),
            (fields["public_tls_port"] ?: fields["tls_port"])?.toIntOrNull() ?: -1,
            (fields["public_upnp_tls_port"] ?: fields["upnp_tls_port"])?.toIntOrNull() ?: -1,
        )
    }
}

private fun radioListShape(line: String): String {
    val payload = line.trim().removePrefix("radio list").trim()
    if (payload.isBlank()) return "0 records; no fields"
    val records = payload.split('|').filter(String::isNotBlank)
    val keys = records.flatMap { brokerFields(it).keys }.distinct().sorted()
    return "${records.size} record(s); fields=${keys.joinToString(",").ifBlank { "none" }}"
}

fun parseWanEndpoint(line: String, radio: SmartLinkRadio): WanEndpoint? {
    if (!line.startsWith("radio connect_ready ")) return null
    val fields = brokerFields(line.removePrefix("radio connect_ready "))
    if (fields["serial"] != radio.serial || radio.publicIp.isBlank() || radio.tlsPort < 1) return null
    val handle = fields["handle"]?.takeIf(String::isNotBlank) ?: return null
    return wanValidationFirst(handle)?.let { WanEndpoint(radio.publicIp, radio.tlsPort, handle, radio.serial) }
}

class SmartLinkBrokerClient(private val config: SmartLinkConfig) : AutoCloseable {
    private var socket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val protocol = SmartLinkBrokerProtocol()
    private val running = AtomicBoolean(false)
    private var keepalive: Thread? = null

    fun connectAndList(idToken: String): List<SmartLinkRadio> {
        check(config.complete)
        val uri = URI(if (config.server.contains("://")) config.server else "tls://${config.server}")
        val host = uri.host ?: error("SmartLink server host is invalid")
        val port = if (uri.port > 0) uri.port else 443
        val connected = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
        connected.soTimeout = 2_000
        connected.startHandshake()
        socket = connected
        reader = BufferedReader(InputStreamReader(connected.inputStream, StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(connected.outputStream, StandardCharsets.UTF_8))
        send(protocol.registration(idToken) ?: error("SmartLink registration must be first"))
        startKeepalive()
        val deadline = System.nanoTime() + 30_000_000_000L
        var emptyLists = 0
        var lastEmptyShape = "no list received"
        while (System.nanoTime() < deadline) {
            val line = try {
                reader?.readLine() ?: error("SmartLink server closed before sending a radio list")
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (line.startsWith("application registration_invalid")) error("SmartLink registration token was rejected")
            if (line.trim() == "radio list" || line.trim().startsWith("radio list ")) {
                val radios = parseSmartLinkRadios(line).distinctBy { it.serial }
                if (radios.isEmpty()) {
                    emptyLists++
                    lastEmptyShape = radioListShape(line)
                    continue
                }
                return radios
            }
        }
        error("SmartLink broker reported no radios during the 30-second discovery window ($emptyLists empty update(s); $lastEmptyShape)")
    }

    fun request(radio: SmartLinkRadio): WanEndpoint? {
        if (radio.tlsPort < 1) error("This radio requires SmartLink NAT hole-punching, which is not available in this build")
        send(protocol.connect(radio.serial) ?: return null)
        while (true) {
            val line = reader?.readLine() ?: return null
            parseWanEndpoint(line, radio)?.let { protocol.ready(); return it }
        }
    }

    @Synchronized
    private fun send(value: String) {
        writer?.apply { write(value); write("\n"); flush() } ?: error("SmartLink broker is not connected")
    }

    private fun startKeepalive() {
        running.set(true)
        keepalive = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(10_000)
                    if (running.get()) send("ping from client")
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }, "shackcq-smartlink-ping").apply { isDaemon = true; start() }
    }

    override fun close() {
        running.set(false)
        keepalive?.interrupt()
        keepalive = null
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}

private class ScopedRadioTrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not accepted")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Direct radio certificate missing")
        leaf.checkValidity()
        if (leaf.subjectX500Principal != leaf.issuerX500Principal) {
            throw CertificateException("Direct radio certificate is not self-signed")
        }
        runCatching { leaf.verify(leaf.publicKey) }.getOrElse {
            throw CertificateException("Direct radio self-signature is invalid", it)
        }
    }
}

class SmartLinkCertificateChanged(
    val endpoint: WanEndpoint,
    val expectedFingerprint: String,
    val observedFingerprint: String,
) : CertificateException("SmartLink radio certificate changed")

class SmartLinkTrustStore(context: Context) {
    private val values = context.getSharedPreferences("flex-smartlink-certificates", Context.MODE_PRIVATE)
    private fun key(endpoint: WanEndpoint): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(endpoint.serial.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun load(endpoint: WanEndpoint): String? = values.getString(key(endpoint), null)
    fun save(endpoint: WanEndpoint, fingerprint: String) {
        values.edit().putString(key(endpoint), fingerprint).apply()
    }
}

private fun certificateFingerprint(certificate: X509Certificate): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it) }

fun connectValidatedWan(endpoint: WanEndpoint, trustStore: SmartLinkTrustStore): Socket {
    val factory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(ScopedRadioTrustManager()), SecureRandom())
    }.socketFactory
    val socket = factory.createSocket(endpoint.host, endpoint.port) as SSLSocket
    socket.soTimeout = 750
    socket.startHandshake()
    val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: run {
        socket.close()
        throw CertificateException("Direct radio certificate missing after TLS handshake")
    }
    val observed = certificateFingerprint(leaf)
    val expected = trustStore.load(endpoint)
    if (expected == null) {
        socket.close()
        throw SmartLinkCertificateChanged(endpoint, "", observed)
    } else if (expected != observed) {
        socket.close()
        throw SmartLinkCertificateChanged(endpoint, expected, observed)
    }
    val validation = wanValidationFirst(endpoint.handle) ?: run {
        socket.close()
        error("Invalid WAN validation handle")
    }
    socket.outputStream.write(validation.toByteArray(StandardCharsets.US_ASCII))
    socket.outputStream.flush()
    return socket
}
