package com.ditchoom.socket.udp

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Node.js [DatagramChannel] backed by a `dgram.Socket` — the RFC Phase 4 net-new actual (nothing to
 * lift; `dgram` is the only backend with no quiche ancestor). Cleaned to the public datagram shape:
 *
 * - **per-packet source exposed** — the `message` event carries `rinfo`, surfaced as [Datagram.peer].
 * - **event → suspend bridge** — inbound datagrams push into an UNLIMITED [Channel]; [receive] drains
 *   it. A cancelled [receive] (e.g. a `withTimeout` expiry) cancels the channel wait WITHOUT closing
 *   the socket, matching the JVM/native cancel≠close contract.
 * - **control plane at the Node ceiling (§7.1 Node row)** — `dgram` exposes only `setTTL`, so
 *   [capabilities] advertises `hopLimitSend` alone; every other send field is a no-op and every
 *   read-side field degrades to its §7.2 sentinel ([Ecn.Unknown] / `-1` / `null`).
 *
 * Not thread-safe (Node is single-threaded anyway); confine [receive] and [send] each to one coroutine,
 * per the buffer-flow contract. [connectedPeer] is non-null iff this channel was opened via
 * [UdpSocket.connect]; then `send(to = null)` is legal and targets the fixed peer.
 */
@ExperimentalDatagramApi
internal class NodeDatagramChannel(
    private val socket: DgramSocket,
    private val connectedPeer: SocketAddress?,
) : DatagramChannel {
    private var closed = false

    // UNLIMITED so a burst of `message` events never blocks the event loop; receive() drains in order.
    private val incoming = Channel<DatagramReadResult>(Channel.UNLIMITED)

    override val isOpen: Boolean get() = !closed

    override val localAddress: SocketAddress =
        socket.address().let { SocketAddress.ofLiteral(it.address, it.port) }

    /** The classic UDP payload ceiling (65535 − 8 UDP − 20 IP). Path-MTU/PMTUD is a consumer concern. */
    override val maxWritableSize: Int = MAX_UDP_PAYLOAD

    // Node `dgram` exposes exactly one control-plane knob: setTTL. No ECN/DSCP/DF send, no recv cmsg
    // (§7.1 Node column). Everything else degrades correctly per §7.2.
    override val capabilities: DatagramCapabilities =
        DatagramCapabilities(
            ecnSend = false,
            ecnReceive = false,
            dscpSend = false,
            dontFragment = false,
            hopLimitSend = true, // dgram.setTTL
            hopLimitReceive = false,
            localAddressReceive = false,
            sourceAddressSelect = false,
            multicast = false, // design-for, defer to Phase 5
        )

    init {
        socket.on("message") { msg, rinfo ->
            if (!closed) {
                val int8 = nodeBufferToInt8Array(msg)
                val payload = JsBuffer(int8)
                payload.position(int8.length)
                payload.resetForRead()
                incoming.trySend(
                    DatagramReadResult.Received(
                        Datagram(
                            payload = payload,
                            peer = SocketAddress.ofLiteral(rinfo.address, rinfo.port),
                            ecn = Ecn.Unknown,
                            localAddress = null,
                            hopLimit = -1,
                        ),
                    ),
                )
            }
        }
        // A dgram socket `error` makes the channel unusable (ICMP unreachable on a connected socket,
        // bind/send faults). Tear down so a parked receive() observes Closed rather than hanging.
        socket.on("error") { _ -> close() }
    }

    // setTTL is socket-wide (there is no per-datagram ancillary send path on Node), so apply only on
    // change to avoid a redundant syscall on every send.
    private var appliedTtl = -1

    private fun applyControlPlane(options: DatagramSendOptions) {
        // Only hopLimit is honored (capabilities.hopLimitSend). ECN/DSCP/DF/fromLocal are advisory or
        // correctness-critical caps Node lacks (advertised absent) — a documented no-op here, never a
        // silent wrong value.
        val ttl = options.hopLimit
        if (ttl in 1..255 && ttl != appliedTtl) {
            socket.setTTL(ttl)
            appliedTtl = ttl
        }
    }

    override suspend fun receive(): DatagramReadResult {
        if (closed) return DatagramReadResult.Closed()
        return try {
            incoming.receive()
        } catch (_: ClosedReceiveChannelException) {
            DatagramReadResult.Closed()
        }
    }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(!closed) { "sink is closed" }
        applyControlPlane(options)
        // slice() is an independent view over the remaining bytes, so the caller's buffer is not
        // consumed (send-does-not-consume). No extra copy for the concrete JsBuffer.
        val slice = payload.slice()
        val length = slice.remaining()
        val msg = slice.asUint8ArrayForSend()
        suspendCancellableCoroutine { cont ->
            val callback: (Any?) -> Unit = { error ->
                if (!cont.isCompleted) {
                    if (error != null) {
                        cont.resumeWithException(SendFailedException(error.toString()))
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            if (to != null) {
                socket.send(msg, 0, length, to.port, to.host, callback)
            } else {
                check(connectedPeer != null) { "no destination: send(to = null) requires a connected channel" }
                socket.send(msg, 0, length, callback)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        incoming.close() // releases a parked receive() with ClosedReceiveChannelException → Closed
        runCatching { socket.removeAllListeners() }
        runCatching { socket.close { } }
        runCatching { socket.unref() }
    }

    companion object {
        /** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
        private const val MAX_UDP_PAYLOAD = 65507
    }
}

/** A `dgram.send` callback surfaced a non-null error (the send itself failed, not an unreachable peer). */
internal class SendFailedException(
    message: String,
) : RuntimeException(message)
