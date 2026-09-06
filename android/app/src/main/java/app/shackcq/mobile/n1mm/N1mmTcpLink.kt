package app.shackcq.mobile.n1mm

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class N1mmTcpLink(
    private val socket: Socket,
    maximumFrameBytes: Int,
    maximumBufferBytes: Int,
    private val onFrame: (N1mmFrame) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onClosed: () -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val parser = N1mmStreamParser(maximumFrameBytes, maximumBufferBytes)
    private var reader: Thread? = null
    val active: Boolean get() = running.get() && !closed.get()
    fun start() {
        if (!running.compareAndSet(false, true)) return
        reader = Thread({
            val chunk=ByteArray(8192)
            try { socket.getInputStream().use { input -> while(running.get()) { val count=input.read(chunk); if(count<0) break; parser.append(chunk.copyOf(count)).forEach(onFrame) } } }
            catch (error: Exception) { if(running.get()) onError(error.javaClass.simpleName) }
            finally { close() }
        }, "ShackCQ-N1MM-TCP").apply { isDaemon=true; start() }
    }
    @Synchronized fun send(frame: N1mmFrame) { require(running.get()); socket.getOutputStream().apply { write(N1mmFrameCodec.encode(frame)); flush() } }
    override fun close() {
        running.set(false)
        runCatching(socket::close)
        if (closed.compareAndSet(false, true)) onClosed()
    }
}
