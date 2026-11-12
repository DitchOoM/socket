@file:OptIn(ExperimentalDatagramApi::class, InternalDatagramLoopApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.InternalDatagramLoopApi
import com.ditchoom.socket.udp.NonSuspendingDatagramSend

internal actual fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    to: SocketAddress?,
    options: DatagramSendOptions,
): ImmediateSend =
    if (channel is NonSuspendingDatagramSend) {
        if (channel.trySendNow(payload, to, options)) ImmediateSend.Sent else ImmediateSend.WouldWait
    } else {
        ImmediateSend.Unsupported
    }
