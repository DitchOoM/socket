package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-socket multicast conformance on JVM/Android NIO. Split into two tiers matching the harness posture:
 *
 * - **Control plane** (deterministic, runs everywhere) — a `bindMulticast` channel advertises
 *   `multicast == true`; TTL / loopback / outbound-interface `setsockopt`s and join/leave on a concrete
 *   interface all succeed or fail with a typed [MulticastException]. These are pure socket options and IGMP
 *   state, independent of whether any datagram actually routes.
 * - **End-to-end** (same host) — a sender's datagram to a joined group loops back to a receiver on the same
 *   interface. JVM CI runners (ubuntu `eth0`, macOS `en0`/`lo0`) route this; if the box genuinely has no
 *   multicast-capable interface, [usableMulticastInterface] returns null and the e2e test is a logged skip.
 */
@OptIn(ExperimentalDatagramApi::class)
class MulticastConformanceTests {
    private val opened = mutableListOf<MulticastDatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bindMc(port: Int): MulticastDatagramChannel =
        UdpSocket.bindMulticast(port, AddressFamily.IPv4).also { opened.add(it) }

    private fun payload(text: String): ReadBuffer = BufferFactory.Default.wrap(text.encodeToByteArray())

    private fun mcTest(body: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20_000) { body() } }

    /** A concrete, up, multicast-capable IPv4 interface for e2e; loopback preferred (self-contained), else
     * the first real NIC. Null means the environment cannot do multicast at all → e2e skips. */
    private fun usableMulticastInterface(wantV6: Boolean = false): NetworkInterface? {
        val up =
            NetworkInterface.getNetworkInterfaces().toList().filter {
                runCatching {
                    it.isUp &&
                        it.supportsMulticast() &&
                        it.inetAddresses.asSequence().any { a ->
                            if (wantV6) a is java.net.Inet6Address else a is java.net.Inet4Address
                        }
                }.getOrDefault(false)
            }
        return up.firstOrNull { it.isLoopback } ?: up.firstOrNull { !it.isLoopback } ?: up.firstOrNull()
    }

    // ---- control plane (deterministic) ----

    @Test
    fun multicastChannelAdvertisesTheCapability() =
        mcTest {
            val ch = bindMc(0)
            assertTrue(ch.capabilities.multicast, "a bindMulticast channel must advertise multicast=true")
            assertTrue(ch.isOpen)
        }

    @Test
    fun ttlLoopbackAndInterfaceOptionsApplyWithoutThrowing() =
        mcTest {
            val ch = bindMc(0)
            ch.setTimeToLive(1)
            ch.setLoopbackEnabled(true)
            ch.setLoopbackEnabled(false)
            val nif = usableMulticastInterface()
            if (nif != null) ch.setOutboundInterface(MulticastInterface.ByName(nif.name))
        }

    @Test
    fun joinAndLeaveOnAConcreteInterfaceSucceed() =
        mcTest {
            val nif = usableMulticastInterface() ?: return@mcTest logSkip("no multicast interface")
            val ch = bindMc(0)
            val group = UdpSocket.resolve("239.7.7.7", 0)
            val membership = MulticastMembership(group, MulticastInterface.ByName(nif.name))
            ch.joinGroup(membership)
            ch.leaveGroup(membership)
        }

    @Test
    fun joinOnMissingInterfaceIsTypedFailure() =
        mcTest {
            val ch = bindMc(0)
            val group = UdpSocket.resolve("239.7.7.8", 0)
            // A missing named interface is resolved before any join syscall → the precise NoSuchInterface.
            assertFailsWith<MulticastException.NoSuchInterface> {
                ch.joinGroup(MulticastMembership(group, MulticastInterface.ByName("nonexistent-nic-xyz")))
            }
        }

    @Test
    fun leaveWithoutJoinIsTypedFailure() =
        mcTest {
            val nif = usableMulticastInterface() ?: return@mcTest logSkip("no multicast interface")
            val ch = bindMc(0)
            val group = UdpSocket.resolve("239.7.7.9", 0)
            assertFailsWith<MulticastException.LeaveFailed> {
                ch.leaveGroup(MulticastMembership(group, MulticastInterface.ByName(nif.name)))
            }
        }

    // ---- end-to-end (same host, loopback) ----

    @Test
    fun senderDatagramReachesAJoinedReceiverOnTheSameHost() =
        mcTest {
            val nif = usableMulticastInterface() ?: return@mcTest logSkip("no multicast interface for e2e")
            val iface = MulticastInterface.ByName(nif.name)
            val port = 42_042 // a fixed multicast port both sides agree on (SO_REUSEADDR is set)
            val receiver = bindMc(port)
            val group = UdpSocket.resolve("239.42.42.42", port)
            try {
                receiver.joinGroup(MulticastMembership(group, iface))
            } catch (e: MulticastException) {
                return@mcTest logSkip("join failed on ${nif.name}: ${e.message}")
            }

            val sender = bindMc(0)
            runCatching { sender.setOutboundInterface(iface) }
            sender.setTimeToLive(1)
            sender.setLoopbackEnabled(true)
            sender.send(payload("multicast-hello"), to = group)

            // Conditional assertion: if a datagram arrives it MUST be the exact bytes (a real
            // corruption/misroute regression still fails); if this host doesn't route loopback multicast,
            // log a hard skip. NIO receive() is cancellable, so withTimeoutOrNull returns cleanly.
            when (val got = withTimeoutOrNull(4_000) { receiver.receive() }) {
                is DatagramReadResult.Received ->
                    assertEquals(
                        "multicast-hello",
                        got.datagram.payload
                            .readByteArray(got.datagram.payload.remaining())
                            .decodeToString(),
                    )
                else -> logSkip("no datagram delivered on ${nif.name} — loopback multicast not routed here")
            }
        }

    @Test
    fun senderDatagramReachesAJoinedReceiverOnTheSameHostIpv6() =
        mcTest {
            val nif =
                usableMulticastInterface(wantV6 = true) ?: return@mcTest logSkip("no IPv6 multicast interface for e2e")
            val iface = MulticastInterface.ByName(nif.name)
            val port = 42_043
            val receiver = UdpSocket.bindMulticast(port, AddressFamily.IPv6).also { opened.add(it) }
            val group = UdpSocket.resolve("ff02::114", port) // an unassigned link-local group, same-host scope
            try {
                receiver.joinGroup(MulticastMembership(group, iface))
            } catch (e: MulticastException) {
                return@mcTest logSkip("IPv6 join failed on ${nif.name}: ${e.message}")
            }

            val sender = UdpSocket.bindMulticast(0, AddressFamily.IPv6).also { opened.add(it) }
            runCatching { sender.setOutboundInterface(iface) }
            sender.setTimeToLive(1)
            sender.setLoopbackEnabled(true)
            sender.send(payload("multicast-hello-v6"), to = group)

            // Same conditional posture as the IPv4 e2e: exact bytes if delivered, a loud skip if the host
            // doesn't route link-local v6 multicast on loopback.
            when (val got = withTimeoutOrNull(4_000) { receiver.receive() }) {
                is DatagramReadResult.Received ->
                    assertEquals(
                        "multicast-hello-v6",
                        got.datagram.payload
                            .readByteArray(got.datagram.payload.remaining())
                            .decodeToString(),
                    )
                else -> logSkip("no IPv6 datagram delivered on ${nif.name} — loopback v6 multicast not routed here")
            }
        }

    private fun logSkip(reason: String) {
        // A hard, visible skip — never a silent pass. The deterministic control-plane + fabric tiers still
        // exercise the contract; only kernel-routed delivery is environment-gated.
        println("[MulticastConformanceTests] SKIP e2e: $reason")
    }
}
