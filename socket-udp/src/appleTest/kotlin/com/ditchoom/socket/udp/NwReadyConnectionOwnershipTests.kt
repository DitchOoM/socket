@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.socket.udp.nw.nw_udp_create
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A connection that reached `ready` is started, and a started `nw_connection_t` must be cancelled.
 * Whatever the connect does after readiness (the peer's address lookup here) and throws from, the
 * connection must not be left running with nobody holding it.
 */
class NwReadyConnectionOwnershipTests {
    @Test
    fun aConnectThatFailsAfterReadinessCancelsItsConnection() =
        runBlocking {
            val trace = NwConnectTrace()
            val boom = IllegalStateException("peer lookup failed")
            val outcome =
                runCatching {
                    UdpSocket.connectNw(
                        "127.0.0.1",
                        44431,
                        1500,
                        defaultDatagramBufferFactory,
                        trace,
                        peerOf = { throw boom },
                    ) { nw_udp_create("127.0.0.1", "44431") }
                }
            assertSame(boom, outcome.exceptionOrNull(), "unexpected outcome: $outcome")
            val start = TimeSource.Monotonic.markNow()
            while (!trace.render().contains("state=cancelled")) {
                if (start.elapsedNow().inWholeMilliseconds > 3000) {
                    fail("the connection that was ready when the connect threw was never cancelled:\n${trace.render()}")
                }
                delay(20.milliseconds)
            }
        }
}
