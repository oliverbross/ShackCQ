// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.radio.qmx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class QmxSurfaceLayout { COMPACT, STANDARD, WIDE_TABLET }

private val QmxAmber = Color(0xFFFFB547)
private val QmxGreen = Color(0xFF5AD18A)
private val QmxRed = Color(0xFFFF5D65)
private val QmxMuted = Color(0xFFA9B2BA)

@Composable
fun QmxRadioSurface(
    snapshot: QmxRadioSnapshot,
    actions: QmxRadioActionPort,
    layout: QmxSurfaceLayout,
    modifier: Modifier = Modifier,
) {
    val body: @Composable () -> Unit = {
        Header(snapshot)
        Frequency(snapshot, layout)
        MeterRail(snapshot)
        ModeAndFilter(snapshot, actions)
        GainControls(snapshot, actions)
        ReceiveControls(snapshot, actions)
        SafetyControls(snapshot, actions)
        Readiness(snapshot)
    }
    Card(
        modifier.widthIn(max = if (layout == QmxSurfaceLayout.WIDE_TABLET) 1_280.dp else 760.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF172027)),
    ) {
        if (layout == QmxSurfaceLayout.WIDE_TABLET) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Header(snapshot); Frequency(snapshot, layout); MeterRail(snapshot); Readiness(snapshot)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeAndFilter(snapshot, actions); GainControls(snapshot, actions); ReceiveControls(snapshot, actions); SafetyControls(snapshot, actions)
                }
            }
        } else Column(Modifier.fillMaxWidth().padding(if (layout == QmxSurfaceLayout.COMPACT) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { body() }
    }
}

@Composable private fun Header(snapshot: QmxRadioSnapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(if (snapshot.model == QmxModel.QMX_PLUS) "QMX+" else "QMX", color = QmxAmber, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("NATIVE RADIO PROFILE · USB CDC + UAC I/Q", color = QmxMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text(if (snapshot.ready) "READY" else if (snapshot.connected) "CONNECTING" else "OFFLINE", color = if (snapshot.ready) QmxGreen else QmxAmber, fontWeight = FontWeight.Black)
    }
}

@Composable private fun Frequency(snapshot: QmxRadioSnapshot, layout: QmxSurfaceLayout) {
    val frequency = snapshot.vfoAHz?.let(::formatQmxFrequency) ?: "—.———.———"
    Text(frequency, color = QmxAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = if (layout == QmxSurfaceLayout.COMPACT) 31.sp else 42.sp)
    val secondary = snapshot.vfoBHz?.let { "VFO B ${formatQmxFrequency(it)}" } ?: "VFO B unavailable"
    Text("$secondary · ${if (snapshot.split == QmxTriState.TRUE) "SPLIT" else "SIMPLEX/UNKNOWN"} · ${qmxBand(snapshot.vfoAHz)}", color = QmxMuted)
}

@Composable private fun MeterRail(snapshot: QmxRadioSnapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("S ${snapshot.sMeter?.toString() ?: "—"}", color = QmxGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        if (snapshot.txState == QmxTxState.TX) {
            Text("PWR ${snapshot.powerWatts?.let { "%.1f W".format(it) } ?: "—"}", color = QmxRed, fontWeight = FontWeight.Bold)
            Text("SWR ${snapshot.swr?.let { "%.2f".format(it) } ?: "—"}", color = if (snapshot.swrFault) QmxRed else QmxGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun ModeAndFilter(snapshot: QmxRadioSnapshot, actions: QmxRadioActionPort) {
    if (snapshot.capabilities.mode == QmxCapabilityState.SUPPORTED) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(QmxMode.LSB, QmxMode.USB, QmxMode.CW, QmxMode.DIGI).plus(if (snapshot.capabilities.amMode == QmxCapabilityState.SUPPORTED) listOf(QmxMode.AM) else emptyList()).forEach { mode ->
                FilterChip(snapshot.mode == mode, { actions.emit(QmxRadioAction.SetMode(mode)) }, { Text(mode.name) }, enabled = snapshot.ready && snapshot.txState != QmxTxState.TX)
            }
        }
    }
    Text("MODE ${snapshot.mode.name} · FILTER ${snapshot.filterHz?.let { "$it Hz" } ?: "UNKNOWN"}", color = QmxMuted)
}

@Composable private fun GainControls(snapshot: QmxRadioSnapshot, actions: QmxRadioActionPort) {
    if (snapshot.capabilities.afGain == QmxCapabilityState.SUPPORTED) {
        var af by remember(snapshot.afGainNativeQuarterDb) { mutableFloatStateOf((snapshot.afGainNativeQuarterDb ?: 0).toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AF ${"%.2f".format(af / 4f)} dB", color = QmxMuted)
            Slider(af, { af = it; actions.emit(QmxRadioAction.SetAfGain(it.roundToInt())) }, valueRange = 0f..799f, enabled = snapshot.ready, modifier = Modifier.weight(1f))
        }
    }
    if (snapshot.capabilities.rfGain == QmxCapabilityState.SUPPORTED) {
        var rf by remember(snapshot.rfGainDb) { mutableFloatStateOf((snapshot.rfGainDb ?: 0).toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RF ${rf.roundToInt()} dB", color = QmxMuted)
            Slider(rf, { rf = it; actions.emit(QmxRadioAction.SetRfGain(it.roundToInt())) }, valueRange = 0f..99f, enabled = snapshot.ready, modifier = Modifier.weight(1f))
        }
    }
}

@Composable private fun ReceiveControls(snapshot: QmxRadioSnapshot, actions: QmxRadioActionPort) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.capabilities.rit == QmxCapabilityState.SUPPORTED) OutlinedButton({ actions.emit(QmxRadioAction.ClearRit) }, enabled = snapshot.ready) { Text("CLEAR RIT") }
        if (snapshot.capabilities.split == QmxCapabilityState.SUPPORTED) OutlinedButton({ actions.emit(QmxRadioAction.SetSplit(snapshot.split != QmxTriState.TRUE)) }, enabled = snapshot.ready) { Text(if (snapshot.split == QmxTriState.TRUE) "SPLIT OFF" else "SPLIT ON") }
    }
    Text("RIT ${snapshot.ritHz?.let { "%+d Hz".format(it) } ?: "UNKNOWN"} · CW OFFSET ${snapshot.cwOffsetHz?.let { "$it Hz" } ?: "UNKNOWN"}", color = QmxMuted)
}

@Composable private fun SafetyControls(snapshot: QmxRadioSnapshot, actions: QmxRadioActionPort) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.capabilities.directToneTx == QmxCapabilityState.SUPPORTED) Button({ actions.emit(QmxRadioAction.RequestTransmitConfirmation) }, enabled = snapshot.ready && snapshot.txState == QmxTxState.RX && !snapshot.swrFault) { Text("REQUEST DIGI TX") }
        if (snapshot.capabilities.swrTune == QmxCapabilityState.SUPPORTED) OutlinedButton({ actions.emit(QmxRadioAction.RequestSWRProtectionTuneConfirmation) }, enabled = snapshot.ready && snapshot.txState == QmxTxState.RX) { Text("REQUEST SWR TUNE") }
        if (snapshot.txState != QmxTxState.RX) Button({ actions.emit(QmxRadioAction.RequestEmergencyReceive) }) { Text("STOP / RX") }
    }
    if (snapshot.swrFault) Text("SWR FAULT LATCHED · VERIFY ANTENNA BEFORE CLEARING", color = QmxRed, fontWeight = FontWeight.Black)
}

