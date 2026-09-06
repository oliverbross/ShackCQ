package app.shackcq.mobile

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class NeuralProviderCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun cache(name: String = "provider.txt") = temporary.newFile(name)

    @Test fun freshValidCacheSkipsFetch() {
        val file = cache().apply { writeText("old"); setLastModified(950_000) }
        var fetched = false
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, { fetched = true; "new" }) { value ->
            require(value in setOf("old", "new")); value
        }
        assertEquals(NeuralProviderState.CACHED, result.status.state)
        assertEquals("old", result.value)
        assertFalse(fetched)
    }

    @Test fun expiredCacheAndValidFetchCommitsLiveValue() {
        val file = cache().apply { writeText("old"); setLastModified(800_000) }
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, { "new" }) { it }
        assertEquals(NeuralProviderState.LIVE, result.status.state)
        assertEquals("new", result.value)
        assertEquals("new", file.readText())
    }

    @Test fun expiredCacheSurvivesFetchFailureAsStale() {
        val file = cache().apply { writeText("last-good"); setLastModified(800_000) }
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, { error("network failed") }) { it }
        assertEquals(NeuralProviderState.STALE, result.status.state)
        assertEquals("last-good", result.value)
        assertEquals("last-good", file.readText())
    }

    @Test fun forcedFailureKeepsFreshCacheCachedWithDetail() {
        val file = cache().apply { writeText("last-good"); setLastModified(950_000) }
        val result = loadNeuralProvider(file, "Test", 100, 100, true, 1_000, { error("provider failed") }) { it }
        assertEquals(NeuralProviderState.CACHED, result.status.state)
        assertEquals("last-good", result.value)
        assertTrue(result.status.detail.contains("provider failed"))
    }

    @Test fun malformedFetchCannotReplaceExpiredLastGoodCache() {
        val file = cache().apply { writeText("valid"); setLastModified(800_000) }
        val before = file.readBytes()
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, { "malformed" }) {
            require(it == "valid") { "invalid content" }; it
        }
        assertEquals(NeuralProviderState.STALE, result.status.state)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test fun failureWithoutCacheIsUnavailable() {
        val file = temporary.root.resolve("missing.txt")
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, { error("offline") }) { it }
        assertEquals(NeuralProviderState.UNAVAILABLE, result.status.state)
        assertNull(result.value)
    }

    @Test fun oversizedCacheIsNeverDecodedOrExposed() {
        val file = cache().apply { writeText("x".repeat(101)) }
        var decoded = false
        val result = loadNeuralProvider(file, "Test", 100, 100, false, 1_000, null) { decoded = true; it }
        assertEquals(NeuralProviderState.UNAVAILABLE, result.status.state)
        assertFalse(decoded)
        assertNull(result.value)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        loadNeuralProvider(temporary.root.resolve("missing.txt"), "Test", 100, 100, false, 1_000,
            { throw CancellationException("cancelled") }, { it })
    }

    @Test fun stateAgesAndLabelsAreDeterministic() {
        val live = NeuralProviderStatus("Test", NeuralProviderState.LIVE, 1_000, 1_100)
        assertEquals(NeuralProviderState.STALE, live.effective(1_101).state)
        assertEquals("just now", neuralProviderAgeLabel(1_000, 1_005))
        assertEquals("42s old", neuralProviderAgeLabel(1_000, 1_042))
        assertEquals("8m old", neuralProviderAgeLabel(1_000, 1_480))
        assertEquals("3h old", neuralProviderAgeLabel(1_000, 11_800))
        assertEquals("2d old", neuralProviderAgeLabel(1_000, 173_800))
        assertEquals("", neuralProviderAgeLabel(0, 1_000))
    }

    @Test fun atomicReplacementLeavesOneCompleteDestination() {
        val file = cache().apply { writeText("old") }
        atomicWriteNeuralText(file, "complete replacement", 1_000)
        assertEquals("complete replacement", file.readText())
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test fun weatherDecoderRestoresCurrentAndHourlyDerivedContext() {
        val weather = decodeNeuralWeather("""{
            "current":{"time":"2026-08-21T02:00","temperature_2m":24.5,"pressure_msl":1008.0,"relative_humidity_2m":84,"wind_speed_10m":18.0,"wind_direction_10m":120,"precipitation":1.2,"weather_code":61},
            "hourly":{"time":["2026-08-21T00:00","2026-08-21T01:00","2026-08-21T02:00"],"cape":[10,20,30],"temperature_850hPa":[14,15,16],"wind_speed_300hPa":[80,90,100],"wind_direction_300hPa":[250,260,270],"pressure_msl":[1011,1010,1008]}
        }""")
        assertEquals(24.5, weather.temperatureC!!, 0.0)
        assertEquals(30.0, weather.cape!!, 0.0)
        assertEquals(16.0, weather.temperature850C!!, 0.0)
        assertEquals(100.0, weather.wind300Kmh!!, 0.0)
        assertEquals(270, weather.wind300Direction)
        assertEquals("FALLING FAST", weather.pressureTrend)
        assertTrue(weather.tropoIndex != null)
        assertTrue(weather.ductingRisk != null)
    }

    @Test fun wsprExplicitEmptyDataIsValidButMissingDataIsRejected() {
        val empty = decodeNeuralWspr("{\"data\":[]}")
        assertTrue(empty.available)
        assertTrue(empty.hf.isEmpty() && empty.vhf.isEmpty())
        assertTrue(runCatching { decodeNeuralWspr("{}") }.isFailure)
        assertTrue(runCatching { decodeNeuralWspr("not json") }.isFailure)
    }

    @Test fun locationCacheKeyIsStableDistinctSafeAndLocaleIndependent() {
        val point = GeoPoint(48.1234, 17.9876)
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val first = neuralPointCacheKey(point)
            Locale.setDefault(Locale.US)
            assertEquals(first, neuralPointCacheKey(point))
            assertNotEquals(first, neuralPointCacheKey(GeoPoint(48.22, 17.99)))
            assertTrue(first.matches(Regex("[A-Za-z0-9_]+")))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun providerSummaryCountsEffectiveStatesTruthfully() {
        val statuses = listOf(
            NeuralProviderStatus("A", NeuralProviderState.LIVE, 900, 1_100),
            NeuralProviderStatus("B", NeuralProviderState.CACHED, 900, 1_100),
            NeuralProviderStatus("C", NeuralProviderState.LIVE, 800, 900),
            NeuralProviderStatus("D", NeuralProviderState.UNAVAILABLE),
        )
        assertEquals("Providers · 1 live · 1 cached · 1 stale · 1 unavailable", neuralProviderSummary(statuses, 1_000))
    }
}
