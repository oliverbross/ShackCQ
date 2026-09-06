package app.shackcq.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformContractFixtureTest {
    private fun fixture(name: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("platform/$name")) {
            "Shared platform fixture not found: $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    @Test fun schema16FixturePreservesSemanticBoundaryAndUnknownAdif() {
        val text = fixture("schema16_qso_golden.json")
        assertTrue(text.contains("\"schemaVersion\": 16"))
        assertTrue(text.contains("\"APP_SHACKCQ_FUTURE\": \"preserved\""))
        assertTrue(text.contains("\"semanticInteroperabilityOnly\": true"))
        assertTrue(text.contains("\"androidWindowsDatabaseBytesInterchangeable\": false"))
    }

    @Test fun portableConfigurationFixtureExportsNoAuthorityOrCredentials() {
        val text = fixture("configuration_wavelog_golden.json")
        assertTrue(text.contains("\"credentialsIncluded\": false"))
        assertTrue(text.contains("\"restoreAuthority\": false"))
        assertTrue(text.contains("\"ambiguous-create\""))
        assertFalse(text.contains("wl2_"))
        assertTrue(text.contains("pendingCommands"))
    }
}
