package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnectionLife
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * What a client wrote and finished before a graceful close reaches the server's reader as data and then the FIN,
 * however late that reader reads: the close ends the connection, not what it delivered (RFC 9000 section 10.2).
 */
class FinBeforeCloseVirtualTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            incomingStreams = IncomingStreams.Accept(),
        )

    /** The server's reader takes the first chunk, then reads again only once the client's close ended the connection. */
    @Test
    fun aReaderThatReadsAfterThePeersGracefulCloseGetsTheDataThenTheFin() =
        VirtualPair.with(options) { pair ->
            val server = pair.accept().public()
            val client = pair.client.connection.public()
            val incoming = CompletableDeferred<QuicIncomingStream>()
            val release = CompletableDeferred<Unit>()
            // The handler holds its stream open while the test reads it: a handler that returns completes its stream.
            launch {
                server.handleStreams {
                    incoming.complete(it)
                    release.await()
                }
            }
            lateinit var stream: QuicStream
            var opened = false
            launch {
                stream = client.openStream().getOrElse { throw AssertionError("no stream: $it") }
                assertIs<StreamWrite.Written>(stream.send.write(bytes()))
                opened = true
            }
            pair.runUntil { opened && incoming.isCompleted }
            val receive = receiveOf(incoming.await())
            var first = 0
            launch { receive.read { first = it.remaining() } }
            pair.runUntil { first > 0 }

            var closed = false
            launch {
                assertIs<StreamWrite.Written>(stream.send.writeAndFinish(bytes()))
                client.close(QuicCloseChoice.Graceful())
                closed = true
            }
            pair.runUntil { closed }
            pair.runUntil { server.life() !is QuicConnectionLife.Open }

            val after = ArrayList<String>()
            var done = false
            launch {
                while (!done) {
                    receive.read { it.remaining() }.fold({ after += "data $it" }, {
                        after += it.toString()
                        done = true
                    })
                }
            }
            pair.runUntil { done }
            release.complete(Unit)
            assertEquals(listOf("data $PAYLOAD", ScopedRead.End.PeerFinished.toString()), after)
        }

    /**
     * The server's read takes the stream's last bytes with its FIN, and the next read comes only after the client's
     * close ended the connection: it reads the FIN, not the close (Android API 28 CI: `ConnectionEnded` instead of
     * `PeerFinished`, as the stream's receiving side had ended while the stream itself was still live).
     */
    @Test
    fun aReadAfterTheCloseOfAStreamWhoseFinWasTakenWithItsLastBytesReadsTheFin() =
        VirtualPair.with(options) { pair ->
            val server = pair.accept().public()
            val client = pair.client.connection.public()
            val incoming = CompletableDeferred<QuicIncomingStream>()
            val release = CompletableDeferred<Unit>()
            launch {
                server.handleStreams {
                    incoming.complete(it)
                    release.await()
                }
            }
            var finished = false
            launch {
                val stream = client.openStream().getOrElse { throw AssertionError("no stream: $it") }
                assertIs<StreamWrite.Written>(stream.send.writeAndFinish(bytes()))
                finished = true
            }
            pair.runUntil { finished && incoming.isCompleted }
            pair.runFor(pair.oneWay * 4)
            val receive = receiveOf(incoming.await())
            var first = 0
            launch { receive.read { first = it.remaining() } }
            pair.runUntil { first > 0 }
            assertEquals(PAYLOAD, first, "the one read took every byte, with the FIN behind them")

            var closed = false
            launch {
                client.close(QuicCloseChoice.Graceful())
                closed = true
            }
            pair.runUntil { closed && server.life() !is QuicConnectionLife.Open }

            var after = ""
            launch { after = receive.read { it.remaining() }.fold({ "data $it" }, { it.toString() }) }
            pair.runUntil { after.isNotEmpty() }
            release.complete(Unit)
            assertEquals(ScopedRead.End.PeerFinished.toString(), after)
        }

    private fun receiveOf(incoming: QuicIncomingStream): QuicReceiveStream =
        when (incoming) {
            is QuicStream -> incoming.receive
            is QuicReceiveStream -> incoming
        }

    private fun bytes() =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val PAYLOAD = 600
    }
}
