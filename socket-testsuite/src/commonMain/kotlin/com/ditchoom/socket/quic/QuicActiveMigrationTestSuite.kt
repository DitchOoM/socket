package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * So this suite deliberately has **no `supportsActiveMigration()` escape hatch**, and the two QUIC
 * suites that still had one — `QuicPassiveMigrationTestSuite.supportsPassiveSourceRebind` and
 * `QuicConcurrencySoakTestSuite.supportsConcurrentConnectionsToSameEndpoint` — have since been brought
 * to this shape, because such a hook is exactly how a platform gap turns back into a silent pass. A
 * platform that genuinely cannot migrate must record that as a typed
 * [com.ditchoom.socket.testkit.skip.SkipGate] on its member class, which keeps the absence *visible in
 * the skip inventory* rather than dissolving it into a green run.
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
     * This asserts only that a live client connection does not answer
     * [MigrationResult.Unmoved.Impossible] — nothing about whether the migration then works. Splitting
     * it means a red run names its own cause: this test failing is "the platform has no migration seam
     * wired at all", while [streamSurvivesActiveMigrationToAFreshLocalPort] failing alone is "the seam
     * exists but the migration is broken". Debugging the difference from a single combined failure costs
     * far more than the extra connect this duplicates.
     *
     * [MigrationResult.Unmoved.Impossible] is the family meaning "and never will, whatever the network
     * does", and every member of this suite is a client connection on a platform with a real QUIC engine
     * under the default (permitting) [MigrationPolicy] — so reaching it here is by definition a gap,
     * never a legitimate outcome. A [MigrationResult.Unmoved.Failed] would be a different (and
     * legitimate) story, which is exactly why the assertion names the family and not the whole of
     * `Unmoved`.
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
                                result !is MigrationResult.Unmoved.Impossible,
                                "this platform has a QUIC engine but reports active migration as permanently " +
                                    "impossible — the connection has no UdpChannelFactory wired, so RFC 9000 §9 " +
                                    "migration cannot be attempted at all. Got: $result",
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
                                        try {
                                            stream.writeFully(data.buffer, 5.seconds)
                                        } finally {
                                            // read transfers ownership; write is zero-copy and takes none — without this
                                            // free every echoed chunk leaks, and accumulated echo leaks were the #401
                                            // corruption's primer. writeFully because a QUIC write may be partial.
                                            data.buffer.freeIfNeeded()
                                        }
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
                            // The endpoint reported is the one the platform RESOLVED, not the one asked
                            // for: this call asked for MigrationTarget.FreshLocalEndpoint — no host, no
                            // port — so a Succeeded that echoed its request could name nothing at all.
                            // (It used to: `Succeeded(null, 0)`, on the very platform where an assigned
                            // endpoint is the only thing the caller cannot otherwise learn.)
                            assertTrue(
                                result.localEndpoint.port in 1..65535 && result.localEndpoint.host.isNotBlank(),
                                "migration must report the endpoint it bound, got ${result.localEndpoint}",
                            )
                            // …and the same resolved value reaches pathState. One fact, one place: a
                            // migration whose result and whose path state disagree is two facts.
                            assertEquals(
                                QuicPathState.Migrated(result.localEndpoint),
                                pathState.value,
                                "pathState must carry the same resolved endpoint the result reports",
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

    /**
     * **Migration must keep working past `activeConnectionIdLimit` migrations** — the mobile-reality
     * test (issue #395). A phone on a flapping network migrates over and over on one long-lived
     * connection; nothing in RFC 9000 caps how many times.
     *
     * Two defects made migration N fail while migrations 1..N-1 passed, which is why this test
     * counts to five (past the limit of 4) instead of stopping at one:
     *
     *  1. The driver never retired the path it migrated from, and quiche's path table caps at
     *     `active_conn_id_limit` (= [QuicOptions.activeConnectionIdLimit] = 4) with eviction only
     *     possible for paths holding no DCID — so the **4th** probe was refused outright
     *     ([MigrationResult.Unmoved.Failed.ProbeRejected]).
     *  2. Spare source CIDs were issued exactly once per connection, so the client's RFC 9000 §9.5
     *     retirements freed capacity the peer never refilled and a later migration found no spare
     *     DCID at all ([MigrationResult.Unmoved.Failed.NoSpareConnectionId]).
     *
     * [MigrationResult.Unmoved.Failed.NoSpareConnectionId] between attempts is *transient by
     * design* — after a migration the peer needs a round trip to replenish the CID the client just
     * retired (RFC 9000 §5.1.1) — so the loop retries exactly that answer, bounded, and every other
     * non-success is a hard failure naming which migration died.
     */
    @Test
    fun theConnectionCanKeepMigratingPastTheConnectionIdLimit() =
        runQuicTest(timeout = 120.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    val data = stream.read(8.seconds)
                                    if (data is ReadResult.Data) {
                                        try {
                                            stream.writeFully(data.buffer, 5.seconds)
                                        } finally {
                                            // read transfers ownership; write is zero-copy and takes none — without this
                                            // free every echoed chunk leaks, and accumulated echo leaks were the #401
                                            // corruption's primer. writeFully because a QUIC write may be partial.
                                            data.buffer.freeIfNeeded()
                                        }
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
                            assertEquals("m0", stream.echoOnce("m0", readTimeout = 5.seconds))

                            repeat(5) { i ->
                                val migration = i + 1
                                var result = migrate()
                                var retries = 0
                                while (result is MigrationResult.Unmoved.Failed.NoSpareConnectionId && retries < 40) {
                                    delay(50.milliseconds)
                                    result = migrate()
                                    retries++
                                }
                                assertTrue(
                                    result is MigrationResult.Succeeded,
                                    "migration $migration of 5 failed with $result — a migrated-from path is " +
                                        "never released (its DCID is never retired, so quiche's path table " +
                                        "fills at activeConnectionIdLimit and refuses the probe) and/or " +
                                        "retired CID capacity is never re-issued by the peer; a long-lived " +
                                        "connection on a flapping network loses the ability to migrate (#395)",
                                )
                                assertEquals(
                                    "m$migration",
                                    stream.echoOnce("m$migration", readTimeout = 9.seconds),
                                    "stream did not round-trip after migration $migration",
                                )
                            }
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
