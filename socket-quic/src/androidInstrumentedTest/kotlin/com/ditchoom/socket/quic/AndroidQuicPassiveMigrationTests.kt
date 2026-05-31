package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **Passive** connection migration (RFC 9000 §9.3 NAT rebinding) on Android — the Android port of
 * the JVM [QuicPassiveMigrationTests] (issue #72 Task 3). The client never calls [QuicScope.migrate];
 * the path's source address changes underneath it, as a NAT rebind would.
 *
 * Both ends run in one Android process via [withQuicServer]. A userspace [RebindingUdpProxy] sits
 * between client and server (no root / netns / tc): the client talks to the proxy, the proxy
 * forwards to the in-process server, and mid-stream the proxy swaps its upstream socket for one with
 * a fresh source port. From the server's view that's the same connection (unchanged DCID) arriving
 * from a new 4-tuple — a passive rebind. We assert the stream still round-trips, proving the Android
 * server keeps the stream alive via per-source recv_info + sendInfo.to routing.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicPassiveMigrationTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun streamSurvivesPassiveSourceRebind() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(25.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    while (true) {
                                        val data = stream.read(8.seconds)
                                        if (data is ReadResult.Data) {
                                            stream.write(data.buffer, 5.seconds)
                                        } else {
                                            break
                                        }
                                    }
                                    stream.close()
                                }
                            }
                        delay(100)

                        val proxy = RebindingUdpProxy(serverPort = port)
                        val beforeEcho = CompletableDeferred<String>()
                        val afterEcho = CompletableDeferred<String>()

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", proxy.proxyPort, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    beforeEcho.complete(stream.echoOnce("before"))

                                    // Passive rebind: the proxy's source toward the server changes,
                                    // with NO client-side migrate(). The server must keep the stream
                                    // alive via per-source recv_info + sendInfo.to routing.
                                    proxy.rebind()

                                    afterEcho.complete(stream.echoOnce("after"))
                                    stream.close()
                                }
                            }

                        try {
                            assertEquals("before", withTimeout(12.seconds) { beforeEcho.await() })
                            assertEquals(
                                "after",
                                withTimeout(15.seconds) { afterEcho.await() },
                                "stream did not round-trip after passive source rebind",
                            )
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                            proxy.close()
                        }
                    }
                }
            }
        }

    /**
     * Minimal userspace UDP forwarder that simulates a NAT rebind. Client ↔ [proxyPort] ↔ server.
     * [rebind] swaps the upstream (server-facing) socket for one with a new source port, so the
     * server sees the same connection arrive from a new 4-tuple. Two daemon threads pump each
     * direction with blocking [DatagramChannel]s (test-only; ByteBuffer is fine in tests).
     */
    private class RebindingUdpProxy(
        private val serverPort: Int,
    ) {
        private val clientChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val proxyPort: Int = (clientChannel.localAddress as InetSocketAddress).port

        @Volatile private var upstream = newUpstream()

        @Volatile private var clientAddr: SocketAddress? = null

        @Volatile private var running = true

        private fun newUpstream(): DatagramChannel = DatagramChannel.open().apply { connect(InetSocketAddress("127.0.0.1", serverPort)) }

        private val clientToServer =
            thread(isDaemon = true, name = "proxy-c2s") {
                val buf = ByteBuffer.allocate(2048)
                while (running) {
                    try {
                        buf.clear()
                        val from = clientChannel.receive(buf) ?: continue
                        clientAddr = from
                        buf.flip()
                        upstream.write(buf)
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }

        private val serverToClient =
            thread(isDaemon = true, name = "proxy-s2c") {
                val buf = ByteBuffer.allocate(2048)
                while (running) {
                    try {
                        buf.clear()
                        val n = upstream.read(buf) // reads the current upstream; throws when swapped/closed
                        if (n > 0) {
                            buf.flip()
                            clientAddr?.let { clientChannel.send(buf, it) }
                        }
                    } catch (_: Exception) {
                        if (!running) break // else: upstream was swapped (closed) — next iteration reads the new one
                    }
                }
            }

        /** Swap the upstream socket for a fresh source port — the NAT rebind. */
        fun rebind() {
            val old = upstream
            upstream = newUpstream()
            old.close() // unblocks serverToClient's read on the old channel
        }

        fun close() {
            running = false
            clientChannel.close()
            upstream.close()
            clientToServer.interrupt()
            serverToClient.interrupt()
        }
    }
}
