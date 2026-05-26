package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest

class JvmQuicServerTestSuite : QuicServerTestSuite() {
    // Track every engine created during a test so @AfterTest can close them all.
    // The abstract parent's test methods call serverEngine()/clientEngine() then
    // close the resulting server/connection but never the engine itself — each
    // engine retains a coroutine scope, worker threads, and a native quiche state
    // block. Without this tracker, every test in the suite leaks at least one
    // QuicServerEngine; with ~6 tests across the parent + this class, the pile
    // crosses a threshold on CI that hangs alphabetically-late tests later in
    // the same gradle daemon. JUnit creates a fresh test instance per @Test, so
    // these lists naturally reset per test.
    private val createdServerEngines = mutableListOf<QuicServerEngine>()
    private val createdClientEngines = mutableListOf<QuicEngine>()

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override fun serverEngine(): QuicServerEngine =
        try {
            defaultQuicServerEngine().also { createdServerEngines += it }
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    override fun clientEngine(): QuicEngine =
        try {
            defaultQuicEngine().also { createdClientEngines += it }
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    @AfterTest
    fun closeTrackedEngines() {
        // Swallow close failures — best-effort cleanup. If close throws, the
        // engine is still abandoned; throwing here would mask the real test
        // failure that probably preceded it.
        createdServerEngines.forEach { runCatching { it.close() } }
        createdClientEngines.forEach { runCatching { it.close() } }
        createdServerEngines.clear()
        createdClientEngines.clear()
    }
}
