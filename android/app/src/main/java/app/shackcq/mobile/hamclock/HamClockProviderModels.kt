package app.shackcq.mobile.hamclock

/** Provenance and freshness are kept with every public-data payload. */
internal enum class HamClockFeedState { LIVE, CACHED, DEGRADED, STALE, UNAVAILABLE }

internal data class HamClockFeed<T>(
    val value: T,
    val state: HamClockFeedState,
    val source: String,
    val fetchedAtEpoch: Long = 0,
    val error: String = "",
)

internal enum class HamClockContestStatus { ACTIVE, UPCOMING }

internal data class HamClockContest(
    val id: String,
    val name: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val mode: String,
    val status: HamClockContestStatus,
    val url: String,
)

internal enum class HamClockDxpeditionStatus { ACTIVE, UPCOMING, RECENTLY_ENDED, UNDATED }

internal data class HamClockDxpedition(
    val callsign: String,
    val entity: String,
    val startEpoch: Long?,
    val endEpoch: Long?,
    val status: HamClockDxpeditionStatus,
    val dateText: String,
    val bands: Set<String>,
    val modes: Set<String>,
    val qsl: String,
    val information: String,
    val sourceUrl: String,
)

internal data class HamClockXrayPoint(
    val epoch: Long,
    val fluxWattsPerSquareMetre: Double,
    val satellite: Int?,
)

internal data class HamClockXraySeries(
    val points: List<HamClockXrayPoint> = emptyList(),
    val currentClass: String = "—",
    val peakClass: String = "—",
)

internal data class HamClockSolarImage(
    val channel: String,
    val title: String,
    val wavelengthAngstrom: Int?,
    val primaryUrl: String,
    val fallbackUrls: List<String>,
    val attribution: String,
    val refreshSeconds: Long = 15 * 60,
)

internal enum class HamClockMoonPhaseName {
    NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS,
    FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT,
}

internal data class HamClockMoonSnapshot(
    /** Fraction through the synodic month: 0=new, 0.5=full. */
    val phase: Double,
    val illumination: Double,
    val ageDays: Double,
    val name: HamClockMoonPhaseName,
)

internal enum class HamClockDaylightState { NORMAL, MIDNIGHT_SUN, POLAR_NIGHT }

internal data class HamClockSunTimes(
    val dateUtc: String,
    val sunriseEpoch: Long?,
    val sunsetEpoch: Long?,
    val state: HamClockDaylightState,
)

internal data class HamClockSolarCelestialSnapshot(
    val images: List<HamClockSolarImage>,
    val xray: HamClockXraySeries,
    val moon: HamClockMoonSnapshot,
    val sunTimes: HamClockSunTimes,
)
