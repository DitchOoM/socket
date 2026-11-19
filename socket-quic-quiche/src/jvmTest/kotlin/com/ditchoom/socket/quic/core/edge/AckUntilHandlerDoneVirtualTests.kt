package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.conn.AckRule
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [AckRule.UntilHandlerDone] on a real core client and server on virtual time, both ends choosing it. Handlers run off
 * the loop, so a reply comes back in a later turn: the ACK of what a handler was resumed with waits while that handler
 * runs, rides on its reply, leaves at once when the handler suspends without one, and leaves at the advertised
 * max_ack_delay (less a wait step) when the handler runs on. Loss still acknowledges at once.
 */
class AckUntilHandlerDoneVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)
    private val rule = AckRule.untilHandlerDone(WaitResolution(1_000_000L))
    private val serverConfig = ServerConfig(ack = rule)
    private val cap = (rule as AckRule.UntilHandlerDone).cap.nanos

    private fun pair(
        oneWay: Duration = 30.milliseconds,
        body: suspend CoroutineScope.(VirtualPair) -> Unit,
    ) = VirtualPair.with(options, oneWay = oneWay, serverConfig = serverConfig, clientAck = rule, body = body)

    @Test
    fun anEchoWithHandlersOffTheLoopIsOneDatagramEachWay() {
        for (oneWay in listOf(1.milliseconds, 30.milliseconds, 100.milliseconds)) {
            pair(oneWay) { pair ->
                launch { echo(pair.accept().acceptStream()) }
                val (client, server) = echoes(pair)
                assertEquals(ECHOES, server.size, "one-way $oneWay: each reply carried the ACK of its request: ${shown(server)}")
                // Each request carried the ACK of the reply before it; the last reply's ACK left when its handler finished.
                assertEquals(ECHOES + 1, client.size, "one-way $oneWay: each request carried the ACK of the reply before: ${shown(client)}")
            }
        }
    }

    @Test
    fun anEchoAnswersEachRequestOneRoundTripAfterItLeft() =
        pair { pair ->
            launch { echo(pair.accept().acceptStream()) }
            val (client, server) = echoes(pair)
            val requests = client.filter { it.bytes.size > ACK_ONLY }
            val replies = server.filter { it.bytes.size > ACK_ONLY }
            assertEquals(ECHOES, replies.size, "every request was answered: ${shown(server)}")
            for (n in 0 until ECHOES) {
                assertEquals(requests[n].at + pair.oneWay.inWholeNanoseconds, replies[n].at, "reply $n left the instant its request arrived")
            }
        }

    @Test
    fun aHandlerThatSuspendsWithoutReplyingSendsTheAckAtOnce() =
        pair { pair ->
            val parked = CompletableDeferred<Unit>()
            launch {
                val stream = pair.accept().acceptStream()
                while (true) {
                    val got = runCatching { stream.read(30.seconds) }.getOrNull()
                    if (got !is ReadResult.Data) break
                    got.buffer.freeIfNeeded()
                    // Suspends on something that is not this connection: the handler is no longer running.
                    if (!parked.isCompleted) parked.await()
                }
            }
            val stream = pair.client.connection.openStream()
            stream.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            assertEquals(0L, ackDelayOfOneRequest(pair, stream), "the ACK left the instant the handler suspended")
            parked.complete(Unit)
        }

    @Test
    fun aHandlerStillRunningAtTheCapSendsTheAckThen() =
        pair { pair ->
            val running = AtomicInteger(RUN)
            launch(Dispatchers.Default) {
                val stream = pair.accept().acceptStream()
                while (true) {
                    val got = runCatching { stream.read(30.seconds) }.getOrNull()
                    if (got !is ReadResult.Data) break
                    got.buffer.freeIfNeeded()
                    // Runs, never suspending, until the test lets it go.
                    while (running.get() == RUN) Thread.onSpinWait()
                    running.set(RUN)
                }
            }
            val stream = pair.client.connection.openStream()
            stream.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            running.set(LET_GO)
            pair.runUntil { running.get() == RUN }
            parkedOnItsRead()
            val delay = ackDelayOfOneRequest(pair, stream)
            running.set(LET_GO)
            assertTrue(delay >= cap, "held while the handler ran: ${delay / 1_000} us, cap ${cap / 1_000} us")
            assertTrue(delay < 25.milliseconds.inWholeNanoseconds, "within the advertised max_ack_delay: ${delay / 1_000} us")
        }

    @Test
    fun lossSendsTheAckAtOnceWhileAHandlerRuns() =
        pair { pair ->
            val running = AtomicInteger(LET_GO)
            val accepted = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) {
                val connection = pair.accept()
                repeat(2) {
                    val stream = connection.acceptStream()
                    launch(Dispatchers.Default) {
                        while (true) {
                            val got = runCatching { stream.read(30.seconds) }.getOrNull()
                            if (got !is ReadResult.Data) break
                            got.buffer.freeIfNeeded()
                            while (running.get() == RUN) Thread.onSpinWait()
                        }
                    }
                }
                accepted.complete(Unit)
            }
            val lost = pair.client.connection.openStream()
            val kept = pair.client.connection.openStream()
            lost.write(payload(ECHO), 1.seconds)
            kept.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 6)
            pair.runUntil { accepted.isCompleted }
            running.set(RUN)
            parkedOnItsRead()
            pair.lose(End.Client, 1)
            lost.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay)
            val from = pair.now()
            kept.write(payload(ECHO), 1.seconds)
            pair.runFor(pair.oneWay * 2)
            val request = pair.sentBy(End.Client, from).first()
            val ack = pair.sentBy(End.Server, from).first()
            running.set(LET_GO)
            assertEquals(request.at + pair.oneWay.inWholeNanoseconds, ack.at, "a gap in what arrived is acknowledged at once")
        }

    /** A handler on another thread parks its next read within this wall-clock wait: only a parked read's caller is resumed. */
    private fun parkedOnItsRead() = Thread.sleep(PARK_MILLIS)

    /** Echoes every request of [stream] until it ends. */
    private suspend fun echo(stream: QuicByteStream) {
        runCatching {
            while (true) {
                val got = stream.read(10.seconds)
                if (got !is ReadResult.Data) break
                got.buffer.freeIfNeeded()
                stream.write(payload(ECHO), 1.seconds)
            }
        }
    }

    /** [ECHOES] echoes after [WARM], handlers off the loop; the datagrams each end sent from the first counted one. */
    private suspend fun CoroutineScope.echoes(pair: VirtualPair): Pair<List<Sent>, List<Sent>> {
        val stream = pair.client.connection.openStream()
        var echoed = 0
        var from = 0L
        launch(EmptyCoroutineContext) {
            repeat(WARM + ECHOES) {
                if (it == WARM) from = pair.now()
                stream.write(payload(ECHO), 1.seconds)
                (stream.read(10.seconds) as ReadResult.Data).buffer.freeIfNeeded()
                echoed++
            }
        }
        pair.runUntil(within = 60.seconds) { echoed == WARM + ECHOES }
        pair.runFor(pair.oneWay * 4)
        return pair.sentBy(End.Client, from) to pair.sentBy(End.Server, from)
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

        /** Larger than any datagram that carries only an ACK, smaller than one that carries [ECHO] bytes. */
        const val ACK_ONLY = 80
        const val WARM = 4
        const val ECHOES = 16
        const val RUN = 0
        const val LET_GO = 1
        const val PARK_MILLIS = 100L
    }
}
