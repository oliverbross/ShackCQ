package app.shackcq.mobile.n1mm

import java.net.InetAddress

object N1mmDiscoveryCodec {
    const val MAX_DATAGRAM = 1024
    fun encode(value: N1mmDiscovery): ByteArray {
        val fields = listOf(value.station, value.advertisedIp, value.tcpPort.toString(), value.version, value.operatorCall, value.vpnName)
        require(fields.none { '%' in it || '~' in it }); require(value.tcpPort in 1..65535)
        return (fields.joinToString("%") + "%").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_DATAGRAM) }
    }
    fun decode(payload: ByteArray): N1mmDiscovery {
        require(payload.size in 1..MAX_DATAGRAM); val text = payload.toString(Charsets.UTF_8)
        require(text.endsWith('%')) { "Discovery advertisement must retain trailing percent" }
        val fields = text.dropLast(1).split('%'); require(fields.size == 6)
        val port = fields[2].toIntOrNull() ?: error("Invalid discovery port"); require(port in 1..65535)
        require(fields[0].isNotBlank() && fields[3].isNotBlank()); InetAddress.getByName(fields[1])
        return N1mmDiscovery(fields[0], fields[1], port, fields[3], fields[4], fields[5])
    }
    fun addressDiscrepancy(value: N1mmDiscovery, packetSource: InetAddress): Boolean = value.advertisedIp != packetSource.hostAddress
}
