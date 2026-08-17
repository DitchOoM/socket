package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **active** connection-migration test suite (RFC 9000 §9) — the client deliberately moves its
 * own local endpoint via [QuicScope.migrate], as opposed to [QuicPassiveMigrationTestSuite] where the
 * source address changes underneath it.
 *
 * ## Why this is a shared suite and not another per-platform test
 * Active migration was tested only by platform-private files — `QuicMigrationLoopbackTests` (JVM) and
 * `LinuxQuicMigrationLoopbackTests` (Linux K/N). Nothing required an Apple counterpart to exist, so
 * when Apple shipped with `udpChannelFactory = null` the gap was invisible: there was no red test,
 * only an absent one. A platform's inability to migrate has to *fail*, not *not-exist*.
 *
 * So this suite deliberately has **no `supportsActiveMigration()` escape hatch**. The passive suite
 * has one (`supportsPassiveSourceRebind`) and that hook is exactly how a platform gap turns back into
 * a silent pass. A platform that genuinely cannot migrate must record that as a typed
 * [com.ditchoom.socket.testkit.SkipGate] on its member class, which keeps the absence *visible in the
 * skip inventory* rather than dissolving it into a green run.
 *
 * ## Migrating to a fresh ephemeral port, not a loopback alias
 * The two pre-existing tests migrate to `127.0.0.2`, which only works because all of `127.0.0.0/8` is
 * loopback **on Linux**. macOS configures `127.0.0.1` alone, so that trick needs a privileged
 * `ifconfig lo0 alias` and cannot run hermetically in CI.
 *
 * A QUIC path is the 4-tuple, so moving to a fresh **local port** on the same address is a genuine
 * new path: quiche must open the new socket, PATH_CHALLENGE it, have the peer echo PATH_RESPONSE, and
 * only then switch. That exercises the whole probe→validate→migrate machine with no aliases, no
 * privileges, and no platform-specific addressing — which is what lets every target share one body.
 */
abstract class QuicActiveMigrationTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /**
     * Platform hook for skip-on-missing-native-lib semantics, matching the other suites: the JVM
     * member converts `UnsatisfiedLinkError` into a skip; native targets inherit the no-op, because a
     * cinterop binding is fixed at compile time and any failure there is a real failure.
     */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private suspend fun QuicByteStream.echoOnce(
        payload: String,
        readTimeout: Duration,
    ): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        write(out, 5.seconds)
        val resp = read(readTimeout)
        return if (resp is ReadResult.Data) resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8) else "no_data"
    }

    /**
     * The **capability** half, kept deliberately separate from the behavioural test below.
     *
     * This asserts only that a live client connection does not answer [MigrationResult.Unsupported] —
     * nothing about whether the migration then works. Splitting it means a red run names its own
     * cause: this test failing is "the platform has no migration seam wired at all", while
     * [streamSurvivesActiveMigrationToAFreshLocalPort] failing alone is "the seam exists but the
     * migration is broken". Debugging the difference from a single combined failure costs far more
     * than the extra connect this duplicates.
     *
     * [MigrationResult.Unsupported] is documented as "this platform/connection does not support active
     * migration", and every member of this suite is a client connection on a platform with a real QUIC
     * engine — so reaching it here is by definition a gap, never a legitimate outcome.
     */
    @Test
    fun migrateReportsACapabilityNotAnAbsence() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val serverJob = launch { connections { acceptStream() } }
                    try {
                        // Inline client, per the passive suite's lesson: a per-op withTimeout throws a
                        // CancellationException, which inside a child launch would silently cancel it
                        // and hang an await() until the whole-test budget, masking the real cause.
                        withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                            val result = migrate()
                            assertTrue(
                                result !is MigrationResult.Unsupported,
                                "this platform has a QUIC engine but reports active migration as unsupported — " +
                                    "the connection has no UdpChannelFactory wired, so RFC 9000 §9 migration " +
                                    "cannot be attempted at all. Got: $result",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The **behavioural** half: a stream must still carry data after the connection has moved to a new
     * local endpoint.
     *
     * `migrate()` with defaults means "a fresh ephemeral socket on the current default interface",
     * which is precisely what an auto-migration on a network change issues — so this is the real
     * production call shape, not a test-only variant.
     */
    @Test
    fun streamSurvivesActiveMigrationToAFreshLocalPort() =
        // Generous budget: connect + echo + probe/validate/migrate + a post-migration echo, where the
        // migration costs at least a path-validation round trip and may absorb a retransmit.
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val serverJob =
                        launch {
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

                    try {
                        withQuicConnection("127.0.0.1", port, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            assertEquals("before", stream.echoOnce("before", readTimeout = 5.seconds))

                            val result = migrate()
                            assertTrue(
                                result is MigrationResult.Succeeded,
                                "expected active migration to a fresh ephemeral local port to succeed, got $result",
                            )

                            // Bounded well under the 10s idle timeout so a connection that never
                            // recovers fails promptly with this message instead of hanging.
                            assertEquals(
                                "after",
                                stream.echoOnce("after", readTimeout = 9.seconds),
                                "stream did not round-trip after active migration",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
