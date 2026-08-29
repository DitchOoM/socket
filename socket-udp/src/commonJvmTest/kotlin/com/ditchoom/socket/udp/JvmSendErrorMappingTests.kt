package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import java.nio.channels.DatagramChannel as NioChannel

/**
 * #457: the JVM/Android backend mapped every `IOException` a send threw onto
 * [DatagramSendError.Transport], so [DatagramSendError.Unreachable] — the one member that means "this
 * path is gone", and the one a migration trigger or an ICE agent branches on — could not be constructed
 * on the JVM at all. A trigger wired to it looked correct and could never fire.
 *
 * These drive the production send loop ([writeAbsorbingBackpressure]) with a writer that throws exactly
 * what the JDK's natives throw. The types and `strerror` phrases are not invented: each is either
 * measured on JDK 21 / macOS 26 (`java SendProbe.java`, table in PR #457's description) or read off
 * jdk21u and AOSP libcore, with the producing line cited in [jvmSendErrorOf]'s KDoc. Every mapped case
 * here reported `Transport` before the fix.
 *
 * Through a stub writer rather than a socket because the host decides whether a destination is
 * unroutable: on this Mac `240.0.0.1` drew `ENETUNREACH` on one run and was quietly forwarded to the
 * default gateway on the next, once a VPN's `utun` default route appeared. The conditions loopback
 * *can* produce on demand are exercised on a real channel in [JvmSendErrorRealSocketTests].
 */
class JvmSendErrorMappingTests {
    private val neverWaits: suspend (Duration) -> WriteReadiness = { error("a throwing write must not wait on readiness") }

    private fun report(
        thrown: IOException,
        size: Int = 64,
    ): DatagramSendError =
        runBlocking {
            assertFailsWith<DatagramSendException> {
                writeAbsorbingBackpressure(ByteBuffer.allocate(size), { throw thrown }, neverWaits)
            }.error
        }

    // --- Unreachable: the member #457 is about ---------------------------------------------------

    /** `send0` path, `EHOSTUNREACH`: `Net.c handleSocketErrorWithMessage` names a dedicated type. */
    @Test
    fun noRouteToHostException_reportsUnreachable() {
        val error = assertIs<DatagramSendError.Unreachable>(report(NoRouteToHostException("No route to host")))
        assertEquals(ERRNO_NOT_SURFACED, error.errno, "NIO never exposes the errno; the member must not invent one")
    }

