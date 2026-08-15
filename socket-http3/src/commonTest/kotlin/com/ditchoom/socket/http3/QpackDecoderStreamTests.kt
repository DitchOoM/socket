package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The QPACK acknowledgment accounting rule, asserted directly.
 *
 * #353's regression test had to reach this rule *through* a live encoder/decoder pair and inject a
 * yield count to force the losing interleaving, because the accounting lived inside [QpackDecoder]
 * behind a lock and could only be observed by racing it. With the accounting owned by
 * [QpackDecoderStream], the rule is a property of one sequential object and the tests below are just
 * calls in an order — no scheduling injection, no encoder, no decoder. The integration test stays as
 * well: it proves the two ends agree, which this cannot.
 */
class QpackDecoderStreamTests {
    private val streamId = QuicStreamId(0)

    @Test
    fun anAcknowledgmentImplicitlyCoversItsInsertionsSoNoIncrementFollows() =
        runTest {
            val stream = RecordingQpackDecoderStream()

            // The section required all three insertions, so acknowledging it tells the peer's encoder
            // its Known Received Count is 3 — reporting those same three afterwards would double-count,
            // which is the QPACK_DECODER_STREAM_ERROR the peer is required to kill the connection over.
            stream.acknowledgeSection(streamId, InsertCount(3))
            stream.reportInsertsUpTo(InsertCount(3))

            assertEquals(listOf<QpackDecoderInstruction>(QpackDecoderInstruction.SectionAck(streamId)), stream.written)
        }

