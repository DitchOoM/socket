@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.UdpBindError
import com.ditchoom.socket.udp.UdpBindException
import com.ditchoom.socket.udp.UdpSocket

/**
 * Bind the server's UDP channel so its replies carry the address the client dialled.
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
 * ## Why this is not chosen by capability
 *
 * [com.ditchoom.buffer.flow.DatagramCapabilities.sourceAddressSelect] is the flag that would let a
 * backend with `IP_PKTINFO` take a one-socket path instead. It is deliberately **not** consulted
 * here: no JVM backend has cmsg — NIO cannot express `IP_PKTINFO` — so the flag can only ever be
 * false on this platform and the composite is the whole answer.
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

    return when (val bind = bindEachAddress(addresses, port) { host, port -> bindServerMember(host, port, recvBufPool) }) {
        is SharedPortBind.Bound -> PerLocalAddressServerChannel.of(bind.members)
        // Every enumerated address left the host before it could be bound: the same position as an
        // empty enumeration, answered the same way.
        is SharedPortBind.NoAddressAvailable -> openServerChannel(recvBufPool)
    }
}

/**
 * One member of the composite: a socket on one local address, staging each datagram at the QUIC
 * datagram size exactly as [openServerChannel]'s single socket does — not at the 64 KB UDP ceiling,
 * which would make every buffer the receive pool hands out and keeps that size.
 */
internal suspend fun bindServerMember(
    host: String,
    port: Int,
    recvBufPool: BufferPool,
): AddressedDatagramChannel =
    UdpSocket.bind(
        localHost = host,
        localPort = port,
        receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
        bufferFactory = recvBufPool,
    )

/** What [bindEachAddress] holds: every address that is still this host's, on one port — or none of them. */
internal sealed interface SharedPortBind {
    /**
     * Enumerated addresses the kernel refused as not this host's (`EADDRNOTAVAIL`) by the time of the
     * bind: a rotated IPv6 temporary address, an interface torn down, an address still in duplicate
     * address detection. Not served — no socket can receive on them — and named here.
     */
    val unavailable: List<UdpBindException>

    /** [members] share one port, one socket per address that could be bound. */
    class Bound(
        val members: List<AddressedDatagramChannel>,
        override val unavailable: List<UdpBindException>,
    ) : SharedPortBind

    /** Every enumerated address was [unavailable]. */
    class NoAddressAvailable(
        override val unavailable: List<UdpBindException>,
    ) : SharedPortBind
}

/**
 * Bind every address in [addresses] to one shared port, through [bind].
 *
 * A fixed [port] is bound directly on each address, and taken on any one of them is that address's
 * refusal. An ephemeral one is **drawn and then claimed**: the first address binds port 0, and the
 * rest take the number it was given. That can collide, because the port was only proven free on the
 * address that drew it; a collision releases everything and draws again **from the address that
 * collided**, which is handed a port free exactly where the last one was not. Bounded.
 *
 * Each refusal is read by its [UdpBindError], because only one kind is worth another draw:
 *
 *  - [UdpBindError.AddressInUse] — a collision: redrawn (ephemeral) or thrown (fixed).
 *  - [UdpBindError.LocalAddressUnavailable] — the address is no longer this host's. No port fixes
 *    that, so it is dropped and named in [SharedPortBind.unavailable]; the rest are served.
 *  - anything else — thrown at once.
 *
 * A refusal is thrown as the [UdpBindException] naming the address and port it happened on, with
 * each earlier draw's collision attached as suppressed. Nothing is left bound when it is thrown.
 */
internal suspend fun bindEachAddress(
    addresses: List<String>,
    port: Int,
    bind: suspend (host: String, port: Int) -> AddressedDatagramChannel,
): SharedPortBind {
    val unavailable = mutableListOf<UdpBindException>()
    val collisions = mutableListOf<UdpBindException>()
    var order = addresses
    repeat(if (port == 0) EPHEMERAL_PORT_DRAWS else 1) {
        val bound = mutableListOf<AddressedDatagramChannel>()
        try {
            for (address in order) {
                if (unavailable.any { it.host == address }) continue
                val target = if (port != 0) port else bound.firstOrNull()?.localAddress?.port ?: 0
                try {
                    bound += bind(address, target)
                } catch (e: UdpBindException) {
                    if (e.error !is UdpBindError.LocalAddressUnavailable) throw e
                    unavailable += e
                }
            }
        } catch (e: Throwable) {
            // Partial binds hold the port on the addresses that did succeed; releasing them is what
            // makes the next draw a fresh one rather than a rerun of the same collision.
            for (b in bound) runCatching { b.close() }
            if (port != 0 || e !is UdpBindException || e.error !is UdpBindError.AddressInUse) {
                collisions.forEach { e.addSuppressed(it) }
                throw e
            }
            collisions += e
            order = listOf(e.host) + order.filterNot { it == e.host }
            return@repeat
        }
        return if (bound.isEmpty()) SharedPortBind.NoAddressAvailable(unavailable) else SharedPortBind.Bound(bound, unavailable)
    }
    val last = collisions.last()
    collisions.dropLast(1).forEach { last.addSuppressed(it) }
    throw last
}

/**
 * Bounded redraws for an ephemeral shared port. Each draw starts from the address the last one
 * collided on, so a run of them means the port space is crowded on several addresses at once.
 */
private const val EPHEMERAL_PORT_DRAWS = 8
