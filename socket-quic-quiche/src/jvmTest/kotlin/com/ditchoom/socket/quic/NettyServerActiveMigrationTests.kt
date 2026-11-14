package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import io.netty.handler.codec.quic.QuicPathEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This library's client migrating, by its own decision, against a QUIC server this library did not write
 * (Netty's QUIC codec). Every other active-migration test here has this library on both ends, so a
 * mistake the client and the server share would pass them all.
 *
 * The migration is the public [QuicScope.migrate] to a fresh local port, a new path by its 4-tuple: the
 * client probes it, the server answers the challenge, and the client moves.
 */
class NettyServerActiveMigrationTests {
    @Test
    fun aStreamStaysByteExactAcrossAMigrationAgainstANettyServer() = migratesMidTransfer(Link.Loopback)

    /** The same, on a path with a round-trip time, where a packet sent on the old path is still in flight when the client moves. */
    @Test
    fun aStreamStaysByteExactAcrossAMigrationAgainstANettyServerOnAPathWithARoundTripTime() =
        migratesMidTransfer(Link.Delayed(PATH_ONE_WAY_DELAY))

    /**
     * Each migration uses up a connection id the server issued and leaves a path behind. More migrations
     * than the client's `active_connection_id_limit` succeed only if the client retires each path it
     * left and the server issues new connection ids in exchange, and only if the client pushes the left
     * path's source id out: quiche keeps it linked to that path until the peer retires it.
     *
     * The server advertises [ROOMY_SERVER_ID_LIMIT], not Netty's default of 2. At 2, quiche on the server
     * holds two paths and frees the one the client left only once its destination id is gone, but retiring
     * that id links the client's replacement to the same old path, so the server drops the next probe and
     * a client can move once per connection whatever it does.
     */
    @Test
    fun theClientKeepsMigratingPastItsConnectionIdLimitAgainstANettyServer() =
        runQuicTest(timeout = 120.seconds) {
            skipOnMissingNativeLib(NettyServerActiveMigrationTests::class) {
                val timeline = ClientTimeline()
                timeline.explaining {
                    NettyQuicEchoServer(certificate("cert.crt"), certificate("cert.key"), ALPN, ROOMY_SERVER_ID_LIMIT).use { server ->
                        withQuicConnection("127.0.0.1", server.port, timeline.options, timeout = CONNECTION_BUDGET.scaled()) {
                            timeline.watch((this as QuicheBackedConnection).quicheDriver)
                            timeline.whileConnected {
                                val stream = openStream()
                                assertEquals("m0", stream.echo("m0"))
                                repeat(MIGRATIONS_PAST_THE_LIMIT) { index ->
                                    val result = migrateOnceAConnectionIdIsSpare()
                                    val moved =
                                        assertIs<MigrationResult.Succeeded>(
                                            result,
                                            "migration ${index + 1}: $result, Netty saw ${server.describe()}",
                                        )
                                    eventually(
                                        SERVER_SEES_BUDGET,
                                        { "Netty never named ${moved.localEndpoint.port} as the peer: ${server.describe()}" },
                                    ) {
                                        server.peers() == listOf(NettyQuicEchoServer.NettyPeer.At(moved.localEndpoint.port))
                                    }
                                    assertEquals("m${index + 1}", stream.echo("m${index + 1}"), "the stream after migration ${index + 1}")
                                }
                                stream.close()
                            }
                        }
                    }
                }
            }
        }

    /**
     * Against a quiche server that advertises an `active_connection_id_limit` of 2 (Netty's default), a
     * client can move to a new 4-tuple once per connection, whatever it does. The server holds two paths
     * (quiche `lib.rs` `PathMap::new` takes the local limit as its capacity) and admits a third only by
     * evicting an unused one (`path.rs` `make_room_for_new_path`: unused = not active and no destination
     * id). The left path never becomes unused: the only way a client retires the id it holds is Retire
     * Prior To, which arrives with a new id in the same NEW_CONNECTION_ID, and quiche links that new id
     * to the left path when it retires the old one (`lib.rs`, the `NewConnectionId` frame: inserted first
     * by `ids.new_dcid`, then `lowest_available_dcid_seq` relinked to every retired path). So the server
     * drops the second probe. The client knows the server's limit from the handshake and has seen no sign
     * that it frees left paths, so it must answer the second move at once with NoSpareConnectionId, send
     * no probe the server would drop, and keep the connection.
     */
    @Test
    fun aSecondMoveAgainstAServerWithRoomForTwoPathsIsRefusedAtOnceAndTheConnectionLives() =
        runQuicTest(timeout = 60.seconds) {
            skipOnMissingNativeLib(NettyServerActiveMigrationTests::class) {
                val timeline = ClientTimeline()
                timeline.explaining {
                    NettyQuicEchoServer(certificate("cert.crt"), certificate("cert.key"), ALPN, NETTY_DEFAULT_ID_LIMIT).use { server ->
                        withQuicConnection("127.0.0.1", server.port, timeline.options, timeout = CONNECTION_BUDGET.scaled()) {
                            timeline.watch((this as QuicheBackedConnection).quicheDriver)
                            timeline.whileConnected {
                                val stream = openStream()
                                assertEquals("m0", stream.echo("m0"))
                                assertIs<MigrationResult.Succeeded>(migrateOnceAConnectionIdIsSpare(), "the first move")
                                assertEquals("m1", stream.echo("m1"))

                                val second = withTimeout(SECOND_MOVE_BUDGET.scaled()) { migrate() }
                                assertEquals(
                                    MigrationResult.Unmoved.Failed.NoSpareConnectionId,
                                    second,
                                    "a second move the server cannot admit",
                                )
                                assertIs<QuicPathState.Migrated>(pathState.value, "the path the first move landed on")
                                assertEquals("m2", stream.echo("m2"), "the connection on the path it kept")
                                stream.close()
                            }
                        }
                    }
                }
            }
        }

    private fun migratesMidTransfer(link: Link) =
        runQuicTest(timeout = 90.seconds) {
            skipOnMissingNativeLib(NettyServerActiveMigrationTests::class) {
                val timeline = ClientTimeline()
                timeline.explaining {
                    NettyQuicEchoServer(certificate("cert.crt"), certificate("cert.key"), ALPN, NETTY_DEFAULT_ID_LIMIT).use { server ->
                        link.open(InetSocketAddress("127.0.0.1", server.port)).use { route ->
                            withQuicConnection(
                                "127.0.0.1",
                                route.clientFacingPort,
                                timeline.options,
                                timeout = CONNECTION_BUDGET.scaled(),
                            ) {
                                timeline.watch((this as QuicheBackedConnection).quicheDriver)
                                timeline.whileConnected {
                                    val stream = openStream()
                                    val echoed = EchoedPrefix(PAYLOAD)
                                    val originalPort = localPorts().single()

                                    val writerAtBarrier = CompletableDeferred<Unit>()
                                    val migrationStarted = CompletableDeferred<Unit>()
                                    val moved =
                                        coroutineScope {
                                            launch {
                                                while (echoed.count < PAYLOAD.size) echoed.take(stream.read(READ_DEADLINE.scaled()))
                                            }
                                            launch {
                                                for (chunk in 0 until CHUNKS) {
                                                    // Never more than a window ahead of the echo: bytes are in flight
                                                    // whenever the writer waits, and it cannot finish before the move.
                                                    awaitUntil(
                                                        STALL_BUDGET.scaled(),
                                                        "the echo stalled before chunk $chunk: ${echoed.progress()}",
                                                    ) {
                                                        echoed.count >= (chunk - PIPELINE_WINDOW) * CHUNK_BYTES
                                                    }
                                                    if (chunk == MIGRATE_AT_CHUNK) {
                                                        writerAtBarrier.complete(Unit)
                                                        migrationStarted.await()
                                                    }
                                                    stream.send(chunk)
                                                }
                                            }
                                            writerAtBarrier.await()
                                            migrationStarted.complete(Unit)
                                            val result = migrate()
                                            assertIs<MigrationResult.Succeeded>(result, "the migration with ${echoed.progress()}: $result")
                                        }

                                    assertEquals(PAYLOAD.size, echoed.count, "every byte written came back, in order")
                                    assertEquals(QuicPathState.Migrated(moved.localEndpoint), pathState.value)

                                    // Netty 4.2.20's path events carry their ports byte-swapped (a peer on 52374 is
                                    // reported as 38604), so the new peer port is read from the connection itself.
                                    val peerPortForNetty = route.serverSidePortOf(moved.localEndpoint.port)
                                    eventually(SERVER_SEES_BUDGET, { "Netty never reported a peer migration: ${server.describe()}" }) {
                                        server.pathEvents().any { it is QuicPathEvent.PeerMigrated }
                                    }
                                    eventually(
                                        SERVER_SEES_BUDGET,
                                        { "Netty never moved the connection to port $peerPortForNetty: ${server.describe()}" },
                                    ) {
                                        server.peers() == listOf(NettyQuicEchoServer.NettyPeer.At(peerPortForNetty))
                                    }

                                    eventually(RETIRE_BUDGET, { "the path on port $originalPort was never released" }) {
                                        localPorts() == listOf(moved.localEndpoint.port)
                                    }
                                    assertEquals("after", stream.echo("after"), "the stream on the new path, once the old one is gone")
                                    stream.close()
                                }
                            }
                        }
                    }
                }
            }
        }

    /** Waits, within [budget], for [condition] to hold; fails with [what] if it never does. */
    private suspend fun eventually(
        budget: Duration,
        what: () -> String,
        condition: suspend () -> Boolean,
    ) {
        try {
            withTimeout(budget.scaled()) {
                while (!condition()) delay(POLL)
            }
        } catch (expired: TimeoutCancellationException) {
            throw AssertionError(what(), expired)
        }
    }

    /** The local ports of the paths the client's driver sends or probes on; a left path it still reads is not one. */
    private suspend fun QuicScope.localPorts(): List<Int> {
        val snapshot = assertIs<Inspected.Read<DriverStallSnapshot>>((this as QuicheBackedConnection).quicheDriver.stallSnapshot())
        return snapshot.value.paths
            .filter { it.use != PathUse.Left }
            .map { it.key.port }
    }

    /** A migration needs a connection id the server has issued and the client has not used; the server issues one per id retired. */
    private suspend fun QuicScope.migrateOnceAConnectionIdIsSpare(): MigrationResult {
        repeat(SPARE_ID_ATTEMPTS) {
            val result = migrate()
            if (result !is MigrationResult.Unmoved.Failed.NoSpareConnectionId) return result
            delay(SPARE_ID_WAIT)
        }
        return migrate()
    }

    private suspend fun QuicByteStream.send(chunk: Int) {
        val out = BufferFactory.deterministic().allocate(CHUNK_BYTES)
        out.writeString(chunkText(chunk), Charset.UTF8)
        out.resetForRead()
        try {
            writeFully(out, WRITE_DEADLINE.scaled())
        } finally {
            out.freeNativeMemory()
        }
    }

    private suspend fun QuicByteStream.echo(text: String): String {
        val out = BufferFactory.deterministic().allocate(text.length)
        out.writeString(text, Charset.UTF8)
        out.resetForRead()
        try {
            writeFully(out, WRITE_DEADLINE.scaled())
        } finally {
            out.freeNativeMemory()
        }
        val back = StringBuilder()
        while (back.length < text.length) {
            val read = assertIs<ReadResult.Data>(read(READ_DEADLINE.scaled()), "the echo of \"$text\" after \"$back\"")
            try {
                back.append(read.buffer.readString(read.buffer.remaining(), Charset.UTF8))
            } finally {
                read.buffer.freeIfNeeded()
            }
        }
        return back.toString()
    }

    private fun NettyQuicEchoServer.describe(): String = "peers ${peers()}, path events ${pathEvents()}"

    private fun certificate(name: String): File {
        val url = assertNotNull(this::class.java.classLoader.getResource("certs/$name"), "certs/$name is not on the test classpath")
        return File(url.toURI())
    }

    /**
     * What the client did, attached to a failure: the newest [KEPT_EVENTS] protocol events, and the
     * driver's own record. The driver's flight recording is read without the loop, so it answers even
     * when the loop is stuck; it shows every received datagram's fate (quiche's answer, or dropped as
     * arriving on a path the driver left) and what the loop last did. The snapshot (paths with their
     * slot and which one is active, streams, stats) and the client's source connection ids need the
     * loop, and say so when it does not answer.
     */
    private class ClientTimeline {
        private val events = ConcurrentLinkedQueue<String>()
        private val drivers = ConcurrentLinkedQueue<QuicheDriver>()

        /** quiche's own record of the client connection: whether each packet decrypted, what it carried, what was acknowledged. */
        private val qlog: File = Files.createTempDirectory("netty-migration-qlog").toFile().resolve("client.sqlog")

        val options: QuicOptions =
            clientOptions.copy(
                trace =
                    QuicTraceCapture(
                        captureFor = {
                            QuicConnectionCapture(
                                sink =
                                    TraceSink { event ->
                                        events.add(event.toString())
                                        while (events.size > KEPT_EVENTS) events.poll()
                                    },
                                qlog = QlogTarget.File(qlog.path),
                            )
                        },
                    ),
            )

        fun watch(driver: QuicheDriver) {
            drivers.add(driver)
        }

        /** Runs [block] inside a live connection; a failure takes the driver's report while the loop can still answer. */
        suspend fun whileConnected(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}\n${report()}", failure)
            }
        }

        suspend fun explaining(block: suspend () -> Unit) {
            try {
                block()
                discard()
            } catch (failure: AssertionError) {
                // A new error rather than a suppressed one: coroutine stack-trace recovery copies the
                // failure on its way out and the copy drops what was attached to the original.
                throw AssertionError("${failure.message}\n${report()}", failure)
            }
        }

        private suspend fun report(): String =
            buildString {
                for (driver in drivers) {
                    append(
                        when (val recording = driver.flightRecording()) {
                            FlightRecording.Off -> "driver flight recorder: off"
                            is FlightRecording.Recorded -> recording.render()
                        },
                    )
                    append("\ndriver snapshot: ").append(answerOrSilence { driver.stallSnapshot().toString() })
                    append("\nclient source connection ids: ")
                        .append(answerOrSilence { driver.sourceIds().joinToString { id -> id.toHexString() } })
                    append('\n')
                }
                append("client protocol timeline (").append(events.size).append(" events), newest last:\n")
                events.forEach { append(it).append('\n') }
                append("client qlog, last $KEPT_QLOG_LINES records (${qlog.path}):\n")
                if (qlog.isFile) qlog.readLines().takeLast(KEPT_QLOG_LINES).forEach { append(it).append('\n') }
            }

        /** Removes the qlog of a run that passed. */
        fun discard() {
            qlog.parentFile.deleteRecursively()
        }

        private suspend fun answerOrSilence(read: suspend () -> String): String =
            try {
                withTimeout(LOOP_ANSWER_BUDGET) { read() }
            } catch (_: TimeoutCancellationException) {
                "the driver loop did not answer within $LOOP_ANSWER_BUDGET"
            }
    }

    /** What an echo stream has given back so far, checked against [expected] as it arrives. */
    private class EchoedPrefix(
        private val expected: ByteArray,
    ) {
        private val received = AtomicInteger()

        val count: Int get() = received.get()

        fun progress(): String = "$count of ${expected.size} bytes echoed back"

        /** One reader at a time. Fails on anything that is not the next bytes of [expected]. */
        fun take(result: ReadResult) {
            val data = assertIs<ReadResult.Data>(result, "the stream ended with ${progress()}")
            val bytes = ByteArray(data.buffer.remaining())
            try {
                for (i in bytes.indices) bytes[i] = data.buffer.readByte()
            } finally {
                data.buffer.freeIfNeeded()
            }
            val from = count
            val wanted = expected.copyOfRange(from, minOf(expected.size, from + bytes.size))
            assertContentEquals(wanted, bytes, "the echo diverged at byte $from of ${expected.size}")
            received.set(from + bytes.size)
        }
    }

    /** How the client reaches the server. */
    private sealed interface Link {
        fun open(server: InetSocketAddress): Route

        /** Straight to the server over loopback: no round-trip time to speak of. */
        data object Loopback : Link {
            override fun open(server: InetSocketAddress): Route = Route.Direct(server.port)
        }

        /** Through a [PathDelayRelay] that holds each datagram for [oneWay]. */
        data class Delayed(
            val oneWay: Duration,
        ) : Link {
            override fun open(server: InetSocketAddress): Route = Route.Relayed(PathDelayRelay(server, oneWay))
        }
    }

    private sealed interface Route : AutoCloseable {
        /** The port the client connects to. */
        val clientFacingPort: Int

        /** The port the server sees datagrams from a client on [clientPort] arrive from. */
        fun serverSidePortOf(clientPort: Int): Int

        class Direct(
            override val clientFacingPort: Int,
        ) : Route {
            override fun serverSidePortOf(clientPort: Int): Int = clientPort

            override fun close() = Unit
        }

        class Relayed(
            private val relay: PathDelayRelay,
        ) : Route {
            override val clientFacingPort: Int get() = relay.port

            override fun serverSidePortOf(clientPort: Int): Int = relay.upstreamPorts().getValue(clientPort)

            override fun close() = relay.close()
        }
    }

    private companion object {
        const val ALPN = "test"
        const val CHUNK_BYTES = 64
        const val CHUNKS = 48
        const val PIPELINE_WINDOW = 4
        const val MIGRATE_AT_CHUNK = 8
        const val CONNECTION_ID_LIMIT = 4L
        const val NETTY_DEFAULT_ID_LIMIT = 2L
        const val ROOMY_SERVER_ID_LIMIT = 8L
        const val MIGRATIONS_PAST_THE_LIMIT = 6
        const val SPARE_ID_ATTEMPTS = 40
        const val KEPT_EVENTS = 400
        const val KEPT_QLOG_LINES = 300
        val LOOP_ANSWER_BUDGET = 2.seconds

        val PATH_ONE_WAY_DELAY = 15.milliseconds
        val SPARE_ID_WAIT = 50.milliseconds
        val POLL = 10.milliseconds

        /** Bounds the whole connection, block included: withQuicConnection's timeout is not only the connect. */
        val CONNECTION_BUDGET = 60.seconds
        val READ_DEADLINE = 15.seconds
        val WRITE_DEADLINE = 10.seconds
        val STALL_BUDGET = 20.seconds
        val SERVER_SEES_BUDGET = 10.seconds
        val RETIRE_BUDGET = 15.seconds

        /** An answer that needs no probe: well under one path validation (three PTOs, at least 1 s). */
        val SECOND_MOVE_BUDGET = 500.milliseconds

        val clientOptions =
            QuicOptions(
                alpnProtocols = listOf(ALPN),
                verifyPeer = false,
                idleTimeout = 30.seconds.scaled(),
                activeConnectionIdLimit = CONNECTION_ID_LIMIT,
            )

        val PAYLOAD: ByteArray = (0 until CHUNKS).joinToString("") { chunkText(it) }.encodeToByteArray()

        /** Self-describing, ASCII, exactly [CHUNK_BYTES] long: a divergence names the chunk it is in. */
        fun chunkText(index: Int): String = "chunk-${index.toString().padStart(4, '0')}-".padEnd(CHUNK_BYTES, 'a' + index % 26)

        fun Duration.scaled(): Duration = this * testTimeScale()
    }
}