    /** `send0` path, `ENETUNREACH`: `Net.c`'s default arm, a `SocketException` whose message is `strerror`. */
    @Test
    fun socketExceptionNetworkIsUnreachable_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(SocketException("Network is unreachable")))
    }

    /**
     * `write0` path (a connected channel), `ENETUNREACH`: `IOUtil.c convertReturnVal` throws a **bare**
     * `IOException` — measured as `java.io.IOException("Network is unreachable")` for a connected
     * write to `240.0.0.1`. No type carries the condition on this path; only the phrase does.
     */
    @Test
    fun bareIOExceptionNetworkIsUnreachable_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(IOException("Network is unreachable")))
    }

    /**
     * `write0` path, `EHOSTUNREACH`: measured as `java.io.IOException("No route to host")` for a
     * connected write from `::1` to `2001:db8::1`. The same errno that is `NoRouteToHostException` on
     * the unconnected path has no dedicated type here.
     */
    @Test
    fun bareIOExceptionNoRouteToHost_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(IOException("No route to host")))
    }

    /** `ENETDOWN`: the interface itself went away — the Android handoff case #457 names. */
    @Test
    fun networkIsDown_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(SocketException("Network is down")))
        assertIs<DatagramSendError.Unreachable>(report(IOException("Network is down")))
    }

    /** `EHOSTDOWN`: the next hop stopped answering neighbour discovery. */
    @Test
    fun hostIsDown_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(SocketException("Host is down")))
    }

    /** `EAFNOSUPPORT`: Darwin says "…by protocol family", glibc and bionic say "…by protocol". */
    @Test
    fun addressFamilyNotSupported_reportsUnreachable_onBothPhrasings() {
        assertIs<DatagramSendError.Unreachable>(report(SocketException("Address family not supported by protocol family")))
        assertIs<DatagramSendError.Unreachable>(report(SocketException("Address family not supported by protocol")))
    }

    /**
     * Android's `IoBridge`/`ErrnoException` phrasing wraps the same `strerror` text in
     * `"<call> failed: <ERRNO> (<text>)"`. NIO does not take that path today (see [jvmSendErrorOf]),
     * but the match is a `contains` so a device that ever does lands on the same member.
     */
    @Test
    fun androidErrnoExceptionPhrasing_reportsUnreachable() {
        assertIs<DatagramSendError.Unreachable>(report(SocketException("sendto failed: ENETUNREACH (Network is unreachable)")))
        assertIs<DatagramSendError.Unreachable>(report(SocketException("sendto failed: EHOSTUNREACH (No route to host)")))
    }

    // --- the other members the JDK can name -------------------------------------------------------

    /** `ECONNREFUSED` on both native paths: an ICMP port unreachable came back for an earlier datagram. */
    @Test
    fun portUnreachableException_reportsPortUnreachable() {
        assertIs<DatagramSendError.PortUnreachable>(report(PortUnreachableException()))
    }

    /** `EMSGSIZE` below the parity guard (an interface MTU with DF set, a small `SO_SNDBUF` on Linux). */
    @Test
    fun messageTooLong_reportsTooLarge_withTheAttemptedSize() {
        val onSend = assertIs<DatagramSendError.TooLarge>(report(SocketException("Message too long"), size = 1500))
        assertEquals(1500, onSend.attempted)
        assertEquals(65507, onSend.limit, "the advertised ceiling, as sendErrnoToError reports it")
        assertIs<DatagramSendError.TooLarge>(report(IOException("Message too long"), size = 1500))
    }

    /** `ENOBUFS`: Darwin's "the interface queue is full right now" — transient, as on K/N. */
    @Test
    fun noBufferSpaceAvailable_reportsWouldBlock() {
        assertIs<DatagramSendError.WouldBlock>(report(SocketException("No buffer space available")))
        assertIs<DatagramSendError.WouldBlock>(report(IOException("No buffer space available")))
    }

    /**
     * `EACCES`: measured as `BindException("Permission denied")` on the unconnected path — `Net.c`
     * files `EACCES` under `BindException` even for a send — and a bare `IOException` on the connected
     * one, both for a broadcast destination without `SO_BROADCAST`.
     */
    @Test
    fun permissionDenied_reportsNotPermitted() {
        val error = assertIs<DatagramSendError.NotPermitted>(report(BindException("Permission denied")))
        assertEquals(ERRNO_NOT_SURFACED, error.errno)
        assertIs<DatagramSendError.NotPermitted>(report(IOException("Permission denied")))
    }

    // --- what stays Transport, and why -----------------------------------------------------------

    /** A close racing the send keeps the JDK's own type as the detail; there is no closed member. */
    @Test
    fun closedChannelException_staysTransport_carryingTheJdkType() {
        val closed = ClosedChannelException()
        assertSame(closed, assertIs<DatagramSendError.Transport>(report(closed)).cause)
        val async = AsynchronousCloseException()
        assertSame(async, assertIs<DatagramSendError.Transport>(report(async)).cause)
    }

    /** Anything the mapping does not recognize keeps the exception, never a phrase guessed at. */
    @Test
    fun unrecognizedFailure_staysTransport_withTheCause() {
        val portZero = SocketException("Can't send to port 0")
        assertSame(portZero, assertIs<DatagramSendError.Transport>(report(portZero)).cause)
        // EADDRNOTAVAIL: the bound source cannot reach the destination. Measured on this Mac for a
        // socket bound to 127.0.0.1 sending off-host; K/N reports it as OsError, so it stays raw here.
        val notAvail = BindException("Can't assign requested address")
        assertSame(notAvail, assertIs<DatagramSendError.Transport>(report(notAvail)).cause)
        val bare = IOException("nope")
        assertSame(bare, assertIs<DatagramSendError.Transport>(report(bare)).cause)
        val noMessage = IOException()
        assertSame(noMessage, assertIs<DatagramSendError.Transport>(report(noMessage)).cause)
    }
}

/**
 * The same mapping reached through a real NIO channel, for the conditions loopback can produce on
 * demand. See [JvmSendErrorMappingTests] for why the routing-dependent members are driven through a
 * stub: a host's route table, not this library, decides whether `240.0.0.1` is unreachable today.
 */
@OptIn(ExperimentalDatagramApi::class)
class JvmSendErrorRealSocketTests {
    private fun payload(size: Int = 64): ReadBuffer =
        BufferFactory.deterministic().allocate(size).also { buffer ->
            repeat(size) { buffer.writeByte(0x41) }
            buffer.resetForRead()
        }

