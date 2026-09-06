package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Kx2ControlRulesTest {
    @Test fun legacyElecraftSelectionMigratesToKx3() {
        assertEquals(RadioFamily.ELECRAFT_KX3, decodeRadioFamily("ELECRAFT_KX"))
        assertEquals(RadioFamily.ELECRAFT_KX3, decodeRadioFamily(null))
        assertEquals(RadioFamily.ELECRAFT_KX2, decodeRadioFamily("ELECRAFT_KX2"))
    }

    @Test fun kx2FrontPanelUsesModelSpecificTable8aCodes() {
        val commands = kx2FaceKeys.flatMap { listOf(it.tapCommand, it.holdCommand) }
        assertTrue("SWT08;" in commands) // MODE on KX2, not KX3 BAND+
        assertTrue("SWT14;" in commands) // BAND on KX2
        assertTrue("SWT44;" in commands) // A/B on KX2
        assertEquals(24, commands.distinct().size)
    }

    @Test fun kx2ModePickerDoesNotOfferUnsupportedFm() {
        assertFalse(kx2ModeCommands.any { it.first == "FM" || it.second == "MD4;" })
        assertTrue(kx2ModeCommands.any { it.first == "DATA" })
    }

    @Test fun virtualBandButtonsClampAndMoveAcrossKx2Bands() {
        assertEquals("BN02;", kx2AdjacentBandCommand(7_100_000L, -1))
        assertEquals("BN04;", kx2AdjacentBandCommand(7_100_000L, 1))
        assertEquals("BN00;", kx2AdjacentBandCommand(1_800_000L, -1))
        assertEquals("BN09;", kx2AdjacentBandCommand(28_500_000L, 1))
    }
}
