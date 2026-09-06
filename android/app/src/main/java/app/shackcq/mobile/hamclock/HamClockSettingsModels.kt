package app.shackcq.mobile.hamclock

/** Stable panel identifiers. New panels can use their own stable string without a schema migration. */
object HamClockPanelId {
    const val STATION = "station"
    const val WEATHER = "weather"
    const val BAND_ACTIVITY = "band_activity"
    const val PSK_REPORTER = "psk_reporter"
    const val DX_NEWS = "dx_news"
    const val DX_EXPEDITIONS = "dxpeditions"
    const val DX_CLUSTER = "dx_cluster"
    const val SOLAR = "solar"
    const val DX_TARGET = "dx_target"
    const val VOACAP = "voacap"
    const val PORTABLE = "portable"
    const val SATELLITES = "satellites"
    const val CONTESTS = "contests"
    const val MAP = "map"
    const val ANALOG_CLOCK = "analog_clock"
    const val RBN = "rbn"
    const val WSPR = "wspr"
    const val IBP = "ibp"
    const val BAND_HEALTH = "band_health"
    const val NEURAL_OUTLOOK = "neural_outlook"
}

object HamClockMapLayerId {
    const val DE_STATION = "de_station"
    const val DX_SPOTS = "dx_spots"
    const val DX_PATHS = "dx_paths"
    const val SELECTED_TARGET = "selected_target"
    const val PSK_REPORTER = "psk_reporter"
    const val PORTABLE = "portable"
    const val SATELLITES = "satellites"
    const val GRAYLINE = "grayline"
    const val SUN = "sun"
    const val MOON = "moon"
    const val GRID = "grid"
    const val AURORA = "aurora"
    const val LOGGED_QSOS = "logged_qsos"
    const val CONTEST_QSOS = "contest_qsos"
    const val LIGHTNING = "lightning"
    const val RBN = "rbn"
    const val WSPR_EXPANDED = "wspr_expanded"
    const val IBP = "ibp"
    const val MUF = "muf"
    const val PROPAGATION_HEATMAP = "propagation_heatmap"
    const val WEATHER_RADAR = "weather_radar"
    const val WWBOTA = "wwbota"
    const val NEURAL_OUTLOOK = "neural_outlook"
}

enum class HamClockBasemap { DARK, LIGHT, SATELLITE, TERRAIN }
enum class HamClockDensity { COMPACT, COMFORTABLE, LARGE_TOUCH }
enum class HamClockTimeZoneMode { UTC, LOCAL, BOTH }
enum class HamClockHourFormat { H12, H24 }
enum class HamClockUnitSystem { METRIC, IMPERIAL }
enum class HamClockPskDirection { BEING_HEARD, HEARING, BOTH, MUTUAL }
enum class HamClockDxNewsSource { ALL, DX_WORLD, NG3K }
enum class HamClockRbnSource { CONFIGURED_RETAIL_CLUSTER }
enum class HamClockRbnMode { WHO_HEARS_ME, SKIMMER_VIEW, WATCHLIST, ALL_RBN }
enum class HamClockDxTargetSource { MANUAL, AUTOMATIC }
enum class HamClockNoiseEnvironment { QUIET_RURAL, RURAL, RESIDENTIAL, CITY, INDUSTRIAL }
enum class HamClockShackTheme { STANDARD_DARK, AMBER_SHACK, RED_NIGHT }
enum class HamClockOutlookWindow(val minutes: Int) { MINUTES_30(30), MINUTES_60(60), MINUTES_120(120) }

data class HamClockPanelPreference(
    val id: String,
    val visible: Boolean = true,
    val order: Int = 0,
    val column: Int = 0,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
    val collapsed: Boolean = false,
)

data class HamClockMapLayerPreference(
    val id: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
)

