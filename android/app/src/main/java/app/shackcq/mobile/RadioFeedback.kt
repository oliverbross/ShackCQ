package app.shackcq.mobile

data class RadioFeedback(val title: String, val value: String, val details: List<String> = emptyList())
data class RadioFeedbackBurst(val baseline: RadioState, val feedback: RadioFeedback)

private data class RadioChange(val key: String, val title: String, val value: String) {
    val detail: String get() = "$title $value"
}

private fun compactBandwidth(value: Int): String = when {
    value <= 0 -> ""
    value % 1_000 == 0 -> "${value / 1_000} kHz"
    value >= 1_000 && value % 100 == 0 -> "${value / 1_000}.${value % 1_000 / 100} kHz"
    else -> "$value Hz"
}

/** Describes operator-facing KX3 changes while intentionally ignoring VFO tuning and meter traffic. */
fun detectRadioFeedback(previous: RadioState, current: RadioState): RadioFeedback? {
    if (!previous.connected || !current.connected) return null
    val changes = buildList {
        // MODE owns a coupled KX3 burst: filter width/shift can follow in later CAT frames.
        if (previous.mode != current.mode && current.mode != "--") add(RadioChange("mode", "MODE", current.mode))
        if (previous.keyerSpeed != current.keyerSpeed && current.keyerSpeed >= 8)
            add(RadioChange("keyer", "KEYER SPEED", "${current.keyerSpeed} WPM"))
        if (previous.afGain != current.afGain)
            add(RadioChange("af", "AF GAIN", "${current.afGain} / $KX3_AF_GAIN_MAX"))
        if (previous.rfGain != current.rfGain)
            add(RadioChange("rf", "RF GAIN", "${current.rfGain} / $KX3_RF_GAIN_MAX"))
        if (previous.monitorLevel != current.monitorLevel && current.monitorLevel >= 0)
            add(RadioChange("monitor", "MONITOR", "${current.monitorLevel} / 60"))
        if (previous.micGain != current.micGain && current.micGain >= 0)
            add(RadioChange("mic", "MIC GAIN", "${current.micGain} / 60"))
        if (previous.bandwidthHz != current.bandwidthHz && current.bandwidthHz > 0)
            add(RadioChange("filter", "FILTER WIDTH", "${current.bandwidthHz} Hz"))
        if (previous.ifShiftHz != current.ifShiftHz && current.ifShiftHz >= 0)
            add(RadioChange("shift", "IF SHIFT", "${current.ifShiftHz} Hz"))
        if (previous.powerW != current.powerW) add(RadioChange("power", "TX POWER", "${current.powerW} W"))
        if (previous.agcMode != current.agcMode && current.agcMode >= 0) add(RadioChange("agc", "AGC",
            when (current.agcMode) { 0 -> "OFF"; 2 -> "FAST"; 4 -> "SLOW"; else -> current.agcMode.toString() }))
        if (previous.preamp != current.preamp) add(RadioChange("preamp", "PREAMP", if (current.preamp) "ON" else "OFF"))
        if (previous.attenuator != current.attenuator) add(RadioChange("attenuator", "ATTENUATOR", if (current.attenuator) "ON" else "OFF"))
        if (previous.rit != current.rit) add(RadioChange("rit", "RIT", if (current.rit) "ON" else "OFF"))
        if (previous.xit != current.xit) add(RadioChange("xit", "XIT", if (current.xit) "ON" else "OFF"))
        if (previous.split != current.split) add(RadioChange("split", "SPLIT", if (current.split) "ON" else "OFF"))
        if (previous.cwt != current.cwt) add(RadioChange("cwt", "CW TUNING", if (current.cwt) "ON" else "OFF"))
        if (previous.rxVfo != current.rxVfo) add(RadioChange("rx", "RX VFO", if (current.rxVfo == 0) "A" else "B"))
        if (previous.txVfo != current.txVfo) add(RadioChange("tx", "TX VFO", if (current.txVfo == 0) "A" else "B"))
    }
    val primary = changes.firstOrNull() ?: return null
    val details = buildList {
        if (primary.key == "mode") {
            compactBandwidth(current.bandwidthHz).takeIf(String::isNotBlank)?.let { add("FILTER $it") }
            if (current.ifShiftHz >= 0) add("SHIFT ${current.ifShiftHz} Hz")
        }
        changes.asSequence().filter { it.key != primary.key }
            .filterNot { primary.key == "mode" && it.key in setOf("filter", "shift") }
            .forEach { add(it.detail) }
    }.distinct()
    return RadioFeedback(primary.title, primary.value, details)
}

/** Merges later CAT frames into the active operator action without extending it for unchanged poll traffic. */
fun mergeRadioFeedbackBurst(baseline: RadioState?, previous: RadioState, current: RadioState): RadioFeedbackBurst? {
    val delta = detectRadioFeedback(previous, current) ?: return null
    val start = baseline ?: previous
    return RadioFeedbackBurst(start, detectRadioFeedback(start, current) ?: delta)
}
