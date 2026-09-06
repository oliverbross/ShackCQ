package app.shackcq.mobile.n1mm

object N1mmCommandCodec {
    val schemas: Map<N1mmCommand, List<String>> = mapOf(
        N1mmCommand.ADDBLACKLISTCALL to listOf("callsign"), N1mmCommand.ADDSPOT to listOf("call","timestamp","freqX100","comment","qsxX100","spotter","isWorked"),
        N1mmCommand.CHECKSUM to listOf("startTS","endTS","checksum","contest","myCall","numMinutes","resyncAll","rescoreRequested"),
        N1mmCommand.CLOSEPORT to emptyList(), N1mmCommand.CONFIRMED to listOf("startTS","endTS","checksum","numMinutes"),
        N1mmCommand.CONTESTNAME to listOf("station","contestName","contestSubType"), N1mmCommand.CQFREQ to listOf("freqX100"),
        N1mmCommand.DELETEQS to listOf("startTS","stationName","numMinutes"), N1mmCommand.DELETESPOT to listOf("callsign","freqX100"),
        N1mmCommand.DISCONNECT_ME to emptyList(), N1mmCommand.ECHO to listOf("date","time"), N1mmCommand.ECHOREQ to listOf("date","time"),
        N1mmCommand.FILE to listOf("fileName","contents"), N1mmCommand.FREQ to listOf("freqX100"), N1mmCommand.FREQMODE to listOf("freqX100","mode","focusBackFlag"),
        N1mmCommand.FUNCTIONKEY to listOf("radioNr","callsign","keyNum"), N1mmCommand.IAM to listOf("stationNumberText"), N1mmCommand.LASTQAT to listOf("lastQsoTimestamp"),
        N1mmCommand.MASTER to listOf("masterStationName"), N1mmCommand.PACKET to listOf("packetText"), N1mmCommand.PACKETSTRING to listOf("packetCommand"),
        N1mmCommand.PASSFREQ to listOf("freqX100"), N1mmCommand.QSODELETE to listOf("callsign","timestamp"),
        N1mmCommand.QSONRS to listOf("contestName") + (0..31).map { "qsoNumber$it" },
        N1mmCommand.REJECTNR to listOf("serial","freqX100"), N1mmCommand.REMOVECALLSTACKCALLSIGN to listOf("callsign"),
        N1mmCommand.REQCONTESTNAME to emptyList(), N1mmCommand.REQCQFREQ to emptyList(), N1mmCommand.REQPASSFREQ to emptyList(),
        N1mmCommand.RESERVENR to listOf("serial","freqX100"), N1mmCommand.RESETQSONRS to emptyList(),
        N1mmCommand.SKED to listOf("guid","contest","subtype","deleted","time","call","freq","mode","originStation","comment"),
        N1mmCommand.SKEDD to emptyList(), N1mmCommand.SKEDSYNC to emptyList(), N1mmCommand.STACKANOTHERCALL to listOf("callsign","radioNr"),
        N1mmCommand.STATUS to listOf("passFreqX100","currentFreqX100","isRunning","operatorCall","radioNr","modeCode","mode","operatorCategory","transmitterCategory","countryFileVersion","n1mmVersion","isMaster","stationNumber","networkStatus","runState","txActive","score","rate","reserved"),
        N1mmCommand.STOPIAM to emptyList(), N1mmCommand.TIME to listOf("date","time"), N1mmCommand.TALK to listOf("message"),
        N1mmCommand.WHOAREU to emptyList(), N1mmCommand.XMIT to listOf("radioNr","message","flag1","flag2","flag3"),
    )

    fun decode(frame: N1mmFrame): N1mmTypedCommand {
        require(frame.fields.size <= 128) { "N1MM command field overflow" }
        require(frame.fields.all { it.length <= N1mmContactCodec.MAX_FIELD_CHARS }) { "N1MM command field exceeds bound" }
        val command = runCatching { N1mmCommand.valueOf(frame.command.uppercase()) }.getOrElse { throw IllegalArgumentException("Unknown N1MM command") }
        if (command in setOf(N1mmCommand.QSO, N1mmCommand.RESYNCQSO)) {
            require(frame.fields.size >= 44); N1mmContactCodec.decode(frame.fields.drop(1))
            return N1mmTypedCommand(command, mapOf("oldTimestamp" to frame.fields.first()) + N1mmContact.FIELD_NAMES.zip(frame.fields.drop(1).take(43)), frame.fields.drop(44))
        }
        if (command == N1mmCommand.REEDITQSO) {
            require(frame.fields.size >= 45); N1mmContactCodec.decode(frame.fields.drop(2))
            return N1mmTypedCommand(command, mapOf("oldTimestamp" to frame.fields[0], "oldCallsign" to frame.fields[1]) + N1mmContact.FIELD_NAMES.zip(frame.fields.drop(2).take(43)), frame.fields.drop(45))
        }
        if (command == N1mmCommand.STACKCALL) {
            N1mmContactCodec.decode(frame.fields)
            return N1mmTypedCommand(command, N1mmContact.FIELD_NAMES.zip(frame.fields.take(43)).toMap(), frame.fields.drop(43))
        }
        val names = schemas[command] ?: error("Command schema missing for $command")
        require(frame.fields.size >= names.size) { "$command field underflow" }
        return N1mmTypedCommand(command, names.zip(frame.fields.take(names.size)).toMap(), frame.fields.drop(names.size))
    }

    fun encode(station: String, stationNumber: Int, command: N1mmTypedCommand): N1mmFrame {
        val fields = when (command.command) {
            N1mmCommand.QSO, N1mmCommand.RESYNCQSO -> listOf(command.values.getValue("oldTimestamp")) + N1mmContact.FIELD_NAMES.map { command.values[it].orEmpty() }
            N1mmCommand.REEDITQSO -> listOf(command.values.getValue("oldTimestamp"), command.values.getValue("oldCallsign")) + N1mmContact.FIELD_NAMES.map { command.values[it].orEmpty() }
            N1mmCommand.STACKCALL -> N1mmContact.FIELD_NAMES.map { command.values[it].orEmpty() }
            else -> schemas.getValue(command.command).map { command.values[it].orEmpty() }
        } + command.unknownExtraFields
        return N1mmFrame(stationNumber, station, command.command.name, fields)
    }
}
