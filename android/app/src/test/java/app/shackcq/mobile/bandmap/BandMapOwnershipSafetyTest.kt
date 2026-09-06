// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BandMapOwnershipSafetyTest {
    private fun source(name: String) = File("src/main/java/app/shackcq/mobile/bandmap/$name").readText()

    @Test fun bandMapPackageOwnsNoNetworkProviderOrTransmitPath() {
        val production = File("src/main/java/app/shackcq/mobile/bandmap").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("java.net.Socket", "HttpURLConnection", "java.net.URL", "KeyerDispatchPort", "DxChaserEngine", "DxChaserStore",
            "DigiController", "setPtt", "PTT;", "transmit(", "postSpot(", "connectCluster(").forEach { forbidden ->
            assertFalse("Band Maps must not own $forbidden", forbidden in production)
        }
        assertTrue("Band Maps consumes immutable source rows", "BandMapSourceAdapters" in production)
        assertTrue("Band Maps consumes read-only Chaser snapshot", "DxChaserReadOnlySnapshot" in production)
        assertTrue("Band Maps exposes Keyer queue/availability only", "BandMapKeyerContext" in production)
    }

    @Test fun projectionNeedsPathIsIndexedBoundedAndDoesNotSelectQsoPayloads() {
        val projection = source("BandMapNeedsProjection.kt")
        assertTrue("SELECT DISTINCT" in projection)
        assertTrue("LIMIT" in projection)
        assertTrue("station_profile_id" in projection && "station_callsign_norm" in projection)
        assertFalse("SELECT *" in projection)
        assertFalse("details_json" in projection)
        assertFalse("FROM qso " in projection)
    }

    @Test fun actionsAreGenerationGatedAndReceiveReviewed() {
        val controller = source("BandMapController.kt")
        val convergence = File("src/main/java/app/shackcq/mobile/FinalConvergenceContracts.kt").readText()
        assertTrue("snapshot.contextGeneration != currentContextGeneration" in controller)
        assertTrue("frequencyHz.takeIf { destination == WorkspaceDestination.RADIO }" in controller)
        assertTrue("HomeReceiveTuneReview" in convergence)
        assertTrue("mayKeyPtt: Boolean = false" in convergence)
        assertTrue("mayArmTransmit: Boolean = false" in convergence)
    }

    @Test fun settingsRestoreCannotInvokeRuntimeAuthorities() {
        val store = source("BandMapStateStore.kt")
        listOf("WorkspaceActionRouter", "Keyer", "DxChaser", "Digi", "CAT", "PTT").forEach { assertFalse(it in store) }
        assertTrue("document_last_good" in store)
        assertTrue("commit()" in store)
    }
}
