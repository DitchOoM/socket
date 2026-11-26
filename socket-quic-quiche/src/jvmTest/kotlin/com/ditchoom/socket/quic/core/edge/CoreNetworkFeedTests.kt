@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

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
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.SocketView
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The network monitor's news reaches a real core connection as connection work: no usable network keeps it (the idle
 * timeout bounds an outage), and a usable network back has it send at once.
 */
class CoreNetworkFeedTests {
    private class FakeMonitor : NetworkMonitor {
        override val state = MutableStateFlow<NetworkState>(UP)

        override fun close() = Unit
    }

    /**
     * A monitor whose state the feed subscribes to only once [subscribe] completes, as when the setup lane the feed
     * starts on is busy: what changed before then is in the state's value when the subscription begins.
     */
    private class LateSubscription : NetworkMonitor {
        val held = MutableStateFlow(WIFI_BEFORE)
        val subscribe = CompletableDeferred<Unit>()
        override val state: StateFlow<NetworkState> =
            object : StateFlow<NetworkState> by held {
                override suspend fun collect(collector: FlowCollector<NetworkState>): Nothing {
                    subscribe.await()
                    held.collect(collector)
                }
            }

        override fun close() = Unit
    }

    /**
     * A socket of a network that went away, as Android leaves it: sends are taken and go nowhere, and nothing arrives,
     * until the socket is reopened.
     */
    private class DeadAfterOutage(
        val real: DatagramBatchIo,
    ) : DatagramBatchIo by real {
        @Volatile var dead = false

        @Volatile var reopens = 0

        override fun send(batch: SendBatch): SendBatchResult = if (dead) SendBatchResult.AllTaken else real.send(batch)

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            val got = real.receive(into)
            if (!dead || got !is ReceiveBatchResult.Got) return got
            into.release()
            return ReceiveBatchResult.Nothing
        }

