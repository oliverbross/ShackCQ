package app.shackcq.mobile.hamclock

import app.shackcq.mobile.readBoundedBytes
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

internal data class HamClockHttpResponse(
    val body: String,
    val etag: String = "",
    val lastModified: String = "",
    val contentType: String = "",
    val effectiveUrl: String = "",
    val retryAfterSeconds: Long? = null,
)

internal fun interface HamClockHttpClient {
    fun get(request: HamClockHttpRequest): HamClockHttpResponse
}

internal data class HamClockHttpRequest(
    val url: String,
    val accept: String,
    val maxBytes: Int,
    val etag: String = "",
    val lastModified: String = "",
)

internal class HamClockUrlConnectionClient : HamClockHttpClient {
    override fun get(request: HamClockHttpRequest): HamClockHttpResponse {
        require(request.url.startsWith("https://")) { "Only HTTPS public providers are allowed" }
        var target = URL(request.url)
        repeat(4) { redirectCount ->
            require(target.protocol.equals("https", true)) { "Provider redirected outside HTTPS" }
            val connection = target.openConnection() as HttpURLConnection
            try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ShackCQ-Android/0.1 OpenHamClock-integration")
            connection.setRequestProperty("Accept", request.accept)
            if (request.etag.isNotBlank()) connection.setRequestProperty("If-None-Match", request.etag)
            if (request.lastModified.isNotBlank()) connection.setRequestProperty("If-Modified-Since", request.lastModified)
            val code = connection.responseCode
            if (code in 300..399) {
                require(redirectCount < 3) { "Provider redirect limit exceeded" }
                val location = connection.getHeaderField("Location")?.takeIf(String::isNotBlank)
                    ?: throw IllegalStateException("Provider redirect omitted Location")
                target = URL(target, location)
                require(target.protocol.equals("https", true)) { "Provider redirected outside HTTPS" }
                return@repeat
            }
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) throw HamClockNotModified()
            if (code !in 200..299) throw HamClockHttpException(code,
                connection.getHeaderField("Retry-After")?.toLongOrNull())
            if (connection.contentLengthLong > request.maxBytes) throw IllegalStateException("Provider response is too large")
            val bytes = connection.inputStream.use { it.readBoundedBytes(request.maxBytes + 1) }
            if (bytes.size > request.maxBytes) throw IllegalStateException("Provider response is too large")
            return HamClockHttpResponse(
                body = bytes.toString(Charsets.UTF_8),
                etag = connection.getHeaderField("ETag").orEmpty(),
                lastModified = connection.getHeaderField("Last-Modified").orEmpty(),
                contentType = connection.contentType.orEmpty(),
                effectiveUrl = target.toString(),
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
            )
            } finally {
                connection.disconnect()
            }
        }
        error("Provider redirect limit exceeded")
    }
}

internal class HamClockNotModified : Exception()
internal class HamClockHttpException(val status: Int, val retryAfterSeconds: Long?) :
    IllegalStateException("Provider returned HTTP $status")

internal data class HamClockCacheEntry(
    val body: String,
    val fetchedAtEpoch: Long,
    val etag: String,
    val lastModified: String,
)

/** Small atomic disk cache. Invalid downloads never replace the last good payload. */
internal class HamClockLastGoodCache(directory: File, name: String) {
    private val data = File(directory.apply(File::mkdirs), "$name.json")
    private val metadata = File(directory, "$name.meta")

    @Synchronized fun read(): HamClockCacheEntry? = runCatching {
        if (!data.isFile) return null
        val lines = metadata.takeIf(File::isFile)?.readLines().orEmpty()
        HamClockCacheEntry(
            body = data.readText(Charsets.UTF_8),
            fetchedAtEpoch = lines.getOrNull(0)?.toLongOrNull() ?: data.lastModified() / 1000,
            etag = lines.getOrNull(1).orEmpty(),
            lastModified = lines.getOrNull(2).orEmpty(),
        )
    }.getOrNull()

    @Synchronized fun write(body: String, fetchedAtEpoch: Long, etag: String, lastModified: String) {
        writeAtomic(data, body)
        writeAtomic(metadata, listOf(fetchedAtEpoch, etag.replace('\n', ' '), lastModified.replace('\n', ' ')).joinToString("\n"))
    }

    private fun writeAtomic(destination: File, body: String) {
        val part = File(destination.parentFile, destination.name + ".part")
        part.writeText(body, Charsets.UTF_8)
        if (!part.renameTo(destination)) {
            destination.writeText(body, Charsets.UTF_8)
            part.delete()
        }
    }
}

internal fun providerError(error: Throwable): String = when (error) {
    is java.net.SocketTimeoutException -> "Timed out"
    is UnknownHostException -> "Offline"
    else -> error.message?.replace(Regex("https?://\\S+|(?i)(token|key|password|callback|callsign)=[^\\s&]+"), "[redacted]")
        ?.take(120)?.ifBlank { "Unavailable" } ?: "Unavailable"
}

/** Shares one active provider request without retaining completed results. */
internal class HamClockInFlightCoalescer(
    private val executor: Executor = ForkJoinPool.commonPool(),
) {
    private val active = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> run(key: String, block: () -> T): T {
        val candidate = CompletableFuture<Any?>()
        val shared = active.putIfAbsent(key, candidate) ?: candidate.also { future ->
            try {
                executor.execute {
                    try {
                        val result = block()
                        active.remove(key, future)
                        future.complete(result)
                    } catch (error: Throwable) {
                        active.remove(key, future)
                        future.completeExceptionally(error)
                    }
                }
            } catch (error: Throwable) {
                active.remove(key, future)
                future.completeExceptionally(error)
            }
        }
        return try {
            shared.get() as T
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    internal fun activeRequestCount(): Int = active.size
}
