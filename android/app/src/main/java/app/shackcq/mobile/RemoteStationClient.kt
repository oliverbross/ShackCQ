// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class RemoteRole { OBSERVER, OPERATOR, ADMIN }
enum class RemoteConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, ERROR }
enum class RemoteLeaseState { NONE, REQUESTING, HELD, DENIED }

data class RemoteStationProfile(
    val stationId: String,
    val name: String,
    val host: String,
    val port: Int = 7443,
    val certificateSha256: String,
    val deviceId: String,
    val role: RemoteRole = RemoteRole.OBSERVER,
    val lastConnectedEpoch: Long? = null,
) {
    init {
        require(stationId.matches(Regex("[A-Za-z0-9._:-]{8,128}")))
        require(name.isNotBlank() && name.length <= 80)
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' })
        require(port in 1..65_535)
        require(certificateSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(deviceId.matches(Regex("[A-Za-z0-9._:-]{8,128}")))
    }

    fun radioProfile() = RadioConnectionProfile(
        id = RadioProfileId("remote.${stationId.lowercase(Locale.US)}"),
        name = "REMOTE · $name",
        backendKind = RadioBackendKind.REMOTE_STATION,
        modelId = RadioModelId("REMOTE:$stationId"),
        manufacturer = "ShackCQ",
        model = name,
        transport = RadioTransportType.REMOTE_STATION,
        stableSerialIdentityHash = certificateSha256,
        host = host,
        port = port,
        readOnly = role == RemoteRole.OBSERVER,
        secureWebSocket = true,
    )
}

data class RemoteRuntimeSnapshot(
    val state: RemoteConnectionState = RemoteConnectionState.DISCONNECTED,
    val stationId: String? = null,
    val stationName: String? = null,
    val generation: Long = 0,
    val sessionId: String? = null,
    val role: RemoteRole = RemoteRole.OBSERVER,
    val writerLease: RemoteLeaseState = RemoteLeaseState.NONE,
    val txLease: RemoteLeaseState = RemoteLeaseState.NONE,
    val rotatorLease: RemoteLeaseState = RemoteLeaseState.NONE,
    val rttMillis: Long? = null,
    val audioLatencyMillis: Long? = null,
    val spectrumSequence: Long = 0,
    val audioSequence: Long = 0,
    val droppedFrames: Long = 0,
    val certificatePinned: Boolean = false,
    val radioRoster: List<String> = emptyList(),
    val lastError: String? = null,
)

class RemoteRuntimeState {
    var snapshot by mutableStateOf(RemoteRuntimeSnapshot())
        internal set
    @Volatile var spectrumSink: ((bins: ByteArray, sequence: Long, generation: Long) -> Unit)? = null
    @Volatile var audioPcm16Sink: ((sampleRate: Int, channels: Int, pcm: ByteArray, sequence: Long, generation: Long) -> Unit)? = null
    @Volatile var rawIqSink: ((sampleRate: Int, iq: FloatArray, sequence: Long, generation: Long) -> Unit)? = null
}

private class RemoteOpusJitterDecoder(
    private val output: (sampleRate: Int, channels: Int, pcm: ByteArray, sequence: Long, generation: Long) -> Unit,
) : AutoCloseable {
    private data class Frame(val rate: Int, val channels: Int, val packet: ByteArray, val generation: Long)
    private val queued = TreeMap<Long, Frame>()
    private var expected: Long? = null
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var timestampUs = 0L

    fun offer(sequence: Long, rate: Int, channels: Int, packet: ByteArray, generation: Long) {
        if (packet.isEmpty() || queued.size >= 8) return
        queued[sequence] = Frame(rate, channels, packet, generation)
        if (expected == null) expected = sequence
        while (queued.size >= 2) {
            val next = expected ?: queued.firstKey()
            val frame = queued.remove(next)
            if (frame == null) {
                val first = queued.firstKey()
                if (first > next) {
                    val missing = queued.firstEntry().value
                    val silence = ByteArray((missing.rate / 50) * missing.channels * 2)
                    output(missing.rate, missing.channels, silence, next, missing.generation)
                    expected = next + 1
                    continue
                }
                expected = first
                continue
            }
            decode(next, frame)
            expected = next + 1
        }
    }

    private fun ensureCodec(rate: Int, channels: Int): MediaCodec? {
        if (codec != null && sampleRate == rate && channelCount == channels) return codec
        codec?.let { runCatching { it.stop() }; it.release() }; codec = null
        val value = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }.getOrNull() ?: return null
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, channels)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(channels.toByte()); putShort(0); putInt(rate); putShort(0); put(0); flip()
        }
        format.setByteBuffer("csd-0", head)
        return runCatching { value.configure(format, null, null, 0); value.start(); sampleRate = rate; channelCount = channels; value }
            .getOrElse { value.release(); null }.also { codec = it }
    }

    private fun decode(sequence: Long, frame: Frame) {
        val value = ensureCodec(frame.rate, frame.channels) ?: return
        val input = value.dequeueInputBuffer(10_000)
        if (input < 0) return
        value.getInputBuffer(input)?.apply { clear(); put(frame.packet) }
        value.queueInputBuffer(input, 0, frame.packet.size, timestampUs, 0)
        timestampUs += 20_000
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = value.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) break
            val pcm = ByteArray(info.size)
            value.getOutputBuffer(outputIndex)?.apply { position(info.offset); limit(info.offset + info.size); get(pcm) }
            value.releaseOutputBuffer(outputIndex, false)
            if (pcm.isNotEmpty()) output(frame.rate, frame.channels, pcm, sequence, frame.generation)
        }
    }

    override fun close() {
        queued.clear()
        codec?.let { runCatching { it.stop() }; it.release() }; codec = null
    }
}

