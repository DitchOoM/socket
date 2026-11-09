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
 * Before the event loops, every connection's UDP socket waited for its next datagram in a `select()` of
 * its own, parked on a `Dispatchers.IO` thread. `Dispatchers.IO` has 64 threads. With 64 client
 * connections and the server's receive loop open in one process, every one of them was taken by a parked
 * select, and nothing else that needed IO (the connections' own reads, the server's handlers) could run:
 * every round trip timed out. Measured on the owned-loop spike: 60 connections worked, 64 stalled, and 64
 * worked with `-Dkotlinx.coroutines.io.parallelism=256`.
 *
 * Now the sockets wait in the selectors of the QUIC event loops, a handful of threads for any number of
 * sockets. This opens [CONNECTIONS] connections, holds them all open, and only then has each do a round
 * trip, so every socket is waiting at the same time.
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
