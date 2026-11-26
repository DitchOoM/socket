package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.core.stream.StreamKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `openStream()` and `openUniStream()` at the peer's stream limit, on a real core client and server on virtual time. A peer at
 * its limit paces the opens, so an open past it waits for MAX_STREAMS, in call order and apart for each kind, ends with the
 * connection's close or its policy's bound, and spends no stream id while it waits or when it is cancelled; a stream made for
 * a caller who then left is given back, not left open.
 */
class CoreOpenAtLimitVirtualTests {
    private fun options(
        policy: StreamOpenPolicy = StreamOpenPolicy.UntilClosed,
        uni: Long = LIMIT,
    ) = QuicOptions(
        alpnProtocols = listOf("test"),
        verifyPeer = false,
        idleTimeout = 30.seconds,
        enablePmtuDiscovery = false,
        incomingStreams = IncomingStreams.Accept(QuicStreamLimit(LIMIT.toUInt()), QuicStreamLimit(uni.toUInt())),
        streamOpenPolicy = policy,
    )

    @Test
    fun anOpenPastThePeersLimitWaitsAndCompletesWhenThePeerReleasesAStream() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.openStream() }
            opened(pair) { client.openStream() }
            var third: QuicByteStream? = null
            launch { third = client.openStream() }
            pair.runFor(WAITING_PROOF)
            assertNull(third, "an open past the peer's limit waits for MAX_STREAMS, it neither fails nor proceeds")

            release(pair, server, first)
            pair.runUntil { third != null }
            assertEquals(8L, checkNotNull(third).streamId.id, "the waiting open got the next contiguous client bidirectional id")
        }

    @Test
    fun aBoundedOpenFailsAsAPassedDeadlineAndSpendsNoId() =
        VirtualPair.with(options(StreamOpenPolicy.Bounded(BOUND))) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.openStream() }
            opened(pair) { client.openStream() }
            val asked = pair.now()
            var failedAt = -1L
            var failure: Throwable? = null
            launch {
                failure = runCatching { client.openStream() }.exceptionOrNull()
                failedAt = pair.now()
            }
            pair.runUntil(within = BOUND * 3) { failedAt >= 0 }
            assertIs<TimeoutCancellationException>(failure, "the failure a write's passed deadline is")
            assertTrue(failedAt - asked >= BOUND.inWholeNanoseconds, "it waited its bound: ${(failedAt - asked) / MS} ms")

            release(pair, server, first)
            val next = opened(pair) { client.openStream() }
            assertEquals(8L, next.streamId.id, "the open that passed its bound spent no id")
        }

    @Test
    fun aPeerCloseWhileAnOpenWaitsFailsItWithTheCloseAndItsCode() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            opened(pair) { client.openStream() }
            opened(pair) { client.openStream() }
            var failure: Throwable? = null
            launch { failure = runCatching { client.openStream() }.exceptionOrNull() }
            pair.runFor(WAITING_PROOF)
            assertNull(failure)

            launch { server.close(QuicError.ApplicationError(CLOSE_CODE)) }
            pair.runUntil { failure != null }
            val end = assertIs<QuicCloseException>(failure, "a waiting open fails with the close: $failure").end
            assertEquals(QuicConnectionEnd.ByPeer(QuicError.ApplicationError(CLOSE_CODE)), end)
        }

    @Test
    fun cancellingAWaitingOpenLeavesIdsContiguousAndItsPlaceInTheQueueEmpty() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val first = opened(pair) { client.openStream() }
            opened(pair) { client.openStream() }
            val abandoned = launch { client.openStream() }
            pair.runFor(WAITING_PROOF)
            abandoned.cancel()
            pair.runFor(WAITING_PROOF)

            var later: QuicByteStream? = null
            launch { later = client.openStream() }
            pair.runFor(WAITING_PROOF)
            release(pair, server, first)
            pair.runUntil { later != null }
            assertEquals(8L, checkNotNull(later).streamId.id, "a cancelled open took no id and kept no place in the queue")
        }

    @Test
    fun severalWaitingOpensCompleteInTheOrderTheyWereCalledOnePerCredit() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            val held = ArrayDeque(listOf(opened(pair) { client.openStream() }, opened(pair) { client.openStream() }))
            val waiters = arrayOfNulls<QuicByteStream>(3)
            // Undispatched: each open is queued with the connection before the next starts, so call order is index order.
            val starts = List(3) { n -> launch(start = CoroutineStart.UNDISPATCHED) { waiters[n] = client.openStream() } }
            pair.runFor(WAITING_PROOF)
            assertTrue(waiters.all { it == null }, "no waiter completes before the peer grants credit")

            for (n in 0 until 3) {
                release(pair, server, held.removeFirst())
                pair.runUntil { waiters[n] != null }
                assertEquals(8L + 4L * n, checkNotNull(waiters[n]).streamId.id, "waiter $n was served in call order")
                assertTrue(waiters.drop(n + 1).all { it == null }, "one credit serves one waiter; those behind $n are still waiting")
                held.addLast(checkNotNull(waiters[n]))
            }
            starts.forEach(Job::cancel)
        }

    @Test
    fun aBidirectionalOpenWaitingAtItsLimitDoesNotHoldUpAUnidirectionalOne() =
        VirtualPair.with(options()) { pair ->
            pair.accept()
            val client = pair.client.connection
            opened(pair) { client.openStream() }
            opened(pair) { client.openStream() }
            var waitingBidi: QuicByteStream? = null
            val waiting = launch { waitingBidi = client.openStream() }
            pair.runFor(WAITING_PROOF)
            assertNull(waitingBidi, "the third bidirectional stream waits for the peer's bidirectional credit")

            val uni = opened(pair) { client.openUniStream() }
            assertEquals(2L, uni.streamId.id, "unidirectional credit is separate: the open did not wait behind a bidirectional one")
            assertNull(waitingBidi)
            waiting.cancel()
        }

    @Test
    fun aStreamMadeForACallerWhoLeftIsGivenBackAndTheNextOpenWorks() =
        VirtualPair.with(options()) { pair ->
            val server = pair.accept()
            val client = pair.client.connection
            // The open runs and makes its stream while nobody waits on it; the caller then arrives already cancelled.
            val open = client.opening(StreamKind.Bidirectional, coroutineContext)
            pair.runFor(WAITING_PROOF)
            val left = launch(start = CoroutineStart.ATOMIC) { open.answer() }
            left.cancel()
            pair.runUntil { left.isCompleted }
            pair.runFor(WAITING_PROOF)

            // The peer sees the stream reset at once, so a stream nobody holds is not left open at either end.
            var seen: ReadResult? = null
            launch {
                val stream = server.acceptStream()
                seen = stream.read(1.hours)
                (seen as? ReadResult.Data)?.buffer?.freeIfNeeded()
            }
            pair.runUntil { seen != null }
            assertEquals(ReadResult.Reset, seen, "the stream given back is reset with the code that carries no application meaning")

            val next = opened(pair) { client.openStream() }
            written(pair, next)
            assertTrue(!next.streamId.isUnidirectional, "the next open works and makes an ordinary bidirectional stream")
        }

    /** Finishes [stream] both ways with the peer, so the peer's quiche lets it go and returns its credit in a MAX_STREAMS. */
    private suspend fun CoroutineScope.release(
        pair: VirtualPair,
        server: CoreConnection,
        stream: QuicByteStream,
    ) {
        var peerDone = false
        var ourDone = false
        launch {
            stream.write(bytes(1), 1.hours)
            stream.shutdownSend()
        }
        launch {
            val peer = server.acceptStream()
            drainToEnd(peer)
            peer.close()
            peerDone = true
        }
        launch {
            drainToEnd(stream)
            stream.close()
            ourDone = true
        }
        pair.runUntil { peerDone && ourDone }
        pair.runFor(pair.oneWay * 4)
    }

    private suspend fun drainToEnd(stream: QuicByteStream) {
        while (true) {
            val read = stream.read(1.hours)
            if (read is ReadResult.Data) read.buffer.freeIfNeeded() else return
        }
    }

    /** Runs [open] to its answer, passing the pair's loops meanwhile. */
    private suspend fun <S : Any> CoroutineScope.opened(
        pair: VirtualPair,
        open: suspend () -> S,
    ): S {
        var made: S? = null
        launch { made = open() }
        pair.runUntil { made != null }
        return checkNotNull(made)
    }

    private suspend fun CoroutineScope.written(
        pair: VirtualPair,
        stream: QuicByteStream,
    ) {
        var done = false
        launch {
            stream.write(bytes(1), 1.hours)
            done = true
        }
        pair.runUntil { done }
    }

    private fun bytes(size: Int): PlatformBuffer =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        /** Small enough that "one too many" is exact. */
        const val LIMIT = 2L
        const val CLOSE_CODE = 0x42L
        const val MS = 1_000_000L

        /** Long enough that an open that was going to fail or complete without credit would have. */
        val WAITING_PROOF: Duration = 300.milliseconds
        val BOUND: Duration = 500.milliseconds
    }
}
