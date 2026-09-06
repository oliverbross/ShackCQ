package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class SyncHubRulesTest {
    private fun qso(
        station: String = "OM0RX",
        myGrid: String = "JN88TQ",
        profile: String = "",
        activation: String = "",
    ) = Qso("q1", "VK3ABC", 14_200_000, "SSB", "59", "59", 1_700_000_000,
        band = "20m", stationCallsign = station, myGrid = myGrid, stationProfileId = profile,
        activationProgram = activation)

    @Test fun localOperatorSaveCanAutoEnqueue() {
        assertTrue(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.LOCAL, true, true, true, false))
        assertFalse(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.WAVELOG, true, true, true, false))
    }

    @Test fun importsAndRemoteSyncNeverAutoEnqueue() {
        assertFalse(shouldAutoEnqueue(QsoOrigin.IMPORT, LogMode.LOCAL, true, true, true, false))
        assertFalse(shouldAutoEnqueue(QsoOrigin.REMOTE_SYNC, LogMode.LOCAL, true, true, true, false))
    }

    @Test fun pausedUnconfiguredOrBlockedProviderCannotAutoEnqueue() {
        assertFalse(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.LOCAL, false, true, true, false))
        assertFalse(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.LOCAL, true, false, true, false))
        assertFalse(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.LOCAL, true, true, false, false))
        assertFalse(shouldAutoEnqueue(QsoOrigin.OPERATOR, LogMode.LOCAL, true, true, true, true))
    }

    @Test fun retryScheduleIsBounded() {
        val now = 10_000L
        assertEquals(now + 60, retryAt(1, now))
        assertEquals(now + 300, retryAt(2, now))
        assertEquals(now + 900, retryAt(3, now))
        assertEquals(now + 3_600, retryAt(4, now))
        assertEquals(now + 21_600, retryAt(5, now))
        assertNull(retryAt(6, now))
    }

    @Test fun qrzParserRequiresBodySuccessAndRetainsRemoteId() {
        val result = parseQrzResponse(200, "RESULT=OK&LOGID=123&COUNT=1")
        assertEquals(DeliveryState.ACCEPTED, result.state)
        assertEquals("123", result.remoteId)
        assertEquals(DeliveryState.REJECTED, parseQrzResponse(400, "RESULT=FAIL&REASON=Bad+QSO").state)
    }

    @Test fun qrzAuthAndDuplicateAreExplicit() {
        assertTrue(parseQrzResponse(200, "RESULT=AUTH&REASON=Invalid+key").authenticationBlocked)
        assertEquals(DeliveryState.ACCEPTED_DUPLICATE,
            parseQrzResponse(200, "RESULT=FAIL&REASON=QSO+already+exists").state)
    }

    @Test fun qrzInsertHasNoReplaceOption() {
        val body = formBody(listOf("KEY" to "secret", "ACTION" to "INSERT", "ADIF" to "<CALL:3>ABC<EOR>"))
            .toString(Charsets.UTF_8)
        assertTrue(body.contains("ACTION=INSERT"))
        assertFalse(URLDecoder.decode(body, Charsets.UTF_8.name()).contains("OPTION=REPLACE"))
    }

    @Test fun qrzRequiresExactStationCallsign() {
        assertTrue(qrzStationMatches(qso(), "om0rx"))
        assertFalse(qrzStationMatches(qso(station = "OM0RX/P"), "OM0RX"))
    }

    @Test fun qrzStatusMetadataAndActiveDatesGateDelivery() {
        val status = parseQrzStatus("RESULT=OK&DATA=CALLSIGN%3DOM0RX%26BOOK_NAME%3DMain%26START_DATE%3D2023-01-01%26END_DATE%3D2027-12-31")
        assertEquals("OM0RX", status.callsign)
        assertEquals("Main", status.name)
        assertTrue(qrzDateAllowed(qso().copy(createdAt = 1_700_000_000), status))
        assertFalse(qrzDateAllowed(qso().copy(createdAt = 1_600_000_000), status))
    }

    @Test fun clubLogRealtimeResponsesMapExactly() {
        assertEquals(DeliveryState.ACCEPTED, parseClubLogResponse(200, "QSO OK").state)
        assertEquals(DeliveryState.ACCEPTED_DUPLICATE, parseClubLogResponse(200, "QSO Duplicate").state)
        assertEquals(DeliveryState.ACCEPTED_MODIFIED, parseClubLogResponse(200, "QSO Modified").state)
        assertEquals(DeliveryState.REJECTED, parseClubLogResponse(400, "Bad band").state)
        assertTrue(parseClubLogResponse(403, "Wrong application password").authenticationBlocked)
        assertTrue(parseClubLogResponse(500, "Parser offline").transient)
    }

    @Test fun clubLogBatchNeverContainsClear() {
        val boundary = "test-boundary"
        val body = multipartBody(listOf("email" to "a@b.test", "password" to "app-pass",
            "callsign" to "OM0RX", "api" to "key"), "catch-up.adi", "<CALL:3>ABC<EOR>", boundary)
            .toString(Charsets.UTF_8)
        assertTrue(body.contains("name=\"file\""))
        assertFalse(body.contains("name=\"clear\""))
    }

    @Test fun eqslParserDoesNotTrustHttp200Alone() {
        assertEquals(DeliveryState.REJECTED, parseEqslResponse(200, "<html>generic success</html>").state)
        val accepted = "<!-- Reply form eQSL.cc ADIF Real-time Interface --> Result: 1 out of 1 records added"
        assertEquals(DeliveryState.ACCEPTED, parseEqslResponse(200, accepted).state)
    }

    @Test fun eqslDuplicateAndAuthAreExplicit() {
        val prefix = "<!-- Reply form eQSL.cc ADIF Real-time Interface -->"
        assertEquals(DeliveryState.ACCEPTED_DUPLICATE, parseEqslResponse(200, "$prefix Warning: Bad record: Duplicate").state)
        assertTrue(parseEqslResponse(200, "$prefix Error: No match on eQSL_User/eQSL_Pswd").authenticationBlocked)
    }

    @Test fun eqslPortableAndDifferentQthRequireNickname() {
        assertTrue(requiresEqslProfile(qso(activation = "POTA"), "JN88TQ", ""))
        assertTrue(requiresEqslProfile(qso(myGrid = "QF22AA"), "JN88TQ", ""))
        assertTrue(requiresEqslProfile(qso(profile = "portable"), "JN88TQ", ""))
        assertFalse(requiresEqslProfile(qso(activation = "POTA"), "JN88TQ", "Darwin portable"))
        assertFalse(requiresEqslProfile(qso(), "JN88TQ", ""))
    }

    @Test fun eQslMultipartUsesOfficialFilenameField() {
        val body = multipartBody(listOf("EQSL_USER" to "OM0RX", "EQSL_PSWD" to "secret"),
            "shackcq.adi", "<CALL:3>ABC<EOR>", "boundary", "Filename").toString(Charsets.UTF_8)
        assertTrue(body.contains("name=\"Filename\""))
        assertTrue(body.contains("name=\"EQSL_USER\""))
    }

    @Test fun providerMessagesCannotRetainCredentials() {
        val safe = redactSecrets("<b>Rejected secret-key app-password</b>\u0000", listOf("secret-key", "app-password"))
        assertFalse(safe.contains("secret-key"))
        assertFalse(safe.contains("app-password"))
        assertFalse(safe.contains("<b>"))
    }

    @Test fun acceptanceUpdatesOnlyMatchingSentFlag() {
        val original = qso().copy(qrzReceived = "Y", clublogReceived = "Y", eqslReceived = "Y")
        val qrz = applyAcceptedFlag(original, SyncProvider.QRZ)
        assertEquals("Y", qrz.qrzSent)
        assertEquals("N", qrz.clublogSent)
        assertEquals("N", qrz.eqslSent)
        assertEquals("Y", qrz.qrzReceived)
        assertEquals("Y", qrz.clublogReceived)
        assertEquals("Y", qrz.eqslReceived)
    }

    @Test fun providersRemainIndependent() {
        val club = applyAcceptedFlag(qso(), SyncProvider.CLUB_LOG)
        assertEquals("Y", club.clublogSent)
        assertEquals("N", club.qrzSent)
        assertEquals("N", club.eqslSent)
    }
}
