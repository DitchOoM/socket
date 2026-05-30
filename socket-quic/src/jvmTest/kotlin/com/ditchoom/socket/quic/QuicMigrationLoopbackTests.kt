package com.ditchoom.socket.quic

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
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end active connection migration over loopback (slice 3b harness).
 *
 * The client connects on 127.0.0.1, then [QuicScope.migrate]s to the loopback
 * alias 127.0.0.2 (all of 127.0.0.0/8 is loopback on Linux — no NIC/netem/root).
 * The driver opens a second socket bound to 127.0.0.2, probes it, and on
 * validation switches the active path. We assert migration succeeds and that the
 * stream still round-trips afterwards — proving streams survive the path switch.
 *
 * Runs in CI (needs the built quiche native lib + Linux loopback aliasing); skips
 * cleanly elsewhere.
 */
class QuicMigrationLoopbackTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    /** Verify 127.0.0.2 is bindable (Linux: yes by default; macOS needs an alias) — skip if not. */
    private fun assumeLoopbackAliasBindable() {
        try {
            java.nio.channels.DatagramChannel
                .open()
                .use { it.bind(InetSocketAddress("127.0.0.2", 0)) }
        } catch (e: Exception) {
            assumeTrue("Loopback alias 127.0.0.2 not bindable on this host: ${e.message}", false)
        }
    }

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    @Test
    fun streamSurvivesActiveMigrationToLoopbackAlias() =
        runBlocking(Dispatchers.IO) {
            assumeLoopbackAliasBindable()
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        // Echo loop: mirror every message back until the stream ends.
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

                        val migrationResult = CompletableDeferred<MigrationResult>()
                        val beforeEcho = CompletableDeferred<String>()
                        val afterEcho = CompletableDeferred<String>()

                        val clientJob =
                            launch(Dispatchers.IO) {
                                // Connect on 127.0.0.1 so the peer is reachable from the 127.0.0.2 path too.
                                withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    beforeEcho.complete(stream.echoOnce("before"))

                                    // Active migration to a different loopback source address.
                                    val result = migrate(localHost = "127.0.0.2", localPort = 0)
                                    migrationResult.complete(result)

                                    afterEcho.complete(stream.echoOnce("after"))
                                    stream.close()
                                }
                            }

                        try {
                            assertEquals("before", withTimeout(12.seconds) { beforeEcho.await() })
                            val result = withTimeout(12.seconds) { migrationResult.await() }
                            assertTrue(
                                result is MigrationResult.Succeeded,
                                "expected migration to succeed, got $result",
                            )
                            assertEquals(
                                "after",
                                withTimeout(12.seconds) { afterEcho.await() },
                                "stream did not round-trip after migration",
                            )
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
