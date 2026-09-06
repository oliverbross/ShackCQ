// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmxConnectionTest {
    @Test fun compositeProfileSelectsExactInterfacesAndModel() {
        val profile = QmxUsbCompositeProfile.resolve(identity("QMX+", extra = true))!!
        assertEquals(QmxModel.QMX_PLUS, profile.model)
        assertEquals(0, profile.primaryCatControlInterface)
        assertEquals(setOf(5), profile.extraCdcControlInterfaces)
        assertEquals(setOf(3), profile.uacStreamingInterfaces)
    }

    @Test fun startupHandshakeRequiresIqVoxAndExactUacRoute() {
        val serial = FakeSerial()
        val clock = FakeClock()
        val profile = QmxUsbCompositeProfile.resolve(identity("QMX", extra = true))!!
        val controller = controller(serial, clock, profile.stableDeviceDigest)
        assertTrue(controller.attach(profile))
        assertTrue(controller.snapshot.ready)
        assertEquals(QmxTriState.TRUE, controller.snapshot.iqModeEnabled)
        assertEquals(QmxTriState.TRUE, controller.snapshot.voxDisabled)
        assertTrue(serial.commands.indexOf("Q9 1;") < serial.commands.indexOf("Q9;"))
    }

    @Test fun routeLossClearsReadinessAndObservedRadioState() {
        val serial = FakeSerial(); val profile = QmxUsbCompositeProfile.resolve(identity("QMX"))!!
        val controller = controller(serial, FakeClock(), profile.stableDeviceDigest)
        controller.attach(profile)
        controller.routeLost()
        assertFalse(controller.snapshot.connected)
        assertFalse(controller.snapshot.ready)
        assertEquals(null, controller.snapshot.vfoAHz)
        assertEquals(QmxTriState.UNKNOWN, controller.snapshot.iqModeEnabled)
    }

    @Test fun safeContinuousControlsCoalesceLastWriteWins() {
        val serial = FakeSerial(); val profile = QmxUsbCompositeProfile.resolve(identity("QMX"))!!
        val controller = controller(serial, FakeClock(), profile.stableDeviceDigest)
        controller.attach(profile); serial.commands.clear()
        assertTrue(controller.submit(QmxRadioAction.SetFrequency(14_073_000)))
        assertTrue(controller.submit(QmxRadioAction.SetFrequency(14_074_000)))
        controller.drain()
        assertFalse(serial.commands.contains("FA00014073000;"))
        assertTrue(serial.commands.contains("FA00014074000;"))
    }

    @Test fun transactionOwnerPreventsCommandInterleaving() {
        val serial = FakeSerial(delayMillis = 2); val profile = QmxUsbCompositeProfile.resolve(identity("QMX"))!!
        val controller = controller(serial, FakeClock(), profile.stableDeviceDigest)
        controller.attach(profile); serial.commands.clear(); serial.maxActive.set(0)
        repeat(10) { controller.submit(QmxRadioAction.SetRfGain(it)) }
        val start = CountDownLatch(1)
        val threads = List(2) { Thread { start.await(); controller.drain(20) }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }
        assertEquals(1, serial.maxActive.get())
    }

    @Test fun reconnectRejectsDifferentDeviceAndCloseIsIdempotent() {
        val serial = FakeSerial(); val first = QmxUsbCompositeProfile.resolve(identity("QMX"))!!
        val controller = controller(serial, FakeClock(), first.stableDeviceDigest)
        assertTrue(controller.attach(first))
        val different = QmxUsbCompositeProfile.resolve(identity("QMX+", digest = "b".repeat(64)))!!
        assertFalse(controller.attach(different))
        controller.close(); controller.close()
        assertEquals(1, serial.closeCount)
    }

    private fun controller(serial: FakeSerial, clock: FakeClock, digest: String) = QmxConnectionController(
        serial,
        QmxUsbIdentityPort { identity("QMX", digest = digest) },
        QmxUacAudioPort { QmxAudioRouteEvidence(digest, true, 48_000, 2, 24, QmxIqChannelOrder.I_LEFT_Q_RIGHT, "QMX UAC") },
        clock,
    )

    private fun identity(product: String, extra: Boolean = false, digest: String = "a".repeat(64)) = QmxUsbIdentityEvidence(
        0x0483, 0x5740, product, digest,
        buildList {
            add(QmxUsbFunctionDescriptor(0, QmxUsbFunctionKind.CDC_CONTROL))
            add(QmxUsbFunctionDescriptor(1, QmxUsbFunctionKind.CDC_DATA))
            add(QmxUsbFunctionDescriptor(2, QmxUsbFunctionKind.UAC_CONTROL))
            add(QmxUsbFunctionDescriptor(3, QmxUsbFunctionKind.UAC_STREAMING))
            if (extra) add(QmxUsbFunctionDescriptor(5, QmxUsbFunctionKind.CDC_CONTROL))
        },
    )

    private class FakeClock : QmxClock {
        private var now = 1_000_000_000L
        override fun monotonicNanos() = now
        override fun wallTimeMillis() = 30_000L
        override fun sleepUntilMonotonic(targetNanos: Long) { now = maxOf(now, targetNanos) }
    }

    private class FakeSerial(private val delayMillis: Long = 0) : QmxSerialPort {
        val commands = mutableListOf<String>()
        private val active = AtomicInteger()
        val maxActive = AtomicInteger()
        var closeCount = 0
        override fun exchange(command: String, timeoutMillis: Long): String {
            val value = active.incrementAndGet(); maxActive.updateAndGet { maxOf(it, value) }
            try {
                synchronized(commands) { commands += command }
                if (delayMillis > 0) Thread.sleep(delayMillis)
                return when (command) {
                    "VN;" -> "VN1_04_002QMX;"; "ID;" -> "ID020;"; "Q9;" -> "Q91;"; "Q3;" -> "Q30;"
                    "FA;" -> "FA00014074000;"; "FB;" -> "FB00014076000;"; "MD;" -> "MD6;"; "FW;" -> "FW3000;"
                    "IF;" -> "IFSTATE;"; "AG;" -> "AG0091;"; "RG;" -> "RG063;"; "RT;" -> "RT0;"; "SP;" -> "SP0;"
                    "FR;" -> "FR0;"; "FT;" -> "FT0;"; "SM;" -> "SM009;"; "PC;" -> "PC45;"; "SW;" -> "SW120;"; "TQ;" -> "TQ0;"
                    else -> command.replace(" ", "")
                }
            } finally { active.decrementAndGet() }
        }
        override fun close() { closeCount++ }
    }
}
