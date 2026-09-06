package app.shackcq.mobile.keyer

class KeyerQueueController(
    private val now: () -> Long = System::currentTimeMillis,
    private val pendingLifetimeMillis: Long = 5_000,
) {
    private var value = KeyerQueueSnapshot()

    fun snapshot(): KeyerQueueSnapshot = expire(value)

    fun submit(action: KeyerAction, label: String, context: KeyerContextSnapshot): KeyerDispatchResult {
        if (action is KeyerAction.Stop) { stop(KeyerStopReason.Operator); return KeyerDispatchResult.Accepted(false) }
        value = expire(value)
        val active = value.active
        if (active?.messageId != null && active.messageId == action.messageId) {
            return KeyerDispatchResult.Rejected(KeyerFailureReason.AlreadyActive, "${active.label} is already active")
        }
        val item = KeyerQueueItem(action, action.messageId, label.take(40), context, now(), now() + pendingLifetimeMillis)
        if (value.active == null) {
            value = KeyerQueueSnapshot(KeyerQueueState.ACTIVE, active = item, reason = "Active · ${item.label}")
            return KeyerDispatchResult.Accepted(false)
        }
        if (value.pending == null) {
            value = value.copy(state = KeyerQueueState.PENDING, pending = item, reason = "Pending · ${item.label}")
            return KeyerDispatchResult.Accepted(true)
        }
        return KeyerDispatchResult.Rejected(KeyerFailureReason.QueueFull, "One transmit action is already pending")
    }

    fun complete(activeContext: KeyerContextSnapshot): KeyerQueueItem? {
        value = expire(value)
        val pending = value.pending
        value = if (pending != null && sameIdentity(pending.context, activeContext)) {
            KeyerQueueSnapshot(KeyerQueueState.ACTIVE, active = pending, reason = "Active · ${pending.label}")
        } else KeyerQueueSnapshot(KeyerQueueState.COMPLETED, reason = if (pending == null) "Completed" else "Pending action rejected: context changed")
        return value.active
    }

    fun fail(reason: String, rxVerified: Boolean) {
        value = KeyerQueueSnapshot(KeyerQueueState.FAILED, reason = if (rxVerified) reason else "$reason · RX state unverified")
    }

    fun invalidate(current: KeyerContextSnapshot, reason: KeyerStopReason = KeyerStopReason.ContextChanged): Boolean {
        val invalid = value.active?.let { !sameIdentity(it.context, current) } == true || value.pending?.let { !sameIdentity(it.context, current) } == true
        if (invalid) stop(reason)
        return invalid
    }

    fun stop(reason: KeyerStopReason) {
        value = KeyerQueueSnapshot(KeyerQueueState.IDLE, reason = if (reason == KeyerStopReason.Operator) "Stopped by operator" else "Stopped · ${reason.name}")
    }

    private fun expire(snapshot: KeyerQueueSnapshot): KeyerQueueSnapshot {
        val pending = snapshot.pending ?: return snapshot
        return if (now() > pending.expiresAtMillis) snapshot.copy(state = if (snapshot.active == null) KeyerQueueState.FAILED else KeyerQueueState.ACTIVE,
            pending = null, reason = "Pending action expired") else snapshot
    }

    private fun sameIdentity(left: KeyerContextSnapshot, right: KeyerContextSnapshot): Boolean =
        left.operatingGeneration == right.operatingGeneration && left.foregroundGeneration == right.foregroundGeneration &&
            left.radioIdentity == right.radioIdentity && left.mode == right.mode && left.profileId == right.profileId &&
            right.foreground && right.connected
}
