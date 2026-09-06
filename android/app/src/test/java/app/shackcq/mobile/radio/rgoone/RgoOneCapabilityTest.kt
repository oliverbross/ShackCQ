package app.shackcq.mobile.radio.rgoone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgoOneCapabilityTest {
    @Test fun confirmedV6ExposesOnlyDocumentedCoreCapabilities() {
        val caps = RgoOneCapabilityResolver.resolve(RgoOneGeneration.V6, true, RgoOneFirmwareVersion.parse("0109"), RgoOneModuleSnapshot())
        assertEquals(RgoOneCapabilityState.SUPPORTED_PRESENT, caps[RgoOneCapability.VFO_A])
        assertEquals(RgoOneCapabilityState.SUPPORTED_UNKNOWN, caps[RgoOneCapability.FILTER_BANDWIDTH])
    }

    @Test fun legacyProfileRejectsV6OnlyCommandsAndAllWrites() {
        val snapshot = RgoOneRadioSnapshot(connected = true, generation = RgoOneGeneration.SERIES_5_5_PLUS, generationConfirmed = true)
        assertFalse(RgoOneCommandPolicy.permits(RgoOneAction.Read("FW"), snapshot, RgoOneSettingsDocument()))
        assertEquals(RgoOneCapabilityState.UNSUPPORTED_GENERATION,
            RgoOneCapabilityResolver.resolve(snapshot.generation, true, null, snapshot.modules)[RgoOneCapability.MEMORY_WRITE])
    }

    @Test fun unknownGenerationIsConservativeAndReadOnly() {
        val caps = RgoOneCapabilityResolver.resolve(RgoOneGeneration.UNKNOWN, false, null, RgoOneModuleSnapshot())
        assertEquals(RgoOneCapabilityState.SUPPORTED_UNKNOWN, caps[RgoOneCapability.VFO_A])
        assertEquals(RgoOneCapabilityState.UNSUPPORTED_GENERATION, caps[RgoOneCapability.TRANSMIT])
    }

    @Test fun modulePresentAbsentAndUnknownRequireExplicitEvidence() {
        val settings = RgoOneSettingsDocument(manualModules = mapOf(RgoOneModule.ATU to true, RgoOneModule.NOISE_BLANKER to false))
        val modules = RgoOneCapabilityResolver.initialModules(settings, null)
        assertEquals(RgoOneCapabilityState.SUPPORTED_PRESENT, modules[RgoOneModule.ATU].state)
        assertEquals(RgoOneCapabilityState.SUPPORTED_ABSENT, modules[RgoOneModule.NOISE_BLANKER].state)
        assertEquals(RgoOneCapabilityState.SUPPORTED_UNKNOWN, modules[RgoOneModule.AUDIO_DSP].state)
    }

    @Test fun filterListIsNeverHardCodedAcrossFirmware() {
        val snapshot = RgoOneRadioSnapshot(generation = RgoOneGeneration.V6, generationConfirmed = true)
        val caps = RgoOneCapabilityResolver.resolve(snapshot.generation, true, RgoOneFirmwareVersion.parse("0109"), snapshot.modules)
        assertEquals(RgoOneCapabilityState.SUPPORTED_UNKNOWN, caps[RgoOneCapability.FILTER_BANDWIDTH])
        assertNull(snapshot.filterBandwidthHz)
    }

    @Test fun usbAudioDescriptorIsTypedAndNeverClaimedAsIq() {
        val profile = RgoOneUsbAudioProfile(48_000, 2, RgoOneAudioDirection.BIDIRECTIONAL, "a".repeat(64))
        val modules = RgoOneCapabilityResolver.initialModules(RgoOneSettingsDocument(), RgoOneUsbIdentityEvidence(
            RgoOneGeneration.V6, true, profile))
        assertFalse(profile.isIqSource)
        assertEquals(RgoOneCapabilityState.SUPPORTED_PRESENT, modules[RgoOneModule.USB_AUDIO].state)
    }

    @Test fun memoryCommandsRequireFirmware108AndUse109Correction() {
        val old = RgoOneCapabilityResolver.resolve(RgoOneGeneration.V6, true, RgoOneFirmwareVersion.parse("0107"), RgoOneModuleSnapshot())
        val current = RgoOneCapabilityResolver.resolve(RgoOneGeneration.V6, true, RgoOneFirmwareVersion.parse("0109"), RgoOneModuleSnapshot())
        assertEquals(RgoOneCapabilityState.SUPPORTED_UNKNOWN, old[RgoOneCapability.MEMORY_READ])
        assertEquals(RgoOneCapabilityState.SUPPORTED_PRESENT, current[RgoOneCapability.MEMORY_WRITE])
        assertNull(RgoOneCommandBuilder.read("UN"))
    }
}
