// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class QmxRadioSurfaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun surfaceHidesUnsupportedControlsAndExposesOnlyTypedSupportedActions() {
        var snapshot by mutableStateOf(QmxRadioSnapshot(connected = true, ready = true, vfoAHz = 14_074_000, mode = QmxMode.DIGI, txState = QmxTxState.RX))
        compose.setContent {
            MaterialTheme {
                QmxRadioSurface(
                    snapshot,
                    QmxRadioActionPort {},
                    QmxSurfaceLayout.COMPACT,
                )
            }
        }
        compose.onNodeWithText("REQUEST DIGI TX").assertDoesNotExist()
        compose.onNodeWithText("REQUEST SWR TUNE").assertDoesNotExist()
        compose.onNodeWithText("CLEAR RIT").assertDoesNotExist()

        compose.runOnIdle {
            snapshot = snapshot.copy(capabilities = QmxCapabilities(
                afGain = QmxCapabilityState.SUPPORTED,
                rfGain = QmxCapabilityState.SUPPORTED,
                rit = QmxCapabilityState.SUPPORTED,
                split = QmxCapabilityState.SUPPORTED,
                directToneTx = QmxCapabilityState.SUPPORTED,
                swrTune = QmxCapabilityState.SUPPORTED,
            ))
        }
        compose.onNodeWithText("REQUEST DIGI TX").assertExists()
        compose.onNodeWithText("REQUEST SWR TUNE").assertExists()
        compose.onNodeWithText("CLEAR RIT").assertExists()
    }
}
