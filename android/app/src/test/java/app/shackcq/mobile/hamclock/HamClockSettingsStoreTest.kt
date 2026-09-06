package app.shackcq.mobile.hamclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HamClockSettingsStoreTest {
    @Test
    fun exhaustiveSettingsRoundTripPreservesEveryPreference() {
        val panels = defaultHamClockPanels().mapIndexed { index, panel ->
            panel.copy(visible = index % 2 == 0, order = 20 - index, column = index % 3,
                columnSpan = if (panel.id == HamClockPanelId.MAP) 2 else 1, rowSpan = 2, collapsed = index % 3 == 0)
        }
        val layers = defaultHamClockMapLayers().mapIndexed { index, layer ->
            layer.copy(visible = index % 2 == 1, opacity = (index + 1) / 10f)
        }
        val settings = HamClockUserSettings(
            panels = panels,
            map = HamClockMapPreference(HamClockBasemap.SATELLITE, false, -33.865, 151.209, 5.75, layers),
            cluster = HamClockClusterPreference(false, 45, 20, 333,
                HamClockSpotFilter(setOf("20m", "6m"), setOf("FT8", "CW"), setOf("EU", "OC"), "vk9", -18)),
            pskReporter = HamClockPskPreference(true, HamClockPskDirection.BEING_HEARD, 120, 300, 444,
                HamClockSpotFilter(setOf("40m"), setOf("FT4"), setOf("NA"), "n0call", -22)),
            portable = HamClockPortablePreference(setOf("POTA", "SOTA"), 60, 321, true, true),
            satellites = HamClockSatellitePreference(setOf(25544, 43017), 48, 20, false, true, true),
            dxTarget = HamClockDxTarget("vk9xy", "QF56ab", -31.0, 159.0, true),
            display = HamClockDisplayPreference(HamClockDensity.LARGE_TOUCH, HamClockTimeZoneMode.UTC,
                HamClockHourFormat.H12, HamClockUnitSystem.IMPERIAL, true, true),
        )
        val document = HamClockSettingsDocument(settings = settings, activeProfileId = "travel", profiles = listOf(
            HamClockNamedProfile("travel", "Travel", settings, 100, 200)
        ))

        val decoded = HamClockSettingsCodec.decode(HamClockSettingsCodec.encode(document))

        assertEquals(HamClockSettingsCodec.normalize(document), decoded)
    }

    @Test
    fun namedProfilesApplyPersistRenameAndDelete() {
        val persistence = FakePersistence()
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = HamClockSettingsStore(persistence, clock, { "field-day" })
        store.setPanel(defaultHamClockPanels().first { it.id == HamClockPanelId.SOLAR }.copy(visible = false, column = 2))
        val saved = store.saveProfile("Field Day")
        assertEquals("field-day", saved.id)
        assertEquals("field-day", store.snapshot().activeProfileId)

        store.setPanel(saved.settings.panels.first { it.id == HamClockPanelId.SOLAR }.copy(visible = true))
        assertNull(store.snapshot().activeProfileId)
        store.applyProfile("field-day")
        assertFalse(store.settings().panels.first { it.id == HamClockPanelId.SOLAR }.visible)
        assertEquals("Portable", store.renameProfile("field-day", "Portable").name)

        val reloaded = HamClockSettingsStore(persistence, clock, { "unused" })
        assertEquals("Portable", reloaded.profiles().single().name)
        assertEquals("field-day", reloaded.snapshot().activeProfileId)
        reloaded.deleteProfile("field-day")
        assertTrue(reloaded.profiles().isEmpty())
        assertNull(reloaded.snapshot().activeProfileId)
    }

    @Test
    fun resetAndClearOperationsArePersisted() {
        val store = HamClockSettingsStore(FakePersistence(), Clock.systemUTC(), { "profile" })
        val solar = store.settings().panels.first { it.id == HamClockPanelId.SOLAR }
        store.setPanel(solar.copy(visible = false, rowSpan = 4))
        store.saveProfile("Changed")
        store.clearActiveProfile()
        assertNull(store.snapshot().activeProfileId)
        assertFalse(store.settings().panels.first { it.id == HamClockPanelId.SOLAR }.visible)

        store.resetPanel(HamClockPanelId.SOLAR)
        assertEquals(defaultHamClockPanels().first { it.id == HamClockPanelId.SOLAR },
            store.settings().panels.first { it.id == HamClockPanelId.SOLAR })
        store.setPanel(HamClockPanelPreference("future.panel"))
        store.resetPanel("future.panel")
        assertFalse(store.settings().panels.any { it.id == "future.panel" })
    }

    @Test
    fun importIsValidatedBeforeAtomicReplacement() {
        val persistence = FakePersistence()
        val store = HamClockSettingsStore(persistence, Clock.systemUTC(), { "id" })
        val before = store.exportJson()
        val future = JSONObject(before).put("version", HamClockSettingsCodec.CURRENT_VERSION + 1).toString()

        assertThrows(IllegalArgumentException::class.java) { store.importJson(future) }
        assertEquals(before, store.exportJson())

        val imported = HamClockSettingsDocument(settings = store.settings().copy(
            display = store.settings().display.copy(lowDataMode = true)
        ))
        val result = store.importJson(HamClockSettingsCodec.encode(imported))
        assertEquals(HamClockSettingsCodec.CURRENT_VERSION, result.version)
        assertTrue(store.settings().display.lowDataMode)
    }

    @Test
    fun exportWhitelistsPresentationDataAndDropsCredentialLikeFields() {
        val root = JSONObject(HamClockSettingsCodec.encode(HamClockSettingsDocument()))
            .put("api_key", "do-not-retain")
            .put("password", "do-not-retain")
        root.getJSONObject("settings").put("radio_password", "do-not-retain")

        val exported = HamClockSettingsCodec.encode(HamClockSettingsCodec.decode(root.toString()))

        assertFalse(exported.contains("do-not-retain"))
        assertFalse(exported.contains("api_key"))
        assertFalse(exported.contains("password"))
    }

    @Test
    fun malformedValuesAreBoundedAndFuturePanelAndLayerIdsSurvive() {
        val custom = HamClockUserSettings(
            panels = listOf(HamClockPanelPreference("future.panel", order = -3, column = 99, columnSpan = 99, rowSpan = 0)),
            map = HamClockMapPreference(centerLatitude = 999.0, centerLongitude = 725.0, zoom = 99.0,
                layers = listOf(HamClockMapLayerPreference("future.layer", opacity = 2.5f))),
            cluster = HamClockClusterPreference(windowMinutes = -1, refreshSeconds = 0, maximumSpots = 99_999),
            satellites = HamClockSatellitePreference(setOf(-1, 25544), 999, -20),
        )

        val normalized = HamClockSettingsCodec.normalizeSettings(custom)

        assertTrue(normalized.panels.any { it.id == "future.panel" })
        assertEquals(2, normalized.panels.first { it.id == "future.panel" }.column)
        assertEquals(3, normalized.panels.first { it.id == "future.panel" }.columnSpan)
        assertTrue(normalized.map.layers.any { it.id == "future.layer" })
        assertEquals(1f, normalized.map.layers.first { it.id == "future.layer" }.opacity)
        assertEquals(90.0, normalized.map.centerLatitude, 0.0)
        assertEquals(5.0, normalized.map.centerLongitude, 0.0)
        assertEquals(20.0, normalized.map.zoom, 0.0)
        assertEquals(setOf(25544), normalized.satellites.trackedNoradIds)
    }

    @Test
    fun firstLoadMigratesExistingDashboardAndMapFlags() {
        val persistence = FakePersistence(legacy = mapOf(
            "solar" to false, "portable" to true, "map_paths" to false,
            "map_greyline" to false, "map_psk" to true,
        ))

        val store = HamClockSettingsStore(persistence, Clock.systemUTC(), { "id" })

        assertFalse(store.settings().panels.first { it.id == HamClockPanelId.SOLAR }.visible)
        assertTrue(store.settings().panels.first { it.id == HamClockPanelId.PORTABLE }.visible)
        assertFalse(store.settings().map.layers.first { it.id == HamClockMapLayerId.DX_PATHS }.visible)
        assertFalse(store.settings().map.layers.first { it.id == HamClockMapLayerId.GRAYLINE }.visible)
        assertTrue(store.settings().map.layers.first { it.id == HamClockMapLayerId.PSK_REPORTER }.visible)
        assertTrue(persistence.value?.contains(HamClockSettingsCodec.SCHEMA) == true)
    }

    @Test
    fun versionOneFixedColumnsMigrateToApprovedOperatorLayout() {
        val root = JSONObject(HamClockSettingsCodec.encode(HamClockSettingsDocument())).put("version", 1)
        val panels = root.getJSONObject("settings").getJSONArray("panels")
        val old = mapOf(
            HamClockPanelId.DX_TARGET to (0 to 1), HamClockPanelId.SOLAR to (0 to 3),
            HamClockPanelId.VOACAP to (0 to 4), HamClockPanelId.PSK_REPORTER to (2 to 1),
            HamClockPanelId.DX_EXPEDITIONS to (2 to 2),
        )
        for (index in 0 until panels.length()) panels.getJSONObject(index).let { panel ->
            old[panel.getString("id")]?.let { (column, order) -> panel.put("column", column).put("order", order) }
        }

        val settings = HamClockSettingsCodec.decode(root.toString()).settings

        assertEquals(0, settings.panels.first { it.id == HamClockPanelId.PSK_REPORTER }.column)
        assertEquals(0, settings.panels.first { it.id == HamClockPanelId.DX_EXPEDITIONS }.column)
        assertEquals(2, settings.panels.first { it.id == HamClockPanelId.SOLAR }.column)
        assertEquals(2, settings.panels.first { it.id == HamClockPanelId.DX_TARGET }.column)
        assertEquals(2, settings.panels.first { it.id == HamClockPanelId.VOACAP }.column)
    }

    @Test
    fun knownPanelsNormalizeToRegistryLayoutCapabilities() {
        val malformed = HamClockUserSettings(panels = listOf(
            HamClockPanelPreference(HamClockPanelId.MAP, column = 7, columnSpan = 8, rowSpan = 99, collapsed = true),
            HamClockPanelPreference(HamClockPanelId.STATION, column = 1, columnSpan = 7, collapsed = true),
        ))
        val normalized = HamClockSettingsCodec.normalizeSettings(malformed)
        val map = normalized.panels.first { it.id == HamClockPanelId.MAP }
        val station = normalized.panels.first { it.id == HamClockPanelId.STATION }
        assertEquals(1, map.column)
        assertEquals(2, map.columnSpan)
        assertEquals(4, map.rowSpan)
        assertFalse(map.collapsed)
        assertTrue(station.column in hamClockModuleSpec(HamClockPanelId.STATION)!!.allowedColumns)
        assertEquals(1, station.columnSpan)
    }

    @Test
    fun hiddenMapSurvivesProfileApplyExportAndImport() {
        val persistence = FakePersistence()
        val store = HamClockSettingsStore(persistence, Clock.systemUTC(), { "hidden-map" })
        val map = store.settings().panels.first { it.id == HamClockPanelId.MAP }
        store.setPanel(map.copy(visible = false))
        store.saveProfile("No map")
        store.setPanel(map.copy(visible = true))
        store.applyProfile("hidden-map")
        assertFalse(store.settings().panels.first { it.id == HamClockPanelId.MAP }.visible)

        val imported = HamClockSettingsStore(FakePersistence(), Clock.systemUTC(), { "unused" })
        imported.importJson(store.exportJson(true))
        assertFalse(imported.settings().panels.first { it.id == HamClockPanelId.MAP }.visible)
    }

    private class FakePersistence(
        var value: String? = null,
        private val legacy: Map<String, Boolean> = emptyMap(),
    ) : HamClockDocumentPersistence {
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
        override fun legacyBoolean(key: String): Boolean? = legacy[key]
    }
}
