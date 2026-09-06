package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogApiV2Test {
    @Test fun normalizesHttpsBaseWithoutDuplicatingApiPath() {
        assertEquals("https://example.test/index.php/api/v2", normalizeWavelogV2Root("example.test"))
        assertEquals("https://example.test/index.php/api/v2", normalizeWavelogV2Root("https://example.test/index.php"))
        assertEquals("https://example.test/api/v2", normalizeWavelogV2Root("https://example.test/api/v2/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureV2Endpoint() { normalizeWavelogV2Root("http://example.test") }

    @Test fun negotiatesScopesAndStationsFromRealV2Envelope() {
        val requests = mutableListOf<WavelogV2Request>()
        val client = WavelogApiV2Client("https://example.test", "wl2_secret", WavelogV2Transport { request ->
            requests += request
            when {
                request.url.endsWith("/token") -> WavelogV2Response(200,
                    """{"data":{"id":7,"name":"ShackCQ","owner":"OM0RX","user_id":4,"scopes":["qso:read","station:read"],"expires_at":"2027-01-01T00:00:00Z"},"meta":{}}""")
                request.url.endsWith("/station") -> WavelogV2Response(200,
                    """{"data":[{"id":11,"uuid":"station-uuid","name":"Home","callsign":"OM0RX","gridsquare":"JN88TQ","active":true}],"meta":{}}""")
                else -> error("Unexpected request")
            }
        })
        val token = client.tokenMetadata()
        val stations = client.stations()
        assertTrue(token.capabilities.canReadQsos)
        assertFalse(token.capabilities.canWriteQsos)
        assertEquals("OM0RX", token.owner)
        assertEquals("station-uuid", stations.single().uuid)
        assertTrue(requests.all { it.bearerToken == "wl2_secret" })
    }

    @Test fun classifiesMissingScopeAndRateLimitWithoutLeakingToken() {
        val client = WavelogApiV2Client("https://example.test", "wl2_secret", WavelogV2Transport {
            WavelogV2Response(429, """{"error":{"code":"rate_limited","message":"Slow down"}}""", "120")
        })
        val error = runCatching { client.tokenMetadata() }.exceptionOrNull() as WavelogApiException
        assertEquals(WavelogErrorClass.RATE_LIMIT, error.errorClass)
        assertEquals(120L, error.retryAfterSeconds)
        assertFalse(error.message.orEmpty().contains("wl2_secret"))
    }

    @Test fun classifiesAuthenticationScopeAndMalformedSuccessPrecisely() {
        fun failure(status: Int, body: String) = runCatching {
            WavelogApiV2Client("https://example.test", "wl2_secret", WavelogV2Transport {
                WavelogV2Response(status, body)
            }).tokenMetadata()
        }.exceptionOrNull() as WavelogApiException
        assertEquals(WavelogErrorClass.AUTHENTICATION,
            failure(401, """{"error":{"code":"unauthorized","message":"No"}}""").errorClass)
        assertEquals(WavelogErrorClass.MISSING_SCOPE,
            failure(403, """{"error":{"code":"missing_scope","message":"No"}}""").errorClass)
        assertEquals(WavelogErrorClass.VALIDATION,
            failure(400, """{"error":{"code":"invalid_qso","message":"No"}}""").errorClass)
        assertEquals(WavelogErrorClass.TEMPORARY,
            failure(500, """{"error":{"code":"server_error","message":"No"}}""").errorClass)
        assertEquals(WavelogErrorClass.MALFORMED_RESPONSE, failure(200, "not-json").errorClass)
    }

    @Test fun sendsSingleJsonCreateAndCapturesCreatedRowWithoutInventedIdempotency() {
        var captured: WavelogV2Request? = null
        val client = WavelogApiV2Client("https://example.test", "wl2_secret", WavelogV2Transport { request ->
            captured = request
            WavelogV2Response(201, """{"data":{"id":"42","call":"OM0RX","qso_date":"2026-08-19 12:34:56","band":"20m","mode":"CW","freq":14060000},"meta":{}}""")
        })
        val row = client.createQso("11", CanonicalQso(mapOf(
            "CALL" to "OM0RX", "QSO_DATE" to "20260819", "TIME_ON" to "123456",
            "BAND" to "20m", "MODE" to "CW", "FREQ" to "14.060",
        )))
        assertEquals("POST", captured?.method)
        assertEquals("42", row.getString("id"))
        assertTrue(captured?.body.orEmpty().contains("\"import_type\":\"json\""))
        assertTrue(captured?.body.orEmpty().contains("\"station_profile_id\":11"))
        assertTrue(captured?.body.orEmpty().contains("\"freq\":14060000"))
    }

    @Test fun patchUsesPinnedReleaseWhitelistAndHzFrequency() {
        var captured: WavelogV2Request? = null
        val client = WavelogApiV2Client("https://example.test", "wl2_secret", WavelogV2Transport { request ->
            captured = request
            WavelogV2Response(200, """{"data":{"id":"42","call":"OM0RX","qso_date":"2026-08-19 12:34:56","mode":"CW","freq":14070000},"meta":{}}""")
        })
        val baseline = CanonicalQso(mapOf("CALL" to "OM0RX", "FREQ" to "14.060", "NOTES" to "old",
            "APP_VENDOR_PRIVATE" to "one"))
        val changed = CanonicalQso(mapOf("CALL" to "OM0RX", "FREQ" to "14.070", "NOTES" to "new",
            "APP_VENDOR_PRIVATE" to "two"))
        client.patchQso("42", baseline, changed)
        assertEquals("PATCH", captured?.method)
        assertTrue(captured?.body.orEmpty().contains("\"freq\":14070000"))
        assertTrue(captured?.body.orEmpty().contains("\"notes\":\"new\""))
        assertFalse(captured?.body.orEmpty().contains("APP_VENDOR_PRIVATE"))
    }
}
