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
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * `nw_connection_receive_message` / `nw_connection_send`. A nil receive (peer/cancel) returns -1, which
 * the driver's reader loop treats as a closed channel and exits.
 *
 * NW migration glue (feeding an NWPath change back to quiche as a path probe) is a tracked follow-up;
 * the baseline channel does send/receive/cancel. There is no POSIX fallback — the QUIC *server*
 * datapath is POSIX by design (see [AppleUdpServerChannel]), but the client is NW-only.
 */
internal class AppleNwUdpChannel(
    private val conn: nw_connection_t,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int =
        suspendCancellableCoroutine { continuation ->
            nw_udp_receive(conn) { content, _, errorCode ->
                if (content == null || errorCode != 0) {
                    // nil content = connection closed/cancelled; any error = treat as channel down so
                    // the driver's reader loop exits (quiche already retransmits transient losses).
                    continuation.resume(-1)
                } else {
                    val available = content.length.toInt()
                    val len = if (available > buffer.capacity) buffer.capacity else available
                    if (len > 0) {
                        val dst = buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                        memcpy(dst, content.bytes, len.convert())
                    }
                    continuation.resume(len)
                }
            }
        }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        // Connected NW UDP channel — always sends to the connected peer; server-egress [dest] routing
        // (a migrated peer's new source) applies only to the POSIX server channel.
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
