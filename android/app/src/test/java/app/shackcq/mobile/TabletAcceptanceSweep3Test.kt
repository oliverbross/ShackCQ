// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.compose.ui.graphics.Color
import app.shackcq.mobile.bandmap.BandMapFilter
import app.shackcq.mobile.bandmap.BandMapFilterEngine
import app.shackcq.mobile.bandmap.BandMapSource
import app.shackcq.mobile.bandmap.BandMapSourceAdapters
import app.shackcq.mobile.bandmap.BandMapSpotIndex
import app.shackcq.mobile.contest.highlightScpCallsign
import app.shackcq.mobile.rotator.*
import org.junit.Assert.*
import org.junit.Test

class TabletAcceptanceSweep3Test {
    private val now = 2_000_000L

    @Test fun clusterProductionAdapterFeedsCanonicalBandMapRepository() {
        val row = AndroidDXSpot("fixture", "OM0RX", "W1AW", 14_074_000, now, "20m", "FT8", "Slovakia", "EU",
            15, 28, 48.1, 17.1, "fixture", 1, 1, 1, false, false, false, false, false, false,
            false, 0, 0, "", "", true, "OM")
        val observation = BandMapSourceAdapters.cluster(listOf(row)).single()
        val spot = BandMapSpotIndex().coalesce(listOf(observation)).single()
        assertEquals(setOf(BandMapSource.DX_CLUSTER), spot.sources)
        assertEquals("OM0RX", spot.callsign)
        assertEquals("20m", spot.band)
    }

    @Test fun allFilteredProductionStateHasZeroVisibleRows() {
        val row = AndroidDXSpot("fixture", "OM0RX", "W1AW", 14_074_000, now, "20m", "FT8", "Slovakia", "EU",
            15, 28, 48.1, 17.1, "fixture", 1, 1, 1, false, false, false, false, false, false,
            false, 0, 0, "", "", true, "OM")
        val spot = BandMapSpotIndex().coalesce(BandMapSourceAdapters.cluster(listOf(row))).single()
        assertTrue(BandMapFilterEngine.visible(listOf(spot), BandMapFilter(bands = setOf("40m")), now).isEmpty())
    }

    @Test fun scpPrefixHighlightsOnlyTypedCharacters() {
        val text = highlightScpCallsign("OM0RXX", "OM0RX", Color.Green)
        assertEquals("OM0RXX", text.text)
        assertEquals((0 until 5).map { it to it + 1 }, text.spanStyles.map { it.start to it.end })
    }

    @Test fun scpFuzzyHighlightMarksOnlyActualPositions() {
        val text = highlightScpCallsign("OZ1MOR", "OMR", Color.Green)
        assertEquals(listOf(0 to 1, 3 to 4, 5 to 6), text.spanStyles.map { it.start to it.end })
    }

    @Test fun contactLabelsChooseOneRepresentativePerEntity() {
        val rows = listOf(
            ProgressContactPoint("JN88", 48.0, 17.0, "OM", "Slovakia"),
            ProgressContactPoint("JN98", 48.5, 18.0, "OM", "Slovakia"),
            ProgressContactPoint("JO70", 50.0, 14.0, "OK", "Czechia"),
        )
        val labels = deduplicatedProgressLabelRows(rows)
        assertEquals(2, labels.size)
        assertEquals(setOf("OM", "OK"), labels.map { it.dxcc }.toSet())
    }

    @Test fun rotatorProfileCreationAndDeletionNeverCreatesRuntimeState() {
        val profile = RotatorDeviceProfile(name = "test rotctld", backend = RotatorBackend.REMOTE_ROTCTLD,
            protocol = RotatorProtocolKind.ROTCTLD, transport = RotatorTransportKind.ROTCTLD,
            tcp = TcpSettings("127.0.0.1", 4533))
        val store = RotatorProfileStore()
        store.upsert(profile)
        assertEquals(listOf(profile), store.snapshot().profiles)
        store.delete(profile.id)
        assertTrue(store.snapshot().profiles.isEmpty())
    }

    @Test fun provenanceRegistryHasAuditedClassesAndPins() {
        assertTrue(shackCQProvenance.any { it.name.startsWith("Hamlib") && it.pin.startsWith("40f634") })
        assertTrue(ProvenanceClass.entries.all { classification -> shackCQProvenance.any { it.classification == classification } })
    }

    @Test fun alertProfileValuesAreBoundedIndependentData() {
        val day = AlertDisplayProfile(brightness = 82, autoDim = true, audibleTones = false, quietNonCritical = false)
        val night = AlertDisplayProfile(brightness = 32, autoDim = true, audibleTones = false, quietNonCritical = true)
        assertNotEquals(day, night)
        assertTrue(night.quietNonCritical)
    }
}
