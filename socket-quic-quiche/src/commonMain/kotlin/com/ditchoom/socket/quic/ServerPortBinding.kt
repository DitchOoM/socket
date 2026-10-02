@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.UdpSocket

/**
 * How a server channel decides the source address each reply leaves from.
 *
 * A UDP server on a multi-homed host must answer from the address the client sent to: a client whose
 * socket is `connect()`ed drops anything from another source, and NAT and firewall state key on it too.
 * A wildcard socket that leaves the choice to the kernel does not do that — Darwin answers a local
 * destination from that destination, Linux from the interface's primary address — so this is decided
 * per channel, from what the channel can do, and the server names a reply's source only where it is
 * honoured.
 */
internal sealed interface ReplySourcePinning {
    /** What a single socket can do, read from its bound address and its capabilities. */
    sealed interface OneSocket : ReplySourcePinning

    /**
     * One wildcard socket that reports the address each datagram was sent to and leaves from a named
     * source: `IP_PKTINFO` / `IPV6_PKTINFO`. Each reply names the address its path arrived on.
     */
    data object PerDatagram : OneSocket

    /** A socket bound to one address. Every reply leaves from it; there is nothing to name. */
    data object BoundAddress : OneSocket

    /**
     * A wildcard socket that can do neither half: the kernel picks each reply's source, which can be an
     * address the client did not dial. A reply names no source, because this socket would ignore it.
     */
    data object PlatformChooses : OneSocket

    /**
     * One socket per local address on one port. Each reply names the address its path arrived on, and
     * leaves by the socket bound to it.
     */
    data object SocketPerAddress : ReplySourcePinning
}

/** A server's UDP channel and how it pins each reply's source. */
internal class ServerChannel(
    val channel: AddressedDatagramChannel,
    val pinning: ReplySourcePinning,
)

/**
 * What a platform serves a wildcard bind with when the wildcard socket cannot pin its replies
 * ([ReplySourcePinning.PlatformChooses]). Receives the socket, and either keeps it or closes it and
 * returns its replacement.
 */
internal fun interface UnpinnedWildcard {
    suspend fun serve(
        wildcard: AddressedDatagramChannel,
        binding: QuicPortBinding.Own,
        recvBufPool: BufferPool,
    ): ServerChannel

    companion object {
        /** Keep the socket: the platform has nothing better, and says so. */
        val Keep = UnpinnedWildcard { wildcard, _, _ -> ServerChannel(wildcard, ReplySourcePinning.PlatformChooses) }
    }
}

/**
 * Resolve a [QuicPortBinding] into the channel [SharedQuicheServer] reads — binding a UDP socket, or
 * taking the one a demultiplexer already owns — and how that channel pins each reply's source.
 *
 * An owned bind is chosen by what the bound socket can do: a socket that can pin per datagram, or is
 * bound to one address, is served as is; a wildcard socket that can do neither goes to
 * [unpinnedWildcard]. A shared channel is its owner's, and is described rather than changed.
 *
 * The [recvBufPool] is passed as the socket's own buffer factory in the owned case, so the kernel
 * lands each datagram straight in a pooled buffer the driver later frees back (the no-copy receive
 * path). A shared channel was allocated by its owner and carries that owner's factory instead —
 * which is why a demultiplexed port should be bound with the pool the QUIC server will use.
 */
internal suspend fun QuicPortBinding.openServerChannel(
    recvBufPool: BufferPool,
    unpinnedWildcard: UnpinnedWildcard,
): ServerChannel =
    when (this) {
        is QuicPortBinding.Shared -> ServerChannel(channel, channel.replySourcePinning())
        is QuicPortBinding.Own -> {
            val socket = bindServerSocket(recvBufPool)
            when (val pinning = socket.replySourcePinning()) {
                ReplySourcePinning.PlatformChooses -> unpinnedWildcard.serve(socket, this, recvBufPool)
                ReplySourcePinning.PerDatagram, ReplySourcePinning.BoundAddress -> ServerChannel(socket, pinning)
            }
        }
    }

/** The one socket an owned binding names, staged at the QUIC datagram size from [recvBufPool]. */
internal suspend fun QuicPortBinding.Own.bindServerSocket(recvBufPool: BufferPool): AddressedDatagramChannel =
    UdpSocket.bind(
        host,
        port,
        receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
        bufferFactory = recvBufPool,
    )

/** What this one socket can do about a reply's source; see [ReplySourcePinning.OneSocket]. */
internal fun AddressedDatagramChannel.replySourcePinning(): ReplySourcePinning.OneSocket =
    when {
        !localAddress.isUnspecified() -> ReplySourcePinning.BoundAddress
        capabilities.localAddressReceive && capabilities.sourceAddressSelect -> ReplySourcePinning.PerDatagram
        else -> ReplySourcePinning.PlatformChooses
    }

/** `0.0.0.0` or `::`, however the platform spells it: a wildcard bind. */
internal fun SocketAddress.isUnspecified(): Boolean {
    val address = SocketAddress.ofLiteral(host.substringBefore('%'), 0)
    return address == UNSPECIFIED_V4 || address == UNSPECIFIED_V6
}

private val UNSPECIFIED_V4 = SocketAddress.ofLiteral("0.0.0.0", 0)
private val UNSPECIFIED_V6 = SocketAddress.ofLiteral("::", 0)

/**
 * The transport options this binding actually runs with.
 *
 * On a shared port, GREASE is forced off: RFC 9443 §3 requires an endpoint that demultiplexes QUIC
 * not to send the `grease_quic_bit` transport parameter (RFC 9287), because a peer thereby permitted
 * to grease the fixed bit sends short-header packets whose first byte falls in the DTLS and STUN
 * ranges — unclassifiable, and so undeliverable to any stack on the port. Forced rather than
 * validated for the same reason `forHttp3()` forces its transport prerequisite: a requirement that
 * only a doc comment enforces is one a caller discovers through a silent, intermittent outage.
 */
internal fun QuicPortBinding.transportOptionsFor(options: QuicOptions): QuicOptions =
    when (this) {
        is QuicPortBinding.Own -> options
        is QuicPortBinding.Shared -> if (options.enableGrease) options.copy(enableGrease = false) else options
    }
