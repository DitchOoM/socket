package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Android QUIC connectivity tests against the local `quic-echo` docker
 * harness service (`test-harness/quic-echo/`).
 *
 * CI runs this via `.github/workflows/android_integration.yaml`, which
 * brings up the docker harness on the host (`14433/udp` published on
 * loopback) before the emulator boots. That published port is the
 * [EndpointOrigin.DockerContract] fallback, and an emulator reaches it
 * through its built-in `10.0.2.2` alias — neither half of which exists
 * on a physical device, where the address is computed on the host and
 * carried down instead. See [HarnessEndpoints].
 *
 * For local dev:
 *   docker compose -f test-harness/docker-compose.yml up -d --wait quic-echo
 *   ./gradlew :socket-quic-quiche:connectedDebugAndroidTest
 *   docker compose -f test-harness/docker-compose.yml down -v
 *
 * `:socket-quic-quiche:androidQuicIntegrationTest` is the other path
 * (it starts a host-side `QuicEchoTestServer` JVM process, works out
 * which host address this device can reach it on, probes that address,
 * and passes it down) and is the only one that works on real hardware.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicConnectivityTests {
    private lateinit var server: HarnessEndpoint

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /**
     * Resolve the harness address and prove something answers there, or record a typed skip.
     *
     * This used to be `try { connect() } catch (_: Throwable) { assumeTrue(…, false) }`, which
     * reported every one of those causes — no harness started, no route from this device, a broken
     * JNI native — as the same invisible green tick. Each now names itself, and `recordSkip`'s gate
     * decides which of them is allowed to be a skip at all.
     */
    @Before
    fun checkServerReachable() {
        val harness = HarnessEndpoints.quicEcho.addressOrSkip(AndroidQuicConnectivityTests::class)
        server = harness.endpoint
        try {
            runBlocking(Dispatchers.IO) {
                withQuicConnection(server.host, server.port, testQuicOptions, timeout = 5.seconds) {}
            }
        } catch (t: Throwable) {
            // A native that will not load is not an unreachable harness, and the fix for it is not a
            // routing fix. Distinguishing them is the whole point of the sealed SkipReason — and this
            // lane packages the .so into the test APK, so it is an error here, not a skip.
            if (generateSequence(t) { it.cause }.any { it is UnsatisfiedLinkError }) throw t
            harness.skipUnanswered(AndroidQuicConnectivityTests::class, t)
        }
    }

    @Test
    fun connectToLocalServer() =
        runBlocking(Dispatchers.IO) {
            withQuicConnection(server.host, server.port, testQuicOptions, timeout = 10.seconds) {
                // If we reach here, handshake completed successfully
            }
        }

    @Test
    fun echoOverQuic() =
        runBlocking(Dispatchers.IO) {
            withQuicConnection(server.host, server.port, testQuicOptions, timeout = 10.seconds) {
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
            withQuicConnection(server.host, server.port, testQuicOptions, timeout = 10.seconds) {
                val stream = openStream()
                assertEquals("before", stream.echoOnce("before"))

                val result = migrate(MigrationTarget.FreshLocalEndpoint)
                assertIs<MigrationResult.Succeeded>(result)
                // FreshLocalEndpoint names no host and no port, so the reported endpoint can only have
                // come from the socket the platform actually bound.
                assertTrue(result.localEndpoint.port in 1..65535, "got ${result.localEndpoint}")

                assertEquals("after", stream.echoOnce("after"), "stream did not round-trip after migration")
                stream.close()
            }
        }
}
