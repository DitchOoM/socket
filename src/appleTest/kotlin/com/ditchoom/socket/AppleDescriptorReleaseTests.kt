package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.data.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Every way a TCP socket ends releases its file descriptor, counted on the process's descriptor table.
 *
 * Network.framework closes a connection's descriptor on its own queue after `nw_connection_cancel`, so
 * after each path the count is polled for up to [SETTLE] before it is compared with the baseline. The
 * baseline is taken after warm-up cycles, so descriptors Network.framework opens once per process
 * (its shared agents and kqueues) are not counted as leaks; [SLACK] covers the few it opens lazily
 * during a path. A leak is one descriptor per cycle, far above [SLACK] for [CYCLES] cycles.
 *
 * The assertion is on the count, not on `EMFILE`, so it holds under a CI runner's high `ulimit -n`.
 */
@OptIn(NativeRuntimeApi::class)
class AppleDescriptorReleaseTests {
    private val table = ArrayList<Row>()

    private data class Row(
        val path: String,
        val cycles: Int,
        val baseline: Int,
        val peak: Int,
        val final: Int,
    )

    /** What a server does with each connection it accepts. */
    private fun interface Handler {
        suspend fun handle(peer: ClientSocket)
    }

    private val readThenClose =
        Handler { peer ->
            try {
                drain(peer)
            } finally {
                peer.close()
            }
        }

    /** Answers nothing and reads nothing for [HOLD], then closes: the connection a silent or unreading peer holds. */
    private val holdThenClose =
        Handler { peer ->
            try {
                delay(HOLD)
            } finally {
                peer.close()
            }
        }

    /** Reads the first bytes, then closes: what a plain server does with a TLS ClientHello. */
    private val readOnceThenClose =
        Handler { peer ->
            try {
                try {
                    peer.read(5.seconds)
                } catch (_: SocketException) {
                }
            } finally {
                peer.close()
            }
        }

    private val closeAtOnce = Handler { peer -> peer.close() }

    private suspend fun drain(socket: ClientSocket) {
        while (true) {
            val result =
                try {
                    socket.read(5.seconds)
                } catch (_: SocketException) {
                    return
                }
            if (result !is ReadResult.Data) return
        }
    }

    private suspend fun withServer(
        handler: Handler,
        block: suspend CoroutineScope.(port: Int) -> Unit,
    ) {
        val server = ServerSocket.allocate()
        val accepted = server.bind()
        coroutineScope {
            val collector =
                launch(Dispatchers.Default) {
                    accepted.collect { peer -> launch { handler.handle(peer) } }
                }
            try {
                block(server.port())
            } finally {
                server.close()
                collector.cancelAndJoin()
            }
        }
    }

    /**
     * Runs [cycle] [WARM_UP] times, takes the baseline, runs it [cycles] more times tracking the peak,
     * waits up to [SETTLE] for the count to come back, and asserts it did.
     */
    private suspend fun assertReleased(
        path: String,
        cycles: Int = CYCLES,
        collectWhileSettling: Boolean = false,
        cycle: suspend (Int) -> Unit,
    ) {
        repeat(WARM_UP) { cycle(it) }
        settle(openDescriptorCount(), collectWhileSettling = false)
        val baseline = openDescriptorCount()
        var peak = baseline
        repeat(cycles) {
            cycle(it)
            peak = maxOf(peak, openDescriptorCount())
        }
        // Waits for the baseline itself, so a close still in flight is not mistaken for the slack.
        val final = settle(baseline, collectWhileSettling)
        table += Row(path, cycles, baseline, peak, final)
        assertTrue(
            final <= baseline + SLACK,
            "$path: $final descriptors open after $cycles cycles, baseline $baseline (peak $peak, allowed slack $SLACK)",
        )
    }

    /** Polls until the count is at most [target] or [SETTLE] has passed; the last count. */
    private suspend fun settle(
        target: Int,
        collectWhileSettling: Boolean,
    ): Int {
        val start = TimeSource.Monotonic.markNow()
        var count = openDescriptorCount()
        while (count > target && start.elapsedNow() < SETTLE) {
            if (collectWhileSettling) GC.collect()
            delay(50.milliseconds)
            count = openDescriptorCount()
        }
        return count
    }

    private fun report() {
        println("DESCRIPTOR-TABLE path | cycles | baseline | peak | final")
        table.forEach { println("DESCRIPTOR-TABLE ${it.path} | ${it.cycles} | ${it.baseline} | ${it.peak} | ${it.final}") }
    }

    private fun scenario(block: suspend CoroutineScope.() -> Unit) {
        try {
            runTestNoTimeSkipping(timeout = 240.seconds, block = block)
        } finally {
            report()
        }
    }

