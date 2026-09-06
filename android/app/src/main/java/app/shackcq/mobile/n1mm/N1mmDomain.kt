package app.shackcq.mobile.n1mm

enum class N1mmCommand {
    ADDBLACKLISTCALL, ADDSPOT, CHECKSUM, CLOSEPORT, CONFIRMED, CONTESTNAME, CQFREQ, DELETEQS, DELETESPOT,
    DISCONNECT_ME, ECHO, ECHOREQ, FILE, FREQ, FREQMODE, FUNCTIONKEY, IAM, LASTQAT, MASTER, PACKET,
    PACKETSTRING, PASSFREQ, QSO, QSODELETE, QSONRS, REEDITQSO, REJECTNR, REMOVECALLSTACKCALLSIGN,
    REQCONTESTNAME, REQCQFREQ, REQPASSFREQ, RESERVENR, RESETQSONRS, RESYNCQSO, SKED, SKEDD, SKEDSYNC,
    STACKCALL, STACKANOTHERCALL, STATUS, STOPIAM, TIME, TALK, WHOAREU, XMIT
}

enum class N1mmPolicyTier { SAFE_STATE, CANONICAL_LOG_MUTATION, SERIAL_COORDINATION, RADIO_TX_CONTROL, ARBITRARY_PAYLOAD }
enum class N1mmPolicyDecision { ACCEPT_STATE, MONITOR_ONLY, TRUSTED_REVIEW, AUTO_ACCEPT_SAFE_ADD, BLOCKED_BY_POLICY }
enum class N1mmMode { OFF, MONITOR_ONLY, TRUSTED_LAN_REVIEW, TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS }
enum class N1mmCoverage { FULL_RUNTIME, MONITOR_RUNTIME, TRUSTED_REVIEW_RUNTIME, CODEC_ONLY, EXISTING_SHACKCQ_OWNER, BLOCKED_BY_SAFETY, NOT_IMPLEMENTED, UNKNOWN_REFERENCE }

data class N1mmFrame(val stationNumber: Int, val station: String, val command: String, val fields: List<String>, val malformedUtf8: Boolean = false)
data class N1mmDiscovery(val station: String, val advertisedIp: String, val tcpPort: Int, val version: String, val operatorCall: String, val vpnName: String)
data class N1mmTypedCommand(val command: N1mmCommand, val values: Map<String, String>, val unknownExtraFields: List<String> = emptyList())
data class N1mmContact(val fields: List<String>) {
    init { require(fields.size >= FIELD_NAMES.size) }
    operator fun get(name: String): String = fields[FIELD_NAMES.indexOf(name).also { require(it >= 0) }]
    fun values() = FIELD_NAMES.zip(fields.take(FIELD_NAMES.size)).toMap()
    val unknownExtraFields get() = fields.drop(FIELD_NAMES.size)
    companion object {
        val FIELD_NAMES = listOf("Timestamp","CallSign","Freq","XmitFrequency","Mode","ContestName","SNT","RCV","CountryPrefix","StationPrefix","QTH","Name","Comment","NR","Sect","Prec","CK","ZN","SentNR","Points","IsMultiplier1","IsMultiplier2","Power","Band","WPXPrefix","Exchange1","RadioNr","Op","GridSquare","ContestNR","IsMultiplier3","MiscText","Continent","ContactType","Run1Run2","RoverLocation","RadioInterfaced","ContactNetworkedCompNr","NetBiosName","IsOriginal","IsRunQSO","Id","IsClaimedQso")
    }
}

data class N1mmPeerTrust(
    val station: String,
    val expectedOperatorCall: String,
    val interfaceName: String,
    val subnet: String,
    val pinnedAddress: String? = null,
    val contestName: String,
    val ruleVersion: String,
)
data class N1mmPeerSnapshot(val station: String, val address: String, val version: String, val operatorCall: String, val lastSeen: Long, val contestName: String = "", val trusted: Boolean = false)
data class N1mmPolicyContext(val mode: N1mmMode, val trustedPeer: Boolean, val contestMatches: Boolean, val unambiguousNewAdd: Boolean = false)
