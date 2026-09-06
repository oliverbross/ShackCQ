package app.shackcq.mobile.contest

import java.util.UUID

interface ContestSerialStore {
    fun reservations(sessionId: ContestSessionId): List<ContestSerialReservation>
    fun put(reservation: ContestSerialReservation)
}

class ContestSerialAuthority(private val store: ContestSerialStore, private val clock: () -> Long = { System.currentTimeMillis() / 1_000 }) {
    @Synchronized fun reserve(session: ContestSession, owner: String): ContestSerialReservation {
        val existing = store.reservations(session.id)
        val unavailable = existing.filter { it.state == ContestSerialState.RESERVED || it.state == ContestSerialState.COMMITTED }.map { it.serial }.toSet()
        val serial = generateSequence(session.initialSerial) { it + 1 }.first { it !in unavailable }
        return ContestSerialReservation(UUID.randomUUID().toString(), session.id, serial, owner, clock(), ContestSerialState.RESERVED).also(store::put)
    }

    @Synchronized fun commit(id: String, sessionId: ContestSessionId, qsoId: String): ContestSerialReservation {
        val current = requireNotNull(store.reservations(sessionId).find { it.id == id }) { "Unknown serial reservation" }
        require(current.state == ContestSerialState.RESERVED) { "Serial reservation is not open" }
        require(store.reservations(sessionId).none { it.id != id && it.serial == current.serial && it.state == ContestSerialState.COMMITTED }) { "Committed serial conflict" }
        return current.copy(state = ContestSerialState.COMMITTED, qsoId = qsoId).also(store::put)
    }

    @Synchronized fun release(id: String, sessionId: ContestSessionId): ContestSerialReservation {
        val current = requireNotNull(store.reservations(sessionId).find { it.id == id }) { "Unknown serial reservation" }
        require(current.state == ContestSerialState.RESERVED) { "Only an open reservation can be released" }
        return current.copy(state = ContestSerialState.RELEASED).also(store::put)
    }

    @Synchronized fun recoverAbandoned(sessionId: ContestSessionId, olderThanEpochSeconds: Long): Int {
        val abandoned = store.reservations(sessionId).filter { it.state == ContestSerialState.RESERVED && it.reservedAt < olderThanEpochSeconds }
        abandoned.forEach { store.put(it.copy(state = ContestSerialState.RELEASED)) }
        return abandoned.size
    }
}

class InMemoryContestSerialStore : ContestSerialStore {
    private val rows = linkedMapOf<String, ContestSerialReservation>()
    override fun reservations(sessionId: ContestSessionId) = synchronized(rows) { rows.values.filter { it.sessionId == sessionId } }
    override fun put(reservation: ContestSerialReservation) { synchronized(rows) { rows[reservation.id] = reservation } }
}