class RemoteIdentity(private val alias: String = "shackcq.remote.device.p256") {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun keyPair(): KeyPair {
        val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKeyPair()
    }

    fun publicKeyPem(): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair().public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----\n"
    }

    fun sign(value: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair().private)
        signature.update(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}

class RemoteStationStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote-stations-v1", Context.MODE_PRIVATE)
    val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: "android-${UUID.randomUUID()}".also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    fun load(): List<RemoteStationProfile> = runCatching {
        val array = JSONArray(prefs.getString("profiles", "[]"))
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            RemoteStationProfile(row.getString("stationId"), row.getString("name"), row.getString("host"),
                row.optInt("port", 7443), row.getString("certificateSha256"), row.getString("deviceId"),
                RemoteRole.valueOf(row.optString("role", "OBSERVER")),
                row.optLong("lastConnectedEpoch").takeIf { row.has("lastConnectedEpoch") })
        }
    }.getOrDefault(emptyList())

    fun save(profile: RemoteStationProfile) {
        val profiles = (load().filterNot { it.stationId == profile.stationId } + profile).takeLast(8)
        val rows = JSONArray()
        profiles.forEach { row -> rows.put(JSONObject().put("stationId", row.stationId).put("name", row.name)
            .put("host", row.host).put("port", row.port).put("certificateSha256", row.certificateSha256)
            .put("deviceId", row.deviceId).put("role", row.role.name).apply {
                row.lastConnectedEpoch?.let { put("lastConnectedEpoch", it) }
            }) }
        prefs.edit().putString("profiles", rows.toString()).apply()
    }

    fun forget(stationId: String) {
        val rows = JSONArray()
        load().filterNot { it.stationId == stationId }.forEach { row -> rows.put(JSONObject()
            .put("stationId", row.stationId).put("name", row.name).put("host", row.host).put("port", row.port)
            .put("certificateSha256", row.certificateSha256).put("deviceId", row.deviceId).put("role", row.role.name)) }
        prefs.edit().putString("profiles", rows.toString()).apply()
    }
}

data class RemoteDiscoveryResult(val name: String, val host: String, val port: Int)

class RemoteStationDiscovery(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }
    private var listener: NsdManager.DiscoveryListener? = null
    fun start(onResult: (RemoteDiscoveryResult) -> Unit, onError: (String) -> Unit = {}) {
        close()
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) manager.registerServiceInfoCallback(serviceInfo,
                    callbackExecutor, object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = onError("Discovery resolve failed ($errorCode)")
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val address = info.hostAddresses.firstOrNull()?.hostAddress ?: return
                            onResult(RemoteDiscoveryResult(info.serviceName.take(80), address, info.port))
                            manager.unregisterServiceInfoCallback(this)
                        }
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    })
                else @Suppress("DEPRECATION") manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onError("Discovery resolve failed ($errorCode)")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val address = info.host?.hostAddress ?: return
                        onResult(RemoteDiscoveryResult(info.serviceName.take(80), address, info.port))
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { onError("Discovery start failed ($errorCode)"); close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onError("Discovery stop failed ($errorCode)")
        }
        manager.discoverServices("_shackcq._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
    override fun close() { listener?.let { runCatching { manager.stopServiceDiscovery(it) } }; listener = null }
}

