package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * "A send either delivers or reports" on real native sockets (Linux io_uring / Apple NW + POSIX). This
 * is the leg that currently fails: Apple's `sendto` result is discarded, so a datagram larger than the
 * default `SO_SNDBUF` returns cleanly and never leaves the host. See [assertSendNeverSilentlyDrops].
 */
class SendVisibilityTests {
    @Test
    fun sendNeverSilentlyDrops() =
        runBlocking {
            withTimeout(60_000) { assertSendNeverSilentlyDrops(this) }
        }
}
