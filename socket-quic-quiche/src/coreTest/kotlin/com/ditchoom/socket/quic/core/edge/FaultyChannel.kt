@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.DatagramSendException
import kotlinx.coroutines.awaitCancellation

/** What a [FaultyChannel] does with one send. */
internal sealed interface SendFault {
    data object Passes : SendFault

    /** The send throws as socket-udp does for this [error]: the channel stays open. */
    class Refuses(
        val error: DatagramSendError,
    ) : SendFault

    /** The send never returns, until cancelled. */
    data object Hangs : SendFault
}

/** [inner], whose sends [fault] decides one by one (counted from 1, with each destination); receives pass through. */
internal class FaultyChannel(
    private val inner: AddressedDatagramChannel,
    private val fault: (n: Int, to: SocketAddress) -> SendFault,
) : AddressedDatagramChannel {
    private var sends = 0
    override val localAddress: SocketAddress get() = inner.localAddress
    override val isOpen: Boolean get() = inner.isOpen
    override val capabilities: DatagramCapabilities get() = inner.capabilities
    override val maxWritableSize: Int get() = inner.maxWritableSize

    override suspend fun receive(): DatagramReadResult = inner.receive()

    override suspend fun send(
        payload: ReadBuffer,
        to: SocketAddress,
        options: DatagramSendOptions,
    ) {
        when (val decided = fault(++sends, to)) {
            is SendFault.Passes -> inner.send(payload, to, options)
            is SendFault.Refuses -> throw DatagramSendException(decided.error)
            is SendFault.Hangs -> awaitCancellation()
        }
    }

    override fun close() = inner.close()
}
