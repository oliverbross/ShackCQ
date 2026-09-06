package app.shackcq.mobile.hamclock

import java.io.File

/** One integration point for the OpenHamClock-equivalent public providers. */
internal class HamClockPublicProviders(
    cacheDirectory: File,
    http: HamClockHttpClient = HamClockUrlConnectionClient(),
) {
    private val coalescer = HamClockInFlightCoalescer()
    val contests = ContestCalendarProvider(cacheDirectory, http, coalescer)
    val dxpeditions = DxpeditionScheduleProvider(cacheDirectory, http, coalescer)
    val dxNews = DxNewsRepository(cacheDirectory, http, coalescer)
    val pskReporter = PskReporterRepository(cacheDirectory, http, coalescer)
    val wspr = HamClockWsprRepository(pskReporter)
    val solarCelestial = SolarCelestialProvider(cacheDirectory, http, coalescer)
}
