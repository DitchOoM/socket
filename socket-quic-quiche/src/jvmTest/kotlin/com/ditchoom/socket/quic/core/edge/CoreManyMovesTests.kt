package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * One connection that moves fifty times keeps what it holds bounded: the server routes the ids of the paths it
 * follows and drops those the client retired, and the client holds the receive infos of its current and previous
 * address only.
 */
class CoreManyMovesTests {
    private class FakeMonitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(WIFI_BEFORE)

        override fun close() = Unit
    }

    /** The receive infos made and not yet freed through it. */
    private class CountingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        private val made = AtomicInteger()

        fun live(): Int = made.get()

        override fun recvInfoNew(
            fromAddr: Long,
            fromAddrLen: Int,
            toAddr: Long,
            toAddrLen: Int,
        ): QuicheRecvInfo {
            made.incrementAndGet()
            return real.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
        }

        override fun recvInfoFree(info: QuicheRecvInfo) {
            made.decrementAndGet()
            real.recvInfoFree(info)
        }
    }

    @Test
    fun fiftyMovesOnOneConnectionKeepTheServersRoutesAndTheClientsReceiveInfosBounded() =
        runBlocking {
            skipOnMissingNativeLib(CoreManyMovesTests::class) {
                withCoreEchoServer { server ->
                    val monitor = FakeMonitor()
                    var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                    val api = CountingApi(loadQuicheApi())
                    val client =
                        onIo {
                            coreConnect(
                                mismatch = MismatchResponse.Fail,
                                host = "127.0.0.1",
                                port = server.port,
                                serverName = "localhost",
                                options =
                                    coreTestOptions.copy(
                                        networkMonitor = NetworkMonitorSource.Supplied(monitor),
                                        migration = MigrationPolicy.Automatic,
                                    ),
                                factory = BufferFactory.Default,
                                api = api,
                                timeout = 10.seconds,
                                readPolicy = ReadPolicy.Bounded(30.seconds),
                                writePolicy = WritePolicy.Bounded(30.seconds),
                                routeSource = { source },
                            )
                        }
                    val warm = Random(0).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))
                    var mostRoutes = 0
                    var mostInfos = 0
                    for (move in 1..MOVES) {
                        // The wildcard and the loopback address alternate: each is another address than the socket leaves from.
                        source = RouteSource.Resolved(if (move % 2 == 1) "0.0.0.0" else "127.0.0.1")
                        monitor.state.value = if (move % 2 == 1) WIFI_AFTER else WIFI_BEFORE
                        withTimeout(10.seconds) { while (migrated(client) < move) delay(10) }
                        val payload = Random(move).nextBytes(1_000)
                        val echoed = withTimeout(10.seconds) { echoOver(client.connection.openStream(), payload) }
                        assertContentEquals(payload, echoed, "echo after move $move")
                        mostRoutes = maxOf(mostRoutes, server.parts.routes)
                        mostInfos = maxOf(mostInfos, api.live())
                    }
                    assertEquals(MOVES, migrated(client))
                    client.connection.close(QuicError.NoError)
                    assertTrue(mostRoutes <= MOST_ROUTES, "the server routed up to $mostRoutes ids over $MOVES moves")
                    assertTrue(mostInfos <= MOST_INFOS, "the client held up to $mostInfos receive infos over $MOVES moves")
                }
            }
        }

    private suspend fun migrated(client: CoreClient): Int {
        val recorded = ArrayList<TraceEvent>()
        client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded.filterIsInstance<TraceEvent.PathState>().count { it.phase == "Migrated" }
    }

    private companion object {
        const val MOVES = 50

        /** Its own id and the original, and the spare ids the peer may hold at once. */
        const val MOST_ROUTES = 12

        /** The current address and the previous. */
        const val MOST_INFOS = 4
        val BEFORE = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
        val AFTER = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
        val WIFI_BEFORE: NetworkState = NetworkState.Routable(BEFORE, InternetAccess.Observed.Confirmed)
        val WIFI_AFTER: NetworkState = NetworkState.Routable(AFTER, InternetAccess.Observed.Pending)
    }
}
