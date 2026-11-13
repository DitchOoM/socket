@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress

/** The native channels complete a send through their own completion, never in place. */
internal actual fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    options: DatagramSendOptions,
): ImmediateSend = ImmediateSend.MustWait

internal actual fun sendImmediately(
    channel: Any,
    payload: ReadBuffer,
    to: SocketAddress,
    options: DatagramSendOptions,
): ImmediateSend = ImmediateSend.MustWait
