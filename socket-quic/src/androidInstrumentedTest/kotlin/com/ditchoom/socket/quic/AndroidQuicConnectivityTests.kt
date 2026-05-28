package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Android QUIC connectivity tests against the local `quic-echo` docker
 * harness service (`test-harness/quic-echo/`).
 *
 * CI runs this via `.github/workflows/android_integration.yaml`, which
 * brings up the docker harness on the host (`14433/udp` published on
 * loopback) before the emulator boots. The emulator reaches the host
 * via its built-in `10.0.2.2` alias.
 *
 * For local dev:
 *   docker compose -f test-harness/docker-compose.yml up -d --wait quic-echo
 *   ./gradlew :socket-quic:connectedDebugAndroidTest
 *   docker compose -f test-harness/docker-compose.yml down -v
 *
 * The legacy `:socket-quic:androidQuicIntegrationTest` Gradle task is
 * still wired (starts a host-side `QuicEchoTestServer` JVM process)
 * for envs without Docker, but the docker harness is the canonical
 * path going forward — it matches the server used by every other
 * QUIC test target.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicConnectivityTests {
    // 10.0.2.2 is the Android emulator's alias for the host's loopback,
    // and 14433/udp is where the docker-compose `quic-echo` service binds
    // on the host (test-harness/docker-compose.yml). Must stay in sync with
    // test-harness/harness.env QUIC_ECHO_PORT — mirrored manually here
    // because androidInstrumentedTest doesn't depend on commonTest, so
    // the generated QuicHarnessConfig isn't visible from this source set.
    private val serverHost = "10.0.2.2"
    private val serverPort = 14433

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /** Skip the entire class if the test server isn't reachable. */
    @Before
    fun checkServerReachable() {
        try {
            runBlocking(Dispatchers.IO) {
                withQuicConnection(serverHost, serverPort, testQuicOptions, timeout = 5.seconds) {}
            }
        } catch (_: Throwable) {
            assumeTrue(
                "QUIC test server not reachable at $serverHost:$serverPort — " +
                    "start the docker harness (`docker compose -f test-harness/docker-compose.yml " +
                    "up -d --wait quic-echo`) or fall back to the legacy " +
                    "`:socket-quic:androidQuicIntegrationTest` Gradle task.",
                false,
            )
        }
    }

    @Test
    fun connectToLocalServer() =
        runBlocking(Dispatchers.IO) {
            withQuicConnection(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                // If we reach here, handshake completed successfully
            }
        }

    @Test
    fun echoOverQuic() =
        runBlocking(Dispatchers.IO) {
            withQuicConnection(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                val stream = openStream()
                val sendBuf = BufferFactory.Default.allocate(5)
                sendBuf.writeString("hello", Charset.UTF8)
                sendBuf.resetForRead()
                stream.write(sendBuf, 5.seconds)

                val response = stream.read(5.seconds)
                assertIs<com.ditchoom.buffer.flow.ReadResult.Data>(response)

                stream.close()
            }
        }
}
