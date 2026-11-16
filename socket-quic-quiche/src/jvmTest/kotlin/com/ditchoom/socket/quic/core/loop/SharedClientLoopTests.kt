@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.CoreClient
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.edge.withEchoServer
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Client connections that share loop threads: placement, byte-exact echoes, and the failure tiers on one loop. */
class SharedClientLoopTests {
    @Test
    fun sixtyFourConnectionsOnTwoLoopsEachEchoAThousandTimesByteExact() =
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopTests::class) {
                withEchoServer { port ->
                    val loops = LoopSet(2)
                    try {
                        val clients = withContext(Dispatchers.IO) { List(CONNECTIONS) { connect(port, loops) } }
                        val perLoop = clients.groupingBy { it.loop.name }.eachCount()
                        assertEquals(2, perLoop.size, "the connections spread over both loops: $perLoop")
                        assertTrue(perLoop.values.all { it == CONNECTIONS / 2 }, "an even spread: $perLoop")
                        withTimeout(120.seconds) {
                            withContext(Dispatchers.Default) {
                                clients
                                    .mapIndexed { index, client ->
                                        async {
                                            val stream = client.connection.openStream()
                                            val payload = Random(index).nextBytes(PAYLOAD)
                                            repeat(
                                                ECHOES,
                                            ) { assertContentEquals(payload, echoOver(stream, payload), "connection $index echo $it") }
                                        }
                                    }.awaitAll()
                            }
                        }
                        clients.forEach { it.connection.close(QuicError.NoError) }
                    } finally {
                        loops.close()
                    }
                }
            }
        }

    @Test
    fun aSocketThatFailsEndsItsConnectionAndTheOthersOnTheLoopKeepEchoing() =
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopTests::class) {
                withEchoServer { port ->
                    val loops = LoopSet(1)
                    try {
                        val planted = IllegalStateException("planted in receive")
                        val flaky = arrayOfNulls<FailingIo>(1)
                        val healthy = withContext(Dispatchers.IO) { List(3) { connect(port, loops) } }
                        val broken = withContext(Dispatchers.IO) { connect(port, loops) { FailingIo(it).also { io -> flaky[0] = io } } }
                        assertEquals(1, setOf(broken.loop.name).size)
                        val payload = Random(3).nextBytes(PAYLOAD)
                        val streams = (healthy + broken).map { it.connection.openStream() }
                        streams.forEach { assertContentEquals(payload, echoOver(it, payload)) }

                        flaky[0]!!.plant = planted
                        val failure = assertFailsWith<QuicCloseException> { withTimeout(10.seconds) { echoOver(streams.last(), payload) } }
                        assertSame(planted, failure.cause, "the connection ended with the socket's failure")

                        repeat(ECHOES) { round ->
                            streams.dropLast(1).forEach { assertContentEquals(payload, echoOver(it, payload), "healthy echo $round") }
                        }
                        assertSame(LoopLife.Alive, broken.loop.life.load(), "the loop itself did not fail")
                        healthy.forEach { it.connection.close(QuicError.NoError) }
                    } finally {
                        loops.close()
                    }
                }
            }
        }

    @Test
    fun aClosedSocketEndsItsConnectionWithATypedFailureAndTheOthersKeepEchoing() =
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopTests::class) {
                withEchoServer { port ->
                    val loops = LoopSet(1)
                    try {
                        val doomed = arrayOfNulls<DatagramBatchIo>(1)
                        val healthy = withContext(Dispatchers.IO) { connect(port, loops) }
                        val broken = withContext(Dispatchers.IO) { connect(port, loops) { it.also { io -> doomed[0] = io } } }
                        val payload = Random(4).nextBytes(PAYLOAD)
                        val healthyStream = healthy.connection.openStream()
                        val brokenStream = broken.connection.openStream()
                        assertContentEquals(payload, echoOver(brokenStream, payload))

                        (doomed[0] as NioOneByOne).channel.close()
                        try {
                            val failure =
                                assertFailsWith<QuicCloseException> { withTimeout(10.seconds) { echoOver(brokenStream, payload) } }
                            assertIs<com.ditchoom.socket.quic.core.life.ConnEnd.SocketFailed>(
                                (broken.connection.end() as com.ditchoom.socket.quic.core.edge.EdgeEnd.Ended).end,
                                "the connection ended because its socket failed: $failure",
                            )

                            repeat(
                                ECHOES,
                            ) { round -> assertContentEquals(payload, echoOver(healthyStream, payload), "healthy echo $round") }
                        } catch (failed: Throwable) {
                            // Where each side stood when it went wrong, so the next occurrence names its cause.
                            throw AssertionError(
                                "broken=${broken.connection.state.value} healthy=${healthy.connection.state.value} " +
                                    "loop=${broken.loop.life.load()} connectionsOnLoop=${broken.loop.connections.value()}",
                                failed,
                            )
                        }
                        healthy.connection.close(QuicError.NoError)
                    } finally {
                        loops.close()
                    }
                }
            }
        }

    @Test
    fun aLoopThatFailsEndsItsConnectionsOnlyAndTheOtherLoopKeepsEchoingAndIsReplaced() =
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopTests::class) {
                withEchoServer { port ->
                    val loops = LoopSet(2)
                    // The loop's Error is reported on its thread, not contained; on Android the default handler would
                    // end the test process, so the report is captured here.
                    val reported = java.util.concurrent.CompletableFuture<Throwable>()
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                        if (thread.name.startsWith(
                                LoopSet.THREAD_PREFIX,
                            )
                        ) {
                            reported.complete(error)
                        } else {
                            previous?.uncaughtException(thread, error)
                        }
                    }
                    try {
                        val planted = PlantedError()
                        val flaky = arrayOfNulls<FailingIo>(1)
                        val first = withContext(Dispatchers.IO) { connect(port, loops) { FailingIo(it).also { io -> flaky[0] = io } } }
                        val other = withContext(Dispatchers.IO) { connect(port, loops) }
                        val sameLoop = withContext(Dispatchers.IO) { connect(port, loops) }
                        val otherToo = withContext(Dispatchers.IO) { connect(port, loops) }
                        assertSame(first.loop, sameLoop.loop)
                        assertSame(other.loop, otherToo.loop)
                        val payload = Random(6).nextBytes(PAYLOAD)
                        val streams = listOf(first, sameLoop, other, otherToo).map { it.connection.openStream() }
                        streams.forEach { assertContentEquals(payload, echoOver(it, payload)) }

                        val inFlight =
                            async(Dispatchers.Default) {
                                assertFailsWith<QuicCloseException>("the neighbour's echoes end with the loop") {
                                    withTimeout(10.seconds) { while (true) echoOver(streams[1], payload) }
                                }
                            }
                        kotlinx.coroutines.delay(20)
                        flaky[0]!!.plant = planted
                        val failure = assertFailsWith<QuicCloseException> { withTimeout(10.seconds) { echoOver(streams[0], payload) } }
                        inFlight.await()
                        assertSame(planted, failure.cause, "the loop's failure is the cause")
                        assertSame(planted, reported.get(5, java.util.concurrent.TimeUnit.SECONDS), "reported on the loop's thread")
                        assertSame(LoopLife.Stopped, first.loop.life.load())

                        repeat(ECHOES) { round ->
                            assertContentEquals(payload, echoOver(streams[2], payload), "echo $round on the other loop")
                            assertContentEquals(payload, echoOver(streams[3], payload), "echo $round on the other loop")
                        }
                        assertSame(LoopLife.Alive, other.loop.life.load())

                        val replaced = withContext(Dispatchers.IO) { connect(port, loops) }
                        assertTrue(replaced.loop !== first.loop, "a connection is not placed on a stopped loop")
                        assertContentEquals(payload, echoOver(replaced.connection.openStream(), payload))
                    } finally {
                        Thread.setDefaultUncaughtExceptionHandler(previous)
                        loops.close()
                    }
                }
            }
        }

    @Test
    fun aConnectionThatEndsLeavesItsLoopAndIsNotPlacedAgainstIt() =
        runBlocking {
            skipOnMissingNativeLib(SharedClientLoopTests::class) {
                withEchoServer { port ->
                    val loops = LoopSet(2)
                    try {
                        val first = withContext(Dispatchers.IO) { connect(port, loops) }
                        first.connection.close(QuicError.NoError)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        while (first.loop.connections.value() != 0L && System.nanoTime() < deadline) kotlinx.coroutines.delay(10)
                        assertEquals(0L, first.loop.connections.value(), "the ended connection left its loop")
                    } finally {
                        loops.close()
                    }
                }
            }
        }

    private suspend fun connect(
        port: Int,
        loops: LoopSet,
        wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
    ): CoreClient =
        coreConnect(
            host = "127.0.0.1",
            port = port,
            serverName = "localhost",
            options = coreTestOptions,
            factory = BufferFactory.Default,
            api = loadQuicheApi(),
            timeout = 20.seconds,
            readPolicy = ReadPolicy.Bounded(20.seconds),
            writePolicy = WritePolicy.Bounded(20.seconds),
            wrapIo = wrapIo,
            loops = loops,
        )

    /** The real socket's io, until a failure is planted: then its next receive throws it. */
    private class FailingIo(
        private val real: DatagramBatchIo,
    ) : DatagramBatchIo by real {
        @Volatile
        var plant: Throwable = NOT_PLANTED

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            val now = plant
            if (now !== NOT_PLANTED) throw now
            return real.receive(into)
        }
    }

    private companion object {
        const val CONNECTIONS = 64
        const val ECHOES = 1_000
        const val PAYLOAD = 100
        val NOT_PLANTED = IllegalStateException()
    }

    private class PlantedError : Error()
}
