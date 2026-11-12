package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * **Establishing a connection does not fail because the server closed right after the handshake.**
 *
 * A client reads how the handshake used a session ticket (`resumption`) once it is established, to hand
 * to the chain verifiers. It used to read that from the connection's published state *later*, so a server
 * whose handler returned at once, and whose CONNECTION_CLOSE reached the client first, made the connect
 * throw `QuicCloseException: resumption is only available while the connection is established` instead of
 * returning the connection the server had accepted (seen on the Linux FFM lane in
 * `acceptsMatchingLeafHash`). The outcome now comes out of the wait for the handshake to settle.
 *
 * Nothing here relies on timing: the client's `wallClock`, which the connect calls between the wait and
 * that read, holds until the driver has torn down, which is after it published `Closed`.
 */
@OptIn(ExperimentalTime::class)
class ResumptionOutcomeSurvivesCloseTests {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val options =
        QuicOptions(
            alpnProtocols = listOf("resumption-after-close"),
            verifyPeer = false,
            idleTimeout = 1.minutes,
            migration = MigrationPolicy.Forbidden,
        )

    /** Counts down when the driver frees its connection, which it does after publishing the terminal state. */
    private class FreeObserver(
        private val delegate: QuicheApi,
        val freed: CountDownLatch,
    ) : QuicheApi by delegate {
        override fun connFree(conn: QuicheConn) {
            delegate.connFree(conn)
            freed.countDown()
        }
    }

    @Test
    fun aServerThatClosesAtOnceStillYieldsTheConnection() =
        failIfWedged(2.minutes, "resumption outcome survives close", Dispatchers.IO) {
            skipOnMissingNativeLib(ResumptionOutcomeSurvivesCloseTests::class) {
                val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, options)
                try {
                    val serverJob = launch { server.connections { } }
                    val freed = CountDownLatch(1)
                    var waitedForTeardown = false
                    val tuning =
                        QuicheDriverTuning(
                            wallClock = {
                                waitedForTeardown = freed.await(20, TimeUnit.SECONDS)
                                Instant.fromEpochSeconds(0)
                            },
                        )
                    val connection =
                        buildJvmQuicConnection(
                            QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), server.port),
                            "localhost",
                            options,
                            TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            20.seconds,
                            FreeObserver(loadQuicheApi(), freed),
                            tuning,
                        )
                    assertTrue(waitedForTeardown, "the server never closed the connection, so the race was not set up")
                    connection.close()
                    serverJob.cancel()
                } finally {
                    server.close()
                }
            }
        }
}
