package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    ): DatagramChannel {
        ensureNode()
        val socket = createDgramSocket(if (isIpv6(localHost)) UDP6 else UDP4)
        awaitBind(socket, localPort, localHost)
        return NodeDatagramChannel(socket, connectedPeer = null)
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
    ): DatagramChannel {
        ensureNode()
        // Resolve the peer out of band (numeric literal → no DNS), then pin it as the channel's fixed
        // peer. The socket family follows the resolved remote, so a v6 peer opens a udp6 socket.
        val peer = resolve(remoteHost, remotePort)
        val socket = createDgramSocket(if (peer.family == AddressFamily.IPv6) UDP6 else UDP4)
        awaitBind(socket, localPort, localHost)
        awaitConnect(socket, peer.port, peer.host)
        return NodeDatagramChannel(socket, connectedPeer = peer)
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
