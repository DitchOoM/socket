package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A server builds one `recv_info` per path a peer is seen on, not one per datagram, however many
 * peers it serves.
 *
 * Counted, not timed: the server runs over a [QuicheApi] that counts `quiche_recv_info_new`, and the
 * count is read around rounds in which every already-established peer sends again from the address
 * it has always used. Nothing about those rounds is new to the server, so nothing may be built.
 */
class ServerRecvInfoPerPeerTests {
    private class CountingRecvInfoApi(
        private val delegate: QuicheApi,
    ) : QuicheApi by delegate {
        val recvInfosBuilt = AtomicInteger()

        override fun recvInfoNew(
            fromAddr: Long,
            fromAddrLen: Int,
            toAddr: Long,
            toAddrLen: Int,
        ): QuicheRecvInfo {
            recvInfosBuilt.incrementAndGet()
            return delegate.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
        }
    }

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    // A peer waits its turn without sending; however slow the machine, that wait is not an idle timeout.
    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.minutes)

    @Test
    fun establishedPeersSendingAgainBuildNoRecvInfo() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(ServerRecvInfoPerPeerTests::class) {
                val api = CountingRecvInfoApi(loadQuicheApi())
                val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, options, api = api)
                try {
                    val serverJob = launch { server.connections { echoUntilEnd(acceptStream()) } }
                    withTimeout(5.minutes) {
                        val established = List(PEERS) { CompletableDeferred<Unit>() }
                        val turns = List(PEERS) { Channel<CompletableDeferred<Unit>>(Channel.RENDEZVOUS) }
                        val peers =
                            List(PEERS) { peer ->
                                launch {
                                    client(server.port) {
                                        val stream = openStream()
                                        stream.echo("first")
                                        established[peer].complete(Unit)
                                        for (turn in turns[peer]) {
                                            stream.echo("again")
                                            turn.complete(Unit)
                                        }
                                        stream.shutdownSend()
                                    }
                                }
                            }
                        established.awaitAll()

                        val before = api.recvInfosBuilt.get()
                        repeat(ROUNDS) {
                            for (turn in turns) CompletableDeferred<Unit>().also { turn.send(it) }.await()
                        }
                        val built = api.recvInfosBuilt.get() - before

                        turns.forEach { it.close() }
                        peers.forEach { it.join() }
                        assertEquals(
                            0,
                            built,
                            "$PEERS established peers each sent $ROUNDS more times from an unchanged address, " +
                                "and the server built $built recv_infos for them",
                        )
                    }
                    serverJob.cancelAndJoin()
                } finally {
                    server.close()
                }
            }
        }

    private suspend fun <R> client(
        port: Int,
        block: suspend QuicScope.() -> R,
    ): R =
        commonJvmWithQuicConnection(
            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
            serverName = "localhost",
            quicOptions = options,
            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
            timeout = 60.seconds,
            block = block,
        )

    private suspend fun echoUntilEnd(stream: QuicByteStream) {
        try {
            while (stream.read(30.seconds) { stream.writeFully(it, 10.seconds) } is ScopedRead.Data) {
                // Echo until the peer finishes.
            }
        } finally {
            stream.close()
        }
    }

    private suspend fun QuicByteStream.echo(text: String) {
        val out = BufferFactory.deterministic().allocate(text.length)
        try {
            out.writeString(text, Charset.UTF8)
            out.resetForRead()
            writeFully(out, 10.seconds)
        } finally {
            out.freeIfNeeded()
        }
        var got = ""
        while (got.length < text.length) {
            got += assertIs<ScopedRead.Data<String>>(read(30.seconds) { it.readString(it.remaining(), Charset.UTF8) }).value
        }
        assertEquals(text, got)
    }

    private companion object {
        /** More peers than a server-wide table of 256 holds. */
        const val PEERS = 300
        const val ROUNDS = 3
    }
}
