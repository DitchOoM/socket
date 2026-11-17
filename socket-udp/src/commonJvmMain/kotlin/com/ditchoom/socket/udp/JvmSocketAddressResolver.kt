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
 * band). Numeric literals resolve on the caller's thread with no lookup and no dispatcher hop
 * ([isAddressLiteral]); a hostname performs real DNS via [InetAddress.getByName], offloaded to
 * [Dispatchers.IO] and kept cancellation-correct with [runInterruptible]. Either way the result is an [InternedJvmSocketAddress] that owns its
 * [InetSocketAddress] for zero-alloc reuse as a send target.
 */
@ExperimentalDatagramApi
internal object JvmSocketAddressResolver : SocketAddressResolver {
    override suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress {
        require(port in 0..65535) { "port out of range: $port" }
        val address =
            if (isAddressLiteral(host)) {
                // getByName checks a literal's format and parses it; it never asks DNS.
                InetAddress.getByName(host)
            } else {
                runInterruptible(Dispatchers.IO) { InetAddress.getByName(host) }
            }
        return InternedJvmSocketAddress(InetSocketAddress(address, port))
    }

    /**
     * True for a dotted-quad IPv4 literal or anything with a colon, which [InetAddress.getByName] only
     * ever parses as an IPv6 literal (failing fast if it is not one). Anything else, including the
     * rarer IPv4 short forms, takes the off-thread path, which is merely slower.
     */
    private fun isAddressLiteral(host: String): Boolean {
        if (host.indexOf(':') >= 0) return true
        var dots = 0
        for (c in host) {
            when (c) {
                '.' -> dots++
                in '0'..'9' -> Unit
                else -> return false
            }
        }
        return dots == IPV4_DOTS
    }

    private const val IPV4_DOTS = 3
}
