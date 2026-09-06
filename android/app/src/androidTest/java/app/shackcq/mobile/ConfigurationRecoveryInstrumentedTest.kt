package app.shackcq.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigurationRecoveryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clear() {
        listOf("shackcq-app", "shackcq-digi", "wavelog", "shackcq-groupsio-credentials").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun exportExcludesCredentialsDataAndTransmitAuthority() {
        context.getSharedPreferences("shackcq-app", Context.MODE_PRIVATE).edit()
            .putString("station_call", "OM0RX").putBoolean("transmit_armed", true).commit()
        context.getSharedPreferences("shackcq-digi", Context.MODE_PRIVATE).edit()
            .putString("visible_modes", "FT8,RTTY").putBoolean("ptt_state", true).commit()
        context.getSharedPreferences("wavelog", Context.MODE_PRIVATE).edit()
            .putString("base_url", "https://example.invalid").putString("station_id", "7").putString("api_key", "secret").commit()
        context.getSharedPreferences("shackcq-groupsio-credentials", Context.MODE_PRIVATE).edit()
            .putString("api_key", "secret").commit()

        val text = ConfigurationRecovery(context).export()
        val payload = JSONObject(text).getJSONObject("sections").toString()
        assertTrue(payload.contains("OM0RX"))
        assertTrue(payload.contains("visible_modes"))
        assertTrue(payload.contains("station_id"))
        assertFalse(payload.contains("api_key"))
        assertFalse(payload.contains("transmit_armed"))
        assertFalse(payload.contains("ptt_state"))
        assertFalse(payload.contains("secret"))
    }

    @Test fun selectiveRestoreIsHashedAndAlwaysClearsUnsafeState() {
        val app = context.getSharedPreferences("shackcq-app", Context.MODE_PRIVATE)
        app.edit().putString("station_call", "OM0RX").commit()
        val recovery = ConfigurationRecovery(context)
        val text = recovery.export()
        app.edit().putString("station_call", "VK8OLD").putBoolean("transmit_armed", true).commit()

        val preview = recovery.preview(text)
        assertTrue(preview.sections.first { it.name == "app" }.changedKeys.contains("station_call"))
        recovery.restore(text, setOf("app"))
        assertEquals("OM0RX", app.getString("station_call", ""))
        assertFalse(app.contains("transmit_armed"))

        val tampered = JSONObject(text).apply {
            getJSONObject("sections").getJSONObject("app").getJSONObject("station_call").put("value", "BAD")
        }.toString()
        assertTrue(runCatching { recovery.preview(tampered) }.isFailure)
    }

    @Test fun legacyVersionTwoMigratesOnlySafeConfigurationAndNeverRestoresTx() {
        val legacy = JSONObject()
            .put("version", 2)
            .put("created_at", 1_700_000_000L)
            .put("preferences", JSONObject()
                .put("station_call", "OM0RX")
                .put("profile", "FIELD")
                .put("api_key", "must-not-migrate")
                .put("transmit_armed", true))
            .put("hamclock_layout", JSONObject().put("selected_profile", "portable"))
            .toString()
        val recovery = ConfigurationRecovery(context)
        val preview = recovery.preview(legacy)
        assertEquals(1, preview.schema)
        assertTrue(preview.sections.first { it.name == "app" }.changedKeys.contains("station_call"))

        recovery.restore(legacy, preview.selectedByDefault)
        val app = context.getSharedPreferences("shackcq-app", Context.MODE_PRIVATE)
        assertEquals("OM0RX", app.getString("station_call", ""))
        assertFalse(app.contains("api_key"))
        assertFalse(app.contains("transmit_armed"))
    }
}
