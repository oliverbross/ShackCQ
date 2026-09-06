package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class QsoProjectionStoreTest {
    @Test fun zeroIsNotAWorkedDxccEntity() {
        assertEquals("", QsoProjectionStore.normalizeDxcc("0"))
        assertEquals("", QsoProjectionStore.normalizeDxcc(" 0 "))
        assertEquals("291", QsoProjectionStore.normalizeDxcc(" 291 "))
    }

    @Test fun onlyCanonicalContinentCodesAreProjected() {
        assertEquals("EU", QsoProjectionStore.normalizeContinent(" eu "))
        assertEquals("", QsoProjectionStore.normalizeContinent("??"))
        assertEquals("", QsoProjectionStore.normalizeContinent("maritime mobile"))
    }
}
