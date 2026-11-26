@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.ALLOCATION_SAMPLE
import com.ditchoom.socket.quic.core.testing.PerfGates
import com.ditchoom.socket.quic.core.testing.allocationSamples
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A lent server with [PEERS] live peers converts each peer's address once: what its loop allocates under the peer
 * book's lookups (JFR allocation samples on the server's loop thread with a [PeerBook] frame) is held to [GATE] per
 * datagram echo, with every peer echoing in turn.
 */
class CoreLentServerPeersAllocationTests {
    @Test
    fun aLentServerWithThirtyTwoPeersConvertsNoAddressPerDatagram() =
        runBlocking {
            skipOnMissingNativeLib(CoreLentServerPeersAllocationTests::class) {
                coroutineScope {
                    val on = coreTestOptions.copy(datagrams = DatagramOptions(), sessionStore = QuicSessionStore.NotKept)
                    val demux = CoroutineScope(coroutineContext + Dispatchers.Default)
                    val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(demux)
                    val listener = assertIs<QuicServe.Serving<QuicListener>>(serveQuicOnCore(shared.quic, platformTlsConfig(), on)).value
                    val echoing =
                        launch(Dispatchers.Default) {
                            listener.handleConnections { c ->
                                c.datagrams.receiveEach { c.datagrams.send(it) }
                            }
                        }
                    val loops = LoopSet(1)
                    try {
                        val clients = List(PEERS) { connectCore(shared.localAddress.port, loops = loops, options = on) }
                        val channels = clients.map { it.connection.datagramChannel() }
                        val payload = payloadOf()
                        repeat(WARM_UP) { for (c in channels) echo(c, payload) }
                        val recording = jdk.jfr.Recording()
                        recording.enable(ALLOCATION_SAMPLE).with("throttle", "off").withStackTrace()
                        recording.start()
                        val from = Instant.now()
                        repeat(MEASURED) { for (c in channels) echo(c, payload) }
                        recording.stop()
                        val dump = Files.createTempFile("core-lent-peers", ".jfr")
                        recording.dump(dump)
                        recording.close()
                        val inBook =
                            allocationSamples(dump) { it == SERVER_THREAD }
                                .filter { it.startTime >= from }
                                .filter { e ->
                                    e.stackTrace
                                        ?.frames
                                        .orEmpty()
                                        .any {
                                            it.method.type.name
                                                .endsWith(".PeerBook")
                                        }
                                }.sumOf { it.getLong("weight") }
                                .toDouble() / (MEASURED * PEERS)
                        Files.delete(dump)
                        println("[core lent server, $PEERS peers] the peer book allocates %.1f B/datagram echo".format(inBook))
                        PerfGates.record(
                            CoreLentServerPeersAllocationTests::class.java,
                            "lent-server-peers",
                            "book-bytes",
                            inBook,
                            GATE,
                            "B-per-echo",
                        )
                        assertTrue(
                            inBook <= GATE,
                            "the peer book of a lent server with $PEERS peers: %.1f B/echo (gate %.0f)".format(inBook, GATE),
                        )
                        for (c in clients) c.connection.closeNow(QuicError.NoError)
                    } finally {
                        loops.close()
                        echoing.cancel()
                        listener.close()
                        shared.close()
                    }
                }
            }
        }

    private fun payloadOf(): PlatformBuffer =
        BufferFactory.network().allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
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

    private companion object {
        const val PEERS = 32
        const val PAYLOAD = 64
        const val WARM_UP = 20
        const val MEASURED = 100

        /** No conversion per datagram, with room for one sample's weight spread over the run. */
        const val GATE = 8.0
    }
}
