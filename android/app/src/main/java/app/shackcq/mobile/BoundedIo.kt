package app.shackcq.mobile

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBoundedBytes(maximumBytes: Int): ByteArray {
    require(maximumBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maximumBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) {
            val single = read()
            if (single < 0) break
            output.write(single)
            remaining--
            continue
        }
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

internal fun InputStream.readExactBytes(count: Int): ByteArray =
    readBoundedBytes(count).also { require(it.size == count) { "Incomplete stream" } }

internal fun InputStream.readBoundedBytesOrThrow(maximumBytes: Int): ByteArray {
    require(maximumBytes >= 0)
    val probeLimit = maximumBytes.toLong().plus(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return readBoundedBytes(probeLimit).also {
        require(it.size <= maximumBytes) { "Stream exceeds $maximumBytes bytes" }
    }
}
