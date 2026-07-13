package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.SocketAddressResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The JVM/Android hostname resolver installed into buffer-flow (RFC §10.1: DNS happens once, out of
 * band). Numeric literals resolve synchronously with no lookup; a hostname performs real DNS via
 * [InetAddress.getByName], offloaded to [Dispatchers.IO] and kept cancellation-correct with
 * [runInterruptible]. Either way the result is an [InternedJvmSocketAddress] that owns its
 * [InetSocketAddress] for zero-alloc reuse as a send target.
 */
@ExperimentalDatagramApi
internal object JvmSocketAddressResolver : SocketAddressResolver {
    override suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress {
        require(port in 0..65535) { "port out of range: $port" }
        val address = runInterruptible(Dispatchers.IO) { InetAddress.getByName(host) }
        return InternedJvmSocketAddress(InetSocketAddress(address, port))
    }
}
