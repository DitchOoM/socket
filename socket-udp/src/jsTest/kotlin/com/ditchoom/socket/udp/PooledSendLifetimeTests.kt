package com.ditchoom.socket.udp

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The #277 regression on real Node `dgram` sockets: the send path used to take a `ReadBuffer.slice()`,
 * whose pooled form holds a reference nothing released — so every send leaked one chunk out of the
 * caller's [com.ditchoom.buffer.pool.BufferPool]. The assertion is shared with the JVM and native
 * suites in [assertSendReturnsPooledChunkToPool]; only the runner is per-platform (Node has no
 * `runBlocking`, so the test returns the Promise for Mocha to await).
 */
@OptIn(DelicateCoroutinesApi::class)
class PooledSendLifetimeTests {
    @Test
    fun sendReturnsPooledChunkToPool() =
        GlobalScope.promise {
            withTimeout(20_000) { assertSendReturnsPooledChunkToPool() }
        }
}