    @Test
    fun clientConnectsAndCloses() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("client closes") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    socket.close()
                }
            }
        }

    @Test
    fun peerClosesFirstThenClientReadsEndAndCloses() =
        scenario {
            withServer(closeAtOnce) { port ->
                assertReleased("peer closes, client reads end, closes") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    drain(socket)
                    socket.close()
                }
            }
        }

    @Test
    fun peerClosesAndClientReadsEndButNeverCloses() =
        scenario {
            withServer(closeAtOnce) { port ->
                assertReleased("peer closes, client reads end, no close") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    drain(socket)
                }
            }
        }

    @Test
    fun peerClosesAndClientDropsTheSocketUnread() =
        scenario {
            withServer(closeAtOnce) { port ->
                assertReleased("peer closes, client drops socket unread, collected", collectWhileSettling = true) {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                }
            }
        }

    /** A protocol client that reads the peer's last message and stops reading, as a closing handshake does. */
    @Test
    fun peerSendsThenClosesAndClientStopsReadingWithoutClosing() =
        scenario {
            val farewell =
                Handler { peer ->
                    peer.writeString("bye")
                    peer.close()
                }
            withServer(farewell) { port ->
                assertReleased("peer sends then closes, client reads data, drops", collectWhileSettling = true) {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    socket.read(5.seconds)
                }
            }
        }

    @Test
    fun refusedConnect() =
        scenario {
            assertReleased("connection refused") {
                val socket = ClientSocket.allocate()
                try {
                    socket.open(REFUSED_PORT, "127.0.0.1")
                } catch (_: SocketException) {
                }
                socket.close()
            }
        }

    @Test
    fun connectCancelledMidConnect() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("connect cancelled mid-connect") { index ->
                    val socket = ClientSocket.allocate()
                    val connecting = launch(Dispatchers.Default) { socket.open(port, "127.0.0.1") }
                    // Cancel at a different point of the establishment each cycle.
                    delay((index % 5).milliseconds)
                    connecting.cancelAndJoin()
                    socket.close()
                }
            }
        }

    @Test
    fun connectTimesOutAgainstASilentTlsServer() =
        scenario {
            withServer(holdThenClose) { port ->
                assertReleased("tls connect timeout", cycles = 10) {
                    val socket = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = 1.seconds))
                    try {
                        socket.open(port, "127.0.0.1")
                    } catch (_: SocketException) {
                    }
                    socket.close()
                }
            }
        }

    @Test
    fun tlsHandshakeAgainstAPlainTcpListenerFails() =
        scenario {
            withServer(readOnceThenClose) { port ->
                assertReleased("tls against plain tcp listener", cycles = 50) {
                    val socket = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds))
                    try {
                        socket.open(port, "127.0.0.1")
                    } catch (_: SocketException) {
                    }
                    socket.close()
                }
            }
        }

    @Test
    fun readTimesOutThenClose() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("read times out, close") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    try {
                        socket.read(20.milliseconds)
                    } catch (_: SocketTimeoutException) {
                    }
                    socket.close()
                }
            }
        }

    @Test
    fun boundedWriteTimesOutThenClose() =
        scenario {
            // The server accepts and does not read, so the client's send buffers fill and a write blocks.
            withServer(holdThenClose) { port ->
                assertReleased("write times out, close", cycles = 20) {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    try {
                        while (true) {
                            val chunk = BufferFactory.Default.allocate(1 shl 20)
                            chunk.position(chunk.limit())
                            chunk.resetForRead()
                            socket.write(chunk, 100.milliseconds)
                        }
                    } catch (_: SocketException) {
                    }
                    socket.close()
                }
            }
        }

    @Test
    fun closeTwice() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("close twice") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    socket.close()
                    socket.close()
                }
            }
        }

    @Test
    fun closeRacesAPendingRead() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("close races pending read") {
                    val socket = ClientSocket.allocate()
                    socket.open(port, "127.0.0.1")
                    val reading = async(Dispatchers.Default) { runCatching { socket.read(5.seconds) } }
                    yield()
                    launch(Dispatchers.Default) { socket.close() }
                    reading.await()
                    socket.close()
                }
            }
        }

    @Test
    fun acceptedSocketsWhoseClientVanished() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("accepted socket, client resets") {
                    val peer = connectRawTcpPeer(port)
                    peer.reset()
                }
            }
        }

    @Test
    fun acceptedSocketsWhoseClientClosedGracefully() =
        scenario {
            withServer(readThenClose) { port ->
                assertReleased("accepted socket, client closes gracefully") {
                    val peer = connectRawTcpPeer(port)
                    peer.close()
                }
            }
        }

    @Test
    fun serverClosesWithAcceptedSocketsNobodyTook() =
        scenario {
            assertReleased("server closes with untaken connections", cycles = 50) {
                val server = ServerSocket.allocate()
                server.bind()
                val peers = List(3) { connectRawTcpPeer(server.port()) }
                delay(20.milliseconds)
                server.close()
                peers.forEach { it.close() }
            }
        }

    @Test
    fun connectRacerLosersAreReleased() =
        scenario {
            withServer(readThenClose) { port ->
                val both =
                    HostResolver {
                        Resolution.Resolved(listOf(ResolvedAddress("127.0.0.1", IpFamily.V4), ResolvedAddress("::1", IpFamily.V6)))
                    }
                assertReleased("connect racer, staggered") {
                    val config =
                        TransportConfig(
                            nameResolution = NameResolution.Via(both),
                            connectPacing = ConnectPacing.Staggered(1.milliseconds),
                        )
                    val socket = ClientSocket.allocate(config)
                    socket.open(port, "localhost")
                    socket.close()
                }
            }
        }

    private companion object {
        const val CYCLES = 200
        const val WARM_UP = 3
        const val SLACK = 6
        val SETTLE = 10.seconds
        val HOLD = 1500.milliseconds
    }
}
