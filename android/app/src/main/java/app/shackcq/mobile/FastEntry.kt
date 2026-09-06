package app.shackcq.mobile

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

data class FastEntryDefaults(
    val date: LocalDate,
    val operatorCallsign: String = "",
    val stationCallsign: String = "",
    val stationProfileId: String = "",
    val myGrid: String = "",
    val band: String = "",
    val mode: String = "",
    val submode: String = "",
    val frequencyMhz: Double? = null,
    val duplicateKeys: Set<String> = emptySet(),
)
data class FastEntryIssue(val line: Int, val message: String, val warning: Boolean = false)
data class FastEntryRow(val line: Int, val qso: Qso, val inherited: Set<String>) {
    val duplicateKey: String get() = fastEntryDuplicateKey(qso)
}
data class FastEntryResult(val rows: List<FastEntryRow>, val issues: List<FastEntryIssue>) {
    val errors get() = issues.filterNot { it.warning }
    val warnings get() = issues.filter { it.warning }
}

fun fastEntryDuplicateKey(qso: Qso) = listOf(
    qso.callsign.trim().uppercase(Locale.US), qso.frequencyHz.toString(), qso.mode.trim().uppercase(Locale.US),
    (qso.createdAt / 15L).toString(),
).joinToString("|")

object FastEntryParser {
    private val callPattern = Regex("(?i)^(?:[A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}\\d[A-Z0-9]{1,5}(?:/[A-Z0-9]{1,4})?$")
    private val gridPattern = Regex("(?i)^#?[A-R]{2}\\d{2}(?:[A-X]{2}(?:\\d{2}(?:[A-X]{2})?)?)?$")
    private val reportPattern = Regex("^[-+]?\\d{1,3}$")
    private val knownModes = setOf("AM", "ARDOP", "ATV", "C4FM", "CHIP", "CLO", "CONTESTI", "CW", "CWR", "DIGITALVOICE", "DOMINO", "DSTAR", "FAX", "FM", "FREEDV", "FSK441", "FT4", "FT8", "HELL", "ISCAT", "JT4", "JT6M", "JT9", "JT44", "JT65", "JS8", "MFSK", "MSK144", "MT63", "OLIVIA", "OPERA", "PAC", "PAX", "PKT", "PSK", "PSK31", "PSK63", "Q15", "QRA64", "ROS", "RTTY", "RTTYM", "SSB", "SSTV", "T10", "THOR", "THRB", "TOR", "USB", "LSB", "V4", "VOI", "WINMOR", "WSPR", "DATA", "DIGI")
    private val knownBands = setOf("2190m", "630m", "560m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "8m", "6m", "5m", "4m", "2m", "1.25m", "70cm", "33cm", "23cm", "13cm", "9cm", "6cm", "3cm", "1.25cm", "6mm", "4mm", "2.5mm", "2mm", "1mm", "submm", "sat")
    private val referencePattern = Regex("(?i)^(?:[A-Z0-9]{1,4}/[A-Z]{2}-\\d{3}|[A-Z]*[FNSUACA]-\\d{3}|[A-Z0-9]{1,4}-\\d{4,5}|[A-Z0-9]{1,4}FF-\\d{4,5})$")

    fun parse(text: String, defaults: FastEntryDefaults): FastEntryResult {
        var date = defaults.date
        var timezoneMinutes = 0
        var band = defaults.band
        var mode = defaults.submode.ifBlank { defaults.mode }
        var frequencyMhz = defaults.frequencyMhz
        var time = ""
        var operator = defaults.operatorCallsign
        val retained = linkedMapOf<String, String>()
        defaults.myGrid.takeIf(String::isNotBlank)?.let { retained["MY_GRIDSQUARE"] = it }
        val rows = mutableListOf<FastEntryRow>()
        val issues = mutableListOf<FastEntryIssue>()
        val seen = defaults.duplicateKeys.toMutableSet()

        text.lineSequence().forEachIndexed { zeroIndex, original ->
            val lineNumber = zeroIndex + 1
            var line = original.trim()
            if (line.isBlank() || line.startsWith("//") || line.startsWith("# ")) return@forEachIndexed

            parseTimezone(line)?.let { offset ->
                if (offset !in -14 * 60..14 * 60) issues += FastEntryIssue(lineNumber, "Timezone offset must be between -14:00 and +14:00")
                else timezoneMinutes = offset
                return@forEachIndexed
            }
            Regex("(?i)^(?:DATE\\s+)?(\\d{4}-\\d{2}-\\d{2}|\\d{8})$").matchEntire(line)?.let { match ->
                val parsed = runCatching {
                    val raw = match.groupValues[1]
                    if ('-' in raw) LocalDate.parse(raw) else LocalDate.of(raw.take(4).toInt(), raw.substring(4, 6).toInt(), raw.takeLast(2).toInt())
                }.getOrNull()
                if (parsed == null) issues += FastEntryIssue(lineNumber, "Invalid date") else date = parsed
                return@forEachIndexed
            }
            Regex("(?i)^DAY\\s*(\\++)$").matchEntire(line)?.let {
                date = date.plusDays(it.groupValues[1].length.toLong())
                return@forEachIndexed
            }

            val fields = linkedMapOf<String, String>()
            Regex("<([^>]*)>|\\[([^]]*)]|\\{([^}]*)}").findAll(line).toList().forEach { match ->
                line = line.replace(match.value, " ")
                val angle = match.groupValues[1]
                val bracket = match.groupValues[2]
                val brace = match.groupValues[3]
                when {
                    bracket.isNotBlank() -> appendField(fields, "QSLMSG", bracket.trim())
                    brace.isNotBlank() -> appendField(fields, "COMMENT", brace.trim())
                    else -> {
                        val pair = Regex("^([A-Za-z0-9_]+)\\s*[:=]\\s*(.*)$").matchEntire(angle.trim())
                        if (pair == null) appendField(fields, "COMMENT", angle.trim())
                        else {
                            val key = pair.groupValues[1].uppercase(Locale.US)
                            val value = pair.groupValues[2].trim()
                            if (value.isBlank()) retained.remove(key) else {
                                if (key.startsWith("MY_") || key in setOf("TX_PWR", "OPERATOR", "BAND", "MODE", "SUBMODE", "FREQ")) retained[key] = value
                                fields[key] = value
                            }
                        }
                    }
                }
            }

            val tokens = line.split(Regex("\\s+")).filter(String::isNotBlank)
            if (tokens.isEmpty()) { retained.putAll(fields); return@forEachIndexed }
            val callsignIndex = tokens.indexOfFirst { callPattern.matches(it) && normalizedBand(it) == null && normalizedMode(it) == null }
            if (callsignIndex < 0) {
                tokens.forEach { token ->
                    when {
                        normalizedBand(token) != null -> { band = normalizedBand(token)!!; frequencyMhz = null }
                        normalizedMode(token) != null -> mode = normalizedMode(token)!!
                        frequency(token) != null -> { frequencyMhz = frequency(token); band = bandForFastEntryFrequency(frequencyMhz!!) }
                        token.startsWith("OPERATOR=", true) -> operator = token.substringAfter('=').uppercase(Locale.US)
                        referencePattern.matches(token) -> retainReference(retained, token)
                        else -> issues += FastEntryIssue(lineNumber, "Header has no callsign; unrecognized token: $token")
                    }
                }
                retained.putAll(fields)
                return@forEachIndexed
            }

            val inherited = mutableSetOf("date")
            var callsign = ""
            var grid = ""
            var rstSent = ""
            var rstReceived = ""
            var name = ""
            val references = mutableListOf<String>()
            var explicitBand = false
            var explicitMode = false
            var explicitFrequency = false
            var explicitTime = false

            tokens.forEachIndexed { index, token ->
                val tokenBand = normalizedBand(token)
                val tokenMode = normalizedMode(token)
                val tokenFrequency = frequency(token)
                when {
                    tokenBand != null -> { band = tokenBand; frequencyMhz = null; explicitBand = true }
                    tokenMode != null -> { mode = tokenMode; explicitMode = true }
                    tokenFrequency != null -> { frequencyMhz = tokenFrequency; band = bandForFastEntryFrequency(tokenFrequency); explicitFrequency = true }
                    token.matches(Regex("^[0-2]\\d[0-5]\\d(?:[0-5]\\d)?$")) -> { time = token.take(4); explicitTime = true }
                    index < callsignIndex && time.isNotBlank() && token.matches(Regex("^[0-9]$")) -> { time = time.dropLast(1) + token; explicitTime = true }
                    index < callsignIndex && time.isNotBlank() && token.matches(Regex("^[0-5]\\d$")) -> { time = time.dropLast(2) + token; explicitTime = true }
                    callsign.isBlank() && callPattern.matches(token) -> callsign = token.uppercase(Locale.US)
                    gridPattern.matches(token) -> grid = token.removePrefix("#").uppercase(Locale.US)
                    callsign.isNotBlank() && reportPattern.matches(token) && rstSent.isBlank() -> rstSent = token
                    callsign.isNotBlank() && reportPattern.matches(token) && rstReceived.isBlank() -> rstReceived = token
                    callsign.isNotBlank() && token.startsWith("@") -> name = token.drop(1).replace('_', ' ')
                    callsign.isNotBlank() && token.startsWith(",") -> parseExchange(token.drop(1), true, fields)
                    callsign.isNotBlank() && token.startsWith(".") -> parseExchange(token.drop(1), false, fields)
                    referencePattern.matches(token) -> references += token.uppercase(Locale.US)
                    else -> issues += FastEntryIssue(lineNumber, "Unrecognized token: $token", warning = callsign.isNotBlank())
                }
            }

            if (band.isBlank()) issues += FastEntryIssue(lineNumber, "Band or frequency is required") else if (!explicitBand && !explicitFrequency) inherited += "band"
            if (mode.isBlank()) issues += FastEntryIssue(lineNumber, "Mode is required") else if (!explicitMode) inherited += "mode"
            if (time.isBlank()) issues += FastEntryIssue(lineNumber, "UTC/local time is required") else if (!explicitTime) inherited += "time"
            if (operator.isNotBlank() && operator == defaults.operatorCallsign) inherited += "operator"
            if (issues.any { it.line == lineNumber && !it.warning }) return@forEachIndexed

            val frequencyHz = frequencyMhz?.times(1_000_000)?.toLong() ?: defaultFrequency(band, mode)
            if (frequencyHz <= 0) {
                issues += FastEntryIssue(lineNumber, "Frequency is unknown for $band $mode; enter an explicit MHz value")
                return@forEachIndexed
            }
            val local = LocalDateTime.of(date.year, date.month, date.dayOfMonth, time.take(2).toInt(), time.drop(2).take(2).toInt())
            val utc = local.minusMinutes(timezoneMinutes.toLong())
            val allFields = retained + fields
            val allReferences = (retainedReferences(retained) + references).distinct()
            val pota = allReferences.filter { it.matches(Regex("(?i)^[A-Z0-9]{1,4}-\\d{4,5}$")) && !it.contains("FF-") }
            val qso = Qso(
                id = UUID.randomUUID().toString(), callsign = callsign, frequencyHz = frequencyHz,
                mode = mainMode(mode), submode = mode.takeIf { mainMode(it) != it }.orEmpty(),
                rstSent = rstSent.ifBlank { defaultReport(mode) }, rstReceived = rstReceived.ifBlank { defaultReport(mode) },
                createdAt = utc.toEpochSecond(ZoneOffset.UTC), band = band, grid = grid, name = name,
                operatorCallsign = allFields["OPERATOR"] ?: operator, stationCallsign = defaults.stationCallsign,
                stationProfileId = defaults.stationProfileId, myGrid = allFields["MY_GRIDSQUARE"] ?: defaults.myGrid,
                txPowerW = allFields["TX_PWR"]?.toDoubleOrNull()?.toInt() ?: 0,
                comment = allFields["COMMENT"].orEmpty(), qslMessage = allFields["QSLMSG"].orEmpty(),
                sotaRef = allReferences.firstOrNull { it.contains('/') }.orEmpty(),
                iota = allReferences.firstOrNull { it.matches(Regex("(?i)^[A-Z]*[FNSUACA]-\\d{3}$")) }.orEmpty(),
                wwffRef = allReferences.firstOrNull { it.contains("FF-") }.orEmpty(), potaRef = pota.firstOrNull().orEmpty(),
                potaRefs = pota, myPotaRefs = retainedReferences(retained, "MY_POTA_REF"),
                contestId = allFields["CONTEST_ID"].orEmpty(), propagationMode = allFields["PROP_MODE"].orEmpty(),
                frequencyRxHz = allFields["FREQ_RX"]?.replace(',', '.')?.toDoubleOrNull()?.times(1_000_000)?.toLong() ?: 0,
                bandRx = allFields["BAND_RX"].orEmpty(),
                extraAdifFields = (allFields + mapOf("FAST_ENTRY_REFS" to allReferences.joinToString(",")))
                    .filterKeys { it !in WavelogCanonicalizer.shackCQFields },
            )
            val row = FastEntryRow(lineNumber, qso, inherited)
            if (!seen.add(row.duplicateKey)) issues += FastEntryIssue(lineNumber, "Duplicate candidate: same callsign, frequency, mode and 15-second window", warning = true)
            rows += row
        }
        return FastEntryResult(rows, issues)
    }

    private fun parseTimezone(line: String): Int? {
        val match = Regex("(?i)^(?:TIMEZONE|TZOFS)\\s+([+-])(\\d{1,2})(?::?(\\d{2}))?$").matchEntire(line) ?: return null
        val minutes = match.groupValues[2].toInt() * 60 + match.groupValues[3].ifBlank { "0" }.toInt()
        return if (match.groupValues[1] == "-") -minutes else minutes
    }
    private fun normalizedBand(token: String): String? = knownBands.firstOrNull { it.equals(token, true) }
    private fun normalizedMode(token: String): String? = token.uppercase(Locale.US).takeIf(knownModes::contains)
    private fun frequency(token: String): Double? = token.replace(',', '.').toDoubleOrNull()?.takeIf { '.' in token || ',' in token }
    private fun appendField(fields: MutableMap<String, String>, key: String, value: String) {
        fields[key] = listOfNotNull(fields[key], value).filter(String::isNotBlank).joinToString(" ")
    }
    private fun retainReference(fields: MutableMap<String, String>, reference: String) = appendField(fields, "FAST_ENTRY_REFS", reference.uppercase(Locale.US))
    private fun retainedReferences(fields: Map<String, String>, key: String = "FAST_ENTRY_REFS") = fields[key].orEmpty().split(Regex("[,;\\s]+")).filter(String::isNotBlank)
    private fun parseExchange(value: String, sent: Boolean, fields: MutableMap<String, String>) {
        val parts = value.split('.', ',')
        parts.firstOrNull()?.takeIf { it.all(Char::isDigit) }?.let { fields[if (sent) "STX" else "SRX"] = it }
        parts.drop(1).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank)?.let { fields[if (sent) "STX_STRING" else "SRX_STRING"] = it }
    }
    private fun mainMode(mode: String) = when (mode.uppercase(Locale.US)) {
        "USB", "LSB" -> "SSB"; "FT8", "FT4", "JS8", "JT4", "JT9", "JT44", "JT65", "WSPR" -> "MFSK"
        "PSK31", "PSK63" -> "PSK"; "CWR" -> "CW"; else -> mode.uppercase(Locale.US)
    }
    private fun defaultReport(mode: String) = when (mainMode(mode)) { "CW" -> "599"; "MFSK", "PSK", "RTTY", "DATA", "DIGI" -> "+0"; else -> "59" }
    private fun defaultFrequency(band: String, mode: String): Long {
        val middle = mapOf("2190m" to 136_000L, "630m" to 475_000L, "160m" to 1_900_000L, "80m" to 3_700_000L,
            "60m" to 5_357_000L, "40m" to 7_100_000L, "30m" to 10_120_000L, "20m" to 14_200_000L,
            "17m" to 18_100_000L, "15m" to 21_250_000L, "12m" to 24_930_000L, "10m" to 28_500_000L,
            "6m" to 50_150_000L, "4m" to 70_200_000L, "2m" to 145_000_000L, "1.25m" to 223_500_000L,
            "70cm" to 433_500_000L, "33cm" to 915_000_000L, "23cm" to 1_296_000_000L)
        return middle[band.lowercase(Locale.US)]?.let { if (mainMode(mode) in setOf("CW", "MFSK", "PSK", "RTTY")) it - minOf(100_000L, it / 100) else it } ?: 0
    }
    private fun bandForFastEntryFrequency(mhz: Double): String {
        val hz = (mhz * 1_000_000).toLong()
        return when (hz) {
            in 135_000L..138_000L -> "2190m"; in 472_000L..479_000L -> "630m"; in 1_800_000L..2_000_000L -> "160m"
            in 3_500_000L..4_000_000L -> "80m"; in 5_000_000L..5_500_000L -> "60m"; in 7_000_000L..7_300_000L -> "40m"
            in 10_100_000L..10_150_000L -> "30m"; in 14_000_000L..14_350_000L -> "20m"; in 18_068_000L..18_168_000L -> "17m"
            in 21_000_000L..21_450_000L -> "15m"; in 24_890_000L..24_990_000L -> "12m"; in 28_000_000L..29_700_000L -> "10m"
            in 50_000_000L..54_000_000L -> "6m"; in 70_000_000L..71_000_000L -> "4m"; in 144_000_000L..148_000_000L -> "2m"
            in 222_000_000L..225_000_000L -> "1.25m"; in 420_000_000L..450_000_000L -> "70cm"; in 902_000_000L..928_000_000L -> "33cm"
            in 1_240_000_000L..1_300_000_000L -> "23cm"; else -> ""
        }
    }
}
