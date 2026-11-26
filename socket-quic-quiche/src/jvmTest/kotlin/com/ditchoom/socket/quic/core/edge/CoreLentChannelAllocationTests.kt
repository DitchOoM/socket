@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.ALLOCATION_SAMPLE
import com.ditchoom.socket.quic.core.testing.PerfGates
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What a core client on a lent channel allocates per 64-byte datagram echo against a core server, on its loop thread,
 * with the JVM's per-thread allocation counter after warm-up: held to the gate of the same echo on a socket the core
 * owns ([CoreDatagramAllocationTests]). Each datagram crosses the lent channel's io both ways, so a per-datagram object
 * on its loop side, the copy into the loop's receive buffer and the copy into a send slot, shows here. The lent io's
 * coroutines run on [kotlinx.coroutines.Dispatchers.Default], beside the channel's own code (the demultiplexer, NIO):
 * what those threads allocate is printed, and what the lent io's own code allocates there, read from JFR allocation
 * samples whose innermost frame is ours, is gated.
 */
class CoreLentChannelAllocationTests {
    @Test
    fun aDatagramEchoOnALentChannelAllocatesNoMoreOnTheLoopThanOnAnOwnedSocket() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(CoreLentChannelAllocationTests::class) {
                    val on = coreTestOptions.copy(datagrams = DatagramOptions(), sessionStore = QuicSessionStore.NotKept)
                    withCoreServer(options = on, onConnection = { echoDatagrams(it.datagramChannel()) }) { server ->
                        coroutineScope {
                            // The demultiplexer reads on the default dispatcher: this test's own thread blocks while it measures.
                            val demux = CoroutineScope(coroutineContext + Dispatchers.Default)
                            val shared = UdpSocket.bind(LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(demux)
                            val loops = LoopSet(1)
                            try {
                                val client =
                                    coreConnect(
                                        mismatch = MismatchResponse.Fail,
                                        host = LOOPBACK,
                                        port = server.port,
                                        serverName = "localhost",
                                        options = on,
                                        factory = BufferFactory.network(),
                                        api = platformQuicheApi(),
                                        timeout = 10.seconds,
                                        readPolicy = ReadPolicy.Bounded(10.seconds),
                                        writePolicy = WritePolicy.Bounded(10.seconds),
                                        loops = loops,
                                        ride = ClientRide.Lent(lentChannelOf(shared.quic)),
                                    )
                                gate(client)
                                client.connection.close(QuicError.NoError)
                            } finally {
                                loops.close()
                                shared.close()
                            }
                        }
                    }
                }
            }
        }

    private fun gate(client: CoreClient) {
        val dispatcher = ArrayQueueDispatcher(CALLER_THREAD)
        val done = CompletableFuture<kotlin.Result<Pair<Double, Double>>>()
        suspend { measure(client, client.connection.datagramChannel()) }
            .startCoroutine(Continuation(dispatcher + Job()) { done.complete(it) })
        val (loop, io) = done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getOrThrow()
        dispatcher.close()
        println(
            "[core lent-channel allocation] loop %.1f B/echo, lent io and channel %.1f B/echo; their sites:\n%s".format(loop, io, ioSites),
        )
        val loopGate =
            when (netStackOfThisRuntime) {
                is NetStack.Native -> LOOP_GATE
                is NetStack.Jdk -> JDK_LOOP_GATE
            }
        PerfGates.record(
            CoreLentChannelAllocationTests::class.java,
            "lent-datagram-echo",
            "loop-bytes",
            loop,
            loopGate.toDouble(),
            "B-per-echo",
        )
        assertTrue(
            loop <= loopGate,
            "loop thread on a lent channel: %.1f B/datagram echo (gate %d); sites:\n%s".format(loop, loopGate, sites),
        )
        PerfGates.record(CoreLentChannelAllocationTests::class.java, "lent-datagram-echo", "own-io-bytes", ownIo, IO_GATE, "B-per-echo")
        assertTrue(
            ownIo <= IO_GATE,
            "the lent io's own code on its threads: %.1f B/datagram echo (gate %.0f); sites:\n%s".format(ownIo, IO_GATE, ownIoSites),
        )
    }

    /** On the caller's thread: warms up, then counts what the client's loop, and the threads of the lent io, allocate per echo. */
    private suspend fun measure(
        client: CoreClient,
        channel: ConnectedDatagramChannel,
    ): Pair<Double, Double> {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val payload =
            BufferFactory.network().allocate(PAYLOAD).apply {
                repeat(PAYLOAD) { writeByte(it.toByte()) }
                resetForRead()
            }
        repeat(WARM_UP) { echo(channel, payload) }
        val loopId = threadId(client.loop.name)
        val ioIds = defaultWorkers()
        val recording = jdk.jfr.Recording()
        recording.enable(ALLOCATION_SAMPLE).with("throttle", "off").withStackTrace()
        recording.start()
        val from = Instant.now()
        val loopBefore = threads.getThreadAllocatedBytes(loopId)
        val ioBefore = threads.getThreadAllocatedBytes(ioIds).sum()
        repeat(MEASURED) { echo(channel, payload) }
        val loop = (threads.getThreadAllocatedBytes(loopId) - loopBefore).toDouble() / MEASURED
        val io = (threads.getThreadAllocatedBytes(ioIds).sum() - ioBefore).toDouble() / MEASURED
        recording.stop()
        val dump = Files.createTempFile("core-lent-loop", ".jfr")
        recording.dump(dump)
        recording.close()
        sites = sitesOf(sampled(dump, from) { it == client.loop.name })
        val onIo = sampled(dump, from) { it.startsWith(DEFAULT_WORKER) }
        Files.delete(dump)
        ioSites = sitesOf(onIo)
        ownIo = onIo.filter { ours(it.top) }.sumOf { it.weight }.toDouble() / MEASURED
        ownIoSites = sitesOf(onIo.filter { ours(it.top) })
        return loop to io
    }

    /** The loop's allocation sites over the measured echoes, in bytes per echo: what a failed gate prints. */
    private var sites = ""

    /** The same for the threads the lent io's coroutines and the channel's own code run on: printed. */
    private var ioSites = ""

    /** What the lent io's own code allocated on those threads, per echo: sampled, so read as an upper bound. */
    private var ownIo = 0.0
    private var ownIoSites = ""

    /** One allocation sample: where, the class of its innermost frame, and the bytes it stands for. */
    private class Sampled(
        val site: String,
        val top: String,
        val weight: Long,
    )

    private fun sampled(
        dump: java.nio.file.Path,
        from: Instant,
        onThread: (String) -> Boolean,
    ): List<Sampled> =
        allocationSamples(dump, onThread)
            .filter { it.startTime >= from }
            .map { e ->
                val frames = e.stackTrace?.frames.orEmpty()
                val site = frames.take(6).joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                Sampled(
                    "${e.getClass("objectClass").name} @ $site",
                    frames
                        .firstOrNull()
                        ?.method
                        ?.type
                        ?.name
                        .orEmpty(),
                    e.getLong("weight"),
                )
            }

    private fun sitesOf(samples: List<Sampled>): String {
        val bySite = HashMap<String, Long>()
        for (s in samples) bySite.merge(s.site, s.weight, Long::plus)
        return bySite.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

    /** Whether an allocation's innermost frame is the lent io's own code, not the channel's or the coroutine library's. */
    private fun ours(frameClass: String): Boolean = OWN_CLASSES.any { frameClass.startsWith(it) }

    private suspend fun echo(
        channel: ConnectedDatagramChannel,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        channel.send(payload)
        when (val result = channel.receive()) {
            is DatagramReadResult.Received -> result.datagram.payload.freeIfNeeded()
            is DatagramReadResult.Closed -> fail("the echo ended early: ${result.reason}")
        }
    }

    private suspend fun echoDatagrams(channel: ConnectedDatagramChannel) {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    val payload = result.datagram.payload
                    channel.send(payload)
                    payload.freeIfNeeded()
                }
                is DatagramReadResult.Closed -> return
            }
        }
    }

    private fun threadId(name: String): Long =
        Thread
            .getAllStackTraces()
            .keys
            .first { it.name == name }
            .id

    /** The default dispatcher's workers, where the lent io's receiver and sender run, with the channel's own code. */
    private fun defaultWorkers(): LongArray =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.name.startsWith(DEFAULT_WORKER) }
            .map { it.id }
            .toLongArray()

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CALLER_THREAD = "core-lent-alloc-caller"
        const val DEFAULT_WORKER = "DefaultDispatcher-worker"

        /** The lent io's own code, by class name prefix: what [IO_GATE] holds. */
        val OWN_CLASSES =
            listOf(
                "com.ditchoom.socket.quic.core.edge.Lent",
                "com.ditchoom.socket.quic.core.edge.PeerBook",
                "com.ditchoom.socket.quic.core.edge.WaiterCell",
                "com.ditchoom.socket.quic.core.edge.OpGuard",
                "com.ditchoom.socket.quic.core.udp.LentDrops",
            )

        /** The lent io's own objects on its threads, per echo: none, with room for one sample's weight spread over the run. */
        const val IO_GATE = 8.0

        /** The owned-socket echo's gates ([CoreDatagramAllocationTests]): a lent channel adds nothing on the loop. */
        const val LOOP_GATE = 8
        const val JDK_LOOP_GATE = 130
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val TIMEOUT_SECONDS = 120L
    }
}
