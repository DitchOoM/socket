package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamClosedException
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The public stream and connection API on the run-to-completion core, against today's server over a real socket. */
class CoreEdgeTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds, standbyLink = StandbyLink.OnDemand)

    @Test
    fun smallAndLargeEchoesAreByteExact() =
        withCoreClient { connection ->
            for (size in listOf(1, 1_200, 1 shl 20)) {
                val payload = Random(size).nextBytes(size)
                assertContentEquals(payload, echo(connection.openStream(), payload), "echo of $size bytes")
            }
        }

    @Test
    fun eightConcurrentStreamsEachEchoTheirOwnBytes() =
        withCoreClient { connection ->
            val payloads = List(8) { Random(it).nextBytes(64 * 1024) }
            val echoed = payloads.map { payload -> async { echo(connection.openStream(), payload) } }.awaitAll()
            payloads.zip(echoed).forEachIndexed { i, (sent, back) -> assertContentEquals(sent, back, "stream $i") }
        }

    @Test
    fun aReadPastItsDeadlineRaisesTheSameTimeoutAs4x() =
        withCoreClient(serverHolds = true) { connection ->
            val stream = connection.openStream()
            stream.writeFully(bytes(byteArrayOf(1)), 5.seconds)
            assertFailsWith<TimeoutCancellationException> { stream.read(200.milliseconds) }
        }

    @Test
    fun aCancelledReadLeavesTheStreamUsable() =
        withCoreClient { connection ->
            val stream = connection.openStream()
            val pending = launch { stream.read(10.seconds) }
            delay(100.milliseconds)
            pending.cancel()
            pending.join()
            val payload = Random(3).nextBytes(4_000)
            assertContentEquals(payload, echo(stream, payload), "echo after a cancelled read")
        }

    @Test
    fun closeReportsALocalGracefulClose() =
        withCoreClient(closeAtEnd = false) { connection ->
            connection.close(QuicError.NoError)
            assertEquals(QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.NoError)), connection.state.value)
        }

    @Test
    fun aReadOnAClosedStreamRaisesAStreamClosedExceptionForTheReadSide() =
        withCoreClient { connection ->
            val stream = connection.openStream()
            stream.close()
            val failure = assertFailsWith<QuicStreamClosedException> { stream.read(1.seconds) { } }
            assertEquals(QuicStreamSide.Read, failure.side)
            assertEquals(stream.streamId.id, failure.streamId)
        }

    @Test
    fun aWriteOnAClosedOrFinishedStreamRaisesAStreamClosedExceptionForTheWriteSide() =
        withCoreClient { connection ->
            val closed = connection.openStream()
            closed.close()
            val afterClose = assertFailsWith<QuicStreamClosedException> { closed.writeFully(bytes(byteArrayOf(1)), 1.seconds) }
            assertEquals(QuicStreamSide.Write, afterClose.side)
            assertEquals(closed.streamId.id, afterClose.streamId)

            val finished = connection.openStream()
            finished.shutdownSend()
            val afterFinish = assertFailsWith<QuicStreamClosedException> { finished.writeFully(bytes(byteArrayOf(1)), 1.seconds) }
            assertEquals(QuicStreamSide.Write, afterFinish.side)
        }

    @Test
    fun aHandshakeTheClientRejectsRaisesTheHandshakeFailedCircumstanceWithTheCloseReason() =
        runBlocking {
            skipOnMissingNativeLib(CoreEdgeTests::class) {
                withQuicServer(port = 0, tlsConfig = tlsConfig(), quicOptions = options) {
                    val failure =
                        assertFailsWith<QuicCloseException> {
                            withContext(Dispatchers.IO) {
                                coreConnect(
                                    mismatch = MismatchResponse.Fail,
                                    host = "127.0.0.1",
                                    port = port,
                                    serverName = "localhost",
                                    options = options.copy(verifyPeer = true),
                                    factory = BufferFactory.Default,
                                    api = loadQuicheApi(),
                                    timeout = 10.seconds,
                                    readPolicy = ReadPolicy.Bounded(10.seconds),
                                    writePolicy = WritePolicy.Bounded(10.seconds),
                                )
                            }
                        }
                    assertEquals(QuicCloseCircumstance.HandshakeFailed, failure.circumstance)
                    assertTrue(failure.closeReason !is QuicCloseReason.Graceful, "the reason says why: ${failure.closeReason}")
                }
            }
        }

    private suspend fun echo(
        stream: QuicByteStream,
        payload: ByteArray,
    ): ByteArray {
        val received = ByteArrayOutputStream()
        coroutineScopeOf {
            val reader =
                async {
                    while (received.size() < payload.size) {
                        if (stream.read(10.seconds) { received.write(it.readByteArray(it.remaining())) } !is ScopedRead.Data<*>) break
                    }
                }
            stream.writeFully(bytes(payload), 10.seconds)
            reader.await()
        }
        return received.toByteArray()
    }

    private fun withCoreClient(
        serverHolds: Boolean = false,
        closeAtEnd: Boolean = true,
        body: suspend kotlinx.coroutines.CoroutineScope.(CoreConnection) -> Unit,
    ) = runBlocking {
        skipOnMissingNativeLib(CoreEdgeTests::class) {
            withQuicServer(port = 0, tlsConfig = tlsConfig(), quicOptions = options) {
                val release = CompletableDeferred<Unit>()
                val server =
                    launch(Dispatchers.IO) {
                        connections {
                            while (true) {
                                val stream = acceptStream()
                                launch {
                                    if (serverHolds) release.await()
                                    // The client closing the connection ends the echo; it is not a failure of the server.
                                    try {
                                        while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
                                    } catch (closed: com.ditchoom.socket.quic.QuicCloseException) {
                                        Unit
                                    }
                                }
                            }
                        }
                    }
                withContext(Dispatchers.IO) {
                    val connection =
                        coreConnect(
                            mismatch = MismatchResponse.Fail,
                            host = "127.0.0.1",
                            port = port,
                            serverName = "localhost",
                            options = options,
                            factory = BufferFactory.Default,
                            api = loadQuicheApi(),
                            timeout = 10.seconds,
                            readPolicy = ReadPolicy.Bounded(10.seconds),
                            writePolicy = WritePolicy.Bounded(10.seconds),
                        ).connection
                    try {
                        body(connection)
                    } finally {
                        release.complete(Unit)
                        if (closeAtEnd) connection.close(QuicError.NoError)
                    }
                }
                server.cancel()
            }
        }
    }

    private fun bytes(payload: ByteArray) =
        BufferFactory.Default.allocate(payload.size).apply {
            writeBytes(payload)
            resetForRead()
        }

    private fun tlsConfig(): QuicTlsConfig = coreTlsConfig()
}

private suspend fun <T> coroutineScopeOf(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
    kotlinx.coroutines.coroutineScope(block)
