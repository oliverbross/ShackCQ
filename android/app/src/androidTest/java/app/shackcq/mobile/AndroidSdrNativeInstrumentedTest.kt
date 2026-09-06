package app.shackcq.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidSdrNativeInstrumentedTest {
    @Test fun timeShiftPersistsOnlyBoundedReducedBookmarks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("shackcq-sdr-operational-v2-derived.db")
        SdrOperationalV2(context).use { operational ->
            operational.timeShift.configure(TimeShiftLength.SECONDS_30)
            val trace = FloatArray(2_048) { -120f + (it % 40) }
            val frame = PanadapterFrame(1, 1, 1, 0, 48_000, 48_000, 2_048, 512, 1, 0f,
                1f, 23.4f, -80f, -120f, -60f, -60f, 0f, 0f, 0f, true,
                trace, trace.copyOf(), trace.copyOf())
            operational.onPanadapterFrame(frame, 0, 14_074_000, "TEST")
            operational.timeShift.pause()
            val bookmark = operational.timeShift.bookmark("bounded test")
            assertTrue(bookmark != null)
            assertTrue(bookmark!!.sampleCount <= 512)
            assertEquals(TimeShiftPlayback.PAUSED, operational.timeShift.snapshot.playback)
        }
        context.deleteDatabase("shackcq-sdr-operational-v2-derived.db")
    }

    @Test fun tciStatusAndSafeCommandsUseSharedNativeContract() {
        val rows = NativeTci.parseStatus("protocol:TCI,1.9;device:ExpertSDR;ready;")
        assertEquals(listOf("protocol|TCI,1.9", "device|ExpertSDR", "ready|"), rows.toList())
        assertEquals("vfo:1,0,14074000;", NativeTci.buildCommand(NativeTci.VFO, 1, 0, 14_074_000, ""))
        assertEquals("if:1,0,-1500;", NativeTci.buildCommand(NativeTci.IF_OFFSET, 1, 0, -1_500, ""))
        assertEquals("volume:-20;", NativeTci.buildCommand(NativeTci.VOLUME, 0, 0, -20, ""))
        assertEquals("split_enable:1,true;", NativeTci.buildCommand(NativeTci.SPLIT, 1, 0, 1, ""))
        assertEquals("", NativeTci.buildCommand(99, 0, 0, 0, ""))
    }

    @Test fun nativeRxDspProcessesFiniteAudioAndReportsMetrics() {
        val handle = NativeRxDsp.create()
        try {
            val samples = FloatArray(4_800) { index ->
                (sin(2.0 * PI * 1_000.0 * index / 48_000.0) * .4).toFloat()
            }
            samples[1_000] = 1.2f
            val metrics = NativeRxDsp.process(handle, samples, 48_000, true, true, .35f, true, 250, -110f, .8f)
            assertEquals(8, metrics.size)
            assertTrue(samples.all(Float::isFinite))
            assertTrue(metrics.all(Float::isFinite))
            assertTrue(metrics[3] in 0f..1f)
        } finally {
            NativeRxDsp.destroy(handle)
        }
    }
}
