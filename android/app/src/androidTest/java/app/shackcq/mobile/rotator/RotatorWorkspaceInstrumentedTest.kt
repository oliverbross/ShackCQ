package app.shackcq.mobile.rotator

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RotatorWorkspaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun workspaceHidesUnsupportedElevation() {
        compose.setContent { RotatorWorkspace(null, RotatorCapabilitySnapshot(), null, RotatorAutomationSession(), emptyList(), null, { _, _, _ -> }) }
        assertTrue(compose.onAllNodesWithTag("rotator-elevation").fetchSemanticsNodes().isEmpty())
    }
}
