package app.shackcq.mobile.n1mm

import org.junit.Assert.*
import org.junit.Test

class N1mmCodecPolicyTest {
    private fun contactFields()=N1mmContact.FIELD_NAMES.map{when(it){"Timestamp"->"2026-08-22 12:00:00";"CallSign"->"OM0RX";"Freq","XmitFrequency"->"1407400";"Points"->"3";"IsOriginal"->"1";"Id"->"0123456789abcdef0123456789abcdef";else->""}}
    @Test fun discoveryRoundTripsTrailingPercentAndDetectsForgedAddress(){val value=N1mmDiscovery("A","127.0.0.1",12070,"1.0","OM0RX","");val bytes=N1mmDiscoveryCodec.encode(value);assertEquals('%'.code.toByte(),bytes.last());assertEquals(value,N1mmDiscoveryCodec.decode(bytes));assertTrue(N1mmDiscoveryCodec.addressDiscrepancy(value,java.net.InetAddress.getByName("127.0.0.2")))}
    @Test fun streamHandlesSplitConcatenatedNoiseAndNul(){val a=N1mmFrame(0,"A","ECHOREQ",listOf("2026-08-22","12:00:00"));val b=N1mmFrame(1,"B","MASTER",listOf("B"));val encoded=N1mmFrameCodec.encode(a);val parser=N1mmStreamParser();assertTrue(parser.append(encoded.copyOfRange(0,12)).isEmpty());assertEquals(a,parser.append(encoded.copyOfRange(12,encoded.size)).single());val both=parser.append("noise".toByteArray()+N1mmFrameCodec.encode(a)+N1mmFrameCodec.encode(b));assertEquals(listOf(a,b),both);assertTrue(parser.append(ByteArray(6)).isEmpty())}
    @Test fun literalTildeIsSanitizedAndMalformedFramesAreBounded(){val encoded=N1mmFrameCodec.encode(N1mmFrame(0,"A","TALK",listOf("a~b")));assertTrue(encoded.toString(Charsets.UTF_8).contains("a!b"));assertFails{N1mmStreamParser(32,64).append(ByteArray(65){'x'.code.toByte()})}}
    @Test fun everyDocumentedCommandHasTypedRoundTripCoverage(){N1mmCommand.entries.forEach{command->
        val typed=when(command){
            N1mmCommand.QSO,N1mmCommand.RESYNCQSO->N1mmTypedCommand(command,mapOf("oldTimestamp" to "old")+N1mmContact.FIELD_NAMES.zip(contactFields()))
            N1mmCommand.REEDITQSO->N1mmTypedCommand(command,mapOf("oldTimestamp" to "old","oldCallsign" to "OLD")+N1mmContact.FIELD_NAMES.zip(contactFields()))
            N1mmCommand.STACKCALL->N1mmTypedCommand(command,N1mmContact.FIELD_NAMES.zip(contactFields()).toMap())
            else->N1mmTypedCommand(command,N1mmCommandCodec.schemas.getValue(command).associateWith{"1"})
        }
        val decoded=N1mmCommandCodec.decode(N1mmCommandCodec.encode("A",0,typed));assertEquals(command,decoded.command)
    }}
    @Test fun contactPreservesAll43AndUnknownFutureFields(){val contact=N1mmContactCodec.decode(contactFields()+listOf("future"));assertEquals(43,contact.values().size);assertEquals(listOf("future"),contact.unknownExtraFields);assertEquals(contact.fields,N1mmContactCodec.encode(contact))}
    @Test fun xmlCoversBroadcastRootsAndRejectsDtdEntities(){
        N1mmXmlCodec.outboundRoots.forEach { root ->
            assertEquals(root,N1mmXmlCodec.decode(N1mmXmlCodec.encode(N1mmXmlMessage(root,mapOf("call" to "OM0RX")))).root)
        }
        assertFails{N1mmXmlCodec.decode("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><RadioInfo>&e;</RadioInfo>".toByteArray())}
    }
    @Test fun policyBlocksControlTimeFileAndUntrustedMutations(){val off=N1mmPolicyContext(N1mmMode.MONITOR_ONLY,false,false);assertEquals(N1mmPolicyDecision.BLOCKED_BY_POLICY,N1mmCommandPolicy.decide(N1mmCommand.XMIT,off));assertEquals(N1mmPolicyDecision.BLOCKED_BY_POLICY,N1mmCommandPolicy.decide(N1mmCommand.TIME,off));assertEquals(N1mmPolicyDecision.BLOCKED_BY_POLICY,N1mmCommandPolicy.decide(N1mmCommand.FILE,off));assertEquals(N1mmPolicyDecision.MONITOR_ONLY,N1mmCommandPolicy.decide(N1mmCommand.QSO,off));val trusted=N1mmPolicyContext(N1mmMode.TRUSTED_LAN_AUTO_ACCEPT_SAFE_ADDS,true,true,true);assertEquals(N1mmPolicyDecision.AUTO_ACCEPT_SAFE_ADD,N1mmCommandPolicy.decide(N1mmCommand.QSO,trusted));assertEquals(N1mmPolicyDecision.TRUSTED_REVIEW,N1mmCommandPolicy.decide(N1mmCommand.QSODELETE,trusted))}
    private fun assertFails(block:()->Unit){try{block();fail("Expected failure")}catch(_:IllegalArgumentException){}}
}
