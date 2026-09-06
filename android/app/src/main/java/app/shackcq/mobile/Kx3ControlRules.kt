package app.shackcq.mobile

import java.util.Locale

const val KX3_AF_GAIN_MIN = 0
const val KX3_AF_GAIN_MAX = 255
const val KX3_RF_GAIN_MIN = 0
const val KX3_RF_GAIN_MAX = 250

fun kx3AfGainCommand(value: Int): String =
    String.format(Locale.US, "AG%03d;", value.coerceIn(KX3_AF_GAIN_MIN, KX3_AF_GAIN_MAX))

fun kx3RfGainCommand(value: Int): String =
    String.format(Locale.US, "RG%03d;", value.coerceIn(KX3_RF_GAIN_MIN, KX3_RF_GAIN_MAX))
