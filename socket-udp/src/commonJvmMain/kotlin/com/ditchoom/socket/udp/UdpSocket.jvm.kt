package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel as NioChannel

/**
 * JVM/Android [UdpSocket] over NIO [NioChannel]. Shared by both platforms via `commonJvmMain` (NIO is
 * identical on the JVM and on Android's ART).
 */
@ExperimentalDatagramApi
actual object UdpSocket {
    init {
        // Installing the platform resolver here means merely referencing UdpSocket wires
        // SocketAddress.resolve() to real DNS for the whole process (resolved-only model, RFC §10.1).
        SocketAddress.installResolver(JvmSocketAddressResolver)
    }

    actual suspend fun bind(
        localHost: String?,
        localPort: Int,
    ): DatagramChannel {
        val channel = NioChannel.open()
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
        return NioDatagramChannel(channel)
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
    ): DatagramChannel {
        // Resolve the peer out of band (numeric literal → no DNS), then pin it as the channel's fixed
        // peer. A `connect()`ed UDP socket only receives from — and `write()`s to — this address.
        val peer = resolve(remoteHost, remotePort).toInetSocketAddress()
        val channel = NioChannel.open()
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
        channel.connect(peer)
        return NioDatagramChannel(channel)
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.resolve(host, port)

    private const val WILDCARD = "0.0.0.0"
}
