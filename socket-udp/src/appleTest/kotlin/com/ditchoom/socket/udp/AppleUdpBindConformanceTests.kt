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
 * `SO_REUSEADDR` must stay **multicast-only** on Apple (see `UdpSocket.setReuseAddr`), the same contract
 * the JVM actual and `LinuxUdpBindConformanceTests` hold.
 *
 * Darwin refuses a duplicate bind of the *identical* address:port even with the option set, so the Linux
 * port-theft flake never reproduced here — but that only ever covered exact duplicates. An *overlapping*
 * pair is a different kernel check, and it is the pair this module produces: `bind(localHost = null)`
 * binds the dual-stack wildcard `::`, and `bind(localHost = "127.0.0.1")` binds a specific address.
 * Measured on Darwin with the wildcard socket binding first: a later specific bind to the same port is
 * refused when plain and **allowed** when it carries `SO_REUSEADDR`, and the more-specific socket then
 * takes the delivery — the wildcard-bound server receives nothing for the rest of its life.
 *
 * These assert the kernel-visible behaviour (is the port actually exclusive?), not the setsockopt call,
 * so they stay honest if the implementation is refactored.
 */
@OptIn(ExperimentalDatagramApi::class)
class AppleUdpBindConformanceTests {
    private val opened = mutableListOf<DatagramChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private suspend fun bind(
        host: String?,
        port: Int = 0,
    ): AddressedDatagramChannel = UdpSocket.bind(host, port).also { opened.add(it) }

    @Test
    fun wildcardBind_isNotStolenByALaterSpecificV4Bind() =
        runBlocking {
            // The production shape: a QUIC server binds the wildcard, and anything that later binds the
            // same port on a specific address silently takes its traffic. This is the leg that fails with
            // SO_REUSEADDR on the unicast path.
            val server = bind(null)
            val port = server.localAddress.port
            assertFails("a later specific v4 bind to :$port must be refused (SO_REUSEADDR must not be set)") {
                runBlocking { bind("127.0.0.1", port) }
            }
            Unit
        }

    @Test
    fun wildcardBind_isNotStolenByALaterSpecificV6Bind() =
        runBlocking {
            // Same overlap through the v6 half of the dual-stack socket. Worth its own leg because Darwin's
            // NWConnection client resolves "localhost" to ::1 by preference, so this is the address family
            // a stolen loopback port actually goes silent on.
            val server = bind(null)
            val port = server.localAddress.port
            assertFails("a later specific v6 bind to :$port must be refused (SO_REUSEADDR must not be set)") {
                runBlocking { bind("::1", port) }
            }
            Unit
        }

    @Test
    fun wildcardBind_refusesAPortWhoseIPv4HalfAnEarlierAfInetSocketAlreadyOwns() =
        runBlocking {
            // The mirror of the two legs above, and the one that actually bit: the *earlier* socket is
            // the AF_INET wildcard, and Darwin lets the dual-stack `::` bind succeed on top of it —
            // then hands every 127.0.0.1:port datagram to the AF_INET socket. Measured three ways on
            // macOS 15: an AF_INET **wildcard** holder wins IPv4 and leaves ::1 alone, while a
            // 127.0.0.1-specific holder and an IPV6_V6ONLY holder both make the dual-stack bind fail.
            // So this shape is the only silent one, which is why it had to be caught by choosing the
            // port through IPv4 rather than by the kernel refusing the bind.
            //
            // `bind("0.0.0.0", 0)` is a genuine AF_INET socket here (the family follows the address),
            // so the thief needs no raw POSIX.
            //
            // What this prevents: a QUIC server bound to a port whose IPv4 half belongs to some daemon
            // is open, healthy and permanently deaf over IPv4 — the client retransmits its Initial for
            // the whole idle timeout and closes with `local: IdleTimeout` over an empty server trace
            // (DitchOoM/socket#450, #367).
            val thief = bind("0.0.0.0")
            val port = thief.localAddress.port
            assertFails(
                "the wildcard bind on :$port must fail while an AF_INET socket holds 0.0.0.0:$port — " +
                    "succeeding returns a socket that never receives an IPv4 datagram",
            ) {
                runBlocking { bind(null, port) }
            }
            Unit
        }

    @Test
    fun unicastBind_holdsItsPortExclusively() =
        runBlocking {
            // Darwin enforces this one even with SO_REUSEADDR, so it does not discriminate the fix — it
            // guards the neighbouring mistake instead: SO_REUSEPORT on a unicast socket *would* let a
            // second socket take the identical address:port, and the multicast path sets both options.
            val first = bind("127.0.0.1")
            val port = first.localAddress.port
            assertFails("a second unicast bind to :$port must be refused") {
                runBlocking { bind("127.0.0.1", port) }
            }
            Unit
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