    /**
     * A connected socket learns of the peer's ICMP port unreachable on its **next** send: the kernel
     * parks `ECONNREFUSED` on the socket and `send(2)` returns it (Linux consumes it in
     * `sock_alloc_send_pskb`, Darwin on the next `sosend`). Measured on this Mac: write #1 returns 64,
     * write #2 throws `PortUnreachableException`, and so on alternately. Deterministic on loopback —
     * the only way it does not happen is a host that filters ICMP on `lo`, which is a loud skip, not a
     * pass.
     */
    @Test
    fun icmpPortUnreachable_onConnectedLoopback_reportsPortUnreachable() =
        runBlocking(Dispatchers.IO) {
            // A port nothing listens on: bind, read the port, close. The 4-tuple is what draws the ICMP.
            val closedPort = UdpSocket.bind("127.0.0.1", 0).let { probe -> probe.localAddress.port.also { probe.close() } }
            val sender = UdpSocket.connect("127.0.0.1", closedPort)
            try {
                withTimeout(10_000) {
                    repeat(10) {
                        try {
                            sender.send(payload())
                        } catch (e: DatagramSendException) {
                            assertIs<DatagramSendError.PortUnreachable>(
                                e.error,
                                "the JDK's PortUnreachableException must reach a consumer as the member, not as Transport",
                            )
                            return@withTimeout
                        }
                        delay(100)
                    }
                    recordSkip(
                        JvmSendErrorRealSocketTests::class,
                        SkipReason.HostBehaviourDiffers(
                            "ten sends to a closed loopback port on a connected socket all returned normally — this host " +
                                "does not deliver ICMP port unreachable on lo, so the send-side mapping cannot be reached here",
                        ),
                        gate = SkipGate.HostCannotProvideIt("ICMP port unreachable on loopback"),
                    )
                }
            } finally {
                sender.close()
            }
        }

    /**
     * The NIO channel closed underneath this library's own `closed` flag — the race `close()` and an
     * in-flight send can run — reports the JDK's type as the detail, as the readiness-wait arm already
     * does, so the two shutdown paths agree.
     */
    @Test
    fun nioChannelClosedUnderTheSend_reportsTransportWithClosedChannelException() =
        runBlocking(Dispatchers.IO) {
            val nio = NioChannel.open()
            nio.configureBlocking(false)
            nio.bind(InetSocketAddress("127.0.0.1", 0))
            val local = InternedJvmSocketAddress(nio.localAddress as InetSocketAddress)
            val channel = AddressedNioDatagramChannel(channel = nio, localAddress = local)
            nio.close()
            val thrown = assertFailsWith<DatagramSendException> { channel.send(payload(), to = local) }
            assertIs<ClosedChannelException>(assertIs<DatagramSendError.Transport>(thrown.error).cause)
            channel.close()
        }

    /**
     * Opportunistic: the first destination this host refuses to route reports [DatagramSendError.Unreachable].
     * Three rungs, because which one a host refuses depends on its route table (Darwin forwards an
     * unconnected `sendto` to a reserved address up the default route and refuses the connected
     * `send`; Linux refuses the `connect()` itself, which is not a send). A host that refuses none of
     * them records a loud skip — the mapping is pinned regardless by [JvmSendErrorMappingTests].
     */
    @Test
    fun unroutableDestination_reportsUnreachable_whereTheHostRefusesIt() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30_000) {
                val rungs =
                    listOf<Pair<String, suspend () -> Unit>>(
                        "connected write to 240.0.0.1" to {
                            val s = UdpSocket.connect("240.0.0.1", 9)
                            try {
                                s.send(payload())
                            } finally {
                                s.close()
                            }
                        },
                        "addressed send to 240.0.0.1" to {
                            val s = UdpSocket.bind("0.0.0.0", 0)
                            try {
                                s.send(payload(), to = UdpSocket.resolve("240.0.0.1", 9))
                            } finally {
                                s.close()
                            }
                        },
                        "addressed send to 2001:db8::1 from a v6 socket" to {
                            val nio = NioChannel.open(StandardProtocolFamily.INET6)
                            nio.configureBlocking(false)
                            nio.bind(InetSocketAddress("::", 0))
                            val s = AddressedNioDatagramChannel(nio, InternedJvmSocketAddress(nio.localAddress as InetSocketAddress))
                            try {
                                s.send(payload(), to = UdpSocket.resolve("2001:db8::1", 9))
                            } finally {
                                s.close()
                            }
                        },
                    )
                val outcomes = mutableListOf<String>()
                for ((name, rung) in rungs) {
                    try {
                        rung()
                        outcomes += "$name: accepted"
                    } catch (e: DatagramSendException) {
                        assertIs<DatagramSendError.Unreachable>(
                            e.error,
                            "$name: the host refused the route, and that must reach a consumer as Unreachable",
                        )
                        return@withTimeout
                    } catch (e: IOException) {
                        // connect()/bind() refused before any send happened — not this library's send path.
                        outcomes += "$name: ${e::class.simpleName}(${e.message}) before the send"
                    }
                }
                recordSkip(
                    JvmSendErrorRealSocketTests::class,
                    SkipReason.HostBehaviourDiffers("no rung was refused at send time on this host: $outcomes"),
                    gate = SkipGate.HostCannotProvideIt("an unroutable destination"),
                )
            }
        }
}
