// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSdrWorkbenchV4InstrumentedTest {
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().context

    @Test fun float32IqCaptureIsAtomicBoundedAndOfflineReplaySeeks() {
        val context = context()
        File(context.filesDir, "sdr/iq-captures").deleteRecursively()
        val directory = File(context.filesDir, "sdr/iq-captures").apply { mkdirs() }
        File(directory, "orphan.f32iq").writeBytes(ByteArray(8))
        File(directory, "incomplete.json.tmp").writeText("{}")
        IqCaptureRepository(context).use { assertTrue(it.captures.isEmpty()) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        IqCaptureRepository(context).use { repository ->
            repository.configure(1, 16L * 1024 * 1024)
            assertTrue(repository.start("TEST", 0, 14_074_000, 8_000, "OFFLINE", "20m", "instrumented"))
            val samples = FloatArray(16_000) { if (it % 2 == 0) .25f else -.25f }
            repository.append("TEST", 0, 8_000, samples)
            val capture = repository.stop("test")
            assertNotNull(capture)
            assertEquals(64_000L, capture!!.dataBytes)
            val directory = File(context.filesDir, "sdr/iq-captures")
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            ReplayIqSource(repository).use { replay ->
                val received = CountDownLatch(1)
                replay.sink = { source, _, center, rate, values ->
                    if (source == "REPLAY" && center == 14_074_000L && rate == 8_000 && values.isNotEmpty()) received.countDown()
                }
                assertTrue(replay.play(capture.metadata.id, 2f))
                replay.seek(500)
                assertTrue(received.await(2, TimeUnit.SECONDS))
                replay.stop()
            }
        }
        File(context.filesDir, "sdr/iq-captures").deleteRecursively()
    }

    @Test fun spectrumSurveyFreshCreateAggregateReopenRetentionAndFutureSchemaRejection() {
        val context = context()
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
        context.getDatabasePath("shackcq-spectrum-survey.sqlite").apply { parentFile?.mkdirs(); writeText("not a sqlite database") }
        assertThrows(SQLiteException::class.java) { SpectrumSurveyRepository(context).writableDatabase }
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
        SpectrumSurveyRepository(context).use { survey ->
            repeat(200) { index -> survey.aggregate(System.currentTimeMillis(), "20m", 14_074_000L + index % 3 * 1_000,
                "DIGU", "TEST", 0, -80f + index % 4, -120f, occupied = index % 2 == 0, scannerHit = index % 10 == 0) }
            survey.compact()
            assertEquals("ok", survey.quickCheck())
            assertTrue(survey.query(limit = 100).isNotEmpty())
            assertTrue(survey.stats.rows <= 3)
        }
        SpectrumSurveyRepository(context).use { assertTrue(it.query(limit = 10).isNotEmpty()) }
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
        context.openOrCreateDatabase("shackcq-spectrum-survey.sqlite", Context.MODE_PRIVATE, null).use { it.version = 99 }
        assertThrows(SQLiteException::class.java) { SpectrumSurveyRepository(context).writableDatabase }
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
        context.openOrCreateDatabase("shackcq-spectrum-survey.sqlite", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE spectrum_aggregate(bucket_epoch INTEGER NOT NULL, band TEXT NOT NULL, frequency_bucket_hz INTEGER NOT NULL, mode TEXT NOT NULL, source TEXT NOT NULL, receiver INTEGER NOT NULL, samples INTEGER NOT NULL, occupied_samples INTEGER NOT NULL, median_level REAL NOT NULL, peak_level REAL NOT NULL, median_noise REAL NOT NULL, signal_count INTEGER NOT NULL, scanner_hit_count INTEGER NOT NULL, PRIMARY KEY(bucket_epoch, band, frequency_bucket_hz, mode, source, receiver))")
            db.execSQL("CREATE INDEX spectrum_aggregate_time ON spectrum_aggregate(bucket_epoch DESC)")
            db.execSQL("CREATE INDEX spectrum_aggregate_frequency ON spectrum_aggregate(frequency_bucket_hz, bucket_epoch DESC)")
            db.version = 1
        }
        SpectrumSurveyRepository(context).use { survey ->
            assertEquals(2, survey.writableDatabase.version)
            assertTrue(survey.writableDatabase.rawQuery("SELECT schema_version FROM spectrum_survey_meta", null).use { it.moveToFirst() && it.getInt(0) == 2 })
        }
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
    }

    @Test fun measurementsTrackerCalibrationAndFourMonitorsRemainInSpanAndRelative() {
        val controller = SignalMeasurementController(ReceiveCalibrationRepository(context()))
        val trace = FloatArray(1_024) { -120f }
        trace[600] = -45f; trace[599] = -50f; trace[601] = -50f
        val frame = PanadapterFrame(1, 1, 1, 0, 96_000, 96_000, 1_024, 256, 1, 0f,
            1f, 93.75f, -45f, -120f, -60f, -60f, 0f, 0f, 0f, true,
            trace, trace.copyOf(), trace.copyOf())
        val expected = 14_074_000L - 48_000 + 600L * 96_000 / 1_024
        controller.setMarkerA(expected)
        controller.setMarkerB(expected + 1_000)
        controller.selectTracker(expected)
        repeat(4) { controller.upsertMonitor(ChannelMonitor(name = "M$it", frequencyHz = expected + it * 100, squelchDb = -100f)) }
        controller.upsertMonitor(ChannelMonitor(name = "M5", frequencyHz = expected + 500, squelchDb = -100f))
        controller.updateFrame(frame, 14_074_000, "TEST")
        assertEquals(4, controller.monitors.size)
        assertTrue(controller.inspector.markerA!!.snr > 60f)
        assertTrue(controller.inspector.markerA!!.units.startsWith("dBFS"))
        assertTrue(controller.tracker.selected)
        assertTrue(controller.monitors.first().occupied)
        assertTrue(controller.monitors.all { it.toneState.contains("NO TONE CLAIM") })
    }
}
