package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.NoServerLoopLeft
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.HANDLER_FAILED
import com.ditchoom.socket.quic.core.sched.HandlerObservation
import com.ditchoom.socket.quic.core.sched.HandlerPlacement
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** SPIKE: stream handlers placed on the server's loop thread ([HandlerPlacement.OnLoop]). */
class OnLoopHandlerTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(1)
    private val observations = ConcurrentLinkedQueue<HandlerObservation>()

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun anOnLoopHandlerRunsEveryStepOnTheServerLoopThread() =
        runBlocking {
            val threads = ConcurrentLinkedQueue<String>()
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                threads += threadName()
                echo(stream) { threads += threadName() }
            }) { server, _ ->
                echoesOnAFreshConnection(server.port, 20)
            }
            assertTrue(threads.isNotEmpty())
            assertEquals(setOf(SERVER_THREAD), threads.toSet(), "threads the handler ran on")
        }

    @Test
    fun anOnLoopEchoSendsOneDatagramFromTheServerWithTheAckRidingTheReply() =
        runBlocking {
            withHandlerServer(HandlerPlacement.OnLoop, handler = { echo(it) }) { server, _ ->
                val client = connectCore(server.port, loops = loops)
                try {
                    val stream = client.connection.openStream()
                    repeat(WARM) { roundTrip(stream) }
                    delay(50.milliseconds)
                    val before = server.loop.counts.datagramsOut
                    repeat(ECHOES) { roundTrip(stream) }
                    val perEcho = (server.loop.counts.datagramsOut - before).toDouble() / ECHOES
                    println("[on-loop] server datagrams out per echo: $perEcho")
                    assertTrue(perEcho < 1.2, "server datagrams per echo: $perEcho (an ACK alone, then the reply, is 2)")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aHandlerThatThrowsResetsOnlyItsStreamAndIsReported() =
        runBlocking {
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                echo(stream) { first -> if (first == POISON) error("handler threw") }
            }) { server, _ ->
                val client = connectCore(server.port, loops = loops)
                try {
                    val healthy = client.connection.openStream()
                    roundTrip(healthy)
                    val poisoned = client.connection.openStream()
                    poisoned.writeFully(payload(POISON), 10.seconds)
                    val answer = poisoned.read(10.seconds)
                    assertTrue(answer is ReadResult.Reset, "the poisoned stream is reset, not $answer")
                    repeat(10) { roundTrip(healthy) }
                    val failed = observations.filterIsInstance<HandlerObservation.Failed>().single()
                    assertEquals(poisoned.streamId, (failed.site as HandlerSite.Stream).stream)
                } finally {
                    client.connection.close(QuicError.NoError)
                }
                echoesOnAFreshConnection(server.port, 5)
            }
            assertEquals(HANDLER_FAILED, 0x5e1fL)
        }

    @Test
    fun aHandlerCancelledWhileSuspendedLeavesTheLoopHealthy() =
        runBlocking {
            val parked = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                healthyOr(stream) {
                    parked.complete(Unit)
                    try {
                        stream.read(10.seconds).let { if (it is ReadResult.Data) it.buffer.freeIfNeeded() }
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }, onHandling = { job -> launch { if (parked.isActive) parked.await().also { job.cancel() } } }) { server, _ ->
                val client = connectCore(server.port, loops = loops)
                try {
                    // Opens the stream with a first byte; the handler parks in its read of the next.
                    val stream = client.connection.openStream()
                    stream.writeFully(payload(1, size = 1), 10.seconds)
                    withTimeout(5.seconds) { cancelled.await() }
                } finally {
                    client.connection.close(QuicError.NoError)
                }
                echoesOnAFreshConnectionOffLoopToo(server.port)
            }
        }

    @Test
    fun aHandlerResumedFromAnotherThreadContinuesOnTheLoop() =
        runBlocking {
            val after = ConcurrentLinkedQueue<String>()
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                delay(2.milliseconds)
                after += threadName()
                val elsewhere = CompletableDeferred<Unit>()
                launch(Dispatchers.Default) {
                    delay(2.milliseconds)
                    elsewhere.complete(Unit)
                }
                elsewhere.await()
                after += threadName()
                echo(stream)
            }) { server, _ ->
                echoesOnAFreshConnection(server.port, 10)
            }
            assertEquals(listOf(SERVER_THREAD, SERVER_THREAD), after.toList())
        }

    @Test
    fun aConnectionClosedWhileItsHandlerIsSuspendedCancelsTheHandler() =
        runBlocking {
            val suspended = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                healthyOr(stream) {
                    try {
                        suspended.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }) { server, _ ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openStream()
                stream.writeFully(payload(1, size = 1), 10.seconds)
                withTimeout(5.seconds) { suspended.await() }
                client.connection.close(QuicError.NoError)
                withTimeout(5.seconds) { cancelled.await() }
                echoesOnAFreshConnectionOffLoopToo(server.port)
            }
        }

    @Test
    fun aHandlerThatHoldsTheLoopIsReportedByStream() =
        runBlocking {
            withHandlerServer(HandlerPlacement.OnLoop, handler = { stream ->
                echo(stream) { first -> if (first == POISON) Thread.sleep(5) }
            }) { server, _ ->
                val client = connectCore(server.port, loops = loops)
                try {
                    val stream = client.connection.openStream()
                    roundTrip(stream, POISON)
                    val stalled = observations.filterIsInstance<HandlerObservation.Stalled>()
                    assertTrue(stalled.isNotEmpty(), "no stall observed")
                    val site = stalled.first().site as HandlerSite.Stream
                    assertEquals(stream.streamId, site.stream)
                    assertTrue(stalled.first().heldNanos >= 5_000_000L, "held ${stalled.first().heldNanos} ns")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    // ---- support ----

    private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")

    /** Echoes a stream whose first byte is [HEALTHY]; any other stream runs [hostile] after its first chunk. */
    private suspend fun healthyOr(
        stream: QuicByteStream,
        hostile: suspend () -> Unit,
    ) {
        val first = stream.read(10.seconds)
        if (first !is ReadResult.Data) return
        if (first.buffer.get(first.buffer.position()) == HEALTHY) {
            stream.writeFully(first.buffer, 10.seconds)
            first.buffer.freeIfNeeded()
            echo(stream)
        } else {
            first.buffer.freeIfNeeded()
            hostile()
        }
    }

    /** Echoes [stream] until its end; [each] sees the first byte of every chunk before it is echoed. */
    private suspend fun echo(
        stream: QuicByteStream,
        each: (Byte) -> Unit = {},
    ) {
        while (stream.read(10.seconds) {
                each(it.get(it.position()))
                stream.writeFully(it, 10.seconds)
            } is ScopedRead.Data<*>
        ) {
            Unit
        }
        stream.close()
    }

    private suspend fun echoesOnAFreshConnection(
        port: Int,
        times: Int,
    ) {
        val client = connectCore(port, loops = loops)
        try {
            val stream = client.connection.openStream()
            repeat(times) { roundTrip(stream) }
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }

    /** The loop still serves: a fresh connection echoes, with its payload intact. */
    private suspend fun echoesOnAFreshConnectionOffLoopToo(port: Int) {
        val client = connectCore(port, loops = loops)
        try {
            val sent = Random(7).nextBytes(4_000)
            sent[0] = HEALTHY
            assertContentEquals(sent, echoOver(client.connection.openStream(), sent))
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }

    private suspend fun roundTrip(
        stream: QuicByteStream,
        first: Byte = 1,
    ) {
        stream.writeFully(payload(first), 10.seconds)
        var received = 0
        while (received < PAYLOAD) {
            val result = stream.read(10.seconds)
            if (result !is ReadResult.Data) error("the echo ended early: $result")
            received += result.buffer.remaining()
            result.buffer.freeIfNeeded()
        }
    }

    private fun payload(
        first: Byte,
        size: Int = PAYLOAD,
    ) = BufferFactory.network().allocate(size).apply {
        writeByte(first)
        repeat(size - 1) { writeByte(it.toByte()) }
        resetForRead()
    }

    /**
     * A server whose every connection runs [handler] for each stream, placed by [placement]; afterwards every buffer the
     * core took from the server's factory has been freed exactly once.
     */
    private suspend fun withHandlerServer(
        placement: HandlerPlacement,
        handler: suspend CoroutineScope.(QuicByteStream) -> Unit,
        onHandling: CoroutineScope.(Job) -> Unit = {},
        body: suspend CoroutineScope.(CoreServer, FreeCountingFactory) -> Unit,
    ) {
        val factory = FreeCountingFactory(BufferFactory.network(), setOf(FreeCountingFactory.Site.Core))
        val server =
            coreServe(
                mismatch = MismatchResponse.Fail,
                bind = loopbackV4(),
                tls = platformTlsConfig(),
                options = coreTestOptions,
                factory = factory,
                api = platformQuicheApi(),
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
            )
        server.loop.port.handlers
            .observe { observations += it }
        try {
            kotlinx.coroutines.coroutineScope {
                val accepting =
                    launch(Dispatchers.Default) {
                        runCatching {
                            server.connections().collect { connection -> onHandling(connection.handleStreams(placement, handler)) }
                        }
                    }
                body(server, factory)
                accepting.cancel()
            }
        } finally {
            server.close()
        }
        assertTrue(factory.edgeBuffers().isNotEmpty(), "the core took no buffer from the server's factory")
        val unfreed = factory.unfreedAfter(5.seconds)
        val twice = factory.edgeBuffers().filter { it.frees.get() > 1 }
        assertTrue(twice.isEmpty(), "freed more than once: $twice")
        assertTrue(unfreed.isEmpty(), "never freed: ${unfreed.groupBy { it.place }.mapValues { it.value.size }}")
    }

    private companion object {
        const val PAYLOAD = 1_024
        const val WARM = 20
        const val ECHOES = 200
        const val POISON: Byte = 0x55
        const val HEALTHY: Byte = 0x22
    }
}