@Composable private fun Readiness(snapshot: QmxRadioSnapshot) {
    val iq = if (snapshot.iqModeEnabled == QmxTriState.TRUE) "IQ CONFIRMED" else "IQ UNCONFIRMED"
    val vox = if (snapshot.voxDisabled == QmxTriState.TRUE) "VOX SAFE" else "VOX UNCONFIRMED"
    val terminal = if (snapshot.menuTerminalAvailable == QmxTriState.TRUE) "MENU CDC AVAILABLE" else "MENU CDC UNAVAILABLE"
    Text("$iq · $vox · $terminal", color = if (snapshot.ready) QmxGreen else QmxAmber, style = MaterialTheme.typography.labelMedium)
    Text("FW ${snapshot.firmware?.toString() ?: "UNKNOWN"} · CAP ${snapshot.capabilities.digest()} · POLL ${snapshot.sourceAgeMillis?.let { "$it ms" } ?: "UNKNOWN"}", color = QmxMuted, style = MaterialTheme.typography.labelSmall)
    snapshot.lastSanitizedError?.let { Text(it, color = QmxRed, style = MaterialTheme.typography.labelSmall) }
}

private fun formatQmxFrequency(hertz: Long) = "%d.%03d.%03d".format(hertz / 1_000_000, (hertz / 1_000) % 1_000, hertz % 1_000)
private fun qmxBand(hertz: Long?): String = when (hertz ?: 0L) {
    in 1_800_000L..2_000_000L -> "160 m"
    in 3_500_000L..4_000_000L -> "80 m"
    in 5_250_000L..5_450_000L -> "60 m"
    in 7_000_000L..7_300_000L -> "40 m"
    in 10_100_000L..10_150_000L -> "30 m"
    in 14_000_000L..14_350_000L -> "20 m"
    in 18_068_000L..18_168_000L -> "17 m"
    in 21_000_000L..21_450_000L -> "15 m"
    in 24_890_000L..24_990_000L -> "12 m"
    in 28_000_000L..29_700_000L -> "10 m"
    in 50_000_000L..54_000_000L -> "6 m"
    else -> "OUT OF BAND"
}
