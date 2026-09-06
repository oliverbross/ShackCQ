package app.shackcq.mobile

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Locale

enum class NeuralProviderState {
    LIVE,
    CACHED,
    STALE,
    UNAVAILABLE,
}

data class NeuralProviderStatus(
    val source: String,
    val state: NeuralProviderState,
    val updatedEpoch: Long = 0,
    val expiresEpoch: Long = 0,
    val detail: String = "",
)

data class NeuralProviderResult<T>(
    val value: T?,
    val status: NeuralProviderStatus,
)

internal fun NeuralProviderStatus.effective(nowEpoch: Long = Instant.now().epochSecond): NeuralProviderStatus =
    if (state in setOf(NeuralProviderState.LIVE, NeuralProviderState.CACHED) &&
        expiresEpoch > 0 && nowEpoch > expiresEpoch
    ) copy(state = NeuralProviderState.STALE) else this

internal fun neuralProviderAgeLabel(updatedEpoch: Long, nowEpoch: Long = Instant.now().epochSecond): String {
    if (updatedEpoch <= 0) return ""
    val age = (nowEpoch - updatedEpoch).coerceAtLeast(0)
    return when {
        age < 10 -> "just now"
        age < 60 -> "${age}s old"
        age < 3_600 -> "${age / 60}m old"
        age < 86_400 -> "${age / 3_600}h old"
        else -> "${age / 86_400}d old"
    }
}

internal fun neuralProviderSummary(
    statuses: List<NeuralProviderStatus>,
    nowEpoch: Long = Instant.now().epochSecond,
): String {
    val counts = statuses.map { it.effective(nowEpoch).state }.groupingBy { it }.eachCount()
    val parts = listOf(
        NeuralProviderState.LIVE to "live",
        NeuralProviderState.CACHED to "cached",
        NeuralProviderState.STALE to "stale",
        NeuralProviderState.UNAVAILABLE to "unavailable",
    ).mapNotNull { (state, label) -> counts[state]?.takeIf { it > 0 }?.let { "$it $label" } }
    return "Providers · " + parts.joinToString(" · ").ifBlank { "none requested" }
}

internal fun neuralPointCacheKey(point: GeoPoint): String =
    String.format(Locale.US, "%.2f_%.2f", point.latitude, point.longitude)
        .replace('-', 'm')
        .replace('.', 'p')

internal fun atomicWriteNeuralText(file: File, text: String, updatedEpoch: Long = Instant.now().epochSecond) {
    val parent = requireNotNull(file.parentFile) { "Cache file requires a parent directory" }
    Files.createDirectories(parent.toPath())
    val temporary = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp").toFile()
    try {
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(updatedEpoch * 1_000))
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

internal fun <T> loadNeuralProvider(
    file: File,
    source: String,
    ttlSeconds: Long,
    maximumBytes: Int,
    force: Boolean,
    nowEpoch: Long = Instant.now().epochSecond,
    fetch: (() -> String)?,
    decode: (String) -> T,
): NeuralProviderResult<T> {
    var cachedValue: T? = null
    var cachedUpdatedEpoch = 0L
    var cacheFailure = ""
    if (file.exists()) {
        if (file.length() in 1..maximumBytes.toLong()) {
            try {
                cachedValue = decode(file.readText(Charsets.UTF_8))
                cachedUpdatedEpoch = file.lastModified() / 1_000
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cacheFailure = neuralProviderFailureDetail(error)
            }
        } else {
            cacheFailure = if (file.length() <= 0) "Cached response is empty" else "Cached response is too large"
        }
    }

    fun cached(detail: String = ""): NeuralProviderResult<T>? {
        val value = cachedValue ?: return null
        val expires = cachedUpdatedEpoch + ttlSeconds
        val state = if (nowEpoch <= expires) NeuralProviderState.CACHED else NeuralProviderState.STALE
        return NeuralProviderResult(value, NeuralProviderStatus(source, state, cachedUpdatedEpoch, expires, detail))
    }

    if (fetch == null) {
        return cached() ?: NeuralProviderResult(
            null,
            NeuralProviderStatus(source, NeuralProviderState.UNAVAILABLE, detail = cacheFailure.ifBlank { "No cached data" }),
        )
    }
    if (!force && cachedValue != null && nowEpoch <= cachedUpdatedEpoch + ttlSeconds) return cached()!!

    return try {
        val fetched = fetch()
        require(fetched.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "Response is too large" }
        val decoded = decode(fetched)
        atomicWriteNeuralText(file, fetched, nowEpoch)
        NeuralProviderResult(
            decoded,
            NeuralProviderStatus(source, NeuralProviderState.LIVE, nowEpoch, nowEpoch + ttlSeconds),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        cached(neuralProviderFailureDetail(error)) ?: NeuralProviderResult(
            null,
            NeuralProviderStatus(source, NeuralProviderState.UNAVAILABLE, detail = neuralProviderFailureDetail(error)),
        )
    }
}

private fun neuralProviderFailureDetail(error: Throwable): String = when (error) {
    is java.net.SocketTimeoutException -> "Timed out"
    is java.net.UnknownHostException -> "Offline"
    else -> error.message.orEmpty()
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "provider")
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(120)
        .ifBlank { "Unavailable" }
}
