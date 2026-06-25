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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * In-process **server-role** QUIC coverage on the Android/JNI runtime — the Android counterpart of
 * the JVM [QuicMigrationLoopbackTests] / K-N [LinuxQuicMigrationLoopbackTests]. Issue #72 Task 2.
 *
 * Until now the Android instrumented suite was client-only ([AndroidQuicConnectivityTests] +
 * [AndroidQuicMigrationTests] both talk to the external docker `quic-echo`). These tests run BOTH
 * ends in one process via [withQuicServer] over loopback — no docker, no root, no [NetworkControl] —
 * so they exercise the Android server path (`JvmQuicServer` on the `commonJvmMain` runtime) for the
 * first time. The server needs a real TLS cert chain + key on disk, supplied by [AndroidTestCerts]
 * (bundled certs extracted to the instrumentation cache dir).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicLoopbackTests {
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

    /** Verify 127.0.0.2 is bindable (Linux/Android: yes by default for 127.0.0.0/8). */
    private fun assumeLoopbackAliasBindable() {
        try {
            java.nio.channels.DatagramChannel
                .open()
                .use { it.bind(InetSocketAddress("127.0.0.2", 0)) }
        } catch (e: Exception) {
            assumeTrue("Loopback alias 127.0.0.2 not bindable: ${e.message}", false)
        }
    }

    /**
     * Core server-role proof: an in-process [withQuicServer] echoes a stream back to an in-process
     * client over loopback. No external server. If this round-trips, the Android server path works.
     */
    @Test
    fun inProcessServerEchoesStream() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
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

                        val echo = CompletableDeferred<String>()
                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    echo.complete(stream.echoOnce("hello-android-server"))
                                    stream.close()
                                }
                            }

                        try {
                            assertEquals(
                                "hello-android-server",
                                withTimeout(12.seconds) { echo.await() },
                                "in-process server did not echo the stream",
                            )
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * Regression guard for the JNI backend's peer connection-close code (the Android-critical half of
     * the fix that added `nConnError` to `JniQuicheApi`). Android always uses the JNI quiche binding;
     * before the fix it inherited the null-returning `connPeerError` default, so a peer's
     * `closeWithError(code)` was silently reported as a clean `NoError` shutdown — defeating the
     * typed-error feature exactly where it matters. Here the in-process server aborts the whole
     * connection with an application code and the client must observe a connection-level
     * [QuicCloseException] carrying [QuicError.ApplicationError]. Mirrors the shared
     * `QuicServerTestSuite.connectionCloseWithErrorIsObservedByPeer` (Android reimplements rather than
     * subclasses the suite).
     */
    @Test
    fun peerConnectionCloseCodeIsObservedOverJni() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverReadFirst = CompletableDeferred<Unit>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    stream.read(5.seconds)
                                    serverReadFirst.complete(Unit)
                                    closeWithError(0xBEEFL)
                                }
                            }
                        delay(100)

                        val observed = CompletableDeferred<QuicError>()
                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    val hello = BufferFactory.Default.allocate(5)
                                    hello.writeString("hello", Charset.UTF8)
                                    hello.resetForRead()
                                    stream.write(hello, 5.seconds)
                                    serverReadFirst.await()
                                    try {
                                        repeat(50) {
                                            val ping = BufferFactory.Default.allocate(4)
                                            ping.writeString("ping", Charset.UTF8)
                                            ping.resetForRead()
                                            stream.write(ping, 5.seconds)
                                            delay(100)
                                        }
                                    } catch (e: QuicCloseException) {
                                        observed.complete(e.quicError)
                                    }
                                }
                            }

                        try {
                            assertEquals(
                                QuicError.ApplicationError(0xBEEFL),
                                withTimeout(12.seconds) { observed.await() },
                                "the JNI backend must surface the peer's CONNECTION_CLOSE application code",
                            )
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * Active migration (RFC 9000 §9) against an in-process server — mirrors
     * [QuicMigrationLoopbackTests.streamSurvivesActiveMigrationToLoopbackAlias] on Android. The
     * client connects on 127.0.0.1, [migrate]s its source to 127.0.0.2, and the stream must still
     * round-trip. Exercises both ends (client JNI migrate + server per-source path routing) in one
     * Android process.
     */
    @Test
    fun streamSurvivesActiveMigrationToLoopbackAlias() =
        runBlocking(Dispatchers.IO) {
            assumeLoopbackAliasBindable()
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
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

                        val migrationResult = CompletableDeferred<MigrationResult>()
                        val beforeEcho = CompletableDeferred<String>()
                        val afterEcho = CompletableDeferred<String>()

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    beforeEcho.complete(stream.echoOnce("before"))

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
