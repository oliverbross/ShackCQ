package app.shackcq.mobile.n1mm

import java.io.Closeable
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

class N1mmDiscoveryService(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val onDiscovery: (N1mmDiscovery, InetAddress) -> Unit,
    private val onError: (String) -> Unit = {},
) : Closeable {
    private val running=AtomicBoolean(false)
    private var socket: DatagramSocket?=null
    fun start() {
        if(!running.compareAndSet(false,true)) return
        val opened=DatagramSocket(null).apply { reuseAddress=true; bind(InetSocketAddress(bindAddress,this@N1mmDiscoveryService.port)); soTimeout=500 }
        socket=opened
        Thread({ val data=ByteArray(N1mmDiscoveryCodec.MAX_DATAGRAM+1); while(running.get()) try {
            val packet=DatagramPacket(data,data.size); opened.receive(packet)
            if(packet.length<=N1mmDiscoveryCodec.MAX_DATAGRAM) onDiscovery(N1mmDiscoveryCodec.decode(packet.data.copyOfRange(packet.offset,packet.offset+packet.length)),packet.address)
        } catch(_:SocketTimeoutException) {} catch(error:Exception) { if(running.get()) onError(error.javaClass.simpleName) } },"ShackCQ-N1MM-Discovery").apply { isDaemon=true;start() }
    }
    fun advertise(value:N1mmDiscovery,target:InetAddress,targetPort:Int=port){ val bytes=N1mmDiscoveryCodec.encode(value); socket?.send(DatagramPacket(bytes,bytes.size,target,targetPort)) ?: error("Discovery is not active") }
    override fun close(){running.set(false);socket?.close();socket=null}
}
