package app.shackcq.mobile

import app.shackcq.mobile.hamclock.*
import app.shackcq.mobile.hamclock.finishline.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HamClockFinishLineTest {
    @Test fun finishLineSettingsRoundTripThroughProfiles() {
        val settings = HamClockUserSettings(
            propagation = HamClockPropagationPreference(txPowerWatts = 500, txGainDb = 6.0,
                noiseEnvironment = HamClockNoiseEnvironment.QUIET_RURAL, longPath = true, coverageResolution = 720),
            idReminder = HamClockIdReminderPreference(enabled = true, intervalMinutes = 15, running = true,
                lastResetEpochSeconds = 1_700_000_000),
            shackDisplay = HamClockShackDisplayPreference(HamClockShackTheme.RED_NIGHT, true, true, 60, "field", true),
        )
        val document = HamClockSettingsDocument(settings = settings, profiles = listOf(
            HamClockNamedProfile("field", "Field", settings, 1, 2)))
        assertEquals(HamClockSettingsCodec.normalize(document),
            HamClockSettingsCodec.decode(HamClockSettingsCodec.encode(document)))
    }

    @Test fun malformedFinishLineSettingsAreBounded() {
        val normalized = HamClockSettingsCodec.normalizeSettings(HamClockUserSettings(
            propagation = HamClockPropagationPreference(txPowerWatts = -1, txGainDb = Double.NaN,
                selectedFrequenciesMHz = listOf(-1.0, 14.1, 99.0), coverageResolution = 999),
            idReminder = HamClockIdReminderPreference(intervalMinutes = 99, lastResetEpochSeconds = -1),
            shackDisplay = HamClockShackDisplayPreference(rotationSeconds = 3),
        ))
        assertEquals(1, normalized.propagation.txPowerWatts)
        assertEquals(listOf(14.1), normalized.propagation.selectedFrequenciesMHz)
        assertEquals(288, normalized.propagation.coverageResolution)
        assertEquals(10, normalized.idReminder.intervalMinutes)
        assertEquals(30, normalized.shackDisplay.rotationSeconds)
    }

    @Test fun noaaSpaceWeatherParserKeepsIndependentMeasurements() {
        val root = JSONObject()
            .put("plasma", JSONArray().put(JSONObject().put("time_tag", "2026-08-21T01:00:00Z").put("proton_speed", 512.5)))
            .put("magnetic", JSONArray().put(JSONObject().put("time_tag", "2026-08-21T01:00:00Z").put("bz_gsm", -4.2)))
            .put("protons", JSONArray().put(JSONObject().put("time_tag", "2026-08-21T01:00:00Z").put("energy", ">=10 MeV").put("flux", 12.0)))
            .put("alerts", JSONArray().put(JSONObject().put("message", "Space Weather Message\nDetails")))
        val result = parseSpaceWeather(root, 1_700_000_000)
        assertEquals(512.5, result.solarWindSpeedKmS!!, 0.01)
        assertEquals(-4.2, result.imfBzNt!!, 0.01)
        assertEquals("S1", result.radiationState)
        assertEquals(listOf("Space Weather Message"), result.alerts)
    }

    @Test fun auroraParserCapsAndValidatesGeometry() {
        val coordinates = JSONArray()
        repeat(2_000) { index -> coordinates.put(JSONArray(listOf((index % 360).toDouble(), 65.0, (index % 100) + 1))) }
        val parsed = parseAurora(JSONObject().put("coordinates", coordinates)
            .put("Forecast Time", "2026-08-21T01:00:00Z").put("Observation Time", "2026-08-21T00:30:00Z"), 1)
        assertTrue(parsed.cells.size <= 720)
        assertTrue(parsed.cells.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.probability in 1..100 })
    }

    @Test fun idTimerRestoresAndDetectsClockChange() {
        val runtime = HamClockIdTimerRuntime()
        val preference = HamClockIdReminderPreference(enabled = true, running = true, intervalMinutes = 10,
            lastResetEpochSeconds = 1_000)
        assertEquals(500, runtime.snapshot(preference, 1_100, 10_000).remainingSeconds)
        assertTrue(runtime.snapshot(preference, 1_200, 11_000).clockChanged)
        assertTrue(runtime.snapshot(preference, 1_700, 12_000).due)
    }

    @Test fun satelliteGeometryIsBoundedAndDatelineSafe() {
        val ring = satelliteFootprint(0.0, 179.5, 500.0)
        assertEquals(49, ring.size)
        assertTrue(ring.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        val segments = splitSatelliteTrack(listOf(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0),
            GeoPoint(0.0, -170.0)))
        assertTrue(segments.flatten().zipWithNext().all { (a, b) -> kotlin.math.abs(a.longitude - b.longitude) <= 180.0 })
    }

}
