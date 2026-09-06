package app.shackcq.mobile.keyer

import org.junit.Assert.*
import org.junit.Test

class KeyerQueueAndHotkeyTest {
    private var clock = 1_000L
    private fun context(generation: Long = 1, foreground: Boolean = true, radio: String = "r1", profile: String = "general-cw") =
        KeyerContextSnapshot(generation, 1, radio, true, foreground, KeyerMode.CW, KeyerProfileId(profile))
    private fun action(id: String) = KeyerAction.SendMessage(id)

    @Test fun queueAllowsOneActiveAndOnePending() {
        val queue = KeyerQueueController({ clock })
        assertEquals(KeyerDispatchResult.Accepted(false), queue.submit(action("a"), "A", context()))
        assertEquals(KeyerDispatchResult.Accepted(true), queue.submit(action("b"), "B", context()))
        assertEquals(1, queue.snapshot().pendingCount)
    }

    @Test fun thirdTransmitIsRejected() {
        val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context()); queue.submit(action("b"), "B", context())
        assertEquals(KeyerFailureReason.QueueFull, (queue.submit(action("c"), "C", context()) as KeyerDispatchResult.Rejected).reason)
    }

    @Test fun sameActiveMessageIsRejected() {
        val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context())
        assertEquals(KeyerFailureReason.AlreadyActive, (queue.submit(action("a"), "A", context()) as KeyerDispatchResult.Rejected).reason)
    }

    @Test fun pendingExpiresWithoutBacklog() {
        val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context()); queue.submit(action("b"), "B", context())
        clock += 5_001
        assertNull(queue.snapshot().pending)
    }

    @Test fun generationChangeInvalidatesActiveAndPending() {
        val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context()); queue.submit(action("b"), "B", context())
        assertTrue(queue.invalidate(context(generation = 2)))
        assertNull(queue.snapshot().active); assertNull(queue.snapshot().pending)
    }

    @Test fun radioProfileAndForegroundIdentityChangesInvalidate() {
        listOf(context(radio = "r2"), context(profile = "other"), context(foreground = false)).forEach { changed ->
            val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context()); assertTrue(queue.invalidate(changed))
        }
    }

    @Test fun stopBypassesQueueAndIsIdempotent() {
        val queue = KeyerQueueController({ clock }); queue.submit(action("a"), "A", context()); queue.submit(KeyerAction.Stop, "Stop", context()); queue.stop(KeyerStopReason.Operator)
        assertEquals(KeyerQueueState.IDLE, queue.snapshot().state)
    }

    @Test fun controllerRecreationRestoresNoQueueState() {
        val first = KeyerQueueController({ clock }); first.submit(action("a"), "A", context())
        assertEquals(KeyerQueueState.IDLE, KeyerQueueController({ clock }).snapshot().state)
    }

    @Test fun functionAndModifierChordsRemainDistinct() {
        assertNotEquals(KeyChord(1), KeyChord(1, shift = true)); assertNotEquals(KeyChord(1, ctrl = true), KeyChord(1, alt = true))
        assertEquals("Ctrl+Shift+F12", KeyChord(12, shift = true, ctrl = true).label)
    }

    @Test fun duplicateBindingConflictIsDetected() {
        val first = KeyerBinding(KeyChord(1), action("a")); val second = KeyerBinding(KeyChord(1), action("b"))
        assertEquals(first, KeyerHotkeyDispatcher.conflict(listOf(first), second))
    }

    @Test fun unboundDisabledRepeatAndTextFocusAreNotConsumed() {
        val binding = KeyerBinding(KeyChord(1), action("a"))
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(chord = KeyChord(2)), true, listOf(binding), false).consumed)
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(chord = KeyChord(1)), false, listOf(binding), false).consumed)
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(chord = KeyChord(1), initialDown = false), true, listOf(binding), false).consumed)
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(chord = KeyChord(1), textInputFocused = true), true, listOf(binding), false).consumed)
    }

    @Test fun escapeStopsOnlyWhenActivityExists() {
        assertTrue(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(escape = true), false, emptyList(), true).consumed)
        assertFalse(KeyerHotkeyDispatcher.dispatch(KeyerKeyEvent(escape = true), true, emptyList(), false).consumed)
    }

    @Test fun voicePlanBoundsAreEnforced() {
        assertEquals(12, VoiceMacroPlan(List(12) { it % 6 }).slotIds.size)
        assertThrows(IllegalArgumentException::class.java) { VoiceMacroPlan(List(13) { it % 6 }) }
        assertThrows(IllegalArgumentException::class.java) { VoiceMacroPlan(listOf(6)) }
    }

    @Test fun repeatRequiresExplicitStartAndHonoursLimits() {
        val repeat = RepeatCqController { clock }; val limits = RepeatCqLimits(2, 2, 1)
        assertFalse(repeat.state.active); assertFalse(repeat.start("", limits)); assertTrue(repeat.start("cq", limits))
        clock += 2_000; assertTrue(repeat.due(limits, true)); clock += 2_000; assertTrue(repeat.due(limits, true)); assertFalse(repeat.due(limits, true))
    }

    @Test fun repeatDoesNotBacklogWhenKeyerBusy() {
        val repeat = RepeatCqController { clock }; val limits = RepeatCqLimits(2, 3, 1); repeat.start("cq", limits)
        clock += 2_000; assertFalse(repeat.due(limits, false)); assertEquals(1, repeat.state.cycle)
    }
}
