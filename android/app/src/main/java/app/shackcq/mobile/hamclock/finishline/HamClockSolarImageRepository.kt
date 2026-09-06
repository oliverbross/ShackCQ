package app.shackcq.mobile.hamclock.finishline

import app.shackcq.mobile.readBoundedBytes
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

internal enum class HamClockSolarChannel(val title: String, val url: String, val source: String) {
    AIA_171("SDO AIA 171 Å", "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_0171.jpg", "NASA SDO/AIA"),
    AIA_193("SDO AIA 193 Å", "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_0193.jpg", "NASA SDO/AIA"),
    AIA_304("SDO AIA 304 Å", "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_0304.jpg", "NASA SDO/AIA"),
    HMI_CONTINUUM("SDO HMI continuum", "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_HMIIC.jpg", "NASA SDO/HMI"),
    HMI_MAGNETOGRAM("SDO HMI magnetogram", "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_HMIB.jpg", "NASA SDO/HMI"),
}

internal data class HamClockSolarImageSnapshot(
    val channel: HamClockSolarChannel,
    val bitmap: Bitmap? = null,
    val fetchedAtEpoch: Long = 0,
    val truth: HamClockProviderTruth = HamClockProviderTruth.UNAVAILABLE,
    val error: String = "",
)

internal class HamClockSolarImageRepository(cacheDirectory: File) {
    private val directory = File(cacheDirectory, "finishline-solar-images").apply(File::mkdirs)

    fun load(channel: HamClockSolarChannel, lowData: Boolean, force: Boolean = false,
        nowEpoch: Long = Instant.now().epochSecond): HamClockSolarImageSnapshot {
        val cached = cached(channel, nowEpoch)
        if (lowData) return cached.copy(bitmap = null, truth = HamClockProviderTruth.UNAVAILABLE,
            error = "Solar imagery disabled in low-data mode")
        if (cached.bitmap != null && nowEpoch - cached.fetchedAtEpoch < REFRESH_COOLDOWN_SECONDS) {
            return cached.copy(error = if (force) "Manual refresh cooldown active" else cached.error)
        }
        return runCatching {
            val connection = URL(channel.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "ShackCQ-Android/0.1 HamClock solar image")
                connection.setRequestProperty("Accept", "image/jpeg,image/png")
                require(connection.responseCode in 200..299) { "Solar image HTTP ${connection.responseCode}" }
                require(connection.contentType.orEmpty().lowercase().startsWith("image/")) { "Solar image payload was not an image" }
                require(connection.contentLengthLong <= MAX_BYTES) { "Solar image payload is too large" }
                val bytes = connection.inputStream.use { it.readBoundedBytes(MAX_BYTES + 1) }
                require(bytes.size <= MAX_BYTES) { "Solar image payload is too large" }
                val bitmap = decodeBoundedSolarImage(bytes)
                val target = imageFile(channel)
                val part = File(target.parentFile, target.name + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(target)) { target.writeBytes(bytes); part.delete() }
                metadataFile(channel).writeText(nowEpoch.toString())
                HamClockSolarImageSnapshot(channel, bitmap, nowEpoch, HamClockProviderTruth.CURRENT)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            if (cached.bitmap != null) cached.copy(error = failure.message.orEmpty().take(120))
            else HamClockSolarImageSnapshot(channel, truth = HamClockProviderTruth.ERROR,
                error = failure.message.orEmpty().take(120).ifBlank { "Solar image unavailable" })
        }
    }

    fun cached(channel: HamClockSolarChannel, nowEpoch: Long = Instant.now().epochSecond): HamClockSolarImageSnapshot {
        val file = imageFile(channel)
        val fetched = metadataFile(channel).takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: 0
        val bitmap = runCatching { file.takeIf(File::isFile)?.readBytes()?.let(::decodeBoundedSolarImage) }.getOrNull()
        return if (bitmap == null) HamClockSolarImageSnapshot(channel) else HamClockSolarImageSnapshot(
            channel, bitmap, fetched,
            if (nowEpoch - fetched <= IMAGE_STALE_SECONDS) HamClockProviderTruth.CACHED else HamClockProviderTruth.STALE,
        )
    }

    private fun imageFile(channel: HamClockSolarChannel) = File(directory, channel.name.lowercase() + ".image")
    private fun metadataFile(channel: HamClockSolarChannel) = File(directory, channel.name.lowercase() + ".meta")

    private companion object {
        const val MAX_BYTES = 4_000_000
        const val MAX_DIMENSION = 2048
        const val REFRESH_COOLDOWN_SECONDS = 15 * 60L
        const val IMAGE_STALE_SECONDS = 2 * 60 * 60L
    }
}

internal fun decodeBoundedSolarImage(bytes: ByteArray): Bitmap {
    require(bytes.size in 1..4_000_000) { "Solar image byte count is invalid" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "Solar image dimensions are invalid" }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })) { "Solar image decode failed" }
}
