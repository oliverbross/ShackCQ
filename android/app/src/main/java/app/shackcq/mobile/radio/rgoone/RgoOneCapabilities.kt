package app.shackcq.mobile.radio.rgoone

object RgoOneCapabilityResolver {
    private val v6Core = setOf(
        RgoOneCapability.VFO_A, RgoOneCapability.VFO_B, RgoOneCapability.RX_VFO, RgoOneCapability.TX_VFO,
        RgoOneCapability.MODE, RgoOneCapability.SPLIT, RgoOneCapability.FINE_TUNE, RgoOneCapability.RIT,
        RgoOneCapability.XIT, RgoOneCapability.AGC, RgoOneCapability.S_METER, RgoOneCapability.TX_METERS,
        RgoOneCapability.RF_GAIN, RgoOneCapability.TX_POWER, RgoOneCapability.PREAMP, RgoOneCapability.ATTENUATOR,
        RgoOneCapability.KEYER_SPEED, RgoOneCapability.MIC_GAIN,
        RgoOneCapability.TRANSMIT, RgoOneCapability.TUNE,
    )

    fun resolve(
        generation: RgoOneGeneration,
        generationConfirmed: Boolean,
        firmware: RgoOneFirmwareVersion?,
        modules: RgoOneModuleSnapshot,
    ): Map<RgoOneCapability, RgoOneCapabilityState> {
        val result = RgoOneCapability.entries.associateWith { RgoOneCapabilityState.UNSUPPORTED_GENERATION }.toMutableMap()
        if (generation != RgoOneGeneration.V6 || !generationConfirmed) {
            listOf(RgoOneCapability.VFO_A, RgoOneCapability.VFO_B, RgoOneCapability.MODE).forEach {
                result[it] = RgoOneCapabilityState.SUPPORTED_UNKNOWN
            }
            return result
        }
        v6Core.forEach { result[it] = RgoOneCapabilityState.SUPPORTED_PRESENT }
        result[RgoOneCapability.FILTER_BANDWIDTH] = RgoOneCapabilityState.SUPPORTED_UNKNOWN
        result[RgoOneCapability.FIRMWARE_IDENTITY] = RgoOneCapabilityState.SUPPORTED_PRESENT
        val memorySupported = firmware != null && firmware >= RgoOneFirmwareVersion(1, 8, "0108")
        result[RgoOneCapability.MEMORY_READ] = if (memorySupported) RgoOneCapabilityState.SUPPORTED_PRESENT else RgoOneCapabilityState.SUPPORTED_UNKNOWN
        result[RgoOneCapability.MEMORY_WRITE] = if (memorySupported) RgoOneCapabilityState.SUPPORTED_PRESENT else RgoOneCapabilityState.SUPPORTED_UNKNOWN
        result[RgoOneCapability.ANTENNA_TUNER] = modules[RgoOneModule.ATU].state
        result[RgoOneCapability.NOISE_BLANKER] = modules[RgoOneModule.NOISE_BLANKER].state
        result[RgoOneCapability.AUDIO_DSP] = modules[RgoOneModule.AUDIO_DSP].state
        result[RgoOneCapability.SPEECH_PROCESSOR] = modules[RgoOneModule.SPEECH_PROCESSOR].state
        result[RgoOneCapability.USB_AUDIO] = modules[RgoOneModule.USB_AUDIO].state
        return result
    }

    fun initialModules(settings: RgoOneSettingsDocument, usbEvidence: RgoOneUsbIdentityEvidence?): RgoOneModuleSnapshot {
        val states = RgoOneModule.entries.associateWith { module ->
            when (settings.manualModules[module]) {
                true -> RgoOneModuleState(RgoOneCapabilityState.SUPPORTED_PRESENT, RgoOneModuleEvidence.OPERATOR_CONFIRMED)
                false -> RgoOneModuleState(RgoOneCapabilityState.SUPPORTED_ABSENT, RgoOneModuleEvidence.OPERATOR_CONFIRMED)
                null -> RgoOneModuleState()
            }
        }.toMutableMap()
        val audio = usbEvidence?.audioProfile
        if (audio != null) states[RgoOneModule.USB_AUDIO] = RgoOneModuleState(RgoOneCapabilityState.SUPPORTED_PRESENT, RgoOneModuleEvidence.USB_DESCRIPTOR)
        return RgoOneModuleSnapshot(states, audio)
    }
}

