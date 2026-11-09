package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlin.coroutines.CoroutineContext

/**
 * A [HostResolver] over a platform lookup that blocks its thread (`getaddrinfo`,
 * `InetAddress.getAllByName`), which a caller can still cancel.
 *
 * Neither call can be interrupted: a thread inside it stays there until the system resolver answers,
 * which on a host whose DNS server is slow or gone takes as long as the resolver's retries (tens of
 * seconds). Run under `withContext`, the caller's cancellation would wait for that answer too, so a
 * `withTimeout` around a connect, or the cancellation of the scope that started it, did nothing for as
 * long as the name took to fail. A QUIC connect resolves the name before its own handshake timeout starts,
 * so nothing at all bounded that wait.
 *
 * Here the lookup runs in a scope of its own on [context], and the caller only awaits it: cancelling the
 * caller abandons the wait at once. The lookup finishes in the background, frees what it allocated, and
 * its answer is dropped.
 */
internal class BlockingHostResolver(
    private val context: CoroutineContext,
    private val lookup: (host: String) -> Resolution,
) : HostResolver {
    override suspend fun resolve(host: String): Resolution = CoroutineScope(context).async { lookup(host) }.await()
}
