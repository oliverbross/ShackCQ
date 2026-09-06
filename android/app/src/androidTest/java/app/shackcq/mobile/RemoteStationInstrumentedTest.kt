// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteStationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun clearProfiles() {
        context.getSharedPreferences("remote-stations-v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun nativeWorkspaceIsExplicitlyRemoteAndDebugLabNeverClaimsRadio() {
        val app = AppController(context)
        val runtime = RemoteRuntimeState()
        val factory = RemoteStationBackendFactory(app::remoteStation, runtime)
        compose.setContent { RemoteStationScreen(app, runtime, factory, {}, {}, {}) }
        compose.onNodeWithText("REMOTE STATIONS").assertIsDisplayed()
        compose.onNodeWithText("REMOTE · DISCONNECTED").assertIsDisplayed()
        compose.onAllNodesWithText("GLOBAL STOP").onFirst().assertIsDisplayed()
        compose.onNodeWithText("DEMO · NO RADIO").performScrollTo().assertIsDisplayed()
    }

    @Test fun profileStoreIsBoundedAndFaultLabClearsEveryLease() {
        val store = RemoteStationStore(context)
        repeat(8) { index ->
            store.save(RemoteStationProfile("station-${index}0000000", "Station $index", "192.0.2.${index + 1}",
                7443, "%064x".format(index + 1), "android-device-0001", RemoteRole.OBSERVER))
        }
        assertEquals(8, store.load().size)
        val lab = DebugRemoteLabV6()
        lab.acquireWriter(); lab.exerciseLeases(); lab.injectNetworkLoss()
        assertTrue(lab.snapshot.leases.contains("writer NONE"))
        assertTrue(lab.snapshot.leases.contains("TX CLEARED"))
        assertTrue(lab.snapshot.leases.contains("rotator NONE"))
    }
}
