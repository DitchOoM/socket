@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.UdpSocket

/**
 * Resolve a [QuicPortBinding] into the channel [SharedQuicheServer] reads — binding a UDP socket, or
 * taking the one a demultiplexer already owns. Common to every platform: the bind wiring differs per
 * platform only in the config/API objects around it, never here.
 *
 * The [recvBufPool] is passed as the socket's own buffer factory in the owned case, so the kernel
 * lands each datagram straight in a pooled buffer the driver later frees back (the no-copy receive
 * path). A shared channel was allocated by its owner and carries that owner's factory instead —
 * which is why a demultiplexed port should be bound with the pool the QUIC server will use.
 */
internal suspend fun QuicPortBinding.openServerChannel(recvBufPool: BufferPool): AddressedDatagramChannel =
    when (this) {
        is QuicPortBinding.Own ->
            UdpSocket.bind(
                host,
                port,
                receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                bufferFactory = recvBufPool,
            )

        is QuicPortBinding.Shared -> channel
    }

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
