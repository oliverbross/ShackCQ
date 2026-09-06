// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdrWorkbenchV4Test {
    @Test fun channelMemoryJsonAndCsvRoundTripAllValidatedMetadata() {
        val row = ScanMemory(145_500_000, "nfm", 12_500, "Calling", "VHF", 88.5f, 23,
            scanEnabled = true, priority = true, note = "Local, quoted \"note\"", locationGrid = "jn88tq",
            lastHeardEpoch = 123, activityScore = 77f)
        val json = importChannelMemoriesJson(exportChannelMemoriesJson(listOf(row))).single()
        val csv = importChannelMemoriesCsv(exportChannelMemoriesCsv(listOf(row))).single()
        assertEquals(row.validated(), json)
        assertEquals(row.validated(), csv)
        assertEquals("JN88TQ", csv.locationGrid)
    }

    @Test fun scanMemoryAndCalibrationBoundsFailClosed() {
        val memory = ScanMemory(1, "", 1, name = "x".repeat(100), expectedCtcssHz = 999f,
            expectedDcs = 9999, activityScore = 500f).validated()
        assertEquals(100_000, memory.frequencyHz)
        assertEquals("USB", memory.mode)
        assertEquals(50, memory.filterHz)
        assertEquals(300f, memory.expectedCtcssHz)
        assertEquals(777, memory.expectedDcs)
        assertEquals(100f, memory.activityScore)
        val calibration = ReceiveCalibration("TCI", 500f, 999f, 9f, 200f).validated()
        assertEquals(100f, calibration.levelOffsetDb)
        assertEquals(250f, calibration.frequencyCorrectionPpm)
        assertEquals(1.5f, calibration.iqGainCorrection)
        assertEquals(30f, calibration.iqPhaseCorrectionDegrees)
    }

    @Test fun fourMonitorLimitAndTruthStatesNeverInventToneDecode() {
        val rows = (0 until 5).map { ChannelMonitor(name = "M$it", frequencyHz = 14_074_000L + it * 1_000) }
        assertFalse(rows.first().occupied)
        assertEquals("UNDETECTED", rows.first().toneState)
        assertTrue(rows.all { it.activity == ChannelActivityState.UNKNOWN })
    }
}
