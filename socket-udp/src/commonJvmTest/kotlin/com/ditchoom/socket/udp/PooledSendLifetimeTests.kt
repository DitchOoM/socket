package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The #277 regression on real JVM/Android NIO sockets: staging a send used to go through
 * `ReadBuffer.slice()`, whose pooled form holds a reference the send path never released — so every
 * send leaked one chunk out of the caller's [com.ditchoom.buffer.pool.BufferPool]. The assertion is
 * shared with the Node and native suites in [assertSendReturnsPooledChunkToPool]; only the runner is
 * per-platform.
 */
class PooledSendLifetimeTests {
    @Test
    fun sendReturnsPooledChunkToPool() =
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) { assertSendReturnsPooledChunkToPool() }
        }
}
