package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

class AndroidSdrEnhancementDomainTest {
    @Test fun scanBankRunsBoundedPriorityWatchAndPublishesDwellEvents() = runBlocking {
        val tuned = CopyOnWriteArrayList<Long>()
        val events = CopyOnWriteArrayList<ScannerDwellEvent>()
        val scanner = ReceiveOnlyScannerController(
            tuneReceive = { frequency, _, _ -> tuned += frequency; true },
            signalLevel = { -42f },
            onDwell = events::add,
        )
        val bank = ScanBank("bank", "Priority", memories = listOf(ScanMemory(14_070_000, "DIGU"), ScanMemory(14_071_000, "DIGU")),
            priority = ScanMemory(14_074_000, "DIGU"), dwellMillis = 100)
        scanner.startBank(bank)
        withTimeout(2_000) { while (events.none { it.priority }) delay(25) }
        scanner.stop()
        assertTrue(14_074_000L in tuned)
        assertTrue(events.any { it.priority })
        assertEquals(ScannerState.STOPPED, scanner.snapshot.state)
        scanner.close()
    }

    @Test fun fftCandidatesAreLocalMaximaSortedByStrengthAndBounded() {
        val trace = floatArrayOf(-120f, -50f, -90f, -42f, -80f, -38f, -100f)
        val values = fftCandidates(trace, 14_100_000, 70_000, -60f, 2)
        assertEquals(2, values.size)
        assertTrue(values[0] > values[1])
    }

    @Test fun fftCandidatesRejectInvalidOrFlatInputs() {
        assertTrue(fftCandidates(floatArrayOf(), 0, 0, -80f, 4).isEmpty())
        assertTrue(fftCandidates(floatArrayOf(-40f, -40f, -40f), 14_000_000, 1_000, -80f, 4).isEmpty())
    }

    @Test fun greatCirclePreservesEndpointsAndNormalizesLongitude() {
        val path = greatCircle(48.7, 17.6, -33.9, 151.2, points = 65)
        assertEquals(65, path.size)
        assertTrue(abs(path.first().latitude - 48.7) < 1e-6)
        assertTrue(abs(path.last().latitude - -33.9) < 1e-6)
        assertTrue(path.all { it.longitude in -180.0..180.0 && it.latitude in -90.0..90.0 })
    }

    @Test fun explicitLongPathDiffersFromShortestPath() {
        val short = greatCircle(48.7, 17.6, 40.7, -74.0, longPath = false, points = 17)
        val long = greatCircle(48.7, 17.6, 40.7, -74.0, longPath = true, points = 17)
        assertFalse(abs(short[8].longitude - long[8].longitude) < 1.0)
    }

    @Test fun propagationControlPointsAreBoundedAndAbsentForLocalPaths() {
        val local = observation(48.7, 17.6, 49.0, 18.0)
        val distant = observation(48.7, 17.6, -33.9, 151.2)
        assertTrue(propagationControlPoints(local, false).isEmpty())
        assertTrue(propagationControlPoints(distant, false).size in 1..5)
    }

    @Test fun scannerTunesReceiveOnlyMemoryAndManualTuneStopsIt() = runBlocking {
        val tuned = CopyOnWriteArrayList<Long>()
        val scanner = ReceiveOnlyScannerController(tuneReceive = { frequency, _, _ -> tuned += frequency; true })
        try {
            scanner.updateConfig(ScannerConfig(dwellMillis = 100))
            scanner.startMemory(listOf(
                RadioPreset(0, "A", 7_074_000, "DIGU", 2_700, 0),
                RadioPreset(1, "B", 14_074_000, "DIGU", 2_700, 0),
            ))
            repeat(20) { if (tuned.isNotEmpty()) return@repeat; delay(10) }
            assertTrue(tuned.isNotEmpty())
            scanner.onManualTune()
            assertEquals(ScannerState.STOPPED, scanner.snapshot.state)
            assertEquals("Manual operator tune", scanner.snapshot.stopReason)
        } finally {
            scanner.close()
        }
    }

    @Test fun rangeScannerCapsCandidatesSkipsChannelsAndStopsOnBackground() = runBlocking {
        val tuned = CopyOnWriteArrayList<Long>()
        val scanner = ReceiveOnlyScannerController(tuneReceive = { frequency, _, _ -> tuned += frequency; true })
        try {
            scanner.updateConfig(ScannerConfig(mode = ScannerMode.RANGE, startHz = 1_000_000,
                endHz = 50_000_000, stepHz = 10, dwellMillis = 100, skipHz = setOf(1_000_010)))
            scanner.startRange()
            repeat(20) { if (tuned.isNotEmpty()) return@repeat; delay(10) }
            assertEquals(10_000, scanner.snapshot.candidateCount)
            assertFalse(1_000_010L in tuned)
            scanner.onBackground()
            assertEquals(ScannerState.STOPPED, scanner.snapshot.state)
        } finally {
            scanner.close()
        }
    }

    @Test fun spokenTuningPhraseIsCombinedAndSafetySuppressionsFailClosed() {
        assertEquals("20 metres, USB, 14.074000 megahertz", tuningAnnouncementText(14_074_000, "20 metres", "USB"))
        assertTrue(announcementAllowed(true, true, false, false, false, "ready"))
        assertFalse(announcementAllowed(true, true, true, false, false, "quiet"))
        assertFalse(announcementAllowed(true, true, false, true, false, "tx"))
        assertFalse(announcementAllowed(true, true, false, false, true, "macro"))
        assertFalse(announcementAllowed(false, true, false, false, false, "disabled"))
    }

    @Test fun rfModelCapsAndFiltersOneHundredThousandRowsDeterministically() = runBlocking {
        val now = Instant.now().epochSecond
        val controller = RfObservationController()
        try {
            controller.submit(List(100_000) { index ->
                observation(-12.0, 130.0, -70.0 + index % 140, -179.0 + index % 358)
                    .copy(id = "scale-$index", source = listOf("PSK", "WSPR", "RBN")[index % 3], epoch = now,
                        callsign = "TEST$index", band = listOf("20m", "40m", "15m")[index % 3])
            })
            withTimeout(10_000) { while (controller.filtered.size != 100_000) delay(25) }
            assertEquals(100_000, controller.observations.size)
            assertEquals(100_000, controller.filtered.size)
            controller.updateFilters(controller.filters.copy(sources = setOf("WSPR")))
            withTimeout(10_000) { while (controller.filtered.size != 33_333) delay(25) }
            assertEquals(33_333, controller.filtered.size)
            assertTrue(controller.filtered.all { it.source == "WSPR" })
        } finally {
            controller.close()
        }
    }

    private fun observation(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = RfObservation(
        id = "fixture", source = "TEST", evidence = RfEvidenceClass.OBSERVED, epoch = 1,
        callsign = "TEST", band = "20m", mode = "FT8", transmitterLatitude = aLat,
        transmitterLongitude = aLon, receiverLatitude = bLat, receiverLongitude = bLon,
        precision = RfPrecision.EXACT,
    )
}
