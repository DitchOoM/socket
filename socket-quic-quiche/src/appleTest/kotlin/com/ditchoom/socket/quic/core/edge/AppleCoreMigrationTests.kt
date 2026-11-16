package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.Granted
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A client riding Network.framework follows a route that leaves from another address: a UDP connection never re-homes,
 * so the move is a second connection to the peer, and the first is cancelled once the second is ready.
 */
class AppleCoreMigrationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    private class FakeMonitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(WIFI_BEFORE)

        override fun close() = Unit
    }

    @Test
    fun aRouteFromAnotherAddressMovesTheNetworkFrameworkConnectionAndTheConnectionEchoesFromIt() = migrates(listOf("0.0.0.0"))

    @Test
    fun eachOfThreeSuccessiveRoutesSwapsInANewConnectionAndEveryEchoIsAnswered() = migrates(listOf("0.0.0.0", "127.0.0.2", "0.0.0.0"))

    private fun migrates(routes: List<String>) =
        coreTest("AppleCoreMigrationTests.migrates$routes") {
            withCoreEchoServer { server ->
                val monitor = FakeMonitor()
                var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                val client =
                    coreConnect(
                        host = "127.0.0.1",
                        port = server.port,
                        serverName = "localhost",
                        options =
                            coreTestOptions.copy(
                                networkMonitor = NetworkMonitorSource.Supplied(monitor),
                                migration = MigrationPolicy.Automatic,
                            ),
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        timeout = 10.seconds,
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                        loops = loops,
                        routeSource = { source },
                    )
                try {
                    assertIs<Granted.NotReported>(client.socket.granted, "the client's datagrams ride Network.framework")
                    val warm = Random(1).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))

                    for ((n, route) in routes.withIndex()) {
                        source = RouteSource.Resolved(route)
                        monitor.state.value = if (n % 2 == 0) WIFI_AFTER else WIFI_BEFORE
                        val payload = Random(7 + n).nextBytes(2_000)
                        val echoed = withTimeout(5.seconds) { echoOver(client.connection.openStream(), payload) }
                        assertContentEquals(payload, echoed, "echo after move ${n + 1}")
                        withTimeout(5.seconds) { while (marksOf(client).count { it == "Migrated" } <= n) delay(20) }
                    }
                    assertEquals(routes.size, marksOf(client).count { it == "Migrated" }, "every move migrated: ${marksOf(client)}")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private suspend fun marksOf(client: CoreClient): List<String> {
        val recorded = ArrayList<TraceEvent>()
        client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
    }

    private companion object {
        val BEFORE = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
        val AFTER = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
        val WIFI_BEFORE: NetworkState = NetworkState.Routable(BEFORE, InternetAccess.Observed.Confirmed)
        val WIFI_AFTER: NetworkState = NetworkState.Routable(AFTER, InternetAccess.Observed.Pending)
    }
}