data class HamClockMapPreference(
    val basemap: HamClockBasemap = HamClockBasemap.DARK,
    val followStation: Boolean = true,
    val centerLatitude: Double = 0.0,
    val centerLongitude: Double = 0.0,
    val zoom: Double = 1.2,
    val layers: List<HamClockMapLayerPreference> = defaultHamClockMapLayers(),
)

/** Empty filter sets mean all values, avoiding duplicated provider-specific configuration. */
data class HamClockSpotFilter(
    val bands: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val continents: Set<String> = emptySet(),
    val callQuery: String = "",
    val minimumSnr: Int? = null,
)

data class HamClockClusterPreference(
    val enabled: Boolean = true,
    val windowMinutes: Int = 30,
    val refreshSeconds: Int = 30,
    val maximumSpots: Int = 100,
    val filter: HamClockSpotFilter = HamClockSpotFilter(),
)

data class HamClockPskPreference(
    val enabled: Boolean = true,
    val direction: HamClockPskDirection = HamClockPskDirection.BOTH,
    val windowMinutes: Int = 15,
    val refreshSeconds: Int = 300,
    val maximumReports: Int = 250,
    val filter: HamClockSpotFilter = HamClockSpotFilter(),
)

data class HamClockDxNewsPreference(
    val source: HamClockDxNewsSource = HamClockDxNewsSource.ALL,
    val compactVisible: Boolean = false,
)

data class HamClockRbnPreference(
    val enabled: Boolean = true,
    val source: HamClockRbnSource = HamClockRbnSource.CONFIGURED_RETAIL_CLUSTER,
    val viewMode: HamClockRbnMode = HamClockRbnMode.ALL_RBN,
    val windowMinutes: Int = 10,
    val maximumRows: Int = 120,
    val bands: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val minimumSnr: Int? = null,
    val skimmerCall: String = "",
    val dxCall: String = "",
    val watchlistOnly: Boolean = false,
    val showPaths: Boolean = true,
)

data class HamClockWsprPreference(
    val personalEnabled: Boolean = true,
    val direction: HamClockPskDirection = HamClockPskDirection.BOTH,
    val windowMinutes: Int = 30,
    val band: String = "ALL",
    val minimumSnr: Int? = null,
    val maximumPaths: Int = 100,
    val regionalEnabled: Boolean = false,
    val showPaths: Boolean = true,
    val showRegionalGrid: Boolean = false,
)

data class HamClockIbpPreference(
    val showAllSites: Boolean = true,
    val showPaths: Boolean = true,
)

data class HamClockBandHealthPreference(
    val windowMinutes: Int = 15,
    val mode: String = "ALL",
    val enabledSources: Set<String> = setOf("CLUSTER", "PSK", "RBN", "WSPR"),
    val visibleBands: Set<String> = setOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"),
)

data class HamClockOutlookPreference(
    val enabled: Boolean = true,
    val defaultWindow: HamClockOutlookWindow = HamClockOutlookWindow.MINUTES_60,
    val visibleBands: Set<String> = setOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm", "3cm"),
    val worldLayerEnabled: Boolean = false,
    val worldOpacity: Float = .58f,
    val retentionDays: Int = 180,
    val showOnCompact: Boolean = false,
)

data class HamClockPortablePreference(
    val enabledPrograms: Set<String> = setOf("POTA", "WWFF", "SOTA", "WWBOTA"),
    val windowMinutes: Int = 30,
    val maximumSpots: Int = 100,
    val favouritesOnly: Boolean = false,
    val showPaths: Boolean = false,
)

data class HamClockSatellitePreference(
    val trackedNoradIds: Set<Int> = emptySet(),
    val passWindowHours: Int = 24,
    val minimumElevationDegrees: Int = 10,
    val showTracks: Boolean = true,
    val showFootprints: Boolean = true,
    val showDoppler: Boolean = false,
)

data class HamClockDxTarget(
    val callsign: String = "",
    val grid: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locked: Boolean = false,
    val source: HamClockDxTargetSource = HamClockDxTargetSource.MANUAL,
)

