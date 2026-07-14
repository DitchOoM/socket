@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket

/**
 * Common [UdpChannelFactory] over `:socket-udp` (Phase 6). Opens a new connected [UdpSocket.connect]
 * channel to the same [peer], bound to a chosen local endpoint, for active connection migration —
 * replacing the per-platform `NioUdpChannelFactory` / `IoUringUdpChannelFactory`.
 *
 * [peer] is already resolved, so reconnecting passes its numeric [SocketAddress.host] and is a literal
 * parse to the same address (no DNS). The new path's local sockaddr is encoded via [codec] into pinned
 * native memory that the driver decodes into a [PathKey] to route datagrams to this socket; the driver
 * frees it via [NewPath.release] when the path is torn down.
 */
internal class UdpSocketChannelFactory(
    private val peer: SocketAddress,
    private val codec: SocketAddressCodec,
    private val bufferFactory: BufferFactory,
    private val receiveBufferSize: Int,
) : UdpChannelFactory {
    override suspend fun openPath(
        localHost: String?,
        localPort: Int,
    ): NewPath {
        val channel = UdpSocket.connect(peer.host, peer.port, localHost, localPort, receiveBufferSize)
        val local = channel.localAddress ?: error("connected migration path has no local address")
        val encoded = codec.encodeToNative(local, bufferFactory)
        return NewPath(
            channel = DatagramChannelUdpChannel(channel),
            localSockAddrAddress = encoded.address,
            localSockAddrLength = encoded.length,
            release = { encoded.free() },
        )
    }
}
