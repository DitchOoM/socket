@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.udp.UdpBindError
import com.ditchoom.socket.udp.UdpBindException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import java.nio.channels.DatagramChannel as NioChannel

/**
 * A wildcard server's per-address bind: every local address on one port, or a typed refusal.
 *
 * Driven through [bindEachAddress]'s bind parameter by a fake host whose port tables the test
 * writes, so each collision happens on every run rather than when the machine's socket churn lines it
 * up; the two real-socket cases pin the JDK's side of the contract.
 */
class SharedPortBindTests {
    /**
     * An address enumerated a moment ago and gone by the bind — a rotated IPv6 temporary address, a VPN
     * torn down, an IPv6 address still in duplicate-address detection — is not this host's to serve.
     * No port can fix that, so it is dropped and named; the rest of the host is served.
     */
    @Test
    fun anAddressThatIsNoLongerThisHostsIsDroppedAndNamedNotFatal() =
        runBlocking {
            val host = FakeHost(ephemeral = mapOf(QUIET to (5000..5099)), unavailable = setOf(GONE))

            val bind = assertBound(host, listOf(GONE, QUIET, OTHER), port = 0)

            assertEquals(listOf(QUIET, OTHER), bind.members.map { it.localAddress.host })
            assertEquals(listOf(GONE), bind.unavailable.map { it.host }, "the dropped address must be named")
            assertIs<UdpBindError.LocalAddressUnavailable>(bind.unavailable.single().error)
            assertEquals(1, host.callsFor(GONE), "an unavailable address is not worth a second draw")
        }

