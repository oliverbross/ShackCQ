package app.shackcq.mobile.hamclock.finishline

import org.json.JSONObject
import app.shackcq.mobile.hamclock.HamClockNoiseEnvironment

internal data class HamClockNativeStatus(
    val available: Boolean,
    val status: String,
    val engine: String,
    val model: String,
    val dataPack: String,
    val elapsedMicros: Long,
    val errors: List<String>,
)

internal object HamClockNativePropagation {
    init { System.loadLibrary("shackcq") }

    fun evaluate(input: HamClockPropagationInput): HamClockNativeStatus {
        val root = JSONObject(nativeEvaluate(
            input.txLatitude, input.txLongitude, input.rxLatitude, input.rxLongitude,
            input.year, input.month, input.utcHour, input.sunspotNumber, input.txPowerWatts,
            input.txGainDb, input.rxGainDb, input.frequenciesMHz.toDoubleArray(),
            input.noiseEnvironment.ordinal, input.requiredReliability, input.requiredSnrDb,
            input.bandwidthHz, input.digital, input.longPath,
        ))
        val errors = root.optJSONArray("errors")
        return HamClockNativeStatus(
            root.optBoolean("available"), root.optString("status"), root.optString("engine"),
            root.optString("model"), root.optString("dataPack"), root.optLong("elapsedMicros"),
            buildList { if (errors != null) for (index in 0 until errors.length()) add(errors.getString(index)) },
        )
    }

    private external fun nativeEvaluate(
        txLatitude: Double, txLongitude: Double, rxLatitude: Double, rxLongitude: Double,
        year: Int, month: Int, utcHour: Int, sunspotNumber: Int, txPowerWatts: Double,
        txGainDb: Double, rxGainDb: Double, frequenciesMHz: DoubleArray, noiseEnvironment: Int,
        requiredReliability: Int, requiredSnrDb: Double, bandwidthHz: Double,
        digital: Boolean, longPath: Boolean,
    ): String
}

internal data class HamClockPropagationInput(
    val txLatitude: Double,
    val txLongitude: Double,
    val rxLatitude: Double,
    val rxLongitude: Double,
    val year: Int,
    val month: Int,
    val utcHour: Int,
    val sunspotNumber: Int,
    val txPowerWatts: Double,
    val txGainDb: Double,
    val rxGainDb: Double,
    val frequenciesMHz: List<Double>,
    val noiseEnvironment: HamClockNoiseEnvironment,
    val requiredReliability: Int,
    val requiredSnrDb: Double,
    val bandwidthHz: Double,
    val digital: Boolean,
    val longPath: Boolean,
)