        override fun reopen(): Reopened {
            val reopened = real.reopen()
            dead = false
            reopens++
            return reopened
        }
    }

    /** A socket whose second socket hears nothing, as a new address the peer's answers never reach. */
    private class SecondGoesNowhere(
        val real: DatagramBatchIo,
    ) : DatagramBatchIo by real {
        @Volatile var opened = 0

        @Volatile var closedSeconds = 0

        override fun openBeside(
            bind: PathKey,
            ticket: SocketTicket,
        ): Beside = real.openBeside(bind, ticket).also { opened++ }

        override fun closeSecond() {
            closedSeconds++
            real.closeSecond()
        }

        override fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult {
            real.receiveSecond(into)
            into.release()
            return ReceiveBatchResult.Nothing
        }
    }

    /** A client io's second socket, seen from outside: on the JDK stack its channel, on the native one whether it holds one. */
    private sealed interface SecondHandle {
        fun open(): Boolean

        class Jdk(
            private val channel: java.nio.channels.DatagramChannel,
        ) : SecondHandle {
            override fun open(): Boolean = channel.isOpen
        }

        class Native(
            private val io: NativeIo,
        ) : SecondHandle {
            /** The io answers its carrying descriptor for the second once none is open. */
            override fun open(): Boolean = io.secondDescriptor() != io.descriptor()
        }

        companion object {
            fun of(io: DatagramBatchIo): SecondHandle =
                when (io) {
                    is NioOneByOne -> Jdk(io.secondChannel)
                    is NativeIo -> Native(io)
                    else -> throw AssertionError("no view of ${io::class}")
                }
        }
    }

    /** The path marks of [client]'s flight recording, in order. */
    private suspend fun marksOf(client: CoreClient): List<String> {
        val recorded = ArrayList<TraceEvent>()
        client.connection.dumpFlight(TraceSink { recorded += it })
        return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
    }

    private suspend fun connectWatched(
        port: Int,
        monitor: NetworkMonitor,
        wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
        routeSource: suspend (com.ditchoom.socket.quic.UdpSocketChannelFactory) -> RouteSource = { it.routeSourceAddress() },
        migration: MigrationPolicy = MigrationPolicy.Automatic,
    ): CoreClient =
        onIo {
            coreConnect(
                mismatch = MismatchResponse.Fail,
                host = "127.0.0.1",
                port = port,
                serverName = "localhost",
                options = coreTestOptions.copy(networkMonitor = NetworkMonitorSource.Supplied(monitor), migration = migration),
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                timeout = 10.seconds,
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
                wrapIo = wrapIo,
                routeSource = routeSource,
            )
        }

    @Test
    fun theSameAddressOnANewNetworkReopensTheSocketOnTheSamePortAndTheConnectionEchoesAtOnce() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    monitor.state.value = WIFI_BEFORE
                    var io = DeadAfterOutage(FakeIo())
                    val client = connectWatched(port, monitor, wrapIo = { DeadAfterOutage(it).also { wrapped -> io = wrapped } })
                    val socket = SocketView.of(io.real)
                    val before = socket.identity()
                    val bound = socket.local()
                    val warm = Random(1).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))

                    monitor.state.value = NetworkState.Offline
                    io.dead = true
                    val payload = Random(7).nextBytes(2_000)
                    val stream = client.connection.openStream()
                    val echo = async { echoOver(stream, payload) }
                    // Long enough for quiche's probe timer to back off past a second.
                    delay(3_000)
                    val back = TimeSource.Monotonic.markNow()
                    monitor.state.value = WIFI_AFTER
                    assertContentEquals(payload, echo.await())
                    val took = back.elapsedNow()

                    assertEquals(1, io.reopens, "the socket was reopened once")
                    socket.assertReplaced(before)
                    assertEquals(bound, socket.local(), "on the same address and port: the path is unchanged")
                    assertTrue(took < 1.seconds, "the echo finished $took after the network came back, not a backed-off timer later")
                    val recorded = ArrayList<TraceEvent>()
                    client.connection.dumpFlight(TraceSink { recorded += it })
                    assertTrue(recorded.any { it is TraceEvent.PathState && it.phase == "Rehomed" }, "the flight recording shows it")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /**
     * On real sockets: the route to the peer leaves from another address, so the client's socket moves there, on a port
     * of its own, the path from it is validated, and the connection migrates and echoes over it. The wildcard stands
     * in for a second interface address, which loopback alone does not have on every platform. The route to it is
     * answered [ROUTE_ANSWER] late, as a real route probe is: the move happens off the test's thread, after it.
     */
    @Test
    fun aRouteFromAnotherAddressMovesTheSocketAndTheConnectionEchoesFromIt() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    monitor.state.value = WIFI_BEFORE
                    var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                    var io = DeadAfterOutage(FakeIo())
                    val client =
                        connectWatched(
                            port,
                            monitor,
                            wrapIo = { DeadAfterOutage(it).also { wrapped -> io = wrapped } },
                            routeSource = {
                                if (source == WILDCARD) delay(ROUTE_ANSWER)
                                source
                            },
                        )
                    val socket = SocketView.of(io.real)
                    val bound = socket.local()
                    val warm = Random(1).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))

                    source = WILDCARD
                    monitor.state.value = WIFI_AFTER
                    // The echo crosses the move only once the socket moved: one sent before it is answered on the old path.
                    withTimeout(5.seconds) { while ("Moved" !in marksOf(client)) delay(5) }
                    val payload = Random(7).nextBytes(2_000)
                    val echoed = withTimeout(5.seconds) { echoOver(client.connection.openStream(), payload) }

                    assertContentEquals(payload, echoed)
                    assertTrue(socket.local() != bound, "the socket moved to a new local endpoint: ${socket.local()}")
                    val marks = marksOf(client)
                    assertTrue("Moved" in marks && "Migrated" in marks, "the flight recording shows the move: $marks")
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /**
     * On real sockets: the route moves to another address whose path never answers, the connection echoes on through the
     * probes from the socket it had, the move is given up, and its socket is closed: the io holds no second socket.
     */
    @Test
    fun aMoveWhosePathNeverAnswersClosesItsSocketAndKeepsEchoingOnTheOldOne() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    monitor.state.value = WIFI_BEFORE
                    var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                    var io = SecondGoesNowhere(FakeIo())
                    val client =
                        connectWatched(
                            port,
                            monitor,
                            wrapIo = { SecondGoesNowhere(it).also { wrapped -> io = wrapped } },
                            routeSource = { source },
                        )
                    val socket = SocketView.of(io.real)
                    val bound = socket.local()
                    val warm = Random(1).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))

                    source = WILDCARD
                    monitor.state.value = WIFI_AFTER
                    withTimeout(5.seconds) { while (io.opened == 0) delay(5) }
                    val second = SecondHandle.of(io.real)
                    assertTrue(second.open(), "the move opened a socket beside the carrying one")
                    val payload = Random(7).nextBytes(2_000)
                    withTimeout(MOVE_GIVEN_UP) {
                        while ("Abandoned" !in marksOf(client)) {
                            assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                            delay(200)
                        }
                    }
                    assertEquals(1, io.closedSeconds, "the move's socket was closed")
                    assertTrue(!second.open(), "the move's socket did not outlive it")
                    assertEquals(bound, socket.local(), "the carrying socket carried on")
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                    assertIs<EdgeEnd.Open>(client.connection.end())
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aClientThatDoesNotMoveOnItsOwnEndsAtOnceAsLocalAddressGoneWhenANewAddressTakesTheRoute() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    monitor.state.value = WIFI_BEFORE
                    var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                    val client = connectWatched(port, monitor, routeSource = { source }, migration = MigrationPolicy.Manual)

                    monitor.state.value = NetworkState.Offline
                    delay(200)
                    // The v6 lane of the walk: the new network comes up unvalidated with only a link-local address...
                    source = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)
                    monitor.state.value = NetworkState.Routable(AFTER, InternetAccess.Observed.Pending)
                    delay(300)
                    assertIs<EdgeEnd.Open>(client.connection.end(), "no route yet is ridden out")
                    // ...and is validated with a new privacy address: the bound one is gone.
                    source = RouteSource.Resolved("192.0.2.7")
                    monitor.state.value = NetworkState.Routable(AFTER, InternetAccess.Observed.Confirmed)

                    val ended =
                        withTimeout(2.seconds) {
                            while (client.connection.end() is EdgeEnd.Open) delay(10)
                            assertIs<EdgeEnd.Ended>(client.connection.end()).end
                        }
                    assertSame(ConnEnd.LocalAddressGone, ended, "not an idle timeout read as NetworkLost later")
                }
            }
        }

    /**
     * A move the monitor reported after the connect returned but before the feed subscribed to it is followed: the feed
     * compares what it reads first with the network the connect opened on, rather than taking that first reading as it.
     */
    @Test
    fun aMoveReportedBeforeTheFeedSubscribesIsFollowed() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = LateSubscription()
                    var source: RouteSource = RouteSource.Resolved("127.0.0.1")
                    val client = connectWatched(port, monitor, routeSource = { source })
                    val warm = Random(1).nextBytes(500)
                    assertContentEquals(warm, echoOver(client.connection.openStream(), warm))

                    source = WILDCARD
                    monitor.held.value = WIFI_AFTER
                    monitor.subscribe.complete(Unit)
                    withTimeout(5.seconds) { while ("Migrated" !in marksOf(client)) delay(5) }
                    val payload = Random(7).nextBytes(2_000)
                    assertContentEquals(payload, withTimeout(5.seconds) { echoOver(client.connection.openStream(), payload) })
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun anOutageOfSeveralSecondsKeepsTheConnectionAndItEchoesOnceTheNetworkReturns() =
        runBlocking {
            skipOnMissingNativeLib(CoreNetworkFeedTests::class) {
                withEchoServer { port ->
                    val monitor = FakeMonitor()
                    val client = connectWatched(port, monitor)
                    monitor.state.value = NetworkState.Offline
                    delay(4_000)
                    assertIs<EdgeEnd.Open>(client.connection.end(), "no usable network does not end the connection")
                    monitor.state.value = UP
                    val payload = Random(7).nextBytes(2_000)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private companion object {
        val WILDCARD = RouteSource.Resolved("0.0.0.0")
        val ROUTE_ANSWER = 100.milliseconds

        /** Past three failed validations of a move and the waits between them, on real time. */
        val MOVE_GIVEN_UP = 30.seconds

        val UP: NetworkState = NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved)
        val BEFORE = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
        val AFTER = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
        val WIFI_BEFORE: NetworkState = NetworkState.Routable(BEFORE, InternetAccess.Observed.Confirmed)
        val WIFI_AFTER: NetworkState = NetworkState.Routable(AFTER, InternetAccess.Observed.Pending)
    }
}
