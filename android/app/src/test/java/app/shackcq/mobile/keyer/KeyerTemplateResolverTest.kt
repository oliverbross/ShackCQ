package app.shackcq.mobile.keyer

import org.junit.Assert.*
import org.junit.Test

class KeyerTemplateResolverTest {
    private fun context() = KeyerContextSnapshot(7, 3, "radio-1", true, true, KeyerMode.CW,
        KeyerProfileId("general-cw"), myCall = "OM0RX", call = "VK1AA", rst = "599", rstSent = "579",
        rstRecv = "589", serial = "007", exchange = "001 NT", grid = "JN88TQ", reference = "VKFF-0001", band = "20M")

    @Test fun allSupportedTokensResolveDeterministically() {
        mapOf("MYCALL" to "OM0RX", "CALL" to "VK1AA", "RST" to "599", "RST_SENT" to "579",
            "RST_RECV" to "589", "SERIAL" to "007", "EXCHANGE" to "001 NT", "GRID" to "JN88TQ",
            "REFERENCE" to "VKFF-0001", "MODE" to "CW", "BAND" to "20M").forEach { (token, expected) ->
            assertEquals(token, expected, KeyerTemplateResolver.resolve("{$token}", context()).text)
        }
    }

    @Test fun tokenNamesAreCaseInsensitiveAndWhitespaceIsStable() {
        assertEquals("CQ OM0RX DE VK1AA", KeyerTemplateResolver.resolve(" CQ   {mycall} DE {Call} ", context()).text)
    }

    @Test fun unknownTokenIsRejected() { assertEquals(KeyerFailureReason.CwTextInvalid, KeyerTemplateResolver.resolve("{POWER}", context()).error) }
    @Test fun requiredMissingTokenIsRejected() { assertEquals(KeyerFailureReason.CwTextInvalid, KeyerTemplateResolver.resolve("{CALL}", context().copy(call = "")).error) }
    @Test fun optionalMissingTokenIsExplicitlyBlank() { assertEquals("CQ", KeyerTemplateResolver.resolve("CQ {CALL?}", context().copy(call = "")).text) }
    @Test fun backendCapacityIsNotSilentlyTruncated() { assertEquals(KeyerFailureReason.BackendCapacityExceeded, KeyerTemplateResolver.resolve("1234567890123456789012345", context()).error) }
    @Test fun unsafeCharactersAreRejected() { assertEquals(KeyerFailureReason.BackendCapacityExceeded, KeyerTemplateResolver.resolve("CQ;TX", context()).error) }

    @Test fun serialFormattingSupportsWidthOverflowAndCutNumbers() {
        assertEquals("007", SerialFormat(3, leadingZeroes = true).format(7))
        assertEquals("1234", SerialFormat(3, leadingZeroes = true).format(1234))
        assertEquals("ATN", SerialFormat(3, leadingZeroes = true, cutNumbers = true).format(109))
    }
}
