package app.shackcq.mobile

object AudioOwners {
    const val NONE = "NONE"
    const val MONITOR = "MONITOR"
    const val PANADAPTER = "PANADAPTER"
    const val EQ = "EQ"
    const val VOICE = "VOICE"
    const val VOICE_TX = "VOICE_TX"
    const val FLEX_RX_AUDIO = "FLEX_RX_AUDIO"
    const val TCI_RX_AUDIO = "TCI_RX_AUDIO"
    const val FLEX_MIC_TX = "FLEX_MIC_TX"
    const val FLEX_VOICE_TX = "FLEX_VOICE_TX"
    const val FLEX_CW_TX = "FLEX_CW_TX"
    const val FLEX_TUNE = "FLEX_TUNE"
    const val DIGI_RX = "DIGI_RX"
    const val DIGI_TX = "DIGI_TX"
    const val FLEX_DIGI_TX = "FLEX_DIGI_TX"
}

data class AudioLeaseDecision(
    val accepted: Boolean,
    val pauseMonitor: Boolean = false,
)

fun decideAudioLease(
    currentOwner: String,
    requestedOwner: String,
    monitorRunning: Boolean,
    pauseMonitor: Boolean,
): AudioLeaseDecision {
    if (requestedOwner in setOf(AudioOwners.NONE, AudioOwners.MONITOR)) return AudioLeaseDecision(false)
    if (currentOwner == AudioOwners.NONE) return AudioLeaseDecision(true)
    if (currentOwner == AudioOwners.MONITOR && monitorRunning && pauseMonitor) {
        return AudioLeaseDecision(accepted = true, pauseMonitor = true)
    }
    return AudioLeaseDecision(false)
}

fun canStartAudioMonitor(currentOwner: String): Boolean = currentOwner == AudioOwners.NONE
