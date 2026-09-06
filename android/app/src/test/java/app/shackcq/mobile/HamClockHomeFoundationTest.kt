package app.shackcq.mobile

import app.shackcq.mobile.hamclock.HamClockDxTarget
import app.shackcq.mobile.hamclock.HamClockDxTargetSource
import app.shackcq.mobile.hamclock.HamClockMapLayerAvailability
import app.shackcq.mobile.hamclock.HamClockMapLayerId
import app.shackcq.mobile.hamclock.HamClockMapPreference
import app.shackcq.mobile.hamclock.HamClockMapSelection
import app.shackcq.mobile.hamclock.HamClockUnitSystem
import app.shackcq.mobile.hamclock.defaultHamClockMapLayers
import app.shackcq.mobile.hamclock.defaultHamClockPanels
import app.shackcq.mobile.hamclock.hamClockMapLayerRegistry
import app.shackcq.mobile.hamclock.hamClockModuleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HamClockHomeFoundationTest {
    @Test
    fun moduleRegistryIsTheCompleteDefaultLayoutAuthority() {
        assertEquals(hamClockModuleRegistry.map { it.id }, defaultHamClockPanels().map { it.id })
        assertEquals(hamClockModuleRegistry.size, hamClockModuleRegistry.map { it.id }.toSet().size)
        hamClockModuleRegistry.zip(defaultHamClockPanels()).forEach { (spec, preference) ->
            assertEquals(spec.defaultVisible, preference.visible)
            assertEquals(spec.defaultColumn, preference.column)
            assertEquals(spec.defaultColumnSpan, preference.columnSpan)
            assertEquals(spec.defaultRowSpan, preference.rowSpan)
        }
    }

    @Test
    fun mapRegistryIsCompleteBoundedAndExplainsUnavailableLayers() {
        assertEquals(hamClockMapLayerRegistry.map { it.id }, defaultHamClockMapLayers().map { it.id })
        assertEquals(hamClockMapLayerRegistry.size, hamClockMapLayerRegistry.map { it.id }.toSet().size)
        assertTrue(hamClockMapLayerRegistry.all { it.maximumObjectCount >= 0 })
        assertTrue(hamClockMapLayerRegistry.filter { it.availability == HamClockMapLayerAvailability.UNAVAILABLE }
            .all { it.unavailableReason.isNotBlank() && it.maximumObjectCount == 0 })
        assertEquals("psk-reporter", hamClockMapLayerRegistry.first { it.id == HamClockMapLayerId.PSK_REPORTER }.upstreamId)
        assertEquals(32, hamClockMapLayerRegistry.first { it.id == HamClockMapLayerId.GRID }.maximumObjectCount)
        assertEquals(HamClockMapLayerAvailability.UNAVAILABLE,
            hamClockMapLayerRegistry.first { it.id == HamClockMapLayerId.MOON }.availability)
    }

    @Test
    fun boundedSnapshotCapsEachLayerAndDropsUnknownLayerObjects() {
        val points = (0 until 240).map {
            HamClockMapPoint(it.toString(), HamClockMapLayerId.DX_SPOTS, "DX$it", "", 0.0, 0.0, "#fff")
        } + HamClockMapPoint("unknown", "future.unknown", "Unknown", "", 0.0, 0.0, "#fff")

        val bounded = boundedHamClockMapSnapshot(HamClockMapSnapshot(points = points))

        assertEquals(160, bounded.points.count { it.layerId == HamClockMapLayerId.DX_SPOTS })
        assertTrue(bounded.points.none { it.layerId == "future.unknown" })
    }

    @Test
    fun lockedManualTargetBlocksAutomaticReplacement() {
        val manual = HamClockDxTarget("VK9MAN", "QF56", -33.0, 151.0, true, HamClockDxTargetSource.MANUAL)
        val automatic = dxSpot("ZL1AUTO", -36.8, 174.7)

        val resolved = resolveHamClockTarget(manual, automatic)

        assertEquals("VK9MAN", resolved?.callsign)
        assertEquals(HamClockDxTargetSource.MANUAL, resolved?.source)
    }

    @Test
    fun unlockedTargetUsesRankedAutomaticGeometry() {
        val manual = HamClockDxTarget("VK9MAN", "QF56", -33.0, 151.0, false, HamClockDxTargetSource.MANUAL)
        val automatic = dxSpot("ZL1AUTO", -36.8, 174.7)

        val resolved = resolveHamClockTarget(manual, automatic)

        assertEquals("ZL1AUTO", resolved?.callsign)
        assertEquals(HamClockDxTargetSource.AUTOMATIC, resolved?.source)
    }

    @Test
    fun featureIdentityUsesBandColoursUnitsAndNeverClaimsMoonGeometry() {
        val spot = dxSpot("ZL1AUTO", -36.8, 174.7)
        val dx = hamClockDxMapPoint(spot)
        assertEquals(spot.id, dx.contextId)
        assertEquals(spot.frequencyHz, dx.frequencyHz)
        assertEquals(HamClockMapSelection.DX_SPOT, dx.selection)
        assertEquals("ZL1AUTO", dx.callsign)
        assertEquals(hamClockBandColor("20m"), dx.color)
        assertEquals("#f08ba7", hamClockBandColor("2m"))
        assertEquals("#c481d8", hamClockBandColor("70cm"))
        assertEquals("#f4c94e", hamClockBandColor("sat"))
        assertEquals("260 mi", hamClockDistanceLabel(420.0, HamClockUnitSystem.IMPERIAL))
        assertEquals(HamClockMapLayerAvailability.UNAVAILABLE,
            hamClockMapLayerRegistry.first { it.id == HamClockMapLayerId.MOON }.availability)
    }

    @Test
    fun delayedGestureCameraMergesLatestNonCameraSettingsButRejectsNewerCamera() {
        val latest = HamClockMapPreference(layers = defaultHamClockMapLayers().map { it.copy(visible = false) })
        val merged = mergeGestureCameraPreference(latest, 0.0, 0.0, 1.2, 12.0, 30.0, 4.0)
        assertEquals(latest.layers, merged?.layers)
        assertEquals(12.0, merged?.centerLatitude ?: 0.0, 0.0)
        assertEquals(null, mergeGestureCameraPreference(latest.copy(centerLatitude = 44.0),
            0.0, 0.0, 1.2, 12.0, 30.0, 4.0))
    }

    @Test
    fun noaaSunspotParserUsesLatestValidObservedValue() {
        val parsed = parseLatestNoaaSunspot("""[
            {"time-tag":"2026-05","observed_swpc_ssn":82.0},
            {"time-tag":"bad","observed_swpc_ssn":999.0},
            {"time-tag":"2026-06","observed_swpc_ssn":79.5}
        ]""")
        assertEquals(79.5f, parsed.first)
        assertEquals("2026-06", parsed.second)
    }

    private fun dxSpot(call: String, latitude: Double, longitude: Double) = AndroidDXSpot(
        id = call, callsign = call, spotter = "TEST", frequencyHz = 14_074_000,
        receivedEpoch = 1, band = "20m", mode = "FT8", country = "New Zealand", continent = "OC",
        cqZone = 32, ituZone = 60, latitude = latitude, longitude = longitude, comment = "",
        score = 90, confidence = 90, samples = 1, watchlisted = false,
        workedCountry = false, workedCall = false, workedBand = false, workedMode = false,
        workedBandMode = false, recentDupe = false, distanceKm = 0, bearingDegrees = 0,
        pathState = "", reason = "test",
    )
}
