package app.shackcq.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class RfWorldGeometryTest {
    @Test fun naturalEarthLandContoursAreBoundedAndDetailedEnoughForTheOfflineGlobe() {
        val points = rfWorldLandContours.flatten()

        assertTrue(rfWorldLandContours.size >= 60)
        assertTrue(points.size >= 650)
        assertTrue(points.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
        assertTrue(points.any { it.latitude in -45.0..-10.0 && it.longitude in 110.0..155.0 })
    }
}
