package com.ditchoom.socket.quic.core.patch

import com.ditchoom.socket.quic.DEFAULT_PATH_LATENCY
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guards for the quiche patches that fix stream FIN handling and the receive-gap counter. Each test stages one
 * wire order on a real quiche pair; it goes red when the patch it names is reverted alone.
 */
class QuichePatchStreamTests {
    private val env = jvmMigrationSimEnv()

    /**
     * quiche counts the ack-eliciting packets that arrive after a gap of at least two packet numbers
     * (`quiche_conn_recv_gaps`, patched in by `patchQuicheRecvGapCounter`): RFC 9000 §13.2.1's trigger for an
     * immediate ACK, and the only loss a pure receiver can see. A flight delivered whole counts none; the
     * same flight with one datagram lost counts none either (quiche itself skips single packet numbers, so a
     * gap of one is not evidence); with its first two datagrams lost it counts one, the datagram after the
     * gap; the lost ones arriving late fill the hole and count nothing more.
     *
     * Red against a quiche built without the `socket_recv_gaps += 1` line of the patch.
     */
    @Test
    fun aPacketThatArrivesAfterAGapIsCountedAndAnInOrderOneIsNot() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.settle()
                assertEquals(7, pair.streamSend(pair.client, stream, "request", fin = true))
                pair.settle()
                val reply = "reply;".repeat(STAGED_REPLY_BYTES / 6)

