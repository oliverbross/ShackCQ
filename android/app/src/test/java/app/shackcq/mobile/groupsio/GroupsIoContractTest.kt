package app.shackcq.mobile.groupsio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupsIoContractTest {
    @Test fun disabledFeatureIsHiddenEverywhere() {
        assertFalse(groupsIoDestinationVisible(enabled = false, compact = false))
        assertFalse(groupsIoDestinationVisible(enabled = false, compact = true))
    }

    @Test fun enabledFeatureUsesExpandedRailNotCompactBottomNavigation() {
        assertTrue(groupsIoDestinationVisible(enabled = true, compact = false))
        assertFalse(groupsIoDestinationVisible(enabled = true, compact = true))
    }

    @Test fun paginationTreatsTokenAsOpaqueAndRequiresItWhenMorePagesExist() {
        val (token, more) = groupsIoPagination(JSONObject(fixture("groups-page-1.json")))
        assertTrue(more)
        assertEquals("opaque-next-2", token)
        assertThrows(GroupsIoApiException::class.java) { groupsIoPagination(JSONObject("""{"object":"list","has_more":true,"data":[]}""")) }
    }

    @Test fun messageBodyNormalisationRemovesExecutableMarkupWithoutInventingContent() {
        val value = normaliseBody("<p>Hello &amp; &#34;73&#34;</p><script>steal()</script><iframe src='x'>bad</iframe><br>Second line")
        assertEquals("Hello & \"73\"\n\nSecond line", value)
        assertFalse(value.contains("steal"))
    }

    @Test fun documentedErrorsMapToUsefulNonSensitiveCategories() {
        assertEquals("credential", GroupsIoApiException.from(401, fixture("error-unauthorized.json")).category)
        assertEquals("permission", GroupsIoApiException.from(403, """{"object":"error","type":"inadequate_permissions"}""").category)
        assertEquals("rate_limited", GroupsIoApiException.from(429, "{}").category)
        assertFalse(GroupsIoApiException.from(401, fixture("error-unauthorized.json")).message.contains("Bearer"))
    }

    @Test fun attachmentWithoutServerIdGetsStablePrivateIdentity() {
        val value = JSONObject("""{"filename":"front panel.png","download_url":"https://example.invalid/signed"}""")
        val first = groupsIoAttachmentId(value, "front panel.png", "https://example.invalid/signed", 0)
        assertTrue(first > 0)
        assertEquals(first, groupsIoAttachmentId(value, "front panel.png", "https://example.invalid/signed", 0))
        assertEquals(73L, groupsIoAttachmentId(JSONObject("""{"id":73}"""), "front panel.png", "https://example.invalid/signed", 0))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
}
