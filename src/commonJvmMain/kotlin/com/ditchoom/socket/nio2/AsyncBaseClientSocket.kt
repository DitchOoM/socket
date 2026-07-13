package com.ditchoom.socket.nio2

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
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
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
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

abstract class AsyncBaseClientSocket(
    config: TransportConfig = TransportConfig(),
) : ByteBufferClientSocket<AsynchronousSocketChannel>(config) {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    /**
     * The socket-scoped home for the *orphaned* read (RFC §3.2). The JDK's timed
     * `AsynchronousSocketChannel.read` is destructive — an elapsed timeout arms `readKilled` and kills
     * the read half. To enforce a deadline **non-destructively** the read itself runs here — decoupled
     * from the caller's coroutine and with **no** JDK timeout ([Duration.INFINITE]) — while the caller
     * `withTimeout`s only the *wait*. On timeout the wait is abandoned; the read (and the receive
     * buffer it owns) survives to be re-awaited by the next `read()`. Cancelled in [close] so an
     * outstanding read unwinds when the socket closes.
     */
    private val orphanedReadScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    /** The single outstanding read, if one is in flight — enforces single-flight (§3.2). */
    private var inFlightRead: InFlightRead? = null

    /**
     * An orphaned read: the background [deferred] doing the actual `aRead`, plus the pooled [buffer] it
     * reads into. The buffer's lifetime **follows the read, not the caller** — it is freed only once
     * [deferred] completes (fails / EOF), never on a caller timeout, so a caller that gave up waiting
     * can't free a buffer a live read is still writing into (the QUIC-JNI UAF class, §3.2).
     */
    private class InFlightRead(
        val deferred: Deferred<Int>,
        val buffer: PlatformBuffer,
    )

    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(deadline) }
        // The JDK's timed read is destructive (`readKilled`), so route the plain read through the
        // orphaned-read single-flight (RFC §3.2) instead — enforced *and* non-destructive.
        return orphanedReadRaw(deadline)
    }

    /**
     * The non-destructive read (RFC §3.2 orphaned-read single-flight). Enforces [deadline] by racing a
     * `withTimeout` against a background `aRead` that owns its receive buffer and carries **no** JDK
     * timeout (so `readKilled` is never armed):
     *
     *  - **Enforced (Axis 1):** the caller waits at most [deadline] for the background read; on expiry
     *    it throws [SocketTimeoutException] rather than blocking forever.
     *  - **Non-destructive (Axis 2):** the timeout abandons only the *wait*. The background read keeps
     *    running on [orphanedReadScope] with its buffer intact, left in [inFlightRead] for the next
     *    `read()` to re-await — so no bytes are lost and the connection stays usable.
     *  - **Buffer safety (§3.2):** the pooled buffer is freed only when the read itself completes
     *    (fails / EOF), never on a caller timeout, so a gave-up caller can't free a buffer a live read
     *    is still writing into.
     *
     * `withLock` serializes readers, so at most one background read is ever in flight.
     */
    private suspend fun orphanedReadRaw(deadline: Duration): ReadBuffer =
        readMutex.withLock {
            val inFlight = inFlightRead ?: startOrphanedRead().also { inFlightRead = it }
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
                    inFlightRead = null
                    inFlight.buffer.freeNativeMemory()
                    throw e
                }
            inFlightRead = null
            if (bytesRead < 0) {
                inFlight.buffer.freeNativeMemory()
                throw SocketClosedException.EndOfStream("Received $bytesRead from server indicating a socket close.")
            }
            inFlight.buffer.resetForRead()
            inFlight.buffer
        }

    /**
     * Launches the background read that backs [orphanedReadRaw]. The `aRead` runs with a
     * [Duration.INFINITE] deadline on purpose: enforcement is the caller's `withTimeout`, never the
     * JDK's destructive timeout — the read must genuinely stay outstanding so it survives a caller
     * timeout (RFC §3.2).
     */
    private fun startOrphanedRead(): InFlightRead {
        val receiveBuffer = socket.getOption(StandardSocketOptions.SO_RCVBUF)
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(receiveBuffer))
        val byteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer
        val deferred =
            orphanedReadScope.async {
                try {
                    socket.aRead(byteBuffer, Duration.INFINITE)
                } catch (e: IOException) {
                    throw wrapJvmException(e)
                }
            }
        return InFlightRead(deferred, buffer)
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            try {
                readMutex.withLock {
                    socket.aRead(buffer.byteBuffer, timeout)
                }
            } catch (e: IOException) {
                // Route every platform IOException through the single mapper. The async
                // failed() callback also wraps; this catches synchronous throws from
                // channel setup and passes already-wrapped SocketException through.
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
                    val bytesWritten = socket.aWrite(byteBuffer, timeout)
                    if (bytesWritten < 0) {
                        throw SocketClosedException.EndOfStream("Received $bytesWritten from server indicating a socket close.")
                    }
                    totalWritten += bytesWritten
                }
            }
        } catch (e: IOException) {
            // Route every platform IOException (Broken pipe, Connection reset, etc.)
            // through the single mapper. The async failed() callback also wraps; this
            // catches synchronous throws and passes already-wrapped SocketException through.
            throw wrapJvmException(e)
        }
        return totalWritten
    }

    override suspend fun close() {
        // Tear down the waiter for any orphaned read; closing the channel (in super.close) is what
        // actually completes the outstanding JDK read, so no read leaks past close.
        orphanedReadScope.cancel()
        super.close()
    }
}
