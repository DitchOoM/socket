package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A client that supplied a network monitor and asked for its observations to be recorded gets the monitor's identity,
 * reachability and declared capability in the same trace as its datagrams, so a recording shows what the network did
 * beside what the connection did.
 */
class CoreConnectivityTraceTests {
    private class SettableMonitor : NetworkMonitor {
        override val state =
            MutableStateFlow<NetworkState>(NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, 1L), InternetAccess.Unobserved))

        override fun close() = Unit
    }

    @Test
    fun theMonitorsStateReachesAClientsTraceBesideItsDatagrams() =
        runBlocking {
            skipOnMissingNativeLib(CoreConnectivityTraceTests::class) {
                withCoreEchoServer { server ->
                    val lines = Collections.synchronizedList(mutableListOf<String>())
                    val monitor = SettableMonitor()
                    val migratedTo = NetworkId.Link(NetworkKind.Wifi, 9L)
                    val options =
                        coreTestOptions.copy(
                            networkMonitor = NetworkMonitorSource.Supplied(monitor),
                            migration = MigrationPolicy.Manual,
                            trace = QuicTraceCapture(sink = { e -> lines += e.toString() }, recordNetworkObservations = true),
                        )
                    val client = connectCore(server.port, options = options, factory = BufferFactory.Default)
                    echoOver(client.connection.openStream(), ByteArray(PAYLOAD) { it.toByte() })

                    // One change at a time: the monitor's single state flow conflates.
                    monitor.state.value = NetworkState.Routable(migratedTo, InternetAccess.Unobserved)
                    awaitTapped(lines, "networkId=$migratedTo") { it.networkId == migratedTo }
                    monitor.state.value = NetworkState.Offline
                    awaitTapped(lines, "Offline") { it == NetworkState.Offline }
                    client.connection.close(QuicError.NoError)

                    val snapshot = synchronized(lines) { lines.toList() }
                    assertTrue(snapshot.all { it.startsWith("v1 ") }, "every line is versioned: $snapshot")
                    assertTrue(snapshot.any { it.contains("DGRAM_OUT") }, "the connection records the datagrams it sent: $snapshot")
                    assertTrue(snapshot.any { it.contains("DGRAM_IN") }, "the connection records the datagrams it received")
                    val events = TraceEvent.parseAll(snapshot)
                    assertTrue(events.filterIsInstance<TraceEvent.Net>().any { it.state.networkId == migratedTo }, "identity: $snapshot")
                    assertTrue(events.filterIsInstance<TraceEvent.Net>().any { it.state == NetworkState.Offline }, "reachability: $snapshot")
                    assertTrue(events.filterIsInstance<TraceEvent.NetCapability>().isNotEmpty(), "declared capability, once: $snapshot")
                }
            }
        }

    /** Waits until the trace holds a network state [predicate] accepts; [what] names it when the wait runs out. */
    private suspend fun awaitTapped(
        lines: List<String>,
        what: String,
        predicate: (NetworkState) -> Boolean,
    ) {
        withTimeout(10.seconds) {
            while (TraceEvent.parseAll(synchronized(lines) { lines.toList() }).filterIsInstance<TraceEvent.Net>().none { predicate(it.state) }) {
                delay(POLL_MILLIS)
            }
        }
    }

    private companion object {
        const val PAYLOAD = 500
        const val POLL_MILLIS = 10L
    }
}
