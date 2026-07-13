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
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    /**
     * The single outstanding native receive, if one is in flight — enforces single-flight (RFC §3.2).
     * Network.framework has no per-receive cancel, so a `nw_connection_receive` can't be abandoned;
     * instead the one-shot completion is captured here and, on a caller timeout, left outstanding for
     * the next `read()` to re-await. Non-destructive: a deadline no longer tears down the connection.
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
                    // The receive itself failed / hit EOF (deferred completed exceptionally) — drop the
                    // single-flight state so the next read() starts a fresh receive.
                    inFlightRead = null
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
        nw_helper_tcp_receive(conn, 1u, 65536u) { data, isComplete, errorDomain, _, errorDesc ->
            when {
                data != null && data.length.toInt() > 0 -> {
                    // Zero-copy: wrap NSData directly using NSDataBuffer
                    val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                    buffer.position(data.length.toInt())
                    buffer.resetForRead()
                    deferred.complete(buffer)
                }
                isComplete?.boolValue == true -> {
                    closeInternal()
                    deferred.completeExceptionally(SocketClosedException.EndOfStream())
                }
                errorDomain != 0 -> {
                    closeInternal()
                    deferred.completeExceptionally(mapSocketException(errorDomain, errorDesc))
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
                        performSend(conn, nsData, buffer, bytesToWrite)
                    } else {
                        withTimeout(deadline) { performSend(conn, nsData, buffer, bytesToWrite) }
                    }
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
     * an infinite (default) write simply suspends on back-pressure. On success advances [buffer]'s
     * position by [bytesToWrite], matching the JVM NIO write contract.
     */
    private suspend fun performSend(
        conn: nw_connection_t,
        nsData: NSData,
        buffer: ReadBuffer,
        bytesToWrite: Int,
    ): Int =
        suspendCancellableCoroutine { continuation ->
            nw_helper_send_tcp(conn, nsData) { errorDomain, _, errorDesc ->
                if (errorDomain != 0) {
                    continuation.resumeWithException(
                        mapSocketException(errorDomain, errorDesc),
                    )
                } else {
                    buffer.position(buffer.position() + bytesToWrite)
                    continuation.resume(bytesToWrite)
                }
            }
        }

    internal fun closeInternal() {
        if (closedLocally) return
        closedLocally = true
        connectionReady = false
        val conn = connection ?: return
        nw_helper_cancel(conn)
        nw_helper_force_cancel(conn)
    }

    override suspend fun close() {
        closeInternal()
    }

    companion object {
        fun mapSocketException(
            errorDomain: Int,
            errorString: String?,
            hostname: String? = null,
        ): SocketException {
            val message = errorString ?: "Socket error"
            val msgLower = message.lowercase()
            return when (errorDomain) {
                SocketErrorTypeDns -> SocketUnknownHostException(hostname, message)
                SocketErrorTypeTls -> {
                    when {
                        // A certificate rejection is a distinct typed reason from a generic handshake
                        // failure (issue #166) — pick TlsBadCertificate when the NW/Sec error names a cert.
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
                        // ENOMEM during establishment — a typed connect failure (issue #166).
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
