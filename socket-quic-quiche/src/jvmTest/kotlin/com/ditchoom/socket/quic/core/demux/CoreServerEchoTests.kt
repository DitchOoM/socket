package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The run-to-completion client against the run-to-completion server over a real loopback socket. */
class CoreServerEchoTests {
    @Test
    fun aKibAndAMibEchoByteExact() =
        runBlocking {
            withCoreEchoServer { server ->
                val client = connectCore(server.port)
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
                val client = connectCore(server.port)
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
    fun theFourPointXClientEchoesThroughTheCoreServer() =
        runBlocking {
            withCoreEchoServer { server ->
                for (size in listOf(1_024, 1 shl 20)) {
                    val payload = Random(size).nextBytes(size)
                    val echoed =
                        withQuicConnection("127.0.0.1", server.port, coreTestOptions, timeout = 20.seconds) {
                            echoOver(openStream(), payload)
                        }
                    assertContentEquals(payload, echoed, "4.x echo of $size bytes")
                }
                assertEquals(2L, server.counts.accepted)
            }
        }

    @Test
    fun sixtyFourConcurrentConnectionsEchoOnOneServerLoop() = concurrentEchoes(64)

    @Test
    fun aThousandAndTwentyFourConcurrentConnectionsEchoOnOneServerLoop() = concurrentEchoes(1_024)

    private fun concurrentEchoes(connections: Int) =
        runBlocking {
            withCoreEchoServer(ServerConfig(admission = Admission(perPass = 256, inProgress = 2_048))) { server ->
                val echoed =
                    (0 until connections)
                        .map { n ->
                            async(Dispatchers.IO) {
                                val client = connectCore(server.port)
                                try {
                                    val payload = Random(n).nextBytes(1_024)
                                    payload to echoOver(client.connection.openStream(), payload)
                                } finally {
                                    client.connection.close(QuicError.NoError)
                                }
                            }
                        }.awaitAll()
                echoed.forEachIndexed { n, (sent, back) -> assertContentEquals(sent, back, "connection $n") }
                assertEquals(connections.toLong(), server.counts.accepted)
            }
        }
}
