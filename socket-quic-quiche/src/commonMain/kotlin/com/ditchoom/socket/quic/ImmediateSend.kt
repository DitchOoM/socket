@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/** What one send attempt that never suspends came to; see [sendImmediately]. */
internal enum class ImmediateSend {
    /** The socket took the datagram. */
    Sent,

    /** The socket had no room: nothing was sent and the payload is untouched. Send it the waiting way. */
    WouldWait,

    /** This platform's channel has no such attempt: send it the waiting way. */
    Unsupported,
}

/**
 * One send attempt on [channel] that never suspends, to [to] on an addressed channel. A datagram the socket
 * takes at once is the normal case, and it needs none of what a bounded wait costs (a scope, a child
 * coroutine, a timer): [UdpChannel.sendNow] is built on this. A failure throws the typed
 * `DatagramSendException` the channel's own `send` would.
 */
internal expect fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    to: SocketAddress?,
    options: DatagramSendOptions,
): ImmediateSend
