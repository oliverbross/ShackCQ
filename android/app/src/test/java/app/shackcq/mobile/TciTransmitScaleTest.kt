// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TciTransmitScaleTest {
    @Test fun deterministicThirtyMinuteAndThousandCycleProfileStaysBounded() = runBlocking {
        var now = 1_000L
        lateinit var authority: TciTransmitAuthority
        class ScaleAdapter : TciTransmitAdapter {
            override val deviceIdentity = "debug:no-radio|scale"
            override val isDemoNoRadio = true
            var transmitting = false; var tuning = false; var connected = true
            var swr = 1.2; var frames = 0L; var textWrites = 0L
            override fun readback() = TciTxReadback(connected, transmitting, tuning, true, 0,
                14_074_000, 14_074_000, "A", false, false, 0, "DIGU", null,
                20, 5, if (transmitting || tuning) 10.0 else 0.0, 11.0, 0.2, swr, .2,
                48_000, true, 0, textWrites)
            override fun sendText(command: String): Boolean {
                textWrites++
                if (command.contains("trx:0,true")) { transmitting = true; authority.onChrono(2_048) }
                if (command.contains("tune:0,true")) tuning = true
                if (command.contains("trx:0,false")) transmitting = false
                if (command.contains("tune:0,false")) tuning = false
                return connected
            }
            override fun sendBinary(frame: ByteArray): Boolean {
                frames++; authority.onChrono(2_048); return connected && frame.size == 8_256
            }
        }
        val adapter = ScaleAdapter()
        authority = TciTransmitAuthority(
            frameBuilder = TciAudioFrameBuilder { _, _, _, _, _, requested, _ -> ByteArray(64 + requested * 4) },
            commandBuilder = TciCommandBuilder { kind, receiver, _, number, _ -> when (kind) {
                NativeTci.TRX -> "trx:$receiver,${if (number != 0L) "true,tci" else "false"};"
                NativeTci.TUNE -> "tune:$receiver,${number != 0L};"
                NativeTci.SAFE_STOP -> "trx:$receiver,false;tune:$receiver,false;"
                else -> "command:$kind,$receiver,$number;"
            } },
            nowMillis = { now }, pause = { now += it },
        )
        authority.attach("scale", adapter.deviceIdentity, TciAcceptanceState.UNVERIFIED, null, adapter,
            TciTxSettings(maxTuneDurationMillis = 500))
        // Initialise the shared coroutine IO dispatcher before the thread
        // baseline. Its lazy JVM workers belong to kotlinx.coroutines, not
        // to a transmit session and must not be misclassified as a leak.
        assertTrue(authority.requestRxAndRecheck())
        val memoryBefore = retainedHeapBytes()
        val threadsBefore = Thread.getAllStackTraces().size
        val fdsBefore = fdCount()
        val rssBefore = rssKiB()
        val second = FloatArray(48_000) { .1F }
        repeat(1_800) {
            assertTrue(authority.transmit(TciTxIntent("Scale", TciTxSource.DEBUG_BENCH, "FT8", second, 48_000, 0, 14_074_000)))
        }
        val short = FloatArray(480) { .1F }
        repeat(1_000) {
            assertTrue(authority.transmit(TciTxIntent("PTT cycle", TciTxSource.DEBUG_BENCH, "FT8", short, 48_000, 0, 14_074_000)))
        }
        repeat(1_000) { assertTrue(!authority.tune("Tune cycle", 0)) }
        repeat(1_000) {
            adapter.swr = 5.0
            assertTrue(!authority.transmit(TciTxIntent("Abort cycle", TciTxSource.DEBUG_BENCH, "FT8", short, 48_000, 0, 14_074_000)))
            adapter.swr = 1.2
        }
        val memoryAfter = retainedHeapBytes()
        println("TCI_SCALE retained_heap_before=$memoryBefore retained_heap_after=$memoryAfter " +
            "threads_before=$threadsBefore threads_after=${Thread.getAllStackTraces().size} " +
            "frames=${adapter.frames} queue=${authority.snapshot.queueDepth} state=${authority.snapshot.state}")
        assertTrue("heap growth must remain bounded", memoryAfter - memoryBefore < 64L * 1024 * 1024)
        // Dispatchers.IO may lazily add up to the coroutine scheduler's small
        // JVM worker floor while this synchronous scale profile runs.
        assertTrue("thread growth must remain bounded", Thread.getAllStackTraces().size - threadsBefore <= 8)
        assertTrue(authority.snapshot.queueDepth <= 8)
        assertEquals(TciTxMachineState.RX_IDLE, authority.snapshot.state)
        assertTrue(adapter.frames >= 85_000)
        println("TCI_SCALE simulated_seconds=1800 ptt_cycles=1000 tune_cycles=1000 abort_cycles=1000 " +
            "frames=${adapter.frames} heap_before=$memoryBefore heap_after=$memoryAfter " +
            "rss_kib_before=$rssBefore rss_kib_after=${rssKiB()} threads_before=$threadsBefore " +
            "threads_after=${Thread.getAllStackTraces().size} fds_before=$fdsBefore fds_after=${fdCount()} " +
            "queue=${authority.snapshot.queueDepth} underruns=${authority.snapshot.underruns} " +
            "overruns=${authority.snapshot.overruns} ptt_ms=${authority.snapshot.pttLatencyMillis} " +
            "rx_ms=${authority.snapshot.rxRecoveryLatencyMillis} jitter_ms=${authority.snapshot.frameJitterMillis}")
    }

    private fun retainedHeapBytes(): Long {
        System.gc()
        System.runFinalization()
        System.gc()
        Thread.sleep(100)
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    }

    private fun fdCount(): Long = sequenceOf(Path.of("/proc/self/fd"), Path.of("/dev/fd"))
        .firstOrNull(Files::isDirectory)?.let { runCatching { Files.list(it).use { rows -> rows.count() } }.getOrDefault(-1) } ?: -1

    private fun rssKiB(): Long = runCatching {
        ProcessBuilder("sh", "-c", "ps -o rss= -p \$PPID")
            .start().inputStream.bufferedReader().readText().trim().toLong()
    }.getOrDefault(-1)
}
