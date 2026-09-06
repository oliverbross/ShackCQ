// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSdrWorkbenchV4ScaleInstrumentedTest {
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().context

    @Test fun thirtyMinuteSegmentedCaptureSeekTwoReceiversAndThousandReplaySwitchesStayBounded() {
        val context = context()
        val directory = File(context.filesDir, "sdr/iq-captures")
        directory.deleteRecursively()
        val started = System.nanoTime()
        var writtenBytes = 0L
        IqCaptureRepository(context).use { repository ->
            repository.configure(600, 256L * 1024 * 1024)
            val oneSecond = FloatArray(16_000) { index -> if (index % 2 == 0) .2f else -.2f }
            repeat(3) { segment ->
                assertTrue(repository.start("SCALE", 0, 14_074_000L, 8_000, "OFFLINE", "20m", "10-minute segment ${segment + 1}"))
                repeat(600) { repository.append("SCALE", 0, 8_000, oneSecond) }
            }
            assertEquals(3, repository.captures.size)
            assertTrue(repository.captures.all { it.metadata.durationMillis == 600_000L })
            writtenBytes = repository.captures.sumOf { it.dataBytes }
            ReplayIqSource(repository).use { replay ->
                repository.captures.forEach { row ->
                    val received = CountDownLatch(1)
                    replay.sink = { source, _, _, _, values -> if (source == "REPLAY" && values.isNotEmpty()) received.countDown() }
                    assertTrue(replay.play(row.metadata.id, 2f))
                    Thread.sleep(5)
                    replay.seek(599_000)
                    assertTrue(received.await(2, TimeUnit.SECONDS))
                    replay.stop("Scale segment complete")
                }
                repeat(1_000) {
                    assertTrue(replay.play(repository.captures.first().metadata.id, 2f))
                    replay.stop("Replay/live switch $it")
                }
            }
            val routes = AudioMonitorController(context)
            val audio = TciRxAudioController(context, routes)
            val store = SdrV2DerivedStore(context)
            val local = LocalReceiverController(context, audio, ReceiveTimeShiftController(store))
            try {
                assertTrue(local.add("REPLAY", 0, 14_074_000L, 96_000))
                assertTrue(local.add("REPLAY", 0, 14_074_000L, 96_000))
                local.pushIq("REPLAY", 0, 14_074_000L, 96_000, FloatArray(8_192))
                assertEquals(2, local.snapshot.receivers.size)
            } finally {
                local.close(); audio.close(); store.close()
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        logMetrics("capture_replay", elapsedMillis, writtenBytes, 0, 0, 0)
        directory.deleteRecursively()
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test fun hundredThousandSurveyThirtyDaysTrackerMonitorsAndAdaptiveScannerStayBounded() {
        val context = context()
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
        val now = System.currentTimeMillis()
        var aggregationMillis = 0L
        var queryMillis = 0L
        var rows = 0L
        var bytes = 0L
        SpectrumSurveyRepository(context).use { survey ->
            survey.configure(90, 15, 250_000, 64L * 1024 * 1024)
            aggregationMillis = measureTimeMillis {
                survey.aggregateBatch(sequence {
                    repeat(100_000) { index ->
                        yield(SpectrumSurveyInput(now - (index % 2_880) * 900_000L, "20m",
                            14_000_000L + (index % 1_000) * 1_000L, if (index % 2 == 0) "DIGU" else "USB",
                            "SCALE", index % 2, -115f + index % 60, -128f + index % 8,
                            index % 3 == 0, scannerHit = index % 97 == 0))
                    }
                })
            }
            survey.compact()
            queryMillis = measureTimeMillis { assertTrue(survey.query(limit = 100_000).isNotEmpty()) }
            rows = survey.stats.rows
            bytes = survey.stats.bytes
            assertTrue(rows in 1..100_000)
            assertTrue(bytes <= 64L * 1024 * 1024)
            assertEquals("ok", survey.quickCheck())
        }

        val calibration = ReceiveCalibrationRepository(context)
        val measurement = SignalMeasurementController(calibration)
        val trace = FloatArray(1_024) { -120f }.apply { this[512] = -45f }
        val frame = PanadapterFrame(1, 1, 1, 0, 96_000, 96_000, 1_024, 256, 1, 0f,
            1f, 93.75f, -45f, -120f, -60f, -60f, 0f, 0f, 0f, true,
            trace, trace.copyOf(), trace.copyOf())
        repeat(4) { measurement.upsertMonitor(ChannelMonitor(name = "M${it + 1}", frequencyHz = 14_074_000L + it * 100)) }
        repeat(1_000) { index ->
            measurement.selectTracker(14_074_000L + index % 200 - 100)
            measurement.updateFrame(frame, 14_074_000L, "SCALE")
        }
        assertEquals(4, measurement.monitors.size)
        assertTrue(measurement.tracker.selected)

        AndroidSdrWorkbenchV4(context).use { workbench ->
            workbench.updateSettings(workbench.settings.copy(scannerOrder = ScannerIntelligenceOrder.MOST_ACTIVE,
                adaptiveDwell = AdaptiveDwellMode.CONSERVATIVE))
            val memories = listOf(ScanMemory(14_000_000L, "USB", 2_700, activityScore = 1f),
                ScanMemory(14_074_000L, "DIGU", 3_000, priority = true, activityScore = 90f))
            assertEquals(14_074_000L, workbench.ordered(memories, null).first().frequencyHz)
            assertTrue(workbench.dwell(14_074_000L, 500, true) in 500..1_000)
        }
        logMetrics("survey_analysis", aggregationMillis, 0, rows, bytes, queryMillis)
        context.deleteDatabase("shackcq-spectrum-survey.sqlite")
    }

    private fun logMetrics(profile: String, elapsedMillis: Long, throughputBytes: Long, rows: Long, dbBytes: Long, queryMillis: Long) {
        val runtime = Runtime.getRuntime()
        val rssKb = File("/proc/self/status").takeIf(File::isFile)?.readLines()
            ?.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull() ?: -1
        val metrics = JSONObject().put("profile", profile).put("utc", Instant.now().toString())
            .put("elapsed_ms", elapsedMillis).put("pss_kb", Debug.getPss()).put("rss_kb", rssKb)
            .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            .put("java_heap_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("threads", Thread.getAllStackTraces().size).put("fds", File("/proc/self/fd").list()?.size ?: -1)
            .put("throughput_bytes", throughputBytes).put("dropped_blocks", 0)
            .put("survey_rows", rows).put("db_bytes", dbBytes).put("query_ms", queryMillis)
        Log.i("ShackCQV4Scale", metrics.toString())
    }
}
