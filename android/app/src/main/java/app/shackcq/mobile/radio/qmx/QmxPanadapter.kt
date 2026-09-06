// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import kotlin.math.sqrt

enum class QmxIqChannelOrder { I_LEFT_Q_RIGHT, Q_LEFT_I_RIGHT, UNKNOWN }

data class QmxAudioRouteEvidence(
    val stableDeviceDigest: String? = null,
    val ready: Boolean = false,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val sampleBits: Int = 0,
    val channelOrder: QmxIqChannelOrder = QmxIqChannelOrder.UNKNOWN,
    val routeName: String = "UNAVAILABLE",
)

data class QmxAudioProfile(
    val stableDeviceDigest: String,
    val sampleRate: Int,
    val channels: Int,
    val sampleBits: Int,
    val channelOrder: QmxIqChannelOrder,
    val routeReady: Boolean,
    val orientationConfirmed: Boolean,
) {
    companion object {
        fun from(route: QmxAudioRouteEvidence, expectedDeviceDigest: String): QmxAudioProfile? {
            if (!route.ready || route.stableDeviceDigest != expectedDeviceDigest) return null
            if (route.sampleRate != 48_000 || route.channels != 2 || route.sampleBits !in setOf(16, 24, 32)) return null
            return QmxAudioProfile(
                expectedDeviceDigest,
                route.sampleRate,
                route.channels,
                route.sampleBits,
                route.channelOrder,
                routeReady = true,
                orientationConfirmed = route.channelOrder != QmxIqChannelOrder.UNKNOWN,
            )
        }
    }
}

data class QmxPanadapterAdapterContract(
    val model: QmxModel,
    val stableDeviceDigest: String,
    val sampleRate: Int = 48_000,
    val stereoIq: Boolean = true,
    val channelOrder: QmxIqChannelOrder,
    val orientationConfirmed: Boolean,
    val nominalIfOffsetHz: Int = 12_000,
    val effectiveIfOffsetHz: Int = 12_000,
    val cwOffsetHz: Int? = null,
    val settings: QmxSettingsDocument,
    val correction: QmxIqCorrector,
) {
    init {
        require(sampleRate == 48_000)
        require(effectiveIfOffsetHz in -24_000..24_000)
    }

    fun frequencyAtBaseband(vfoHz: Long, basebandOffsetHz: Double, mode: QmxMode): Long {
        val residual = basebandOffsetHz - effectiveIfOffsetHz
        val direction = when (mode) {
            QmxMode.LSB, QmxMode.CW_REVERSE, QmxMode.DIGI_REVERSE -> -1.0
            else -> 1.0
        }
        return (vfoHz + direction * residual).toLong()
    }

    fun basebandOffsetForFrequency(vfoHz: Long, targetHz: Long, mode: QmxMode): Double {
        val direction = when (mode) {
            QmxMode.LSB, QmxMode.CW_REVERSE, QmxMode.DIGI_REVERSE -> -1.0
            else -> 1.0
        }
        return effectiveIfOffsetHz + (targetHz - vfoHz) * direction
    }

    fun passbandOffsets(mode: QmxMode, widthHz: Int): ClosedFloatingPointRange<Double> {
        require(widthHz in 50..20_000)
        return when (mode) {
            QmxMode.LSB, QmxMode.CW_REVERSE, QmxMode.DIGI_REVERSE -> -widthHz.toDouble()..0.0
            QmxMode.AM -> -(widthHz / 2.0)..(widthHz / 2.0)
            else -> 0.0..widthHz.toDouble()
        }
    }
}

