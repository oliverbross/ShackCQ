package app.shackcq.mobile.n1mm

class N1mmPeerRegistry(private val maximumPeers: Int = 32) {
    private val peers = linkedMapOf<String, N1mmPeerSnapshot>()
    @Synchronized fun observe(discovery: N1mmDiscovery, packetAddress: String, now: Long, trusted: Boolean = false): N1mmPeerSnapshot {
        val address = if (discovery.advertisedIp == packetAddress) discovery.advertisedIp else packetAddress
        val peer = N1mmPeerSnapshot(discovery.station, address, discovery.version, discovery.operatorCall, now, trusted = trusted)
        if (discovery.station !in peers && peers.size >= maximumPeers) peers.entries.minByOrNull { it.value.lastSeen }?.key?.let(peers::remove)
        peers[discovery.station] = peer; return peer
    }
    @Synchronized fun updateContest(station: String, contest: String) { peers[station]?.let { peers[station] = it.copy(contestName=contest) } }
    @Synchronized fun snapshots(): List<N1mmPeerSnapshot> = peers.values.sortedByDescending { it.lastSeen }
    @Synchronized fun removeExpired(before: Long): Int { val keys=peers.filterValues { it.lastSeen < before }.keys; keys.forEach(peers::remove); return keys.size }
}
