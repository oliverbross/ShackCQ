package app.shackcq.mobile.contest

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ContestWorkspaceInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    private val definition=ContestRuleRegistry().require(ContestDefinitionId("cq-ww-cw")).definition
    private val session=ContestSession(ContestSessionId("s"),definition.id,definition.version,"CQWW",0,999999,"OM0RX","JN88",ContestEntityInfo(),ContestCategory(mode=ContestMode.CW),listOf("OM0RX"))
    @Test fun standaloneWorkspaceExposesSetupLoggingReviewAndSafeNetwork(){val page=mutableStateOf(ContestWorkspacePage.SETUP);compose.setContent{MaterialTheme{ContestWorkspace(ContestWorkspaceState(session,definition,page=page.value),ContestWorkspaceCallbacks(onPage={page.value=it}))}};compose.onNodeWithText("START WITHOUT TRANSMITTING").assertExists();compose.onNodeWithText("LOGGING").performClick();compose.onNodeWithContentDescription("Contest callsign").assertExists();compose.onNodeWithText("LOG QSO").assertExists();compose.onNodeWithText("NETWORK").performClick();compose.onNodeWithText("Mode: OFF · default OFF · loopback only").assertExists();compose.onNodeWithText("START MONITOR").assertExists()}
}
