package app.shackcq.mobile.n1mm

import app.shackcq.mobile.contest.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class N1mmTransportScaleTest {
    private fun tcpPort()=ServerSocket(0).use{it.localPort};private fun udpPort()=tcpPort()
    private fun config(name:String,tcp:Int,udp:Int)=N1mmNetworkConfig(true,N1mmMode.MONITOR_ONLY,name,operatorCall="OM0RX",contestName="CQWWCW",tcpPort=tcp,discoveryPort=udp)
    @Test fun noSocketsBeforeExplicitStartAndShutdownIsIdempotent(){val controller=N1mmNetworkController(config("A",tcpPort(),udpPort()));assertFalse(controller.active);controller.start();assertTrue(controller.active);controller.close();controller.close();assertFalse(controller.active)}
    @Test fun loopbackTcpHeartbeatAndUdpDiscoveryWork(){val seen=CountDownLatch(1);val discovered=CountDownLatch(1);val bc=config("B",tcpPort(),udpPort());val ac=config("A",tcpPort(),udpPort());val b=N1mmNetworkController(bc,onCommand={_,command,_,_->if(command.command==N1mmCommand.ECHOREQ)seen.countDown()});val a=N1mmNetworkController(ac);try{a.start();b.start();a.connect("127.0.0.1",bc.tcpPort);a.heartbeat();assertTrue(seen.await(3,TimeUnit.SECONDS));a.advertise("127.0.0.1",bc.discoveryPort);repeat(30){if(b.peerSnapshots().any{it.station=="A"})discovered.countDown() else Thread.sleep(20)};assertTrue(discovered.await(1,TimeUnit.SECONDS))}finally{a.close();b.close()}}
    @Test fun discoveryTrustRequiresConfiguredInterfaceSubnetContestAndRule(){val target=config("B",tcpPort(),udpPort()).copy(ruleVersion="2026.1");val source=config("A",tcpPort(),udpPort());val trust=N1mmPeerTrust("A","OM0RX","loopback","127.0.0.0/8",contestName="CQWWCW",ruleVersion="2026.1");val b=N1mmNetworkController(target,trusts=listOf(trust));val a=N1mmNetworkController(source);try{a.start();b.start();a.advertise("127.0.0.1",target.discoveryPort);repeat(50){if(b.peerSnapshots().none{it.station=="A"})Thread.sleep(20)};assertTrue(b.peerSnapshots().single{it.station=="A"}.trusted)}finally{a.close();b.close()}}
    @Test fun scoreRebuildHandlesTenThousandSessionQsosWithoutGeneralLogMaterialization(){val definition=ContestRuleRegistry().require(ContestDefinitionId("cq-wpx-cw")).definition;val rows=(0 until 10_000).map{i->ContestQsoDraft("q$i","K${i}AA",i.toLong(),14_050_000,ContestBand.B20,ContestMode.CW,"599","599",worked=ContestEntityInfo(dxcc=(i%340).toString(),continent=if(i%2==0)"EU" else "NA",wpxPrefix="K$i"),station=ContestEntityInfo(dxcc="291",continent="NA"))};val start=System.nanoTime();val score=ContestScoreEngine().rebuild(definition,rows,20_000);val elapsed=(System.nanoTime()-start)/1_000_000;assertEquals(10_000,score.acceptedQsos);assertTrue("elapsed=${elapsed}ms",elapsed<15_000)}
}