object QmxPanadapterProfile {
    fun resolve(
        route: QmxAudioRouteEvidence,
        expectedDeviceDigest: String,
        model: QmxModel,
        cwOffsetHz: Int?,
        settings: QmxSettingsDocument,
    ): QmxPanadapterAdapterContract? {
        val audio = QmxAudioProfile.from(route, expectedDeviceDigest) ?: return null
        val safe = settings.validated()
        val order = when {
            !safe.iqSwap -> audio.channelOrder
            audio.channelOrder == QmxIqChannelOrder.I_LEFT_Q_RIGHT -> QmxIqChannelOrder.Q_LEFT_I_RIGHT
            audio.channelOrder == QmxIqChannelOrder.Q_LEFT_I_RIGHT -> QmxIqChannelOrder.I_LEFT_Q_RIGHT
            else -> QmxIqChannelOrder.UNKNOWN
        }
        return QmxPanadapterAdapterContract(
            model = model,
            stableDeviceDigest = expectedDeviceDigest,
            channelOrder = order,
            orientationConfirmed = order != QmxIqChannelOrder.UNKNOWN,
            effectiveIfOffsetHz = safe.ifOffsetOverrideHz ?: 12_000,
            cwOffsetHz = cwOffsetHz,
            settings = safe,
            correction = QmxIqCorrector(enabled = safe.correctionEnabled),
        )
    }
}

/** Independently written bounded adaptive Gram-Schmidt correction for QMX stereo I/Q. */
class QmxIqCorrector(
    enabled: Boolean = true,
    private val dcAlpha: Double = 0.002,
    private val steadyAdaptation: Double = 0.04,
) {
    var enabled: Boolean = enabled
        set(value) { if (field != value) reset(); field = value }
    var dcI = 0.0; private set
    var dcQ = 0.0; private set
    var qGain = 1.0; private set
    var qLeakage = 0.0; private set
    var blocksProcessed = 0L; private set

    init {
        require(dcAlpha in 0.00001..0.1)
        require(steadyAdaptation in 0.001..0.25)
    }

    @Synchronized
    fun process(interleaved: FloatArray): FloatArray {
        require(interleaved.size % 2 == 0)
        if (!enabled || interleaved.isEmpty()) return interleaved.copyOf()
        var sumI = 0.0; var sumQ = 0.0
        var index = 0
        while (index < interleaved.size) { sumI += interleaved[index]; sumQ += interleaved[index + 1]; index += 2 }
        val pairs = interleaved.size / 2
        val meanI = sumI / pairs; val meanQ = sumQ / pairs
        dcI += dcAlpha * (meanI - dcI); dcQ += dcAlpha * (meanQ - dcQ)

        var powerI = 0.0; var powerQ = 0.0; var cross = 0.0
        index = 0
        while (index < interleaved.size) {
            val i = interleaved[index] - dcI
            val q = interleaved[index + 1] - dcQ
            powerI += i * i; powerQ += q * q; cross += i * q
            index += 2
        }
        if (powerI > 1e-12 && powerQ > 1e-12) {
            val measuredLeakage = (cross / powerI).coerceIn(-0.75, 0.75)
            var orthogonalPowerQ = 0.0
            index = 0
            while (index < interleaved.size) {
                val i = interleaved[index] - dcI
                val q = interleaved[index + 1] - dcQ - measuredLeakage * i
                orthogonalPowerQ += q * q
                index += 2
            }
            val measuredGain = sqrt(powerI / orthogonalPowerQ.coerceAtLeast(1e-12)).coerceIn(0.5, 2.0)
            val adaptation = if (blocksProcessed < 6) 0.45 else steadyAdaptation
            qLeakage += adaptation * (measuredLeakage - qLeakage)
            qGain += adaptation * (measuredGain - qGain)
        }
        blocksProcessed++
        val output = FloatArray(interleaved.size)
        index = 0
        while (index < interleaved.size) {
            val i = interleaved[index] - dcI
            val q = (interleaved[index + 1] - dcQ - qLeakage * i) * qGain
            output[index] = i.toFloat()
            output[index + 1] = q.toFloat()
            index += 2
        }
        return output
    }

    @Synchronized
    fun reset() {
        dcI = 0.0; dcQ = 0.0; qGain = 1.0; qLeakage = 0.0; blocksProcessed = 0
    }
}
