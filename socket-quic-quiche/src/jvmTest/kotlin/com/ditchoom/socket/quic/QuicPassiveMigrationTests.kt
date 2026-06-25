package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM passive NAT-rebind migration test — the JVM member of the shared [QuicPassiveMigrationTestSuite].
 *
 * Provides the JVM cert resolution (classpath `certs/`), the `UnsatisfiedLinkError → assumeTrue`
 * skip (so a missing JNI/FFM quiche native skips cleanly), and a [RebindingProxy] backed by a shared
 * non-blocking [SelectorDatagramRelay]. The test body itself lives in the common suite, guaranteeing
 * parity with the Linux K/N port.
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
     * [RebindingProxy] backed by a non-blocking [SelectorDatagramRelay]: both directions are pure
     * pass-through; [rebind] swaps the upstream for a fresh source port. Because the relay closes the old
     * upstream on its own select() thread (never while blocked in a read), neither rebind nor teardown can
     * hit the `IOException: Success` close race — see [SelectorDatagramRelay].
     */
    private class DatagramChannelRebindingProxy(
        serverPort: Int,
    ) : RebindingProxy {
        // lateinit + init{}: the pass-through callbacks reference the relay, so an inferred `val` whose
        // type comes from those same callbacks would be a type-inference cycle.
        private lateinit var relay: SelectorDatagramRelay

        override val proxyPort: Int get() = relay.proxyPort

        init {
            relay =
                SelectorDatagramRelay(
                    serverPort = serverPort,
                    maxDatagram = 2048,
                    onClientToServer = { buf, _ -> relay.writeToServer(buf) },
                    onServerToClient = { buf, _ -> relay.writeToClient(buf) },
                )
            relay.start()
        }

        override fun rebind() = relay.rebindUpstream()

        override suspend fun close() = relay.close()
    }
}
