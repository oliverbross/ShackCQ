package app.shackcq.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedIoTest {
    @Test fun strictBoundedReadRejectsOversizedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBoundedBytesOrThrow(2)
        }
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBoundedBytesOrThrow(2))
    }

    @Test fun framedReadsRejectTruncationAndMakeProgressAfterZero() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf(1)).readExactBytes(2)
        }
        val delegate = ByteArrayInputStream(byteArrayOf(4, 5, 6))
        var first = true
        val stream = object : InputStream() {
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return delegate.read(buffer, offset, length)
            }
        }
        assertArrayEquals(byteArrayOf(4, 5, 6), stream.readBoundedBytes(3))
    }
}
