package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread

/**
 * JVM passive NAT-rebind migration test — the JVM member of the shared [QuicPassiveMigrationTestSuite].
 *
 * Provides the JVM cert resolution (classpath `certs/`), the `UnsatisfiedLinkError → assumeTrue`
 * skip (so a missing JNI/FFM quiche native skips cleanly), and a [RebindingProxy] built on blocking
 * [DatagramChannel]s. The test body itself lives in the common suite, guaranteeing parity with the
 * Linux K/N port.
 */
class QuicPassiveMigrationTests : QuicPassiveMigrationTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    override fun createRebindingProxy(serverPort: Int): RebindingProxy = DatagramChannelRebindingProxy(serverPort)

    /**
     * [RebindingProxy] over blocking [DatagramChannel]s. Two daemon threads pump each direction;
     * [rebind] swaps the upstream channel for one with a new source port and closes the old one,
     * which unblocks the server-to-client thread's read. ByteBuffer is fine here — test-only.
     */
    private class DatagramChannelRebindingProxy(
        private val serverPort: Int,
    ) : RebindingProxy {
        private val clientChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        override val proxyPort: Int = (clientChannel.localAddress as InetSocketAddress).port

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

        override fun rebind() {
            val old = upstream
            upstream = newUpstream()
            old.close() // unblocks serverToClient's read on the old channel
        }

        override suspend fun close() {
            running = false
            clientChannel.close()
            upstream.close()
            clientToServer.interrupt()
            serverToClient.interrupt()
        }
    }
}
