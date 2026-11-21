@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A graceful close with writers on other threads still writing, over a real loopback socket, on every platform: the
 * close returns well inside its limit, the peer receives exactly the bytes accepted before the close point, and every
 * writer's next write is refused with [QuicCloseCircumstance.Closing].
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
            withCoreServer(onConnection = { connection ->
                repeat(WRITERS) {
                    val stream = connection.acceptStream()
                    launch {
                        try {
                            while (stream.read(10.seconds) { received.addAndFetch(it.remaining().toLong()) } is ScopedRead.Data<*>) Unit
                        } catch (ended: QuicCloseException) {
                            // The connection ended with nothing more held for this stream.
                        } finally {
                            reading.addAndFetch(-1)
                        }
                    }
                }
                awaitCancellation()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
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
                val writers = streams.map { stream -> async(Dispatchers.Default) { writeUntilRefused(stream) } }
                delay(WRITING)

                val began = TimeSource.Monotonic.markNow()
                val outcome = client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                val took = began.elapsedNow()
                val written = writers.map { it.await() }

                assertSame(CloseOutcome.Drained, outcome, "everything accepted before the close point was acknowledged")
                assertTrue(took < LIMIT, "close returned in $took; its limit is $LIMIT")
                for (writer in written) assertEquals<QuicCloseCircumstance>(QuicCloseCircumstance.Closing, writer.refusal)
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

    private suspend fun writeUntilRefused(stream: QuicByteStream): Written {
        val chunk = BufferFactory.network().allocate(CHUNK)
        var accepted = 0L
        while (true) {
            chunk.position(0)
            chunk.setLimit(CHUNK)
            try {
                accepted += stream.write(chunk, 10.seconds).count
            } catch (refused: QuicCloseException) {
                return Written(accepted, refused.circumstance)
            }
        }
    }

    private companion object {
        const val WRITERS = 4
        const val CHUNK = 16 * 1024
        val WRITING = 300.milliseconds
        val LIMIT = 10.seconds
    }
}
