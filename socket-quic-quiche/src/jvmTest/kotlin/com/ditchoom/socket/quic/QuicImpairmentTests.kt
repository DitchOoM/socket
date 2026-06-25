package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
     * [ImpairingProxy] whose datagram I/O runs on a shared non-blocking [SelectorDatagramRelay]; each
     * direction applies [policy] per datagram once [arm]ed. Delayed ([ImpairAction.ForwardAfter]) and held
     * ([ImpairAction.HoldUntilNext]) datagrams are copied out of the (reused) recv buffer before the next
     * receive overwrites it; duplicates re-send the live buffer (both sends complete before the next
     * receive). `ByteBuffer`/`ByteArray` are fine here — test-only.
     */
    private class DatagramChannelImpairingProxy(
        serverPort: Int,
        private val policy: ImpairmentPolicy,
    ) : ImpairingProxy {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "impair-sched").apply { isDaemon = true } }

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

        private val c2sPump =
            DirectionPump(ImpairDirection.ClientToServer, { relay.writeToServer(it) }, { relay.writeToServerBytes(it) })
        private val s2cPump =
            DirectionPump(ImpairDirection.ServerToClient, { relay.writeToClient(it) }, { relay.writeToClientBytes(it) })

        // The relay owns all DatagramChannel I/O on a single non-blocking Selector loop, making the
        // close-time `IOException: Success` teardown race structurally impossible (see its KDoc).
        // Explicitly typed lateinit + init{} construction: the pump callbacks reference the relay and the
        // relay's ctor references the pumps, so an inferred `val` would form a type-inference cycle.
        private lateinit var relay: SelectorDatagramRelay

        override val proxyPort: Int get() = relay.proxyPort

        init {
            relay =
                SelectorDatagramRelay(
                    serverPort = serverPort,
                    maxDatagram = MAX_DATAGRAM,
                    onClientToServer = { buf, n -> c2sPump.handle(buf, n) },
                    onServerToClient = { buf, n -> s2cPump.handle(buf, n) },
                )
            relay.start()
        }

        override suspend fun close() {
            scheduler.shutdownNow()
            relay.close()
        }

        companion object {
            private const val MAX_DATAGRAM = 2048
        }
    }
}
