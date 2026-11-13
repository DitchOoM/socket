@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.DatagramSendError

/** What one send attempt that never suspends came to. */
internal sealed interface ImmediateSend {
    /** The socket took the datagram. */
    data object Sent : ImmediateSend

    /** The send failed with [error]. */
    class Failed(
        val error: DatagramSendError,
    ) : ImmediateSend

    /** The send has to wait for room, or this channel has no attempt that does not suspend: send it through [UdpChannel.send]. */
    data object MustWait : ImmediateSend
}

/** One attempt to send on a connected [channel] that never suspends. */
internal expect fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    options: DatagramSendOptions,
): ImmediateSend

/** One attempt to send on an addressed [channel] to [to] that never suspends. */
internal expect fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    to: SocketAddress,
    options: DatagramSendOptions,
): ImmediateSend
