package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.CrossingCount
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A sender that keeps the core's datagram send queue backed up, over loopback, waiting for room each time it is told
 * the queue is full: what the client's loop allocates per datagram, and how many calls into quiche's `dgram_send` it
 * makes beyond one per datagram quiche took: the refusals that find quiche's hand-off full, one per round that fills it.
 */
class CoreDatagramFlowTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aBackedUpDatagramFlowAllocatesNothingOnTheLoop() =
        flow(platformQuicheApi()) { client, channel, payload ->
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val loop =
                Thread
                    .getAllStackTraces()
                    .keys
                    .first { it.name == client.loop.name }
                    .id
            val recording = jdk.jfr.Recording()
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
            recording.start()
            val from = Instant.now()
            val before = threads.getThreadAllocatedBytes(loop)
            val accepted = send(channel, payload, MEASURED)
            val perDatagram = (threads.getThreadAllocatedBytes(loop) - before).toDouble() / accepted
            recording.stop()
            println("[core datagram flow] loop %.2f B/datagram over %d datagrams".format(perDatagram, accepted))
            val gate =
                when (netStackOfThisRuntime) {
                    is NetStack.Native -> LOOP_GATE
                    is NetStack.Jdk -> JDK_LOOP_GATE
                }
            if (perDatagram > gate) {
                fail(
                    "loop: %.2f B/datagram (gate %d), where:\n%s".format(
                        perDatagram,
                        gate,
                        where(recording, from, client.loop.name, MEASURED),
                    ),
                )
            }
        }

    @Test
    fun aBackedUpDatagramFlowRefusesOncePerFullHandOffAndOncePerTurnAtMost() {
        val count = CrossingCount(platformQuicheApi())
        flow(count.api) { _, channel, payload ->
            count.start()
            val accepted = send(channel, payload, MEASURED)
            count.stop()
            val calls = count.byFunction().toMap()
            val sends = calls["connDgramSend"] ?: 0L
            val turns = calls["connReadable"] ?: 0L
            val refusals = sends - accepted
            println(
                "[core datagram flow] %d datagrams, %d turns: %d dgram_send, %.2f refused per turn"
                    .format(accepted, turns, sends, refusals.toDouble() / turns),
            )
            // One refusal ends each round that fills quiche's hand-off: a turn writes several, and starts at most one more.
            val bound = turns + accepted / HAND_OFF + SLACK
            assertTrue(refusals <= bound, "dgram_send refused $refusals times in $turns turns, over the bound $bound")
        }
    }

    /** A client on [api] whose sender keeps the queue backed up, warmed up, against a server that receives everything. */
    private fun flow(
        api: com.ditchoom.socket.quic.QuicheApi,
        measure: suspend (CoreClient, CoreDatagramChannel, PlatformBuffer) -> Unit,
    ) = runBlocking {
        withDescriptorsAbove127 {
            skipOnMissingNativeLib(CoreDatagramFlowTests::class) {
                val options = coreTestOptions.copy(datagrams = DatagramOptions(sendQueueLen = DEPTH))
                withCoreServer(options = options, onConnection = { drain(it) }) { server ->
                    val client = connectCore(server.port, loops = loops, api = api, options = options)
                    val channel = client.connection.coreDatagrams
                    val payload =
                        BufferFactory.network().allocate(PAYLOAD).apply {
                            repeat(PAYLOAD) { writeByte(it.toByte()) }
                            resetForRead()
                        }
                    send(channel, payload, WARM_UP)
                    measure(client, channel, payload)
                    payload.freeIfNeeded()
                    client.connection.close(QuicError.NoError)
                }
            }
        }
    }

    /** Sends [count] datagrams, waiting for room whenever the queue is full; returns how many were queued. */
    private suspend fun send(
        channel: CoreDatagramChannel,
        payload: PlatformBuffer,
        count: Int,
    ): Long {
        var queued = 0L
        while (queued < count) {
            when (val sent = channel.send(payload)) {
                is DatagramSend.Sent -> queued++
                is DatagramSend.NotSent.QueueFull -> sent.awaitRoom()
                is DatagramSend.NotSent.TooLarge, is DatagramSend.NotSent.Unavailable -> error("unexpected: $sent")
            }
        }
        return queued
    }

    /** The loop's allocation sites the recording sampled since [from], heaviest first, per datagram of [count]. */
    private fun where(
        recording: jdk.jfr.Recording,
        from: Instant,
        loop: String,
        count: Int,
    ): String {
        val dump =
            java.nio.file.Files
                .createTempFile("core-datagram-flow", ".jfr")
        recording.dump(dump)
        val sites = HashMap<String, Long>()
        allocationSamples(dump) { it == loop }
            .filter { it.startTime >= from }
            .forEach { e ->
                val frames =
                    e.stackTrace
                        ?.frames
                        .orEmpty()
                        .take(FRAMES)
                        .joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                sites.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        java.nio.file.Files
            .delete(dump)
        return sites.entries
            .sortedByDescending { it.value }
            .take(SITES)
            .joinToString("\n") { "  %.1f B/datagram %s".format(it.value.toDouble() / count, it.key) }
    }

    private suspend fun drain(connection: CoreConnection) {
        while (true) {
            when (val got = connection.coreDatagrams.receiveDatagram()) {
                is DatagramReceive.Received -> got.buffer.freeIfNeeded()
                is DatagramReceive.NotReceived -> return
            }
        }
    }

    private companion object {
        const val DEPTH = 128
        const val PAYLOAD = 64
        const val WARM_UP = 20_000
        const val MEASURED = 100_000
        const val LOOP_GATE = 8
        const val JDK_LOOP_GATE = 130

        /** quiche's hand-off at this depth: the 32 that fill a packet with small datagrams. */
        const val HAND_OFF = 32
        const val SLACK = 64L
        const val FRAMES = 8
        const val SITES = 12
    }
}
