// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmxToneTxSafetyTest {
    @Test fun ft8PlanUsesSeventyNineAbsoluteOneSixtyMillisecondDeadlines() {
        val clock = FakeClock(); val port = FakeTonePort(); val safety = FakeSafety(port)
        val plan = plan(QmxFtProtocol.FT8); port.authorizedDigest = plan.digest()
        val outcome = QmxToneTxBackend(port, safety, clock).execute(plan, supported())
        assertEquals(QmxToneTxResult.COMPLETED, outcome.result)
        assertEquals(79, outcome.symbolsSent)
        assertEquals(79, port.commands.count { it.startsWith("TA") && it != "TA0;" })
        assertEquals(0, outcome.maximumDeadlineSlipNanos)
        assertEquals(79L * 160_000_000L + 5_000_000L, clock.monotonicNanos())
    }

    @Test fun ft4PlanUsesOneHundredFiveAbsoluteFortyEightMillisecondDeadlines() {
        val clock = FakeClock(); val port = FakeTonePort(); val safety = FakeSafety(port)
        val plan = plan(QmxFtProtocol.FT4); port.authorizedDigest = plan.digest()
        val outcome = QmxToneTxBackend(port, safety, clock).execute(plan, supported())
        assertEquals(QmxToneTxResult.COMPLETED, outcome.result)
        assertEquals(105, outcome.symbolsSent)
        assertEquals(105L * 48_000_000L + 5_000_000L, clock.monotonicNanos())
    }

    @Test fun contextChangeAbortsAndStillCleansUp() {
        val clock = FakeClock(); val port = FakeTonePort(); val safety = FakeSafety(port, contextChangesAfter = 4)
        val plan = plan(QmxFtProtocol.FT8); port.authorizedDigest = plan.digest()
        val outcome = QmxToneTxBackend(port, safety, clock).execute(plan, supported())
        assertEquals(QmxToneTxResult.CONTEXT_CHANGED, outcome.result)
        assertTrue(outcome.symbolsSent in 4..5)
        assertEquals(listOf("TA0;", "RX;"), port.commands.takeLast(2))
    }

    @Test fun swrTripAbortsAndLatchesUntilOperatorClear() {
        val clock = FakeClock(); val port = FakeTonePort(swr = 4.2); val safety = FakeSafety(port)
        val plan = plan(QmxFtProtocol.FT8); port.authorizedDigest = plan.digest()
        val backend = QmxToneTxBackend(port, safety, clock, swrTripRatio = 3.0)
        val outcome = backend.execute(plan, supported())
        assertEquals(QmxToneTxResult.SWR_TRIPPED, outcome.result)
        assertTrue(backend.swrFault)
        assertFalse(backend.clearSwrFault(false))
        assertTrue(backend.clearSwrFault(true))
    }

    @Test fun receiveUnconfirmedFailsClosedAndRetryBelongsToSafetyPort() {
        val clock = FakeClock(); val port = FakeTonePort(confirmReceive = false); val safety = FakeSafety(port, cleanupRetry = true)
        val plan = plan(QmxFtProtocol.FT8); port.authorizedDigest = plan.digest()
        val outcome = QmxToneTxBackend(port, safety, clock).execute(plan, supported())
        assertEquals(QmxToneTxResult.RX_UNCONFIRMED, outcome.result)
        assertEquals(2, port.commands.count { it == "RX;" })
        assertTrue(safety.cleanupRequests.contains(QmxCleanupOperation.RECEIVE))
    }

    @Test fun capabilityAndDigiAuthorizationAreBothMandatory() {
        val clock = FakeClock(); val port = FakeTonePort(); val safety = FakeSafety(port)
        val plan = plan(QmxFtProtocol.FT8)
        port.authorizedDigest = plan.digest()
        assertEquals(QmxToneTxResult.CAPABILITY_UNAVAILABLE, QmxToneTxBackend(port, safety, clock).execute(plan, QmxCapabilities()).result)
        port.authorizedDigest = "wrong"
        assertEquals(QmxToneTxResult.AUTHORIZATION_DENIED, QmxToneTxBackend(port, safety, clock).execute(plan, supported()).result)
        assertFalse(port.commands.any { it == "TX;" })
    }

    @Test fun packageContainsNoProviderLogQsoOrCentralAuthorityClient() {
        val root = File("src/main/java/app/shackcq/mobile/radio/qmx")
        assertTrue(root.isDirectory)
        val source = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("AppController", "RadioBackend", "NativeCore", "DigiController", "Wavelog", "QsoMutation", "http://", "https://").forEach {
            assertFalse("forbidden reference $it", source.contains(it))
        }
        assertFalse(source.contains("callsign", ignoreCase = true))
    }

    private fun supported() = QmxCapabilities(directToneTx = QmxCapabilityState.SUPPORTED)

    private fun plan(protocol: QmxFtProtocol) = QmxTonePlan(
        protocol = protocol,
        slotStartEpochMillis = 30_000L,
        baseToneHz = 1_000.0,
        symbols = List(protocol.symbolCount) { it % (protocol.maximumTone + 1) },
        expectedContextGeneration = 7,
        expectedDeviceDigest = "a".repeat(64),
    )

    private class FakeClock : QmxClock {
        private var now = 0L
        override fun monotonicNanos() = now
        override fun wallTimeMillis() = 30_000L
        override fun sleepUntilMonotonic(targetNanos: Long) { now = maxOf(now, targetNanos) }
    }

    private class FakeTonePort(private val swr: Double? = 1.2, private val confirmReceive: Boolean = true) : QmxDigiToneTxPort {
        val commands = mutableListOf<String>()
        var authorizedDigest = ""
        val toneCount: Int get() = commands.count { it.startsWith("TA") && it != "TA0;" }
        override fun authorization(plan: QmxTonePlan) = QmxDigiAuthorization(true, true, true, authorizedDigest)
        override fun execute(command: QmxCommand): QmxCommandReceipt {
            commands += command.text
            return when (command.text) {
                "TX;" -> QmxCommandReceipt(true, QmxTxState.TX)
                "RX;" -> QmxCommandReceipt(true, if (confirmReceive) QmxTxState.RX else QmxTxState.UNKNOWN)
                else -> QmxCommandReceipt(true)
            }
        }
        override fun samplePowerSWR() = QmxPowerSwrSample(5.0, swr)
    }

    private class FakeSafety(
        private val port: FakeTonePort,
        private val contextChangesAfter: Int = Int.MAX_VALUE,
        private val cleanupRetry: Boolean = false,
    ) : QmxSafetyPort {
        val cleanupRequests = mutableListOf<QmxCleanupOperation>()
        override fun currentContextGeneration() = if (port.toneCount >= contextChangesAfter) 8L else 7L
        override fun currentDeviceDigest() = "a".repeat(64)
        override fun abortRequested() = false
        override fun allowCleanupRetry(operation: QmxCleanupOperation): Boolean { cleanupRequests += operation; return cleanupRetry }
    }
}
