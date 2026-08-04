package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * "A send either delivers or reports" on real JVM/Android NIO sockets. See
 * [assertSendNeverSilentlyDrops] for the invariant and why it is a consumer correctness issue; only the
 * runner is per-platform.
 */
class SendVisibilityTests {
    @Test
    fun sendNeverSilentlyDrops() =
        runBlocking(Dispatchers.IO) {
            withTimeout(60_000) { assertSendNeverSilentlyDrops(this) }
        }
}
