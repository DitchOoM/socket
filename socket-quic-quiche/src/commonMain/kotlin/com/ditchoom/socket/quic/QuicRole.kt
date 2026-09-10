package com.ditchoom.socket.quic

/**
 * Which side of the QUIC protocol a [QuicheDriver] is.
 *
 * Decides RFC 9000 §2.1 stream-id parity, the role label in qlog and traces, and whether egress
 * names a [SendTarget.ServerReply] — the reply-source pin a wildcard-bound server needs (#556).
 *
 * Orthogonal to [DatagramIngress]: a driver's protocol role says nothing about who pulls its
 * datagrams off the wire, and every combination of the two is constructed in this repository.
 */
sealed interface QuicRole {
    /** The label this role carries into a qlog filename and a trace's role field. */
    val label: String

    data object Client : QuicRole {
        override val label = "client"
    }

    data object Server : QuicRole {
        override val label = "server"
    }
}

/**
 * Who pulls datagrams off the wire and hands them to quiche.
 *
 * Orthogonal to [QuicRole], and the reason the two cannot be collapsed into one flag:
 *
 * | role | ingress | who |
 * |---|---|---|
 * | [QuicRole.Client] | [DriverReaderLoop] | the production client on every platform |
 * | [QuicRole.Server] | [ExternalPump] | `SharedQuicheServer`, and `MigrationSim`'s server |
 * | [QuicRole.Server] | [DriverReaderLoop] | `SemanticSim`'s server, standing in for the real receive loop |
 * | [QuicRole.Client] | [ExternalPump] | the driver unit tests, whose `StubUdpChannel` never delivers |
 *
 * ⚠️ [ExternalPump] is what a **multi-path** server requires, not merely a convenience: quiche
 * recognises a client's new path only if the server hands it a `recv_info` whose `from` is that
 * datagram's real source, and a [DriverReaderLoop] has exactly one `recv_info` fixed at
 * construction. A server that must see two paths therefore cannot own its own ingress.
 */
sealed interface DatagramIngress {
    /** The driver runs its own `udpReaderLoop` against its [UdpChannel]. */
    data object DriverReaderLoop : DatagramIngress

    /** Somebody else receives and submits, with a `recv_info` per datagram source. */
    data object ExternalPump : DatagramIngress
}
