package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Node default. Note that `dgram` delivers each datagram as its own Node `Buffer` (copied out in
 * [NodeDatagramChannelCore]), so — like [receiveBufferSize] — an injected [BufferFactory] has no staging
 * buffer to allocate and is not consulted; this value only satisfies the common default.
 */
internal actual val defaultDatagramBufferFactory: BufferFactory = BufferFactory.Default

/**
 * Node.js [UdpSocket] over `dgram` (RFC Phase 4). The single JS target also compiles for the browser,
 * which has no raw UDP — so, exactly like root `:socket`'s TCP, every entry point throws
 * [UnsupportedOperationException] in the browser and only Node reaches `dgram` / `dns`.
 */
@ExperimentalDatagramApi
actual object UdpSocket {
    init {
        // Wire SocketAddress.resolve() to real DNS process-wide (resolved-only model, RFC §10.1). Guard
        // on Node so merely loading this module in a browser bundle doesn't touch `dns`.
        if (isNode()) SocketAddress.installResolver(NodeSocketAddressResolver)
    }

    actual suspend fun bind(
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): AddressedDatagramChannel {
        // receiveBufferSize and bufferFactory are ignored on Node: `dgram` delivers each datagram as its
        // own Node Buffer (copied out in NodeDatagramChannelCore), so there is no staging buffer to size
        // or allocate from an injected factory.
        ensureNode()
        return createDgramSocket(if (isIpv6(localHost)) UDP6 else UDP4).closedIfSetupFails {
            awaitBind(this, localPort, localHost)
            // Addressed by construction: the wrapper reads socket.address() (non-null once bound) eagerly.
            AddressedNodeDatagramChannel(this)
        }
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): ConnectedDatagramChannel {
        ensureNode()
        // Resolve the peer out of band (numeric literal → no DNS), then pin it as the channel's fixed
        // peer. The socket family follows the resolved remote, so a v6 peer opens a udp6 socket.
        val peer = resolve(remoteHost, remotePort)
        return createDgramSocket(if (peer.family == AddressFamily.IPv6) UDP6 else UDP4).closedIfSetupFails {
            awaitBind(this, localPort, localHost)
            awaitConnect(this, peer.port, peer.host)
            ConnectedNodeDatagramChannel(this, peer)
        }
    }

    actual suspend fun bindMulticast(
        port: Int,
        family: AddressFamily,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): MulticastDatagramChannel {
        ensureNode()
        val v6 = family == AddressFamily.IPv6
        // reuseAddr:true is the multicast-listener arrangement (several receivers share the group port).
        // Bind the wildcard of the family so joined groups on any interface are received.
        return createDgramMulticastSocket(if (v6) UDP6 else UDP4).closedIfSetupFails {
            awaitBind(this, port, null)
            val base = AddressedNodeDatagramChannel(this)
            MulticastNodeDatagramChannel(this, base, ipv6 = v6)
        }
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress {
        ensureNode()
        return SocketAddress.resolve(host, port)
    }

    private const val UDP4 = "udp4"
    private const val UDP6 = "udp6"

    private fun isIpv6(host: String?): Boolean = host != null && host.contains(':')

    private fun ensureNode() {
        if (!isNode()) throw UnsupportedOperationException("UDP is not supported in the browser")
    }
}

/** Node iff there is no `window` global (mirrors root `:socket`'s `Socket.kt` detection). */
private fun isNode(): Boolean = js("global.window") == null

/**
 * Turns a freshly created `dgram` socket into the [setup] result, and closes the socket if anything in
 * between is refused — so a refusal propagates with nothing left behind (#521).
 *
 * A `dgram` socket owns a libuv `udp` handle and its descriptor from `createSocket()`, before it is
 * bindable, connectable or wrappable, and every step after that can be refused: the bind (an endpoint
 * another socket holds, an address of the wrong family, a privileged port), the connect, the wrapper's
 * eager `socket.address()`. Node will not clean up after the refusal either — `dgram.js` carries a
 * literal `// Todo: close?` where it abandons the socket on a failed bind — so without this guard the
 * socket stayed open for the life of the process. Measured on Node v24.10.0 / macOS arm64, 32 refusals
 * of each kind, counted as `dgram.Socket` handles in `process._getActiveHandles()` (a count that tracks
 * `lsof -nP -a -p <pid> -i UDP` one for one):
 *
 * | refused at | leaked descriptors, unguarded | with this guard |
 * |---|---|---|
 * | `bind`, endpoint held | 32 of 32 | 0 |
 * | `bind` inside `connect` | 32 of 32 | 0 |
 * | `connect`, after its bind | 32 of 32 | 0 |
 * | `bindMulticast`, port held | 32 of 32 | 0 |
 *
 * **Why a plain `catch` is where the close belongs, given `dgram`'s two failure shapes.** A refusal
 * reaches us either as an `'error'` event a turn or more after the call (`EADDRINUSE`, `EACCES`,
 * `EINVAL`) or as a synchronous throw out of the `dgram` call itself (`ERR_SOCKET_BAD_PORT`) — but both
 * are funnelled through the same continuation: the event listener resumes it with the exception, and a
 * synchronous throw propagates out of the `suspendCancellableCoroutine` block. Neither can arrive after
 * the suspending call has already returned, so there is no error to observe outside this region and no
 * flag to carry. A cancellation lands here too, and closes the socket the same way — which the abandoned
 * version never did.
 *
 * `close()` is legal on a socket whose bind was refused (Node's `healthCheck` gates on the handle
 * existing, not on the bind state) and libuv releases the descriptor *inside* the `close()` call, not on
 * its callback — measured, `lsof` drops before the callback runs — so nothing here waits. The temporary
 * `'error'` listener [awaitBind] / [awaitConnect] leave registered is deliberately not removed: it
 * swallows anything the close stirs up, where a socket with no `'error'` listener would take the process
 * down with an unhandled event.
 *
 * The other three actuals already did this: the JVM/Android one closes the channel (#463), the Linux one
 * `close(fd)`s on either refusal, the Apple one cancels the `NWConnection`.
 */
private inline fun <T> DgramSocket.closedIfSetupFails(setup: DgramSocket.() -> T): T {
    try {
        return setup()
    } catch (refusal: Throwable) {
        try {
            close { }
        } catch (closeFailure: Throwable) {
            refusal.addSuppressed(closeFailure)
        }
        throw refusal
    }
}

/** Await a `dgram` bind: register a one-shot error listener, resolve on the `listening` callback. */
private suspend fun awaitBind(
    socket: DgramSocket,
    port: Int,
    address: String?,
) = suspendCancellableCoroutine { cont ->
    socket.on("error") { error ->
        if (!cont.isCompleted) cont.resumeWithException(UdpBindException(error.toString()))
    }
    val onBound: () -> Unit = {
        if (!cont.isCompleted) {
            socket.removeAllListeners() // drop the temp error listener; the channel re-registers its own
            cont.resume(Unit)
        }
    }
    if (address != null) socket.bind(port, address, onBound) else socket.bind(port, onBound)
}

/** Await a `dgram` connect to the (already-resolved, numeric) peer. */
private suspend fun awaitConnect(
    socket: DgramSocket,
    port: Int,
    address: String,
) = suspendCancellableCoroutine { cont ->
    socket.on("error") { error ->
        if (!cont.isCompleted) cont.resumeWithException(UdpBindException(error.toString()))
    }
    socket.connect(port, address) {
        if (!cont.isCompleted) {
            socket.removeAllListeners()
            cont.resume(Unit)
        }
    }
}

/** A `dgram` bind/connect fault surfaced on the socket's `error` event. */
internal class UdpBindException(
    message: String,
) : RuntimeException(message)
