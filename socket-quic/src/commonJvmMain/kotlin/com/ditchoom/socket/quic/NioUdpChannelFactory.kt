package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

/**
 * JVM/Android [UdpChannelFactory]: opens a new [DatagramChannel] bound to a chosen
 * local address and connected to the same [peer] as the originating connection,
 * for active connection migration (slice 3). Mirrors the socket setup in
 * [commonJvmWithQuicConnection].
 */
internal class NioUdpChannelFactory(
    private val peer: InetSocketAddress,
    private val bufferFactory: BufferFactory,
) : UdpChannelFactory {
    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        val channel = DatagramChannel.open()
        channel.configureBlocking(false)
        // Bind the new local 4-tuple, then connect to the same peer. localHost null binds the
        // wildcard address; localPort 0 picks an ephemeral port — a fresh source either way.
        channel.bind(InetSocketAddress(localHost ?: "0.0.0.0", localPort))
        channel.connect(peer)
        val localAddr = channel.localAddress as InetSocketAddress

        val localSockAddr = localAddr.toNativeSockAddr(bufferFactory)
        return NewPath(
            channel = NioUdpChannel(channel),
            localSockAddrAddress = localSockAddr.address,
            localSockAddrLength = localSockAddr.length,
            release = { localSockAddr.free() },
        )
    }
}
