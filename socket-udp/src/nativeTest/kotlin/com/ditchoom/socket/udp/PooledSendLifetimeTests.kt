package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The #277 pooled-send lifetime contract on real native sockets (Linux io_uring / Apple NW + POSIX).
 * These backends never had the leak — they resolve `nativeAddress + position()` and take no reference —
 * so this is a guard, not a fix: it pins the invariant on the platforms that already hold it. The
 * assertion is shared with the JVM and Node suites in [assertSendReturnsPooledChunkToPool].
 */
class PooledSendLifetimeTests {
    @Test
    fun sendReturnsPooledChunkToPool() =
        runBlocking {
            withTimeout(20_000) { assertSendReturnsPooledChunkToPool() }
        }
}
