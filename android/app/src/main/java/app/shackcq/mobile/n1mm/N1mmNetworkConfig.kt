package app.shackcq.mobile.n1mm

data class N1mmNetworkConfig(
    val enabled: Boolean = false,
    val mode: N1mmMode = N1mmMode.OFF,
    val stationName: String,
    val stationNumber: Int = 0,
    val operatorCall: String,
    val contestName: String,
    val ruleVersion: String = "",
    val version: String = "ShackCQ-1",
    val bindAddress: String = "127.0.0.1",
    val interfaceName: String = "loopback",
    val discoveryPort: Int = 12070,
    val tcpPort: Int = 12070,
    val lanBroadcastOptIn: Boolean = false,
    val announceMaster: Boolean = false,
    val maximumPeers: Int = 32,
    val maximumLinks: Int = 32,
    val maximumFrameBytes: Int = 64 * 1024,
    val maximumStreamBufferBytes: Int = 256 * 1024,
    val maximumFramesPerMinutePerPeer: Int = 600,
    val reconnectMinMillis: Long = 1_000,
    val reconnectMaxMillis: Long = 60_000,
    val dedupeRetentionSeconds: Long = 3600,
    val resyncWindowMinutes: Int = 60,
    val retainedEvents: Int = 500,
) {
    init {
        require(stationName.isNotBlank() && '%' !in stationName); require(operatorCall.length <= 32); require(stationNumber in 0..99)
        require(interfaceName.isNotBlank())
        require(discoveryPort in 1..65535 && tcpPort in 1..65535); require(maximumPeers in 1..64 && maximumLinks in 1..64)
        require(maximumFrameBytes in 1024..64*1024 && maximumStreamBufferBytes in maximumFrameBytes..256*1024)
        require(!announceMaster || enabled && mode != N1mmMode.OFF)
    }
}
