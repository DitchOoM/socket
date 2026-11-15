package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