private class FingerprintTrustManager(private val expected: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not accepted")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing station certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw CertificateException("Station certificate fingerprint mismatch")
        leaf.checkValidity()
    }
}

private fun pinnedClient(expectedFingerprint: String): OkHttpClient {
    val trust = FingerprintTrustManager(expectedFingerprint)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
        .hostnameVerifier { _, session ->
            runCatching {
                val cert = session.peerCertificates.first() as X509Certificate
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
                    .equals(expectedFingerprint, ignoreCase = true)
            }.getOrDefault(false)
        }.pingInterval(5, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
}

class RemoteStationBackendFactory(
    private val profileProvider: (RadioConnectionProfile) -> RemoteStationProfile?,
    private val runtime: RemoteRuntimeState,
    private val identity: RemoteIdentity = RemoteIdentity(),
) : RadioBackendFactory {
    @Volatile var active: RemoteStationBackend? = null
        private set
    override suspend fun create(profile: RadioConnectionProfile): ManagedRadioBackend {
        val remote = profileProvider(profile) ?: throw IllegalArgumentException("Unknown paired Remote Station profile")
        return RemoteStationBackend(profile, remote, pinnedClient(remote.certificateSha256), runtime, identity) { backend ->
            if (active === backend) active = null
        }.also { active = it }
    }

    suspend fun acquireWriter() = active?.acquireWriter() ?: false
    suspend fun acquireTransmit() = active?.acquireTransmit() ?: false
    suspend fun acquireRotator() = active?.acquireRotator() ?: false
}

class RemoteStationBackend(
    override val profile: RadioConnectionProfile,
    private val remote: RemoteStationProfile,
    private val client: OkHttpClient,
    private val runtime: RemoteRuntimeState,
    private val identity: RemoteIdentity,
    private val onClosed: (RemoteStationBackend) -> Unit,
) : ManagedRadioBackend {
    private val generation = AtomicLong(0)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val heartbeatSentAt = ConcurrentHashMap<String, Long>()
    private val decode = Executors.newSingleThreadExecutor { Thread(it, "ShackCQ-Remote-Media").apply { isDaemon = true } }
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "ShackCQ-Remote-Heartbeat").apply { isDaemon = true } }
    @Volatile private var heartbeat: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var currentRadio = RadioRuntimeSnapshot(profileId = profile.id,
        backendKind = RadioBackendKind.REMOTE_STATION, modelId = profile.modelId)
    @Volatile private var closed = false
    private val opus = RemoteOpusJitterDecoder { rate, channels, pcm, sequence, frameGeneration ->
        runtime.audioPcm16Sink?.invoke(rate, channels, pcm, sequence, frameGeneration)
        runtime.snapshot = runtime.snapshot.copy(audioSequence = sequence)
    }

    override val snapshot: RadioRuntimeSnapshot get() = currentRadio

    override suspend fun connect(): Boolean {
        if (closed || socket != null) return false
        ready = CompletableDeferred()
        runtime.snapshot = RemoteRuntimeSnapshot(state = RemoteConnectionState.CONNECTING,
            stationId = remote.stationId, stationName = remote.name)
        val request = runCatching { Request.Builder().url("wss://${remote.host}:${remote.port}")
            .header("Sec-WebSocket-Protocol", "shackcq.remote.v1").build() }.getOrElse {
            fail("Invalid Remote Station endpoint"); return false
        }
        socket = client.newWebSocket(request, Listener())
        return withTimeoutOrNull(10_000) { ready.await() } ?: run { fail("Remote Station authentication timeout"); false }
    }

    override suspend fun disconnect() {
        heartbeat?.cancel(false); heartbeat = null
        requestReceive()
        socket?.close(1000, "Operator disconnect")
        socket = null
        requests.values.forEach { it.complete(false) }; requests.clear()
        runtime.snapshot = runtime.snapshot.copy(state = RemoteConnectionState.DISCONNECTED,
            sessionId = null, writerLease = RemoteLeaseState.NONE, txLease = RemoteLeaseState.NONE,
            rotatorLease = RemoteLeaseState.NONE)
        currentRadio = currentRadio.copy(connected = false, generation = generation.incrementAndGet())
    }

    override suspend fun requestReceive(): Boolean = if (runtime.snapshot.sessionId == null) true
        else sendRequest("GLOBAL_STOP", JSONObject(), 2_000)

    override suspend fun execute(action: RadioPlatformAction): Boolean {
        if (action.actionClass == RadioActionClass.READ_ONLY) return true
        if (action.actionClass in setOf(RadioActionClass.TRANSMIT, RadioActionClass.TUNE, RadioActionClass.MEMORY_WRITE)) return false
        val payload = JSONObject().put("operation", when (action.name.lowercase(Locale.US)) {
            "frequency" -> "frequency"; "mode" -> "mode"; "rotator_stop" -> "rotator.stop"; else -> action.name.lowercase(Locale.US)
        })
        action.longValue?.let { payload.put("value", it.toString()) }
        action.textValue?.let { payload.put("value", it) }
        return sendRequest("MUTATE", payload, 3_000)
    }

    suspend fun acquireWriter(ttlMillis: Int = 5_000) = lease("WRITER", ttlMillis)
    suspend fun acquireTransmit(ttlMillis: Int = 5_000) = lease("TX", ttlMillis)
    suspend fun acquireRotator(ttlMillis: Int = 5_000) = lease("ROTATOR", ttlMillis)

    suspend fun configureMedia(preset: String = "BALANCED", capKbps: Int = 64,
                               pcmLanFallback: Boolean = false, rawIq: Boolean = false): Boolean =
        sendRequest("MEDIA_CONFIG", JSONObject().put("audioCodec", if (pcmLanFallback) "PCM16" else "OPUS")
            .put("audioPreset", preset.uppercase(Locale.US)).put("audioCapKbps", capKbps.coerceIn(12, 128))
            .put("rawIq", rawIq).put("lowDataMode", false), 3_000)

    private suspend fun lease(kind: String, ttlMillis: Int): Boolean {
        runtime.snapshot = when (kind) {
            "TX" -> runtime.snapshot.copy(txLease = RemoteLeaseState.REQUESTING)
            "ROTATOR" -> runtime.snapshot.copy(rotatorLease = RemoteLeaseState.REQUESTING)
            else -> runtime.snapshot.copy(writerLease = RemoteLeaseState.REQUESTING)
        }
        val ok = sendRequest("LEASE", JSONObject().put("kind", kind).put("ttlMs", ttlMillis.coerceIn(1_000, 30_000)), 3_000)
        runtime.snapshot = when (kind) {
            "TX" -> runtime.snapshot.copy(txLease = if (ok) RemoteLeaseState.HELD else RemoteLeaseState.DENIED)
            "ROTATOR" -> runtime.snapshot.copy(rotatorLease = if (ok) RemoteLeaseState.HELD else RemoteLeaseState.DENIED)
            else -> runtime.snapshot.copy(writerLease = if (ok) RemoteLeaseState.HELD else RemoteLeaseState.DENIED)
        }
        return ok
    }

    private suspend fun sendRequest(type: String, payload: JSONObject, timeout: Long): Boolean {
        val ws = socket ?: return type == "GLOBAL_STOP"
        val session = runtime.snapshot.sessionId ?: return false
        val requestId = UUID.randomUUID().toString()
        val pending = CompletableDeferred<Boolean>()
        requests[requestId] = pending
        val sent = ws.send(JSONObject().put("version", 1).put("type", type).put("stationId", remote.stationId)
            .put("sessionId", session).put("requestId", requestId).put("generation", runtime.snapshot.generation.toString())
            .put("timestampMs", System.currentTimeMillis().toString()).put("payload", payload).toString())
        if (!sent) { requests.remove(requestId); return false }
        return withTimeoutOrNull(timeout) { pending.await() } ?: false.also { requests.remove(requestId) }
    }

    override fun close() {
        closed = true
        heartbeat?.cancel(false); heartbeat = null
        socket?.cancel(); socket = null
        requests.values.forEach { it.complete(false) }; requests.clear()
        opus.close(); decode.shutdownNow(); heartbeatExecutor.shutdownNow(); client.dispatcher.executorService.shutdown()
        currentRadio = currentRadio.copy(connected = false)
        onClosed(this)
    }

    private inner class Listener : WebSocketListener() {
        private var authenticated = false
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runtime.snapshot = runtime.snapshot.copy(state = RemoteConnectionState.AUTHENTICATING, certificatePinned = true)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 65_536) { webSocket.close(1009, "Control frame too large"); fail("Remote control frame exceeds 64 KiB"); return }
            val message = runCatching { JSONObject(text) }.getOrElse { webSocket.close(1002, "Malformed JSON"); fail("Malformed Remote Station control frame"); return }
            when (message.optString("type")) {
                "HELLO" -> {
                    val stationId = message.optString("stationId")
                    val serverGeneration = message.optString("generation").toLongOrNull() ?: return fail("Remote HELLO lacks generation")
                    if (stationId != remote.stationId || !message.optString("certificateSha256").equals(remote.certificateSha256, true)) {
                        webSocket.close(1008, "Pinned station identity mismatch"); fail("Remote Station identity mismatch"); return
                    }
                    generation.set(serverGeneration)
                    val nonce = message.optString("authNonce")
                    if (!nonce.matches(Regex("[a-fA-F0-9]{48}"))) return fail("Remote HELLO lacks a valid one-time challenge")
                    val challenge = "$stationId|auth|$nonce|$serverGeneration"
                    val payload = JSONObject().put("deviceId", remote.deviceId).put("nonce", nonce)
                        .put("signature", identity.sign(challenge)).put("foreground", true)
                    webSocket.send(JSONObject().put("version", 1).put("type", "AUTH").put("requestId", UUID.randomUUID().toString())
                        .put("generation", serverGeneration.toString()).put("payload", payload).toString())
                }
                "ACK" -> {
                    val code = message.optString("code")
                    val requestId = message.optString("requestId")
                    heartbeatSentAt.remove(requestId)?.let { sentAt ->
                        if (code == "HEARTBEAT" && message.optBoolean("ok")) {
                            runtime.snapshot = runtime.snapshot.copy(
                                rttMillis = (System.currentTimeMillis() - sentAt).coerceAtLeast(0))
                        }
                    }
                    if (!message.optBoolean("ok") && code in setOf("STALE_GENERATION", "SESSION_REQUIRED", "AUTH_FAILED")) {
                        webSocket.close(1008, code); disconnected("Remote session rejected: $code"); return
                    }
                    if (code == "AUTHENTICATED") {
                        val payload = message.getJSONObject("payload")
                        authenticated = true
                        runtime.snapshot = runtime.snapshot.copy(state = RemoteConnectionState.READY,
                            sessionId = payload.getString("sessionId"), generation = message.optString("generation").toLongOrNull() ?: generation.get(),
                            role = runCatching { RemoteRole.valueOf(payload.optString("role")) }.getOrDefault(remote.role),
                             radioRoster = payload.optJSONArray("radioRoster")?.let { array ->
                                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                             }.orEmpty(), lastError = null)
                        val mediaRequest = UUID.randomUUID().toString()
                        webSocket.send(JSONObject().put("version", 1).put("type", "MEDIA_CONFIG")
                            .put("stationId", remote.stationId).put("sessionId", payload.getString("sessionId"))
                            .put("requestId", mediaRequest).put("generation", runtime.snapshot.generation.toString())
                            .put("payload", JSONObject().put("audioCodec", "OPUS").put("audioPreset", "BALANCED")
                                .put("audioCapKbps", 64).put("rawIq", false).put("lowDataMode", false)).toString())
                        startHeartbeat(webSocket)
                        ready.complete(true)
                    }
                    requests.remove(requestId)?.complete(message.optBoolean("ok"))
                }
                "STATE" -> if (authenticated) applyState(message)
                else -> Unit
            }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!authenticated || bytes.size > 256 * 1024 + 36) { fail("Rejected oversized or unauthenticated Remote media frame"); return }
            decode.execute { decodeMedia(bytes.toByteArray()) }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(reason) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = if (t is SSLPeerUnverifiedException) "Certificate pin validation failed" else t.message ?: "Remote connection failed"
            fail(reason.take(240)); ready.complete(false)
        }
    }

    private fun applyState(message: JSONObject) {
        val serverGeneration = message.optString("generation").toLongOrNull() ?: return
        if (serverGeneration < runtime.snapshot.generation) return
        if (serverGeneration > runtime.snapshot.generation) runtime.snapshot = runtime.snapshot.copy(
            generation = serverGeneration, writerLease = RemoteLeaseState.NONE, txLease = RemoteLeaseState.NONE, rotatorLease = RemoteLeaseState.NONE)
        val radio = message.optJSONObject("radio") ?: return
        message.optJSONObject("leases")?.let { leases ->
            runtime.snapshot = runtime.snapshot.copy(
                writerLease = if (leases.optBoolean("writer")) RemoteLeaseState.HELD else RemoteLeaseState.NONE,
                txLease = if (leases.optBoolean("tx")) RemoteLeaseState.HELD else RemoteLeaseState.NONE,
                rotatorLease = if (leases.optBoolean("rotator")) RemoteLeaseState.HELD else RemoteLeaseState.NONE,
            )
        }
        val frequency = radio.optString("frequencyHz").toLongOrNull()
        val mode = radio.optString("mode").takeIf { it.isNotBlank() }
        currentRadio = RadioRuntimeSnapshot(generation = serverGeneration, profileId = profile.id,
            backendKind = RadioBackendKind.REMOTE_STATION, modelId = profile.modelId, connected = true,
            sourceAgeMillis = 0, vfoAHz = frequency?.let { availableRemote(it) } ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
            mode = mode?.let { availableRemote(it) } ?: AvailableRadioValue(RadioAvailability.UNKNOWN),
            transmitting = AvailableRadioValue(RadioAvailability.UNAVAILABLE),
            capabilities = RadioCapabilitySet(frequency = if (frequency != null) RadioAvailability.AVAILABLE else RadioAvailability.UNKNOWN,
                mode = if (mode != null) RadioAvailability.AVAILABLE else RadioAvailability.UNKNOWN,
                panadapter = RadioAvailability.AVAILABLE, iqAudio = RadioAvailability.AVAILABLE,
                ptt = RadioAvailability.UNKNOWN, tune = RadioAvailability.UNKNOWN, memoryWrite = RadioAvailability.UNAVAILABLE),
            firmware = "ShackCQ Remote Protocol v1")
    }

    private fun decodeMedia(bytes: ByteArray) {
        if (bytes.size < 36 || !bytes.copyOfRange(0, 4).contentEquals("RWR1".toByteArray())) return drop()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data.position(4)
        if (data.short.toInt() != 1) return drop()
        val channel = data.get().toInt() and 0xff
        if (data.get().toInt() != 0) return drop()
        val flags = data.short.toInt() and 0xffff; if (data.short.toInt() != 0) return drop()
        val sequence = data.int.toLong() and 0xffffffffL
        data.long
        val frameGeneration = data.long
        val size = data.int
        if (size < 0 || size > 256 * 1024 || size != data.remaining() || frameGeneration != runtime.snapshot.generation) return drop()
        val payload = ByteArray(size).also(data::get)
        when (channel) {
            5 -> {
                if (payload.size < 4) return drop()
                val rate = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                if (rate !in 8_000..192_000) return drop()
                if (flags and 1 != 0) {
                    val channels = payload[4].toInt()
                    if (payload.size < 11 || channels !in 1..2 || payload[5].toInt() != 20) return drop()
                    val audioSequence = ByteBuffer.wrap(payload, 6, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
                    opus.offer(audioSequence, rate, channels, payload.copyOfRange(10, payload.size), frameGeneration)
                } else {
                    if ((payload.size - 4) % 2 != 0) return drop()
                    runtime.audioPcm16Sink?.invoke(rate, 1, payload.copyOfRange(4, payload.size), sequence, frameGeneration)
                    runtime.snapshot = runtime.snapshot.copy(audioSequence = sequence)
                }
            }
            7, 8 -> {
                runtime.spectrumSink?.invoke(payload, sequence, frameGeneration)
                runtime.snapshot = runtime.snapshot.copy(spectrumSequence = sequence)
            }
            9 -> {
                if (payload.size < 5 || payload[4].toInt() != 2 || (payload.size - 5) % 4 != 0) return drop()
                val rate = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                if (rate !in 16_000..192_000) return drop()
                val floats = FloatArray((payload.size - 5) / 4)
                ByteBuffer.wrap(payload, 5, payload.size - 5).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                runtime.rawIqSink?.invoke(rate, floats, sequence, frameGeneration)
            }
            else -> Unit
        }
    }
    private fun drop() { runtime.snapshot = runtime.snapshot.copy(droppedFrames = runtime.snapshot.droppedFrames + 1) }
    private fun fail(reason: String) {
        runtime.snapshot = runtime.snapshot.copy(state = RemoteConnectionState.ERROR, lastError = reason)
        currentRadio = currentRadio.copy(connected = false, lastSanitizedError = reason)
    }
    private fun disconnected(reason: String) {
        heartbeat?.cancel(false); heartbeat = null
        socket = null; requests.values.forEach { it.complete(false) }; requests.clear()
        heartbeatSentAt.clear()
        runtime.snapshot = runtime.snapshot.copy(state = RemoteConnectionState.DISCONNECTED, sessionId = null,
            writerLease = RemoteLeaseState.NONE, txLease = RemoteLeaseState.NONE, rotatorLease = RemoteLeaseState.NONE,
            lastError = reason.takeIf { it.isNotBlank() })
        currentRadio = currentRadio.copy(connected = false, generation = generation.incrementAndGet())
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.cancel(false)
        heartbeat = heartbeatExecutor.scheduleAtFixedRate({
            val snapshot = runtime.snapshot
            val session = snapshot.sessionId ?: return@scheduleAtFixedRate
            val requestId = UUID.randomUUID().toString()
            heartbeatSentAt.clear()
            heartbeatSentAt[requestId] = System.currentTimeMillis()
            val message = JSONObject().put("version", 1).put("type", "HEARTBEAT")
                .put("stationId", remote.stationId).put("sessionId", session)
                .put("requestId", requestId).put("generation", snapshot.generation.toString())
                .put("timestampMs", System.currentTimeMillis().toString())
                .put("payload", JSONObject().put("foreground", true))
            if (!webSocket.send(message.toString())) {
                heartbeatSentAt.remove(requestId)
                disconnected("Remote Station heartbeat failed")
            }
        }, 2, 5, TimeUnit.SECONDS)
    }
}

