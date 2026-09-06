package app.shackcq.mobile.contest

import app.shackcq.mobile.Qso
import app.shackcq.mobile.QsoDatabase

data class ContestQsoPage(val rows: List<Qso>, val nextLinkedAt: Long?, val nextQsoId: String?)

class ContestCanonicalQsoReader(private val sessionStore: ContestSessionStore, private val canonicalDatabase: QsoDatabase) {
    fun page(sessionId: ContestSessionId, afterLinkedAt: Long = Long.MIN_VALUE, afterQsoId: String = "", limit: Int = 250): ContestQsoPage {
        val links = sessionStore.linkedQsoPage(sessionId,afterLinkedAt,afterQsoId,limit)
        val rows = links.mapNotNull { canonicalDatabase.qso(it.qsoId) }
        val last = links.lastOrNull()
        return ContestQsoPage(rows,last?.linkedAt,last?.qsoId)
    }
}
