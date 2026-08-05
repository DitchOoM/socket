package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * `SO_REUSEADDR` must stay **multicast-only** on Linux (see `UdpSocket.setReuseAddr`).
 *
 * Regression guard for the linuxX64 `:socket-http3` handshake stall in release run `30954202211`: the
 * option was being set on every unicast socket (`bind()` and `connect()`), and on Linux — unlike Darwin —
 * that relaxes the kernel's duplicate-bind check. Two sockets could then hold one port with the *later*
 * binder receiving the traffic, and `bind(port = 0)` would re-hand out a live ephemeral port. A QUIC
 * server that loses its port that way never sees the client's Initial, so the client observes total
 * silence and dies at its idle timeout.
 *
 * These assert the kernel-visible behaviour (is the port actually exclusive?), not the setsockopt call,
 * so they stay honest if the implementation is refactored.
 */
@OptIn(ExperimentalDatagramApi::class)
class LinuxUdpBindConformanceTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(port: Int = 0): AddressedDatagramChannel = UdpSocket.bind("127.0.0.1", port).also { opened.add(it) }

    @Test
    fun unicastBind_holdsItsPortExclusively() =
        runBlocking {
            val first = bind()
            val port = first.localAddress.port
            // The kernel's duplicate-bind check must still apply: without it the second socket wins the
            // traffic and `first` silently receives nothing for the rest of its life.
            assertFails("a second unicast bind to :$port must be refused (SO_REUSEADDR must not be set)") {
                runBlocking { bind(port) }
            }
            Unit
        }

    @Test
    fun ephemeralPorts_areNeverHandedOutTwice() =
        runBlocking {
            // With SO_REUSEADDR set this collided in 5/5 runs, usually inside the first few hundred binds;
            // without it, 0/5. 400 keeps the test quick while staying well inside the observed hit range.
            val seen = mutableSetOf<Int>()
            repeat(400) {
                val port = bind().localAddress.port
                assertEquals(true, seen.add(port), "bind(port = 0) handed out an already-live ephemeral port: $port")
            }
        }

    @Test
    fun multicastBind_stillSharesItsPort() =
        runBlocking {
            // The other side of the contract: several multicast receivers on one host DO share the group
            // port, so the fix must not have stripped SO_REUSEADDR from the multicast path.
            val first = UdpSocket.bindMulticast(0, AddressFamily.IPv4)
            opened.add(first)
            val port = first.localAddress.port
            val second = UdpSocket.bindMulticast(port, AddressFamily.IPv4)
            opened.add(second)
            assertEquals(port, second.localAddress.port, "multicast listeners must still be able to share a port")
        }
}
