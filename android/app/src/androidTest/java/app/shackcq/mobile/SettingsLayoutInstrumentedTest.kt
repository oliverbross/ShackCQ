package app.shackcq.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SettingsLayoutInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsSectionsExposeThePortedTabletLayouts() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Diag").performClick()
        compose.onNodeWithTag("settings-default-cty").assertIsDisplayed()

        compose.onNodeWithText("Macros").performClick()
        compose.onNodeWithText("CW").performClick()
        compose.onNodeWithTag("settings-cw-macro-grid").assertIsDisplayed()

        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithTag("settings-audio-layout").assertIsDisplayed()

        compose.onNodeWithText("About").performClick()
        compose.onNodeWithTag("settings-developer-information").assertIsDisplayed()
        compose.onNodeWithText("Oliver Bross, OM0RX").assertIsDisplayed()
    }
}
