package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * **Two concurrent `close()` calls free a server's native config once** (confinement audit, F3).
 *
 * `close()` used to test a flag and then set it, so two callers (the application and a scope teardown, or
 * two `finally` blocks) could both pass the test and both free the config, the cached addresses and the
 * recv_info cache. The window has no suspension point to hook, so this cannot be made deterministic on the
 * old code: it releases two threads together, many times, and counts the frees. The count is taken at a spy
 * that forwards only the first free, so a failure reports the double free instead of crashing the JVM.
 */
class ServerCloseOnceTests {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val options =
        QuicOptions(
            alpnProtocols = listOf("close-once"),
            verifyPeer = false,
            idleTimeout = 1.minutes,
            migration = MigrationPolicy.Forbidden,
        )

    private class ConfigFreeSpy(
        private val delegate: QuicheApi,
    ) : QuicheApi by delegate {
        val frees = AtomicInteger(0)

        override fun configFree(config: QuicheConfig) {
            if (frees.incrementAndGet() == 1) delegate.configFree(config)
        }
    }

    @Test
    fun twoConcurrentClosersFreeTheConfigOnce() =
        failIfWedged(2.minutes, "server close once", Dispatchers.IO) {
            skipOnMissingNativeLib(ServerCloseOnceTests::class) {
                val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                repeat(ROUNDS) { round ->
                    val spy = ConfigFreeSpy(loadQuicheApi())
                    val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, options, api = spy)
                    spy.frees.set(0)
                    val together = CyclicBarrier(2)
                    coroutineScope {
                        repeat(2) {
                            launch(Dispatchers.IO) {
                                together.await()
                                server.close()
                            }
                        }
                    }
                    assertEquals(1, spy.frees.get(), "round $round: the server's config was freed by both concurrent closers")
                }
            }
        }

    private companion object {
        const val ROUNDS = 300
    }
}
