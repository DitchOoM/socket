package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.DatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import java.nio.channels.DatagramChannel as NioChannel

/**
 * **A wildcard UDP bind must own its port for IPv4, or say so.**
 *
 * `DatagramChannel.open()` takes no protocol family, so on a dual-stack host the JDK returns an
 * `AF_INET6` socket with `IPV6_V6ONLY` cleared — one socket meant to serve both families. On
 * BSD/Darwin it does not: a plain `AF_INET` socket may already hold `0.0.0.0:port`, the dual-stack
 * bind **still succeeds**, and every datagram addressed to `127.0.0.1:port` goes to the more specific
 * IPv4 socket. What the caller is handed is a socket that is open, healthy, parked in `select()` — and
 * permanently deaf over IPv4, with no error anywhere.
 *
 * That is the mechanism behind DitchOoM/socket#450 and #367. `bind(0)` picks the ephemeral port from
 * the IPv6 table, so it can hand out a port whose IPv4 half belongs to an unrelated daemon (`homed`,
 * `adb`, … — measured at roughly 1 bind in 4 000 on a developer Mac). A QUIC server bound that way
 * never receives its client's Initial: the client retransmits for the whole idle timeout and closes
 * with `local: IdleTimeout`, and the server's trace is empty because no connection ever existed. It
 * looked load-dependent only because the suite that exposed it binds a fresh ephemeral port per test.
 */
@OptIn(ExperimentalDatagramApi::class)
class WildcardBindPortOwnershipTests {
    private val opened = mutableListOf<DatagramChannel>()
    private val rawOpened = mutableListOf<NioChannel>()

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        rawOpened.forEach { runCatching { it.close() } }
        opened.clear()
        rawOpened.clear()
    }

    /** An `AF_INET`-only wildcard socket on a kernel-chosen port — the shape a system daemon leaves behind. */
    private fun ipv4OnlyWildcardSocket(): Pair<NioChannel, Int> {
        val thief = NioChannel.open(StandardProtocolFamily.INET).also { rawOpened += it }
        thief.configureBlocking(false)
        thief.bind(InetSocketAddress("0.0.0.0", 0))
        return thief to (thief.localAddress as InetSocketAddress).port
    }

    /**
     * **The contract.** Binding the wildcard on a port whose IPv4 half another socket already owns must
     * fail — the same [BindException] Linux raises for exactly this bind — instead of returning a
     * channel that will never receive an IPv4 datagram.
     *
     * RED before the fix on macOS: the bind succeeded and `UdpSocket.bind` handed back a deaf socket.
     * On Linux this has always held, because the kernel refuses the dual-stack bind itself; the test is
     * written so the *observable contract* is identical on both, whichever layer enforces it.
     */
    @Test
    fun aWildcardBindRefusesAPortWhoseIPv4HalfAnotherSocketAlreadyOwns() =
        runBlocking {
            val (_, port) = ipv4OnlyWildcardSocket()

            assertFailsWith<BindException>(
                "binding the wildcard on udp/$port must fail while an AF_INET socket holds 0.0.0.0:$port. " +
                    "Succeeding here returns a socket that is open and permanently deaf over IPv4 — #450.",
            ) {
                UdpSocket.bind(localHost = null, localPort = port).also { opened += it }
            }
            Unit
        }

    /**
     * The OS behaviour the contract above exists for, measured through raw NIO so it depends on nothing
     * in this module. Exhaustive over the two platform answers, because both are correct and the point
     * is that neither leaves a usable dual-stack socket:
     *
     * - Linux refuses the second bind (`EADDRINUSE`) — the hazard cannot arise;
     * - Darwin accepts it and then routes every `127.0.0.1:port` datagram to the IPv4-only socket.
     *
     * If a platform ever accepted the bind *and* delivered to the dual-stack socket, this fails — and
     * that is the day [aWildcardBindRefusesAPortWhoseIPv4HalfAnotherSocketAlreadyOwns] becomes
     * unnecessary rather than merely passing.
     */
    @Test
    fun aDualStackWildcardIsEitherRefusedOrOutrankedByAnIpv4OnlySocketOnTheSamePort() {
        val (thief, port) = ipv4OnlyWildcardSocket()

        val dualStack = NioChannel.open().also { rawOpened += it }
        dualStack.configureBlocking(false)
        try {
            dualStack.bind(InetSocketAddress("0.0.0.0", port))
        } catch (_: BindException) {
            // Linux: the kernel enforces it. Nothing further to measure.
            return
        }

        val sender = NioChannel.open(StandardProtocolFamily.INET).also { rawOpened += it }
        sender.configureBlocking(false)
        sender.send(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), InetSocketAddress("127.0.0.1", port))

        // The datagram is on loopback and already queued by the time send() returns; a single read of
        // each non-blocking socket is enough to say which one the kernel chose.
        val toThief = ByteBuffer.allocate(64)
        val toDualStack = ByteBuffer.allocate(64)
        Thread.sleep(50)
        val thiefGot = thief.receive(toThief) != null
        val dualStackGot = dualStack.receive(toDualStack) != null

        assertTrue(
            thiefGot && !dualStackGot,
            "on a platform that accepts the dual-stack bind, the IPv4-only socket must be the one that " +
                "receives 127.0.0.1:$port (thief=$thiefGot dualStack=$dualStackGot). Anything else means " +
                "the delivery rule changed and #450's mechanism needs re-reading.",
        )
    }

    /**
     * The end-to-end property the port choice protects: an ephemeral wildcard bind receives the IPv4
     * loopback datagrams addressed to its own port. Non-vacuous by construction — it sends one and
     * requires it back — so a bind that silently lost the IPv4 half fails here rather than later, in
     * some other suite, as a handshake that never completed.
     */
    @Test
    fun anEphemeralWildcardBindReceivesIpv4LoopbackDatagramsAddressedToItsPort() =
        runBlocking {
            val server = UdpSocket.bind(localHost = null, localPort = 0).also { opened += it }
            val port = server.localAddress.port

            val client = UdpSocket.connect("127.0.0.1", port).also { opened += it }
            client.send(BufferFactory.Default.wrap("v4-loopback".encodeToByteArray()))

            val received = withTimeout(5.seconds) { server.receive() }
            val datagram = assertIs<DatagramReadResult.Received>(received).datagram
            assertEquals(
                "v4-loopback",
                datagram.payload.readByteArray(datagram.payload.remaining()).decodeToString(),
            )
        }
}
