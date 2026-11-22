package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.path.IdIssuer
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Typed streams over a core client and a core server on a real loopback socket, on every platform. */
class TypedStreamTests {
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    private suspend fun <T : Any> ScopedRead<T>.value(): T = (assertIs<ScopedRead.Data<T>>(this)).value

    private fun notes(count: Int) = List(count) { Note(it, "note-$it-" + "x".repeat(it % 97)) }

    @Test
    fun valuesRoundTripByteExactThroughATypedEchoServer() =
        coreTest("TypedStreamTests.valuesRoundTripByteExactThroughATypedEchoServer") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                while (true) {
                    when (val read = stream.receive()) {
                        is ScopedRead.Data -> stream.send(read.value)
                        else -> break
                    }
                }
                stream.close()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                val sent = notes(500) + Note(7, "") + Note(8, "é漢字🙂".repeat(300)) + Note(9, "y".repeat(100_000))
                for (note in sent) {
                    stream.send(note)
                    assertEquals(note, stream.receive().value())
                }
                stream.finish()
                assertEquals(ScopedRead.End, stream.receive())
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun tenThousandEchoesOnOneStreamNeverResumeACallWithTheNextOnesWakeup() =
        coreTest("TypedStreamTests.tenThousandEchoesOnOneStreamNeverResumeACallWithTheNextOnesWakeup") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                while (true) {
                    when (val read = stream.receive()) {
                        is ScopedRead.Data -> stream.send(read.value)
                        else -> break
                    }
                }
                stream.close()
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                repeat(10_000) { n ->
                    val note = Note(n, "n" + "z".repeat(n % 5))
                    stream.send(note)
                    assertEquals(note, stream.receive().value())
                }
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun framesSplitAcrossReadsAreFoundOneByteAtATimeManyInOneReadAndSpanningManyReads() =
        coreTest("TypedStreamTests.framesSplitAcrossReadsAreFoundOneByteAtATimeManyInOneReadAndSpanningManyReads") {
            val bytewise = Note(1, "one byte at a time")
            val several = notes(40)
            val spanning = Note(3, "s".repeat(300_000))
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                val first = frameOf(bytewise)
                stream.writeInPieces(first, 1)
                // Several frames in one write.
                val together = several.map(::frameOf)
                val all = BufferFactory.network().allocate(together.sumOf { it.remaining() })
                together.forEach { all.write(it) }
                all.resetForRead()
                stream.writeFully(all, 10.seconds)
                // One frame over many writes.
                stream.writeInPieces(frameOf(spanning), 1_000)
                (stream as HalfCloseable).shutdownSend()
                delay(100.milliseconds)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(0, "go"))
                assertEquals(bytewise, stream.receive().value())
                for (note in several) assertEquals(note, stream.receive().value())
                assertEquals(spanning, stream.receive().value())
                assertEquals(ScopedRead.End, stream.receive())
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aWriterBlockedOnTheFlowWindowWaitsAndFinishesOnceThePeerReads() =
        coreTest("TypedStreamTests.aWriterBlockedOnTheFlowWindowWaitsAndFinishesOnceThePeerReads") {
            val reading = kotlinx.coroutines.CompletableDeferred<Unit>()
            val received = kotlinx.coroutines.CompletableDeferred<Int>()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                reading.await()
                var count = 0
                while (stream.receive() is ScopedRead.Data) count++
                received.complete(count)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                val big = Note(1, "b".repeat(1 shl 20))
                val sender =
                    async {
                        repeat(FRAMES) { stream.send(big) }
                        stream.finish()
                    }
                delay(1_000.milliseconds)
                assertTrue(sender.isActive, "the writer must be parked on the flow window while the peer does not read")
                reading.complete(Unit)
                withTimeout(20.seconds) { sender.await() }
                assertEquals(FRAMES, withTimeout(20.seconds) { received.await() })
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun anOversizedFrameFailsTheReadWithATypedErrorAndResetsTheStream() =
        coreTest("TypedStreamTests.anOversizedFrameFailsTheReadWithATypedErrorAndResetsTheStream") {
            val sawReset = kotlinx.coroutines.CompletableDeferred<ReadResult>()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                stream.read(10.seconds)
                stream.writeFully(headerDeclaring(1 shl 28), 10.seconds)
                sawReset.complete(stream.read(10.seconds))
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec, TypedStreamConfig(maxFrameBytes = 1 shl 16))
                stream.send(Note(0, "go"))
                val failure = assertFailsWith<QuicTypedStreamException> { stream.receive() }
                val oversized = assertIs<TypedFailure.Oversized>(failure.failure)
                assertEquals((1 shl 28) + 4, oversized.declared)
                assertEquals(1 shl 16, oversized.bound)
                // Every later read fails the same way.
                assertIs<TypedFailure.Oversized>(assertFailsWith<QuicTypedStreamException> { stream.receive() }.failure)
                assertEquals(ReadResult.Reset, withTimeout(10.seconds) { sawReset.await() })
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aTypedStreamThePeerResetReadsAsResetWithThePeersCode() =
        coreTest("TypedStreamTests.aTypedStreamThePeerResetReadsAsResetWithThePeersCode") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                stream.read(10.seconds)
                stream.reset(PEER_RESET_CODE)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                assertIs<PeerReset.NotMet>(stream.peerReset())
                stream.send(Note(0, "go"))
                assertEquals<ScopedRead<Note>>(ScopedRead.Reset, withTimeout(10.seconds) { stream.receive() })
                assertEquals(QuicAppErrorCode(PEER_RESET_CODE), assertIs<PeerReset.Reset>(stream.peerReset()).code)
                // The peer stopped our side too, so the stream is not finished: the connection's close ends it.
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aFrameTheCodecRejectsFailsTheReadWithATypedErrorAndResetsTheStream() =
        coreTest("TypedStreamTests.aFrameTheCodecRejectsFailsTheReadWithATypedErrorAndResetsTheStream") {
            val sawReset = kotlinx.coroutines.CompletableDeferred<ReadResult>()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                stream.read(10.seconds)
                stream.writeFully(frameOf(Note(NoteCodec.REJECTED_ID, "no")), 10.seconds)
                sawReset.complete(stream.read(10.seconds))
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(0, "go"))
                assertIs<TypedFailure.Rejected>(assertFailsWith<QuicTypedStreamException> { stream.receive() }.failure)
                assertEquals(ReadResult.Reset, withTimeout(10.seconds) { sawReset.await() })
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aPeerThatFinishesInsideAFrameFailsTheReadWithoutResettingTheStream() =
        coreTest("TypedStreamTests.aPeerThatFinishesInsideAFrameFailsTheReadWithoutResettingTheStream") {
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                stream.read(10.seconds)
                val frame = frameOf(Note(5, "truncated"))
                frame.setLimit(frame.limit() - 3)
                stream.writeFully(frame, 10.seconds)
                (stream as HalfCloseable).shutdownSend()
                delay(200.milliseconds)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(0, "go"))
                assertIs<TypedFailure.Truncated>(assertFailsWith<QuicTypedStreamException> { stream.receive() }.failure)
                // Our side is not reset: it still sends.
                stream.send(Note(1, "still open"))
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aReadCancelledInsideAFrameKeepsTheBytesAndTheNextReadFinishesTheFrame() =
        coreTest("TypedStreamTests.aReadCancelledInsideAFrameKeepsTheBytesAndTheNextReadFinishesTheFrame") {
            val rest = kotlinx.coroutines.CompletableDeferred<Unit>()
            val note = Note(11, "split frame ".repeat(50))
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptStream()
                stream.read(10.seconds)
                val frame = frameOf(note)
                val half = frame.remaining() / 2
                val end = frame.limit()
                frame.setLimit(half)
                stream.writeFully(frame, 10.seconds)
                rest.await()
                frame.setLimit(end)
                stream.writeFully(frame, 10.seconds)
                delay(200.milliseconds)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(0, "go"))
                assertFailsWith<TimeoutCancellationException> { withTimeout(300.milliseconds) { stream.receive() } }
                rest.complete(Unit)
                assertEquals(note, stream.receive().value())
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun cancellingReadsAndWritesAndClosingLeavesNoBufferOutstanding() =
        coreTest("TypedStreamTests.cancellingReadsAndWritesAndClosingLeavesNoBufferOutstanding") {
            val tracked = TrackedFactory()
            withCoreServer(onConnection = { connection ->
                val stream = connection.acceptTypedStream(NoteCodec)
                while (stream.receive() is ScopedRead.Data) Unit
                stream.close()
            }) { server ->
                val client = connectCore(server.port, loops = loops, factory = tracked)
                val connected = tracked.live()

                suspend fun cycle(round: Int) {
                    val stream = client.connection.openTypedStream(NoteCodec)
                    stream.send(Note(round, "hello"))
                    // A read parked on a stream that has nothing, cancelled.
                    assertFailsWith<TimeoutCancellationException> { withTimeout(20.milliseconds) { stream.receive() } }
                    // A read parked while the stream closes under it.
                    val parked = async { runCatching { stream.receive() } }
                    delay(20.milliseconds)
                    stream.close()
                    assertTrue(parked.await().isFailure, "the parked read must fail when the stream closes")
                }
                cycle(WARM_UP_ID)
                // A close hands the stream's buffers to the connection to free: they are freed once it ran that work.
                drained(client.conn)
                // Each stream holds two buffers of its own; once it is closed they are freed, so a cycle changes nothing.
                val settled = tracked.live()
                repeat(20) { round -> cycle(round) }
                assertTrue(
                    eventually { tracked.live() == settled },
                    "typed stream buffers were not freed: ${tracked.live() - settled} live after 20 streams (connect left $connected)",
                )
                client.connection.close(QuicError.NoError)
                // The connection's own memory goes with its loop thread's socket, not with the connection.
                loops.close()
                assertTrue(
                    eventually { tracked.live() == 0 },
                    "${tracked.live()} buffers still live once the connection and its loops closed",
                )
            }
        }

    @Test
    fun aByteStreamReadThatEndsLeavesOneIdleBufferWithTheConnection() =
        coreTest("TypedStreamTests.aByteStreamReadThatEndsLeavesOneIdleBufferWithTheConnection") {
            val tracked = TrackedFactory()
            withCoreServer(onConnection = { connection ->
                connection.acceptStream().also { it.read(10.seconds) }.let { (it as HalfCloseable).shutdownSend() }
                delay(1.seconds)
            }) { server ->
                val client = connectCore(server.port, loops = loops, factory = tracked)
                val before = tracked.live()
                val stream = client.connection.openStream()
                stream.write(frameOf(Note(1, "x")), 10.seconds)
                // The server finishes its side without sending: the read ends with no data, and its buffer is kept, not freed.
                assertEquals(ReadResult.End, stream.read(10.seconds))
                val held = tracked.live() - before
                // The connection's idle slot holds that one buffer, which only the connection's end frees.
                assertEquals(1, held, "the ended read's buffer is the connection's idle slot")
                client.connection.close(QuicError.NoError)
                loops.close()
                assertTrue(eventually { tracked.live() == 0 }, "${tracked.live()} buffers still live once the connection closed")
            }
        }

    @Test
    fun aSecondReadOnTheSameStreamAtTheSameTimeFailsAsBusy() =
        coreTest("TypedStreamTests.aSecondReadOnTheSameStreamAtTheSameTimeFailsAsBusy") {
            withCoreServer(onConnection = { connection ->
                connection.acceptStream().read(10.seconds)
                delay(1.seconds)
            }) { server ->
                val client = connectCore(server.port, loops = loops)
                val stream = client.connection.openTypedStream(NoteCodec)
                stream.send(Note(0, "go"))
                val first = launch { runCatching { stream.receive() } }
                delay(100.milliseconds)
                assertIs<TypedFailure.Busy>(assertFailsWith<QuicTypedStreamException> { stream.receive() }.failure)
                first.cancel()
                stream.close()
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun aConnectionReportsItselfEstablishedHoldingEveryBufferItKeeps() =
        coreTest("TypedStreamTests.aConnectionReportsItselfEstablishedHoldingEveryBufferItKeeps") {
            withCoreServer(onConnection = { delay(5.seconds) }) { server ->
                // The issuer's scratch is the last buffer the handshake's end makes: holding its allocation holds the loop there.
                val hold = AllocationHold.OfSize(IdIssuer.BYTES)
                val tracked = TrackedFactory(hold)
                val client = connectCore(server.port, loops = loops, factory = tracked)
                val atConnect = tracked.live()
                hold.release()
                drained(client.conn)
                assertEquals(atConnect, tracked.live(), "the connection took buffers after it reported itself established")
                client.connection.close(QuicError.NoError)
            }
        }

    private companion object {
        const val FRAMES = 24
        const val PEER_RESET_CODE = 0x10cL
        const val WARM_UP_ID = 1_000
    }
}
