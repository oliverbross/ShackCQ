package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotWorkedStatusTest {
    private val spot = SpotLogIdentity("spot", "K1ABC", "291", "United States", "20m", "CW")

    @Test fun callStatusProgressesFromNewToConfirmed() {
        assertEquals("NC", classifySpotStatus(spot, WorkedDimensions(), WorkedDimensions()).callStatus)
        assertEquals("NB", classifySpotStatus(spot, WorkedDimensions(any = true, bands = setOf("40M")), WorkedDimensions()).callStatus)
        assertEquals("NM", classifySpotStatus(spot, WorkedDimensions(any = true, bands = setOf("20M"),
            bandModes = setOf("20M|SSB")), WorkedDimensions()).callStatus)
        assertEquals("W", classifySpotStatus(spot, WorkedDimensions(any = true, bands = setOf("20M"),
            bandModes = setOf("20M|CW")), WorkedDimensions()).callStatus)
        assertEquals("C", classifySpotStatus(spot, WorkedDimensions(any = true, confirmedAny = true,
            bands = setOf("20M"), confirmedBands = setOf("20M"), bandModes = setOf("20M|CW"),
            confirmedBandModes = setOf("20M|CW")), WorkedDimensions()).callStatus)
    }

    @Test fun dxccStatusUsesWorkedAndConfirmedBandDimensions() {
        assertEquals("ATNO", classifySpotStatus(spot, WorkedDimensions(), WorkedDimensions()).dxccStatus)
        assertEquals("W/NB", classifySpotStatus(spot, WorkedDimensions(),
            WorkedDimensions(any = true, bands = setOf("40M"))).dxccStatus)
        assertEquals("C/NB", classifySpotStatus(spot, WorkedDimensions(),
            WorkedDimensions(any = true, confirmedAny = true, bands = setOf("40M"), confirmedBands = setOf("40M"))).dxccStatus)
        assertEquals("W", classifySpotStatus(spot, WorkedDimensions(),
            WorkedDimensions(any = true, bands = setOf("20M"))).dxccStatus)
        assertEquals("C", classifySpotStatus(spot, WorkedDimensions(),
            WorkedDimensions(any = true, confirmedAny = true, bands = setOf("20M"), confirmedBands = setOf("20M"))).dxccStatus)
    }

    @Test fun modeAliasesMatchWavelogDimensions() {
        assertEquals("SSB", canonicalSpotMode("USB"))
        assertEquals("SSB", canonicalSpotMode("LSB"))
        assertEquals("CW", canonicalSpotMode("CW-R"))
        assertEquals("DATA", canonicalSpotMode("MFSK"))
        assertEquals("FT8", canonicalSpotMode("FT8"))
    }

    @Test fun dxFeedIdentityKeepsLogDimensionsInTheirNamedFields() {
        val identity = spotLogIdentity("live", "OM0RX", "504", "Slovak Republic", "20m", "CW")
        assertEquals("504", identity.dxcc)
        assertEquals("Slovak Republic", identity.country)
        assertEquals("20m", identity.band)
        assertEquals("CW", identity.mode)
    }

    @Test fun pskReportIdentityKeepsItsActualMode() {
        val report = SignalReport("OM0RX", "JN88TQ", null, null, 14_074_000, "20m", "FT8", -12, null, 1_000)
        assertEquals("FT8", report.toSpotLogIdentity(null).mode)
    }
}
