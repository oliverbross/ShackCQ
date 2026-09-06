package app.shackcq.mobile

import kotlinx.coroutines.delay

val KX3_EQ_SWITCH_COMMANDS = listOf("SWT19;", "SWT27;", "SWT20;", "SWT28;", "SWT21;", "SWT29;", "SWT32;", "SWT33;")
private val forbiddenEqCommands = listOf("TX;", "SWT16;", "SWH16;", "SWT11;", "SWH11;", "SWT44;", "SWH35;")

fun formatTxEqCommand(curve: EqCurve): String = "TE" + curve.values.joinToString("") { "%+03d".format(it) } + ";"

data class EqDbValue(val bandIndex: Int, val frequencyHz: Int, val gainDb: Int, val raw: String)

fun parseEqDbResponse(response: String, expectedBand: Int): EqDbValue {
    require(expectedBand in 0 until EQ_BAND_COUNT)
    val frame = Regex("(?:^|;)(DB[^;]*;)", RegexOption.IGNORE_CASE).findAll(response).lastOrNull()?.groupValues?.get(1)
        ?: error("No complete DB response")
    val display = frame.removeSuffix(";").trim().removePrefix("DB").trim().uppercase().replace('−', '-')
    val expectedFrequency = EQ_FREQUENCIES_HZ[expectedBand]
    val frequencyPatterns = mapOf(
        50 to listOf("0.05", "50", ".05K", "0.05K"), 100 to listOf("0.10", "100", ".10K", "0.1K"),
        200 to listOf("0.20", "200", ".20K", "0.2K"), 400 to listOf("0.40", "400", ".40K", "0.4K"),
        800 to listOf("0.80", "800", ".80K", "0.8K"), 1_600 to listOf("1.60", "1600", "1.6K", "1.60K"),
        2_400 to listOf("2.40", "2400", "2.4K", "2.40K"), 3_200 to listOf("3.20", "3200", "3.2K", "3.20K"),
    )
    val matched = frequencyPatterns.entries.firstOrNull { (_, tokens) -> tokens.any { display.contains(it) } }?.key
        ?: error("DB response does not identify an EQ band: $display")
    require(matched == expectedFrequency) { "Expected ${expectedFrequency} Hz but DB displayed $matched Hz" }
    val withoutFrequency = frequencyPatterns.getValue(matched).fold(display) { value, token -> value.replace(token, " ") }
    val signed = Regex("([+-])\\s*(\\d{1,2})").find(withoutFrequency)
    val gain = signed?.let { (if (it.groupValues[1] == "-") -1 else 1) * it.groupValues[2].toInt() }
        ?: Regex("(?:^|\\s)(0)(?:\\s|$)").find(withoutFrequency)?.groupValues?.get(1)?.toInt()
        ?: error("DB response does not contain an exact signed gain: $display")
    require(gain in -16..16) { "DB gain is outside -16..+16 dB: $gain" }
    return EqDbValue(expectedBand, expectedFrequency, gain, frame)
}

interface EqCatIo {
    suspend fun query(command: String, expectedPrefix: String, timeoutMillis: Long = 750): String
    suspend fun write(command: String)
    suspend fun pause(milliseconds: Long) { delay(milliseconds) }
}

data class EqReadResult(val snapshot: EqSnapshot, val trace: List<String>)
data class EqApplyResult(val verified: EqSnapshot?, val trace: List<String>, val failedBands: List<Int> = emptyList())

suspend fun EqCatIo.readKx3Eq(path: EqPath, model: String, firmware: String?, context: EqContext, source: String): EqReadResult {
    require(model.uppercase().contains("KX3")) { "KX3 EQ hardware access is unavailable for $model" }
    require(context.writable) { context.label }
    val trace = mutableListOf<String>()
    fun record(command: String, response: String? = null) { trace += if (response == null) command else "$command → $response" }
    val tq = query("TQ;", "TQ").also { record("TQ;", it) }
    require(tq.split(';').any { it == "TQ0" }) { "EQ is locked while the radio is transmitting" }
    val menu = query("MN;", "MN").also { record("MN;", it) }
    require(menu.split(';').any { it == "MN255" }) { "Close the menu on the KX3, then try again." }
    val values = IntArray(EQ_BAND_COUNT)
    var entered = false
    try {
        val open = if (path == EqPath.RX) "MN008;" else "MN009;"
        write(open); record(open); entered = true; pause(90)
        for (band in 0 until EQ_BAND_COUNT) {
            write(KX3_EQ_SWITCH_COMMANDS[band]); record(KX3_EQ_SWITCH_COMMANDS[band]); pause(45)
            val db = query("DB;", "DB").also { record("DB;", it) }
            values[band] = parseEqDbResponse(db, band).gainDb
        }
    } finally {
        if (entered) runCatching { write("MN255;"); record("MN255;"); pause(600) }
    }
    return EqReadResult(EqSnapshot(path, context, EqCurve.of(values.toList()), model, firmware, source), trace)
}

suspend fun EqCatIo.applyKx3Eq(target: EqCurve, baseline: EqSnapshot): EqApplyResult {
    val trace = mutableListOf<String>()
    val failed = mutableListOf<Int>()
    val path = baseline.path
    if (path == EqPath.TX) {
        val command = formatTxEqCommand(target)
        require(forbiddenEqCommands.none(command::contains))
        write(command); trace += command; pause(120)
    } else {
        var entered = false
        try {
            write("MN008;"); trace += "MN008;"; entered = true; pause(90)
            for (band in 0 until EQ_BAND_COUNT) {
                try {
                    write(KX3_EQ_SWITCH_COMMANDS[band]); trace += KX3_EQ_SWITCH_COMMANDS[band]; pause(45)
                    val observed = parseEqDbResponse(query("DB;", "DB"), band).gainDb
                    val step = if (target[band] >= observed) "UP;" else "DN;"
                    repeat(kotlin.math.abs(target[band] - observed)) { write(step); trace += step; pause(35) }
                    val verified = parseEqDbResponse(query("DB;", "DB"), band).gainDb
                    if (verified != target[band]) failed += band
                } catch (_: Exception) { failed += band; break }
            }
        } finally {
            if (entered) runCatching { write("MN255;"); trace += "MN255;"; pause(600) }
        }
    }
    val readback = try {
        readKx3Eq(path, baseline.model, baseline.firmware, baseline.context, baseline.contextSource)
    } catch (error: Exception) {
        throw IllegalStateException("Post-write full readback failed: ${error.message}", error)
    }
    readback?.trace?.let(trace::addAll)
    val mismatch = readback?.snapshot?.curve?.changedBands(target).orEmpty()
    failed += mismatch
    return EqApplyResult(readback?.snapshot, trace, failed.distinct())
}

fun eqTraceContainsTransmissionCommand(trace: List<String>): Boolean = trace.any { line ->
    line.split(' ', '→').any { token -> token.trim() in forbiddenEqCommands }
}
