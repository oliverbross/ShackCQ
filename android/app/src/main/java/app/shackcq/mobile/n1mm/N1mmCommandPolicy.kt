package app.shackcq.mobile.n1mm

object N1mmCommandPolicy {
    fun tier(command: N1mmCommand): N1mmPolicyTier = when (command) {
        N1mmCommand.QSO, N1mmCommand.REEDITQSO, N1mmCommand.RESYNCQSO, N1mmCommand.QSODELETE, N1mmCommand.DELETEQS, N1mmCommand.CHECKSUM, N1mmCommand.CONFIRMED -> N1mmPolicyTier.CANONICAL_LOG_MUTATION
        N1mmCommand.QSONRS, N1mmCommand.RESERVENR, N1mmCommand.REJECTNR, N1mmCommand.RESETQSONRS -> N1mmPolicyTier.SERIAL_COORDINATION
        N1mmCommand.FREQMODE, N1mmCommand.FUNCTIONKEY, N1mmCommand.XMIT, N1mmCommand.CLOSEPORT, N1mmCommand.PACKETSTRING, N1mmCommand.TIME -> N1mmPolicyTier.RADIO_TX_CONTROL
        N1mmCommand.FILE, N1mmCommand.PACKET -> N1mmPolicyTier.ARBITRARY_PAYLOAD
        else -> N1mmPolicyTier.SAFE_STATE
    }

    fun decide(command: N1mmCommand, context: N1mmPolicyContext): N1mmPolicyDecision = when (tier(command)) {
        N1mmPolicyTier.SAFE_STATE -> if (context.mode == N1mmMode.OFF) N1mmPolicyDecision.MONITOR_ONLY else N1mmPolicyDecision.ACCEPT_STATE
        N1mmPolicyTier.RADIO_TX_CONTROL, N1mmPolicyTier.ARBITRARY_PAYLOAD -> N1mmPolicyDecision.BLOCKED_BY_POLICY
        N1mmPolicyTier.SERIAL_COORDINATION -> if (context.mode != N1mmMode.OFF && context.trustedPeer && context.contestMatches) N1mmPolicyDecision.TRUSTED_REVIEW else N1mmPolicyDecision.MONITOR_ONLY
        N1mmPolicyTier.CANONICAL_LOG_MUTATION -> when {
            context.mode == N1mmMode.TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS && command == N1mmCommand.QSO && context.trustedPeer && context.contestMatches && context.unambiguousNewAdd -> N1mmPolicyDecision.AUTO_ACCEPT_SAFE_ADD
            context.mode in setOf(N1mmMode.TRUSTED_LAN_REVIEW, N1mmMode.TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS) && context.trustedPeer && context.contestMatches -> N1mmPolicyDecision.TRUSTED_REVIEW
            else -> N1mmPolicyDecision.MONITOR_ONLY
        }
    }
}
