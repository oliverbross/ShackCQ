package app.shackcq.mobile.keyer

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shackcq.mobile.ConfigurationRecovery
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyerPrefs get() = context.getSharedPreferences("shackcq-keyer", Context.MODE_PRIVATE)

    @After fun clear() { keyerPrefs.edit().clear().commit() }

    @Test fun sixCwAndVoiceSlotsMigrateWithoutFileOrContentCopying() {
        val cwLabels = List(6) { "C$it" }; val cwTexts = List(6) { "TEXT $it" }; val voiceLabels = List(6) { "V$it" }
        val store = KeyerProfileStore(context, cwLabels, cwTexts, voiceLabels)
        assertEquals(cwLabels, store.profiles.first { it.id.value == "general-cw" }.messages.map { it.label })
        assertEquals(cwTexts, store.profiles.first { it.id.value == "general-cw" }.messages.map { it.template })
        assertEquals(voiceLabels, store.profiles.first { it.id.value == "general-voice" }.messages.map { it.label })
        assertEquals((0..5).map(::listOf), store.profiles.first { it.id.value == "general-voice" }.messages.map { it.voicePlan!!.slotIds })
        assertFalse(store.exportText().contains(".wav")); assertFalse(store.exportText().contains("voice-macros"))
    }

    @Test fun migrationIsIdempotentAndMalformedCurrentUsesLastGood() {
        val first = KeyerProfileStore(context, List(6) { "C$it" }, List(6) { "T$it" }, List(6) { "V$it" })
        val expected = first.exportText()
        keyerPrefs.edit().putString("keyer_document", "{bad").commit()
        val restored = KeyerProfileStore(context, emptyList(), emptyList(), emptyList())
        assertEquals(expected, restored.exportText())
        assertTrue(restored.status.contains("last-good"))
    }

    @Test fun restoredSettingsContainNoRuntimeTransmitAuthority() {
        val store = KeyerProfileStore(context, emptyList(), emptyList(), emptyList())
        store.updateHotkeysEnabled(true)
        val bundle = ConfigurationRecovery(context).export()
        val keyer = JSONObject(bundle).getJSONObject("sections").getJSONObject("keyer")
        assertTrue(keyer.has("keyer_document")); assertFalse(bundle.contains("queue", true)); assertFalse(bundle.contains("armed", true))
    }

    @Test fun androidFunctionKeysAndRepeatsNormalizeSafely() {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals(KeyChord(12, shift = true, ctrl = true), androidFunctionChord(event))
        val repeated = KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(KeyChord(1), initialDown = false), true,
            listOf(KeyerBinding(KeyChord(1), KeyerAction.SendMessage("cq"))), false)
        assertFalse(repeated.consumed)
    }

    @Test fun editableFocusBlocksWhileNonEditableSurfaceDispatchesAndEscapeStops() {
        val binding = KeyerBinding(KeyChord(1), KeyerAction.SendMessage("cq"))
        assertTrue(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(KeyChord(1)), true, listOf(binding), false).consumed)
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(KeyChord(1), textInputFocused = true), true, listOf(binding), false).consumed)
        assertEquals(KeyerAction.Stop, KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(escape = true), false, emptyList(), true).action)
    }

    @Test fun generalFallbackIsExplicitAndProfileEditsPersist() {
        val store = KeyerProfileStore(context, List(6) { "C$it" }, List(6) { "T$it" }, List(6) { "V$it" })
        val general = store.profiles.first { it.id.value == "general-cw" }
        assertTrue(store.replaceProfile(general.copy(bindings = listOf(KeyerBinding(KeyChord(1), KeyerAction.SendMessage("cw-0"))))))
        val portable = store.profiles.first { it.id.value == "portable-run-cw" }
        store.activate(portable.id)
        assertTrue(store.bindingsForActive().isEmpty())
        store.updateFallbackToGeneral(true)
        assertEquals(KeyChord(1), store.bindingsForActive().single().chord)
        assertEquals("cw-0", store.resolveMessage("cw-0")?.id)
        assertTrue(store.renameProfile(portable.id, "Field Run"))
        assertTrue(store.moveProfile(portable.id, -1))
        val restored = KeyerProfileStore(context, emptyList(), emptyList(), emptyList())
        assertEquals("Field Run", restored.profiles.first { it.id == portable.id }.name)
    }

    @Test fun stripStateAndAccessibilityLabelExposeActiveAndPending() {
        val context = KeyerContextSnapshot(1, 1, "radio", true, true, KeyerMode.CW,
            KeyerProfileId("general-cw"), true, KeyerOperatingRole.GENERAL)
        val active = KeyerQueueItem(KeyerAction.SendMessage("cq"), "cq", "CQ", context, 1, 5_001)
        val pending = KeyerQueueItem(KeyerAction.SendMessage("tu"), "tu", "TU", context, 2, 5_002)
        val queue = KeyerQueueSnapshot(KeyerQueueState.ACTIVE, active, pending)
        val binding = KeyerBinding(KeyChord(1), KeyerAction.SendMessage("cq"))
        val message = KeyerMessageTemplate("cq", "Call CQ", KeyerMode.CW, "CQ")
        assertEquals("ACTIVE", keyerStripState("cq", queue, true))
        assertEquals("PENDING", keyerStripState("tu", queue, true))
        val profile = KeyerProfile(KeyerProfileId("general-cw"), "General CW", KeyerOperatingRole.GENERAL,
            KeyerMode.CW, listOf(message), listOf(binding))
        val description = keyerStripContentDescription(profile, binding, message, queue, true)
        assertTrue(description.contains("F1, Call CQ, CW, General CW, GENERAL, ACTIVE"))
        assertEquals(1, queue.pendingCount)
    }
}
