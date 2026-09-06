package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppBrowserTest {
    @Test
    fun acceptsOnlyHttpsUrlsWithHosts() {
        assertEquals("https://amsat-dl.org/en/", validatedInAppBrowserUrl(" https://amsat-dl.org/en/ "))
        assertNull(validatedInAppBrowserUrl("http://amsat-dl.org"))
        assertNull(validatedInAppBrowserUrl("file:///data/local/tmp/page.html"))
        assertNull(validatedInAppBrowserUrl("javascript:alert(1)"))
        assertNull(validatedInAppBrowserUrl("https://"))
        assertNull(validatedInAppBrowserUrl("https://user:secret@example.com/"))
        assertEquals("mailto:operator@example.com", validatedExternalBrowserUrl("mailto:operator@example.com"))
        assertNull(validatedExternalBrowserUrl("file:///data/local/tmp/page.html"))
    }
}
