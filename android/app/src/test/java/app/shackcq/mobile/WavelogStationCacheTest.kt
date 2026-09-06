package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class WavelogStationCacheTest {
    @Test fun publicOperatorDefaultsMatchTheConfiguredShackCQProfile() {
        assertEquals("OM0RX", ShackCQDefaults.OPERATOR_CALLSIGN)
        assertEquals("Oliver Bross", ShackCQDefaults.OPERATOR_NAME)
        assertEquals("JN88TQ", ShackCQDefaults.OPERATOR_GRID)
        assertEquals("cluster.om0rx.com", ShackCQDefaults.CLUSTER_HOST)
        assertEquals(7300, ShackCQDefaults.CLUSTER_PORT)
        assertEquals("OM0JRX", ShackCQDefaults.CLUSTER_LOGIN)
        assertEquals("https://om0rx.wavelog.online/index.php", ShackCQDefaults.WAVELOG_BASE_URL)
        assertEquals("OM0RX", ShackCQDefaults.QRZ_USERNAME)
    }
}
