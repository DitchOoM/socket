@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.nwudp.nw_udp_cancel
import com.ditchoom.socket.quic.nwudp.nw_udp_receive
import com.ditchoom.socket.quic.nwudp.nw_udp_send
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Network.nw_connection_t
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * Apple (Kotlin/Native) [UdpChannel] over an `nw_connection_t` in **UDP mode** — the production Apple
 * QUIC client datapath. NWConnection (not a raw POSIX socket) keeps Apple's NWPath awareness so the
 * connection can follow a Wi-Fi<->cellular handoff, and gives a deterministic [close] via
 * `nw_connection_cancel`. quiche owns QUIC itself; this just moves datagrams.
 *
 * The connection must already be `ready` before construction (the engine waits on the state handler,
 * then reads the effective local/remote sockaddrs for quiche). One [receive]/[send] is outstanding at
 * a time (the driver's single reader loop + serialized writer), so each maps to one
 * `nw_connection_receive_message` / `nw_connection_send`.
 *
 * NW migration glue (feeding an NWPath change back to quiche as a path probe) is a tracked follow-up;
 * the baseline channel does send/receive/cancel. There is no POSIX fallback — the QUIC *server*
 * datapath is POSIX by design (see [AppleUdpServerChannel]), but the client is NW-only.
 */
internal class AppleNwUdpChannel(
    private val conn: nw_connection_t,
) : UdpChannel {
    // Completes when the NW connection has terminally failed/closed (a receive_message callback delivered
    // nil content or an error). Thread-safe (set on the libdispatch callback thread, read on the reader
    // coroutine). Used to PARK the reader rather than busy-return -1 forever — see [receive].
    private val terminal = CompletableDeferred<Unit>()

    /**
     * Receive one datagram into [buffer]. The `nw_connection_receive_message` callback fires on a
     * libdispatch (foreign) thread and does the byte copy there, so the buffer's lifetime MUST be fenced
     * against it: the reader loop frees its buffer as soon as [receive] returns, and an outstanding
     * callback writing into a freed pooled buffer would be a foreign-thread write-after-free. The callback
     * completes [done] exactly once (NW guarantees the handler runs once — on data, error, or cancel); we
     * only return after [done] resolves, so the copy is always finished before the buffer can be freed.
     *
     * Returns the datagram length, or -1 the FIRST time the connection is found closed/cancelled/errored.
     * After that the driver's reader loop (which retries on a non-positive read — io_uring legitimately
     * returns negative errnos on its 1s recv timeout) would re-enter immediately, and NW would fire the
     * callback again at once on a dead connection → CPU busy-spin. So once [terminal], we [awaitCancellation]
     * instead, parking the reader with zero CPU until the driver tears it down (close / idle timeout).
     */
    override suspend fun receive(buffer: PlatformBuffer): Int {
        if (terminal.isCompleted) awaitCancellation()
        val done = CompletableDeferred<Int>()
        nw_udp_receive(conn) { content, _, errorCode ->
            if (content == null || errorCode != 0) {
                terminal.complete(Unit)
                done.complete(-1)
            } else {
                val available = content.length.toInt()
                val len = if (available > buffer.capacity) buffer.capacity else available
                if (len > 0) {
                    val dst = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                    memcpy(dst, content.bytes, len.convert())
                }
                done.complete(len)
            }
        }
        return try {
            done.await()
        } catch (e: CancellationException) {
            // The reader loop was cancelled mid-receive. The pending callback WILL still fire — cancel the
            // connection so it fires with nil content (no copy), then wait non-cancellably for it to
            // complete before unwinding, so the caller never frees `buffer` while the callback could still
            // write into it. nw_udp_cancel is idempotent, so [close] running concurrently is harmless.
            nw_udp_cancel(conn)
            withContext(NonCancellable) { done.await() }
            throw e
        }
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        // Connected NW UDP channel — always sends to the connected peer; server-egress [dest] routing
        // (a migrated peer's new source) applies only to the POSIX server channel. nw_udp_send copies the
        // bytes into a dispatch_data buffer synchronously (DISPATCH_DATA_DESTRUCTOR_DEFAULT), so the
        // caller's buffer is safe to reuse/free the moment the call returns — only the completion is async.
        val ptr = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
        suspendCancellableCoroutine<Unit> { continuation ->
            nw_udp_send(conn, ptr, len) { _, _ ->
                // Best-effort, like a UDP datagram send: a transient NW send failure is non-fatal —
                // quiche's loss recovery retransmits. Resume unconditionally so the driver proceeds.
                continuation.resume(Unit)
            }
        }
    }

    override fun close() {
        nw_udp_cancel(conn)
    }
}
