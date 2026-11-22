@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicSendRefusal
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.UdpSocketChannelFactory
import com.ditchoom.socket.quic.UnresolvedRouteSourceException
import com.ditchoom.socket.quic.core.conn.AccessGuard
import com.ditchoom.socket.quic.core.conn.PermissionRead
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.udp.ERRNO_NOT_SURFACED
import com.ditchoom.socket.udp.UdpConnectError
import com.ditchoom.socket.udp.UdpConnectException
import kotlinx.coroutines.runBlocking
import java.net.NoRouteToHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A connect the system refuses as it refuses an app the local network fails at once, naming the access and its remedy;
 * one it refuses for any other reason fails as it did before. Documentation addresses: nothing leaves the host.
 */
class CoreAccessDeniedTests {
    private class RefusesEverySend(
        private val real: DatagramBatchIo,
        private val why: UdpError,
    ) : DatagramBatchIo by real {
        override fun send(batch: SendBatch): SendBatchResult = SendBatchResult.Partial(0, why)
    }

    private val docNet = { listOf(AttachedPrefix(PathKey(4, 0, 0L, 0xC000_0200L), 24)) } // 192.0.2.0/24
    private val noRoute: suspend (UdpSocketChannelFactory) -> RouteSource = {
        RouteSource.Unresolved(
            RouteProbeFailure.ProbeRefused(
                UdpConnectException(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED), NoRouteToHostException("No route to host")),
            ),
        )
    }

    private suspend fun connect(
        host: String,
        access: AccessGuard,
        wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
        routeSource: suspend (UdpSocketChannelFactory) -> RouteSource = { it.routeSourceAddress() },
    ): CoreClient =
        coreConnect(
            host = host,
            port = 4433,
            serverName = "localhost",
            options = coreTestOptions.copy(handshakePolicy = HandshakePolicy.Bounded(BOUND)),
            factory = BufferFactory.Default,
            api = loadQuicheApi(),
            timeout = 10.seconds,
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
            wrapIo = wrapIo,
            routeSource = routeSource,
            flight = TEST_FLIGHT,
            access = access,
        )

    /** The connect's failure and how long it took. */
    private suspend fun failure(connect: suspend () -> CoreClient): Pair<Throwable, Duration> {
        val began = TimeSource.Monotonic.markNow()
        try {
            connect()
        } catch (failed: Exception) {
            return failed to began.elapsedNow()
        }
        error("the connect cannot complete when nothing is sent")
    }

    private fun quiche(test: suspend () -> Unit) = runBlocking { skipOnMissingNativeLib(CoreAccessDeniedTests::class) { test() } }

    @Test
    fun androidWithThePermissionWithheldFailsAtOnceNamingThePermission() =
        quiche {
            val guard = AccessGuard.AndroidPermission({ PermissionRead.Withheld }, docNet)
            val (thrown, took) =
                failure { connect("192.0.2.10", guard, wrapIo = { RefusesEverySend(it, UdpError.PermissionDenied) }) }

            val closed = assertIs<QuicCloseException>(thrown)
            val end = assertIs<QuicConnectionEnd.AccessDenied>(closed.end)
            assertSame(QuicAccessDenial.AndroidPermissionNotHeld, end.denial)
            assertEquals(NetworkAccess.LocalNetwork, end.denial.access)
            assertTrue("ACCESS_LOCAL_NETWORK" in closed.message.orEmpty(), "message was: ${closed.message}")
            assertTrue(took < 2.seconds, "the denial took $took: it waited instead of failing on the refused send")
        }

    /** Data Saver, a background firewall chain or VPN lockdown refuses sends as not permitted too: not the permission. */
    @Test
    fun androidWithThePermissionWithheldDoesNotBlameItForLoopbackOrInternetPeers() =
        quiche {
            val guard = AccessGuard.AndroidPermission({ PermissionRead.Withheld }, docNet)
            for (host in listOf("127.0.0.1", "203.0.113.10")) {
                val (thrown, took) =
                    failure { connect(host, guard, wrapIo = { RefusesEverySend(it, UdpError.PermissionDenied) }) }

                val end = assertIs<QuicConnectionEnd.HandshakeSendRefused>(assertIs<QuicCloseException>(thrown).end, host)
                assertEquals(QuicSendRefusal.PermissionDenied, end.refusal, host)
                assertTrue(took >= BOUND, "$host ended after $took, before the bound")
            }
        }

    @Test
    fun macosNoRouteOnTheRouteProbeToAnOnLinkPeerFailsAtOnceAsLikelyLocalNetworkPrivacy() =
        quiche {
            val (thrown, took) = failure { connect("192.0.2.10", AccessGuard.MacosPrivacy(docNet), routeSource = noRoute) }

            val closed = assertIs<QuicCloseException>(thrown)
            val end = assertIs<QuicConnectionEnd.AccessDenied>(closed.end)
            assertIs<QuicAccessDenial.Likely>(end.denial)
            assertSame(QuicAccessDenial.MacosNoRouteOnLink, end.denial)
            assertTrue("Local Network" in closed.message.orEmpty(), "message was: ${closed.message}")
            assertTrue(took < 2.seconds, "took $took")
        }

    @Test
    fun macosNoRouteToAPeerOffTheLinkStaysAnUnresolvedRoute() =
        quiche {
            val (thrown, _) = failure { connect("203.0.113.10", AccessGuard.MacosPrivacy(docNet), routeSource = noRoute) }

            assertIs<UnresolvedRouteSourceException>(thrown)
        }

    @Test
    fun macosNoRouteOnALaterRouteProbeToTheSamePeerStaysAnUnresolvedRoute() =
        quiche {
            val guard = AccessGuard.MacosPrivacy(docNet)
            failure { connect("192.0.2.11", guard, routeSource = noRoute) }

            val (thrown, _) = failure { connect("192.0.2.11", guard, routeSource = noRoute) }

            assertIs<UnresolvedRouteSourceException>(thrown, "a reconnect may follow a neighbour that stopped answering")
        }

    /**
     * After a neighbour stops answering, macOS refuses the first send of a new socket to it at once, for about twenty
     * seconds: a dead host on the link looks like the privacy refusal, so no refused send is judged on macOS.
     */
    @Test
    fun macosEverySendToAnOnLinkPeerRefusedWithNoRouteWaitsOutTheBound() =
        quiche {
            val (thrown, took) =
                failure {
                    connect("192.0.2.10", AccessGuard.MacosPrivacy(docNet), wrapIo = { RefusesEverySend(it, UdpError.HostUnreachable) })
                }

            val end = assertIs<QuicConnectionEnd.HandshakeSendRefused>(assertIs<QuicCloseException>(thrown).end)
            assertEquals(QuicSendRefusal.HostUnreachable, end.refusal)
            assertTrue(took >= BOUND, "ended after $took, before the bound")
        }

    @Test
    fun macosEverySendToAPeerOffTheLinkRefusedWithNoRouteStillWaitsOutTheBound() =
        quiche {
            val (thrown, took) =
                failure {
                    connect(
                        "203.0.113.10",
                        AccessGuard.MacosPrivacy(docNet),
                        wrapIo = { RefusesEverySend(it, UdpError.HostUnreachable) },
                    )
                }

            val end = assertIs<QuicConnectionEnd.HandshakeSendRefused>(assertIs<QuicCloseException>(thrown).end)
            assertEquals(QuicSendRefusal.HostUnreachable, end.refusal)
            assertTrue(took >= BOUND, "ended after $took, before the bound")
        }

    @Test
    fun theProbeRefusalOfAnUnguardedPlatformStaysAnUnresolvedRoute() =
        quiche {
            val (thrown, _) = failure { connect("192.0.2.10", AccessGuard.Unguarded, routeSource = noRoute) }

            assertIs<UnresolvedRouteSourceException>(thrown)
        }

    private companion object {
        val BOUND = 2.seconds
    }
}
