package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogStationCacheInstrumentedTest {
    @Test fun stationMetadataRoundTripsForOfflineRestore() {
        val station = AndroidWavelogStation("7", "Home", "OM0RX", "JN88TQ", "Bratislava",
            "Slovak Republic", "504", "15", "28", "", "", "", "", "", true)

        assertEquals(listOf(station), decodeWavelogStations(encodeWavelogStations(listOf(station))))
    }

    @Test fun invalidStationCacheFailsClosed() {
        assertTrue(decodeWavelogStations("not-json").isEmpty())
    }
}