data class HamClockDisplayPreference(
    val density: HamClockDensity = HamClockDensity.COMPACT,
    val timeZoneMode: HamClockTimeZoneMode = HamClockTimeZoneMode.BOTH,
    val hourFormat: HamClockHourFormat = HamClockHourFormat.H24,
    val unitSystem: HamClockUnitSystem = HamClockUnitSystem.METRIC,
    val lowDataMode: Boolean = false,
    val immersive: Boolean = false,
)

data class HamClockPropagationPreference(
    val txPowerWatts: Int = 100,
    val txGainDb: Double = 0.0,
    val rxGainDb: Double = 0.0,
    val noiseEnvironment: HamClockNoiseEnvironment = HamClockNoiseEnvironment.RESIDENTIAL,
    val requiredReliability: Int = 90,
    val requiredSnrDb: Double = 10.0,
    val bandwidthHz: Int = 2400,
    val digital: Boolean = false,
    val longPath: Boolean = false,
    val selectedFrequenciesMHz: List<Double> = listOf(1.84, 3.6, 5.35, 7.1, 10.12, 14.1, 18.1, 21.1, 24.93, 28.1),
    val coverageResolution: Int = 288,
)

data class HamClockIdReminderPreference(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 10,
    val startOnVerifiedTx: Boolean = false,
    val notificationEnabled: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val lastResetEpochSeconds: Long = 0,
)

data class HamClockShackDisplayPreference(
    val theme: HamClockShackTheme = HamClockShackTheme.STANDARD_DARK,
    val keepScreenOn: Boolean = false,
    val rotationEnabled: Boolean = false,
    val rotationSeconds: Int = 30,
    val selectedProfileId: String? = null,
    val reducedMotion: Boolean = false,
)

data class HamClockUserSettings(
    val panels: List<HamClockPanelPreference> = defaultHamClockPanels(),
    val map: HamClockMapPreference = HamClockMapPreference(),
    val cluster: HamClockClusterPreference = HamClockClusterPreference(),
    val pskReporter: HamClockPskPreference = HamClockPskPreference(),
    val dxNews: HamClockDxNewsPreference = HamClockDxNewsPreference(),
    val rbn: HamClockRbnPreference = HamClockRbnPreference(),
    val wspr: HamClockWsprPreference = HamClockWsprPreference(),
    val ibp: HamClockIbpPreference = HamClockIbpPreference(),
    val bandHealth: HamClockBandHealthPreference = HamClockBandHealthPreference(),
    val outlook: HamClockOutlookPreference = HamClockOutlookPreference(),
    val portable: HamClockPortablePreference = HamClockPortablePreference(),
    val satellites: HamClockSatellitePreference = HamClockSatellitePreference(),
    val dxTarget: HamClockDxTarget? = null,
    val display: HamClockDisplayPreference = HamClockDisplayPreference(),
    val propagation: HamClockPropagationPreference = HamClockPropagationPreference(),
    val idReminder: HamClockIdReminderPreference = HamClockIdReminderPreference(),
    val shackDisplay: HamClockShackDisplayPreference = HamClockShackDisplayPreference(),
)

data class HamClockNamedProfile(
    val id: String,
    val name: String,
    val settings: HamClockUserSettings,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class HamClockSettingsDocument(
    val version: Int = HamClockSettingsCodec.CURRENT_VERSION,
    val settings: HamClockUserSettings = HamClockUserSettings(),
    val activeProfileId: String? = null,
    val profiles: List<HamClockNamedProfile> = emptyList(),
)

data class HamClockImportResult(
    val version: Int,
    val profileCount: Int,
    val activeProfileId: String?,
)

fun defaultHamClockPanels(): List<HamClockPanelPreference> = defaultPanelsFromRegistry()

fun defaultHamClockMapLayers(): List<HamClockMapLayerPreference> = defaultLayersFromRegistry()
