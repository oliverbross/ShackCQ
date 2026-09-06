package app.shackcq.mobile.n1mm

import java.io.Closeable
import java.net.*
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class N1mmNetworkController(
    private val config: N1mmNetworkConfig,
    private val peers: N1mmPeerRegistry = N1mmPeerRegistry(config.maximumPeers),
    private val diagnostics: N1mmDiagnostics = N1mmDiagnostics(config.retainedEvents),
    private val trusts: List<N1mmPeerTrust> = emptyList(),
    private val onCommand: (String, N1mmTypedCommand, N1mmPolicyContext, N1mmPolicyDecision) -> Unit = { _, _, _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis()/1_000 },
) : Closeable {
    private val running=AtomicBoolean(false)
    private val links=CopyOnWriteArrayList<N1mmTcpLink>()
    private val dedupe=linkedMapOf<String,Long>()
    private val rates=linkedMapOf<String,ArrayDeque<Long>>()
    private var server:ServerSocket?=null
    private var discovery:N1mmDiscoveryService?=null
    val active get()=running.get()

    fun start() {
        require(config.enabled && config.mode != N1mmMode.OFF) { "N1MM networking requires explicit enable" }
        if(!running.compareAndSet(false,true)) return
        try {
            val bind=InetAddress.getByName(config.bindAddress)
            require(config.lanBroadcastOptIn || bind.isLoopbackAddress) { "LAN binding requires explicit opt-in" }
            server=ServerSocket().apply { reuseAddress=true; bind(InetSocketAddress(bind,config.tcpPort)); soTimeout=500 }
            discovery=N1mmDiscoveryService(bind,config.discoveryPort,{ value,source ->
                val trusted=trusts.any { trust ->
                    trust.station==value.station &&
                        trust.expectedOperatorCall.equals(value.operatorCall,true) &&
                        trust.interfaceName==config.interfaceName &&
                        trust.contestName==config.contestName &&
                        trust.ruleVersion==config.ruleVersion &&
                        addressInSubnet(source,trust.subnet) &&
                        (trust.pinnedAddress==null||trust.pinnedAddress==source.hostAddress)
                }
                val peer=peers.observe(value,source.hostAddress.orEmpty(),clock(),trusted); diagnostics.record("DISCOVERY","ACCEPTED",if(value.advertisedIp==source.hostAddress)"address matched" else "advertised address differed; packet source retained",peer.station)
            },{ diagnostics.record("DISCOVERY","ERROR",it) }).also { it.start() }
            Thread({ while(running.get()) try { val socket=server?.accept() ?: break; if(links.size>=config.maximumLinks) socket.close() else addLink(socket) } catch(_:SocketTimeoutException){} catch(error:Exception){if(running.get())diagnostics.record("TCP","ERROR",error.javaClass.simpleName)} },"ShackCQ-N1MM-Accept").apply{isDaemon=true;start()}
        } catch (error: Throwable) {
            discovery?.close(); discovery = null
            runCatching { server?.close() }; server = null
            running.set(false)
            throw error
        }
    }

    fun advertise(targetAddress:String="127.0.0.1",targetPort:Int=config.discoveryPort){
        require(active); discovery?.advertise(N1mmDiscovery(config.stationName,config.bindAddress,config.tcpPort,config.version,config.operatorCall,""),InetAddress.getByName(targetAddress),targetPort)
    }

    fun connect(address:String,port:Int=config.tcpPort){ require(active); require(links.size<config.maximumLinks); addLink(Socket().apply { connect(InetSocketAddress(address,port),3000) }) }

    fun heartbeat(){ broadcast(N1mmFrame(config.stationNumber,config.stationName,N1mmCommand.ECHOREQ.name,listOf(java.time.LocalDate.now().toString(),java.time.LocalTime.now().withNano(0).toString()))) }
    fun gracefulDisconnect(){ if(active) broadcast(N1mmFrame(config.stationNumber,config.stationName,N1mmCommand.DISCONNECT_ME.name,emptyList())); close() }
    fun onNetworkChanged(){ close() }
    fun peerSnapshots()=peers.snapshots()
    fun diagnosticEvents()=diagnostics.snapshot()
    fun diagnosticCounters()=diagnostics.counterSnapshot()

    private fun addLink(socket:Socket){
        lateinit var link:N1mmTcpLink
        link=N1mmTcpLink(socket,config.maximumFrameBytes,config.maximumStreamBufferBytes,{handle(it,link)},{diagnostics.record("TCP","ERROR",it)},{links.remove(link)})
        links+=link
        link.start()
    }
    private fun broadcast(frame:N1mmFrame){
        links.removeIf{!it.active}
        links.forEach { link ->
            runCatching{link.send(frame)}.onFailure{error ->
                diagnostics.record(frame.command,"SEND_ERROR",error.javaClass.simpleName)
                link.close()
            }
        }
    }
    private fun handle(frame:N1mmFrame,sourceLink:N1mmTcpLink){
        val now=clock(); val queue=synchronized(rates){rates.getOrPut(frame.station){ArrayDeque()}.also { while(it.firstOrNull()?.let { t->t<=now-60 }==true)it.removeFirst(); if(it.size>=config.maximumFramesPerMinutePerPeer){diagnostics.record(frame.command,"RATE_LIMIT","peer frame limit",frame.station);return};it.addLast(now)}}
        queue.size
        val hash=MessageDigest.getInstance("SHA-256").digest(N1mmFrameCodec.encode(frame)).joinToString(""){"%02x".format(it)}
        synchronized(dedupe){dedupe.entries.removeIf{it.value<=now};if(dedupe.put(hash,now+config.dedupeRetentionSeconds)!=null){diagnostics.record(frame.command,"DEDUPE","replayed frame",frame.station);return}}
        val typed=runCatching{N1mmCommandCodec.decode(frame)}.getOrElse{diagnostics.record(frame.command,"REJECTED","malformed command",frame.station);return}
        if(typed.command==N1mmCommand.ECHOREQ){broadcast(N1mmFrame(config.stationNumber,config.stationName,N1mmCommand.ECHO.name,listOf(typed.values["date"].orEmpty(),typed.values["time"].orEmpty())))}
        if(typed.command==N1mmCommand.CONTESTNAME)peers.updateContest(frame.station,typed.values["contestName"].orEmpty())
        val peer=peers.snapshots().find{it.station==frame.station};val context=N1mmPolicyContext(config.mode,peer?.trusted==true,
            typed.values["ContestName"].orEmpty().let{it.isBlank()||it==config.contestName},
            typed.command==N1mmCommand.QSO && typed.values["Id"].orEmpty().isNotBlank() && typed.values["IsOriginal"]=="1")
        val decision=N1mmCommandPolicy.decide(typed.command,context);diagnostics.record(typed.command.name,decision.name,"policy evaluated",frame.station);onCommand(frame.station,typed,context,decision)
        if(typed.command==N1mmCommand.DISCONNECT_ME)sourceLink.close()
    }

    fun reconnectDelayMillis(attempt:Int):Long=min(config.reconnectMaxMillis,config.reconnectMinMillis*(1L shl attempt.coerceIn(0,20)))
    private fun addressInSubnet(address:InetAddress,subnet:String):Boolean{
        val parts=subnet.split('/',limit=2)
        val network=runCatching{InetAddress.getByName(parts[0])}.getOrNull()?:return false
        if(parts.size==1)return address==network
        val addressBytes=address.address
        val networkBytes=network.address
        if(addressBytes.size!=networkBytes.size)return false
        val prefix=parts[1].toIntOrNull()?:return false
        if(prefix !in 0..addressBytes.size*8)return false
        var remaining=prefix
        for(index in addressBytes.indices){
            val bits=min(remaining,8)
            if(bits==0)break
            val mask=(0xff shl (8-bits)) and 0xff
            if((addressBytes[index].toInt() and mask)!=(networkBytes[index].toInt() and mask))return false
            remaining-=bits
        }
        return true
    }
    override fun close(){if(!running.compareAndSet(true,false))return;discovery?.close();discovery=null;runCatching{server?.close()};server=null;links.forEach(Closeable::close);links.clear();synchronized(rates){rates.clear()}}
}
