package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

/** What the operating system gave a UDP socket that asked for a kernel receive buffer of a given size. */
@ExperimentalDatagramApi
sealed interface KernelBufferGrant {
    /** The buffer holds [usableBytes], at least the [requestedBytes] asked for. */
    data class Granted(
        val requestedBytes: Int,
        val usableBytes: Int,
    ) : KernelBufferGrant

    /**
     * The operating system's limit is below the request, and the buffer holds [usableBytes]. On Linux the limit
     * is `net.core.rmem_max`; on macOS, `kern.ipc.maxsockbuf`.
     */
    data class Capped(
        val requestedBytes: Int,
        val usableBytes: Int,
    ) : KernelBufferGrant

    /** The size could not be set, or not read back: the buffer is whatever it was. */
    data class Unread(
        val requestedBytes: Int,
    ) : KernelBufferGrant

    /** The channel is not a socket this call can size: one this library did not open, or a Node.js socket. */
    data object Unsupported : KernelBufferGrant

    companion object {
        /** The grant for a socket that reports [usableBytes] of buffer after [requestedBytes] were asked for. */
        internal fun of(
            requestedBytes: Int,
            usableBytes: Int,
        ): KernelBufferGrant =
            if (usableBytes >= requestedBytes) Granted(requestedBytes, usableBytes) else Capped(requestedBytes, usableBytes)
    }
}

/**
 * Asks the operating system for [bytes] of kernel receive buffer on this socket (`SO_RCVBUF`) and reports what
 * it gave. A socket that receives from many peers at once needs more than the default, which holds a few hundred
 * small datagrams: what arrives while the buffer is full is dropped.
 */
@ExperimentalDatagramApi
expect fun AddressedDatagramChannel.requestKernelReceiveBuffer(bytes: Int): KernelBufferGrant
