@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import platform.posix.F_OK
import platform.posix.access
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end active connection migration over loopback on Kotlin/Native (Gap 4) — the K/N
 * counterpart of the JVM `QuicMigrationLoopbackTests`.
 *
 * The client connects on 127.0.0.1, then [QuicScope.migrate]s to the loopback alias 127.0.0.2
 * (all of 127.0.0.0/8 is loopback on Linux). The path factory opens a second
 * io_uring socket bound to 127.0.0.2; the in-process K/N server recognises the new source via
 * its per-source recv_info routing and replies follow the peer via `sendInfo.to`. We assert
 * migration succeeds and the stream still round-trips — proving cinterop migration on both ends.
 */
class LinuxQuicMigrationLoopbackTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        // Scoped read (#538): decode inside the block, buffer released on the way out. On K/N there
        // is no collector behind a buffer's native memory at all — an unreleased one leaks outright.
        val resp = read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
        return if (resp is ScopedRead.Data) resp.value else "no_data"
    }

    @Test
    fun streamSurvivesActiveMigrationToLoopbackAlias() =
        runQuicTest {
            withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                // Echo loop: mirror every message back until the stream ends.
                val serverJob =
                    launch {
                        connections {
                            val stream = acceptStream()
                            while (true) {
                                if (stream.read(8.seconds) { stream.write(it, 5.seconds) } !is ScopedRead.Data) break
                            }
                            stream.close()
                        }
                    }

                val migrationResult = CompletableDeferred<MigrationResult>()
                val beforeEcho = CompletableDeferred<String>()
                val afterEcho = CompletableDeferred<String>()

                val clientJob =
                    launch {
                        // Connect on 127.0.0.1 so the peer is reachable from the 127.0.0.2 path too.
                        withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            beforeEcho.complete(stream.echoOnce("before"))

                            val result = migrate(MigrationTarget.LocalAddress("127.0.0.2"))
                            migrationResult.complete(result)

                            afterEcho.complete(stream.echoOnce("after"))
                            stream.close()
                        }
                    }

                try {
                    assertEquals("before", beforeEcho.await())
                    val result = migrationResult.await()
                    assertTrue(result is MigrationResult.Succeeded, "expected migration to succeed, got $result")
                    // The RESOLVED endpoint, not the request: the target named 127.0.0.2 but left the
                    // port ephemeral, so a Succeeded echoing its request would report port 0.
                    assertEquals("127.0.0.2", result.localEndpoint.host, "migration bound a different address")
                    assertTrue(
                        result.localEndpoint.port in 1..65535,
                        "migration must report the ephemeral port it bound, got ${result.localEndpoint}",
                    )
                    assertEquals("after", afterEcho.await(), "stream did not round-trip after migration")
                } finally {
                    clientJob.cancel()
                    serverJob.cancel()
                }
            }
        }
}
