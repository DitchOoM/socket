package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.conn.AckHold
import com.ditchoom.socket.quic.core.conn.AckRule
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ACK a turn owes, on a real core client and server over a 60 ms round trip on virtual time. One ACK covers what a
 * turn received. By default an ACK waits only for the rest of its loop pass: a reply written on the loop carries it,
 * and a reply written off the loop finds it already sent. The cross-turn hold, chosen explicitly for the server, lets
 * a reply carry it, leaves at its deadline within the advertised max_ack_delay, and backs off after misses.
 */
class AckHoldVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)
    private val acrossTurns = ServerConfig(ack = AckRule.acrossTurns(WaitResolution(1_000_000L)))

    @Test
    fun whatOneTurnReceivedIsAcknowledgedByOneDatagram() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch {
                val stream = serverSide.acceptStream()
                runCatching { while (true) stream.read(10.seconds).also { (it as? ReadResult.Data)?.buffer?.freeIfNeeded() } }
            }
            val stream = pair.client.connection.openStream()
            stream.write(payload(64), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            val from = pair.now()
            stream.write(payload(BATCH), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            val batch = pair.sentBy(End.Client, from)
            assertTrue(batch.size > 1, "the write left as several datagrams: ${batch.size}")
            assertEquals(batch.first().at, batch.last().at, "the client sent the batch at one instant")
            val acks = pair.sentBy(End.Server, from)
            assertEquals(1, acks.size, "one datagram acknowledged the ${batch.size} the server read in one turn: ${shown(acks)}")
            assertEquals(batch.first().at + pair.oneWay.inWholeNanoseconds, acks.single().at, "at once: more than two arrived")
        }

    /** Echoes every request of the first stream, its handler on [context]. */
    private fun CoroutineScope.echoServer(
        pair: VirtualPair,
        context: CoroutineContext,
    ) = launch(context) {
        val stream = pair.accept().acceptStream()
        runCatching {
            while (true) {
                val got = stream.read(10.seconds)
                if (got !is ReadResult.Data) break
                got.buffer.freeIfNeeded()
                stream.write(payload(ECHO), 1.seconds)
            }
        }
    }

    /** [ECHOES] echoes after [WARM], the client on [context]; the datagrams each end sent from the first counted one. */
    private suspend fun CoroutineScope.echoes(
        pair: VirtualPair,
        context: CoroutineContext,
    ): Pair<List<Sent>, List<Sent>> {
        val stream = pair.client.connection.openStream()
        var echoed = 0
        var from = 0L
        launch(context) {
            repeat(WARM + ECHOES) {
                if (it == WARM) from = pair.now()
                stream.write(payload(ECHO), 1.seconds)
                (stream.read(10.seconds) as ReadResult.Data).buffer.freeIfNeeded()
                echoed++
            }
        }
        pair.runUntil(within = 30.seconds) { echoed == WARM + ECHOES }
        pair.runFor(pair.oneWay * 4)
        return pair.sentBy(End.Client, from) to pair.sentBy(End.Server, from)
    }

    @Test
    fun aReplyWrittenOnTheLoopCarriesTheAckOfItsRequest() =
        VirtualPair.with(options) { pair ->
            echoServer(pair, Dispatchers.Unconfined)
            val (client, server) = echoes(pair, Dispatchers.Unconfined)
            assertEquals(ECHOES, server.size, "each reply carried the ACK of its request: ${shown(server)}")
            // The last reply's ACK has no request to ride on: it leaves at the end of its pass.
            assertEquals(ECHOES + 1, client.size, "each request carried the ACK of the reply before it: ${shown(client)}")
        }

    @Test
    fun anAckWhoseReplyIsWrittenOffTheLoopLeavesAtTheEndOfItsPass() =
        VirtualPair.with(options) { pair ->
            echoServer(pair, EmptyCoroutineContext)
            val (client, server) = echoes(pair, EmptyCoroutineContext)
            assertEquals(2 * ECHOES, server.size, "an ACK alone, then the reply: ${shown(server)}")
            // From the last warm-up reply's ACK: an ACK alone, then the next request, and the last reply's ACK.
            assertEquals(2 * ECHOES + 1, client.size, "an ACK alone, then the next request: ${shown(client)}")
            for (n in 0 until ECHOES) {
                val arrived = client[2 * n + 1].at + pair.oneWay.inWholeNanoseconds
                assertEquals(arrived, server[2 * n].at, "the ACK of request $n left the instant the request arrived")
            }
        }

    @Test
    fun aReplyCarriesTheAckOfItsRequestAcrossTurnsWhenChosen() =
        VirtualPair.with(options, serverConfig = acrossTurns) { pair ->
            echoServer(pair, EmptyCoroutineContext)
            val (_, server) = echoes(pair, EmptyCoroutineContext)
            assertEquals(ECHOES, server.size, "each reply, written off the loop, carried the ACK of its request: ${shown(server)}")
        }

    @Test
    fun aCrossTurnHoldNoReplyEndsLeavesAtItsDeadline() =
        VirtualPair.with(options, serverConfig = acrossTurns) { pair ->
            silentReader(pair)
            val stream = pair.client.connection.openStream()
            stream.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            val delay = ackDelayOfOneRequest(pair, stream)
            val rtt = pair.oneWay * 2
            assertTrue(delay >= (rtt / 8).inWholeNanoseconds, "the ACK was held for a reply: ${delay / 1_000} us")
            assertTrue(delay <= (rtt / 4 + 1.milliseconds).inWholeNanoseconds, "within a quarter of the RTT: ${delay / 1_000} us")
            assertTrue(delay < 25.milliseconds.inWholeNanoseconds, "within the advertised max_ack_delay: ${delay / 1_000} us")
        }

    @Test
    fun aConnectionWhoseCrossTurnHoldsKeepMissingAcknowledgesAtOnceAndTriesAgainLater() =
        VirtualPair.with(options, serverConfig = acrossTurns) { pair ->
            silentReader(pair)
            val stream = pair.client.connection.openStream()
            stream.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            // The first request opened the stream: a new stream, handed up, was the connection's first missed hold.
            val delays = List(AckHold.MISSES_TO_BACK_OFF - 1 + AckHold.FIRST_SKIP + 1) { ackDelayOfOneRequest(pair, stream) }
            val held = delays.map { it > 0L }
            val expected =
                List(AckHold.MISSES_TO_BACK_OFF - 1) { true } + List(AckHold.FIRST_SKIP) { false } + listOf(true)
            assertEquals(expected, held, "holds, then at once after misses, then one trial: ${delays.map { it / 1_000 }} us")
        }

    /** The server reads every stream and never answers. */
    private fun CoroutineScope.silentReader(pair: VirtualPair) =
        launch {
            val serverSide = pair.accept()
            val stream = serverSide.acceptStream()
            runCatching { while (true) stream.read(30.seconds).also { (it as? ReadResult.Data)?.buffer?.freeIfNeeded() } }
        }

    /** How long after one request arrived the server's ACK of it left. */
    private suspend fun ackDelayOfOneRequest(
        pair: VirtualPair,
        stream: QuicByteStream,
    ): Long {
        val from = pair.now()
        stream.write(payload(ECHO), 1.seconds)
        pair.runFor(pair.oneWay * 6)
        val request = pair.sentBy(End.Client, from).single()
        val ack = pair.sentBy(End.Server, from).single()
        return ack.at - (request.at + pair.oneWay.inWholeNanoseconds)
    }

    private fun shown(sent: List<Sent>): String = sent.joinToString { "${it.at / 1_000_000} ms ${it.bytes.size} B" }

    private fun payload(size: Int): PlatformBuffer =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val ECHO = 64
        const val BATCH = 6_000
        const val WARM = 4
        const val ECHOES = 16
    }
}
