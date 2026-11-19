@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.nw_helper_resolve_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_resolve_start
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal actual fun platformHostResolver(): HostResolver = DnsServiceHostResolver

/** The longest a lookup runs before it is [ResolveFailure.TimedOut]. */
private const val LOOKUP_TIMEOUT_MS = 15_000

/**
 * DNS-SD (`DNSServiceGetAddrInfo`, the resolver behind `getaddrinfo` and Network.framework): the lookup runs
 * in mDNSResponder and calls back on a private dispatch queue, so no thread waits for it, and cancelling
 * deallocates the query without waiting for an answer.
 */
private object DnsServiceHostResolver : HostResolver {
    override suspend fun resolve(host: String): Resolution =
        suspendCancellableCoroutine { continuation ->
            val query =
                nw_helper_resolve_start(host, LOOKUP_TIMEOUT_MS) { addresses, status, raw ->
                    val answer =
                        when (status) {
                            STATUS_ANSWERED -> {
                                val records =
                                    addresses.orEmpty().map {
                                        val literal = it as String
                                        ResolvedAddress(literal, if (':' in literal) IpFamily.V6 else IpFamily.V4)
                                    }
                                resolvedInOrder(records, host)
                            }
                            STATUS_NOT_FOUND -> Resolution.Failed(host, ResolveFailure.NotFound)
                            STATUS_TIMED_OUT -> Resolution.Failed(host, ResolveFailure.TimedOut)
                            else -> Resolution.Failed(host, ResolveFailure.Os(raw))
                        }
                    continuation.resume(answer)
                }
            continuation.invokeOnCancellation { nw_helper_resolve_cancel(query) }
        }

    private const val STATUS_ANSWERED = 0
    private const val STATUS_NOT_FOUND = 1
    private const val STATUS_TIMED_OUT = 2
}
