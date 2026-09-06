package app.shackcq.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureWorkedLogDecodingTest {
    @Test fun missingWorkedLogAndCompletenessDecodeAsNotLoaded() {
        assertEquals(AndroidWorkedLog(), decodeWorkedLog(JSONObject()))
        val spot = decodeDxSpot(JSONObject("""{
            "callsign":"JA1ABC","frequencyHz":14074000,"receivedEpoch":1,
            "reason":"NEW ENTITY IN LOGBOOK"
        }"""))
        assertFalse(spot.workedIndexComplete)
        assertEquals("FRESH CLUSTER ACTIVITY", spot.reason)
    }

    @Test fun completeWorkedLogAndOpportunityDecode() {
        val root = JSONObject("""{
            "workedLog":{"loaded":true,"complete":true,"cells":12,"records":15,
                "accepted":14,"rejected":1,"truncated":0}
        }""")
        val state = decodeWorkedLog(root)
        assertTrue(state.loaded)
        assertTrue(state.complete)
        assertEquals(12, state.cells)
        assertEquals(1, state.rejected)

        val spot = decodeDxSpot(JSONObject("""{
            "callsign":"JA1ABC","frequencyHz":14074000,"receivedEpoch":1,
            "workedIndexComplete":true,"reason":"NEW ENTITY IN LOGBOOK"
        }"""))
        assertTrue(spot.workedIndexComplete)
        assertEquals("NEW ENTITY IN LOGBOOK", spot.reason)
    }
}
