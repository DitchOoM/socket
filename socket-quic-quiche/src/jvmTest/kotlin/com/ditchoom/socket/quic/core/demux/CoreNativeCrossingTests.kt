package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.CrossingCount
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The calls into quiche per 1 KiB echo on one long-lived stream, client and server counted together: every call is a
 * crossing into native code. A connection's turn learns its streams' state in one crossing, so the count does not grow
 * with the streams quiche reports readable or writable.
 */
class CoreNativeCrossingTests {
    @get:Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun oneConnectionEchoesCrossIntoQuicheAFewTimesPerEcho() = measure(connections = 1, measured = 2_000)

    @Test
    fun sixtyFourConnectionsEchoCrossIntoQuicheAFewTimesPerEcho() = measure(connections = 64, measured = 200)

    private fun measure(
        connections: Int,
        measured: Int,
    ) = runBlocking {
        withDescriptorsAbove127 {
            val count = CrossingCount(platformQuicheApi())
            withCoreEchoServer(api = count.api, factory = BufferFactory.network()) { server ->
                val clients =
                    List(connections) { connectCore(server.port, loops = loops, api = count.api, factory = BufferFactory.network()) }
                val streams = clients.map { it.connection.openStream() }
                val payloads = List(connections) { payload() }
                echoes(streams, payloads, WARM_UP)
                count.start()
                echoes(streams, payloads, measured)
                count.stop()
                val echoes = connections * measured
                val report = count.report(echoes)
                println("[core native crossings] %d connection(s), per 1 KiB echo:\n%s".format(connections, report))
                clients.forEach { it.connection.close(QuicError.NoError) }
                val perStream = count.of(PER_STREAM).toDouble() / echoes
                assertTrue(
                    perStream <= PER_STREAM_GATE,
                    "%.2f per-stream crossings per echo (gate %.1f):\n%s".format(perStream, PER_STREAM_GATE, report),
                )
            }
        }
    }

    private suspend fun echoes(
        streams: List<QuicByteStream>,
        payloads: List<PlatformBuffer>,
        count: Int,
    ) = coroutineScope {
        streams.indices
            .map { i -> async(Dispatchers.Default) { repeat(count) { roundTrip(streams[i], payloads[i]) } } }
            .awaitAll()
    }

    private fun payload(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private suspend fun roundTrip(
        stream: QuicByteStream,
        payload: PlatformBuffer,
    ) {
        payload.position(0)
        stream.write(payload, 10.seconds)
        var received = 0
        while (received < payload.limit()) {
            val result = stream.read(10.seconds)
            if (result !is ReadResult.Data) fail("the echo ended early: $result")
            received += result.buffer.remaining()
            result.buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val PAYLOAD = 1_024
        const val WARM_UP = 300

        /** The calls that walk quiche's readable and writable streams one at a time. */
        val PER_STREAM =
            setOf("connReadable", "connWritable", "streamIterNext", "streamIterNextId", "streamIterFree", "connStreamWritableNext")
        const val PER_STREAM_GATE = 0.0
    }
}
