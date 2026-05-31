package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
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

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.Default.allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    /**
     * Active connection migration (RFC 9000 §9) on the Android/JNI runtime — the real-device
     * counterpart of the JVM/K-N loopback tests, and the first test that actually calls
     * [QuicScope.migrate] on Android (the [AndroidQuicMigrationTests] suite only does passive
     * resilience). [migrate] rebinds a fresh local 4-tuple (new ephemeral source port) to the
     * same docker quic-echo server, exercising the JNI `connNewScid`, client path-routing decode,
     * and `connMigrate`. The echo server has server-side path routing (PR #63) so it validates
     * the new path; we assert migration succeeds and the stream still round-trips.
     */
    @Test
    fun streamSurvivesActiveMigration() =
        runBlocking(Dispatchers.IO) {
            withQuicConnection(serverHost, serverPort, testQuicOptions, timeout = 10.seconds) {
                val stream = openStream()
                assertEquals("before", stream.echoOnce("before"))

                val result = migrate(localHost = null, localPort = 0)
                assertIs<MigrationResult.Succeeded>(result)

                assertEquals("after", stream.echoOnce("after"), "stream did not round-trip after migration")
                stream.close()
            }
        }
}
