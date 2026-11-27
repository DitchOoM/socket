@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A graceful close with writers on other threads parked mid-write, over a real loopback socket, on every platform: the
 * close returns well inside its limit, the peer receives exactly the bytes accepted before the close point, and every
 * write parked at the close point is refused with [QuicCloseCircumstance.Ended].
 */
class CoreClosePointTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun writersOnOtherThreadsStopAtTheClosePointAndThePeerGetsExactlyWhatWasAccepted() =
        coreTest("CoreClosePointTests.writersOnOtherThreadsStopAtTheClosePointAndThePeerGetsExactlyWhatWasAccepted") {
            val received = AtomicLong(0L)
            val reading = AtomicInt(WRITERS)
            // The server reads nothing until the close is asked: each stream's flow window fills, so every writer is
            // parked at the close point. quiche acknowledges what arrives whether or not it is read.
            val closing = CompletableDeferred<Unit>()
            withCoreServer(options = OPTIONS, onConnection = { connection ->
                repeat(WRITERS) {
                    val stream = connection.acceptStream()
                    launch {
                        try {
                            closing.await()
                            while (stream.read(
                                    10.seconds,
                                ) { received.addAndFetch(it.remaining().toLong()) } is ScopedReadResult.Data<*>
                            ) {
                                Unit
                            }
                        } catch (ended: QuicCloseException) {
                            // The connection ended with nothing more held for this stream.
                        } finally {
                            reading.addAndFetch(-1)
                        }
                    }
                }
                awaitCancellation()
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = OPTIONS)
                val streams = List(WRITERS) { client.connection.openStream() }
                for (stream in streams) {
                    stream.writeFully(
                        BufferFactory
                            .network()
                            .allocate(1)
                            .apply { writeByte(1) }
                            .also { it.resetForRead() },
                        10.seconds,
                    )
                }
                val writers = streams.map { Writer(it) }
                val writing = writers.map { writer -> async(Dispatchers.Default) { writer.writeUntilRefused() } }
                assertTrue(eventually(within = 10.seconds) { allParked(writers) }, "every writer parked on its stream's full window")

                closing.complete(Unit)
                val began = TimeSource.Monotonic.markNow()
                val outcome = client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                val took = began.elapsedNow()
                val written = writing.map { it.await() }

                assertSame(CloseOutcome.Drained, outcome, "everything accepted before the close point was acknowledged")
                assertTrue(took < LIMIT, "close returned in $took; its limit is $LIMIT")
                for (writer in written) {
                    assertEquals<QuicCloseCircumstance>(QuicCloseCircumstance.Ended, writer.refusal, "a write parked at the close point")
                }
                val accepted = written.sumOf { it.accepted } + WRITERS
                assertTrue(written.all { it.accepted > 0 }, "every writer wrote before the close")
                assertTrue(eventually(within = 10.seconds) { reading.load() == 0 }, "the server read every stream to its end")
                assertEquals(accepted, received.load(), "the peer received exactly the bytes accepted before the close point")
            }
        }

    /** What one writer had written when its write was refused. */
    private class Written(
        val accepted: Long,
        val refusal: QuicCloseCircumstance,
    )

    /** Writes one stream in a loop until a write is refused; any thread reads whether it is inside a write, and how far it got. */
    private class Writer(
        private val stream: QuicByteStream,
    ) {
        val inWrite = AtomicInt(0)
        val accepted = AtomicLong(0L)

        suspend fun writeUntilRefused(): Written {
            val chunk = BufferFactory.network().allocate(CHUNK)
            while (true) {
                chunk.position(0)
                chunk.setLimit(CHUNK)
                inWrite.store(1)
                try {
                    accepted.addAndFetch(stream.write(chunk, 10.seconds).count.toLong())
                } catch (refused: QuicCloseException) {
                    return Written(accepted.load(), refused.circumstance)
                } finally {
                    inWrite.store(0)
                }
            }
        }
    }

    /** Every writer filled its stream's window (its first byte included) and is inside its next write: it is parked. */
    private fun allParked(writers: List<Writer>): Boolean = writers.all { it.inWrite.load() == 1 && it.accepted.load() + 1 == WINDOW }

    private companion object {
        const val WRITERS = 4
        const val CHUNK = 16 * 1024
        const val WINDOW = 256L * 1024
        val LIMIT = 10.seconds
        val OPTIONS =
            coreTestOptions.copy(flowControl = FlowControl(initialMaxStreamDataBidiLocal = WINDOW, initialMaxStreamDataBidiRemote = WINDOW))
    }
}
