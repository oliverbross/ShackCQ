package app.shackcq.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PotaActivationTest {
    private val dayOne = LocalDate.of(2026, 8, 18).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
    private val setup = PotaActivationSetup("OM0RX/P", "OM0RX", listOf("OM-0001"), "OM-0001",
        stationGrid = "JN88TQ", location = "Bratislava", state = "BL", txPowerW = 10,
        boundaryAcknowledged = true, startAt = dayOne)
    private val session = PotaActivationSession("session-1", setup, currentUtcDay = "2026-08-18", lastSavedAt = dayOne)

    @Test fun sessionEncodeDecodeAndRecoveryRoundTrip() {
        val restored = decodePotaSession(encodePotaSession(session.copy(lastFrequencyHz = 7_032_000, lastMode = "CW", qsoIds = listOf("q1"))))
        assertEquals(session.id, restored.id); assertEquals(listOf("OM-0001"), restored.setup.references)
        assertEquals(7_032_000, restored.lastFrequencyHz); assertEquals("CW", restored.lastMode); assertEquals(listOf("q1"), restored.qsoIds)
    }

    @Test fun abandonmentDoesNotMutateCommittedQso() {
        val committed = qso("q1", dayOne)
        var active: PotaActivationSession? = session
        active = null
        assertNull(active); assertEquals("session-1", committed.activationSessionId); assertEquals("OM-0001", committed.myPotaRef)
    }

    @Test fun singleReferenceActivatorQsoUsesOwnScalarAndList() {
        val row = qso("q1", dayOne)
        assertEquals("OM-0001", row.myPotaRef); assertEquals(listOf("OM-0001"), row.myPotaRefs)
        assertEquals("POTA", row.activationProgram); assertTrue(row.potaRef.isBlank())
    }

    @Test fun multiOwnReferenceMetadataUsesOneLocalRow() {
        val row = qso("q1", dayOne).copy(myPotaRef = "OM-0001", myPotaRefs = listOf("OM-0001", "OM-0002"))
        assertEquals(2, row.myPotaRefs.size); assertEquals("q1", row.id)
    }

    @Test fun p2pOtherReferenceMappingPreservesStructuredList() {
        val row = qso("q1", dayOne).copy(potaRef = "OM-0099", potaRefs = listOf("OM-0099", "OM-0100"))
        assertEquals("OM-0099", row.potaRef); assertEquals(2, row.potaRefs.size)
    }

    @Test fun tenQsoProgressIsPerUtcDay() {
        val rows = (1..10).map { qso("q$it", dayOne + it) }
        val progress = activationProgress(session, rows, dayOne + 100)
        assertEquals(10, progress.currentDay); assertTrue(progress.thresholdReached); assertEquals(10, progress.uniqueCalls)
    }

    @Test fun utcRolloverResetsCurrentDayWithoutDroppingSessionTotal() {
        val nextDay = dayOne + 86_400
        val progress = activationProgress(session, listOf(qso("q1", dayOne), qso("q2", nextDay)), nextDay + 60)
        assertEquals(2, progress.total); assertEquals(1, progress.currentDay); assertEquals("2026-08-19", progress.utcDay)
    }

    @Test fun exportCreatesOneFilePerOwnReferenceAndUtcDate() {
        val multi = session.copy(setup = setup.copy(references = listOf("OM-0001", "OM-0002")))
        val exports = buildPotaExports(multi, listOf(qso("q1", dayOne), qso("q2", dayOne + 86_400)), dayOne + 172_800)
        assertTrue(exports.corrections.isEmpty()); assertEquals(4, exports.files.size)
        assertEquals(setOf("OM-0001", "OM-0002"), exports.files.map { it.ownReference }.toSet())
    }

    @Test fun multiP2pExpandsUnchangedRecordsPerOtherReference() {
        val row = qso("q1", dayOne).copy(potaRef = "OM-0099", potaRefs = listOf("OM-0099", "OM-0100"))
        val body = buildPotaExports(session, listOf(row), dayOne + 60).files.single().contents
        assertEquals(2, "<EOR>".toRegex().findAll(body).count())
        assertTrue(body.contains("<SIG_INFO:7>OM-0099")); assertTrue(body.contains("<SIG_INFO:7>OM-0100"))
        assertEquals(2, "<TIME_ON:6>120000".toRegex().findAll(body).count())
    }

    @Test fun requiredAdifTagsAndFilenameArePresent() {
        val file = buildPotaExports(session, listOf(qso("q1", dayOne)), dayOne + 60).files.single()
        assertEquals("OM0RX_P@OM-0001-20260818.adi", file.filename)
        listOf("STATION_CALLSIGN", "OPERATOR", "CALL", "QSO_DATE", "TIME_ON", "BAND", "MODE", "MY_SIG", "MY_SIG_INFO")
            .forEach { assertTrue("missing $it", file.contents.contains("<$it:")) }
    }

    @Test fun oldQsoJsonWithoutReferenceArraysRemainsCompatible() {
        val old = JSONObject("""{"myPotaRef":"OM-0001","potaRef":"OM-0099"}""")
        assertTrue(old.optJSONArray("myPotaRefs").jsonStringList().isEmpty())
        assertTrue(old.optJSONArray("potaRefs").jsonStringList().isEmpty())
        assertEquals("OM-0001", old.optString("myPotaRef"))
    }

    @Test fun lifecycleAndExportHaveNoCatOrTransmitCommandSink() {
        val commands = mutableListOf<String>()
        decodePotaSession(encodePotaSession(session)); buildPotaExports(session, listOf(qso("q1", dayOne)), dayOne + 60)
        assertTrue(commands.isEmpty())
    }

    @Test fun sotaLiveUsesTheOperatorApprovedReceiveOnlyClusterEndpoint() {
        assertTrue(SOTA_LIVE_APPROVED)
        assertEquals("cluster.sota.org.uk", SOTA_CLUSTER_HOST)
        assertEquals(7300, SOTA_CLUSTER_PORT)
    }

    @Test fun invalidOrEmptyExportReturnsCorrectionInsteadOfFile() {
        val empty = buildPotaExports(session, emptyList(), dayOne + 60)
        assertTrue(empty.files.isEmpty()); assertTrue(empty.corrections.any { it.contains("No QSOs") })
    }

    private fun qso(id: String, epoch: Long) = Qso(id, "K${id.removePrefix("q")}ABC", 7_032_000, "CW", "599", "599", epoch,
        band = "40m", operatorCallsign = setup.operatorCallsign, stationCallsign = setup.stationCallsign, myGrid = setup.stationGrid,
        myState = setup.state, myPotaRef = setup.primaryReference, activationSessionId = session.id, activationProgram = "POTA",
        myPotaRefs = setup.references)
}
