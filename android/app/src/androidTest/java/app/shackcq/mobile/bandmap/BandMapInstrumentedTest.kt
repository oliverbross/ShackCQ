// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BandMapInstrumentedTest {
    private lateinit var context: Context

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(BAND_MAP_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun settingsRoundTripIsAtomicBoundedAndKeepsLastGoodOnMalformedImport() {
        val store = BandMapStateStore(context)
        val expected = BandMapSettings(selectedLayout = BandMapLayoutMode.MULTI_HORIZONTAL,
            selectedBands = listOf("6m", "20m"), activePresetId = "contest",
            marks = listOf(BandMapMark("K1ABC/P", "20m", 14_074_000, setOf(BandMapMarkKind.WATCH), 1_000)))
        store.save(expected)
        assertEquals(expected, BandMapStateStore(context).load())
        assertThrows(IllegalArgumentException::class.java) { store.importDocument("{\"schema\":99}") }
        assertEquals(expected, BandMapStateStore(context).load())
        val raw = context.getSharedPreferences(BAND_MAP_PREFERENCES, Context.MODE_PRIVATE)
        assertTrue(raw.contains("document_v1") && raw.contains("document_last_good"))
    }

    @Test fun builtInPresetsRemainEditableDataAndColourVisionPaletteExists() {
        val store = BandMapStateStore(context)
        val loaded = store.load()
        val renamed = loaded.copy(presets = loaded.presets.map { if (it.id == "contest") it.copy(label = "My S and P") else it })
        store.save(renamed)
        val restored = BandMapStateStore(context).load()
        assertEquals("My S and P", restored.presets.first { it.id == "contest" }.label)
        assertEquals("COLOUR_VISION_FRIENDLY", restored.palette)
    }
}
