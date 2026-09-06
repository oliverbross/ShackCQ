package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFeedbackTest {
    private val live = RadioState(connected = true, revision = 1)

    @Test fun ignoresVfoAndMeterTraffic() {
        assertNull(detectRadioFeedback(live, live.copy(frequencyHz = 14_074_120, frequencyBHz = 7_030_000,
            meter = 8, swrTenths = 13, rfOutputTenths = 42, revision = 2)))
    }

    @Test fun reportsKeyerSpeedImmediatelyInOperatorUnits() {
        assertEquals(RadioFeedback("KEYER SPEED", "24 WPM"),
            detectRadioFeedback(live.copy(keyerSpeed = 20), live.copy(keyerSpeed = 24, revision = 2)))
    }

    @Test fun reportsControlsAndToggleState() {
        assertEquals(RadioFeedback("AF GAIN", "96 / 255"),
            detectRadioFeedback(live.copy(afGain = 80), live.copy(afGain = 96, revision = 2)))
        assertEquals(RadioFeedback("RIT", "ON"),
            detectRadioFeedback(live.copy(rit = false), live.copy(rit = true, revision = 2)))
    }

    @Test fun groupsModeWithItsCoupledFilterState() {
        val previous = live.copy(mode = "USB", bandwidthHz = 2_700, ifShiftHz = 1_500)
        val current = live.copy(mode = "CW", bandwidthHz = 500, ifShiftHz = 700, revision = 2)
        assertEquals(RadioFeedback("MODE", "CW", listOf("FILTER 500 Hz", "SHIFT 700 Hz")),
            detectRadioFeedback(previous, current))
    }

    @Test fun modeRemainsPrimaryWhenItsDependentFramesArriveTogether() {
        val previous = live.copy(mode = "USB", bandwidthHz = 2_700, ifShiftHz = 1_500)
        val current = live.copy(mode = "CW", bandwidthHz = 400, ifShiftHz = 650, agcMode = 2, revision = 2)
        assertEquals(RadioFeedback("MODE", "CW", listOf("FILTER 400 Hz", "SHIFT 650 Hz", "AGC FAST")),
            detectRadioFeedback(previous, current))
    }

    @Test fun laterCatFramesMergeIntoOneModeFeedbackBurst() {
        val start = live.copy(mode = "USB", bandwidthHz = 2_700, ifShiftHz = 1_500)
        val modeFrame = start.copy(mode = "CW", revision = 2)
        val first = mergeRadioFeedbackBurst(null, start, modeFrame)!!
        val filterFrames = modeFrame.copy(bandwidthHz = 400, ifShiftHz = 650, revision = 3)
        val merged = mergeRadioFeedbackBurst(first.baseline, modeFrame, filterFrames)!!
        assertEquals(RadioFeedback("MODE", "CW", listOf("FILTER 400 Hz", "SHIFT 650 Hz")), merged.feedback)
        assertNull(mergeRadioFeedbackBurst(first.baseline, filterFrames, filterFrames.copy(revision = 4)))
    }

    @Test fun doesNotAnnounceInitialConnectionSnapshot() {
        assertNull(detectRadioFeedback(RadioState(), live.copy(keyerSpeed = 24, revision = 2)))
    }
}
