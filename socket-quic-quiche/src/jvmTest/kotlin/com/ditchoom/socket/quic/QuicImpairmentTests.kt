package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * JVM network-impairment test — the JVM member of the shared [QuicImpairmentTestSuite].
 *
 * Provides JVM cert resolution (classpath `certs/`), the `UnsatisfiedLinkError → assumeTrue` skip (so a
 * missing JNI/FFM quiche native skips cleanly), and a [DatagramChannelImpairingProxy] built on blocking
 * [DatagramChannel]s — the impairment sibling of [QuicPassiveMigrationTests]' rebinding proxy. The test
 * bodies live in the common suite, guaranteeing parity with the Linux K/N port.
 */
class QuicImpairmentTests : QuicImpairmentTestSuite() {
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

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = DatagramChannelImpairingProxy(serverPort, policy)

    /**
     * [ImpairingProxy] over blocking [DatagramChannel]s. Two daemon threads pump each direction; each
     * applies [policy] per datagram once [arm]ed. Delayed ([ImpairAction.ForwardAfter]) and held
     * ([ImpairAction.HoldUntilNext]) datagrams are copied out of the (reused) recv buffer before the next
     * receive overwrites it; duplicates re-send the live buffer (both sends complete before the next
     * receive). `ByteBuffer`/`ByteArray` are fine here — test-only.
     */
    private class DatagramChannelImpairingProxy(
        serverPort: Int,
        private val policy: ImpairmentPolicy,
    ) : ImpairingProxy {
        private val clientChannel = DatagramChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        override val proxyPort: Int = (clientChannel.localAddress as InetSocketAddress).port
        private val upstream = DatagramChannel.open().apply { connect(InetSocketAddress("127.0.0.1", serverPort)) }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "impair-sched").apply { isDaemon = true } }

        @Volatile private var clientAddr: SocketAddress? = null

        @Volatile private var running = true

        @Volatile private var armed = false

        private val dropped = AtomicInteger(0)
        private val duplicated = AtomicInteger(0)
        private val delayed = AtomicInteger(0)
        private val reordered = AtomicInteger(0)

        override val droppedCount get() = dropped.get()
        override val duplicatedCount get() = duplicated.get()
        override val delayedCount get() = delayed.get()
        override val reorderedCount get() = reordered.get()

        override fun arm() {
            armed = true
        }

        /** Per-direction state + mechanics. [emitLive] sends the (positioned) recv buffer; [emitBytes] sends a copy. */
        private inner class DirectionPump(
            private val direction: ImpairDirection,
            private val emitLive: (ByteBuffer) -> Unit,
            private val emitBytes: (ByteArray) -> Unit,
        ) {
            private val index = AtomicInteger(0)

            @Volatile private var held: ByteArray? = null

            fun handle(
                buf: ByteBuffer,
                n: Int,
            ) {
                if (!armed) {
                    emitLive(buf)
                    return
                }
                val toRelease = held
                held = null
                when (val action = policy.actionFor(direction, index.getAndIncrement())) {
                    ImpairAction.Forward -> emitLive(buf)
                    ImpairAction.Drop -> dropped.incrementAndGet()
                    ImpairAction.ForwardTwice -> {
                        emitLive(buf)
                        buf.rewind()
                        emitLive(buf)
                        duplicated.incrementAndGet()
                    }
                    is ImpairAction.ForwardAfter -> {
                        val copy = buf.toBytes(n)
                        scheduler.schedule({ emitBytes(copy) }, action.delayMs, TimeUnit.MILLISECONDS)
                        delayed.incrementAndGet()
                    }
                    ImpairAction.HoldUntilNext -> {
                        held = buf.toBytes(n)
                        reordered.incrementAndGet()
                    }
                }
                // Release the previously-held datagram AFTER the current one — the structural reorder.
                if (toRelease != null) emitBytes(toRelease)
            }

            private fun ByteBuffer.toBytes(n: Int): ByteArray {
                val a = ByteArray(n)
                duplicate().get(a)
                return a
            }
        }

        private fun sendUpstreamLive(b: ByteBuffer) = guarded { upstream.write(b) }

        private fun sendUpstreamBytes(a: ByteArray) = guarded { upstream.write(ByteBuffer.wrap(a)) }

        private fun sendClientLive(b: ByteBuffer) = guarded { clientAddr?.let { clientChannel.send(b, it) } }

        private fun sendClientBytes(a: ByteArray) = guarded { clientAddr?.let { clientChannel.send(ByteBuffer.wrap(a), it) } }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
                // Best-effort forward — a closed channel during teardown is expected.
            }
        }

        private val c2sPump = DirectionPump(ImpairDirection.ClientToServer, ::sendUpstreamLive, ::sendUpstreamBytes)
        private val s2cPump = DirectionPump(ImpairDirection.ServerToClient, ::sendClientLive, ::sendClientBytes)

        private val clientToServer =
            thread(isDaemon = true, name = "impair-c2s") {
                val buf = ByteBuffer.allocate(MAX_DATAGRAM)
                while (running) {
                    try {
                        buf.clear()
                        val from = clientChannel.receive(buf) ?: continue
                        clientAddr = from
                        buf.flip()
                        c2sPump.handle(buf, buf.remaining())
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }

        private val serverToClient =
            thread(isDaemon = true, name = "impair-s2c") {
                val buf = ByteBuffer.allocate(MAX_DATAGRAM)
                while (running) {
                    try {
                        buf.clear()
                        val n = upstream.read(buf)
                        if (n > 0) {
                            buf.flip()
                            s2cPump.handle(buf, n)
                        }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }

        override suspend fun close() {
            running = false
            scheduler.shutdownNow()
            clientChannel.close()
            upstream.close()
            clientToServer.interrupt()
            serverToClient.interrupt()
        }

        companion object {
            private const val MAX_DATAGRAM = 2048
        }
    }
}
