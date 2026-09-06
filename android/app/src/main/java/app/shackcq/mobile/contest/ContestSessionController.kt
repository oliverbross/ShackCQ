package app.shackcq.mobile.contest

class ContestSessionController(private val store: ContestSessionStore? = null) {
    fun transition(session: ContestSession, target: ContestSessionState): ContestSession {
        val allowed = when (session.state) {
            ContestSessionState.DRAFT -> setOf(ContestSessionState.READY, ContestSessionState.CLOSED)
            ContestSessionState.READY -> setOf(ContestSessionState.RUNNING, ContestSessionState.CLOSED)
            ContestSessionState.RUNNING -> setOf(ContestSessionState.PAUSED, ContestSessionState.STOPPED)
            ContestSessionState.PAUSED -> setOf(ContestSessionState.RUNNING, ContestSessionState.STOPPED)
            ContestSessionState.STOPPED -> setOf(ContestSessionState.RUNNING, ContestSessionState.CLOSED)
            ContestSessionState.CLOSED -> emptySet()
        }
        require(target in allowed) { "Invalid contest session transition ${session.state} -> $target" }
        return session.copy(state = target).also { store?.saveSession(it) }
    }

    fun restored(session: ContestSession): ContestSession = session.copy(networkArmed = false, keyerArmed = false,
        state = if (session.state == ContestSessionState.RUNNING) ContestSessionState.PAUSED else session.state)

    fun changeRole(session: ContestSession, role: ContestOperatingRole) = session.copy(role = role).also { store?.saveSession(it) }
}
