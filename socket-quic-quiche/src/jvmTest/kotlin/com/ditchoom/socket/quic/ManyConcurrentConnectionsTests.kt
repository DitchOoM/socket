package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **More QUIC connections than `Dispatchers.IO` has threads, open at once in one process, all work.**
 *
 * ## The mechanism before the event loops, from thread dumps of this test on `main`
 *
 * Every connection's UDP socket is a `:socket-udp` `NioDatagramChannel`, and its receive waited for the
 * next datagram in `runInterruptible(Dispatchers.IO) { selector.select() }`: one `Dispatchers.IO`
 * thread parked in `KQueue.poll` (epoll on Linux) per waiting socket. `Dispatchers.IO` allows 64 at
 * once. Dumps taken 25 s into a stalled run (macOS, 80 connections):
 *
 * - **JDK 17:** 64 IO threads, every one a client connection's reader in `select()`. The server's
 *   receive-loop reader, which runs on `Dispatchers.IO` too, never got a thread: its task sat in the
 *   queue, so the server read nothing, and every handshake past the first 63 or so timed out (30 s).
 * - **JDK 21:** the same, with 63 client readers plus one IO thread held by the network monitor's
 *   blocking routing-socket `recv` (`FfmRoutingSocketNetworkMonitor`, FFM, JDK 21+ only).
 *
 * The drivers were not the bottleneck: they ran on an elastic `Dispatchers.IO` view (#805) whose
 * threads sat idle. Virtual threads played no part on either JDK. `quicBlockingDispatcher` (virtual
 * threads on JDK 21+) backs only the legacy quiche `NioUdpChannel`, which no production path opens.
 * There were no carrier threads in either dump.
 *
 * Now the sockets wait in the selectors of the QUIC event loops, a handful of threads for any number of
 * sockets. This opens [CONNECTIONS] connections and holds them all open. Only then does each do a round
 * trip, so every socket is waiting at the same time. It fails on `main` on JDK 17 and on JDK 21, and
 * passes with the loops on both.
 */
class ManyConcurrentConnectionsTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("many"),
            verifyPeer = false,
            idleTimeout = 2.minutes,
            migration = MigrationPolicy.Forbidden,
        )

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun moreConnectionsThanIoThreadsEachCompleteARoundTripWhileAllAreOpen() =
        failIfWedged(WATCHDOG, "$CONNECTIONS concurrent QUIC connections", Dispatchers.IO) {
            skipOnMissingNativeLib(ManyConcurrentConnectionsTests::class) {
                withQuicServer(port = 0, host = "127.0.0.1", tlsConfig = tlsConfig, quicOptions = options) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                try {
                                    while (true) {
                                        if (stream.read(OP_DEADLINE) { stream.writeFully(it, OP_DEADLINE) } !is ScopedRead.Data) break
                                    }
                                } finally {
                                    stream.close()
                                }
                            }
                        }
                    val connected = AtomicInteger()
                    val allConnected = CompletableDeferred<Unit>()
                    try {
                        val echoed =
                            List(CONNECTIONS) { index ->
                                async {
                                    withQuicConnection("127.0.0.1", port, options, timeout = OP_DEADLINE) {
                                        val stream = openStream()
                                        if (connected.incrementAndGet() == CONNECTIONS) allConnected.complete(Unit)
                                        // Every connection is up (and its socket waiting) before any of them talks.
                                        allConnected.await()
                                        val text = "connection-$index"
                                        val out = bufferFactory.allocate(text.length)
                                        out.writeString(text, Charset.UTF8)
                                        out.resetForRead()
                                        withTimeout(OP_DEADLINE) {
                                            stream.writeFully(out, OP_DEADLINE)
                                            stream.shutdownSend()
                                            var got = ""
                                            while (true) {
                                                val r = stream.read(OP_DEADLINE) { got += it.readString(it.remaining(), Charset.UTF8) }
                                                if (r !is ScopedRead.Data) break
                                            }
                                            stream.close()
                                            got
                                        }
                                    }
                                }
                            }.awaitAll()
                        assertEquals(List(CONNECTIONS) { "connection-$it" }, echoed)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    private companion object {
        /** More than `Dispatchers.IO`'s 64 threads, with room for the server's own receive loop and handlers. */
        const val CONNECTIONS = 80
        val OP_DEADLINE = 30.seconds
        val WATCHDOG = 3.minutes
    }
}
