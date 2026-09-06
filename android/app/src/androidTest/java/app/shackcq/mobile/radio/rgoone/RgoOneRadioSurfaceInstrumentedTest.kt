package app.shackcq.mobile.radio.rgoone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RgoOneRadioSurfaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun snapshot() = RgoOneRadioSnapshot(
        connected = true, stale = false, generation = RgoOneGeneration.V6, generationConfirmed = true,
        vfoAHz = 14_074_000, vfoBHz = 7_074_000, rxVfo = RgoOneVfo.A, txVfo = RgoOneVfo.B, mode = RgoOneMode.DATA,
        capabilities = RgoOneCapability.entries.associateWith { RgoOneCapabilityState.SUPPORTED_PRESENT },
    )

    @Test fun dominantFrequencyAndStatusRenderWithoutPhysicalDisplayArtwork() {
        compose.setContent { RgoOneRadioSurface(snapshot(), onAction = {}) }
        compose.onNodeWithText("014.074.000").assertIsDisplayed()
        compose.onNodeWithText("DATA").assertIsDisplayed()
    }

    @Test fun transmitControlEmitsTypedReviewedActionOnly() {
        var action: RgoOneAction? = null
        compose.setContent { RgoOneRadioSurface(snapshot(), onAction = { action = it }) }
        compose.onNodeWithText("TX review").performClick()
        assertEquals(RgoOneAction.Transmit, action)
    }
}
