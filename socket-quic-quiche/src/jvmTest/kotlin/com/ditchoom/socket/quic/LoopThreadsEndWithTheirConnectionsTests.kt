@file:OptIn(InternalDatagramLoopApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.udp.DatagramEventLoopGroup
import com.ditchoom.socket.udp.InternalDatagramLoopApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **A process that has closed every QUIC connection and server is left with no loop thread.**
 *
 * The loops are lifecycle-managed rather than started once and kept: a loop's thread starts when a
 * connection is placed on it and ends a linger after the last one is gone. A thread that outlived its
 * connections would be a leak per test in this suite and a permanent cost in an application that used
 * QUIC once.
 *
 * Runs on a group of its own, so whatever other tests leave on the shared group cannot mask or fake a
 * result, with a short linger. Two rounds: the second proves a group whose threads all ended starts again.
 */
class LoopThreadsEndWithTheirConnectionsTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("leak"), verifyPeer = false, idleTimeout = 1.minutes, migration = MigrationPolicy.Forbidden)

    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun everyLoopThreadEndsOnceTheServerAndItsConnectionsAreClosed() =
        failIfWedged(2.minutes, "loop thread lifetimes", Dispatchers.IO) {
            skipOnMissingNativeLib(LoopThreadsEndWithTheirConnectionsTests::class) {
                val group = DatagramEventLoopGroup(size = 4, namePrefix = PREFIX, linger = 100.milliseconds)
                val tuning = QuicheDriverTuning(driverContext = LoopPlacement(group))
                repeat(2) { round ->
                    var busiest = 0
                    val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, options, tuning)
                    try {
                        val serverJob =
                            launch {
                                server.connections {
                                    val stream = acceptStream()
                                    while (stream.read(10.seconds) { stream.writeFully(it, 5.seconds) } is ScopedRead.Data) Unit
                                    stream.close()
                                }
                            }
                        List(CONNECTIONS) { i ->
                            async {
                                commonJvmWithQuicConnection(
                                    endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), server.port),
                                    serverName = "localhost",
                                    quicOptions = options,
                                    connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                                    timeout = 20.seconds,
                                    tuning = tuning,
                                ) {
                                    val stream = openStream()
                                    val out = bufferFactory.allocate(16)
                                    out.writeString("round$round-$i", Charset.UTF8)
                                    out.resetForRead()
                                    stream.writeFully(out, 5.seconds)
                                    stream.read(5.seconds) { }
                                    synchronized(this@LoopThreadsEndWithTheirConnectionsTests) {
                                        busiest = maxOf(busiest, group.runningThreads().size)
                                    }
                                    stream.shutdownSend()
                                    stream.close()
                                }
                            }
                        }.awaitAll()
                        serverJob.cancel()
                    } finally {
                        server.close()
                    }
                    assertTrue(busiest > 0, "round $round: no loop of the group ran, so nothing was placed on it")
                    awaitNoLoopThreads(group, "round $round")
                }
            }
        }

    private fun awaitNoLoopThreads(
        group: DatagramEventLoopGroup,
        label: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (group.runningThreads().isEmpty() && liveLoopThreads().isEmpty()) return
            Thread.sleep(20)
        }
        val alive = liveLoopThreads()
        throw AssertionError(
            "$label: ${alive.size} loop thread(s) still alive 10 s after every connection and the server closed " +
                "(linger 100 ms):\n" + alive.joinToString("\n") { t -> "${t.name}\n    at " + t.stackTrace.joinToString("\n    at ") },
        )
    }

    private fun liveLoopThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("$PREFIX-") }

    private companion object {
        const val PREFIX = "leak-check-loop"
        const val CONNECTIONS = 6
    }
}
