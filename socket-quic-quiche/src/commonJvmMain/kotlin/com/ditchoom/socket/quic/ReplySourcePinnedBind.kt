@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CancellationException

/**
 * Bind the server's UDP channel so its replies carry the address the client dialled (#556).
 *
 * Three cases, and only the last one does anything unusual:
 *
 *  - **A shared port** ([QuicPortBinding.Shared]) — the demultiplexer owns the socket and chose how
 *    to bind it. Not ours to change.
 *  - **An explicit host** — already pinned, by the bind itself. A bound socket cannot send from
 *    another address, so there is nothing to fix.
 *  - **The wildcard** — the defective case. The kernel picks each reply's source and the choice
 *    differs per OS; see [PerLocalAddressServerChannel] for the measurements.
 *
 * ## Why this is not chosen by capability (yet)
 *
 * [com.ditchoom.buffer.flow.DatagramCapabilities.sourceAddressSelect] is the flag that would let a
 * backend with `IP_PKTINFO` take a one-socket path instead. It is deliberately **not** consulted
 * here. Pinning through cmsg only works if the server *asks* for the source on every send
 * (`DatagramSendOptions.fromLocal`, taken from quiche's `send_info.from`), and the shared server does
 * not yet do that — it sends `to` the peer and nothing else, and feeds quiche one fixed
 * `recv_info.to` so quiche could not supply a per-path `from` even if asked. Selecting the
 * one-socket path on the flag alone would therefore re-open the defect, silently, on the first
 * backend to report it. The switch belongs with that plumbing, which is the Apple/Linux half of
 * #556. On JVM the composite is the whole answer today: NIO has no cmsg, and never will.
 *
 * ## Cost, stated plainly
 *
 * The composite puts a coroutine hand-off on the server's receive path — one rendezvous per
 * datagram, because N sockets have to be multiplexed and the channel interface offers no shared
 * selector. That is the price of not having `IP_PKTINFO` on this platform.
 */
internal suspend fun QuicPortBinding.openReplySourcePinnedServerChannel(recvBufPool: BufferPool): AddressedDatagramChannel {
    if (this !is QuicPortBinding.Own || host != null) return openServerChannel(recvBufPool)

    val addresses = enumerateLocalUnicastAddresses().distinct()
    // Nothing to enumerate (an isolated container with every interface down) leaves the wildcard as
    // the only thing that can be bound. It is the defective behaviour, but a server that refuses to
    // start is worse than one whose replies may pick the wrong source on a host that has one address.
    if (addresses.isEmpty()) return openServerChannel(recvBufPool)

    return PerLocalAddressServerChannel.of(bindEachAddress(addresses, port, recvBufPool))
}

/**
 * Bind every address in [addresses] to one shared port.
 *
 * A fixed [port] is bound directly. An ephemeral one has to be *discovered and then claimed*: bind
 * the first address on 0, learn the number, and take that same number on the rest — which can lose,
 * because the port was only ever reserved on one address (the hazard #434 recorded from the other
 * side: a kernel-assigned port still collides on the 4-tuple). A loss is retried with a fresh port
 * rather than failed, since another draw almost always wins.
 */
private suspend fun bindEachAddress(
    addresses: List<String>,
    port: Int,
    recvBufPool: BufferPool,
): List<AddressedDatagramChannel> {
    var lastFailure: Throwable? = null
    repeat(if (port == 0) EPHEMERAL_PORT_ATTEMPTS else 1) {
        val bound = mutableListOf<AddressedDatagramChannel>()
        try {
            for (address in addresses) {
                val target = if (port != 0) port else bound.firstOrNull()?.localAddress?.port ?: 0
                bound += UdpSocket.bind(localHost = address, localPort = target, bufferFactory = recvBufPool)
            }
            return bound
        } catch (e: CancellationException) {
            for (b in bound) runCatching { b.close() }
            throw e
        } catch (e: Throwable) {
            // Partial binds hold the port on the addresses that did succeed; releasing them is what
            // makes the next attempt a fresh draw rather than a rerun of the same collision.
            for (b in bound) runCatching { b.close() }
            lastFailure = e
        }
    }
    throw IllegalStateException(
        "could not bind ${addresses.size} local address(es) to one shared port after " +
            "$EPHEMERAL_PORT_ATTEMPTS attempts; the last failure is the cause",
        lastFailure,
    )
}

private const val EPHEMERAL_PORT_ATTEMPTS = 5
