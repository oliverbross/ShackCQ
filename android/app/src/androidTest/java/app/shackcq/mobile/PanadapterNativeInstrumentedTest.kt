package app.shackcq.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PanadapterNativeInstrumentedTest {
    @Test fun zeroHandleAndMalformedArraysAreRejectedWithoutDereference() {
        NativePanadapter.destroy(0)
        assertFalse(NativePanadapter.configure(0, 96_000, 4_096, 50, 3,
            -140f, 0f, 1f, 1f, 1, false, 0f, false,
            false, false, false, false, 1f, 1f, 1, 0f))
        assertFalse(NativePanadapter.push(0, shortArrayOf(0), 1, false))
        assertEquals(0, NativePanadapter.snapshot(0, LongArray(9), FloatArray(14),
            FloatArray(1), FloatArray(1), FloatArray(1)))
        assertFalse(NativePanadapter.setIqCorrection(0, 1f, 0f, 0f, 0f, true))
        NativePanadapter.resetPeakHold(0)
    }

    @Test fun dedicatedJniContextConfiguresPushesAndReturnsOneCoherentFrame() {
        val handle = NativePanadapter.create()
        try {
            assertTrue(NativePanadapter.configure(handle, 96_000, 4_096, 50, 3,
                -140f, 0f, 1f, 1f, 1, false, 0f, false,
                false, false, false, false, 1f, 1f, 1, 0f))
            val pcm = ShortArray(4_096 * 2)
            repeat(4_096) { index ->
                val phase = 2.0 * PI * 256.0 * index / 4_096.0
                pcm[index * 2] = (cos(phase) * .5 * Short.MAX_VALUE).toInt().toShort()
                pcm[index * 2 + 1] = (sin(phase) * .5 * Short.MAX_VALUE).toInt().toShort()
            }
            assertTrue(NativePanadapter.push(handle, pcm, pcm.size, false))
            val meta = LongArray(9); val metrics = FloatArray(14)
            val trace = FloatArray(4_096); val waterfall = FloatArray(4_096); val peak = FloatArray(4_096)
            assertEquals(4_096, NativePanadapter.snapshot(handle, meta, metrics, trace, waterfall, peak))
            assertEquals(1L, meta[0]); assertEquals(4_096L, meta[1]); assertEquals(96_000L, meta[4])
            assertEquals(2_304, trace.indices.maxBy { trace[it] })
            assertTrue(trace[2_304] in -6.4f..-5.7f)
            assertTrue(trace[2_304] - trace[1_792] > 50f)
        } finally { NativePanadapter.destroy(handle) }
    }

    @Test fun floatIqUsesTheSameBoundedPanadapterOwner() {
        val handle = NativePanadapter.create()
        try {
            assertTrue(NativePanadapter.configure(handle, 48_000, 2_048, 50, 2,
                -140f, 0f, 1f, 1f, 1, false, 0f, false,
                false, false, false, false, 1f, 1f, 1, 0f))
            val iq = FloatArray(4_096)
            repeat(2_048) { index ->
                val phase = 2.0 * PI * 128.0 * index / 2_048.0
                iq[index * 2] = cos(phase).toFloat() * .4f
                iq[index * 2 + 1] = sin(phase).toFloat() * .4f
            }
            assertTrue(NativePanadapter.pushFloat(handle, iq, iq.size, false))
        } finally {
            NativePanadapter.destroy(handle)
        }
    }
}
