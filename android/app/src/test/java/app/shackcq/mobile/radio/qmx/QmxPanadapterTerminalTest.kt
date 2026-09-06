// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmxPanadapterTerminalTest {
    @Test fun audioProfileRequiresExactQmxStereoRouteWithoutFallback() {
        val exact = QmxAudioRouteEvidence("a".repeat(64), true, 48_000, 2, 24, QmxIqChannelOrder.I_LEFT_Q_RIGHT, "QMX UAC")
        assertTrue(QmxAudioProfile.from(exact, "a".repeat(64))!!.orientationConfirmed)
        assertNull(QmxAudioProfile.from(exact.copy(stableDeviceDigest = "b".repeat(64)), "a".repeat(64)))
        assertNull(QmxAudioProfile.from(exact.copy(routeName = "BUILT_IN_MIC", channels = 1), "a".repeat(64)))
    }

    @Test fun iqCorrectionReducesDeterministicImageFixture() {
        val samples = FloatArray(4_096 * 2)
        repeat(4_096) { index ->
            val phase = 2.0 * PI * 37.0 * index / 4_096.0
            samples[index * 2] = (cos(phase) + 0.08).toFloat()
            samples[index * 2 + 1] = (0.68 * sin(phase) + 0.24 * cos(phase) - 0.05).toFloat()
        }
        val before = imageRejectionDb(samples, 37)
        val corrector = QmxIqCorrector()
        var corrected = samples
        repeat(16) { corrected = corrector.process(samples) }
        val after = imageRejectionDb(corrected, 37)
        assertTrue("before=$before after=$after", after > before + 12.0)
        assertTrue(corrector.qGain in 1.2..1.7)
        corrector.reset(); assertEquals(0L, corrector.blocksProcessed)
    }

    @Test fun ifOffsetMapsVfoAndModeAwareAxisExactly() {
        val route = QmxAudioRouteEvidence("a".repeat(64), true, 48_000, 2, 24, QmxIqChannelOrder.I_LEFT_Q_RIGHT)
        val profile = QmxPanadapterProfile.resolve(route, "a".repeat(64), QmxModel.QMX, 700, QmxSettingsDocument())!!
        assertEquals(14_074_000L, profile.frequencyAtBaseband(14_074_000, 12_000.0, QmxMode.USB))
        assertEquals(14_075_000L, profile.frequencyAtBaseband(14_074_000, 13_000.0, QmxMode.USB))
        assertEquals(14_073_000L, profile.frequencyAtBaseband(14_074_000, 13_000.0, QmxMode.LSB))
        assertEquals(13_000.0, profile.basebandOffsetForFrequency(14_074_000, 14_075_000, QmxMode.USB), 0.001)
    }

    @Test fun settingsRestoreIsBoundedAndContainsNoTransmitAuthority() {
        val raw = QmxSettingsDocument(smoothing = 4.0, zoom = 3, ifOffsetOverrideHz = 99_000).toSafeMap().toMutableMap()
        raw["tx_active"] = "true"; raw["digi_arm"] = "true"; raw["terminal_session"] = "open"
        val restored = QmxSettingsDocument.restore(raw)
        assertEquals(1.0, restored.smoothing, 0.0)
        assertEquals(1, restored.zoom)
        assertEquals(24_000, restored.ifOffsetOverrideHz)
        assertFalse(restored.toSafeMap().keys.any { "tx" in it || "arm" in it || "terminal" in it })
    }

    @Test fun menuTerminalRequiresExtraCdcAndExplicitOperatorOpen() {
        val port = FakeTerminalPort(); val controller = QmxMenuTerminalController(port)
        assertFalse(controller.open(profile(extra = false), true))
        assertFalse(controller.open(profile(extra = true), false))
        assertTrue(controller.open(profile(extra = true), true))
        assertEquals(5, port.interfaceNumber)
        assertEquals(listOf(0x0d.toByte()), port.writes.first().toList())
        controller.close(); assertEquals(1, port.closeCount)
    }

    @Test fun terminalInputIsPrintableAndBounded() {
        val port = FakeTerminalPort(); val controller = QmxMenuTerminalController(port, maximumInputCharacters = 16)
        controller.open(profile(extra = true), true)
        assertTrue(controller.sendPrintable("1234567890123456"))
        assertFalse(controller.sendPrintable("X"))
        assertFalse(controller.sendPrintable("bad\u0000binary"))
        assertTrue(controller.sendKey(QmxTerminalKey.BACKSPACE))
        assertTrue(controller.sendPrintable("X"))
        assertFalse(controller.includeTranscriptInSupportBundle())
    }

    @Test fun ansiModelStaysWithinEightyByTwentyFour() {
        val terminal = QmxAnsiTerminal()
        terminal.feed("\u001b[2J\u001b[99;999HEND\u001b[?25l".toByteArray())
        val snapshot = terminal.snapshot(true)
        assertEquals(24, snapshot.rows.size)
        assertTrue(snapshot.rows.all { it.length == 80 })
        assertEquals(23, snapshot.cursorRow)
        assertEquals(79, snapshot.cursorColumn)
        assertFalse(snapshot.cursorVisible)
    }

    private fun imageRejectionDb(samples: FloatArray, bin: Int): Double {
        fun amplitude(sign: Double): Double {
            var real = 0.0; var imaginary = 0.0
            repeat(samples.size / 2) { index ->
                val i = samples[index * 2].toDouble(); val q = samples[index * 2 + 1].toDouble()
                val phase = sign * 2.0 * PI * bin * index / (samples.size / 2)
                real += i * cos(phase) - q * sin(phase)
                imaginary += i * sin(phase) + q * cos(phase)
            }
            return sqrt(real * real + imaginary * imaginary)
        }
        return 20.0 * log10(amplitude(-1.0).coerceAtLeast(1e-12) / amplitude(1.0).coerceAtLeast(1e-12))
    }

    private fun profile(extra: Boolean): QmxUsbCompositeProfile = QmxUsbCompositeProfile(
        "a".repeat(64), QmxModel.QMX_PLUS, 0, 1, setOf(3), if (extra) setOf(5) else emptySet(),
    )

    private class FakeTerminalPort : QmxMenuTerminalPort {
        var interfaceNumber = -1; val writes = mutableListOf<ByteArray>(); var closeCount = 0
        override fun open(interfaceNumber: Int, onBytes: (ByteArray) -> Unit): Boolean { this.interfaceNumber = interfaceNumber; return true }
        override fun write(bytes: ByteArray): Boolean { writes += bytes.copyOf(); return true }
        override fun closeSafely(): Boolean { closeCount++; return true }
    }
}
