package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TimeoutContext
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.remoteAddressOrNull
import com.ditchoom.socket.nio.util.write
import com.ditchoom.socket.translateRead
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.time.Duration

abstract class BaseClientSocket(
    protected val blocking: Boolean = false,
    config: TransportConfig = TransportConfig(),
) : ByteBufferClientSocket<SocketChannel>(config) {
    val selector = if (!blocking) Selector.open()!! else null

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    /**
     * The socket-scoped home for the *orphaned* blocking read (RFC §3.2). A blocking
     * `SocketChannel.read()` can't be interrupted without closing the channel, so to enforce a
     * deadline **non-destructively** the read itself runs here — decoupled from the caller's
     * coroutine — while the caller `withTimeout`s only the *wait*. On timeout the wait is abandoned;
     * the read (and the receive buffer it owns) survives to be re-awaited by the next `read()`.
     * Cancelled in [close] so an outstanding read unwinds when the socket closes.
     */
    private val blockingReadScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    /** The single outstanding blocking read, if one is in flight — enforces single-flight (§3.2). */
    private var inFlightBlockingRead: InFlightBlockingRead? = null

    /**
     * An orphaned blocking read: the background [deferred] doing the actual `channel.read`, plus the
     * pooled [buffer] it reads into. The buffer's lifetime **follows the read, not the caller** — it
     * is freed only once [deferred] completes (fails / EOF), never on a caller timeout, so a caller
     * that gave up waiting can't free a buffer a live read is still writing into (the QUIC-JNI UAF
     * class, §3.2).
     */
    private class InFlightBlockingRead(
        val deferred: Deferred<Int>,
        val buffer: PlatformBuffer,
    )

    override suspend fun remotePort() = (socket.remoteAddressOrNull() as? InetSocketAddress)?.port ?: -1

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(deadline) }
        // The blocking channel can't honor a deadline in-line without being torn down, so route it
        // through the orphaned-read single-flight (RFC §3.2) — enforced *and* non-destructive.
        if (blocking) return blockingReadRaw(deadline)
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(socket.socket().receiveBufferSize))
        try {
            read(buffer.unwrapFully() as BaseJvmBuffer, deadline)
            buffer.resetForRead()
            return buffer
        } catch (e: Exception) {
            // Return the pooled buffer instead of dropping it to the GC.
            buffer.freeNativeMemory()
            throw e
        }
    }

    /**
     * The blocking-path read (RFC §3.2 orphaned-read single-flight). Enforces [deadline] by racing
     * a `withTimeout` against a background read that owns its receive buffer:
     *
     *  - **Enforced (Axis 1):** the caller waits at most [deadline] for the background read; on expiry
     *    it throws [SocketTimeoutException] rather than blocking forever.
     *  - **Non-destructive (Axis 2):** the timeout abandons only the *wait*. The background read keeps
     *    running on [blockingReadScope] with its buffer intact, left in [inFlightBlockingRead] for the
     *    next `read()` to re-await — so no bytes are lost and the connection stays usable.
     *  - **Buffer safety (§3.2):** the pooled buffer is freed only when the read itself completes
     *    (fails / EOF), never on a caller timeout, so a gave-up caller can't free a buffer a live read
     *    is still writing into.
     *
     * `withLock` serializes readers (matching the non-blocking path's `readMutex`), so at most one
     * background read is ever in flight.
     */
    private suspend fun blockingReadRaw(deadline: Duration): ReadBuffer =
        readMutex.withLock {
            val inFlight = inFlightBlockingRead ?: startBlockingRead().also { inFlightBlockingRead = it }
            val bytesRead =
                try {
                    if (deadline.isInfinite()) {
                        inFlight.deferred.await()
                    } else {
                        withTimeout(deadline) { inFlight.deferred.await() }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Our deadline elapsed: orphan the read (keep the buffer + single-flight state) and
                    // abandon only the wait. The next read() re-awaits this same background read.
                    throw SocketTimeoutException(TimeoutContext.Read(deadline))
                } catch (e: CancellationException) {
                    // The waiter was cancelled from outside; the background read still owns the buffer,
                    // so leave it orphaned for the next read() and let the cancellation propagate.
                    throw e
                } catch (e: Throwable) {
                    // The read itself failed (deferred completed exceptionally) — it no longer touches
                    // the buffer, so it's safe to release it and drop single-flight state.
                    inFlightBlockingRead = null
                    inFlight.buffer.freeNativeMemory()
                    throw e
                }
            inFlightBlockingRead = null
            if (bytesRead < 0) {
                inFlight.buffer.freeNativeMemory()
                throw SocketClosedException.EndOfStream("Received $bytesRead from server indicating a socket close.")
            }
            inFlight.buffer.resetForRead()
            inFlight.buffer
        }

    /**
     * Launches the background blocking read that backs [blockingReadRaw]. The `read` runs with an
     * [Duration.INFINITE] deadline on purpose: enforcement is the caller's `withTimeout`, never here —
     * the read must genuinely block so it survives a caller timeout (RFC §3.2).
     */
    private fun startBlockingRead(): InFlightBlockingRead {
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(socket.socket().receiveBufferSize))
        val byteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer
        val deferred =
            blockingReadScope.async {
                try {
                    socket.read(byteBuffer, selector, Duration.INFINITE)
                } catch (e: IOException) {
                    throw wrapJvmException(e)
                }
            }
        return InFlightBlockingRead(deferred, buffer)
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            try {
                readMutex.withLock { socket.read(buffer.byteBuffer, selector, timeout) }
            } catch (e: IOException) {
                // Route every platform IOException (ClosedChannelException, Connection reset,
                // etc.) through the single mapper. Already-wrapped SocketException is passed
                // through unchanged. CancellationException is not an IOException → propagates.
                throw wrapJvmException(e)
            }
        if (bytesRead < 0) {
            throw SocketClosedException.EndOfStream("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return BytesWritten(it.wrap(buffer.unwrapFully() as BaseJvmBuffer, deadline)) }
        return BytesWritten(rawSocketWrite(buffer, deadline))
    }

    internal override suspend fun rawSocketWrite(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val byteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer
        var totalWritten = 0
        try {
            writeMutex.withLock {
                while (byteBuffer.hasRemaining()) {
                    val bytesWritten = socket.write(byteBuffer, selector, timeout)
                    if (bytesWritten < 0) {
                        throw SocketClosedException.EndOfStream("Received $bytesWritten from server indicating a socket close.")
                    }
                    totalWritten += bytesWritten
                }
            }
        } catch (e: IOException) {
            // Route every platform IOException (Broken pipe, Connection reset,
            // ClosedChannelException, etc.) through the single mapper so callers see
            // SocketClosedException.BrokenPipe / .ConnectionReset / .General — not a
            // raw IOException. CancellationException is not an IOException → propagates.
            throw wrapJvmException(e)
        }
        return totalWritten
    }

    override suspend fun close() {
        selector?.aClose()
        // Closing the channel (in super.close) is what unblocks an orphaned blocking read; cancelling
        // its scope tears down the waiter so no read leaks past close.
        if (blocking) blockingReadScope.cancel()
        super.close()
    }
}
