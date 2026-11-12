package com.ditchoom.socket.nio2

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.data.freeIfData
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketIOException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TimeoutContext
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import com.ditchoom.socket.offTheAndroidMainThread
import com.ditchoom.socket.resumeOffThisThread
import com.ditchoom.socket.translateRead
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

abstract class AsyncBaseClientSocket(
    config: TransportConfig = TransportConfig(),
) : ByteBufferClientSocket<AsynchronousSocketChannel>(config) {
    private val readMutex = Mutex()

    override fun toString() = "${super.toString()} orphanedReadPending=${inFlightRead != null}"

    /**
     * One `getsockopt` for the connection's life; a failed query is not cached. A close() landing between
     * `readRaw`'s open check and here makes it throw, mapped like every platform IOException to this side's close.
     */
    private val receiveBuffer: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            socket.getOption(StandardSocketOptions.SO_RCVBUF)
        } catch (e: IOException) {
            throw wrapJvmException(e)
        }
    }

    private val writeMutex = Mutex()

    /**
     * The single outstanding read, if one is in flight — enforces single-flight (§3.2), and is the
     * *orphaned* read (RFC §3.2): the JDK's timed `AsynchronousSocketChannel.read` is destructive — an
     * elapsed timeout arms `readKilled` and kills the read half. To enforce a deadline **non-destructively**
     * the read is started with **no** JDK timeout ([Duration.INFINITE] semantics: the untimed overload) and
     * is completed straight from the JDK's completion handler, while the caller
     * `withTimeout`s only the *wait*. On timeout the wait is abandoned; the read (and the receive buffer it
     * owns) survives to be re-awaited by the next `read()`. Closing the channel completes it exceptionally.
     */
    private var inFlightRead: InFlightRead? = null

    /**
     * An orphaned read: the pooled [buffer] a read started on the channel fills, and where that read's outcome
     * waits for whoever awaits it. The buffer's lifetime **follows the read, not the caller** — it is freed only
     * once the read completes (fails / EOF), never on a caller timeout, so a caller that gave up waiting can't
     * free a buffer a live read is still writing into (the QUIC-JNI UAF class, §3.2).
     *
     * This is the JDK's completion handler itself, and it never resumes a continuation inline: the JDK runs it on
     * its own pool thread, which must not run a caller's code, so [resumeOffThisThread] resumes the awaiter
     * through its own dispatcher (one hand-off, the awaiter's) and moves one with no real dispatcher to
     * `Dispatchers.Default`.
     */
    private class InFlightRead(
        val buffer: PlatformBuffer,
    ) : CompletionHandler<Int, Unit> {
        private sealed interface State {
            /** The read is outstanding and nobody waits for it. */
            data object Pending : State

            class Waiting(
                val continuation: CancellableContinuation<Int>,
            ) : State

            class Done(
                val outcome: Result<Int>,
            ) : State
        }

        private val state = AtomicReference<State>(State.Pending)

        override fun completed(
            result: Int,
            attachment: Unit,
        ) = finish(Result.success(result))

        override fun failed(
            exc: Throwable,
            attachment: Unit,
        ) = finish(Result.failure(wrapJvmException(exc)))

        private fun finish(outcome: Result<Int>) {
            val done = State.Done(outcome)
            while (true) {
                when (val current = state.get()) {
                    State.Pending -> if (state.compareAndSet(current, done)) return
                    is State.Waiting ->
                        if (state.compareAndSet(current, done)) {
                            current.continuation.resumeOffThisThread(outcome)
                            return
                        }
                    is State.Done -> return
                }
            }
        }

        /** The bytes read (negative at end of stream), or the failure. Cancelling the wait leaves the read outstanding. */
        suspend fun await(): Int =
            suspendCancellableCoroutine { continuation ->
                while (true) {
                    when (val current = state.get()) {
                        State.Pending -> {
                            val waiting = State.Waiting(continuation)
                            if (state.compareAndSet(current, waiting)) {
                                continuation.invokeOnCancellation { state.compareAndSet(waiting, State.Pending) }
                                return@suspendCancellableCoroutine
                            }
                        }
                        // Already finished: this is the caller's own thread, so resuming here runs nothing foreign.
                        is State.Done -> {
                            continuation.resumeWith(current.outcome)
                            return@suspendCancellableCoroutine
                        }
                        is State.Waiting -> throw SocketIOException("A second reader awaited an orphaned read that already has a waiter")
                    }
                }
            }
    }

    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(deadline: Duration): ReadResult =
        offTheAndroidMainThread(onAbandoned = { it.freeIfData() }) { translateRead { readRaw(deadline) } }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(deadline) }
        // The JDK's timed read is destructive (`readKilled`), so route the plain read through the
        // orphaned-read single-flight (RFC §3.2) instead — enforced *and* non-destructive.
        return orphanedReadRaw(deadline)
    }

    /**
     * The non-destructive read (RFC §3.2 orphaned-read single-flight). Enforces [deadline] by racing a
     * `withTimeout` against a read that owns its receive buffer and carries **no** JDK
     * timeout (so `readKilled` is never armed):
     *
     *  - **Enforced (Axis 1):** the caller waits at most [deadline] for the background read; on expiry
     *    it throws [SocketTimeoutException] rather than blocking forever.
     *  - **Non-destructive (Axis 2):** the timeout abandons only the *wait*. The background read keeps
     *    still pending in the channel with its buffer intact, left in [inFlightRead] for the next
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
                        inFlight.await()
                    } else {
                        withTimeout(deadline) { inFlight.await() }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Our deadline elapsed: orphan the read (keep the buffer + single-flight state) and
                    // abandon only the wait. The next read() re-awaits this same background read.
                    throw SocketTimeoutException(TimeoutContext.Read(deadline))
                } catch (e: CancellationException) {
                    // The waiter was cancelled from outside; the read still owns the buffer, so leave it
                    // orphaned for the next read() and let the cancellation propagate. (A close() is not a
                    // cancellation here: it fails the read itself, below.)
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
                throw endOfStream("Received $bytesRead from server indicating a socket close.")
            }
            inFlight.buffer.resetForRead()
            inFlight.buffer
        }

    /**
     * Starts the read that backs [orphanedReadRaw]. It runs with the untimed overload, i.e. a
     * [Duration.INFINITE] deadline on purpose: enforcement is the caller's `withTimeout`, never the
     * JDK's destructive timeout — the read must genuinely stay outstanding so it survives a caller
     * timeout (RFC §3.2).
     */
    private fun startOrphanedRead(): InFlightRead {
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(receiveBuffer))
        // Started right here and completed by the JDK's completion handler: no coroutine is launched for the
        // read, so there is no dispatch to start it and none to resume it.
        val read = InFlightRead(buffer)
        try {
            socket.read((buffer.unwrapFully() as BaseJvmBuffer).byteBuffer, Unit, read)
        } catch (e: Throwable) {
            // Nothing is reading into it: the buffer goes back, whatever the throw was.
            buffer.freeNativeMemory()
            throw if (e is IOException) wrapJvmException(e) else e
        }
        return read
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            try {
                readMutex.withLock {
                    offTheAndroidMainThread { socket.aRead(buffer.byteBuffer, timeout) }
                }
            } catch (e: IOException) {
                // Route every platform IOException through the single mapper. The async
                // failed() callback also wraps; this catches synchronous throws from
                // channel setup and passes already-wrapped SocketException through.
                throw wrapJvmException(e)
            }
        if (bytesRead < 0) {
            throw endOfStream("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        offTheAndroidMainThread {
            if (!isOpen) throw SocketClosedException.General("Socket is closed.")
            tlsHandler?.let { return@offTheAndroidMainThread it.wrap(buffer.unwrapFully() as BaseJvmBuffer, deadline) }
            BytesWritten(rawSocketWrite(buffer, deadline))
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
        } catch (e: SocketTimeoutException) {
            // An opt-in Bounded(d) write blew its deadline: the JDK timed write's InterruptedByTimeout
            // has already been mapped to SocketTimeoutException by asyncIOHandler().failed(). This is
            // DESTRUCTIVE (RFC_WRITE_TIMEOUT_CONTRACT §4): the send buffer is wedged and the connection's
            // write capacity is gone, so auto-close, then surface the uniform typed Write timeout. An
            // UntilClosed/infinite write uses the untimed overload and never reaches here.
            close()
            throw SocketTimeoutException(TimeoutContext.Write(timeout), cause = e)
        } catch (e: IOException) {
            // Route every platform IOException (Broken pipe, Connection reset, etc.)
            // through the single mapper. The async failed() callback also wraps; this
            // catches synchronous throws and passes already-wrapped SocketException through.
            throw wrapJvmException(e)
        }
        return totalWritten
    }

    override suspend fun close() {
        // Closing the channel (in super.close) is what completes the outstanding JDK read, exceptionally,
        // so no read leaks past close.
        offTheAndroidMainThread { super.close() }
    }
}
