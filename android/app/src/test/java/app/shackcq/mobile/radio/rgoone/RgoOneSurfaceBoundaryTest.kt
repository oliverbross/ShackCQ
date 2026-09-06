package app.shackcq.mobile.radio.rgoone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RgoOneSurfaceBoundaryTest {
    private fun snapshot() = RgoOneRadioSnapshot(
        connected = true, stale = false, generation = RgoOneGeneration.V6, generationConfirmed = true,
        vfoAHz = 14_074_000, vfoBHz = 7_074_000, rxVfo = RgoOneVfo.A, txVfo = RgoOneVfo.B, mode = RgoOneMode.DATA,
        capabilities = RgoOneCapability.entries.associateWith { RgoOneCapabilityState.SUPPORTED_PRESENT },
    )

    @Test fun compactSnapshotKeepsFrequencyModeAndActionsVisible() {
        val model = RgoOneSurfaceModel.from(snapshot(), 400)
        assertEquals(RgoOneSurfaceLayout.COMPACT, model.layout)
        assertEquals("014.074.000", model.primaryFrequency)
        assertTrue(model.statusLabels.contains("DATA"))
    }

    @Test fun surfaceHidesAbsentOptionalControls() {
        val state = snapshot().copy(capabilities = snapshot().capabilities +
            (RgoOneCapability.ANTENNA_TUNER to RgoOneCapabilityState.SUPPORTED_ABSENT) +
            (RgoOneCapability.AUDIO_DSP to RgoOneCapabilityState.SUPPORTED_UNKNOWN))
        val controls = RgoOneSurfaceModel.from(state, 700).controls
        assertFalse(RgoOneCapability.ANTENNA_TUNER in controls)
        assertFalse(RgoOneCapability.AUDIO_DSP in controls)
    }

    @Test fun displayHierarchyKeepsSecondaryVfoSmallerAndSeparate() {
        val model = RgoOneSurfaceModel.from(snapshot(), 1_000)
        assertEquals("014.074.000", model.primaryFrequency)
        assertEquals("007.074.000", model.secondaryFrequency)
        assertTrue(model.statusLabels.contains("SPLIT"))
    }

    @Test fun settingsRestoreDropsRuntimeWriteAuthority() {
        val restored = RgoOneSettingsDocument(writesConfirmed = true, memoryWriteEnabled = true,
            fastPollMillis = 1, visibleControls = setOf(" atu "), filterFavorites = listOf("", "B01")).safeRestore()
        assertFalse(restored.writesConfirmed)
        assertFalse(restored.memoryWriteEnabled)
        assertEquals(250, restored.fastPollMillis)
        assertFalse(restored.safeExport().keys.any { it.contains("ptt", true) || it.contains("tune", true) || it.contains("arm", true) })
    }

    @Test fun packageAuditAndWatcherFixturesEnforceOwnershipBoundary() {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null && !Files.isRegularFile(candidate.resolve("scripts/check_rgo_one_upstream.py"))) {
            candidate = candidate.parent
        }
        val repositoryRoot = requireNotNull(candidate) { "repository root not found from user.dir" }
        val packagePath = repositoryRoot.resolve("android/app/src/main/java/app/shackcq/mobile/radio/rgoone")
        assertTrue(Files.isDirectory(packagePath))
        val text = buildString {
            Files.walk(packagePath).use { paths ->
                paths.filter { path: Path -> path.toString().endsWith(".kt") }
                    .forEach { path: Path -> append(String(Files.readAllBytes(path), Charsets.UTF_8)) }
            }
        }
        listOf("UsbRadioTransport", "RadioBackend", "NativeCore", "MainActivity", "QsoDatabase", "Wavelog", "Digi", "Panadapter")
            .forEach { owner ->
                assertFalse("forbidden package owner: $owner", Regex("\\b${Regex.escape(owner)}\\b").containsMatchIn(text))
            }
        val script = repositoryRoot.resolve("scripts/check_rgo_one_upstream.py")
        val process = ProcessBuilder("python3", script.toString(), "--self-test").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        assertTrue(output.contains("PASS"))
    }
}
