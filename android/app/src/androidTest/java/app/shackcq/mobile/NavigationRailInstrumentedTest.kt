package app.shackcq.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class NavigationRailInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsRemainsReachableWhenTheTabletRailOverflows() {
        compose.onNodeWithText("Settings")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText("RADIO PROFILES").assertIsDisplayed()
    }
}
