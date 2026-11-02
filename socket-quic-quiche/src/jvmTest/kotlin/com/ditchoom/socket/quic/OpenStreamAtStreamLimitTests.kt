package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TimeoutContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `openStream()` / `openUniStream()` at the peer's stream limit.
 *
 * ## The contract
 *
 * A peer at its `initial_max_streams` (or its latest MAX_STREAMS) is pacing how many streams this side
 * may have open, not failing. So an open past the limit **waits** for the peer's MAX_STREAMS under
 * [QuicOptions.streamOpenPolicy] — [StreamOpenPolicy.UntilClosed] by default, matching how a stalled
 * write is treated — and the waiting is event-driven: the driver serves waiting opens on the turn of its
 * loop that received the credit. A [StreamOpenPolicy.Bounded] wait that runs out fails with the typed
 * [SocketTimeoutException] / [TimeoutContext.StreamOpen]; a connection that closes fails the wait with its
 * own close reason.
 *
 * Stream ids stay contiguous throughout: an id is assigned only when an open succeeds, so neither a
 * timed-out nor a cancelled open leaves a hole.
 *
 * ## History (#423)
 *
 * #423 made `openStream()` materialise the stream with quiche instead of only reserving an id, and the
 * materialising send is the call quiche refuses at the limit. Its first version discarded that refusal
 * and handed back a slot quiche had never created, whose next read answered `INVALID_STREAM_STATE`. That
 * was then reported as a thrown `QuicStreamOpenException`, which every caller with more requests than the
 * peer allows streams had to poll around (the interop endpoint did, every 5 ms). Now the refusal is the
 * driver's cue to wait, and nothing outside it sees a stream quiche refused.
 *
 * ## Why the sim rather than a loopback server
 *
 * `initial_max_streams` is a transport parameter, so reaching it needs a peer configured to advertise a
 * small one. The semantic sim drives real quiche on both ends with a configurable [QuicOptions], which
 * makes "the third stream is one too many" exact, and the credit returns through quiche's own
 * MAX_STREAMS when the peer finishes a stream.
 */
class OpenStreamAtStreamLimitTests {
    @Test
    fun openPastThePeerLimitWaitsAndCompletesWhenThePeerReleasesAStream() =
        withLimitedPeer {
            val first = client.openStream()
            client.openStream()

            val third = async { client.openStream() }
            delay(WAITING_PROOF)
            assertFalse(third.isCompleted, "an open past the peer's limit must wait for MAX_STREAMS, not fail or proceed")

            finishOnBothSides(first)

            val opened = withTimeout(CREDIT_BOUND) { third.await() }
            assertEquals(8L, opened.streamId.id, "the waiting open must get the next contiguous client bidi id")
        }

    @Test
    fun boundedOpenTimesOutWithTheTypedTimeoutAndConsumesNoId() =
        withLimitedPeer(StreamOpenPolicy.Bounded(BOUNDED_WAIT)) {
            val first = client.openStream()
            client.openStream()

            val failure = assertFailsWith<SocketTimeoutException> { client.openStream() }
            assertEquals(TimeoutContext.StreamOpen(BOUNDED_WAIT), failure.context)

            // The connection is still usable, and the timed-out open burned no id.
            finishOnBothSides(first)
            val next = withTimeout(CREDIT_BOUND) { client.openStream() }
            assertEquals(8L, next.streamId.id, "a timed-out open must not consume a stream id")
        }

    @Test
    fun connectionClosingWhileAnOpenWaitsFailsItWithTheCloseReason() =
        withLimitedPeer {
            client.openStream()
            client.openStream()

            val waiting = async { runCatching { client.openStream() } }
            delay(WAITING_PROOF)
            assertFalse(waiting.isCompleted)

            server.close(QuicError.ApplicationError(APP_CLOSE_CODE))

            val outcome = withTimeout(CREDIT_BOUND) { waiting.await() }
            val failure = assertIs<QuicCloseException>(outcome.exceptionOrNull(), "a waiting open must fail with the close, got $outcome")
            assertEquals(QuicCloseReason.ByPeer(QuicError.ApplicationError(APP_CLOSE_CODE)), failure.closeReason)
        }

    @Test
    fun cancellingAWaitingOpenLeavesIdsContiguous() =
        withLimitedPeer {
            val first = client.openStream()
            client.openStream()

            val abandoned = launch { client.openStream() }
            delay(WAITING_PROOF)
            abandoned.cancelAndJoin()

            val later = async { client.openStream() }
            delay(WAITING_PROOF)
            finishOnBothSides(first)

            val opened = withTimeout(CREDIT_BOUND) { later.await() }
            assertEquals(8L, opened.streamId.id, "a cancelled open must neither take an id nor keep its place in the queue")
        }