object RgoOneSnapshotReducer {
    fun apply(snapshot: RgoOneRadioSnapshot, response: RgoOneProtocolResponse, now: Long): RgoOneRadioSnapshot {
        var next = when (response) {
            is RgoOneProtocolResponse.Frequency -> if (response.command == "FA") snapshot.copy(vfoAHz = response.frequencyHz) else snapshot.copy(vfoBHz = response.frequencyHz)
            is RgoOneProtocolResponse.Selection -> when (response.command) {
                "FR" -> snapshot.copy(rxVfo = RgoOneVfo.entries.getOrNull(response.value))
                "FT" -> snapshot.copy(txVfo = RgoOneVfo.entries.getOrNull(response.value))
                "MD" -> snapshot.copy(mode = RgoOneMode.entries.firstOrNull { it.wireValue == response.value })
                "FS" -> snapshot.copy(fineTune = response.value == 1)
                "RT" -> snapshot.copy(ritEnabled = response.value == 1)
                "XT" -> snapshot.copy(xitEnabled = response.value == 1)
                else -> snapshot
            }
            is RgoOneProtocolResponse.Numeric -> when (response.command) {
                "GT" -> snapshot.copy(agc = when (response.value) { 0 -> RgoOneAgc.OFF; 1 -> RgoOneAgc.FAST; 2 -> RgoOneAgc.SLOW; else -> null })
                "KS" -> snapshot.copy(keyerSpeedWpm = response.value)
                "MG" -> snapshot.copy(micGain = response.value)
                "PC" -> snapshot.copy(txPowerWatts = response.value)
                "RG" -> snapshot.copy(rfGain = response.value)
                else -> snapshot
            }
            is RgoOneProtocolResponse.Toggle -> when (response.command) {
                "NB" -> snapshot.copy(noiseBlanker = response.enabled, modules = markPresentWhenTrue(snapshot.modules, RgoOneModule.NOISE_BLANKER, response.enabled))
                "PA" -> snapshot.copy(preamp = response.enabled)
                "RA" -> snapshot.copy(attenuator = response.enabled)
                else -> snapshot
            }
            is RgoOneProtocolResponse.Firmware -> snapshot.copy(firmware = response.version)
            is RgoOneProtocolResponse.ModelId -> snapshot.copy(modelId = response.value, generation = if (response.value == "006") RgoOneGeneration.V6 else RgoOneGeneration.UNKNOWN,
                generationConfirmed = response.value == "006")
            is RgoOneProtocolResponse.Meter -> if (response.kind == null) snapshot.copy(sMeter = response.value)
                else snapshot.copy(meters = snapshot.meters + (response.kind to response.value))
            is RgoOneProtocolResponse.AntennaTuner -> snapshot.copy(modules = markPresentWhenTrue(snapshot.modules, RgoOneModule.ATU, response.enabled || response.tuning))
            is RgoOneProtocolResponse.ExtendedMenu -> if (response.menu == 42)
                snapshot.copy(modules = markPresentWhenTrue(snapshot.modules, RgoOneModule.AUDIO_DSP, response.value != 0)) else snapshot
            is RgoOneProtocolResponse.SerialDigest, is RgoOneProtocolResponse.MemoryChannel, is RgoOneProtocolResponse.Memory,
            is RgoOneProtocolResponse.IfStatus, is RgoOneProtocolResponse.Playback, is RgoOneProtocolResponse.Raw -> snapshot
            is RgoOneProtocolResponse.Malformed -> snapshot.copy(connectionState = RgoOneConnectionState.DEGRADED, status = response.reason)
        }
        next = next.copy(stale = false, lastUpdatedEpochMillis = now)
        return next.copy(capabilities = RgoOneCapabilityResolver.resolve(next.generation, next.generationConfirmed, next.firmware, next.modules))
    }

    private fun markPresentWhenTrue(modules: RgoOneModuleSnapshot, module: RgoOneModule, present: Boolean): RgoOneModuleSnapshot {
        if (!present || modules[module].evidence == RgoOneModuleEvidence.OPERATOR_CONFIRMED) return modules
        return modules.copy(states = modules.states + (module to RgoOneModuleState(RgoOneCapabilityState.SUPPORTED_PRESENT, RgoOneModuleEvidence.OFFICIAL_RESPONSE)))
    }
}

object RgoOneCommandPolicy {
    fun permits(action: RgoOneAction, snapshot: RgoOneRadioSnapshot, settings: RgoOneSettingsDocument): Boolean {
        if (!snapshot.connected || snapshot.generation != RgoOneGeneration.V6 || !snapshot.generationConfirmed) return false
        if (action.actionClass == RgoOneActionClass.READ_ONLY) return true
        if (!settings.writesConfirmed) return false
        if (action.actionClass == RgoOneActionClass.MEMORY_WRITE && !settings.memoryWriteEnabled) return false
        return true
    }
}
