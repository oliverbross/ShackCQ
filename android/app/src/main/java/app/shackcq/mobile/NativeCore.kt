package app.shackcq.mobile

data class RadioState(
    val identity: String = "UNAVAILABLE",
    val model: String = "UNIDENTIFIED",
    val mode: String = "--",
    val frequencyHz: Long = 0,
    val frequencyBHz: Long = 0,
    val connected: Boolean = false,
    val transmitting: Boolean = false,
    val meter: Int = 0,
    val swrTenths: Int = -1,
    val rfOutputTenths: Int = -1,
    val afGain: Int = 0,
    val rfGain: Int = 0,
    val bandwidthHz: Int = 0,
    val powerW: Int = 0,
    val preamp: Boolean = false,
    val attenuator: Boolean = false,
    val rit: Boolean = false,
    val xit: Boolean = false,
    val rxVfo: Int = 0,
    val txVfo: Int = 0,
    val split: Boolean = false,
    val agcMode: Int = -1,
    val cwt: Boolean = false,
    val monitorLevel: Int = -1,
    val micGain: Int = -1,
    val keyerSpeed: Int = -1,
    val ifShiftHz: Int = -1,
    val revision: Long = 0,
    val ritXitOffsetHz: Int = 0,
    val effectiveRxHz: Long = 0,
    val effectiveTxHz: Long = 0,
    val dataSubmode: Int = -1,
    val updatedMonotonicMs: Long = 0,
    val cwDecodedText: String = "",
) {
    val status get() = if (connected) "LIVE" else "OFFLINE"
    val frequencyText get() = if (connected) formatRadioFrequency(frequencyHz) + " MHz" else "—.——— MHz"
}

fun formatRadioFrequency(frequencyHz: Long): String {
    if (frequencyHz <= 0) return "—.———"
    val megahertz = frequencyHz / 1_000_000
    val kilohertz = (frequencyHz / 1_000) % 1_000
    val subKilohertz = frequencyHz % 1_000
    val fine = when {
        subKilohertz % 100 == 0L -> "%01d".format(subKilohertz / 100)
        subKilohertz % 10 == 0L -> "%02d".format(subKilohertz / 10)
        else -> "%03d".format(subKilohertz)
    }
    return "%d.%03d.%s".format(megahertz, kilohertz, fine)
}

object NativeCore {
    init { System.loadLibrary("shackcq") }
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun feed(handle: Long, data: ByteArray): Int
    external fun state(handle: Long): String
    external fun classify(command: String): Int
    external fun qsoIdentity(callsign: String, timestamp: String, frequency: Long, mode: String): String
    external fun adif(identity: String, callsign: String, date: String, time: String, frequency: Long, mode: String, sent: String, received: String): String
    external fun version(): String
    external fun featureCreate(): Long
    external fun featureDestroy(handle: Long)
    external fun featureWatchlist(handle: Long, value: String)
    external fun featureLoadCty(handle: Long, text: String): Boolean
    external fun featureClusterLine(handle: Long, value: String, epoch: Long): Boolean
    external fun featureDxSnapshot(handle: Long, epoch: Long): String
    external fun featureBeginWorkedSync(handle: Long): Boolean
    external fun featureAddWorkedQso(handle: Long, callsign: String, entity: String, band: String,
        mode: String, submode: String, epoch: Long, fromWavelog: Boolean): Boolean
    external fun featureEndWorkedSync(handle: Long): Boolean
    external fun featureSolar(handle: Long, flux: Float, aIndex: Float, kpIndex: Float, epoch: Long)
    external fun flexCreate(): Long
    external fun flexDestroy(handle: Long)
    external fun flexFeed(handle: Long, data: ByteArray): Int
    external fun flexState(handle: Long): String
    external fun flexIdentity(program: String): String
    external fun flexSubscriptions(): String
    external fun flexKeepalive(): String
    external fun flexFrequency(slice: Int, frequencyHz: Long): String
    external fun flexMode(slice: Int, mode: String): String
    external fun flexFilter(letter: String, lowHz: Int, highHz: Int): String
    external fun flexParseDiscovery(data: ByteArray): String
    external fun digiCreate(sampleRate: Int, cwPitchHz: Float, rttyReverse: Boolean, rttyCentreHz: Float): Long
    external fun digiDestroy(handle: Long)
    external fun digiFeedCw(handle: Long, samples: FloatArray): String
    external fun digiFeedRtty(handle: Long, samples: FloatArray): String
    external fun digiFeedSstv(handle: Long, samples: FloatArray): String
    external fun digiDecodeSlot(mode: Int, samples: FloatArray, sampleRate: Int): String
    external fun digiSpectrum(samples: FloatArray, sampleRate: Int, lowHz: Float, highHz: Float, bins: Int, window: Int): FloatArray
    external fun digiDecodePsk31(samples: FloatArray, carrierHz: Float): String
    external fun digiSstvImage(handle: Long): ByteArray
    external fun digiEncodeCw(text: String, wpm: Int, pitchHz: Float, sampleRate: Int): FloatArray
    external fun digiEncodeRtty(text: String, sampleRate: Int, reverse: Boolean): FloatArray
    external fun digiEncodeSlot(mode: Int, text: String, baseHz: Float): FloatArray
    external fun digiEncodePsk31(text: String, carrierHz: Float): FloatArray
    external fun digiEncodeSstv(mode: Int, rgb: ByteArray, width: Int, height: Int, sampleRate: Int): FloatArray

    fun parseState(value: String): RadioState {
        val fields = value.split('|')
        if (fields.size != 33) return RadioState()
        return RadioState(fields[0], fields[1], fields[2], fields[3].toLongOrNull() ?: 0,
            fields[4].toLongOrNull() ?: 0, fields[5] == "1", fields[6] == "1",
            fields[7].toIntOrNull() ?: 0, fields[8].toIntOrNull() ?: -1, fields[9].toIntOrNull() ?: -1,
            fields[10].toIntOrNull() ?: 0, fields[11].toIntOrNull() ?: 0, fields[12].toIntOrNull() ?: 0,
            fields[13].toIntOrNull() ?: 0, fields[14] == "1", fields[15] == "1", fields[16] == "1",
            fields[17] == "1", fields[18].toIntOrNull() ?: 0, fields[19].toIntOrNull() ?: 0,
            fields[20] == "1", fields[21].toIntOrNull() ?: -1, fields[22] == "1",
            fields[23].toIntOrNull() ?: -1, fields[24].toIntOrNull() ?: -1,
            fields[25].toIntOrNull() ?: -1, fields[26].toIntOrNull() ?: -1,
            fields[27].toLongOrNull() ?: 0, fields[28].toIntOrNull() ?: 0,
            fields[29].toLongOrNull() ?: 0, fields[30].toLongOrNull() ?: 0,
            fields[31].toIntOrNull() ?: -1, fields[32].toLongOrNull() ?: 0)
    }
}
