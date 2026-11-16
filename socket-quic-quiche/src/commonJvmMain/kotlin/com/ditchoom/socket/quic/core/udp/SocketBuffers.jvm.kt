package com.ditchoom.socket.quic.core.udp

import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel

/** Asks for these sizes on [channel] and reads back what the operating system granted. */
internal fun SocketBuffers.applyTo(channel: DatagramChannel): GrantedBuffers {
    channel.setOption(StandardSocketOptions.SO_RCVBUF, receiveBytes)
    channel.setOption(StandardSocketOptions.SO_SNDBUF, sendBytes)
    return GrantedBuffers(
        requested = this,
        receiveBytes = channel.getOption(StandardSocketOptions.SO_RCVBUF),
        sendBytes = channel.getOption(StandardSocketOptions.SO_SNDBUF),
    )
}
