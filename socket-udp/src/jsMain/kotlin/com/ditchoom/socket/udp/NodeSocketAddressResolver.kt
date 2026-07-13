package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.SocketAddressResolver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Node.js hostname resolver installed into buffer-flow (RFC §10.1: DNS happens once, out of band).
 * Numeric literals resolve synchronously with no lookup ([SocketAddress.ofLiteral]); a hostname performs
 * real DNS via Node's `dns.lookup`, which runs off the event loop and returns a **numeric** address —
 * so the resulting [SocketAddress] always owns a numeric host for zero-alloc reuse as a send target.
 */
@ExperimentalDatagramApi
internal object NodeSocketAddressResolver : SocketAddressResolver {
    override suspend fun resolve(
        host: String,
        port: Int,
    ): SocketAddress {
        require(port in 0..65535) { "port out of range: $port" }
        // Literal fast path — no DNS. ofLiteral throws for a non-literal; fall through to dns.lookup.
        val literal =
            try {
                SocketAddress.ofLiteral(host, port)
            } catch (_: IllegalArgumentException) {
                null
            }
        if (literal != null) return literal

        val numericAddress =
            suspendCancellableCoroutine { cont ->
                dnsLookup(host) { error, address, _ ->
                    if (!cont.isCompleted) {
                        if (error != null || address == null) {
                            cont.resumeWithException(
                                SocketAddressResolutionException("DNS lookup failed for '$host': $error"),
                            )
                        } else {
                            cont.resume(address)
                        }
                    }
                }
            }
        return SocketAddress.ofLiteral(numericAddress, port)
    }
}

/** A `dns.lookup` failure — the host could not be resolved to a numeric address. */
internal class SocketAddressResolutionException(
    message: String,
) : RuntimeException(message)
