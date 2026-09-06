package app.shackcq.mobile.hamclock

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object HamClockSettingsCodec {
    const val SCHEMA = "shackcq.hamclock.settings"
    const val CURRENT_VERSION = 7
    const val MAX_PROFILES = 24
    const val MAX_JSON_BYTES = 1_048_576

    fun encode(document: HamClockSettingsDocument, indentSpaces: Int = 0): String {
        val value = normalize(document)
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("version", CURRENT_VERSION)
            .put("settings", encodeSettings(value.settings))
            .put("profiles", JSONArray().also { rows ->
                value.profiles.forEach { profile -> rows.put(encodeProfile(profile)) }
            })
        value.activeProfileId?.let { root.put("active_profile_id", it) }
        return if (indentSpaces > 0) root.toString(indentSpaces.coerceIn(0, 8)) else root.toString()
    }

    fun decode(json: String): HamClockSettingsDocument {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "HamClock settings are too large" }
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("Invalid HamClock settings JSON", it) }
        require(root.optString("schema") == SCHEMA) { "Unsupported HamClock settings schema" }
        val version = root.optInt("version", -1)
        require(version in 1..CURRENT_VERSION) { "Unsupported HamClock settings version: $version" }
        val settings = root.optJSONObject("settings")?.let(::decodeSettings) ?: HamClockUserSettings()
        val rows = root.optJSONArray("profiles") ?: JSONArray()
        require(rows.length() <= MAX_PROFILES) { "Too many HamClock profiles" }
        val profiles = buildList {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { add(decodeProfile(it)) }
        }
        val decoded = HamClockSettingsDocument(
            version = version,
            settings = settings,
            activeProfileId = root.optionalString("active_profile_id"),
            profiles = profiles,
        )
        return normalize(if (version == 1) migrateVersionOne(decoded) else decoded)
    }

    fun normalize(document: HamClockSettingsDocument): HamClockSettingsDocument {
        val profiles = document.profiles.asSequence()
            .map(::normalizeProfile)
            .distinctBy(HamClockNamedProfile::id)
            .take(MAX_PROFILES)
            .toList()
        return HamClockSettingsDocument(
            version = CURRENT_VERSION,
            settings = normalizeSettings(document.settings),
            activeProfileId = document.activeProfileId?.takeIf { active -> profiles.any { it.id == active } },
            profiles = profiles,
        )
    }

    fun normalizeSettings(value: HamClockUserSettings): HamClockUserSettings {
        val suppliedPanels = value.panels.asSequence().mapNotNull(::normalizePanel).distinctBy { it.id }.toMutableList()
        defaultHamClockPanels().filter { default -> suppliedPanels.none { it.id == default.id } }.forEach(suppliedPanels::add)
        val suppliedLayers = value.map.layers.asSequence().mapNotNull(::normalizeLayer).distinctBy { it.id }.toMutableList()
        defaultHamClockMapLayers().filter { default -> suppliedLayers.none { it.id == default.id } }.forEach(suppliedLayers::add)
        val target = value.dxTarget?.let(::normalizeTarget)?.takeUnless {
            it.callsign.isBlank() && it.grid.isBlank() && it.latitude == null && it.longitude == null
        }
        return value.copy(
            panels = suppliedPanels,
            map = value.map.copy(
                centerLatitude = value.map.centerLatitude.finiteOr(0.0).coerceIn(-90.0, 90.0),
                centerLongitude = wrapLongitude(value.map.centerLongitude.finiteOr(0.0)),
                zoom = value.map.zoom.finiteOr(1.2).coerceIn(0.0, 20.0),
                layers = suppliedLayers,
            ),
            cluster = value.cluster.copy(
                windowMinutes = value.cluster.windowMinutes.coerceIn(1, 24 * 60),
                refreshSeconds = value.cluster.refreshSeconds.coerceIn(5, 60 * 60),
                maximumSpots = value.cluster.maximumSpots.coerceIn(1, 2_000),
                filter = normalizeFilter(value.cluster.filter),
            ),
            pskReporter = value.pskReporter.copy(
                windowMinutes = value.pskReporter.windowMinutes.takeIf { it in setOf(2, 5, 10, 15, 30, 60, 120) } ?: 15,
                refreshSeconds = value.pskReporter.refreshSeconds.coerceIn(300, 60 * 60),
                maximumReports = value.pskReporter.maximumReports.coerceIn(1, 500),
                filter = normalizeFilter(value.pskReporter.filter),
            ),
            rbn = value.rbn.copy(
                windowMinutes = value.rbn.windowMinutes.takeIf { it in setOf(2, 5, 10, 15, 30) } ?: 10,
                maximumRows = value.rbn.maximumRows.coerceIn(1, 120),
                bands = cleanTokens(value.rbn.bands, 24),
                modes = cleanTokens(value.rbn.modes, 8),
                minimumSnr = value.rbn.minimumSnr?.coerceIn(-100, 100),
                skimmerCall = value.rbn.skimmerCall.trim().uppercase(Locale.US).take(24),
                dxCall = value.rbn.dxCall.trim().uppercase(Locale.US).take(24),
            ),
            wspr = value.wspr.copy(
                windowMinutes = value.wspr.windowMinutes.takeIf { it in setOf(2, 5, 10, 15, 30, 60, 120) } ?: 30,
                band = value.wspr.band.trim().uppercase(Locale.US).take(12).ifBlank { "ALL" },
                minimumSnr = value.wspr.minimumSnr?.coerceIn(-100, 100),
                maximumPaths = value.wspr.maximumPaths.coerceIn(1, 100),
                regionalEnabled = value.wspr.regionalEnabled,
            ),
            bandHealth = value.bandHealth.copy(
                windowMinutes = value.bandHealth.windowMinutes.takeIf { it in setOf(5, 10, 15, 30, 60) } ?: 15,
                mode = value.bandHealth.mode.trim().uppercase(Locale.US).take(12).ifBlank { "ALL" },
                enabledSources = cleanTokens(value.bandHealth.enabledSources, 8) - "QSO_HISTORY",
                visibleBands = cleanTokens(value.bandHealth.visibleBands, 24),
            ),
            outlook = value.outlook.copy(
                visibleBands = cleanTokens(value.outlook.visibleBands, 24),
                worldOpacity = value.outlook.worldOpacity.takeIf(Float::isFinite)?.coerceIn(.15f, .9f) ?: .58f,
                retentionDays = 180,
            ),
            portable = value.portable.copy(
                enabledPrograms = cleanTokens(value.portable.enabledPrograms, 12),
                windowMinutes = value.portable.windowMinutes.coerceIn(1, 24 * 60),
                maximumSpots = value.portable.maximumSpots.coerceIn(1, 2_000),
            ),
            satellites = value.satellites.copy(
                trackedNoradIds = value.satellites.trackedNoradIds.filter { it in 1..99_999_999 }.take(128).toSet(),
                passWindowHours = value.satellites.passWindowHours.coerceIn(1, 168),
                minimumElevationDegrees = value.satellites.minimumElevationDegrees.coerceIn(0, 90),
            ),
            propagation = value.propagation.copy(
                txPowerWatts = value.propagation.txPowerWatts.coerceIn(1, 1_000_000),
                txGainDb = value.propagation.txGainDb.finiteOr(0.0).coerceIn(-100.0, 100.0),
                rxGainDb = value.propagation.rxGainDb.finiteOr(0.0).coerceIn(-100.0, 100.0),
                requiredReliability = value.propagation.requiredReliability.coerceIn(1, 99),
                requiredSnrDb = value.propagation.requiredSnrDb.finiteOr(10.0).coerceIn(-50.0, 100.0),
                bandwidthHz = value.propagation.bandwidthHz.coerceIn(1, 1_000_000),
                selectedFrequenciesMHz = value.propagation.selectedFrequenciesMHz.asSequence()
                    .filter(Double::isFinite).filter { it in 0.1..60.0 }.distinct().take(24).toList()
                    .ifEmpty { HamClockPropagationPreference().selectedFrequenciesMHz },
                coverageResolution = value.propagation.coverageResolution.takeIf { it in setOf(288, 720) } ?: 288,
            ),
            idReminder = value.idReminder.copy(
                intervalMinutes = value.idReminder.intervalMinutes.takeIf { it in setOf(5, 10, 15, 20, 30) } ?: 10,
                lastResetEpochSeconds = value.idReminder.lastResetEpochSeconds.coerceAtLeast(0),
            ),
            shackDisplay = value.shackDisplay.copy(
                rotationSeconds = value.shackDisplay.rotationSeconds.takeIf { it in setOf(15, 30, 60, 120) } ?: 30,
                selectedProfileId = value.shackDisplay.selectedProfileId?.trim()?.take(64)?.takeIf(String::isNotBlank),
            ),
            dxTarget = target,
        )
    }

    /** V1 had no position controls; migrate its fixed columns to the operator-approved V2 layout. */
    private fun migrateVersionOne(document: HamClockSettingsDocument): HamClockSettingsDocument {
        val oldPositions = mapOf(
            HamClockPanelId.STATION to (0 to 0), HamClockPanelId.DX_TARGET to (0 to 1),
            HamClockPanelId.WEATHER to (0 to 2), HamClockPanelId.SOLAR to (0 to 3),
            HamClockPanelId.VOACAP to (0 to 4), HamClockPanelId.MAP to (1 to 0),
            HamClockPanelId.DX_CLUSTER to (2 to 0), HamClockPanelId.PSK_REPORTER to (2 to 1),
            HamClockPanelId.DX_EXPEDITIONS to (2 to 2), HamClockPanelId.PORTABLE to (2 to 3),
            HamClockPanelId.CONTESTS to (2 to 4), HamClockPanelId.BAND_ACTIVITY to (2 to 5),
            HamClockPanelId.SATELLITES to (2 to 6),
        )
        val approved = defaultHamClockPanels().associateBy(HamClockPanelPreference::id)
        fun migrate(settings: HamClockUserSettings) = settings.copy(panels = settings.panels.map { panel ->
            val old = oldPositions[panel.id]
            val next = approved[panel.id]
            if (old != null && next != null && panel.column == old.first && panel.order == old.second)
                panel.copy(column = next.column, order = next.order) else panel
        })
        return document.copy(settings = migrate(document.settings), profiles = document.profiles.map { profile ->
            profile.copy(settings = migrate(profile.settings))
        })
    }

    private fun encodeSettings(value: HamClockUserSettings): JSONObject {
        val settings = normalizeSettings(value)
        return JSONObject()
            .put("panels", JSONArray().also { rows -> settings.panels.forEach { rows.put(encodePanel(it)) } })
            .put("map", encodeMap(settings.map))
            .put("cluster", encodeCluster(settings.cluster))
            .put("psk_reporter", encodePsk(settings.pskReporter))
            .put("dx_news", encodeDxNews(settings.dxNews))
            .put("rbn", encodeRbn(settings.rbn))
            .put("wspr", encodeWspr(settings.wspr))
            .put("ibp", encodeIbp(settings.ibp))
            .put("band_health", encodeBandHealth(settings.bandHealth))
            .put("outlook", encodeOutlook(settings.outlook))
            .put("portable", encodePortable(settings.portable))
            .put("satellites", encodeSatellites(settings.satellites))
            .put("display", encodeDisplay(settings.display))
            .put("propagation", encodePropagation(settings.propagation))
            .put("id_reminder", encodeIdReminder(settings.idReminder))
            .put("shack_display", encodeShackDisplay(settings.shackDisplay))
            .also { root -> settings.dxTarget?.let { root.put("dx_target", encodeTarget(it)) } }
    }

    private fun decodeSettings(root: JSONObject) = HamClockUserSettings(
        panels = root.optJSONArray("panels")?.objects(::decodePanel) ?: defaultHamClockPanels(),
        map = root.optJSONObject("map")?.let(::decodeMap) ?: HamClockMapPreference(),
        cluster = root.optJSONObject("cluster")?.let(::decodeCluster) ?: HamClockClusterPreference(),
        pskReporter = root.optJSONObject("psk_reporter")?.let(::decodePsk) ?: HamClockPskPreference(),
        dxNews = root.optJSONObject("dx_news")?.let(::decodeDxNews) ?: HamClockDxNewsPreference(),
        rbn = root.optJSONObject("rbn")?.let(::decodeRbn) ?: HamClockRbnPreference(),
        wspr = root.optJSONObject("wspr")?.let(::decodeWspr) ?: HamClockWsprPreference(),
        ibp = root.optJSONObject("ibp")?.let(::decodeIbp) ?: HamClockIbpPreference(),
        bandHealth = root.optJSONObject("band_health")?.let(::decodeBandHealth) ?: HamClockBandHealthPreference(),
        outlook = root.optJSONObject("outlook")?.let(::decodeOutlook) ?: HamClockOutlookPreference(),
        portable = root.optJSONObject("portable")?.let(::decodePortable) ?: HamClockPortablePreference(),
        satellites = root.optJSONObject("satellites")?.let(::decodeSatellites) ?: HamClockSatellitePreference(),
        dxTarget = root.optJSONObject("dx_target")?.let(::decodeTarget),
        display = root.optJSONObject("display")?.let(::decodeDisplay) ?: HamClockDisplayPreference(),
        propagation = root.optJSONObject("propagation")?.let(::decodePropagation) ?: HamClockPropagationPreference(),
        idReminder = root.optJSONObject("id_reminder")?.let(::decodeIdReminder) ?: HamClockIdReminderPreference(),
        shackDisplay = root.optJSONObject("shack_display")?.let(::decodeShackDisplay) ?: HamClockShackDisplayPreference(),
    )

    private fun encodePanel(value: HamClockPanelPreference) = JSONObject()
        .put("id", value.id).put("visible", value.visible).put("order", value.order)
        .put("column", value.column).put("column_span", value.columnSpan)
        .put("row_span", value.rowSpan).put("collapsed", value.collapsed)

    private fun decodePanel(row: JSONObject) = HamClockPanelPreference(
        id = row.optString("id"), visible = row.optBoolean("visible", true), order = row.optInt("order"),
        column = row.optInt("column"), columnSpan = row.optInt("column_span", 1),
        rowSpan = row.optInt("row_span", 1), collapsed = row.optBoolean("collapsed"),
    )

    private fun encodeMap(value: HamClockMapPreference) = JSONObject()
        .put("basemap", value.basemap.name).put("follow_station", value.followStation)
        .put("center_latitude", value.centerLatitude).put("center_longitude", value.centerLongitude)
        .put("zoom", value.zoom)
        .put("layers", JSONArray().also { rows -> value.layers.forEach { layer ->
            rows.put(JSONObject().put("id", layer.id).put("visible", layer.visible).put("opacity", layer.opacity.toDouble()))
        } })

    private fun decodeMap(row: JSONObject) = HamClockMapPreference(
        basemap = row.enum("basemap", HamClockBasemap.DARK),
        followStation = row.optBoolean("follow_station", true),
        centerLatitude = row.optDouble("center_latitude", 0.0),
        centerLongitude = row.optDouble("center_longitude", 0.0),
        zoom = row.optDouble("zoom", 1.2),
        layers = row.optJSONArray("layers")?.objects { layer ->
            HamClockMapLayerPreference(layer.optString("id"), layer.optBoolean("visible", true), layer.optDouble("opacity", 1.0).toFloat())
        } ?: defaultHamClockMapLayers(),
    )

    private fun encodeOutlook(value: HamClockOutlookPreference) = JSONObject()
        .put("enabled", value.enabled).put("default_window", value.defaultWindow.name)
        .put("visible_bands", value.visibleBands.strings()).put("world_layer_enabled", value.worldLayerEnabled)
        .put("world_opacity", value.worldOpacity.toDouble()).put("retention_days", 180)
        .put("show_on_compact", value.showOnCompact)

    private fun decodeOutlook(row: JSONObject) = HamClockOutlookPreference(
        enabled = row.optBoolean("enabled", true),
        defaultWindow = row.enum("default_window", HamClockOutlookWindow.MINUTES_60),
        visibleBands = row.optJSONArray("visible_bands")?.stringSet() ?: HamClockOutlookPreference().visibleBands,
        worldLayerEnabled = row.optBoolean("world_layer_enabled", false),
        worldOpacity = row.optDouble("world_opacity", .58).toFloat(), retentionDays = 180,
        showOnCompact = row.optBoolean("show_on_compact", false),
    )

    private fun encodeFilter(value: HamClockSpotFilter) = JSONObject()
        .put("bands", value.bands.strings()).put("modes", value.modes.strings())
        .put("continents", value.continents.strings()).put("call_query", value.callQuery)
        .also { row -> value.minimumSnr?.let { row.put("minimum_snr", it) } }

    private fun decodeFilter(row: JSONObject) = HamClockSpotFilter(
        bands = row.optJSONArray("bands").stringSet(), modes = row.optJSONArray("modes").stringSet(),
        continents = row.optJSONArray("continents").stringSet(), callQuery = row.optString("call_query"),
        minimumSnr = if (row.has("minimum_snr") && !row.isNull("minimum_snr")) row.optInt("minimum_snr") else null,
    )

    private fun encodeCluster(value: HamClockClusterPreference) = JSONObject()
        .put("enabled", value.enabled).put("window_minutes", value.windowMinutes)
        .put("refresh_seconds", value.refreshSeconds).put("maximum_spots", value.maximumSpots)
        .put("filter", encodeFilter(value.filter))

    private fun decodeCluster(row: JSONObject) = HamClockClusterPreference(
        enabled = row.optBoolean("enabled", true), windowMinutes = row.optInt("window_minutes", 30),
        refreshSeconds = row.optInt("refresh_seconds", 30), maximumSpots = row.optInt("maximum_spots", 100),
        filter = row.optJSONObject("filter")?.let(::decodeFilter) ?: HamClockSpotFilter(),
    )

    private fun encodePsk(value: HamClockPskPreference) = JSONObject()
        .put("enabled", value.enabled).put("direction", value.direction.name)
        .put("window_minutes", value.windowMinutes).put("refresh_seconds", value.refreshSeconds)
        .put("maximum_reports", value.maximumReports).put("filter", encodeFilter(value.filter))

    private fun decodePsk(row: JSONObject) = HamClockPskPreference(
        enabled = row.optBoolean("enabled", true), direction = when (row.optString("direction").uppercase(Locale.US)) {
            "HEARD" -> HamClockPskDirection.BEING_HEARD
            else -> row.enum("direction", HamClockPskDirection.BOTH)
        },
        windowMinutes = row.optInt("window_minutes", 15), refreshSeconds = row.optInt("refresh_seconds", 300),
        maximumReports = row.optInt("maximum_reports", 250),
        filter = row.optJSONObject("filter")?.let(::decodeFilter) ?: HamClockSpotFilter(),
    )

    private fun encodeDxNews(value: HamClockDxNewsPreference) = JSONObject()
        .put("source", value.source.name).put("compact_visible", value.compactVisible)

    private fun decodeDxNews(row: JSONObject) = HamClockDxNewsPreference(
        source = row.enum("source", HamClockDxNewsSource.ALL),
        compactVisible = row.optBoolean("compact_visible"),
    )

    private fun encodeRbn(value: HamClockRbnPreference) = JSONObject()
        .put("enabled", value.enabled).put("source", value.source.name).put("view_mode", value.viewMode.name)
        .put("window_minutes", value.windowMinutes)
        .put("maximum_rows", value.maximumRows).put("bands", value.bands.strings()).put("modes", value.modes.strings())
        .put("minimum_snr", value.minimumSnr).put("skimmer_call", value.skimmerCall).put("dx_call", value.dxCall)
        .put("watchlist_only", value.watchlistOnly).put("show_paths", value.showPaths)

    private fun decodeRbn(row: JSONObject) = HamClockRbnPreference(
        enabled = row.optBoolean("enabled", true), source = row.enum("source", HamClockRbnSource.CONFIGURED_RETAIL_CLUSTER),
        viewMode = row.enum("view_mode", HamClockRbnMode.ALL_RBN),
        windowMinutes = row.optInt("window_minutes", 10), maximumRows = row.optInt("maximum_rows", 120),
        bands = row.optJSONArray("bands").stringSet(), modes = row.optJSONArray("modes").stringSet(),
        minimumSnr = if (row.has("minimum_snr") && !row.isNull("minimum_snr")) row.optInt("minimum_snr") else null,
        skimmerCall = row.optString("skimmer_call"), dxCall = row.optString("dx_call"),
        watchlistOnly = row.optBoolean("watchlist_only"), showPaths = row.optBoolean("show_paths", true),
    )

    private fun encodeWspr(value: HamClockWsprPreference) = JSONObject()
        .put("personal_enabled", value.personalEnabled).put("direction", value.direction.name)
        .put("window_minutes", value.windowMinutes).put("band", value.band).put("minimum_snr", value.minimumSnr)
        .put("maximum_paths", value.maximumPaths).put("regional_enabled", value.regionalEnabled)
        .put("show_paths", value.showPaths)
        .put("show_regional_grid", value.showRegionalGrid)

    private fun decodeWspr(row: JSONObject) = HamClockWsprPreference(
        personalEnabled = row.optBoolean("personal_enabled", true), direction = row.enum("direction", HamClockPskDirection.BOTH),
        windowMinutes = row.optInt("window_minutes", 30), band = row.optString("band", "ALL"),
        minimumSnr = if (row.has("minimum_snr") && !row.isNull("minimum_snr")) row.optInt("minimum_snr") else null,
        maximumPaths = row.optInt("maximum_paths", 100), regionalEnabled = row.optBoolean("regional_enabled"),
        showPaths = row.optBoolean("show_paths", true),
        showRegionalGrid = row.optBoolean("show_regional_grid"),
    )

    private fun encodeIbp(value: HamClockIbpPreference) = JSONObject()
        .put("show_all_sites", value.showAllSites).put("show_paths", value.showPaths)

    private fun decodeIbp(row: JSONObject) = HamClockIbpPreference(
        showAllSites = row.optBoolean("show_all_sites", true), showPaths = row.optBoolean("show_paths", true),
    )

    private fun encodeBandHealth(value: HamClockBandHealthPreference) = JSONObject()
        .put("window_minutes", value.windowMinutes).put("mode", value.mode)
        .put("enabled_sources", value.enabledSources.strings()).put("visible_bands", value.visibleBands.strings())

    private fun decodeBandHealth(row: JSONObject) = HamClockBandHealthPreference(
        windowMinutes = row.optInt("window_minutes", 15), mode = row.optString("mode", "ALL"),
        enabledSources = row.optJSONArray("enabled_sources")?.stringSet()
            ?.takeIf(Set<String>::isNotEmpty) ?: HamClockBandHealthPreference().enabledSources,
        visibleBands = row.optJSONArray("visible_bands").stringSet(),
    )

    private fun encodePortable(value: HamClockPortablePreference) = JSONObject()
        .put("programs", value.enabledPrograms.strings()).put("window_minutes", value.windowMinutes)
        .put("maximum_spots", value.maximumSpots).put("favourites_only", value.favouritesOnly)
        .put("show_paths", value.showPaths)

    private fun decodePortable(row: JSONObject) = HamClockPortablePreference(
        enabledPrograms = row.optJSONArray("programs").stringSet(), windowMinutes = row.optInt("window_minutes", 30),
        maximumSpots = row.optInt("maximum_spots", 100), favouritesOnly = row.optBoolean("favourites_only"),
        showPaths = row.optBoolean("show_paths"),
    )

    private fun encodeSatellites(value: HamClockSatellitePreference) = JSONObject()
        .put("tracked_norad_ids", JSONArray(value.trackedNoradIds.sorted()))
        .put("pass_window_hours", value.passWindowHours).put("minimum_elevation_degrees", value.minimumElevationDegrees)
        .put("show_tracks", value.showTracks).put("show_footprints", value.showFootprints).put("show_doppler", value.showDoppler)

    private fun decodeSatellites(row: JSONObject) = HamClockSatellitePreference(
        trackedNoradIds = row.optJSONArray("tracked_norad_ids").intSet(),
        passWindowHours = row.optInt("pass_window_hours", 24),
        minimumElevationDegrees = row.optInt("minimum_elevation_degrees", 10),
        showTracks = row.optBoolean("show_tracks", true), showFootprints = row.optBoolean("show_footprints", true),
        showDoppler = row.optBoolean("show_doppler"),
    )

    private fun encodeTarget(value: HamClockDxTarget) = JSONObject()
        .put("callsign", value.callsign).put("grid", value.grid).put("locked", value.locked)
        .put("source", value.source.name)
        .also { row -> value.latitude?.let { row.put("latitude", it) }; value.longitude?.let { row.put("longitude", it) } }

    private fun decodeTarget(row: JSONObject) = HamClockDxTarget(
        callsign = row.optString("callsign"), grid = row.optString("grid"),
        latitude = row.optionalDouble("latitude"), longitude = row.optionalDouble("longitude"),
        locked = row.optBoolean("locked"), source = row.enum("source", HamClockDxTargetSource.MANUAL),
    )

    private fun encodeDisplay(value: HamClockDisplayPreference) = JSONObject()
        .put("density", value.density.name).put("time_zone_mode", value.timeZoneMode.name)
        .put("hour_format", value.hourFormat.name).put("unit_system", value.unitSystem.name)
        .put("low_data_mode", value.lowDataMode).put("immersive", value.immersive)

    private fun decodeDisplay(row: JSONObject) = HamClockDisplayPreference(
        density = row.enum("density", HamClockDensity.COMPACT),
        timeZoneMode = row.enum("time_zone_mode", HamClockTimeZoneMode.BOTH),
        hourFormat = row.enum("hour_format", HamClockHourFormat.H24),
        unitSystem = row.enum("unit_system", HamClockUnitSystem.METRIC),
        lowDataMode = row.optBoolean("low_data_mode"),
        immersive = row.optBoolean("immersive"),
    )

    private fun encodePropagation(value: HamClockPropagationPreference) = JSONObject()
        .put("tx_power_watts", value.txPowerWatts).put("tx_gain_db", value.txGainDb)
        .put("rx_gain_db", value.rxGainDb).put("noise_environment", value.noiseEnvironment.name)
        .put("required_reliability", value.requiredReliability).put("required_snr_db", value.requiredSnrDb)
        .put("bandwidth_hz", value.bandwidthHz).put("digital", value.digital).put("long_path", value.longPath)
        .put("selected_frequencies_mhz", JSONArray(value.selectedFrequenciesMHz)).put("coverage_resolution", value.coverageResolution)

    private fun decodePropagation(row: JSONObject) = HamClockPropagationPreference(
        txPowerWatts = row.optInt("tx_power_watts", 100), txGainDb = row.optDouble("tx_gain_db", 0.0),
        rxGainDb = row.optDouble("rx_gain_db", 0.0),
        noiseEnvironment = row.enum("noise_environment", HamClockNoiseEnvironment.RESIDENTIAL),
        requiredReliability = row.optInt("required_reliability", 90), requiredSnrDb = row.optDouble("required_snr_db", 10.0),
        bandwidthHz = row.optInt("bandwidth_hz", 2400), digital = row.optBoolean("digital"),
        longPath = row.optBoolean("long_path"),
        selectedFrequenciesMHz = row.optJSONArray("selected_frequencies_mhz")?.doubles()
            ?: HamClockPropagationPreference().selectedFrequenciesMHz,
        coverageResolution = row.optInt("coverage_resolution", 288),
    )

    private fun encodeIdReminder(value: HamClockIdReminderPreference) = JSONObject()
        .put("enabled", value.enabled).put("interval_minutes", value.intervalMinutes)
        .put("start_on_verified_tx", value.startOnVerifiedTx).put("notification_enabled", value.notificationEnabled)
        .put("running", value.running).put("paused", value.paused).put("last_reset_epoch_seconds", value.lastResetEpochSeconds)

    private fun decodeIdReminder(row: JSONObject) = HamClockIdReminderPreference(
        enabled = row.optBoolean("enabled"), intervalMinutes = row.optInt("interval_minutes", 10),
        startOnVerifiedTx = row.optBoolean("start_on_verified_tx"), notificationEnabled = row.optBoolean("notification_enabled"),
        running = row.optBoolean("running"), paused = row.optBoolean("paused"),
        lastResetEpochSeconds = row.optLong("last_reset_epoch_seconds"),
    )

    private fun encodeShackDisplay(value: HamClockShackDisplayPreference) = JSONObject()
        .put("theme", value.theme.name).put("keep_screen_on", value.keepScreenOn)
        .put("rotation_enabled", value.rotationEnabled).put("rotation_seconds", value.rotationSeconds)
        .put("reduced_motion", value.reducedMotion)
        .also { row -> value.selectedProfileId?.let { row.put("selected_profile_id", it) } }

    private fun decodeShackDisplay(row: JSONObject) = HamClockShackDisplayPreference(
        theme = row.enum("theme", HamClockShackTheme.STANDARD_DARK), keepScreenOn = row.optBoolean("keep_screen_on"),
        rotationEnabled = row.optBoolean("rotation_enabled"), rotationSeconds = row.optInt("rotation_seconds", 30),
        selectedProfileId = row.optionalString("selected_profile_id"), reducedMotion = row.optBoolean("reduced_motion"),
    )

    private fun encodeProfile(value: HamClockNamedProfile) = JSONObject()
        .put("id", value.id).put("name", value.name).put("created_at", value.createdAtMillis)
        .put("updated_at", value.updatedAtMillis).put("settings", encodeSettings(value.settings))

    private fun decodeProfile(row: JSONObject) = HamClockNamedProfile(
        id = row.optString("id"), name = row.optString("name"),
        settings = row.optJSONObject("settings")?.let(::decodeSettings) ?: HamClockUserSettings(),
        createdAtMillis = row.optLong("created_at"), updatedAtMillis = row.optLong("updated_at"),
    )

    private fun normalizeProfile(value: HamClockNamedProfile): HamClockNamedProfile {
        val id = cleanId(value.id).ifBlank { throw IllegalArgumentException("Profile id is required") }
        val name = value.name.trim().take(64).ifBlank { throw IllegalArgumentException("Profile name is required") }
        return value.copy(id = id, name = name, settings = normalizeSettings(value.settings),
            createdAtMillis = value.createdAtMillis.coerceAtLeast(0),
            updatedAtMillis = value.updatedAtMillis.coerceAtLeast(value.createdAtMillis.coerceAtLeast(0)))
    }

    private fun normalizePanel(value: HamClockPanelPreference): HamClockPanelPreference? {
        val id = cleanId(value.id).takeIf(String::isNotBlank) ?: return null
        val spec = hamClockModuleSpec(id)
        if (spec == null) return value.copy(id = id, order = value.order.coerceIn(0, 999),
            column = value.column.coerceIn(0, 2), columnSpan = value.columnSpan.coerceIn(1, 3),
            rowSpan = value.rowSpan.coerceIn(1, 4), collapsed = false)
        val column = value.column.takeIf { it in spec.allowedColumns } ?: spec.defaultColumn
        val maximumSpan = if (column == 1) spec.defaultColumnSpan.coerceAtLeast(1) else 1
        return value.copy(id = id, order = value.order.coerceIn(0, 999), column = column,
            columnSpan = value.columnSpan.coerceIn(1, maximumSpan), rowSpan = value.rowSpan.coerceIn(1, 4),
            collapsed = value.collapsed && spec.collapseSupported)
    }

    private fun normalizeLayer(value: HamClockMapLayerPreference): HamClockMapLayerPreference? {
        val id = cleanId(value.id).takeIf(String::isNotBlank) ?: return null
        return value.copy(id = id, opacity = value.opacity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f)
    }

    private fun normalizeFilter(value: HamClockSpotFilter) = value.copy(
        bands = cleanTokens(value.bands, 32), modes = cleanTokens(value.modes, 64),
        continents = cleanTokens(value.continents, 16), callQuery = value.callQuery.trim().uppercase(Locale.US).take(32),
        minimumSnr = value.minimumSnr?.coerceIn(-100, 100),
    )

    private fun normalizeTarget(value: HamClockDxTarget): HamClockDxTarget {
        val lat = value.latitude?.takeIf(Double::isFinite)?.coerceIn(-90.0, 90.0)
        val lon = value.longitude?.takeIf(Double::isFinite)?.let(::wrapLongitude)
        return value.copy(callsign = value.callsign.trim().uppercase(Locale.US).take(24),
            grid = value.grid.trim().uppercase(Locale.US).take(12), latitude = lat, longitude = lon)
    }

    private fun cleanTokens(values: Set<String>, maximum: Int) = values.asSequence()
        .map { it.trim().uppercase(Locale.US).take(24) }.filter(String::isNotBlank).distinct().take(maximum).toSet()

    private fun cleanId(value: String) = value.trim().lowercase(Locale.US).take(64).filter { it.isLetterOrDigit() || it in "_.-" }
    private fun wrapLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    private fun Double.finiteOr(fallback: Double) = takeIf(Double::isFinite) ?: fallback

    private fun JSONArray?.stringSet(): Set<String> = if (this == null) emptySet() else buildSet {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
    private fun JSONArray?.intSet(): Set<Int> = if (this == null) emptySet() else buildSet {
        for (index in 0 until length()) optInt(index).takeIf { it > 0 }?.let(::add)
    }
    private fun Set<String>.strings() = JSONArray(toList().sorted())
    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(transform(it)) }
    }
    private fun JSONArray.doubles(): List<Double> = buildList {
        for (index in 0 until length()) optDouble(index).takeIf(Double::isFinite)?.let(::add)
    }
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == optString(key).uppercase(Locale.US) } ?: fallback
    private fun JSONObject.optionalString(key: String) = if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null
    private fun JSONObject.optionalDouble(key: String) = if (has(key) && !isNull(key)) optDouble(key).takeIf(Double::isFinite) else null
}
