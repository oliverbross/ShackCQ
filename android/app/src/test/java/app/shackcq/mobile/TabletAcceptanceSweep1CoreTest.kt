package app.shackcq.mobile

import app.shackcq.mobile.groupsio.groupsIoTimestampText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TabletAcceptanceSweep1CoreTest {
    @Test fun parallelPortableProviderFailureIsCapturedWithoutCancellingTheCaller() = runBlocking {
        var siblingCompleted = false
        val result = capturePortableProviderPair(
            first = { throw java.net.UnknownHostException("offline fixture") },
            second = { siblingCompleted = true; "agenda" },
        )

        assertTrue(result.isFailure)
        assertTrue(siblingCompleted)
        assertTrue(result.exceptionOrNull() is java.net.UnknownHostException)
    }

    @Test fun sotaClusterLineParsesFrequencyReferenceUtcAndUnknownModeTruthfully() {
        val now = Instant.parse("2026-08-23T15:10:00Z").epochSecond
        val spot = requireNotNull(parseSotaClusterLine(
            "DX de RBNHOLE: 14062.1 HB9BAB/P HB/ZH-015 1503Z",
            now = now,
        ))

        assertEquals("HB9BAB/P", spot.callsign)
        assertEquals(14_062_100L, spot.frequencyHz)
        assertEquals("HB/ZH-015", spot.primary.code)
        assertEquals("UNKNOWN", spot.mode)
        assertEquals("RBNHOLE", spot.spotter)
        assertEquals(now - 7 * 60, spot.spottedAt)
        assertEquals(spot.spottedAt + 3_600, spot.expiresAt)
    }

    @Test fun sotaClusterUsesPreviousUtcDayAcrossMidnightAndOnlyExplicitModeText() {
        val now = Instant.parse("2026-08-23T00:02:00Z").epochSecond
        val spot = requireNotNull(parseSotaClusterLine(
            "DX de M1TJM: 7112.0 M1TJM/P G/TW-004 CW 2359Z",
            now = now,
        ))

        assertEquals(Instant.parse("2026-08-22T23:59:00Z").epochSecond, spot.spottedAt)
        assertEquals("CW", spot.mode)
        assertFalse(spot.invalid)
    }

    @Test fun sotaClusterEnrichesMapCoordinatesFromCatalogueAndRejectsNonSpotText() {
        val summit = SotaSummit(
            "EA1/CT-089", "EA1", "CT", "Test summit", 1_234, 4_049, 8, 0,
            43.1, -4.2, "IN83", "2020-01-01", "",
        )
        val spot = requireNotNull(parseSotaClusterLine(
            "DX de EB2DJB: 7160.0 EB2DJB/P EA1/CT-089 1424Z",
            mapOf(summit.code to summit),
            Instant.parse("2026-08-23T14:30:00Z").epochSecond,
        ))

        assertEquals("Test summit", spot.primary.name)
        assertEquals(43.1, spot.latitude ?: 0.0, 0.0)
        assertEquals(-4.2, spot.longitude ?: 0.0, 0.0)
        assertNull(parseSotaClusterLine("Welcome to the GM4LLD SOTA Cluster"))
    }

    @Test fun sharedSpotStatusGoldenMappingHonoursEveryDefaultAndConfiguredOverride() {
        defaultSpotStatusColours.forEach { (key, expected) ->
            val (dimension, status) = key.split(':', limit = 2)
            assertEquals(expected, resolveSpotStatusColour(emptyMap(), dimension, status))
        }
        assertEquals(0xFF010203.toInt(), resolveSpotStatusColour(
            mapOf("$SPOT_STATUS_DS:ATNO" to 0xFF010203.toInt()), SPOT_STATUS_DS, "ATNO",
        ))
        assertEquals(0xFFF4F0E7.toInt(), resolveSpotStatusColour(emptyMap(), SPOT_STATUS_CS, null))
    }

    @Test fun portableBrowserEnablesScriptOnlyForReviewedExactHosts() {
        assertTrue(requireNotNull(inAppBrowserPolicy("https://pota.app/#/parks")).javaScript)
        assertTrue(requireNotNull(inAppBrowserPolicy("https://www.sotadata.org.uk/en/summit/G/LD-001")).domStorage)
        assertFalse(requireNotNull(inAppBrowserPolicy("https://sotadata.org.uk.evil.example/")).javaScript)
        assertFalse(requireNotNull(inAppBrowserPolicy("https://example.org/")).domStorage)
        assertNull(inAppBrowserPolicy("https://user@example.org/"))
        assertNull(inAppBrowserPolicy("http://sotadata.org.uk/"))
    }

    @Test fun activationRadiusIsInclusiveDeduplicatedCrossBorderAndProgramFiltered() {
        val origin = GeoPoint(48.15, 17.10)
        fun row(program: String, reference: String, name: String, point: GeoPoint) =
            ActivationCatalogReference(program, reference, name, "", point, true, "fixture")
        val rows = listOf(
            row("SOTA", "OM/BA-001", "Slovakia", origin),
            row("SOTA", "OK/JM-001", "Czechia", GeoPoint(49.0, 16.6)),
            row("POTA", "AT-0001", "Austria", GeoPoint(48.2, 16.37)),
            row("POTA", "HU-0001", "Hungary", GeoPoint(47.5, 19.0)),
            row("POTA", "AT-0001", "duplicate", GeoPoint(48.2, 16.37)),
            row("WWFF", "OMFF-0001", "Unavailable programme fixture", origin),
        )

        val found = nearbyActivationReferences(
            origin, 250.0, rows, setOf("POTA", "SOTA"), ActivationCatalogSort.REFERENCE,
        )
        assertEquals(listOf("AT-0001", "HU-0001", "OK/JM-001", "OM/BA-001"), found.map { it.reference })
        assertTrue(found.all { it.distanceKm <= 250.0 })
        assertEquals(listOf("OM/BA-001"), nearbyActivationReferences(
            origin, 0.0, rows, setOf("SOTA"), ActivationCatalogSort.DISTANCE,
        ).map { it.reference })
    }

    @Test fun groupsIoTimestampsUseServerEpochWithLocalUtcAndUnknownStates() {
        val epoch = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
        val text = groupsIoTimestampText(epoch, epoch + 90 * 60_000, ZoneOffset.ofHours(2))

        assertTrue(text.row.startsWith("2026-08-23 14:00"))
        assertTrue(text.row.endsWith("1h ago"))
        assertTrue("2026-08-23 12:00:00 UTC" in text.detail)
        assertEquals("TIME UNKNOWN", groupsIoTimestampText(0).row)
    }
}
