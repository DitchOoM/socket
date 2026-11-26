package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.DriverUdpChannelFactory
import com.ditchoom.socket.quic.InternalQuicApi
import com.ditchoom.socket.quic.LocalEndpointSupport
import com.ditchoom.socket.quic.QuicLocalEndpoint
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.hostSockAddrLayout
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The factory the core client asks where its route leaves from declares [LocalEndpointSupport.Bindable], and what that
 * buys is true where the core opens its own sockets: the route probe names the address a loopback peer is reached from,
 * a caller-named local port is bound exactly, and one that cannot be bound is refused, never replaced by another port the
 * caller would then be told is its own. Network.framework assigns a connection's endpoint, so the named-port tests skip
 * there, loudly.
 */
@OptIn(InternalQuicApi::class)
class CoreLocalEndpointSupportTests {
    /** The factory [coreConnect] builds for a peer on loopback, with the path open the route tests use. */
    private suspend fun factoryFor(port: Int): DriverUdpChannelFactory =
        DriverUdpChannelFactory(
            UdpSocket.resolve("127.0.0.1", port),
            SocketAddressCodec(hostSockAddrLayout),
            BufferFactory.network(),
            BufferFactory.network(),
            MAX_DATAGRAM,
            LocalEndpointSupport.Bindable,
        )

    /** A port this process just held, which the host accepts a bind on again. */
    private suspend fun freePort(factory: DriverUdpChannelFactory): Int {
        val path = factory.openPath(null, 0)
        try {
            return path.localEndpoint.port
        } finally {
            path.channel.close()
            path.release()
        }
    }

    /** Runs [body] where the core opens its own sockets; elsewhere records that it did not. */
    private fun whereTheCoreOwnsItsSockets(
        site: String,
        body: suspend () -> Unit,
    ) {
        when (clientRideOfThisPlatform) {
            is ClientRide.OwnSocket -> runBlocking { body() }
            is ClientRide.NetworkFramework ->
                recordSkip(
                    "CoreLocalEndpointSupportTests.$site",
                    SkipReason.HostBehaviourDiffers("Network.framework assigns a connection's local endpoint; a named port is advisory"),
                    SkipGate.HostCannotProvideIt("a socket bound to a named local port"),
                )
        }
    }

    @Test
    fun theRouteProbeNamesTheAddressALoopbackPeerIsReachedFrom() =
        runBlocking<Unit> {
            assertEquals(RouteSource.Resolved("127.0.0.1"), factoryFor(PEER_PORT).routeSourceAddress())
        }

    @Test
    fun theFactoryBindsThePortItIsAsked() =
        whereTheCoreOwnsItsSockets("theFactoryBindsThePortItIsAsked") {
            val factory = factoryFor(PEER_PORT)
            assertEquals(LocalEndpointSupport.Bindable, factory.localEndpointSupport)
            val port = freePort(factory)
            val path = factory.openPath("127.0.0.1", port)
            try {
                assertEquals(QuicLocalEndpoint("127.0.0.1", port), path.localEndpoint, "the socket bound the endpoint it was asked for")
            } finally {
                path.channel.close()
                path.release()
            }
        }

    @Test
    fun aNamedPortThatIsTakenIsRefusedNotReplacedByAnotherPort() =
        whereTheCoreOwnsItsSockets("aNamedPortThatIsTakenIsRefusedNotReplacedByAnotherPort") {
            val holder = UdpSocket.bind("127.0.0.1", 0)
            try {
                val taken = holder.localAddress.port
                // A host that hands the port out twice answers the same port; one that refuses it answers a failure.
                runCatching { factoryFor(PEER_PORT).openPath("127.0.0.1", taken) }.onSuccess { path ->
                    val bound = path.localEndpoint.port
                    path.channel.close()
                    path.release()
                    assertEquals(taken, bound, "a named port was reported bound as another")
                }
            } finally {
                holder.close()
            }
        }

    private companion object {
        /** The peer's port: a connected UDP socket is opened toward it, and nothing is sent. */
        const val PEER_PORT = 9
    }
}
