@file:OptIn(ExperimentalDatagramApi::class, InternalDatagramLoopApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.DatagramSendAttempt
import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.DatagramSendException
import com.ditchoom.socket.udp.InternalDatagramLoopApi
import com.ditchoom.socket.udp.NonSuspendingAddressedSend
import com.ditchoom.socket.udp.NonSuspendingConnectedSend

internal actual fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    options: DatagramSendOptions,
): ImmediateSend =
    when (channel) {
        is NonSuspendingConnectedSend -> attemptOf { channel.trySendNow(payload, options) }
        else -> ImmediateSend.MustWait
    }

internal actual fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    to: SocketAddress,
    options: DatagramSendOptions,
): ImmediateSend =
    when (channel) {
        is NonSuspendingAddressedSend -> attemptOf { channel.trySendNow(payload, to, options) }
        else -> ImmediateSend.MustWait
    }

private inline fun attemptOf(attempt: () -> DatagramSendAttempt): ImmediateSend =
    try {
        when (attempt()) {
            DatagramSendAttempt.Sent -> ImmediateSend.Sent
            DatagramSendAttempt.WouldWait -> ImmediateSend.MustWait
        }
    } catch (typed: DatagramSendException) {
        ImmediateSend.Failed(typed.error)
    } catch (untyped: Exception) {
        ImmediateSend.Failed(DatagramSendError.Transport(untyped))
    }