                val before = pair.recvGaps(pair.client)
                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val whole = pair.sends(pair.server)
                assertTrue(whole.size >= 4, "the reply must span at least four datagrams, was ${whole.size}")
                pair.deliver(whole, pair.client)
                assertEquals(before, pair.recvGaps(pair.client), "a flight delivered in order has no gap")
                pair.settle()

                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val flight = pair.sends(pair.server)
                assertTrue(flight.size >= 4, "the reply must span at least four datagrams, was ${flight.size}")
                pair.deliver(flight.drop(1), pair.client)
                assertEquals(before, pair.recvGaps(pair.client), "one packet number missing is not a gap quiche counts")
                pair.deliver(flight.take(1), pair.client)
                pair.settle()

                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val third = pair.sends(pair.server)
                assertTrue(third.size >= 4, "the reply must span at least four datagrams, was ${third.size}")
                pair.deliver(third.drop(2), pair.client)
                assertEquals(
                    before + 1,
                    pair.recvGaps(pair.client),
                    "the datagram after two lost ones is one gap, however many follow it",
                )
                pair.deliver(third.take(2), pair.client)
                assertEquals(before + 1, pair.recvGaps(pair.client), "the late datagrams fill the hole and are not counted again")
            } finally {
                pair.close()
            }
        }

    /**
     * A FIN written after a stream's last bytes were sent, while an earlier range of it waits to be
     * retransmitted, is still sent.
     *
     * quiche drops a stream from its send queue once nothing it holds is flushable, and counted only data:
     * the stream was already queued for the retransmission, so the FIN did not queue it again, the
     * retransmission went out without the FIN (it ends mid-stream), and the stream left the queue with the
     * FIN never sent. The peer read the whole reply and never its end; a server closing once its writes are
     * acknowledged waited for a FIN it would never send. The sim's reply-after-close scenario reached this
     * order at random (seed 12, about one run in ten); here it is staged.
     */
    @Test
    fun aFinWrittenWhileAnEarlierRangeAwaitsRetransmissionIsStillSent() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.settle()
                assertEquals(7, pair.streamSend(pair.client, stream, "request", fin = true))
                pair.settle()
                assertEquals(7 to true, pair.readAll(pair.server, stream))

                // The reply goes out in several datagrams, its last bytes included, without the FIN.
                val reply = "reply;".repeat(STAGED_REPLY_BYTES / 6)
                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val flight = pair.sends(pair.server)
                assertTrue(flight.size >= 4, "the reply must span at least four datagrams to stage a loss, was ${flight.size}")
                // The first is lost; the client acknowledges the rest, which declares it lost: its
                // range now waits to be retransmitted.
                pair.deliver(flight.drop(1), pair.client)
                pair.deliver(pair.sends(pair.client), pair.server)
                // The handler closes the stream now.
                assertEquals(0, pair.streamSend(pair.server, stream, "", fin = true))
                pair.settle()

                assertEquals(
                    reply.length to true,
                    pair.readAll(pair.client, stream),
                    "the client must read the whole reply and its end",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * A FIN that arrives on a retransmission of bytes the reader has already consumed still ends the stream.
     *
     * The client writes its request and then its FIN in two packets. The server reads the request, but its ACK is
     * lost, and so is the FIN-only packet; the client then retransmits both as one STREAM frame: offset 0, the whole
     * request, FIN set. Every byte of that frame is a duplicate and the FIN is the only new fact in it. A receiver
     * that records the final size and then discards the frame as a duplicate holds a stream that has ended but that
     * nobody can see end, and each later FIN-only copy is discarded because the final size is already known.
     *
     * Red against a quiche built without `patchQuicheDuplicateDataFinIsKept`.
     */
    @Test
    fun aFinRetransmittedWithBytesTheReaderAlreadyConsumedEndsTheStream() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.settle()
                assertEquals(REQUEST_TEXT.length, pair.streamSend(pair.client, stream, REQUEST_TEXT, fin = false))
                val request = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(request, pair.server)
                assertEquals(
                    REQUEST_TEXT to false,
                    pair.readText(pair.server, stream),
                    "the server reads the request before the FIN exists",
                )

                // The server's acknowledgement of the request is lost, and so is the FIN-only packet.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.sends(pair.server)
                assertEquals(0, pair.streamSend(pair.client, stream, "", fin = true))
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the FIN leaves the client, to be lost")

                // The client's probes carry request and FIN in one frame; nothing the server sends reaches the client.
                var ending = false
                var waited = Duration.ZERO
                while (!ending && waited < FIN_DELIVERED_WITHIN) {
                    pair.elapse(RETRY_STEP)
                    waited += RETRY_STEP
                    pair.deliver(pair.sends(pair.client), pair.server)
                    pair.sends(pair.server)
                    ending = pair.readText(pair.server, stream).second
                }
                assertTrue(ending, "the client's FIN must end the server's read within $FIN_DELIVERED_WITHIN")
            } finally {
                pair.close()
            }
        }

    /**
     * A FIN sent in a packet of its own, behind data that is then acknowledged, and then lost, is sent again.
     *
     * The shape of a handler that writes its reply and closes the stream. quiche counted a send side complete once its
     * data was acknowledged, and a zero-length FIN frame adds nothing to what is acknowledged, so the stream was
     * collected with its FIN unacknowledged: the peer read the whole reply and never its end.
     *
     * Red against a quiche built without the `socket_fin_acked` gate of `patchQuicheFinIsSentUntilAcknowledged`.
     */
    @Test
    fun aFinLostBehindAcknowledgedDataIsSentAgain() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.settle()
                assertEquals(REQUEST_TEXT.length, pair.streamSend(pair.client, stream, REQUEST_TEXT, fin = true))
                pair.settle()
                assertEquals(REQUEST_TEXT.length to true, pair.readAll(pair.server, stream))

                // The reply's data leaves, then its FIN leaves in a packet of its own, which is lost.
                val reply = "reply;"
                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val data = pair.sends(pair.server)
                assertEquals(0, pair.streamSend(pair.server, stream, "", fin = true))
                assertTrue(pair.sends(pair.server).isNotEmpty(), "the FIN leaves the server, to be lost")

                // The data arrives and is acknowledged: the server's send side now has every data byte acknowledged.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(data, pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(pair.sends(pair.client), pair.server)
                assertEquals(reply to false, pair.readText(pair.client, stream), "the client reads the data, not yet its end")

                var ending = false
                var waited = Duration.ZERO
                while (!ending && waited < FIN_DELIVERED_WITHIN) {
                    pair.elapse(RETRY_STEP)
                    waited += RETRY_STEP
                    pair.deliver(pair.sends(pair.server), pair.client)
                    pair.deliver(pair.sends(pair.client), pair.server)
                    ending = pair.readText(pair.client, stream).second
                }
                assertTrue(ending, "the server's FIN must reach the client within $FIN_DELIVERED_WITHIN of being lost")
            } finally {
                pair.close()
            }
        }

    private companion object {
        const val REQUEST_TEXT = "request"
        val RETRY_STEP = 100.milliseconds
        val FIN_DELIVERED_WITHIN = 30.seconds
    }
}

private const val STAGED_REPLY_BYTES = 6000
