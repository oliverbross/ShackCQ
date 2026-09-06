package app.shackcq.mobile

val radioPresetModes = listOf("CW", "USB", "LSB", "AM", "DATA")

fun radioPresetFilterWidths(mode: String): List<Int> = when (mode.uppercase()) {
    "CW" -> listOf(100, 200, 300, 400, 500, 1_000)
    "DATA" -> listOf(200, 400, 500, 1_000, 2_000, 3_000)
    "AM" -> listOf(3_000, 4_000, 5_000, 6_000, 7_000, 8_000)
    else -> listOf(1_800, 2_100, 2_400, 2_700, 3_000, 3_500)
}

fun radioPresetFilterLabel(widthHz: Int): String = if (widthHz >= 1_000) {
    if (widthHz % 1_000 == 0) "${widthHz / 1_000} kHz" else "%.1f kHz".format(widthHz / 1_000.0)
} else "$widthHz Hz"

fun normalizeRadioPresetMode(mode: String): String = when {
    mode.uppercase().startsWith("CW") -> "CW"
    mode.uppercase() == "USB" -> "USB"
    mode.uppercase() == "LSB" -> "LSB"
    mode.uppercase() == "AM" -> "AM"
    mode.isNotBlank() -> "DATA"
    else -> "CW"
}

fun parseRadioPresetFrequency(value: String): Long? {
    val text = value.trim()
    if (text.isBlank() || !text.all { it.isDigit() || it == '.' }) return null
    val parts = text.split('.')
    if (parts.any { it.isEmpty() }) return null
    return runCatching {
        when (parts.size) {
            1 -> parts[0].takeIf { it.length in 7..8 }?.toLong()
            2 -> {
                if (parts[0].length !in 1..2 || parts[1].length !in 1..6) null
                else parts[0].toLong() * 1_000_000L + parts[1].padEnd(6, '0').toLong()
            }
            3 -> {
                if (parts[0].length !in 1..2 || parts[1].length !in 1..3 || parts[2].length !in 1..3) null
                else parts[0].toLong() * 1_000_000L + parts[1].padEnd(3, '0').toLong() * 1_000L +
                    parts[2].padEnd(3, '0').toLong()
            }
            else -> null
        }
    }.getOrNull()
}

fun radioPresetBandName(frequencyHz: Long): String? = when (frequencyHz) {
    in 1_800_000L..2_000_000L -> "160 m"
    in 3_500_000L..4_000_000L -> "80 m"
    in 5_351_500L..5_366_500L -> "60 m"
    in 7_000_000L..7_300_000L -> "40 m"
    in 10_100_000L..10_150_000L -> "30 m"
    in 14_000_000L..14_350_000L -> "20 m"
    in 18_068_000L..18_168_000L -> "17 m"
    in 21_000_000L..21_450_000L -> "15 m"
    in 24_890_000L..24_990_000L -> "12 m"
    in 28_000_000L..29_700_000L -> "10 m"
    in 50_000_000L..54_000_000L -> "6 m"
    else -> null
}

fun isValidRadioPreset(frequencyHz: Long, mode: String, bandwidthHz: Int): Boolean =
    radioPresetBandName(frequencyHz) != null && mode in radioPresetModes && bandwidthHz in radioPresetFilterWidths(mode)
