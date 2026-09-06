// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DebugRemoteLabSnapshot(
    val banner: String = "DEMO · NO RADIO",
    val station: String = "Fake stationd · 2 radios · fake radio A active",
    val clients: String = "observer-1 · operator-1 · admin-1",
    val media: String = "fake Panadapter/waterfall · RX audio · optional I/Q · spots · Digi",
    val leases: String = "writer NONE · TX BLOCKED · rotator NONE",
    val fault: String = "none",
)

/** Deterministic, process-local instrumentation fixture. It never opens a socket or hardware owner. */
class DebugRemoteLabV6 {
    var snapshot by mutableStateOf(DebugRemoteLabSnapshot())
        private set

    fun seed() { snapshot = DebugRemoteLabSnapshot() }
    fun acquireWriter() { snapshot = snapshot.copy(leases = "writer HELD · TX BLOCKED · rotator NONE") }
    fun exerciseLeases() { snapshot = snapshot.copy(leases = "writer HELD · TX DEMO LEASE (NO PTT) · rotator HELD") }
    fun injectNetworkLoss() { snapshot = snapshot.copy(leases = "writer NONE · TX CLEARED · rotator NONE", fault = "network loss · Global Stop latched") }
    fun injectCertificateMismatch() { snapshot = snapshot.copy(leases = "writer NONE · TX BLOCKED · rotator NONE", fault = "certificate mismatch · connection rejected") }
    fun revokeOperator() { snapshot = snapshot.copy(clients = "observer-1 · admin-1 · operator-1 REVOKED", leases = "all leases cleared", fault = "revocation propagated") }
    fun globalStop() { snapshot = snapshot.copy(leases = "writer NONE · TX CLEARED · rotator NONE", fault = "Global Stop · RX-only streams retained") }
}
