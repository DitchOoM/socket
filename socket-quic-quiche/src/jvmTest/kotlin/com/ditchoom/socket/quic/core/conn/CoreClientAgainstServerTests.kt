package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseLinger
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.edge.EdgeEnd
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The core's connection, client side, against today's server over a real socket: the handshake, a 1 MiB stream
 * written as fast as flow control allows, the same bytes echoed back, the connection closed and seen closed.
 */
class CoreClientAgainstServerTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    /**
     * The server keeps each connection open after its handler returns until the client closes it. With the default
     * linger it closed once its echo was acknowledged, racing the client's close: a host slow to call close() (the
     * Windows JFR-ring lane) saw the server's close first, and ByPeer was the right answer to a test asking ByLocal.
     */
    private val serverOptions = options.copy(closeLinger = QuicCloseLinger.UntilPeerDone(10.seconds))

    @Test
    fun aCoreClientEchoesOneMebibyteByteExactAndClosesItsConnection() =
        runBlocking {
            skipOnMissingNativeLib(CoreClientAgainstServerTests::class) {
                withQuicServer(port = 0, tlsConfig = tlsConfig(), quicOptions = serverOptions) {
                    val server =
                        launch(Dispatchers.IO) {
                            connections {
                                val stream = acceptStream()
                                while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedReadResult.Data<*>) Unit
                                stream.shutdownSend()
                            }
                        }
                    val payload = Random(7).nextBytes(PAYLOAD)
                    val echoed = withContext(Dispatchers.IO) { echoThroughCoreClient(port, payload) }
                    val got = echoed.second
                    val firstDiff = (0 until minOf(got.size, payload.size)).firstOrNull { got[it] != payload[it] } ?: -1
                    val nextMatch =
                        if (firstDiff <
                            0
                        ) {
                            -1
                        } else {
                            (firstDiff until payload.size).firstOrNull {
                                payload.copyOfRange(it, minOf(it + 32, payload.size)).contentEquals(
                                    got.copyOfRange(
                                        firstDiff,
                                        minOf(
                                            firstDiff + 32,
                                            got.size,
                                        ),
                                    ),
                                )
                            }
                                ?: -2
                        }
                    assertContentEquals(
                        payload,
                        got,
                        "${echoed.first}; first difference at $firstDiff; echo resumes at payload offset $nextMatch",
                    )
                    server.cancel()
                }
            }
        }

    /** Connects a core client, writes [payload] on one stream and finishes it, reads the echo to its end, closes. */
    private suspend fun echoThroughCoreClient(
        port: Int,
        payload: ByteArray,
    ): Pair<String, ByteArray> {
        val client =
            coreConnect(
                mismatch = MismatchResponse.Fail,
                host = "127.0.0.1",
                port = port,
                serverName = "localhost",
                options = options,
                factory = BufferFactory.Default,
                api = loadQuicheApi(),
                timeout = 10.seconds,
                readPolicy = ReadPolicy.Bounded(10.seconds),
                writePolicy = WritePolicy.Bounded(10.seconds),
            )
        val received = ByteArrayOutputStream()
        val stream = client.connection.openStream()
        var endedBy = "not ended"
        coroutineScope {
            val reader =
                async {
                    while (true) {
                        if (stream.read(10.seconds) { received.write(it.readByteArray(it.remaining())) } !is ScopedReadResult.Data<*>) {
                            endedBy = "fin after ${payload.size} written"
                            break
                        }
                    }
                }
            stream.writeFully(
                BufferFactory.Default.allocate(payload.size).apply {
                    writeBytes(payload)
                    resetForRead()
                },
                10.seconds,
            )
            (stream as HalfCloseable).shutdownSend()
            reader.await()
        }
        // A client slow to close, as on a loaded host: the server must still leave the close to it.
        delay(SLOW_CLOSE)
        client.connection.close(QuicError.NoError)
        // quiche reports the connection closed once its draining period after our close has run out.
        assertIs<ConnEnd.ByLocal>((client.connection.end() as EdgeEnd.Ended).end, "the connection ended by our close")
        return endedBy to received.toByteArray()
    }

    private fun tlsConfig(): QuicTlsConfig = coreTlsConfig()

    private companion object {
        const val PAYLOAD = 1 shl 20
        val SLOW_CLOSE = 1.seconds
    }
}
