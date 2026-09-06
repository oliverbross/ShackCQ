package app.shackcq.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SafeAreaInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun dxTabsAndLogbookStayInsideTheNavigationBarSafeArea() {
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val homeBottom = compose.onNodeWithTag("openhamclock-safe-content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Open Ham Clock must reserve space above the Android navigation bar", homeBottom < rootBottom)

        compose.onNodeWithText("DX").performClick()
        val safeBottom = compose.onNodeWithTag("dx-safe-content").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("DX must reserve space above the Android navigation bar", safeBottom < rootBottom)

        listOf(
            "Cockpit" to "cockpit",
            "Map" to "map",
            "AI Insight" to "insight",
            "World" to "world",
            "Briefing" to "briefing",
            "Satellites" to "satellites",
            "Weather" to "weather",
        ).forEach { (label, tag) ->
            compose.onNodeWithText(label).performClick()
            val page = compose.onNodeWithTag("dx-page-$tag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit the safe DX width", page.right <= compose.onNodeWithTag("dx-safe-content").fetchSemanticsNode().boundsInRoot.right)
            assertTrue("$label must fit above the Android navigation bar", page.bottom <= safeBottom)
        }

        compose.onNodeWithText("Logbook").performClick()
        val logbook = compose.onNodeWithTag("logbook-safe-content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Logbook must reserve space above the Android navigation bar", logbook.bottom < rootBottom)
    }
}
