package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A connect to a peer this host has no route to, many times over. Each one fails at once (NW reports
 * `waiting` on an unsatisfied path), and the connect then cancels the connection, so NW calls the
 * state handler again — on a concurrent queue. A non-atomic "already resumed" flag let that second
 * call resume the continuation twice, and Kotlin/Native aborts the process: three iPhone walk
 * crashes on 10-30/10-31, every one in this handler.
 *
 * Runs only on a host with no IPv6 route, where the condition is real; elsewhere it says so and
 * passes vacuously rather than pretend.
 */
class AppleUnreachableConnectTests {
    @Test
    fun aConnectWithNoRouteFailsTypedEveryTimeAndNeverResumesTwice() =
        runBlocking {
            if (hostHasIpv6Route()) {
                println("SKIPPED-VACUOUS: this host has an IPv6 route, so no connect here is unreachable")
                return@runBlocking
            }
            repeat(ATTEMPTS) { n ->
                val outcome =
                    runCatching {
                        withTimeout(5.seconds) {
                            UdpSocket.connect(UNROUTABLE_V6, 44433, null, 0, 1500, defaultDatagramBufferFactory)
                        }
                    }
                outcome.getOrNull()?.let {
                    it.close()
                    fail("attempt $n connected to $UNROUTABLE_V6 on a host with no IPv6 route")
                }
                val error = (outcome.exceptionOrNull() as? UdpConnectException)?.error
                assertTrue(error is UdpConnectError.Unreachable, "attempt $n: expected Unreachable, got ${outcome.exceptionOrNull()}")
            }
        }

    private fun hostHasIpv6Route(): Boolean = HostUnicastAddresses.firstNonLoopback(AddressFamily.IPv6) != null

    private companion object {
        /** Documentation-range IPv6 (RFC 3849): never answers, and has a route only where any v6 does. */
        const val UNROUTABLE_V6 = "2001:db8::1"
        const val ATTEMPTS = 300
    }
}
