package app.shackcq.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPersistenceRulesTest {
    @Test fun ntpRunsOnFirstOpportunityAndThenHourly() {
        assertTrue(ntpSyncDue(nowMillis = 10_000L, lastSuccessMillis = 0L))
        assertFalse(ntpSyncDue(nowMillis = NTP_SYNC_INTERVAL_MILLIS - 1L, lastSuccessMillis = 1L))
        assertTrue(ntpSyncDue(nowMillis = NTP_SYNC_INTERVAL_MILLIS + 1L, lastSuccessMillis = 1L))
    }

    @Test fun catAdapterPresentationSeparatesNameIdentityAndRoute() {
        val adapter = SerialDeviceDescriptor(
            sessionKey = "7:1",
            stableKey = "stable",
            driverFamily = "Cp21xx",
            manufacturer = "Silicon Labs",
            product = "CP2102N USB to UART",
            vidPid = "10C4:EA60",
            serialNumber = "0123456789ABCDEFGHIJ",
            portIndex = 1,
            deviceAddress = "/dev/bus/usb/001/007",
        )

        assertEquals("CP2102N USB to UART", adapter.displayName)
        assertEquals("Silicon Labs · 10C4:EA60 · S/N …ABCDEFGHIJ", adapter.identityLine)
        assertEquals("Cp21xx · Port 2 · 007", adapter.routeLine)
    }
}
