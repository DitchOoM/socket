package com.ditchoom.socket.quic.core.patch

import com.ditchoom.socket.quic.DEFAULT_PATH_LATENCY
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression guards for the quiche patches that fix connection id retirement. Each test stages one wire order on a real
 * quiche pair; it goes red when the patch it names is reverted alone.
 */
class QuichePatchCidRetirementTests {
    private val env = jvmMigrationSimEnv()

    /**
     * A client keeps sending after the peer retires connection IDs whose NEW_CONNECTION_ID frames the
     * client has queued to send again.
     *
     * The quic-interop-runner's `handshakeloss` against quinn (run 41385368697): the packet that carried
     * our NEW_CONNECTION_ID frames reached quinn, but its acknowledgement did not reach us. One packet from
     * quinn then brought an ACK that declared ours lost and RETIRE_CONNECTION_ID frames for those ids.
     * quiche removed the ids, and when it next built a packet it queued the lost frames' sequence numbers
     * to advertise again without asking whether the ids still existed, looked the first one up, found
     * nothing, and failed: `quiche_conn_send` answered `InvalidState` on every call from then on. The
     * client sent nothing at all, not even an ACK, while it went on decrypting quinn's packets, until its
     * idle timeout 30 s later.
     *
     * Staged here with a real one-way delay between every leg: the frames' packet is held back, three
     * later packets are acknowledged (packet threshold, RFC 9002 §6.1.1), the held packet then arrives,
     * and the server's retirement of what it carried reaches the client before the client sends again.
     */
    @Test
    fun theClientKeepsSendingAfterThePeerRetiresIdsItHadQueuedToAdvertiseAgain() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                val issued = pair.scidsLeft(pair.client)
                assertTrue(
                    issued >= RETIRED_BY_THE_PEER,
                    "the client must be able to issue $RETIRED_BY_THE_PEER ids, could issue $issued",
                )
                pair.issueSpareCids(pair.client)

                // The NEW_CONNECTION_ID frames leave; their packet is held back.
                val advertised = pair.sends(pair.client)
                assertEquals(1, advertised.size, "the client's ids must leave in one datagram")
                // Three later packets arrive and are acknowledged, which declares the held one lost: the
                // client queues its NEW_CONNECTION_ID frames again.
                val later =
                    (0 until PACKET_THRESHOLD).flatMap { i ->
                        assertEquals(2, pair.streamSend(pair.client, stream, "p$i", fin = false))
                        pair.sends(pair.client)
                    }
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(later, pair.server)
                pair.elapse(SERVER_ACK_DELAY)
                val acks = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(acks, pair.client)

                // The held packet arrives after all; the server retires ids it carried.
                pair.deliver(advertised, pair.server)
                (1L..RETIRED_BY_THE_PEER).forEach { seq ->
                    assertEquals(0, pair.retireDcid(seq, pair.server), "the server must retire the client's id $seq")
                }
                val retirement = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(retirement, pair.client)

