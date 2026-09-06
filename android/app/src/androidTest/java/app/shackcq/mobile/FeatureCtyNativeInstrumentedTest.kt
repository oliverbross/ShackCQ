package app.shackcq.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureCtyNativeInstrumentedTest {
    @Test fun zeroAndRetiredHandlesReturnNeutralValues() {
        NativeCore.destroy(0)
        assertEquals(0, NativeCore.feed(0, byteArrayOf(1)))
        assertEquals("", NativeCore.state(0))
        NativeCore.featureDestroy(0)
        assertFalse(NativeCore.featureLoadCty(0, "ignored"))
        assertFalse(NativeCore.featureClusterLine(0, "ignored", 0))
        assertFalse(NativeCore.featureBeginWorkedSync(0))
        assertFalse(NativeCore.featureEndWorkedSync(0))
        assertEquals(0, JSONObject(NativeCore.featureDxSnapshot(0, 0)).length())
        NativeCore.flexDestroy(0)
        assertEquals(-1, NativeCore.flexFeed(0, byteArrayOf(1)))
        assertEquals(0, JSONObject(NativeCore.flexState(0)).length())
        NativeCore.digiDestroy(0)
        assertEquals(0, NativeCore.digiSstvImage(0).size)
    }

    @Test fun ctyAndWorkedLogControlNativeJapanEntityBonus() {
        val handle = NativeCore.featureCreate()
        try {
            assertTrue(NativeCore.featureLoadCty(handle,
                "Japan: 25: 45: AS: 36.00: -138.00: -9.0: JA: JA;\n"))
            assertTrue(NativeCore.featureBeginWorkedSync(handle))
            assertTrue(NativeCore.featureEndWorkedSync(handle))
            assertTrue(NativeCore.featureClusterLine(handle,
                "DX de VK3ABC:  14075.0  JA2ABC  FT8 -08 dB", 1_720_000_000))

            val unworked = opportunity(NativeCore.featureDxSnapshot(handle, 1_720_000_001), "JA2ABC")
            assertEquals("Japan", unworked.getString("country"))
            assertFalse(unworked.getBoolean("workedCountry"))
            assertEquals("NEW ENTITY IN LOGBOOK", unworked.getString("reason"))
            val unworkedScore = unworked.getInt("score")

            assertTrue(NativeCore.featureBeginWorkedSync(handle))
            assertTrue(NativeCore.featureAddWorkedQso(handle, "JA2ABC", "Japan", "20m",
                "FT8", "", 1_719_999_900, false))
            assertTrue(NativeCore.featureEndWorkedSync(handle))
            val worked = opportunity(NativeCore.featureDxSnapshot(handle, 1_720_000_001), "JA2ABC")
            assertTrue(worked.getBoolean("workedCountry"))
            assertFalse(worked.getString("reason").contains("NEW ENTITY IN LOGBOOK"))
            assertEquals(unworkedScore - 22, worked.getInt("score"))
        } finally { NativeCore.featureDestroy(handle) }
    }

    private fun opportunity(json: String, callsign: String): JSONObject {
        val rows = JSONObject(json).getJSONArray("opportunities")
        return (0 until rows.length()).map(rows::getJSONObject).first { it.getString("callsign") == callsign }
    }
}