    /** The same, on the real JDK: `BindException("Can't assign requested address")` is not a collision. */
    @Test
    fun anAddressTheKernelSaysIsNotLocalIsDroppedOnARealBind() =
        runBlocking<Unit> {
            val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())
            // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): never assigned to a host.
            val bind =
                try {
                    bindEachAddress(listOf("127.0.0.1", "192.0.2.1"), 0) { h, p -> bindServerMember(h, p, pool) }
                } catch (e: Throwable) {
                    fail("an address this host does not have must be dropped, not fail the server; threw $e")
                }
            val bound = assertIs<SharedPortBind.Bound>(bind)
            try {
                assertEquals(listOf("127.0.0.1"), bound.members.map { it.localAddress.host })
                assertEquals(listOf("192.0.2.1"), bound.unavailable.map { it.host })
                assertIs<UdpBindError.LocalAddressUnavailable>(bound.unavailable.single().error)
            } finally {
                bound.members.forEach { it.close() }
            }
        }

    @Test
    fun whenNoAddressIsThisHostsAnyMoreTheCallerIsToldSo() =
        runBlocking {
            val host = FakeHost(unavailable = setOf(GONE, QUIET))

            val bind = bindEachAddress(listOf(GONE, QUIET), 0, host::bind)

            val none = assertIs<SharedPortBind.NoAddressAvailable>(bind)
            assertEquals(listOf(GONE, QUIET), none.unavailable.map { it.host })
        }

    /**
     * An address crowded with sockets collides with every port the quiet address draws. Redrawing from
     * the quiet address again is a lottery; drawing from the address that collided finds a port that
     * is free exactly where it was not.
     */
    @Test
    fun aCollisionIsRedrawnFromTheAddressThatCollided() =
        runBlocking {
            val host =
                FakeHost(
                    ephemeral = mapOf(QUIET to (5000..5099), CROWDED to (6000..6099)),
                    held = mapOf(CROWDED to (5000..5099).toSet()),
                )

            val bind = assertBound(host, listOf(QUIET, CROWDED), port = 0)

            val ports = bind.members.map { it.localAddress.port }.toSet()
            assertEquals(1, ports.size, "every member must share one port; got $ports")
            assertEquals(setOf(QUIET, CROWDED), bind.members.map { it.localAddress.host }.toSet())
            assertEquals(bind.members.toSet(), host.open.toSet(), "a collided draw must release what it had bound")
        }

    /** A port the caller named is theirs: taken on one address is a refusal that names that address. */
    @Test
    fun aNamedPortTakenOnOneAddressIsRefusedNamingThatAddress() =
        runBlocking {
            val host = FakeHost(held = mapOf(CROWDED to setOf(4433)))

            val refusal = assertRefused(host, listOf(QUIET, CROWDED), port = 4433)

            assertEquals(CROWDED, refusal.host)
            assertEquals(4433, refusal.port)
            assertIs<UdpBindError.AddressInUse>(refusal.error)
            assertEquals(1, host.callsFor(CROWDED), "a named port is never redrawn")
            assertTrue(host.open.isEmpty(), "a refused bind must leave nothing open; held ${host.open}")
        }

    /** A refusal no other port can change — here, policy — fails at once rather than redrawing. */
    @Test
    fun aRefusalThatIsNotACollisionIsNotRedrawn() =
        runBlocking {
            val host = FakeHost(ephemeral = mapOf(QUIET to (5000..5099)), refused = mapOf(CROWDED to UdpBindError.NotPermitted(0)))

            val refusal = assertRefused(host, listOf(QUIET, CROWDED), port = 0)

            assertEquals(CROWDED, refusal.host)
            assertIs<UdpBindError.NotPermitted>(refusal.error)
            assertEquals(1, host.callsFor(CROWDED))
            assertTrue(host.open.isEmpty(), "a refused bind must leave nothing open; held ${host.open}")
        }

    /** Collisions on every draw end in a bounded, typed refusal — and nothing left holding a port. */
    @Test
    fun collisionsOnEveryDrawEndInATypedRefusal() =
        runBlocking {
            val host =
                FakeHost(
                    ephemeral = mapOf(QUIET to (5000..5099), CROWDED to (6000..6099)),
                    held = mapOf(CROWDED to (5000..5099).toSet(), QUIET to (6000..6099).toSet()),
                )

            val refusal = assertRefused(host, listOf(QUIET, CROWDED), port = 0)

            assertIs<UdpBindError.AddressInUse>(refusal.error)
            assertTrue(refusal.suppressed.all { it is UdpBindException }, "each earlier collision rides along, typed")
            assertTrue(host.calls.size < 64, "the redraw must be bounded; made ${host.calls.size} binds")
            assertTrue(host.open.isEmpty(), "a refused bind must leave nothing open; held ${host.open}")
        }

    /**
     * A member receives exactly as the single server socket [bindServerSocket] binds does: staged at
     * QUIC's datagram size, not the 64 KB UDP ceiling — visible as where an oversized datagram is cut.
     */
    @Test
    fun aMemberReceivesLikeTheSingleServerSocket() =
        runBlocking<Unit> {
            val reference = receiveThrough { pool -> QuicPortBinding.Own(host = "127.0.0.1").bindServerSocket(pool) }
            val member = receiveThrough { pool -> bindServerMember("127.0.0.1", 0, pool) }
            assertEquals(reference, member, "a member must stage and pool its receives as the single server socket does")
        }

    /** Received lengths for an oversized datagram then two ordinary ones. */
    private suspend fun receiveThrough(open: suspend (BufferPool) -> AddressedDatagramChannel): List<Int> {
        val pool = QuicheDriver.newRecvBufPool(BufferFactory.deterministic())
        val channel = open(pool)
        try {
            return listOf(4000, 1200, 1200).map { size ->
                NioChannel.open().use { it.send(ByteBuffer.allocate(size), InetSocketAddress("127.0.0.1", channel.localAddress.port)) }
                val received = assertIs<DatagramReadResult.Received>(withTimeout(5.seconds) { channel.receive() })
                received.datagram.payload
                    .remaining()
                    .also { received.datagram.payload.freeNativeMemory() }
            }
        } finally {
            channel.close()
        }
    }

    private suspend fun assertBound(
        host: FakeHost,
        addresses: List<String>,
        port: Int,
    ): SharedPortBind.Bound =
        try {
            assertIs<SharedPortBind.Bound>(bindEachAddress(addresses, port, host::bind))
        } catch (e: Throwable) {
            fail("the bind must succeed; calls=${host.calls}; threw $e")
        }

    private suspend fun assertRefused(
        host: FakeHost,
        addresses: List<String>,
        port: Int,
    ): UdpBindException {
        val refused = runCatching { bindEachAddress(addresses, port, host::bind) }.exceptionOrNull()
        return assertIs<UdpBindException>(refused, "the refusal must be typed and name the address; got $refused")
    }

    /**
     * Port tables the test writes. A port-0 bind takes the first of the address's [ephemeral] ports
     * that is free on that address, as a kernel does; a named port is refused where it is [held] or
     * already bound here.
     */
    private class FakeHost(
        private val ephemeral: Map<String, IntRange> = emptyMap(),
        private val held: Map<String, Set<Int>> = emptyMap(),
        private val unavailable: Set<String> = emptySet(),
        private val refused: Map<String, UdpBindError> = emptyMap(),
    ) {
        val calls = mutableListOf<Pair<String, Int>>()
        private val live = mutableListOf<FakeMember>()
        val open: List<FakeMember> get() = live.filter { it.isOpen }

        fun callsFor(host: String): Int = calls.count { it.first == host }

        private fun takenOn(host: String): Set<Int> =
            held[host].orEmpty() + open.filter { it.localAddress.host == host }.map { it.localAddress.port }

        @Suppress("RedundantSuspendModifier")
        suspend fun bind(
            host: String,
            port: Int,
        ): AddressedDatagramChannel {
            calls += host to port
            if (host in unavailable) throw UdpBindException(host, port, UdpBindError.LocalAddressUnavailable(0))
            refused[host]?.let { throw UdpBindException(host, port, it) }
            val taken = takenOn(host)
            val chosen = if (port == 0) ephemeral.getValue(host).first { it !in taken } else port
            if (chosen in taken) throw UdpBindException(host, port, UdpBindError.AddressInUse(0))
            return FakeMember(host, chosen).also { live += it }
        }
    }

    private class FakeMember(
        host: String,
        port: Int,
    ) : AddressedDatagramChannel {
        override val localAddress: SocketAddress = SocketAddress.ofLiteral(host, port)
        override var isOpen: Boolean = true
            private set
        override val maxWritableSize: Int = 1200
        override val capabilities: DatagramCapabilities = DatagramCapabilities()

        override suspend fun receive(): DatagramReadResult = awaitCancellation()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) = Unit

        override fun close() {
            isOpen = false
        }

        override fun toString(): String = "${localAddress.host}:${localAddress.port}"
    }

    private companion object {
        // Fake-host addresses: literals, so SocketAddress.ofLiteral accepts them; never bound for real.
        const val QUIET = "10.0.0.1"
        const val CROWDED = "10.0.0.2"
        const val OTHER = "10.0.0.3"
        const val GONE = "2001:db8::dead"
    }
}
