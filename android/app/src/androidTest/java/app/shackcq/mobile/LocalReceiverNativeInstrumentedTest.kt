// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalReceiverNativeInstrumentedTest {
    private fun tone(rate: Int, frequency: Double, frames: Int): FloatArray = FloatArray(frames * 2) { index ->
        val frame = index / 2
        if (index % 2 == 0) (.45 * cos(2.0 * PI * frequency * frame / rate)).toFloat()
        else (.45 * sin(2.0 * PI * frequency * frame / rate)).toFloat()
    }

    @Test fun allLocalModesUseOneCheckedNativeHandleAndBoundedOutput() {
        val handle = NativeLocalReceiver.create()
        assertTrue(handle != 0L)
        try {
            LocalReceiverMode.entries.forEach { mode ->
                val rate = if (mode == LocalReceiverMode.WFM) 192_000 else 96_000
                val defaults = localReceiverModeDefaults(mode)
                assertTrue(NativeLocalReceiver.configure(handle, rate, mode.ordinal, 0f, defaults.filterLowHz.toFloat(),
                    defaults.filterHighHz.toFloat(), defaults.cwPitchHz.toFloat(), defaults.squelchDb, defaults.fmDeemphasisUs))
                val output = NativeLocalReceiver.process(handle, tone(rate, if (mode == LocalReceiverMode.WFM) 19_000.0 else 1_000.0, rate / 20))
                assertTrue(output.size >= NativeLocalReceiver.HEADER_SIZE)
                assertTrue(output.all(Float::isFinite))
                if (mode == LocalReceiverMode.SPECTRUM) assertEquals(NativeLocalReceiver.HEADER_SIZE, output.size)
            }
        } finally { NativeLocalReceiver.destroy(handle) }
    }

    @Test fun wfmCapabilityAndRdsFixtureRemainExplicit() {
        val handle = NativeLocalReceiver.create()
        try {
            val defaults = localReceiverModeDefaults(LocalReceiverMode.WFM)
            assertFalse(NativeLocalReceiver.configure(handle, 96_000, LocalReceiverMode.WFM.ordinal, 0f, 0f, 95_000f, 600f, -100f, 75))
            assertTrue(NativeLocalReceiver.configure(handle, 192_000, LocalReceiverMode.WFM.ordinal, 0f, 0f, 95_000f, 600f, -100f, 75))
            "DEMO FM ".chunked(2).forEachIndexed { index, text ->
                assertTrue(NativeLocalReceiver.debugRdsGroup(handle, 0xC0DE, index, 0x0102, (text[0].code shl 8) or text[1].code))
            }
            val output = NativeLocalReceiver.process(handle, tone(192_000, 19_000.0, 19_200))
            assertEquals(2, output[0].toInt())
            assertEquals(0xC0DE, output[13].toInt())
            assertTrue(output[16] > .5f)
            val metadata = JSONObject(NativeLocalReceiver.metadata(handle))
            assertEquals("DEMO FM", metadata.getString("ps"))
            assertTrue(metadata.has("tp") && metadata.has("ta") && metadata.has("clock"))
        } finally { NativeLocalReceiver.destroy(handle) }
    }

    @Test fun recordingStoreFreshCreateCleansPartialFilesAndRejectsFutureSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("shackcq-local-sdr-v3.db")
        val root = File(context.filesDir, "local-receiver-recordings").apply { mkdirs() }
        val partial = File(root, "crash.wav.partial").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ReceiverRecordingStore(context).use { store ->
            store.recover()
            assertFalse(partial.exists())
            assertEquals(1, store.readableDatabase.version)
            assertTrue(store.rows().isEmpty())
        }
        context.deleteDatabase("shackcq-local-sdr-v3.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("shackcq-local-sdr-v3.db"), null).use { it.version = 2 }
        assertThrows(Exception::class.java) { ReceiverRecordingStore(context).writableDatabase }
        context.deleteDatabase("shackcq-local-sdr-v3.db")
    }
}
