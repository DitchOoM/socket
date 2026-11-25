package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Closing a core connection over a real loopback socket, on every platform: every call still waiting fails with the
 * typed close once the end is published, a write parked on the peer's flow window is refused at the close point, and
 * once the connection's linger is over every buffer it took from the caller's factory is back.
 */
class CoreCloseTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun everyWaitingCallFailsWithTheTypedCloseAndSeesTheEndAlreadyPublished() =
        coreTest("CoreCloseTests.everyWaitingCallFailsWithTheTypedCloseAndSeesTheEndAlreadyPublished") {
            withCoreServer(onConnection = { connection ->
                connection.acceptStream().read(10.seconds)
                awaitCancellation()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val connection = client.connection
                val stream = connection.openStream()
                stream.writeFully(
                    BufferFactory
                        .network()
                        .allocate(1)
                        .apply { writeByte(1) }
                        .also { it.resetForRead() },
                    10.seconds,
                )
                val typed = connection.openTypedStream(NoteCodec)
                val parked =
                    listOf(
                        async { failureOf(connection, "a parked read") { stream.read(10.seconds) } },
                        async { failureOf(connection, "a parked accept") { connection.acceptStream() } },
                        async { failureOf(connection, "a parked typed receive") { typed.receive() } },
                    )
                delay(PARK)
                connection.close(QuicError.NoError)
                for (failure in parked.map { it.await() }) {
                    assertEquals<QuicCloseReason>(
                        QuicCloseReason.ByLocal(QuicError.NoError),
                        failure.reason,
                        "${failure.call} failed typed",
                    )
                    assertTrue(failure.endPublished, "${failure.call} failed after the end was published")
                }
            }
        }

    @Test
    fun aWriteParkedOnThePeersFlowWindowIsRefusedAtTheClosePointAndTheCloseReturns() =
        coreTest("CoreCloseTests.aWriteParkedOnThePeersFlowWindowIsRefusedAtTheClosePointAndTheCloseReturns") {
            val serverRead = CompletableDeferred<Unit>()
            withCoreServer(onConnection = { connection ->
                // Reads once, then never again: the stream's flow window fills.
                connection.acceptStream().read(10.seconds)
                serverRead.complete(Unit)
                awaitCancellation()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openStream()
                val write =
                    async {
                        failureOf(client.connection, "a parked write") {
                            stream.writeFully(BufferFactory.network().allocate(BEYOND_WINDOW), 10.seconds)
                        }
                    }
                serverRead.await()
                delay(PARK)
                client.connection.close(QuicError.NoError)
                val failure = write.await()
                assertEquals<QuicCloseReason>(QuicCloseReason.ByLocal(QuicError.NoError), failure.reason)
                assertEquals<QuicCloseCircumstance>(QuicCloseCircumstance.Ended, failure.circumstance, "refused at the close point")
            }
        }

    @Test
    fun streamDataHeldAtThePeersCloseAndNeverReadIsGivenBackOnceTheApplicationClosesTheConnection() =
        coreTest("CoreCloseTests.streamDataHeldAtThePeersCloseAndNeverReadIsGivenBackOnceTheApplicationClosesTheConnection") {
            heldAtThePeersClose(read = false)
        }

    @Test
    fun streamDataHeldAtThePeersCloseAndReadIsGivenBackOnceTheApplicationClosesTheConnection() =
        coreTest("CoreCloseTests.streamDataHeldAtThePeersCloseAndReadIsGivenBackOnceTheApplicationClosesTheConnection") {
            heldAtThePeersClose(read = true)
        }

    /** The server writes and closes; the client's connection holds what arrived; [read] or not, every buffer comes back. */
    private suspend fun heldAtThePeersClose(read: Boolean) {
        val tracked = TrackedFactory()
        withCoreServer(onConnection = { connection ->
            connection.openStream().writeFully(
                BufferFactory
                    .network()
                    .allocate(HELD)
                    .also { it.position(HELD) }
                    .also { it.resetForRead() },
                10.seconds,
            )
            connection.close(QuicError.NoError)
        }) { server ->
            // The loop takes its own memory from the factory of the first connection placed on it: not the tracked one.
            connectCore(server.port, loops = loops, options = acceptingClientOptions).connection.close(QuicError.NoError)
            val client = connectCore(server.port, loops = loops, options = acceptingClientOptions, factory = tracked)
            assertTrue(eventually(within = 10.seconds) { client.connection.end() is EdgeEnd.Ended }, "the server's close arrived")
            val stream = client.connection.acceptStream()
            if (read) {
                var got = 0
                try {
                    while (stream.read(10.seconds) { got += it.remaining() } is com.ditchoom.socket.quic.ScopedReadResult.Data<*>) Unit
                } catch (closed: QuicCloseException) {
                    // The server never finished the stream: after what was held, the read meets the close.
                }
                assertEquals(HELD, got, "what was held was read")
            }
            // The application is done with the connection: what it never read goes back.
            client.connection.close(QuicError.NoError)
            assertTrue(
                eventually(within = 10.seconds) { tracked.live() == 0 },
                "${tracked.live()} buffers still live once the application closed the connection (read=$read): ${client.conn.life()::class.simpleName}",
            )
        }
    }

    @Test
    fun onceItsLingerIsOverAClosedConnectionHasGivenBackEveryBufferItTook() =
        coreTest("CoreCloseTests.onceItsLingerIsOverAClosedConnectionHasGivenBackEveryBufferItTook") {
            val tracked = TrackedFactory()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                while (true) stream.read(10.seconds) { stream.writeFully(it, 10.seconds) }
            }) { server ->
                // The loop takes its own memory from the factory of the first connection placed on it: not the tracked one.
                connectCore(server.port, loops = loops).connection.close(QuicError.NoError)
                val clients = ArrayList<CoreClient>()
                repeat(ROUNDS) { round ->
                    val client = connectCore(server.port, loops = loops, factory = tracked)
                    clients += client
                    val payload = ByteArray(ECHO) { (it + round).toByte() }
                    val stream = client.connection.openStream()
                    val echoed = echoOver(stream, payload)
                    assertEquals(payload.toList(), echoed.toList())
                    client.connection.close(QuicError.NoError)
                }
                // Each connection's memory, its close's copy included, goes back once its linger ends: the loops stay up.
                assertTrue(
                    eventually(within = 10.seconds) { tracked.live() == 0 },
                    "${tracked.live()} buffers still live after $ROUNDS connections closed and lingered: " +
                        clients.map { it.conn.life()::class.simpleName },
                )
            }
        }

    @Test
    fun aServerConnectionTheApplicationClosesLeavesTheServersRoutesOnceItsLingerIsOver() =
        coreTest("CoreCloseTests.aServerConnectionTheApplicationClosesLeavesTheServersRoutesOnceItsLingerIsOver") {
            withCoreServer(onConnection = { connection ->
                connection.acceptStream().read(10.seconds)
                connection.close(QuicError.NoError)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openStream()
                stream.writeFully(
                    BufferFactory
                        .network()
                        .allocate(1)
                        .apply { writeByte(1) }
                        .also { it.resetForRead() },
                    10.seconds,
                )
                val failure = failureOf(client.connection, "a read the server's close ends") { stream.read(10.seconds) }
                assertEquals<QuicCloseReason>(
                    QuicCloseReason.ByPeer(QuicError.NoError),
                    failure.reason,
                    "the client read the server's close",
                )
                assertTrue(
                    eventually(within = 10.seconds) { server.parts.connections == 0 },
                    "the server still routes to the closed connection",
                )
            }
        }

    /** How a waiting call failed: its typed reason and circumstance, and whether its connection's end was already published. */
    private class Failure(
        val call: String,
        val reason: QuicCloseReason,
        val circumstance: QuicCloseCircumstance,
        val endPublished: Boolean,
    )

    /** Runs [call], which must fail with the close of [connection]. */
    private suspend fun failureOf(
        connection: CoreConnection,
        name: String,
        call: suspend () -> Any,
    ): Failure {
        val thrown =
            try {
                call()
                throw AssertionError("$name returned instead of failing with the close")
            } catch (closed: QuicCloseException) {
                closed
            }
        return Failure(name, thrown.closeReason, thrown.circumstance, connection.state.value is QuicConnectionState.Closed)
    }

    private companion object {
        const val HELD = 8 * 1024
        val PARK = 100.milliseconds
        const val BEYOND_WINDOW = 4 * 1024 * 1024
        const val ROUNDS = 8
        const val ECHO = 4_096
    }
}
