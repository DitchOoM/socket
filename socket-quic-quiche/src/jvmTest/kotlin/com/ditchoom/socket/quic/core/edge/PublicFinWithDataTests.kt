package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The public `writeAndFinish` hands quiche its FIN with its last bytes, on the real loop: the loop drains the send
 * queue the moment bytes are queued, so a FIN asked for after them would reach quiche alone, in a packet of its own.
 * Counted on the client's quiche calls over many small requests: no stream's FIN is offered without bytes.
 */
class PublicFinWithDataTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** One stream send the client asked of quiche: how many bytes it offered and whether with the FIN. */
    private class Offer(
        val stream: Long,
        val bytes: Int,
        val fin: Boolean,
    )

    private class Recording(
        private val inner: QuicheApi,
        private val offers: ConcurrentLinkedQueue<Offer>,
    ) : QuicheApi by inner {
        override fun connStreamSendStep(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
            fin: Boolean,
        ): StreamSendStep = inner.connStreamSendStep(conn, streamId, buf, bufLen, fin).also { offers += Offer(streamId.id, bufLen, fin) }
    }

    @Test
    fun aWriteAndFinishNeverSendsItsFinAlone() =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(PublicFinWithDataTests::class) {
                    withCoreServer(onConnection = { core ->
                        core.public().handleStreams { incoming ->
                            val stream = incoming as QuicStream
                            stream.receive.readEach { }
                            stream.send.finish()
                        }
                    }) { server ->
                        val offers = ConcurrentLinkedQueue<Offer>()
                        val client =
                            connectCore(server.port, loops = loops, api = Recording(platformQuicheApi(), offers)).connection.public()
                        val request =
                            BufferFactory.network().allocate(REQUEST).apply {
                                repeat(REQUEST) { writeByte(it.toByte()) }
                                resetForRead()
                            }
                        repeat(REQUESTS) {
                            val stream = client.openStream().getOrElse { fail("not opened: $it") }
                            request.position(0)
                            if (stream.send.writeAndFinish(request) !== StreamWrite.Written) fail("request $it was not written")
                            stream.receive.readEach { }
                        }
                        val alone = offers.filter { it.fin && it.bytes == 0 }
                        val withBytes = offers.filter { it.fin && it.bytes > 0 }
                        println("[public fin] $REQUESTS requests: FIN with the last bytes ${withBytes.size}, FIN alone ${alone.size}")
                        assertEquals(0, alone.size, "a FIN went alone on streams ${alone.map { it.stream }.take(10)}")
                        assertTrue(withBytes.size >= REQUESTS, "every request's FIN went with its bytes: ${withBytes.size}")
                        client.close()
                    }
                }
            }
        }

    private companion object {
        const val REQUEST = 512
        const val REQUESTS = 200
    }
}
