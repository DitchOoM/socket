package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import platform.posix.EADDRINUSE
import platform.posix.EADDRNOTAVAIL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A refused bind says why, with its errno; and a wildcard bind on the kernel's choice of port survives
 * the one collision its IPv4 probe cannot see.
 *
 * The wildcard bind takes its port from an `AF_INET` probe, which proves the port free in the IPv4
 * table only. The real socket is dual-stack `[::]:P`, and it is refused (`EADDRINUSE`) when anything
 * holds `P` in the IPv6 table — a `::1`-bound client, an `IPV6_V6ONLY` socket. The tests hand the bind
 * exactly that port through its probe parameter, so the collision happens on every run rather than
 * when the host's socket churn lines it up.
 */
@OptIn(ExperimentalDatagramApi::class)
class AppleWildcardBindCollisionTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private val factory = BufferFactory.deterministic()

    @Test
    fun anEphemeralWildcardBindWhosePortIsTakenInTheIpv6TableDrawsAnotherPort() =
        runBlocking {
            val holder = UdpSocket.bind("::1", 0).also { opened += it }
            val taken = holder.localAddress.port
            val probes = mutableListOf<Int>()

            val bound =
                try {
                    UdpSocket.bindDualStackWildcard(0, MAX_UDP_DATAGRAM_SIZE, factory) { requested ->
                        // The first draw is the #660 state exactly: a port the IPv4 probe really does find
                        // free (the real probe runs on it), that [::1] already holds.
                        val port = UdpSocket.wildcardPortOwnedForIpv4(if (probes.isEmpty()) taken else requested)
                        probes += port
                        port
                    }
                } catch (e: Throwable) {
                    fail("an ephemeral wildcard bind must draw again when its port is taken in the IPv6 table; probes=$probes, threw $e")
                }
            opened += bound

            assertEquals(taken, probes.first(), "premise: the first draw was the port [::1] holds")
            assertTrue(probes.size >= 2, "the collision must have been retried with a fresh probe; probes=$probes")
            assertNotEquals(taken, bound.localAddress.port, "the bind must land on a port nobody holds")
        }

    @Test
    fun aWildcardBindOnAnExplicitPortIsRefusedTypedAndNotRetried() =
        runBlocking {
            val holder = UdpSocket.bind("::1", 0).also { opened += it }
            val taken = holder.localAddress.port
            var probes = 0

            val refused =
                runCatching {
                    UdpSocket
                        .bindDualStackWildcard(taken, MAX_UDP_DATAGRAM_SIZE, factory) { requested ->
                            probes++
                            UdpSocket.wildcardPortOwnedForIpv4(requested)
                        }.also { opened += it }
                }.exceptionOrNull()

            val bindFailure = assertIs<UdpBindException>(refused, "a refused bind must be typed; got $refused")
            assertEquals(UdpBindError.AddressInUse(EADDRINUSE), bindFailure.error)
            assertEquals(taken, bindFailure.port)
            assertEquals(1, probes, "a port the caller named is theirs to change, never redrawn")
        }

    @Test
    fun aBindOnAHeldPortReportsAddressInUseWithItsErrno() =
        runBlocking {
            val holder = UdpSocket.bind("127.0.0.1", 0).also { opened += it }
            val port = holder.localAddress.port

            val refused = runCatching { UdpSocket.bind("127.0.0.1", port).also { opened += it } }.exceptionOrNull()

            val bindFailure = assertIs<UdpBindException>(refused, "a refused bind must be typed; got $refused")
            assertEquals(UdpBindError.AddressInUse(EADDRINUSE), bindFailure.error)
            assertEquals("127.0.0.1", bindFailure.host)
            assertEquals(port, bindFailure.port)
        }

    @Test
    fun aBindToAnAddressThisHostDoesNotHaveReportsLocalAddressUnavailable() =
        runBlocking {
            // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): never assigned to a host.
            val refused = runCatching { UdpSocket.bind("192.0.2.1", 0).also { opened += it } }.exceptionOrNull()

            val bindFailure = assertIs<UdpBindException>(refused, "a refused bind must be typed; got $refused")
            assertEquals(UdpBindError.LocalAddressUnavailable(EADDRNOTAVAIL), bindFailure.error)
        }
}
