package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.CountingBufferFactory
import com.ditchoom.buffer.counting
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.withSizeLimit
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A [BufferFactory] composed from the buffer library's parts (a counter, a size limit, over the pooled
 * [BufferFactory.network]) carries a connection's echoes and closes it: the connection allocates from the
 * factory it was given and frees to it, and never stacks a pool of its own on a factory that composes one.
 */
abstract class ComposedBufferFactoryTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options = QuicOptions(alpnProtocols = listOf("composed-factory-test"), verifyPeer = false)

    @Test
    fun aCountedNetworkFactoryCarriesEchoesAndCloses() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val counted = BufferFactory.network().counting()
                echoRounds(counted)
                assertTrue(
                    counted.allocationCount >= ROUNDS,
                    "the connection allocates from the factory it was given: ${counted.allocationCount}",
                )
            }
        }

    @Test
    fun aSizeLimitedCountedNetworkFactoryCarriesEchoesAndCloses() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val counted = BufferFactory.network().withSizeLimit(SIZE_LIMIT).counting()
                echoRounds(counted)
                assertTrue(
                    counted.allocationCount >= ROUNDS,
                    "the connection allocates from the factory it was given: ${counted.allocationCount}",
                )
            }
        }

    private suspend fun echoRounds(factory: CountingBufferFactory) =
        coroutineScope {
            withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                val serverJob =
                    launch {
                        connections {
                            val stream = acceptStream()
                            while (true) {
                                val chunk = stream.read(ECHO_BOUND) { it.readString(it.remaining(), Charset.UTF8) }
                                if (chunk !is ScopedRead.Data) break
                                stream.writeText(bufferFactory, chunk.value)
                            }
                            stream.close()
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", port, options, TransportConfig(bufferFactory = factory)) {
                        val stream = openStream()
                        repeat(ROUNDS) { round ->
                            val text = "echo-$round"
                            stream.writeText(bufferFactory, text)
                            val echoed = stream.read(ECHO_BOUND) { it.readString(it.remaining(), Charset.UTF8) }
                            assertEquals(text, (echoed as ScopedRead.Data).value)
                        }
                        stream.shutdownSend()
                        stream.close()
                    }
                } finally {
                    serverJob.cancelAndJoin()
                }
            }
        }

    private suspend fun QuicByteStream.writeText(
        factory: BufferFactory,
        text: String,
    ) {
        val out = factory.allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            writeFully(out, ECHO_BOUND)
        } finally {
            out.freeIfNeeded()
        }
    }

    private companion object {
        const val ROUNDS = 50
        const val SIZE_LIMIT = 1 shl 20
        val ECHO_BOUND = 10.seconds
    }
}