    /**
     * The other half of the cancellation race: the driver had already handed the open its stream when
     * the caller gave up. Driven at the command level because the instant is not reproducible through
     * `openStream()` — this is exactly what `openStreamSlot` sends when its cancel arrives too late.
     */
    @Test
    fun aStreamHandedToAnOpenWhoseCallerLeftGoesToTheNextOpen() =
        withLimitedPeer {
            val open = QuicheCmd.OpenStream(CompletableDeferred())
            clientDriver.commands.send(open)
            val handed = withTimeout(CREDIT_BOUND) { open.result.await() }
            clientDriver.commands.send(QuicheCmd.AbandonOpen(open))

            val next = withTimeout(CREDIT_BOUND) { client.openStream() }
            assertEquals(handed.id, next.streamId, "a stream nobody received must go to the next open, not be burned")
            val after = withTimeout(CREDIT_BOUND) { client.openStream() }
            assertEquals(4L, after.streamId.id, "and the ids after it carry on contiguously")
        }

    @Test
    fun severalWaitingOpensCompleteInTheOrderTheyWereCalled() =
        withLimitedPeer {
            val held = ArrayDeque(listOf(client.openStream(), client.openStream()))

            // UNDISPATCHED: each open is queued with the driver before the next one is started, so the
            // call order is exactly the list order.
            val waiters: List<Deferred<QuicByteStream>> =
                List(3) { async(start = CoroutineStart.UNDISPATCHED) { client.openStream() } }
            delay(WAITING_PROOF)
            assertFalse(waiters.any { it.isCompleted }, "no waiter may complete before the peer grants credit")

            for ((index, waiter) in waiters.withIndex()) {
                finishOnBothSides(held.removeFirst())
                val opened = withTimeout(CREDIT_BOUND) { waiter.await() }
                assertEquals(8L + 4L * index, opened.streamId.id, "waiter $index must be served in call order")
                // Only one credit per release: the waiters behind it are still waiting.
                assertFalse(waiters.drop(index + 1).any { it.isCompleted }, "waiters behind $index jumped the queue")
                held.addLast(opened)
            }
        }

    @Test
    fun aUniOpenWaitingAtItsLimitDoesNotHoldUpABidiOpen() =
        withLimitedPeer(uniLimit = 1L) {
            val uni = client.openUniStream()
            val waitingUni = async { client.openUniStream() }
            delay(WAITING_PROOF)
            assertFalse(waitingUni.isCompleted, "the second uni stream must wait for the peer's uni credit")

            val bidi = withTimeout(CREDIT_BOUND) { client.openStream() }
            assertEquals(0L, bidi.streamId.id, "bidi credit is separate: a bidi open must not wait behind a uni one")

            // A uni stream is complete for the peer once it has read our FIN.
            uni.shutdownSend()
            val peerSide = server.acceptStream()
            drainToEnd(peerSide)
            peerSide.close()

            val opened = withTimeout(CREDIT_BOUND) { waitingUni.await() }
            assertEquals(6L, opened.streamId.id, "the next client uni id after 2")
        }

    /**
     * Finish [stream] on both sides, so the peer's quiche collects it and returns its credit in a
     * MAX_STREAMS: our FIN, the peer reads to it and FINs back, we read to the peer's FIN.
     */
    private suspend fun LimitedPeer.finishOnBothSides(stream: QuicByteStream) =
        coroutineScope {
            val peer =
                async {
                    val peerSide = server.acceptStream()
                    drainToEnd(peerSide)
                    peerSide.close()
                }
            stream.shutdownSend()
            withTimeout(CREDIT_BOUND) { peer.await() }
            drainToEnd(stream)
            stream.close()
        }

    private suspend fun drainToEnd(stream: QuicByteStream) {
        while (true) {
            val result = stream.read(CREDIT_BOUND)
            if (result is ReadResult.Data) result.buffer.freeIfNeeded() else break
        }
    }

    private fun withLimitedPeer(
        policy: StreamOpenPolicy = StreamOpenPolicy.UntilClosed,
        uniLimit: Long = STREAM_LIMIT,
        block: suspend LimitedPeer.() -> Unit,
    ) = runBlocking {
        skipOnMissingNativeLib(OpenStreamAtStreamLimitTests::class) {
            val options =
                semanticSimOptions(idleTimeout = 10.seconds).let {
                    it.copy(
                        flowControl = it.flowControl.copy(initialMaxStreamsBidi = STREAM_LIMIT, initialMaxStreamsUni = uniLimit),
                        streamOpenPolicy = policy,
                    )
                }
            withSemanticSim(ImpairmentConfig(seed = 423L), quicOptions = options) {
                val sim = this
                coroutineScope { LimitedPeer(sim, this).block() }
            }
        }
    }

    /** The sim's two ends, plus the test's own scope for the opens it starts and abandons. */
    private class LimitedPeer(
        sim: SemanticSimScope,
        scope: CoroutineScope,
    ) : CoroutineScope by scope {
        val client = sim.client
        val server = sim.server
        val clientDriver = sim.clientDriver
    }

    private companion object {
        /** Small enough that "one too many" is exact; large enough that the happy path is exercised. */
        const val STREAM_LIMIT = 2L

        const val APP_CLOSE_CODE = 0x42L

        /** Long enough that an open which was going to fail or complete without credit would have. */
        val WAITING_PROOF = 300.milliseconds

        val BOUNDED_WAIT = 250.milliseconds

        /** A ceiling on a lossless in-process exchange, not an expected duration. */
        val CREDIT_BOUND = 10.seconds
    }
}
