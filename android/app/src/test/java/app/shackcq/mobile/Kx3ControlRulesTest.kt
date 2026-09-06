package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class Kx3ControlRulesTest {
    @Test fun afGainUsesTheFullElecraftCatRange() {
        assertEquals("AG000;", kx3AfGainCommand(-1))
        assertEquals("AG255;", kx3AfGainCommand(255))
        assertEquals("AG255;", kx3AfGainCommand(999))
    }

    @Test fun rfGainUsesTheFullElecraftCatRange() {
        assertEquals("RG000;", kx3RfGainCommand(-1))
        assertEquals("RG250;", kx3RfGainCommand(250))
        assertEquals("RG250;", kx3RfGainCommand(999))
    }
}
