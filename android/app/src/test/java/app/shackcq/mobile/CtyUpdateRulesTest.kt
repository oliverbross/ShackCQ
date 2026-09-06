package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class CtyUpdateRulesTest {
    @Test fun newerRemoteTimestampIsAvailable() {
        assertEquals(CtyUpdateDecision.AVAILABLE, decideCtyUpdate(100, 1_000, 200, 1_000))
    }

    @Test fun matchingTimestampAndLengthIsCurrent() {
        assertEquals(CtyUpdateDecision.CURRENT, decideCtyUpdate(200, 1_000, 200, 1_000))
    }

    @Test fun matchingTimestampWithChangedLengthIsAvailable() {
        assertEquals(CtyUpdateDecision.AVAILABLE, decideCtyUpdate(200, 1_000, 200, 1_001))
    }

    @Test fun legacyInstallWithMatchingLengthRequiresContentVerification() {
        assertEquals(CtyUpdateDecision.VERIFY_CONTENT, decideCtyUpdate(0, 1_000, 200, 1_000))
    }

    @Test fun legacyInstallWithChangedLengthIsAvailableWithoutDownload() {
        assertEquals(CtyUpdateDecision.AVAILABLE, decideCtyUpdate(0, 1_000, 200, 1_001))
    }
}
