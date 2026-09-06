package app.shackcq.mobile

import app.shackcq.mobile.hamclock.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class Task2B2RfEvidenceTest {
    @Test fun rbnParserPreservesSkimmerDxFrequencyModeSnrSpeedAndFlags() {
        val row = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 VK9DX CW 23 dB 31 WPM CQ", 1_000))
        assertEquals("K1ABC-#", row.skimmerCall); assertEquals("VK9DX", row.dxCall)
        assertEquals(14_025_300, row.frequencyHz); assertEquals("20m", row.band); assertEquals("CW", row.mode)
        assertEquals(23, row.snr); assertEquals(31, row.wpm); assertTrue(row.cq); assertFalse(row.test)
    }

    @Test fun genericClusterLineIsNotMislabelledAsRbn() {
        assertEquals(null, parseRbnClusterLine("DX de W1AW: 14074.0 VK3ABC FT8 -12 dB", 1_000))
    }

    @Test fun rbnTrailingUtcTimestampUsesLineTimeWithMidnightRollover() {
        val now = Instant.parse("2026-08-20T00:02:00Z").epochSecond
        val row = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 VK9DX CW 12 dB 2359Z", now))
        assertEquals(Instant.parse("2026-08-19T23:59:00Z").epochSecond, row.observedEpoch)
        assertEquals(now, row.receivedEpoch)
    }

    @Test fun rbnPolicyFiltersDeduplicatesAndCaps() {
        val base = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 VK9DX CW 23 dB 31 WPM CQ", 1_000))
        val duplicate = base.copy(id = "new", observedEpoch = 1_001)
        val other = base.copy(id = "other", dxCall = "W1AW", observedEpoch = 1_002)
        val preference = HamClockRbnPreference(windowMinutes = 2, maximumRows = 1, bands = setOf("20M"),
            modes = setOf("CW"), minimumSnr = 20, dxCall = "VK")
        assertEquals(listOf("new"), boundedRbnObservations(listOf(base, duplicate, other), preference, emptySet(), 1_010).map { it.id })
    }

    @Test fun rbnTypedViewsSelectDxSkimmerWatchlistAndAll() {
        val dx = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 VK9DX CW 20 dB", 1_000))
        val other = dx.copy(id = "other", skimmerCall = "W3LPL-#", dxCall = "W1AW")
        fun rows(mode: HamClockRbnMode, skimmer: String = "", call: String = "", stationCall: String = "") = boundedRbnObservations(
            listOf(dx, other), HamClockRbnPreference(viewMode = mode, skimmerCall = skimmer, dxCall = call),
            setOf("W1AW"), stationCall, 1_010).map { it.dxCall }.toSet()
        assertEquals(setOf("VK9DX"), rows(HamClockRbnMode.WHO_HEARS_ME, stationCall = "EA8/VK9DX/P"))
        assertEquals(setOf("W1AW"), rows(HamClockRbnMode.SKIMMER_VIEW, skimmer = "W3LPL"))
        assertEquals(setOf("W1AW"), rows(HamClockRbnMode.WATCHLIST))
        assertEquals(setOf("VK9DX", "W1AW"), rows(HamClockRbnMode.ALL_RBN))
    }

    @Test fun rbnWatchlistOnlyNormalizesPortableAndPrefixForms() {
        val row = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 EA8/OM0RX/P CW 20 dB", 1_000))
        val preference = HamClockRbnPreference(watchlistOnly = true)
        assertEquals(listOf(row.id), boundedRbnObservations(listOf(row), preference, setOf("OM0RX"), 1_010).map { it.id })
    }

    @Test fun personalWsprReusesPskTransportWithModeAndNoLocator() {
        val directory = Files.createTempDirectory("wspr-shared").toFile()
        try {
            val urls = mutableListOf<String>()
            val client = HamClockHttpClient { request ->
                urls += request.url
                val hearing = request.url.contains("receiverCallsign")
                val sender = if (hearing) "VK3ABC" else "OM0RX"
                val receiver = if (hearing) "OM0RX" else "VK3ABC"
                HamClockHttpResponse("""rwPsk({"receptionReport":[{"senderCallsign":"$sender","senderLocator":"QF22","receiverCallsign":"$receiver","receiverLocator":"JN88TQ","frequency":14095600,"flowStartSeconds":990,"mode":"WSPR","sNR":"-18"}]});""",
                    contentType = "application/javascript", effectiveUrl = request.url)
            }
            val psk = PskReporterRepository(directory, client, HamClockInFlightCoalescer())
            val snapshot = HamClockWsprRepository(psk).refreshPersonal("OM0RX", null,
                HamClockWsprPreference(windowMinutes = 2), nowEpoch = 1_000)
            assertEquals(2, urls.size); assertTrue(urls.all { it.contains("mode=WSPR") && it.contains("nolocator=1") })
            assertEquals(setOf(SignalDirection.BEING_HEARD, SignalDirection.HEARING), snapshot.reports.map { it.direction }.toSet())
        } finally { directory.deleteRecursively() }
    }

    @Test fun regionalWsprIsPolicyUnavailableAndMakesNoRequest() {
        val directory = Files.createTempDirectory("wspr-policy").toFile()
        try {
            var requests = 0
            val psk = PskReporterRepository(directory, HamClockHttpClient { requests++; error("network forbidden") },
                HamClockInFlightCoalescer())
            val snapshot = HamClockWsprRepository(psk).refreshPersonal("", null,
                HamClockWsprPreference(personalEnabled = false, regionalEnabled = true))
            assertEquals(HamClockWsprRegionalState.UNAVAILABLE_POLICY, snapshot.regionalState); assertEquals(0, requests)
        } finally { directory.deleteRecursively() }
    }

    @Test fun mixedPskDirectionIsDegradedNotTotalError() {
        val snapshot = PskReporterSnapshot("OM0RX",
            PskDirectionFeed(SignalDirection.BEING_HEARD, state = HamClockFeedState.LIVE),
            PskDirectionFeed(SignalDirection.HEARING, state = HamClockFeedState.UNAVAILABLE, error = "offline"))
        assertEquals(NeuralSignalSourceState.DEGRADED, neuralSignalSourceState(snapshot, hasRows = false))
        assertEquals(HamClockFeedState.DEGRADED, snapshot.sourceState)
    }

    @Test fun personalWsprLocalProjectionChangesNeverFetch() {
        val directory = Files.createTempDirectory("wspr-local-filter").toFile()
        try {
            var requests = 0
            val client = HamClockHttpClient { request ->
                requests++
                val hearing = request.url.contains("receiverCallsign")
                val sender = if (hearing) "VK3ABC" else "OM0RX"
                val receiver = if (hearing) "OM0RX" else "VK3ABC"
                HamClockHttpResponse("""rwPsk({"receptionReport":[{"senderCallsign":"$sender","senderLocator":"QF22","receiverCallsign":"$receiver","receiverLocator":"JN88TQ","frequency":14095600,"flowStartSeconds":990,"mode":"WSPR","sNR":"-18"}]});""",
                    contentType = "application/javascript", effectiveUrl = request.url)
            }
            val repository = HamClockWsprRepository(PskReporterRepository(directory, client, HamClockInFlightCoalescer()))
            repository.refreshPersonal("OM0RX", null, HamClockWsprPreference(windowMinutes = 2), nowEpoch = 1_000)
            val before = requests
            val filtered = repository.reprojectPersonal(HamClockWsprPreference(windowMinutes = 2,
                direction = HamClockPskDirection.HEARING, band = "20M", minimumSnr = -20, maximumPaths = 1))
            assertEquals(before, requests); assertEquals(1, filtered.reports.size)
            assertEquals(SignalDirection.HEARING, filtered.reports.single().direction)
        } finally { directory.deleteRecursively() }
    }

    @Test fun ibpManifestHasEighteenUniqueOfficialScheduleSitesAndStableHash() {
        val expected = listOf("4U1UN" to "FN30AS", "VE8AT" to "CP38GH", "W6WX" to "CM97BD", "KH6RS" to "BL10TS",
            "ZL6B" to "RE78TW", "VK6RBP" to "OF87AV", "JA2IGY" to "PM84JK", "RR9O" to "NO14KX",
            "VR2B" to "OL72BG", "4S7B" to "MJ96WV", "ZS6DN" to "KG33XI", "5Z4B" to "KI88HR",
            "4X6TU" to "KM72JB", "OH2B" to "KP20EH", "CS3B" to "IM12JT", "LU4AA" to "GF05TJ",
            "OA4B" to "FH17MW", "YV5B" to "FK60ND")
        assertEquals(expected, hamClockIbpManifest.map { it.callsign to it.grid })
        assertEquals("c5a6333fca305bf35c4e9ded6a3c0885b0b217a6513b263f78923a34931fdc41", hamClockIbpManifestHash())
        assertEquals(HAMCLOCK_IBP_MANIFEST_HASH, hamClockIbpManifestHash())
        assertEquals("Masterton", hamClockIbpManifest.single { it.callsign == "ZL6B" }.locationLabel)
        assertEquals("Rolystone", hamClockIbpManifest.single { it.callsign == "VK6RBP" }.locationLabel)
        assertEquals("Kikuyu", hamClockIbpManifest.single { it.callsign == "5Z4B" }.locationLabel)
        assertEquals("São Jorge", hamClockIbpManifest.single { it.callsign == "CS3B" }.locationLabel)
        assertTrue(HAMCLOCK_IBP_MANIFEST_SOURCE.startsWith("https://"))
    }

    @Test fun ibpScheduleIsFiveBandsTenSecondSlotsAndOneHundredEightySecondCycle() {
        val first = hamClockIbpSchedule(180)
        val next = hamClockIbpSchedule(190)
        val wrapped = hamClockIbpSchedule(360)
        assertEquals(listOf("20m", "17m", "15m", "12m", "10m"), first.transmissions.map { it.band })
        assertEquals(10, first.transmissions.first().slotEndEpoch - first.transmissions.first().slotStartEpoch)
        assertNotEquals(first.transmissions.first().beacon, next.transmissions.first().beacon)
        assertEquals(first.transmissions.map { it.beacon.callsign }, wrapped.transmissions.map { it.beacon.callsign })
    }

    @Test fun ibpObservedEvidenceIsSeparateAndLimitedToClusterOrRbn() {
        val observed = observedIbpEvidence(listOf(
            HamClockBandEvidence("RBN", "20m", "CW", "KH6RS", "K1ABC-#", -12, 1_000, 14_100_000),
            HamClockBandEvidence("QSO_HISTORY", "20m", "CW", "KH6RS", observedEpoch = 900)))
        assertEquals(1, observed.size); assertEquals("RBN", observed.single().source)
        assertEquals(14_100_000, observed.single().frequencyHz)
    }

    @Test fun allUnavailableBandHealthSaysNoLiveEvidenceNeverClosed() {
        val preference = HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("RBN", "WSPR"))
        val row = computeHamClockBandHealth(emptyList(), mapOf("RBN" to HamClockEvidenceAvailability.UNAVAILABLE,
            "WSPR" to HamClockEvidenceAvailability.DISABLED), preference, 1_000).single()
        assertEquals("NO LIVE EVIDENCE", row.state); assertFalse(row.state.contains("CLOSED"))
    }

    @Test fun availableButEmptyBandHealthSaysNoRecentEvidence() {
        val preference = HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("RBN"))
        val row = computeHamClockBandHealth(emptyList(), mapOf("RBN" to HamClockEvidenceAvailability.CURRENT),
            preference, 1_000).single()
        assertEquals("NO RECENT EVIDENCE", row.state)
    }

    @Test fun staleOnlyBandHealthIsNeverActive() {
        val evidence = listOf(HamClockBandEvidence("RBN", "20m", "CW", "W1AW", "K1ABC-#", observedEpoch = 995))
        val row = computeHamClockBandHealth(evidence, mapOf("RBN" to HamClockEvidenceAvailability.STALE),
            HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("RBN")), 1_000).single()
        assertEquals("STALE EVIDENCE", row.state); assertEquals("LOW", row.confidence)
    }

    @Test fun qsoHistoryIsComparisonOnlyAndCannotMakeBandLive() {
        val evidence = listOf(HamClockBandEvidence("QSO_HISTORY", "20m", "CW", "W1AW", observedEpoch = 999))
        val row = computeHamClockBandHealth(evidence, mapOf("QSO_HISTORY" to HamClockEvidenceAvailability.CURRENT),
            HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("QSO_HISTORY")), 1_000).single()
        assertEquals("NO LIVE EVIDENCE", row.state); assertEquals(1, row.historicalObservations)
    }

    @Test fun bandHealthCapsRepeatedContributorAndExplainsConfidenceTrend() {
        val rows = (1..8).map { HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", -10, 1_000L + it * 31) } +
            listOf(HamClockBandEvidence("WSPR", "20m", "WSPR", "VK3ABC", "OM0RX", -18, 1_299))
        val preference = HamClockBandHealthPreference(visibleBands = setOf("20m"), enabledSources = setOf("RBN", "WSPR"))
        val result = computeHamClockBandHealth(rows, mapOf("RBN" to HamClockEvidenceAvailability.CURRENT,
            "WSPR" to HamClockEvidenceAvailability.CURRENT), preference, 1_300).single()
        assertEquals(4, result.observations); assertEquals(2, result.sourceCount)
        assertEquals("MEDIUM", result.confidence); assertTrue(result.reasons.joinToString().contains("capped"))
    }

    @Test fun crossSourceClusterRbnDuplicateCountsOnceAndSourceCountIsTruthful() {
        val rows = listOf(
            HamClockBandEvidence("CLUSTER", "20m", "CW", "VK9DX", "K1ABC-#", -10, 1_000),
            HamClockBandEvidence("RBN", "20m", "CW", "VK9DX", "K1ABC-#", -10, 1_005))
        val result = computeHamClockBandHealth(rows, mapOf("CLUSTER" to HamClockEvidenceAvailability.CURRENT,
            "RBN" to HamClockEvidenceAvailability.CURRENT), HamClockBandHealthPreference(visibleBands = setOf("20m"),
            enabledSources = setOf("CLUSTER", "RBN")), 1_010).single()
        assertEquals(1, result.observations); assertEquals(1, result.sourceCount)
        assertEquals(1.0, result.callDiversity, 0.0)
    }

    @Test fun task2B2SettingsRoundTripThroughProfilesAndImportCodec() {
        val settings = HamClockUserSettings(rbn = HamClockRbnPreference(false, viewMode = HamClockRbnMode.WATCHLIST,
            maximumRows = 77, minimumSnr = -12),
            wspr = HamClockWsprPreference(false, windowMinutes = 120, regionalEnabled = true, showRegionalGrid = true),
            ibp = HamClockIbpPreference(false, false),
            bandHealth = HamClockBandHealthPreference(60, "CW", setOf("RBN"), setOf("20m")))
        val document = HamClockSettingsDocument(settings = settings, profiles = listOf(
            HamClockNamedProfile("rf", "RF", settings, 1, 1)))
        val decoded = HamClockSettingsCodec.decode(HamClockSettingsCodec.encode(document))
        assertEquals(settings.rbn, decoded.settings.rbn); assertEquals(settings.wspr, decoded.settings.wspr)
        assertEquals(settings.ibp, decoded.profiles.single().settings.ibp)
        assertEquals(setOf("20M"), decoded.profiles.single().settings.bandHealth.visibleBands)
    }

    @Test fun pskHearingPathRunsRemoteToDeAndNewLayerCapsAreExplicit() {
        val heardByDe = SignalReport("VK3ABC", "QF22", -37.5, 145.0, 14_095_600, "20m", "WSPR", -18,
            null, 1_000, SignalDirection.HEARING, "OM0RX", "VK3ABC", "QF22", "OM0RX", "JN88TQ")
        val endpoints = requireNotNull(hamClockSignalPathEndpoints(heardByDe, requireNotNull(maidenheadCenter("JN88TQ"))))
        assertEquals(-37.5, endpoints.first.latitude, 0.001)
        assertEquals(120, requireNotNull(hamClockMapLayerSpec(HamClockMapLayerId.RBN)).maximumObjectCount)
        assertEquals(100, requireNotNull(hamClockMapLayerSpec(HamClockMapLayerId.WSPR_EXPANDED)).maximumObjectCount)
        assertEquals(23, requireNotNull(hamClockMapLayerSpec(HamClockMapLayerId.IBP)).maximumObjectCount)
    }

    @Test fun rbnPathRunsObservedDxToSkimmerNotDeStation() {
        val base = requireNotNull(parseRbnClusterLine("DX de K1ABC-#: 14025.3 VK9DX CW 20 dB", 1_000))
        val dx = GeoPoint(-10.0, 100.0); val skimmer = GeoPoint(40.0, -75.0)
        val endpoints = requireNotNull(hamClockRbnPathEndpoints(base.copy(dxPoint = dx, skimmerPoint = skimmer)))
        assertEquals(dx, endpoints.first); assertEquals(skimmer, endpoints.second)
    }


    @Test fun rbnEndpointResolverPrefersStationAndCachedGridBeforeCty() {
        val station = GeoPoint(48.5, 17.5); val cty = GeoPoint(48.0, 19.0)
        val exact = resolveRbnEndpoint("OM0RX/P", null, "OM0RX", station, null, cty)
        assertEquals(station, exact.point); assertEquals(HamClockGeometryAccuracy.EXACT, exact.accuracy)
        val cached = resolveRbnEndpoint("K1ABC", null, "OM0RX", station, "FN31", cty)
        assertEquals("CACHED CALLBOOK GRID", cached.source)
    }

    @Test fun backgroundLifecycleGateRejectsProviderAndLightningStarts() {
        assertFalse(shouldStartForegroundWork(false))
        assertFalse(shouldStartForegroundWork(true, alreadyActive = true))
        assertTrue(shouldStartForegroundWork(true))
    }
}