    @Test
    fun anIncrementCoversOnlyWhatNoAcknowledgmentAlreadyDid() =
        runTest {
            val stream = RecordingQpackDecoderStream()

            stream.reportInsertsUpTo(InsertCount(2)) // nothing acked yet → the full 2
            stream.acknowledgeSection(streamId, InsertCount(5)) // jumps the count to 5
            stream.reportInsertsUpTo(InsertCount(5)) // already covered → silent
            stream.reportInsertsUpTo(InsertCount(7)) // only the 2 beyond the ack

            assertEquals(
                listOf(
                    QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(2)),
                    QpackDecoderInstruction.SectionAck(streamId),
                    QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(2)),
                ),
                stream.written,
            )
        }

    @Test
    fun anAcknowledgmentForAnEarlierSectionNeverWindsTheCountBack() =
        runTest {
            val stream = RecordingQpackDecoderStream()

            stream.acknowledgeSection(streamId, InsertCount(5))
            // Sections are acknowledged in decode order, not Required-Insert-Count order, so a later
            // ack can carry a SMALLER count. Taking it would re-report insertions the peer already
            // knows about on the next increment.
            stream.acknowledgeSection(QuicStreamId(4), InsertCount(2))
            stream.reportInsertsUpTo(InsertCount(5))

            assertEquals(
                listOf<QpackDecoderInstruction>(
                    QpackDecoderInstruction.SectionAck(streamId),
                    QpackDecoderInstruction.SectionAck(QuicStreamId(4)),
                ),
                stream.written,
            )
        }

    @Test
    fun aFailedWriteLeavesTheCountWhereItWas() =
        runTest {
            var failNext = true
            val written = mutableListOf<QpackDecoderInstruction>()
            val stream =
                object : QpackDecoderStream() {
                    override suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite {
                        if (failNext) {
                            failNext = false
                            throw IllegalStateException("stream write failed")
                        }
                        written += instruction
                        return DecoderStreamWrite.Sent
                    }
                }

            assertFailsWith<IllegalStateException> { stream.reportInsertsUpTo(InsertCount(3)) }
            // The failed write never reached the peer, so its encoder still knows about nothing. If the
            // count had advanced anyway, this retry would emit a smaller increment — or none — and the
            // peer's Known Received Count would sit permanently behind ours.
            stream.reportInsertsUpTo(InsertCount(3))

            assertEquals(listOf<QpackDecoderInstruction>(QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(3))), written)
        }

    @Test
    fun aReportBehindTheAcknowledgedCountWritesNothingRatherThanThrowing() =
        runTest {
            val stream = RecordingQpackDecoderStream()
            stream.acknowledgeSection(streamId, InsertCount(7))

            // The decoder captures the count it reports under the table lock and reports it under this
            // one, so an acknowledgment on another coroutine can move the acknowledged count past it in
            // between — the peer's traffic decides which is larger. `upTo - acknowledged` would throw
            // IllegalArgumentException here, out of applyEncoderInstruction, past two catch clauses that
            // only handle typed HTTP/3 and QUIC stream errors, and into an uncaught coroutine.
            stream.reportInsertsUpTo(InsertCount(5))

            assertEquals(listOf<QpackDecoderInstruction>(QpackDecoderInstruction.SectionAck(streamId)), stream.written)
        }

    @Test
    fun aWriteThatReportsItSentNothingLeavesTheCountWhereItWas() =
        runTest {
            // The failure mode a `Unit` return could not express: a write that neither wrote nor threw.
            // Both implementations have one — the client swallows a QuicCloseException, the server
            // returns early before its stream is open. Advancing the count for either leaves the peer's
            // Known Received Count permanently short, with no error raised anywhere to say so.
            val written = mutableListOf<QpackDecoderInstruction>()
            var sendNothing = true
            val stream =
                object : QpackDecoderStream() {
                    override suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite {
                        if (sendNothing) {
                            sendNothing = false
                            return DecoderStreamWrite.NotSent(DecoderStreamWrite.NotSentReason.StreamNotOpen)
                        }
                        written += instruction
                        return DecoderStreamWrite.Sent
                    }
                }

            stream.reportInsertsUpTo(InsertCount(3))
            stream.reportInsertsUpTo(InsertCount(3))

            assertEquals(listOf<QpackDecoderInstruction>(QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(3))), written)
        }

    @Test
    fun anAcknowledgmentThatWasNotSentDoesNotCountAsCoveringItsInsertions() =
        runTest {
            val written = mutableListOf<QpackDecoderInstruction>()
            var sendNothing = true
            val stream =
                object : QpackDecoderStream() {
                    override suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite {
                        if (sendNothing) {
                            sendNothing = false
                            return DecoderStreamWrite.NotSent(DecoderStreamWrite.NotSentReason.StreamNotOpen)
                        }
                        written += instruction
                        return DecoderStreamWrite.Sent
                    }
                }

            stream.acknowledgeSection(streamId, InsertCount(4))
            // The acknowledgment never left, so its implicit coverage never happened: the increment that
            // follows must still carry all four insertions.
            stream.reportInsertsUpTo(InsertCount(4))

            assertEquals(listOf<QpackDecoderInstruction>(QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(4))), written)
        }

    @Test
    fun aWriteThatReEntersTheStreamFailsInsteadOfDeadlocking() =
        runTest {
            // The lock is held across `write`, which a subclass supplies, so a re-entrant one would wait
            // on itself forever. Naming the coroutine as the mutex owner turns that into an immediate
            // IllegalStateException. Nothing re-enters today; this is what keeps that true loudly.
            lateinit var stream: QpackDecoderStream
            stream =
                object : QpackDecoderStream() {
                    override suspend fun write(instruction: QpackDecoderInstruction): DecoderStreamWrite {
                        stream.cancelStream(streamId)
                        return DecoderStreamWrite.Sent
                    }
                }

            assertFailsWith<IllegalStateException> { stream.reportInsertsUpTo(InsertCount(1)) }
        }

    @Test
    fun aStreamCancellationCarriesNoCountAndDisturbsNone() =
        runTest {
            val stream = RecordingQpackDecoderStream()

            stream.reportInsertsUpTo(InsertCount(4))
            stream.cancelStream(streamId)
            stream.reportInsertsUpTo(InsertCount(4))

            assertEquals(
                listOf(
                    QpackDecoderInstruction.InsertCountIncrement(InsertCountDelta(4)),
                    QpackDecoderInstruction.StreamCancellation(streamId),
                ),
                stream.written,
            )
        }
}
