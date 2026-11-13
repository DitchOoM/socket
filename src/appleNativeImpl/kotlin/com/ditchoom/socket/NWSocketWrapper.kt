package com.ditchoom.socket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.nwhelpers.SocketErrorTypeDns
import com.ditchoom.socket.nwhelpers.SocketErrorTypePosix
import com.ditchoom.socket.nwhelpers.SocketErrorTypeTls
import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_force_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_local_port
import com.ditchoom.socket.nwhelpers.nw_helper_remote_port
import com.ditchoom.socket.nwhelpers.nw_helper_send_tcp
import com.ditchoom.socket.nwhelpers.nw_helper_tcp_receive
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Network.nw_connection_t
import platform.posix.ECONNABORTED
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlin.time.Duration

/**
 * Base socket wrapper using Apple's Network.framework with zero-copy buffer integration.
 *
 * Read operations return [ReadBuffer] backed by NSData without copying data.
 * Write operations pass NSData directly to Network.framework without copying.
 */
@OptIn(ExperimentalForeignApi::class)
open class NWSocketWrapper(
    /** Injected-once configuration, supplied at `allocate(config)` / accept time. */
    internal val config: TransportConfig = TransportConfig(),
) : ClientSocket {
    internal var connection: nw_connection_t = null
        set(value) {
            field = value
            holding = if (value == null) Holding.Unset else Holding.Connection(NwConnectionRelease(value))
        }

    /** What [connection] is released through: by [closeInternal], or by the collector once this socket is unreachable. */
    private var holding: Holding = Holding.Unset

    private sealed interface Holding {
        data object Unset : Holding

        @OptIn(ExperimentalNativeApi::class)
        class Connection(
            val release: NwConnectionRelease,
        ) : Holding {
            /** Runs [NwConnectionRelease.cancel] when the owning socket is collected without having been closed. */
            private val cleaner = createCleaner(release) { it.cancel() }
        }
    }

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    /**
     * The single outstanding native receive, if one is in flight — enforces single-flight (RFC §3.2).
     * Network.framework has no per-receive cancel, so a `nw_connection_receive` can't be abandoned;
     * instead the one-shot completion is captured here and, on a caller timeout, left outstanding for
     * the next `read()` to re-await. Non-destructive: a deadline does not tear down the connection.
     */
    private var inFlightRead: CompletableDeferred<ReadBuffer>? = null

    @Volatile
    internal var closedLocally = false

    @Volatile
    internal var connectionReady = false

    override val readPolicy: ReadPolicy get() = config.readPolicy
    override val writePolicy: WritePolicy get() = config.writePolicy

    override val isOpen: Boolean get() = !closedLocally && connectionReady

    override suspend fun localPort(): Int =
        connection?.let {
            nw_helper_local_port(it).toInt()
        } ?: -1

    override suspend fun remotePort(): Int =
        connection?.let {
            nw_helper_remote_port(it).toInt()
        } ?: -1

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    /**
     * Zero-copy read operation (RFC §3.2 orphaned-read single-flight). Returns a buffer backed by
     * NSData received from Network.framework, enforcing [deadline] **non-destructively**:
     *
     *  - **Enforced (Axis 1):** the caller waits at most [deadline] for the receive; on expiry it
     *    throws [SocketTimeoutException] rather than blocking forever.
     *  - **Non-destructive (Axis 2):** a timeout abandons only the *wait*. The native receive stays
     *    outstanding (Network.framework has no per-receive cancel) and its result is left in
     *    [inFlightRead] for the next `read()` to re-await — no data is lost and the connection is not
     *    cancelled. `closeInternal()` runs only on a genuine EOF / error / [close], never on a timeout.
     *  - **Buffer safety (§3.2):** the NSData buffer is materialized only when the receive callback
     *    fires, so a gave-up caller can never free a buffer a live receive is still filling.
     */
    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val conn = connection ?: throw SocketClosedException.General("Socket is closed")
        return readMutex.withLock {
            val pending = inFlightRead ?: startReceive(conn).also { inFlightRead = it }
            val buffer =
                try {
                    if (deadline.isInfinite()) {
                        pending.await()
                    } else {
                        withTimeout(deadline) { pending.await() }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Deadline elapsed: leave the native receive outstanding (single-flight) and abandon
                    // only the wait — the next read() re-awaits this same receive. Do NOT cancel the
                    // connection (RFC §3.2, Axis 2). withTimeout leaks kotlinx's
                    // TimeoutCancellationException; surface the uniform type (§4.1, Axis 3).
                    throw SocketTimeoutException(TimeoutContext.Read(deadline), cause = e)
                } catch (e: Throwable) {
                    // Only a receive that itself failed or hit EOF (its deferred completed exceptionally)
                    // ends single-flight: the next read() starts a fresh receive. A waiter cancelled from
                    // outside (Job.cancel, a race loser, scope shutdown) abandons only its wait, exactly
                    // like the deadline branch above: the receive is still outstanding in Network.framework
                    // and its bytes belong to the connection, so the next read() re-awaits it. Forgetting
                    // it here would post a second receive and the first one's bytes would be completed into
                    // a deferred nobody awaits.
                    if (pending.isCancelled) inFlightRead = null
                    throw e
                }
            inFlightRead = null
            buffer
        }
    }

    /**
     * Schedules the one-shot native receive that backs [readRaw] and captures its completion in a
     * [CompletableDeferred]. Runs no timeout of its own — enforcement is the caller's `withTimeout`
     * — so the receive stays outstanding across a caller timeout (RFC §3.2) and completes when the
     * peer finally sends, the stream ends, the connection errors, or [close] cancels it.
     */
    private fun startReceive(conn: nw_connection_t): CompletableDeferred<ReadBuffer> {
        val deferred = CompletableDeferred<ReadBuffer>()
        nw_helper_tcp_receive(conn, 1u, 65536u) { data, isComplete, errorDomain, errorCode, errorDesc ->
            when {
                data != null && data.length.toInt() > 0 -> {
                    // Zero-copy: wrap NSData directly using NSDataBuffer. NW can hand over the last
                    // bytes together with the error that ended the connection (a RST behind data); the
                    // bytes come first, and the next receive reports the error again on its own.
                    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                    buffer.position(data.length.toInt())
                    buffer.resetForRead()
                    deferred.complete(buffer)
                }
                // This side's close ended the receive: NW completes a pending receive on cancel with
                // no data and no error, or with ECANCELED on force-cancel. Neither is the peer's EOF.
                closedLocally -> deferred.completeExceptionally(SocketClosedException.General("Socket closed during read"))
                // The error before `is_complete`: NW reports a peer's RST as is_complete together with
                // ECONNRESET, and reading `is_complete` first turned the reset into a clean EOF.
                errorDomain != 0 -> {
                    closeInternal()
                    deferred.completeExceptionally(mapSocketException(errorDomain, errorDesc, errorCode = errorCode))
                }
                isComplete?.boolValue == true -> {
                    closeInternal()
                    deferred.completeExceptionally(SocketClosedException.EndOfStream())
                }
                else -> {
                    deferred.complete(EMPTY_BUFFER)
                }
            }
        }
        return deferred
    }

    /**
     * Zero-copy write operation.
     * Passes NSData directly to Network.framework without copying.
     *
     * Advances [buffer]'s position by the number of bytes accepted, matching the JVM NIO
     * contract (`socket.write(byteBuffer)` moves position). [ReadBuffer.toNSData] always
     * produces an NSData whose length equals `buffer.remaining()` at call time.
     */
    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val conn = connection ?: throw SocketClosedException.General("Socket is closed")
        val nsData = buffer.toNSData()
        val bytesToWrite = nsData.length.toInt()

        val bytesWritten =
            try {
                writeMutex.withLock {
                    // The default WritePolicy.UntilClosed passes an infinite deadline: suspend on
                    // back-pressure (RFC_WRITE_TIMEOUT_CONTRACT §1) without arming a timeout that could
                    // kill the connection. Only an opt-in Bounded(d) write bounds the send.
                    if (deadline.isInfinite()) {
                        performSend(conn, nsData)
                    } else {
                        withTimeout(deadline) { performSend(conn, nsData) }
                    }
                    // Committed here, on the awaiting caller, only once the send completed and this call
                    // is still the buffer's owner. The completion block runs on a dispatch thread at any
                    // time up to cancel, including after a cancelled write returned the buffer to its
                    // caller, so it must never touch the buffer.
                    buffer.position(buffer.position() + bytesToWrite)
                    bytesToWrite
                }
            } catch (e: TimeoutCancellationException) {
                // An opt-in Bounded(d) write blew its deadline. DESTRUCTIVE (RFC §4): Network.framework
                // has no per-send cancel, so cancel the connection to abandon the outstanding send, then
                // surface the uniform typed Write timeout. (No blanket invokeOnCancellation close here —
                // that would also kill an infinite write cancelled from outside, which must stay
                // non-destructive.)
                closeInternal()
                throw SocketTimeoutException(TimeoutContext.Write(deadline), cause = e)
            }
        return BytesWritten(bytesWritten)
    }

    /**
     * Issues a single native `nw_helper_send_tcp` and suspends until Network.framework reports the send
     * completed (or failed). Runs no timeout of its own — enforcement is the caller's `withTimeout` — so
     * an infinite (default) write simply suspends on back-pressure. The completion block captures only
     * the continuation, never the caller's buffer: a cancelled caller's send is already queued in
     * Network.framework and completes later, whoever owns the buffer by then.
     */
    private suspend fun performSend(
        conn: nw_connection_t,
        nsData: NSData,
    ): Unit =
        suspendCancellableCoroutine { continuation ->
            nw_helper_send_tcp(conn, nsData) { errorDomain, _, errorDesc ->
                if (errorDomain != 0) {
                    continuation.resumeWithException(
                        mapSocketException(errorDomain, errorDesc),
                    )
                } else {
                    continuation.resume(Unit)
                }
            }
        }

    internal fun closeInternal() {
        if (closedLocally) return
        closedLocally = true
        connectionReady = false
        when (val held = holding) {
            is Holding.Connection -> held.release.cancel()
            Holding.Unset -> Unit
        }
    }

    override suspend fun close() {
        closeInternal()
    }

    companion object {
        fun mapSocketException(
            errorDomain: Int,
            errorString: String?,
            hostname: String? = null,
            errorCode: Int = 0,
        ): SocketException {
            val message = errorString ?: "Socket error"
            val msgLower = message.lowercase()
            // The errno decides when NW gave one; the message text is the fallback below.
            if (errorDomain == SocketErrorTypePosix) {
                when (errorCode) {
                    ECONNRESET, ECONNABORTED -> return SocketClosedException.ConnectionReset(message)
                    EPIPE -> return SocketClosedException.BrokenPipe(message)
                }
            }
            return when (errorDomain) {
                SocketErrorTypeDns -> SocketUnknownHostException(hostname, message)
                SocketErrorTypeTls -> {
                    when {
                        // A certificate rejection is a distinct typed reason from a generic handshake
                        // failure — pick TlsBadCertificate when the NW/Sec error names a cert.
                        msgLower.contains("certificate") ||
                            msgLower.contains("cert") ||
                            msgLower.contains("trust") ->
                            SSLHandshakeFailedException(message, reason = ConnectionFailureReason.TlsBadCertificate)
                        msgLower.contains("handshake") ->
                            SSLHandshakeFailedException(message, reason = ConnectionFailureReason.TlsHandshake)
                        else -> SSLProtocolException(message)
                    }
                }
                SocketErrorTypePosix -> {
                    when {
                        // DNS failures can arrive as POSIX errors on macOS
                        // (e.g., EAI_NONAME = "nodename nor servname provided")
                        msgLower.contains("nodename") ||
                            msgLower.contains("servname") ||
                            msgLower.contains("name or service not known") ||
                            msgLower.contains("host not found") ->
                            SocketUnknownHostException(hostname, message)
                        // ENOMEM during establishment — a typed connect failure.
                        msgLower.contains("cannot allocate memory") || msgLower.contains("out of memory") ->
                            SocketConnectionException.Other(ConnectionFailureReason.OutOfMemory, message)
                        msgLower.contains("connection refused") || msgLower.contains("econnrefused") ->
                            SocketConnectionException.Refused(null, 0, platformError = message)
                        msgLower.contains("timed out") || msgLower.contains("timeout") ->
                            SocketTimeoutException(message)
                        msgLower.contains("reset") || msgLower.contains("connection abort") ->
                            SocketClosedException.ConnectionReset(message)
                        msgLower.contains("broken pipe") ->
                            SocketClosedException.BrokenPipe(message)
                        msgLower.contains("not connected") || msgLower.contains("socket is not connected") ->
                            SocketClosedException.BrokenPipe(message)
                        msgLower.contains("network") && msgLower.contains("unreachable") ->
                            SocketConnectionException.NetworkUnreachable(message)
                        msgLower.contains("host") && msgLower.contains("unreachable") ->
                            SocketConnectionException.HostUnreachable(message)
                        msgLower.contains("unreachable") ->
                            SocketConnectionException.NetworkUnreachable(message)
                        else -> SocketIOException(message)
                    }
                }
                else -> SocketIOException(message)
            }
        }
    }
}

/**
 * Cancels one `nw_connection_t`, once. A started connection holds its descriptor until it is cancelled,
 * so every path that ends the connection (a close, a failed read or write, the owning socket being
 * collected) goes through here.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwConnectionRelease(
    private val connection: nw_connection_t,
) {
    private val cancelled = AtomicInt(0)

    fun cancel() {
        if (!cancelled.compareAndSet(0, 1)) return
        val conn = connection ?: return
        nw_helper_cancel(conn)
        nw_helper_force_cancel(conn)
    }
}