private fun <T> availableRemote(value: T) = AvailableRadioValue(RadioAvailability.AVAILABLE, value)

data class PairingOfferV1(val stationId: String, val stationName: String, val endpoint: String,
    val certificateSha256: String, val nonce: String, val expiresAtMs: Long, val defaultRole: RemoteRole) {
    companion object {
        fun parse(raw: String): PairingOfferV1? = runCatching {
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            PairingOfferV1(json.getString("stationId"), json.getString("stationName"), json.getString("endpoint"),
                json.getString("certificateSha256"), json.getString("nonce"), json.getLong("expiresAtMs"),
                RemoteRole.valueOf(json.optString("defaultRole", "OBSERVER"))).also {
                require(it.expiresAtMs > System.currentTimeMillis() && it.expiresAtMs - System.currentTimeMillis() <= 10 * 60_000)
                require(it.certificateSha256.matches(Regex("[a-fA-F0-9]{64}")))
            }
        }.getOrNull()
    }
}

class RemotePairingClient(private val identity: RemoteIdentity = RemoteIdentity()) {
    suspend fun request(offer: PairingOfferV1, deviceId: String,
                        requestedRole: RemoteRole = offer.defaultRole): Boolean {
        val uri = java.net.URI(offer.endpoint)
        val client = pinnedClient(offer.certificateSha256)
        val result = CompletableDeferred<Boolean>()
        val socket = client.newWebSocket(Request.Builder().url(offer.endpoint)
            .header("Sec-WebSocket-Protocol", "shackcq.remote.v1").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (message.optString("type") == "HELLO") {
                    if (message.optString("stationId") != offer.stationId) { result.complete(false); webSocket.cancel(); return }
                    val challenge = "${offer.stationId}|${offer.nonce}|$deviceId"
                    val payload = JSONObject().put("nonce", offer.nonce).put("deviceId", deviceId)
                        .put("publicKeyPem", identity.publicKeyPem()).put("signature", identity.sign(challenge))
                        .put("requestedRole", requestedRole.name)
                    webSocket.send(JSONObject().put("version", 1).put("type", "PAIR_REQUEST")
                        .put("requestId", UUID.randomUUID().toString()).put("payload", payload).toString())
                } else if (message.optString("type") == "ACK") {
                    result.complete(message.optBoolean("ok") && message.optString("code") == "LOCAL_APPROVAL_REQUIRED")
                    webSocket.close(1000, "Pairing request submitted")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { result.complete(false) }
        })
        val accepted = withTimeoutOrNull(10_000) { result.await() } ?: false
        if (!accepted) socket.cancel()
        client.dispatcher.executorService.shutdown()
        return accepted
    }
}
