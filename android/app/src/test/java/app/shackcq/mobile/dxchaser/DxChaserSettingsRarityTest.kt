package app.shackcq.mobile.dxchaser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DxChaserSettingsRarityTest {
    @Test fun settingsImportClampsBoundsAndCannotRestoreActiveState() {
        val parsed = DxChaserSettingsDocument.parse("""{
            "version":1,"defaultOperatingMode":"CHASE_SESSION","normalAttemptLimit":999,
            "scarceAttemptLimit":999,"atnoAttemptLimit":999,"preEngagementTimeoutSeconds":99999,
            "engagedTimeoutSeconds":99999,"sessionTimeoutSeconds":999999,"minimumSnr":-999,
            "selectedModes":["FT8","BAD"],"selectedBands":["20m","invalid"],
            "activeSession":true,"pendingCallIntent":"K1ABC","txArmed":true
        }""")
        assertEquals(10, parsed.normalAttemptLimit); assertEquals(12, parsed.scarceAttemptLimit)
        assertEquals(20, parsed.atnoAttemptLimit); assertEquals(600, parsed.preEngagementTimeoutSeconds)
        assertEquals(600, parsed.engagedTimeoutSeconds); assertEquals(7_200, parsed.sessionTimeoutSeconds)
        assertEquals(-30, parsed.minimumSnr); assertEquals(setOf("FT8"), parsed.selectedModes)
        assertEquals(setOf("20m"), parsed.selectedBands)
        assertFalse(parsed.toJson().contains("activeSession")); assertFalse(parsed.toJson().contains("txArmed"))
    }

    @Test fun rarityImportIsBoundedValidatedAndTruthful() {
        val valid = """{"formatVersion":1,"sourceLabel":"Operator reviewed","sourceDate":"2026-08-01",
            "entities":[{"entityIdentifier":"291","rank":12},{"entityIdentifier":"339","tier":3}]}""".toByteArray()
        val parsed = DxChaserRarityParser.parseJson(valid, LocalDate.of(2026, 8, 22))
        assertEquals(2, parsed.rows.size); assertEquals(64, parsed.digest.length)
        assertTrue(parsed.rows.all { it.origin == DxChaserRarityOrigin.USER_IMPORTED })
        val contradictory = """{"formatVersion":1,"sourceLabel":"x","sourceDate":"2026-08-01",
            "entities":[{"entityIdentifier":"291","rank":1},{"entityIdentifier":"291","tier":2}]}""".toByteArray()
        assertTrue(runCatching { DxChaserRarityParser.parseJson(contradictory) }.isFailure)
        assertTrue(runCatching { DxChaserRarityParser.parseJson("<html>bad</html>".toByteArray()) }.isFailure)
        assertTrue(runCatching { DxChaserRarityParser.parseJson(ByteArray(DxChaserRarityParser.MAX_BYTES + 1)) }.isFailure)
    }
}
