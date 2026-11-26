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
 * on its loop side, the copy into the loop's receive buffer and the copy into a send slot, shows here. The lent
 * channel's own coroutines run on the caller's dispatcher with the channel's own allocations, and are reported, not
 * gated.
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
                                        ride = ClientRide.Lent(LentChannel(shared.quic)),
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
        sites = sitesOf(recording, from) { it == client.loop.name }
        ioSites = sitesOf(recording, from) { it.startsWith(DEFAULT_WORKER) }
        recording.close()
        return loop to io
    }

    /** The loop's allocation sites over the measured echoes, in bytes per echo: what a failed gate prints. */
    private var sites = ""

    /** The same for the threads the lent io's coroutines and the channel's own code run on: printed, not gated. */
    private var ioSites = ""

    private fun sitesOf(
        recording: jdk.jfr.Recording,
        from: Instant,
        onThread: (String) -> Boolean,
    ): String {
        val dump = Files.createTempFile("core-lent-loop", ".jfr")
        recording.dump(dump)
        val bySite = HashMap<String, Long>()
        allocationSamples(dump, onThread)
            .filter { it.startTime >= from }
            .forEach { e ->
                val frames =
                    e.stackTrace
                        ?.frames
                        .orEmpty()
                        .take(6)
                        .joinToString(" < ") { "${it.method.type.name.substringAfterLast('.')}.${it.method.name}" }
                bySite.merge("${e.getClass("objectClass").name} @ $frames", e.getLong("weight"), Long::plus)
            }
        Files.delete(dump)
        return bySite.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString("\n") { "  ${it.value / MEASURED} B/echo ${it.key}" }
    }

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

        /** The owned-socket echo's gates ([CoreDatagramAllocationTests]): a lent channel adds nothing on the loop. */
        const val LOOP_GATE = 8
        const val JDK_LOOP_GATE = 130
        const val PAYLOAD = 64
        const val WARM_UP = 5_000
        const val MEASURED = 20_000
        const val TIMEOUT_SECONDS = 120L
    }
}
