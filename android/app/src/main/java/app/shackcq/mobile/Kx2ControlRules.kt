package app.shackcq.mobile

internal data class Kx2FaceKey(
    val tapLabel: String,
    val tapCommand: String,
    val holdLabel: String,
    val holdCommand: String,
    val transmitRisk: Boolean = false,
)

/** KX2 front-panel switch codes from Elecraft Programmer's Reference, table 8A. */
internal val kx2FaceKeys = listOf(
    Kx2FaceKey("PRE", "SWT19;", "NR", "SWH19;"),
    Kx2FaceKey("FIL", "SWT27;", "APF-AN", "SWH27;"),
    Kx2FaceKey("ATU", "SWT20;", "PFN", "SWH20;"),
    Kx2FaceKey("XMIT", "SWT16;", "TUNE", "SWH16;", transmitRisk = true),
    Kx2FaceKey("RATE", "SWT41;", "FREQ", "SWH41;"),
    Kx2FaceKey("DATA", "SWT26;", "TEXT", "SWH26;"),
    Kx2FaceKey("MSG", "SWT11;", "REC", "SWH11;", transmitRisk = true),
    Kx2FaceKey("MODE", "SWT08;", "RCL", "SWH08;"),
    Kx2FaceKey("BAND", "SWT14;", "STORE", "SWH14;"),
    Kx2FaceKey("A/B", "SWT44;", "A>B", "SWH44;"),
    Kx2FaceKey("RIT", "SWT18;", "SPLIT", "SWH18;"),
    Kx2FaceKey("DISP", "SWT09;", "MENU", "SWH09;"),
)

internal val kx2ModeCommands = listOf(
    "LSB" to "MD1;", "USB" to "MD2;", "CW" to "MD3;",
    "CW-R" to "MD7;", "AM" to "MD5;", "DATA" to "MD6;",
)

private val kx2Bands = listOf(
    1_800_000L to "BN00;", 3_500_000L to "BN01;", 5_250_000L to "BN02;",
    7_000_000L to "BN03;", 10_100_000L to "BN04;", 14_000_000L to "BN05;",
    18_068_000L to "BN06;", 21_000_000L to "BN07;", 24_890_000L to "BN08;",
    28_000_000L to "BN09;",
)

internal fun kx2AdjacentBandCommand(frequencyHz: Long, direction: Int): String {
    val current = kx2Bands.indices.minByOrNull { kotlin.math.abs(kx2Bands[it].first - frequencyHz) } ?: 0
    return kx2Bands[(current + direction.coerceIn(-1, 1)).coerceIn(kx2Bands.indices)].second
}
