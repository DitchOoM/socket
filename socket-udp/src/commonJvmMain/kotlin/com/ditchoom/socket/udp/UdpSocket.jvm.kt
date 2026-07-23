package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel as NioChannel

/**
 * The JVM/Android default: [BufferFactory.Default] hands out a NIO-writable direct buffer (a
 * [com.ditchoom.buffer.BaseJvmBuffer] whose `byteBuffer` the channel receives into, and whose
 * `nativeAddress` downstream FFI reads) — the exact strategy [NioDatagramChannel] has always used.
 */
internal actual val defaultDatagramBufferFactory: BufferFactory = BufferFactory.Default

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
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): DatagramChannel {
        val channel = NioChannel.open()
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
        return NioDatagramChannel(channel, receiveBufferSize, bufferFactory)
    }

    actual suspend fun connect(
        remoteHost: String,
        remotePort: Int,
        localHost: String?,
        localPort: Int,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): DatagramChannel {
        // Resolve the peer out of band (numeric literal → no DNS), then pin it as the channel's fixed
        // peer. A `connect()`ed UDP socket only receives from — and `write()`s to — this address.
        val peer = resolve(remoteHost, remotePort).toInetSocketAddress()
        val channel = NioChannel.open()
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(localHost ?: WILDCARD, localPort))
        channel.connect(peer)
        return NioDatagramChannel(channel, receiveBufferSize, bufferFactory)
    }

    actual suspend fun bindMulticast(
        port: Int,
        family: AddressFamily,
        receiveBufferSize: Int,
        bufferFactory: BufferFactory,
    ): MulticastDatagramChannel {
        // Multicast needs a protocol-family-typed channel: DatagramChannel.join() throws on a channel
        // opened with the no-arg open(). SO_REUSEADDR before bind lets multiple listeners share the port.
        val v6 = family == AddressFamily.IPv6
        val channel = NioChannel.open(if (v6) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        channel.configureBlocking(false)
        channel.bind(InetSocketAddress(if (v6) WILDCARD_V6 else WILDCARD, port))
        val base = NioDatagramChannel(channel, receiveBufferSize, bufferFactory)
        return MulticastNioDatagramChannel(channel, base)
    }

    actual suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress = SocketAddress.resolve(host, port)

    private const val WILDCARD = "0.0.0.0"
    private const val WILDCARD_V6 = "::"
}
