package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HamClockPropagationTest {
    @Test fun parsesModelTruthMufLufAndBands() {
        val snapshot = parseHamClockPropagation(
            """{"model":"Built-in estimation","muf":14.5,"luf":3.5,"distance":6962,
                |"dataSource":"Estimated from solar indices","iturhfprop":{"available":false},
                |"currentBands":[{"band":"20m","freq":14.0,"reliability":33,"snr":"-10dB","status":"FAIR"}]}""".trimMargin(), 1234)
        assertTrue(snapshot.available)
        assertFalse(snapshot.authoritative)
        assertEquals("Built-in estimation", snapshot.model)
        assertEquals(14.5, snapshot.mufMHz!!, 0.0)
        assertEquals(3.5, snapshot.lufMHz!!, 0.0)
        assertEquals(33, snapshot.bands.single().reliability)
    }

    @Test fun identifiesP533ResultWithoutRelabellingFallback() {
        val snapshot = parseHamClockPropagation(
            """{"model":"ITU-R P.533-14","engine":"wasm-p533","currentBands":[{"band":"20m","freq":14.1,"reliability":82,"snr":"20dB","status":"GOOD"}]}""", 1)
        assertTrue(snapshot.authoritative)
    }
}
