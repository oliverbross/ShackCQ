package app.shackcq.mobile.n1mm

import java.security.MessageDigest
import java.util.ArrayDeque

data class N1mmDiagnosticEvent(val at: Long, val command: String, val category: String, val safeReason: String, val peerHash: String)

class N1mmDiagnostics(private val maximumEvents: Int = 500, private val clock: () -> Long = { System.currentTimeMillis()/1_000 }) {
    private val events = ArrayDeque<N1mmDiagnosticEvent>()
    private val counters = linkedMapOf<String, Long>()
    @Synchronized fun record(command: String, category: String, reason: String, peerStation: String = "") {
        val key = "$category:${command.uppercase()}"; counters[key] = (counters[key] ?: 0) + 1
        events.addLast(N1mmDiagnosticEvent(clock(), command.take(48), category.take(48), reason.take(160), hash(peerStation)))
        while (events.size > maximumEvents) events.removeFirst()
    }
    @Synchronized fun snapshot() = events.toList()
    @Synchronized fun counterSnapshot() = counters.toMap()
    private fun hash(value: String): String = if (value.isBlank()) "" else MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
