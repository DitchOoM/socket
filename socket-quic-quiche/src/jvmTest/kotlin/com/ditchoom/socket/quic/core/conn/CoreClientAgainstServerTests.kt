package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StreamRecvResult
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.SelectorWait
import com.ditchoom.socket.quic.core.loop.startLoop
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.loadQuicheApi
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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

    @Test
    fun aCoreClientEchoesOneMebibyteByteExactAndClosesItsConnection() =
        runBlocking {
            skipOnMissingNativeLib(CoreClientAgainstServerTests::class) {
                withQuicServer(port = 0, tlsConfig = tlsConfig(), quicOptions = options) {
                    val server =
                        launch(Dispatchers.IO) {
                            connections {
                                val stream = acceptStream()
                                while (stream.read(10.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data<*>) Unit
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

    private fun echoThroughCoreClient(
        port: Int,
        payload: ByteArray,
    ): Pair<String, ByteArray> {
        val api = loadQuicheApi()
        val factory = BufferFactory.Default
        val channel = DatagramChannel.open().connect(InetSocketAddress("127.0.0.1", port))
        channel.configureBlocking(false)
        val local = (channel.localAddress as InetSocketAddress).toNativeSockAddr(factory)
        val peer = (channel.remoteAddress as InetSocketAddress).toNativeSockAddr(factory)

        val config = api.configNew(1)
        encodeAlpnList(options.alpnProtocols, factory).let { api.configSetApplicationProtos(config, addressOf(it), it.remaining()) }
        applyQuicOptions(options, CommonJvmQuicConfigCalls(api, config), QuicRole.Client)
        val serverName =
            factory.allocate(10).apply {
                writeString("localhost", com.ditchoom.buffer.Charset.UTF8)
                writeByte(0)
                resetForRead()
            }
        val scid = generateScid(factory)
        val handle =
            api.connect(
                addressOf(serverName),
                9,
                addressOf(scid),
                QUIC_ISSUED_CONN_ID_LEN,
                local.address,
                local.length,
                peer.address,
                peer.length,
                config,
            )
        val path =
            ConnPath(api.recvInfoNew(peer.address, peer.length, local.address, local.length), api.sendInfoNew(), SendTarget.ConnectedPeer)

        val limits = BatchLimits(maxDatagrams = 64, maxDatagramSize = 1500)
        val sendStorage = factory.allocate(limits.maxDatagrams * limits.maxDatagramSize)
        val send = SendBatch(sendStorage, addressOf(sendStorage), limits)
        val receive =
            ReceiveBatch(
                FixedPool(
                    limits.maxDatagrams,
                ) { i -> factory.allocate(limits.maxDatagramSize).let { ReceiveBuffer(i, it, addressOf(it)) } },
                limits,
            )
        val clock = LoopClock { System.nanoTime() }
        val parts = OneConnParts()
        val loop = Loop(SelectorWait(channel, clock), clock, NioOneByOne(channel, send), parts, receive, send)
        val echo = EchoClient(payload)
        val conn = Conn(api, handle, path, loop, Entrant.loopTurn(loop = 1, generation = 1), clock, echo)
        parts.attach(conn)
        val running = startLoop(loop, "core-client-test")
        conn.start()

        try {
            val echoed = echo.endedBy.let { echo.result.get(30, TimeUnit.SECONDS) }
            // quiche reports the connection closed once its draining period after our close has run out.
            assertIs<ConnEnd.Locally>(echo.ended.get(5, TimeUnit.SECONDS), "the connection ended by our close")
            return echo.endedBy to echoed
        } finally {
            val closed = CompletableFuture<Unit>()
            loop.close { closed.complete(Unit) }
            closed.get(5, TimeUnit.SECONDS)
            running.thread.join(5_000)
        }
    }

    /** Writes [payload] on stream 0 as flow control allows, reads the echo, closes once all of it is back. */
    private class EchoClient(
        private val payload: ByteArray,
    ) : ConnEvents {
        val result = CompletableFuture<ByteArray>()
        val ended = CompletableFuture<ConnEnd>()
        private val out =
            BufferFactory.Default.allocate(payload.size).apply {
                writeBytes(payload)
                resetForRead()
            }
        private val outAddress = addressOf(out)
        private val inBuffer = BufferFactory.Default.allocate(65_536)
        private val inAddress = addressOf(inBuffer)
        private val received = ByteArrayOutputStream()
        private var written = 0
        private var finished = false
        private val stream = QuicStreamId(0)

        override fun established(inside: Conn.Inside) = writeMore(inside)

        override fun writable(
            inside: Conn.Inside,
            stream: QuicStreamId,
        ) = writeMore(inside)

        override fun readable(
            inside: Conn.Inside,
            stream: QuicStreamId,
        ) {
            while (true) {
                when (val got = inside.streamRecv(stream, inAddress, inBuffer.capacity)) {
                    is StreamRecvResult.Data -> {
                        inBuffer.position(0)
                        inBuffer.setLimit(got.bytesRead)
                        received.write(inBuffer.readByteArray(got.bytesRead))
                        inBuffer.setLimit(inBuffer.capacity)
                        if (got.fin) return finish(inside, "fin after $written written")
                    }
                    StreamRecvResult.Done -> return
                    is StreamRecvResult.Error -> return finish(inside, "error ${got.code} after $written written")
                    is StreamRecvResult.Reset -> return finish(inside, "reset ${got.applicationErrorCode} after $written written")
                    StreamRecvResult.ConnectionGone -> return finish(inside, "connection gone after $written written")
                }
            }
        }

        override fun closed(end: ConnEnd) {
            result.completeExceptionally(AssertionError("closed before the echo completed: $end"))
            ended.complete(end)
        }

        private fun writeMore(inside: Conn.Inside) {
            while (written < payload.size) {
                val sent = inside.streamSend(stream, outAddress + written, payload.size - written, WriteEnd.More).result
                if (sent <= 0) {
                    inside.wantWritable(stream)
                    return
                }
                written += sent
            }
            if (!finished) finished = inside.streamSend(stream, outAddress, 0, WriteEnd.Finish).result >= 0
        }

        var endedBy = "not ended"

        private fun finish(
            inside: Conn.Inside,
            why: String,
        ) {
            endedBy = why
            result.complete(received.toByteArray())
            inside.close(QuicError.NoError)
        }
    }

    private fun tlsConfig(): QuicTlsConfig = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private fun certPath(name: String): String =
        java.io
            .File(
                this::class.java.classLoader
                    .getResource("certs/$name")!!
                    .toURI(),
            ).absolutePath

    private companion object {
        const val PAYLOAD = 1 shl 20
    }
}

private fun addressOf(buffer: com.ditchoom.buffer.PlatformBuffer): Long = buffer.nativeMemoryAccess!!.nativeAddress.toLong()