                // The client writes; it must still put packets on the wire, and the bytes must arrive.
                assertEquals(5, pair.streamSend(pair.client, stream, "after", fin = true))
                val afterRetirement = pair.sends(pair.client)
                assertTrue(
                    afterRetirement.isNotEmpty(),
                    "the client must still send after the peer retired its ids; quiche_conn_send answered " +
                        "${pair.lastSendEnd(
                            pair.client,
                        )} (QUICHE_ERR_DONE is ${QUICHE_ERR_DONE}), client ${pair.closed(pair.client)}",
                )
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(afterRetirement, pair.server)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                assertEquals("open", pair.closed(pair.client), "the client's connection must stay open")
                assertEquals(
                    QUICHE_ERR_DONE,
                    pair.lastSendEnd(pair.client),
                    "every client send must end on DONE, not a refusal",
                )
                assertEquals(
                    "p0p1p2after" to true,
                    pair.readText(pair.server, stream),
                    "the server must read everything the client wrote, and its end",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * The other order of [theClientKeepsSendingAfterThePeerRetiresIdsItHadQueuedToAdvertiseAgain], as the
     * `handshakecorruption` trace has it: the peer retires the ids first, and only afterwards does the
     * client give up on the packet that advertised them and schedule its frames again — here on a probe
     * timeout (RFC 9002 §6.2), which re-sends the frames of a packet that carried stream data. Nothing may
     * schedule a NEW_CONNECTION_ID for an id that is already retired.
     */
    @Test
    fun theClientKeepsSendingWhenAPacketAdvertisingIdsThePeerAlreadyRetiredIsSentAgain() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                val issued = pair.scidsLeft(pair.client)
                assertTrue(
                    issued >= RETIRED_BY_THE_PEER,
                    "the client must be able to issue $RETIRED_BY_THE_PEER ids, could issue $issued",
                )
                pair.issueSpareCids(pair.client)

                // The ids leave beside stream data, so a probe timeout sends the packet's frames again.
                assertEquals(2, pair.streamSend(pair.client, stream, "p0", fin = false))
                val advertised = pair.sends(pair.client)
                assertEquals(1, advertised.size, "the client's ids and its data must leave in one datagram")
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(advertised, pair.server)
                // The server's acknowledgement is lost; it retires ids the packet carried, and that reaches
                // the client.
                pair.elapse(SERVER_ACK_DELAY)
                pair.sends(pair.server)
                (1L..RETIRED_BY_THE_PEER).forEach { seq ->
                    assertEquals(0, pair.retireDcid(seq, pair.server), "the server must retire the client's id $seq")
                }
                val retirement = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(retirement, pair.client)

                // No acknowledgement ever came: the client's probe timeout fires and it sends again.
                pair.elapse(PROBE_TIMEOUT_BOUND)
                val afterRetirement = pair.sends(pair.client)
                assertTrue(
                    afterRetirement.isNotEmpty(),
                    "the client must still send after the peer retired its ids; quiche_conn_send answered " +
                        "${pair.lastSendEnd(
                            pair.client,
                        )} (QUICHE_ERR_DONE is ${QUICHE_ERR_DONE}), client ${pair.closed(pair.client)}",
                )
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(afterRetirement, pair.server)
                assertEquals(2, pair.streamSend(pair.client, stream, "p1", fin = true))
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                assertEquals("open", pair.closed(pair.client), "the client's connection must stay open")
                assertEquals(
                    QUICHE_ERR_DONE,
                    pair.lastSendEnd(pair.client),
                    "every client send must end on DONE, not a refusal",
                )
                assertEquals(
                    "p0p1" to true,
                    pair.readText(pair.server, stream),
                    "the server must read everything the client wrote, and its end",
                )
            } finally {
                pair.close()
            }
        }

    /**
     * The window between those two: quiche has already queued the lost NEW_CONNECTION_ID frames again, but
     * the congestion window — just cut by the loss, with a full flight still out — leaves no room to send
     * them, and the peer's retirement arrives while they wait. Under `handshakeloss` the window is exactly
     * this state. The retirement itself must take the ids out of the queue.
     */
    @Test
    fun theClientKeepsSendingWhenThePeerRetiresIdsQueuedBehindAFullCongestionWindow() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                val issued = pair.scidsLeft(pair.client)
                assertTrue(
                    issued >= RETIRED_BY_THE_PEER,
                    "the client must be able to issue $RETIRED_BY_THE_PEER ids, could issue $issued",
                )
                pair.issueSpareCids(pair.client)

                // The ids leave and are held back; three small packets follow; then a write fills the
                // congestion window and that whole flight is held too.
                val advertised = pair.sends(pair.client)
                assertEquals(1, advertised.size, "the client's ids must leave in one datagram")
                val later =
                    (0 until PACKET_THRESHOLD).flatMap { i ->
                        assertEquals(2, pair.streamSend(pair.client, stream, "p$i", fin = false))
                        pair.sends(pair.client)
                    }
                val bulk = "b".repeat(CONGESTION_WINDOW_FILL_BYTES)
                val accepted = pair.streamSend(pair.client, stream, bulk, fin = false)
                assertTrue(accepted > 0, "the stream must take the bulk write")
                val flight = pair.sends(pair.client)
                assertTrue(accepted > flight.sumOf { it.bytes.size }, "the bulk write must outrun the congestion window")

                // The three small packets are acknowledged: the ids' packet is declared lost and the
                // window cut, with the bulk flight still in it. The client queues the ids again, and
                // has no room to send them.
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(later, pair.server)
                pair.elapse(SERVER_ACK_DELAY)
                val acks = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(acks, pair.client)
                pair.sends(pair.client)

                // The held packet arrives after all; the server retires ids it carried.
                pair.deliver(advertised, pair.server)
                (1L..RETIRED_BY_THE_PEER).forEach { seq ->
                    assertEquals(0, pair.retireDcid(seq, pair.server), "the server must retire the client's id $seq")
                }
                val retirement = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(retirement, pair.client)

                // The bulk flight is released and acknowledged, opening the window.
                pair.deliver(flight, pair.server)
                pair.elapse(SERVER_ACK_DELAY)
                val flightAcks = pair.sends(pair.server)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(flightAcks, pair.client)
                val afterRetirement = pair.sends(pair.client)
                assertTrue(
                    afterRetirement.isNotEmpty(),
                    "the client must still send after the peer retired its ids; quiche_conn_send answered " +
                        "${pair.lastSendEnd(
                            pair.client,
                        )} (QUICHE_ERR_DONE is ${QUICHE_ERR_DONE}), client ${pair.closed(pair.client)}",
                )
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(afterRetirement, pair.server)
                assertEquals(0, pair.streamSend(pair.client, stream, "", fin = true))
                val expected = "p0p1p2" + bulk.take(accepted)
                var received = pair.readText(pair.server, stream).first
                repeat(DELIVERY_ROUNDS) {
                    pair.exchange(DEFAULT_PATH_LATENCY) { true }
                    received += pair.readText(pair.server, stream).first
                }
                assertEquals("open", pair.closed(pair.client), "the client's connection must stay open")
                assertEquals(
                    QUICHE_ERR_DONE,
                    pair.lastSendEnd(pair.client),
                    "every client send must end on DONE, not a refusal",
                )
                assertEquals(expected.length, received.length, "the server must read everything the client wrote")
            } finally {
                pair.close()
            }
        }

    /**
     * A RETIRE_CONNECTION_ID for an id the client has already retired is ignored, even when the client has
     * a single id left — the connection stays open and keeps sending.
     *
     * The quic-interop-runner's `handshakecorruption` and `handshakeloss` against quinn (run 41433270213):
     * quinn retired our ids 1, 2, 3 and 0, leaving one, and in the same packet retired 1 again. quiche's
     * id set refused any removal once a single id was left — before looking whether the id was even there —
     * so the repeat failed with OutOfIdentifiers and the client closed the connection with
     * PROTOCOL_VIOLATION. RFC 9000 allows the repeat: a RETIRE_CONNECTION_ID is retransmitted like any
     * other frame, and its copies can arrive after the first one took effect.
     *
     * Staged with the retransmission quinn's repeat stands in for, and a real one-way delay between legs:
     * the server retires every client id but the newest, the packet that carries that is held back, three
     * later packets are acknowledged so the server declares it lost and sends the retirements again, and
     * the held packet then arrives after its retransmission.
     */
    @Test
    fun aRepeatedRetirementOfAnIdTheClientAlreadyRetiredLeavesItOpen() =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val stream = QuicStreamId(0)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                val issued = pair.scidsLeft(pair.client)
                assertTrue(
                    issued >= RETIRED_BY_THE_PEER,
                    "the client must be able to issue $RETIRED_BY_THE_PEER ids, could issue $issued",
                )
                pair.issueSpareCids(pair.client)
                assertEquals(2, pair.streamSend(pair.client, stream, "p0", fin = false))
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                assertEquals("p0" to false, pair.readText(pair.server, stream))

                // The server retires every client id but the newest — the spares first, then the one in
                // use, as quinn did (1, 2, 3, 0) — and the packet carrying that is held back.
                val newest = issued
                (1L until newest).forEach { seq ->
                    assertEquals(0, pair.retireDcid(seq, pair.server), "the server must retire the client's id $seq")
                }
                assertEquals(0, pair.retireDcid(INITIAL_DCID_SEQ, pair.server), "the server must retire the client's id 0")
                val retirement = pair.sends(pair.server)
                assertEquals(1, retirement.size, "the retirements must leave in one datagram")

                // Three later packets reach the client and are acknowledged: the server declares the
                // held one lost and sends its retirements again, which reach the client.
                val later =
                    (0 until PACKET_THRESHOLD).flatMap { i ->
                        assertEquals(2, pair.streamSend(pair.server, stream, "s$i", fin = false))
                        pair.sends(pair.server)
                    }
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(later, pair.client)
                pair.elapse(SERVER_ACK_DELAY)
                val acks = pair.sends(pair.client)
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(acks, pair.server)
                val retransmission = pair.sends(pair.server)
                assertTrue(retransmission.isNotEmpty(), "the server must send its retirements again")
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(retransmission, pair.client)
                assertEquals("open", pair.closed(pair.client), "the retransmitted retirements alone must not end the connection")
                assertEquals(issued, pair.scidsLeft(pair.client), "only the newest client id may remain")

                // The original arrives after its retransmission: every id it retires is retired already.
                pair.deliver(retirement, pair.client)
                assertEquals("open", pair.closed(pair.client), "a repeated retirement must not end the connection")

                // The connection carries on: the client issues replacement ids and data flows both ways.
                pair.issueSpareCids(pair.client)
                assertEquals(5, pair.streamSend(pair.client, stream, "after", fin = true))
                val afterRetirement = pair.sends(pair.client)
                assertTrue(
                    afterRetirement.isNotEmpty(),
                    "the client must still send; quiche_conn_send answered ${pair.lastSendEnd(pair.client)}, " +
                        "client ${pair.closed(pair.client)}",
                )
                pair.elapse(DEFAULT_PATH_LATENCY)
                pair.deliver(afterRetirement, pair.server)
                pair.exchange(DEFAULT_PATH_LATENCY) { true }
                assertEquals("open", pair.closed(pair.client), "the client's connection must stay open")
                assertEquals("open", pair.closed(pair.server), "the server's connection must stay open")
                assertEquals("after" to true, pair.readText(pair.server, stream), "the server must read the client's write and its end")
                assertEquals("s0s1s2" to false, pair.readText(pair.client, stream), "the client must read the server's writes")
            } finally {
                pair.close()
            }
        }

    /**
     * A peer keeps sending on the path a client leaves until it sees the client's packets on the new one, so its
     * packets sent before it saw the move land after the client has switched. The client must still read them: a
     * peer whose loss recovery cannot declare a left path's packets lost (stock quiche, as in Netty's QUIC server)
     * never resends them, and the stream it was echoing stalls for good.
     *
     * Red against a quiche built without `patchQuicheRetiredScidStillReceivedOnAHeldPath`.
     */
    @Test
    fun packetsThePeerSentOnTheLeftPathBeforeItSawTheMoveAreStillReceived() = leftPathPacketsAreReceived(RetirementOvertakes.No)

    /**
     * As above, with the left path slower than the new one: the peer's RETIRE_CONNECTION_ID for the id its left-path
     * packets carry overtakes them on the faster new path, so they arrive bearing an id the client has dropped. They
     * are still the peer's packets on a path quiche holds, and are received.
     *
     * Red against a quiche built without `patchQuicheRetiredScidStillReceivedOnAHeldPath`.
     */
    @Test
    fun packetsOnASlowerLeftPathAreReceivedAfterThePeerRetiresTheirConnectionId() = leftPathPacketsAreReceived(RetirementOvertakes.Yes)

    /** Whether the peer's RETIRE_CONNECTION_ID reaches the client before the packets the peer sent on the left path. */
    private enum class RetirementOvertakes { No, Yes }

    private fun leftPathPacketsAreReceived(overtakes: RetirementOvertakes) =
        runTest {
            val pair = QuichePairByHand.open(env)
            try {
                val leftPath = 0
                val newPath = 1
                val stream = QuicStreamId(0)

                fun on(endpoint: Int): (QuichePairByHand.Datagram) -> Boolean = { it.clientEndpoint == endpoint }
                pair.exchange(DEFAULT_PATH_LATENCY, on(leftPath))
                pair.issueSpareCids(pair.server)
                pair.issueSpareCids(pair.client)
                pair.exchange(DEFAULT_PATH_LATENCY, on(leftPath))
                assertEquals(REQUEST_TEXT.length, pair.streamSend(pair.client, stream, REQUEST_TEXT, fin = false))
                pair.exchange(DEFAULT_PATH_LATENCY, on(leftPath))
                assertEquals(REQUEST_TEXT to false, pair.readText(pair.server, stream))

                // The peer's reply leaves on the left path and is still in flight when the client moves.
                val reply = "reply;".repeat(REPLY_REPEATS)
                assertEquals(reply.length, pair.streamSend(pair.server, stream, reply, fin = false))
                val onTheLeftPath = pair.sends(pair.server)
                assertTrue(
                    onTheLeftPath.isNotEmpty() && onTheLeftPath.all { it.clientEndpoint == leftPath },
                    "the reply leaves on the left path",
                )

                assertIs<ProbeOutcome.Probed>(pair.probe(newPath))
                pair.exchange(DEFAULT_PATH_LATENCY, on(newPath))
                assertIs<MigrateOutcome.Migrated>(pair.migrate(newPath), "the client moves onto the validated path")
                assertEquals(0, pair.retireDcid(INITIAL_DCID_SEQ), "the client retires the left path's connection id")
                when (overtakes) {
                    RetirementOvertakes.No -> Unit
                    RetirementOvertakes.Yes ->
                        repeat(MOVE_ROUNDS) {
                            assertEquals(1, pair.streamSend(pair.client, QuicStreamId(4), "x", fin = false))
                            pair.exchange(DEFAULT_PATH_LATENCY, on(newPath))
                        }
                }

                // The left path's packets arrive now. The peer's own retransmissions on the new path would read the
                // reply anyway, so what is counted is the packets the client's quiche received, not the stream.
                val receivedBefore = env.api.connStats(pair.client)!!.recv
                pair.deliver(onTheLeftPath, pair.client)
                val received = env.api.connStats(pair.client)!!.recv - receivedBefore
                assertEquals(
                    onTheLeftPath.size.toLong(),
                    received,
                    "datagrams delivered to the client against packets its quiche received: the difference is what it " +
                        "discarded; client ${pair.closed(pair.client)}",
                )
            } finally {
                pair.close()
            }
        }

    private companion object {
        const val REQUEST_TEXT = "request"
        const val REPLY_REPEATS = 40
        const val MOVE_ROUNDS = 4
    }
}

private const val CONGESTION_WINDOW_FILL_BYTES = 64_000

private const val DELIVERY_ROUNDS = 8

private const val INITIAL_DCID_SEQ = 0L

private const val PACKET_THRESHOLD = 3

private val PROBE_TIMEOUT_BOUND = 600.milliseconds

private const val RETIRED_BY_THE_PEER = 3L

private val SERVER_ACK_DELAY = 30.milliseconds
