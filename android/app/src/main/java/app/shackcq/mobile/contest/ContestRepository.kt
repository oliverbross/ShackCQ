package app.shackcq.mobile.contest

import app.shackcq.mobile.Qso
import app.shackcq.mobile.QsoDeleteIntent
import app.shackcq.mobile.QsoMutationCoordinator
import app.shackcq.mobile.QsoOrigin

interface ContestQsoMutationPort {
    fun create(qso: Qso, networkOrigin: Boolean = false): Boolean
    fun update(qso: Qso, networkOrigin: Boolean = false)
    fun delete(id: String, networkOrigin: Boolean = false)
}

class CoordinatorContestQsoMutationPort(private val coordinator: QsoMutationCoordinator) : ContestQsoMutationPort {
    override fun create(qso: Qso, networkOrigin: Boolean) = coordinator.save(qso, if (networkOrigin) QsoOrigin.REMOTE_SYNC else QsoOrigin.OPERATOR)
    override fun update(qso: Qso, networkOrigin: Boolean) = coordinator.update(qso, if (networkOrigin) QsoOrigin.REMOTE_SYNC else QsoOrigin.OPERATOR)
    override fun delete(id: String, networkOrigin: Boolean) = coordinator.delete(id,
        if (networkOrigin) QsoDeleteIntent.REMOTE_SYNC else QsoDeleteIntent.LOCAL_ONLY,
        if (networkOrigin) QsoOrigin.REMOTE_SYNC else QsoOrigin.OPERATOR)
}

data class ContestSaveResult(val accepted: Boolean, val qsoId: String, val serial: ContestSerialReservation?, val reason: String)

class ContestRepository(
    private val mutations: ContestQsoMutationPort,
    private val sessionStore: ContestSessionStore?,
    private val serialAuthority: ContestSerialAuthority,
    private val exchange: ContestExchangeEngine = ContestExchangeEngine(),
) {
    fun save(session: ContestSession, definition: ContestDefinition, draft: ContestQsoDraft, owner: String, networkOrigin: Boolean = false): ContestSaveResult {
        val issues = exchange.validate(definition, draft.received)
        if (issues.any { it.truth == ContestTruth.INVALID || it.truth == ContestTruth.INCOMPLETE }) return ContestSaveResult(false, draft.qsoId, null, issues.joinToString { it.reason })
        val reservation = if (definition.serialRequired) serialAuthority.reserve(session, owner) else null
        val mapped = ContestQsoMapper.toCanonical(session, definition, if (reservation == null) draft else draft.copy(sent = draft.sent + (ContestExchangeField.SERIAL to reservation.serial.toString())))
        val saved = runCatching { mutations.create(mapped, networkOrigin) }.getOrDefault(false)
        if (!saved) {
            reservation?.let { serialAuthority.release(it.id, session.id) }
            return ContestSaveResult(false, mapped.id, reservation, "Canonical QSO save failed; serial was not committed")
        }
        val committed = reservation?.let { serialAuthority.commit(it.id, session.id, mapped.id) }
        sessionStore?.linkQso(session.id, mapped.id, mapped.createdAt.toString())
        return ContestSaveResult(true, mapped.id, committed, "Saved through canonical QSO mutation authority")
    }
}
