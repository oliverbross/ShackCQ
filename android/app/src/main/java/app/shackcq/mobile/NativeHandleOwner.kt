package app.shackcq.mobile

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class NativeOwnerState { OPEN, CLOSING, CLOSED }

internal class LifecycleGeneration {
    private val value = AtomicLong(0L)
    private val closed = AtomicBoolean(false)

    fun next(): Long = value.incrementAndGet()
    fun current(): Long = value.get()
    fun isCurrent(expected: Long): Boolean = !closed.get() && value.get() == expected
    fun retire() { value.incrementAndGet() }
    fun close() {
        if (closed.compareAndSet(false, true)) value.incrementAndGet()
    }
}

/**
 * A deliberately small checked slot for JNI pointer handles.
 *
 * The callback is invoked while [lock] is held, so callers must never suspend or
 * call another controller from [withHandle]. Retiring or closing the slot waits
 * for an in-flight native call and prevents every later call from seeing the old
 * handle.
 */
internal class NativeHandleOwner(
    initialHandle: Long = 0L,
    private val destroyHandle: (Long) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var handle = initialHandle
    private var generation = if (initialHandle == 0L) 0L else 1L
    private var state = NativeOwnerState.OPEN

    fun <T> withHandle(block: (Long) -> T): T? = synchronized(lock) {
        val current = handle
        if (state != NativeOwnerState.OPEN || current == 0L) null else block(current)
    }

    fun install(newHandle: Long): Long? {
        require(newHandle != 0L) { "Native handle must be non-zero" }
        return synchronized(lock) {
            if (state != NativeOwnerState.OPEN) {
                destroyHandle(newHandle)
                return@synchronized null
            }
            val retired = handle
            handle = 0L
            generation++
            try {
                if (retired != 0L) destroyHandle(retired)
            } catch (failure: Throwable) {
                runCatching { destroyHandle(newHandle) }
                throw failure
            }
            handle = newHandle
            ++generation
        }
    }

    fun retire() {
        synchronized(lock) {
            if (state != NativeOwnerState.OPEN) return
            val retired = handle
            handle = 0L
            generation++
            if (retired != 0L) destroyHandle(retired)
        }
    }

    fun generation(): Long = synchronized(lock) { generation }

    fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) {
        state == NativeOwnerState.OPEN && handle != 0L && generation == expectedGeneration
    }

    fun state(): NativeOwnerState = synchronized(lock) { state }

    override fun close() {
        synchronized(lock) {
            if (state != NativeOwnerState.OPEN) return
            state = NativeOwnerState.CLOSING
            val retired = handle
            handle = 0L
            generation++
            try {
                if (retired != 0L) destroyHandle(retired)
            } finally {
                state = NativeOwnerState.CLOSED
            }
        }
    }
}
