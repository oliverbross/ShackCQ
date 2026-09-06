package app.shackcq.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOwnershipRulesTest {
    private val nonMonitorOwners = listOf(
        AudioOwners.PANADAPTER,
        AudioOwners.EQ,
        AudioOwners.VOICE,
        AudioOwners.VOICE_TX,
        AudioOwners.TCI_RX_AUDIO,
    )

    @Test fun idleRouteAcceptsOneExclusiveOwner() {
        nonMonitorOwners.forEach { requested ->
            val decision = decideAudioLease(AudioOwners.NONE, requested, monitorRunning = false, pauseMonitor = true)
            assertTrue(requested, decision.accepted)
            assertFalse(requested, decision.pauseMonitor)
        }
        assertTrue(canStartAudioMonitor(AudioOwners.NONE))
    }

    @Test fun monitorCanOnlyBePausedByExplicitLeasePolicy() {
        nonMonitorOwners.forEach { requested ->
            val accepted = decideAudioLease(AudioOwners.MONITOR, requested, monitorRunning = true, pauseMonitor = true)
            assertTrue(requested, accepted.accepted)
            assertTrue(requested, accepted.pauseMonitor)
            assertFalse(requested, decideAudioLease(AudioOwners.MONITOR, requested, true, false).accepted)
        }
    }

    @Test fun nonMonitorOwnersCannotBePreemptedOrNested() {
        nonMonitorOwners.forEach { current ->
            nonMonitorOwners.forEach { requested ->
                assertFalse("$current -> $requested", decideAudioLease(current, requested, false, true).accepted)
            }
            assertFalse(current, canStartAudioMonitor(current))
        }
    }
}
