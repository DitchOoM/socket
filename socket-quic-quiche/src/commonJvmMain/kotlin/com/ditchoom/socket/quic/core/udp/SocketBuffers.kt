package com.ditchoom.socket.quic.core.udp

import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel

/**
 * The socket buffer sizes asked of the operating system for a UDP socket, in bytes. The receive buffer has to hold
 * what the sender emits in one burst until the loop reads it: the default's 4 MiB is about a 15 ms round trip at
 * the wire speed of a 2.5 Gb/s link, and an operating system default of about 200 KiB overflows long before
 * that. The send buffer only has to hold what one pass hands the kernel, with room for the loop's lanes: 2 MiB is
 * about 1,400 datagrams.
 */
internal class SocketBuffers(
    val receiveBytes: Int,
    val sendBytes: Int,
) {
    /** Asks for these sizes on [channel] and reads back what the operating system granted. */
    fun applyTo(channel: DatagramChannel): GrantedBuffers {
        channel.setOption(StandardSocketOptions.SO_RCVBUF, receiveBytes)
        channel.setOption(StandardSocketOptions.SO_SNDBUF, sendBytes)
        return GrantedBuffers(
            requested = this,
            receiveBytes = channel.getOption(StandardSocketOptions.SO_RCVBUF),
            sendBytes = channel.getOption(StandardSocketOptions.SO_SNDBUF),
        )
    }

    companion object {
        val Default = SocketBuffers(receiveBytes = 4 shl 20, sendBytes = 2 shl 20)
    }
}

/**
 * What the operating system answered when asked for [requested]. Linux answers twice the value it accepted, capped
 * by `net.core.rmem_max` and `net.core.wmem_max`; macOS answers what it set, capped by `kern.ipc.maxsockbuf`.
 */
internal class GrantedBuffers(
    val requested: SocketBuffers,
    val receiveBytes: Int,
    val sendBytes: Int,
)
