@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/** What one send attempt that never suspends came to. */
@InternalDatagramLoopApi
enum class DatagramSendAttempt {
    /** The socket took the datagram. */
    Sent,

    /** The socket had no room: nothing was sent and the payload is untouched. */
    WouldWait,
}

/**
 * A connected datagram channel that can try one send without suspending, for the QUIC driver loop. A send
 * that fails throws the typed `DatagramSendException` that `send` would.
 */
@InternalDatagramLoopApi
interface NonSuspendingConnectedSend {
    fun trySendNow(
        payload: ReadBuffer,
        options: DatagramSendOptions,
    ): DatagramSendAttempt
}

/** As [NonSuspendingConnectedSend], for an addressed channel sending to [to]. */
@InternalDatagramLoopApi
interface NonSuspendingAddressedSend {
    fun trySendNow(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ): DatagramSendAttempt
}
