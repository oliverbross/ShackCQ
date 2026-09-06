package app.shackcq.mobile.keyer

data class KeyerKeyEvent(
    val chord: KeyChord? = null,
    val escape: Boolean = false,
    val initialDown: Boolean = true,
    val textInputFocused: Boolean = false,
    val foreground: Boolean = true,
    val modalOpen: Boolean = false,
)

data class HotkeyDecision(val consumed: Boolean, val action: KeyerAction? = null, val reason: KeyerFailureReason? = null)

object KeyerHotkeyDispatcher {
    fun dispatch(event: KeyerKeyEvent, enabled: Boolean, bindings: List<KeyerBinding>, keyerActive: Boolean): HotkeyDecision {
        if (event.escape && event.initialDown && keyerActive) return HotkeyDecision(true, KeyerAction.Stop)
        if (!enabled) return HotkeyDecision(false, reason = KeyerFailureReason.HotkeysDisabled)
        if (!event.initialDown) return HotkeyDecision(false)
        if (!event.foreground) return HotkeyDecision(false, reason = KeyerFailureReason.AppNotForeground)
        if (event.textInputFocused || event.modalOpen) return HotkeyDecision(false, reason = KeyerFailureReason.TextInputFocused)
        val chord = event.chord ?: return HotkeyDecision(false)
        val matches = bindings.filter { it.chord == chord }
        return when (matches.size) {
            0 -> HotkeyDecision(false, reason = KeyerFailureReason.NoBinding)
            1 -> HotkeyDecision(true, matches.single().action)
            else -> HotkeyDecision(false, reason = KeyerFailureReason.BindingConflict)
        }
    }

    fun conflict(bindings: List<KeyerBinding>, candidate: KeyerBinding): KeyerBinding? =
        bindings.firstOrNull { it.chord == candidate.chord && it.action != candidate.action }
}
