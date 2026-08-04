package com.ditchoom.socket.udp

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.Datagram
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.Ecn
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.HopLimit
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 65535 − 8 (UDP header) − 20 (IPv4 header). Large enough that no real datagram is truncated. */
private const val MAX_UDP_PAYLOAD = 65507

/**
 * Shared Node.js datagram machinery over a `dgram.Socket` — the RFC Phase 4 net-new actual (nothing to
 * lift; `dgram` is the only backend with no quiche ancestor). Cleaned to the public datagram shape:
 *
 * - **per-packet source exposed** — the `message` event carries `rinfo`, surfaced as [Datagram.peer].
 * - **event → suspend bridge** — inbound datagrams push into an UNLIMITED [Channel]; [receive] drains
 *   it. A cancelled [receive] (e.g. a `withTimeout` expiry) cancels the channel wait WITHOUT closing
 *   the socket, matching the JVM/native cancel≠close contract.
 * - **control plane at the Node ceiling (§7.1 Node row)** — `dgram` exposes only `setTTL`, so
 *   [capabilities] advertises `hopLimitSend` alone; every other send field is a no-op and every
 *   read-side field degrades to its §7.2 typed absent state ([Ecn.Unknown] / [HopLimit.Unknown] /
 *   [LocalAddress.Unknown]).
 *
 * Not thread-safe (Node is single-threaded anyway); confine [receive] and sends each to one coroutine,
 * per the buffer-flow contract. The addressing mode is fixed at construction by the final wrapper —
 * [ConnectedNodeDatagramChannel] (send targets the `dgram` connected peer) or
 * [AddressedNodeDatagramChannel] (every send names its destination) — so a destination-less send on an
 * unconnected socket is unrepresentable, not a runtime error.
 */
@ExperimentalDatagramApi
internal abstract class NodeDatagramChannelCore(
    protected val socket: DgramSocket,
) : DatagramChannel {
    private var closed = false

    // UNLIMITED so a burst of `message` events never blocks the event loop; receive() drains in order.
    private val incoming = Channel<DatagramReadResult>(Channel.UNLIMITED)

    override val isOpen: Boolean get() = !closed

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
                        // All five args explicit: a defaulted localAddress rides the default-args
                        // bridge and boxes the value class (see LocalAddress's KDoc).
                        Datagram(
                            payload = payload,
                            peer = SocketAddress.ofLiteral(rinfo.address, rinfo.port),
                            ecn = Ecn.Unknown,
                            localAddress = LocalAddress.Unknown,
                            hopLimit = HopLimit.Unknown,
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

    /**
     * Shared send path: [target] is the wire destination for the addressed wrapper, or `null` for the
     * connected wrapper (the destination-less `dgram.send` overload routes to the connected peer).
     */
    protected suspend fun sendPayload(
        payload: ReadBuffer,
        target: SocketAddress?,
        options: DatagramSendOptions,
    ) {
        check(!closed) { "sink is closed" }
        applyControlPlane(options)
        // asUint8ArrayForSend() views the remaining bytes without consuming them, so no slice() is
        // needed to honor send-does-not-consume — and taking one would be a leak: on a pooled payload
        // ReadBuffer.slice() returns a TrackedSlice holding a reference on the chunk, which this path
        // has nowhere to release, pinning one chunk out of the pool per send (#277).
        val length = payload.remaining()
        val msg = payload.asUint8ArrayForSend()
        suspendCancellableCoroutine { cont ->
            val callback: (Any?) -> Unit = { error ->
                if (!cont.isCompleted) {
                    if (error != null) {
                        cont.resumeWithException(DatagramSendException(nodeSendError(error, length)))
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
            if (target != null) {
                socket.send(msg, 0, length, target.port, target.host, callback)
            } else {
                // Connected wrapper only, by construction — the base type cannot reach this path.
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
}

/**
 * The **connected** (one fixed [peer]) Node channel — what `UdpSocket.connect()` returns. Sends have no
 * destination parameter; the destination-less `dgram.send` overload routes to the `connect()`ed peer.
 */
@ExperimentalDatagramApi
internal class ConnectedNodeDatagramChannel(
    socket: DgramSocket,
    override val peer: SocketAddress,
) : NodeDatagramChannelCore(socket),
    ConnectedDatagramChannel {
    // socket.address() throws if the socket is unbound — failing here IS the construct-time contract.
    override val localAddress: LocalAddress =
        LocalAddress.of(socket.address().let { SocketAddress.ofLiteral(it.address, it.port) })

    override suspend fun send(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ) = sendPayload(payload, target = null, options)
}

/**
 * The **addressed** (many peers) Node channel — what `UdpSocket.bind()` returns. Every send names its
 * destination; [localAddress] is plainly non-null (a bound `dgram` socket always reports it).
 */
@ExperimentalDatagramApi
internal class AddressedNodeDatagramChannel(
    socket: DgramSocket,
) : NodeDatagramChannelCore(socket),
    AddressedDatagramChannel {
    // socket.address() throws if the socket is unbound — failing here IS the construct-time contract.
    override val localAddress: SocketAddress =
        socket.address().let { SocketAddress.ofLiteral(it.address, it.port) }

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) = sendPayload(payload, to, options)
}

/**
 * Convert a `dgram.send` callback error into the typed [DatagramSendError] set.
 *
 * Node reports the POSIX condition as a string `code` on the error object. Reading that at the platform
 * boundary is a conversion, not stringly-typed error handling — the string dies here and a typed value
 * leaves. Anything unrecognized keeps the original error as the cause rather than being flattened into
 * a message.
 */
private fun nodeSendError(
    error: Any?,
    attempted: Int,
): DatagramSendError =
    when (error.asDynamic().code as? String) {
        "EMSGSIZE" -> DatagramSendError.TooLarge(attempted, MAX_UDP_PAYLOAD)
        "EHOSTUNREACH", "ENETUNREACH", "EAFNOSUPPORT" -> DatagramSendError.Unreachable(errno = 0)
        "EACCES" -> DatagramSendError.NotPermitted(errno = 0)
        "EAGAIN", "ENOBUFS" -> DatagramSendError.WouldBlock
        else -> DatagramSendError.Transport(NodeSendFailure(error.toString()))
    }

/** Carrier for a `dgram.send` error that is not one of the recognized POSIX conditions. */
internal class NodeSendFailure(
    message: String,
) : RuntimeException(message)
