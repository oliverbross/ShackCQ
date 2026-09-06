package app.shackcq.mobile.radio.hamlib

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HamlibNativeRegistryInstrumentedTest {
    private val registry by lazy { HamlibModelRegistry.parse(NativeHamlib.libraryInfo(), NativeHamlib.models()) }

    @Test fun pinnedLibraryLoadsFromSingleNativeLibrary() {
        assertEquals("4.7.2", registry.library.version)
        assertEquals(37, registry.library.backendCount)
    }

    @Test fun dummyBackendIsRegistered() {
        assertTrue(registry.models.any { it.manufacturer.contains("Hamlib", ignoreCase = true) && it.model.contains("Dummy", ignoreCase = true) })
    }

    @Test fun qmxModelIsRegistered() {
        assertTrue(registry.models.any { it.model.contains("QMX", ignoreCase = true) })
    }

    @Test fun noDedicatedRgoOneModelIsInvented() {
        assertFalse(registry.models.any { it.model.matches(Regex("(?i).*\\bRGO(?:\\s+|-)ONE\\b.*")) })
    }
}
