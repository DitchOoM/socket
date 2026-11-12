@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/**
 * A datagram channel that can try one send without suspending, for the QUIC driver loop: the common case
 * is a socket with room, and a send that returns at once needs none of the machinery a bounded wait costs.
 * Public only because the QUIC module is a separate module.
 */
@InternalDatagramLoopApi
interface NonSuspendingDatagramSend {
    /**
     * Sends [payload] now if the socket takes it, to [to] on an addressed channel (ignored on a connected
     * one). Returns false when it would have to wait: nothing was sent and [payload] is untouched, so the
     * caller sends it through the channel's own `send`, which waits for room. A send that fails throws the
     * same typed `DatagramSendException` that `send` would. Never suspends.
     */
    fun trySendNow(
        payload: ReadBuffer,
        to: SocketAddress?,
        options: DatagramSendOptions,
    ): Boolean
}
