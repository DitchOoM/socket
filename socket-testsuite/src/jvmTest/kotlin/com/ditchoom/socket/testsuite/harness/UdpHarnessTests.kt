@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.testsuite.harness

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.testkit.fault.FaultSchedule
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier-C validation of the UDP harness (RFC_UNIFIED_NETWORK_TEST_HARNESS.md §5, P1): drives the real
 * `udp-echo` + `udp-toxi` sidecars through the public [NetworkHarnessScope.udpEcho] / [impairedUdp]
 * accessors and `:socket-udp`'s `UdpSocket`, over the wire — the on-the-wire twin of the deterministic
 * in-process `ImpairedDatagramPipe` (Tier-A) tests in `:socket-udp`.
 *
 * JVM-only (not the common `NetworkHarnessTestSuite`): `:socket-udp` builds jvm/android/linux/apple-mac
 * but not tvos/watchos, so it cannot be a dependency of the tvos/watchos-covering common suite. jvmTest
 * inherits jvmMain's `:socket-udp` and runs under the `harnessUp` window on dev Macs (Docker Desktop)
 * and Linux CI — exactly where Tier-C is meant to run (§9).
 *
 * **Skip-on-unreachable, never flaky-fail:** every body runs inside [withNetworkHarness], so with the
 * docker stack down each test is a clean printed skip, not a failure. Assertions inside the block are
 * real (they propagate). The methods use block bodies (returning `Unit`) so JUnit's `void`-method
 * contract is met — an expression body would return [withNetworkHarness]'s `Boolean`.
 */
class UdpHarnessTests {
    private suspend fun DatagramChannel.sendText(text: String) {
        // Connected channel — to = null targets the fixed peer this channel connect()ed to.
        send(BufferFactory.Default.wrap(text.encodeToByteArray()), to = null)
    }

    private suspend fun DatagramChannel.recvText(timeoutMs: Long): String {
        val received = withTimeout(timeoutMs) { receive() } as DatagramReadResult.Received
        val payload = received.datagram.payload
        return payload.readByteArray(payload.remaining()).decodeToString()
    }

    /** Receive up to [expected] datagrams within [budgetMs]; returns however many actually arrived. */
    private suspend fun DatagramChannel.collect(
        expected: Int,
        budgetMs: Long,
    ): List<String> {
        val out = mutableListOf<String>()
        withTimeoutOrNull(budgetMs) {
            while (out.size < expected) {
                val received = receive() as DatagramReadResult.Received
                val payload = received.datagram.payload
                out += payload.readByteArray(payload.remaining()).decodeToString()
            }
        }
        return out
    }

    @Test
    fun udpEcho_roundTripsADatagram() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                val endpoint = udpEcho()
                val channel = UdpSocket.connect(endpoint.host, endpoint.port)
                try {
                    channel.sendText("hello-udp")
                    assertEquals("hello-udp", channel.recvText(5_000))
                } finally {
                    channel.close()
                }
            }
        }
    }

    @Test
    fun impairedUdp_cleanScheduleForwardsBothWays() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                impairedUdp(FaultSchedule.CLEAN) { relay ->
                    val channel = UdpSocket.connect(relay.host, relay.port)
                    try {
                        channel.sendText("ping")
                        assertEquals("ping", channel.recvText(5_000))
                    } finally {
                        channel.close()
                    }
                }
            }
        }
    }

    @Test
    fun impairedUdp_dropsTheSelectedDatagram() {
        runBlocking(Dispatchers.IO) {
            withNetworkHarness {
                // The relay drops the 1st (0-based) client→server datagram; the echo never sees "m1", so
                // it can never echo back. The other three cross cleanly.
                impairedUdp(FaultSchedule { drop(nth = 1) }) { relay ->
                    val channel = UdpSocket.connect(relay.host, relay.port)
                    try {
                        // 30 ms spacing keeps the send order (and thus the unit indices) unambiguous over
                        // loopback, which does not reorder.
                        for (m in listOf("m0", "m1", "m2", "m3")) {
                            channel.sendText(m)
                            delay(30)
                        }
                        val got = channel.collect(expected = 3, budgetMs = 4_000)
                        assertFalse("m1" in got, "the dropped datagram must never echo back; got=$got")
                        assertTrue(got.isNotEmpty(), "non-dropped datagrams should still echo back; got=$got")
                    } finally {
                        channel.close()
                    }
                }
            }
        }
    }
}
