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
 *
 * Holding `[::1]:P` says nothing about `P` in the IPv4 table: the kernel draws the two families'
 * ephemeral ports independently, and `0.0.0.0:P` is refused while ANY IPv4 socket on this host uses
 * `P` — another module's test process, a daemon, a connected client. The simulator shares the host's
 * network stack, so on CI that is every process on the runner. [holdAnIpv6PortWhoseIpv4TwinIsFree]
 * establishes that premise rather than assuming it.
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

    /**
     * Binds `[::1]:P` and returns `P`, once the real IPv4 probe has found `0.0.0.0:P` free.
     *
     * A holder whose IPv4 twin is in use elsewhere on the host is closed and drawn again. Each draw
     * collides with probability (IPv4 ports in use / 16 384), so a bounded number of draws all
     * colliding means the host's IPv4 table is nearly full, and the test says that instead of
     * reporting it as a failure of the bind under test.
     */
    private suspend fun holdAnIpv6PortWhoseIpv4TwinIsFree(): Int {
        val collided = mutableListOf<Int>()
        repeat(MAX_HOLDER_DRAWS) {
            val holder = UdpSocket.bind("::1", 0)
            val port = holder.localAddress.port
            try {
                val probed = UdpSocket.wildcardPortOwnedForIpv4(port)
                check(probed == port) { "the probe was asked for :$port and bound :$probed" }
                opened += holder
                return port
            } catch (e: UdpBindException) {
                holder.close()
                if (e.error !is UdpBindError.AddressInUse) throw e
                collided += port
            }
        }
        fail(
            "premise not established: in $MAX_HOLDER_DRAWS draws, every port [::1] was handed was in use in the " +
                "IPv4 table too ($collided), so the host is short of free IPv4 ports",
        )
    }

    @Test
    fun anEphemeralWildcardBindWhosePortIsTakenInTheIpv6TableDrawsAnotherPort() =
        runBlocking {
            var taken = -1
            val probes = mutableListOf<Int>()

            val bound =
                try {
                    UdpSocket.bindDualStackWildcard(0, MAX_UDP_DATAGRAM_SIZE, factory) { requested ->
                        // The first draw is the #660 state exactly: a port the IPv4 probe really does find
                        // free (the real probe ran on it, inside this draw), that [::1] already holds.
                        val port =
                            if (probes.isEmpty()) {
                                holdAnIpv6PortWhoseIpv4TwinIsFree().also { taken = it }
                            } else {
                                UdpSocket.wildcardPortOwnedForIpv4(requested)
                            }
                        probes += port
                        port
                    }
                } catch (e: AssertionError) {
                    throw e // the premise's own failure, already worded
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
            val taken = holdAnIpv6PortWhoseIpv4TwinIsFree()
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

    private companion object {
        const val MAX_HOLDER_DRAWS = 32
    }
}
