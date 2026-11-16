package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The run-to-completion client against the run-to-completion server over a real loopback socket, on every platform. */
class CoreEchoTests {
    /** Client loops of this class's own, so connections it leaves ending never share a thread with another test's. */
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aKibAndAMibEchoByteExact() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                try {
                    for (size in listOf(1_024, 1 shl 20)) {
                        val payload = Random(size).nextBytes(size)
                        assertContentEquals(payload, echoOver(client.connection.openStream(), payload), "echo of $size bytes")
                    }
                } finally {
                    client.connection.close(QuicError.NoError)
                }
                assertEquals(1L, server.counts.accepted)
            }
        }

    @Test
    fun aThousandStreamsPerRequestEachEchoTheirOwnBytes() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                try {
                    repeat(1_000) { n ->
                        val payload = Random(n).nextBytes(1 + n % 700)
                        val stream = client.connection.openStream()
                        assertContentEquals(payload, echoOver(stream, payload), "request $n")
                        stream.close()
                    }
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun sixtyFourConcurrentConnectionsEchoOnOneServerLoop() =
        runBlocking {
            withCoreEchoServer(ServerConfig(admission = AdmissionBounds(perPass = 256, inProgress = 2_048))) { server ->
                val echoed =
                    (0 until CONNECTIONS)
                        .map { n ->
                            async(Dispatchers.IO) {
                                val client = connectCore(server.port, loops = loops)
                                try {
                                    val payload = Random(n).nextBytes(1_024)
                                    payload to echoOver(client.connection.openStream(), payload)
                                } finally {
                                    client.connection.close(QuicError.NoError)
                                }
                            }
                        }.awaitAll()
                echoed.forEachIndexed { n, (sent, back) -> assertContentEquals(sent, back, "connection $n") }
                assertEquals(CONNECTIONS.toLong(), server.counts.accepted)
            }
        }

    private companion object {
        const val CONNECTIONS = 64
    }
}
