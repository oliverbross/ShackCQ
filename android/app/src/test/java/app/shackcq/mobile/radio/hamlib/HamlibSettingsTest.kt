package app.shackcq.mobile.radio.hamlib

import org.junit.Assert.*
import org.junit.Test

class HamlibSettingsTest {
    private val serial = HamlibSerialProfile("usb:1234:5678:serial", 115200)
    private val network = HamlibNetworkProfile("127.0.0.1", 4532, enabled = true)

    @Test fun serialProfileRoundTrips() {
        val value = HamlibSettingsDocument(listOf(HamlibSavedProfile("QMX", 2044, serial = serial)))
        assertEquals(serial, HamlibSettingsDocument.parse(value.toJson()).profiles.single().serial)
    }
    @Test fun networkProfileRoundTrips() {
        val value = HamlibSettingsDocument(listOf(HamlibSavedProfile("Proxy", 1, network = network)))
        assertEquals(network, HamlibSettingsDocument.parse(value.toJson()).profiles.single().network)
    }
    @Test fun readOnlyDefaultsTrue() { assertTrue(HamlibSavedProfile("Safe", 1, serial = serial).readOnly) }
    @Test fun networkDefaultsDisabled() { assertFalse(HamlibNetworkProfile("localhost", 4532).enabled) }
    @Test fun favoritesRoundTrip() {
        assertEquals(setOf(1, 2), HamlibSettingsDocument.parse(HamlibSettingsDocument(favoriteModelIds = setOf(2, 1)).toJson()).favoriteModelIds)
    }
    @Test fun recentsAreBoundedAndDistinct() {
        val parsed = HamlibSettingsDocument.parse(HamlibSettingsDocument(recentModelIds = listOf(1, 1) + (2..30)).toJson())
        assertEquals(20, parsed.recentModelIds.size); assertEquals(parsed.recentModelIds.distinct(), parsed.recentModelIds)
    }
    @Test fun rejectsAmbiguousTransport() {
        assertThrows(IllegalArgumentException::class.java) { HamlibSavedProfile("Bad", 1, serial, network) }
    }
    @Test fun rejectsUnsafePollRate() {
        assertThrows(IllegalArgumentException::class.java) { HamlibSavedProfile("Fast", 1, serial = serial, pollIntervalMs = 1) }
    }
    @Test fun settingsExcludeTransmitState() {
        val json = HamlibSettingsDocument(listOf(HamlibSavedProfile("Safe", 1, serial = serial))).toJson().lowercase()
        assertFalse(json.contains("ptt")); assertFalse(json.contains("tune")); assertFalse(json.contains("armed"))
    }
}
