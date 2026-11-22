package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.HandlerObservation
import com.ditchoom.socket.quic.core.sched.HandlerSite
import com.ditchoom.socket.quic.core.sched.PoolLife
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Stream handlers on a server's pool, end to end over loopback, under both send rules. */
class StreamHandlersTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    private val rules = listOf(SendRule.ByLoop, SendRule.InPlace)

    @Test
    fun handlersRunOnThePoolAndTheCallReturnsTheEndOnceTheConnectionEnds() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                for (rule in rules) {
                    val threads = ConcurrentHashMap.newKeySet<String>()
                    val handled = CompletableDeferred<StreamsHandled>()
                    withCoreServer(sends = rule, onConnection = { connection ->
                        handled.complete(
                            connection.handleStreams { stream ->
                                threads.add(Thread.currentThread().name)
                                echo(stream)
                            },
                        )
                    }) { server ->
                        val client = connectCore(server.port, loops = loops)
                        repeat(STREAMS) {
                            val stream = client.connection.openStream()
                            assertContentEquals(PAYLOAD, echoOver(stream, PAYLOAD), "$rule")
                            stream.close()
                        }
                        client.connection.close(QuicError.NoError)
                        assertIs<StreamsHandled.Ended>(withTimeout(10.seconds) { handled.await() }, "$rule")
                    }
                    assertTrue(threads.isNotEmpty() && threads.all { it.startsWith(SERVER_HANDLER_THREAD) }, "$rule: $threads")
                }
            }
        }

    @Test
    fun aSecondCallForTheSameConnectionIsRefusedAsMisuse() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                val second = CompletableDeferred<StreamsHandled>()
                withCoreServer(onConnection = { connection ->
                    launch(start = CoroutineStart.UNDISPATCHED) { connection.handleStreams { echo(it) } }
                    second.complete(connection.handleStreams { echo(it) })
                }) { server ->
                    val client = connectCore(server.port, loops = loops)
                    assertEquals(StreamsHandled.AlreadyHandling, withTimeout(10.seconds) { second.await() })
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aHandlerThatThrowsResetsOnlyItsStreamAndIsReported() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                for (rule in rules) {
                    val seen = CopyOnWriteArrayList<HandlerObservation>()
                    withCoreServer(sends = rule, onConnection = { connection ->
                        connection.handleStreams { stream ->
                            var first = true
                            while (stream.read(10.seconds) { chunk ->
                                    if (first && chunk.get(chunk.position()) == BOOM) throw IllegalStateException()
                                    first = false
                                    stream.writeFully(chunk, 10.seconds)
                                } is ScopedRead.Data<*>
                            ) {
                                Unit
                            }
                            stream.close()
                        }
                    }) { server ->
                        server.handlers.observe { seen.add(it) }
                        val client = connectCore(server.port, loops = loops)
                        val failing = client.connection.openStream()
                        failing.write(bufferOf(byteArrayOf(BOOM, 1, 2)), 10.seconds)
                        val answer = failing.read(10.seconds)
                        assertEquals(ReadResult.Reset, answer, "$rule: the failed handler's stream is reset")
                        val other = client.connection.openStream()
                        assertContentEquals(PAYLOAD, echoOver(other, PAYLOAD), "$rule: the other streams go on")
                        val failed = assertIs<HandlerObservation.Failed>(seen.single(), "$rule")
                        assertEquals(failing.streamId, assertIs<HandlerSite.Stream>(failed.site).stream)
                        assertIs<IllegalStateException>(failed.thrown.throwable)
                        client.connection.close(QuicError.NoError)
                    }
                }
            }
        }

    @Test
    fun anAcceptorThatResumesWhereItIsResumedNeverRunsOnTheServerLoop() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                val server =
                    coreServe(
                        mismatch = MismatchResponse.Fail,
                        bind = loopbackV4(),
                        tls = platformTlsConfig(),
                        options = coreTestOptions,
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                    )
                try {
                    val acceptedOn = CompletableDeferred<String>()
                    launch(Dispatchers.Unconfined) {
                        server.accept()
                        acceptedOn.complete(Thread.currentThread().name)
                    }
                    val client = connectCore(server.port, loops = loops)
                    val thread = withTimeout(10.seconds) { acceptedOn.await() }
                    assertNotEquals(SERVER_THREAD, thread)
                    assertTrue(thread.startsWith(SERVER_HANDLER_THREAD), thread)
                    client.connection.close(QuicError.NoError)
                } finally {
                    server.close()
                }
            }
        }

    @Test
    fun aCallerThatResumesWhereItIsResumedIsNeverResumedOnTheLoopAfterHandleStreams() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                val server = plainServer()
                try {
                    val after = CompletableDeferred<String>()
                    launch(Dispatchers.Unconfined) {
                        val connection = server.accept()
                        connection.handleStreams { echo(it) }
                        after.complete(Thread.currentThread().name)
                    }
                    val client = connectCore(server.port, loops = loops)
                    val stream = client.connection.openStream()
                    assertContentEquals(PAYLOAD, echoOver(stream, PAYLOAD))
                    client.connection.close(QuicError.NoError)
                    val thread = withTimeout(10.seconds) { after.await() }
                    assertNotEquals(SERVER_THREAD, thread, "the code after handleStreams ran on the server's loop")
                    assertTrue(thread.startsWith(SERVER_HANDLER_THREAD), thread)
                } finally {
                    server.close()
                }
            }
        }

    @Test
    fun aHandlerThatThrowsAnErrorResetsOnlyItsStreamIsReportedAndTheConnectionGoesOn() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                val seen = CopyOnWriteArrayList<HandlerObservation>()
                val uncaught = CopyOnWriteArrayList<Throwable>()
                val before = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.add(failure) }
                try {
                    withCoreServer(onConnection = { connection ->
                        connection.handleStreams { stream ->
                            when (stream.read(10.seconds) { it.get(it.position()) }) {
                                ScopedRead.Data(BOOM) -> throw AssertionError()
                                else -> Unit
                            }
                            stream.close()
                        }
                    }) { server ->
                        server.handlers.observe { seen.add(it) }
                        val client = connectCore(server.port, loops = loops)
                        val failing = client.connection.openStream()
                        failing.write(bufferOf(byteArrayOf(BOOM)), 10.seconds)
                        assertEquals(ReadResult.Reset, failing.read(10.seconds), "the stream of a handler that threw an Error is reset")
                        assertIs<AssertionError>(assertIs<HandlerObservation.Failed>(seen.single()).thrown.throwable)
                        assertIs<AssertionError>(uncaught.single(), "an Error also reaches the thread's uncaught handler")
                        val other = client.connection.openStream()
                        other.write(bufferOf(byteArrayOf(1)), 10.seconds)
                        assertEquals(ReadResult.End, other.read(10.seconds), "the connection and its other streams go on")
                        client.connection.close(QuicError.NoError)
                    }
                } finally {
                    Thread.setDefaultUncaughtExceptionHandler(before)
                }
            }
        }

    @Test
    fun aConnectionWhoseHandshakeFailsStartsNoHandlerThread() =
        runBlocking {
            skipOnMissingNativeLib(StreamHandlersTests::class) {
                val server = plainServer()
                try {
                    val refused =
                        runCatching {
                            connectCore(
                                server.port,
                                loops = loops,
                                options = coreTestOptions.copy(alpnProtocols = listOf("other")),
                            )
                        }
                    assertTrue(refused.isFailure, "a client offering no protocol the server speaks does not connect")
                    assertIs<PoolLife.Unstarted>(server.handlers.life(), "nothing was offered, so the pool did not start")
                    assertEquals(0, server.handlers.liveThreads())
                    val client = connectCore(server.port, loops = loops)
                    server.accept()
                    assertIs<PoolLife.Running>(server.handlers.life(), "the first offer starts the pool")
                    client.connection.close(QuicError.NoError)
                } finally {
                    server.close()
                }
            }
        }

    private suspend fun plainServer(): CoreServer =
        coreServe(
            mismatch = MismatchResponse.Fail,
            bind = loopbackV4(),
            tls = platformTlsConfig(),
            options = coreTestOptions,
            factory = BufferFactory.network(),
            api = platformQuicheApi(),
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
        )

    private suspend fun echo(stream: QuicByteStream) {
        while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
        stream.close()
    }

    private fun bufferOf(bytes: ByteArray) =
        BufferFactory.network().allocate(bytes.size).apply {
            writeBytes(bytes)
            resetForRead()
        }

    private companion object {
        const val STREAMS = 3
        const val BOOM: Byte = 0x42
        val PAYLOAD = ByteArray(256) { it.toByte() }
    }
}
