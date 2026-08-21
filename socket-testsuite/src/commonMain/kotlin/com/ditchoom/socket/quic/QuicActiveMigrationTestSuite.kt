package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

    /**
     * Options for the two byte-continuity tests below, which are transfers rather than single echoes and
     * are budgeted like [QuicConcurrencySoakTestSuite]'s: a **scaled** idle timeout, well above the
     * (also scaled) per-read deadlines.
     *
     * The fixed 10 s above is right for a test that exchanges one message, and wrong for one that keeps a
     * pipeline full across a path switch. A migration's in-flight packets can be lost and re-sent under
     * QUIC's exponential PTO backoff, which is seconds of legitimate stall on a runner whose cores are
     * busy — measured here as an 8 s gap on Apple K/N while a full JVM suite ran alongside, on a
     * connection that then recovered. Scaling the transport budget and the read deadlines by the same
     * [testTimeScale] gives a loaded runner proportionally more wall-clock while preserving every timing
     * relationship: a stream that has genuinely lost bytes still never catches up, and still fails.
     */
    private val transferQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds.scaled,
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

    /**
     * **Byte-continuity across a migration**: everything the stream delivers must be an exact, in-order
     * prefix of everything written into it, *while the path moves underneath it*.
     *
     * ## Why "the migration succeeded" was never enough
     * The two tests above assert that [migrate] answers [MigrationResult.Succeeded], that the resolved
     * endpoint reaches [QuicScope.pathState], and that one echo round-trips afterwards. Issue #393 passed
     * every one of those: on a 124-minute on-device Android handoff the **connection** stayed healthy for
     * 101 minutes after the stream died — exchanging keepalives the whole time — because a read that timed
     * out with a `StreamRecv` in flight had its already-delivered bytes freed and discarded, punching a
     * permanent hole in the stream's receive offset. "The connection is alive" is precisely the assertion
     * that failed to catch it. The property that catches it is the one asserted here: the bytes.
     *
     * ## The invariant is a prefix, not per-read equality
     * A QUIC `read()` returns *whatever the transport has*, which is not the framing the writer used: two
     * writes can arrive coalesced in one chunk, one write can arrive split across two, and a read whose
     * deadline expired mid-delivery hands its salvaged bytes to the *next* read. So an
     * `assertEquals(payloadN, chunkN)` per read is not a stricter test — it is a **wrong** one, red on a
     * perfectly healthy stream. [StreamContinuityLedger] instead accumulates both sides and asserts, on
     * every chunk, that the received bytes are an exact in-order prefix of the sent bytes. That catches a
     * dropped byte (#393), a reordered one, and a duplicated one, and it is indifferent to framing.
     *
     * ## Why the traffic is provably in flight across the switch
     * A migration on an idle stream proves nothing — #393's stream was idle-ish and looked fine until the
     * next read. Two structures put real data in the air across the switch, without a single wall-clock
     * wait:
     *
     *  - **A pipeline window.** The writer may run at most [PIPELINE_WINDOW] chunks ahead of what has been
     *    echoed back. So whenever the writer is gated, `sent > received` — i.e. bytes are *definitionally*
     *    in flight — and the writer cannot outrun the reader and finish early.
     *  - **A rendezvous, not a sleep.** The writer stops at chunk [MIGRATE_AT_CHUNK] and hands off to the
     *    migrator, which only then calls [migrate]; the writer resumes the moment the migrator has
     *    committed to migrating. Reaching that barrier at all proves at least
     *    `MIGRATE_AT_CHUNK - PIPELINE_WINDOW` chunks have already round-tripped (the stream is live), and
     *    the remaining ~5/6 of the payload is written strictly afterwards, with the migration in flight.
     */
    @Test
    fun streamBytesRemainAnInOrderPrefixAcrossAMigration() =
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                val serverLedger = EchoOwnershipLedger()
                val data = StreamContinuityLedger()
                val trace = MigrationTrace()
                trace.explaining("streamBytesRemainAnInOrderPrefixAcrossAMigration") {
                    // coroutineScope so the echo coroutines are provably finished before assertNoLeaks reads
                    // the ledger — asserting between serverJob.cancel() and the join would race a release.
                    coroutineScope {
                        withQuicServer(
                            port = 0,
                            tlsConfig = testTlsConfig(),
                            quicOptions = transferQuicOptions.copy(trace = trace.capture("server")),
                        ) {
                            val serverJob = launch { connections { echoOneStream(serverLedger) } }
                            try {
                                withQuicConnection(
                                    "127.0.0.1",
                                    port,
                                    transferQuicOptions.copy(trace = trace.capture("client")),
                                    timeout = 10.seconds.scaled,
                                ) {
                                    val stream = openStream()
                                    // Warm-up round, and not only for liveness: openStream() reserves a
                                    // stream id locally without putting anything on the wire, so a read that
                                    // races the first write asks quiche about a stream it has never heard of
                                    // and the driver reports that error as a clean ReadResult.End. Writing
                                    // chunk 0 and draining it makes the stream real on both sides before the
                                    // concurrent phase, so an End below is a genuine terminal verdict.
                                    stream.sendChunk(0, data)
                                    stream.drainUntilCaughtUp(data, "warm-up")

                                    // The two halves of the writer↔migrator rendezvous. Deferreds, not flags:
                                    // an await is a suspension the scheduler resolves, where a polled flag
                                    // would be a wall-clock race dressed up as a loop.
                                    val writerAtBarrier = CompletableDeferred<Unit>()
                                    val migrationCommitted = CompletableDeferred<Unit>()

                                    coroutineScope {
                                        launch {
                                            while (data.receivedCount < CONTINUITY_CHUNKS * CHUNK_BYTES) {
                                                stream.receiveInto(data, STREAM_READ_DEADLINE, "migration echo reader")
                                            }
                                        }
                                        launch {
                                            for (i in 1 until CONTINUITY_CHUNKS) {
                                                // Pipeline window: never more than PIPELINE_WINDOW chunks ahead
                                                // of what has come back, so the writer can neither run the
                                                // stream dry nor finish the payload before the path moves.
                                                awaitUntil(
                                                    PIPELINE_STALL_BUDGET,
                                                    "the echo stalled: chunk $i could not be sent because only " +
                                                        "${data.progress()} — the stream stopped carrying bytes",
                                                ) { data.receivedCount >= (i - PIPELINE_WINDOW) * CHUNK_BYTES }
                                                if (i == MIGRATE_AT_CHUNK) {
                                                    writerAtBarrier.complete(Unit)
                                                    migrationCommitted.await()
                                                }
                                                stream.sendChunk(i, data)
                                            }
                                        }

                                        writerAtBarrier.await()
                                        // Reaching the barrier already proves the stream round-trips (the
                                        // window gate could not have released otherwise), so the migration
                                        // needs no separate liveness probe to be non-vacuous.
                                        migrationCommitted.complete(Unit)
                                        val result = migrate()
                                        assertTrue(
                                            result is MigrationResult.Succeeded,
                                            "expected the mid-transfer migration to succeed (${data.progress()}), got $result",
                                        )
                                        assertTrue(
                                            pathState.value is QuicPathState.Migrated,
                                            "pathState must report the connection moved, got ${pathState.value}",
                                        )
                                    }

                                    data.assertEverySentByteCameBack("streamBytesRemainAnInOrderPrefixAcrossAMigration")
                                    stream.close()
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
                serverLedger.assertNoLeaks("streamBytesRemainAnInOrderPrefixAcrossAMigration")
            }
        }

    /**
     * **A migration that lands on an in-flight read must not cost a byte** — the end-to-end analogue of
     * issue #393.
     *
     * The field failure needed two things at once: a stream read that unwound while the driver still had
     * a `StreamRecv` outstanding for it, and a migration to make that likely. This drives both, in two
     * phases that are deliberately different in kind:
     *
     *  1. **A read parked across the whole migration** — *deterministic*. The stream is fully drained
     *     first, so the read has nothing to return and provably cannot complete until this test writes
     *     again; the migration therefore runs, start to finish, with that read parked. The payload is
     *     written only afterwards, so it is delivered into a read that has been sitting across a path
     *     switch. A moved path that orphans its parked reader dies here.
     *  2. **A deadline sweep under driver pressure** — *opportunistic, and labelled as such*. Writes are
     *     issued back to back with no pipeline window, so the driver's command queue carries a real
     *     backlog, and each write is followed by a read on a deadline of a few milliseconds cycled from
     *     [READ_DEADLINE_LADDER]. Reads therefore unwind while echoes are still outstanding.
     *
     *     Whether any one of those unwinds lands in the *actual* #393 window — the microseconds between a
     *     `StreamRecv` being enqueued and the driver answering it with data — is not something the public
     *     API can force, and this suite should not pretend otherwise. Measured on an idle loopback JVM:
     *     the driver answers fast enough that a timed-out read is essentially always parked on the data
     *     signal, where there is no delivery to lose, and reverting the salvage fix leaves this suite
     *     green. `StreamReadCancellationTests` is what pins that race, with a gated stub driver holding
     *     the interleaving still. What this phase adds is the end-to-end half — a busy driver, real path
     *     switches, and the standing guarantee that a timeout is never allowed to cost a byte, which is
     *     also the shape a destructive-timeout regression would take (RFC_READ_TIMEOUT_CONTRACT §3, axis
     *     2: a timeout must abort the read, never the stream).
     *
     * A read timing out is an expected outcome throughout, never a failure. The failure is a byte that
     * does not come back.
     *
     * Both phases migrate, so this also walks the connection through path switches with reads in flight,
     * retrying the one answer that is transient by design
     * ([MigrationResult.Unmoved.Failed.NoSpareConnectionId] — the peer needs a round trip to replenish the
     * CID the last migration retired, RFC 9000 §5.1.1), exactly as
     * [theConnectionCanKeepMigratingPastTheConnectionIdLimit] does.
     */
    @Test
    fun aMigrationAcrossAnInFlightReadLosesNoBytes() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val serverLedger = EchoOwnershipLedger()
                val trace = MigrationTrace()
                val data = StreamContinuityLedger()
                trace.explaining("aMigrationAcrossAnInFlightReadLosesNoBytes") {
                    coroutineScope {
                        withQuicServer(
                            port = 0,
                            tlsConfig = testTlsConfig(),
                            quicOptions = transferQuicOptions.copy(trace = trace.capture("server")),
                        ) {
                            val serverJob = launch { connections { echoOneStream(serverLedger) } }
                            try {
                                withQuicConnection(
                                    "127.0.0.1",
                                    port,
                                    transferQuicOptions.copy(trace = trace.capture("client")),
                                    timeout = 10.seconds.scaled,
                                ) {
                                    val stream = openStream()
                                    // Warm-up round: openStream() reserves a stream id locally without putting
                                    // anything on the wire, so a read that races the first write asks quiche
                                    // about a stream it has never heard of and the driver reports that error
                                    // as a clean ReadResult.End. Draining one chunk makes the stream real on
                                    // both sides, so an End below is a genuine terminal verdict — and proves
                                    // the stream carries bytes before any deadline is under test.
                                    stream.sendChunk(0, data)
                                    stream.drainUntilCaughtUp(data, "warm-up")

                                    // ---- phase 1: a read parked across the entire migration ----
                                    coroutineScope {
                                        val readStarted = CompletableDeferred<Unit>()
                                        val parkedRead =
                                            launch {
                                                readStarted.complete(Unit)
                                                stream.receiveIntoToleratingTimeout(data, PARKED_READ_DEADLINE)
                                            }
                                        // Ordering, not timing: migrate() is entered only once the reading
                                        // coroutine has been scheduled — and with the stream drained, nothing
                                        // can wake that read until the write below, so the whole path switch
                                        // happens underneath it.
                                        readStarted.await()
                                        val result = migrateReplenishingConnectionIds()
                                        assertTrue(
                                            result is MigrationResult.Succeeded,
                                            "the migration under a parked read failed with $result",
                                        )
                                        // Written on the NEW path, into a read issued on the old one.
                                        stream.sendChunk(1, data)
                                        parkedRead.join()
                                    }
                                    stream.drainUntilCaughtUp(data, "after a migration under a parked read")

                                    // ---- phase 2: short deadlines against a backlogged driver ----
                                    coroutineScope {
                                        launch {
                                            // Mid-sweep, so reads are unwinding both before and after the
                                            // path moves rather than only around one edge of it.
                                            awaitUntil(
                                                PIPELINE_STALL_BUDGET,
                                                "the sweep stalled before its migration: ${data.progress()}",
                                            ) { data.sentCount >= (2 + PRESSURE_CHUNKS / 2) * CHUNK_BYTES }
                                            val result = migrateReplenishingConnectionIds()
                                            assertTrue(
                                                result is MigrationResult.Succeeded,
                                                "the migration under the deadline sweep failed with $result",
                                            )
                                        }
                                        for (i in 0 until PRESSURE_CHUNKS) {
                                            // No pipeline window here on purpose: back-to-back writes leave a
                                            // backlog in the driver's command queue, so a StreamRecv enqueued
                                            // behind them is answered late — widening the window in which a
                                            // read's deadline can expire with a delivery still outstanding.
                                            stream.sendChunk(2 + i, data)
                                            stream.receiveIntoToleratingTimeout(
                                                data,
                                                READ_DEADLINE_LADDER[i % READ_DEADLINE_LADDER.size].scaled,
                                            )
                                        }
                                    }
                                    // Every byte written during the sweep must still arrive, in order. If a
                                    // read that unwound mid-delivery ever loses the bytes quiche had already
                                    // handed it — the #393 defect — this is where the stream's permanent hole
                                    // surfaces: the drain never catches up, and it names the shortfall rather
                                    // than hanging to the whole-test budget.
                                    stream.drainUntilCaughtUp(data, "after the deadline sweep")

                                    data.assertEverySentByteCameBack("aMigrationAcrossAnInFlightReadLosesNoBytes")
                                    stream.close()
                                }
                            } finally {
                                serverJob.cancel()
                            }
                        }
                    }
                }
                serverLedger.assertNoLeaks("aMigrationAcrossAnInFlightReadLosesNoBytes")
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    /**
     * Server side: echo one accepted stream back to the client until it ends.
     *
     * Every read buffer is handed to [EchoOwnershipLedger.took] the instant `read()` returns it and
     * released in a `finally`, because a QUIC `read()` transfers ownership while `write()` takes none: an
     * echo loop that writes a buffer and walks away leaks it, permanently starving the driver's
     * `streamReadPool` — the allocator primer behind #401. The ledger is what makes a dropped release
     * *visible* by name instead of fatal three suites later. [writeFully], not `write`, because a QUIC
     * stream write returns a possibly-partial count at a flow-control boundary and a truncated echo would
     * read here as lost bytes — indicting the driver for the harness's bug.
     */
    private suspend fun QuicScope.echoOneStream(ledger: EchoOwnershipLedger) {
        val stream = acceptStream()
        try {
            while (true) {
                val data = stream.read(STREAM_READ_DEADLINE)
                if (data !is ReadResult.Data) break
                val receipt = ledger.took(data.buffer)
                try {
                    stream.writeFully(data.buffer, STREAM_WRITE_DEADLINE)
                } finally {
                    ledger.release(receipt)
                }
            }
        } finally {
            stream.close()
        }
    }

    /**
     * Write chunk [index] and record it as sent.
     *
     * The recording happens **before** the write, not after: the ledger's invariant is that received bytes
     * are a prefix of sent bytes, and on a fast loopback the echo can be recorded by the reader before a
     * post-write recording would have run — which would report the driver's correct behaviour as an
     * overrun.
     */
    private suspend fun QuicByteStream.sendChunk(
        index: Int,
        data: StreamContinuityLedger,
    ) {
        val payload = chunkPayload(index)
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        data.recordSent(payload.encodeToByteArray())
        try {
            writeFully(out, STREAM_WRITE_DEADLINE)
        } finally {
            // A deterministic() buffer is native-backed: the writer owns it and the write took no
            // ownership, so it is ours to release once the bytes are on the wire.
            out.freeNativeMemory()
        }
    }

    /** Read one chunk into [data]. A deadline that expires here is a failure — nothing should be idle. */
    private suspend fun QuicByteStream.receiveInto(
        data: StreamContinuityLedger,
        deadline: Duration,
        context: String,
    ) {
        val result =
            try {
                read(deadline)
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("$context: read($deadline) timed out with ${data.progress()}", e)
            }
        data.recordReceived(result, context)
    }

    /**
     * Read one chunk into [data], **tolerating** a deadline that expires mid-delivery.
     *
     * That expiry is the point of the exercise, not a failure: quiche may already have written bytes into
     * this read's buffer when the deadline unwinds it, and the contract is that those bytes reach the next
     * `read()` instead of being freed with the buffer. The caller asserts the consequence (nothing is
     * lost) rather than the mechanism, so this returns quietly either way.
     */
    private suspend fun QuicByteStream.receiveIntoToleratingTimeout(
        data: StreamContinuityLedger,
        deadline: Duration,
    ) {
        val result =
            try {
                read(deadline)
            } catch (_: TimeoutCancellationException) {
                return
            }
        data.recordReceived(result, "in-flight read across a migration")
    }

    /** Read until every byte written so far has come back, in order. */
    private suspend fun QuicByteStream.drainUntilCaughtUp(
        data: StreamContinuityLedger,
        context: String,
    ) {
        while (data.receivedCount < data.sentCount) {
            receiveInto(data, STREAM_READ_DEADLINE, "$context: draining the echo")
        }
    }

    /**
     * [migrate], retrying the one non-success that a *previous* migration makes transient by design.
     *
     * After a migration the client has retired a connection ID and the peer needs a round trip to issue a
     * replacement (RFC 9000 §5.1.1), so back-to-back migrations can legitimately find no spare DCID.
     * Every other answer is returned unchanged for the caller to assert on — retrying a
     * [MigrationResult.Unmoved.Impossible] would only convert a capability gap into a timeout.
     */
    private suspend fun QuicScope.migrateReplenishingConnectionIds(): MigrationResult {
        var result = migrate()
        var retries = 0
        while (result is MigrationResult.Unmoved.Failed.NoSpareConnectionId && retries < CID_REPLENISH_RETRIES) {
            delay(CID_REPLENISH_BACKOFF)
            result = migrate()
            retries++
        }
        return result
    }

    /**
     * Chunk [index]'s payload: a zero-padded index label padded out with a per-chunk filler letter.
     *
     * Self-describing on purpose — a divergence report then names *which* chunk and which byte of it went
     * missing, instead of showing an anonymous run of identical bytes in which a lost 64-byte hole and a
     * reordering look the same. ASCII only, so one character is one byte and ledger offsets read directly
     * as stream offsets.
     */
    private fun chunkPayload(index: Int): String = "chunk-${index.toString().padStart(4, '0')}-".padEnd(CHUNK_BYTES, 'a' + index % 26)

    private companion object {
        /** Bytes per chunk. Small enough that a chunk never fragments, big enough to be legible in a dump. */
        private const val CHUNK_BYTES = 64

        /** Chunks written across the migration in [streamBytesRemainAnInOrderPrefixAcrossAMigration]. */
        private const val CONTINUITY_CHUNKS = 48

        /** How far the writer may run ahead of the echo. While it is gated, `sent > received` — bytes are in flight. */
        private const val PIPELINE_WINDOW = 4

        /**
         * The chunk at which the writer hands off to the migrator. Past [PIPELINE_WINDOW] (so reaching it
         * proves `MIGRATE_AT_CHUNK - PIPELINE_WINDOW` chunks already round-tripped) and well short of
         * [CONTINUITY_CHUNKS] (so most of the payload is still written with the migration in flight).
         */
        private const val MIGRATE_AT_CHUNK = 8

        /**
         * Per-read/write deadlines for the transfer tests, scaled in step with
         * [transferQuicOptions]'s idle timeout so the relationship between them holds at any scale: a
         * read gives up well before the transport declares the connection idle, so a stalled stream is
         * reported as a stalled stream and never as an idle timeout.
         */
        private val STREAM_READ_DEADLINE = 15.seconds.scaled
        private val STREAM_WRITE_DEADLINE = 10.seconds.scaled

        /**
         * Backstop for a coroutine waiting on the echo's progress. Deliberately longer than
         * [STREAM_READ_DEADLINE] so that a stalled stream is reported by the *reader* — which names the
         * byte counts and the deadline it waited on — rather than by whichever waiter happened to expire
         * first. Scaled, since it only bounds how long a loaded runner may lag.
         */
        private val PIPELINE_STALL_BUDGET = 20.seconds.scaled

        /** Write+read rounds in the deadline sweep. Bounded work: each round costs one write and one short read. */
        private const val PRESSURE_CHUNKS = 128

        /**
         * Read deadlines cycled through the sweep, all of them at or under the driver's own scheduling
         * granularity so a read regularly unwinds with a delivery still outstanding. Scaled, because on a
         * loaded runner the whole delivery window slides out with them; scaling moves which rung is the
         * interesting one, it never turns an assertion off.
         */
        private val READ_DEADLINE_LADDER =
            listOf(1.milliseconds, 2.milliseconds, 4.milliseconds, 8.milliseconds)

        /**
         * Deadline for the read parked across a whole migration. Long enough that a loopback path
         * validation cannot outlast it (so the read is provably still parked when the migration returns),
         * short enough to stay under the connection's idle timeout.
         */
        private val PARKED_READ_DEADLINE = 5.seconds.scaled

        /** Bounded retry for a CID the peer has not re-issued yet; same shape as the #395 loop above. */
        private const val CID_REPLENISH_RETRIES = 40
        private val CID_REPLENISH_BACKOFF = 50.milliseconds
    }
}

/**
 * Trace capture for the two byte-continuity tests, whose job is to make a lost byte name its own cause.
 *
 * ## Why the tests must carry this, rather than a trace being read after the fact
 * `STREAM_LOSS` ([com.ditchoom.socket.testkit.trace.TraceEvent.StreamLoss], the instrumentation added
 * with #414) reaches a trace through `driver.recorder?.streamLoss(...)` — three call sites, every one of
 * them null-safe. [com.ditchoom.socket.quic.QuicOptions.trace] defaults to `null`, so on a connection
 * that did not opt in there is no recorder and all three are no-ops.
 *
 * That matters because of how this trace is meant to be read. A run that loses bytes either carries
 * `STREAM_LOSS` lines naming the stream, the byte count and the cause, **or** carries none — and the
 * second answer is the informative one: it puts the loss upstream of `DriverStreamAdapter` altogether
 * (quiche's own receive path, or `recv_info`), because the adapter records every chunk it releases.
 * Without capture a run prints zero `STREAM_LOSS` lines *whatever the cause was*, and that reading is
 * not merely unproven but unavailable — an absence that means "the feature was switched off" would be
 * read as an absence that means "the adapter is innocent". Capture is what makes the absence evidence.
 *
 * ## Both sides, and labelled
 * The client ledger compares what the client received against what the client sent, so a server that
 * drops bytes while echoing fails it in a way byte-identical to a client that drops them on delivery.
 * Both connections therefore record onto this one sink behind a `client`/`server` prefix: aggregate
 * diagnostics rather than per-connection replay, which is exactly what [QuicTraceCapture]'s
 * single-sink constructor is for.
 *
 * ## A digest, not the trace
 * A full trace of these tests is thousands of `DGRAM_OUT`/`DGRAM_IN` lines carrying payload hex — on the
 * order of a megabyte, which buries the handful of lines that decide the question and is unreadable in a
 * CI log anyway. [digest] keeps every `STREAM_LOSS`, `PATH_STATE`, `STATE` and `ERROR` line verbatim and
 * **counts** everything else by kind, so what was dropped is stated rather than silently truncated.
 * Filtering is on the typed event, so a discarded datagram never pays to be rendered.
 */
private class MigrationTrace {
    // UNLIMITED + trySend: the recorder emits from driver coroutines (non-suspending, and off-thread on
    // K/N as well as JVM), so a channel is the simplest thread-safe collector that needs no atomics.
    // Counting dropped events by pushing a short token avoids a cross-platform atomic counter entirely.
    private val lines = Channel<String>(Channel.UNLIMITED)

    /** Capture for one side of the connection; [role] prefixes its lines so the two stay separable. */
    fun capture(role: String): QuicTraceCapture =
        QuicTraceCapture(
            sink =
                TraceSink { event ->
                    when (event) {
                        is TraceEvent.StreamLoss,
                        is TraceEvent.PathState,
                        is TraceEvent.State,
                        is TraceEvent.Error,
                        -> lines.trySend("$role $event")
                        // Everything else is volume, not evidence: recorded as a short key so the digest
                        // can report how much it dropped without holding any of it.
                        else -> lines.trySend(DROPPED + role + "/" + summarize(event))
                    }
                },
        )

    /**
     * The bucket a dropped event is counted under.
     *
     * Datagrams carry their **channel port**, which is what makes the counts diagnostic rather than
     * decorative. A migration gives the client a second `UdpChannel`, so post-migration traffic is
     * counted under a different port from pre-migration traffic — and "which channel stopped carrying
     * datagrams, and did its peer keep talking to the old one" is precisely the question a stream that
     * stalls after a successful migration raises. A single total cannot answer it: on the run that
     * prompted this, `DgramOut=80, DgramIn=30` was consistent with a quiet link and with one side
     * shouting into a dead 4-tuple, and nothing distinguished them. Keyed per role as well, because on
     * one shared sink the two endpoints' counts otherwise add into a number that hides the asymmetry.
     *
     * ⚠️ The port is the **channel's own key**, not a source or destination: `RecordingUdpChannel`
     * records the `PathKey` it was wrapped with (`QuicheDriver`'s `recorder?.wrap(channel, key…)`) for
     * receives *and* sends, ignoring the `dest` passed to `send`. So `@:60826` reads "on the channel
     * keyed 60826", and an arrow notation here would assert a direction the recorder never captured.
     *
     * Port only, not the whole [com.ditchoom.socket.testkit.trace.TracePath]: these tests are loopback,
     * so the address is constant and the port is the entire distinction.
     */
    private fun summarize(event: TraceEvent): String =
        when (event) {
            is TraceEvent.DgramOut -> "DgramOut@:${event.path?.port ?: "-"}"
            is TraceEvent.DgramIn -> "DgramIn@:${event.path?.port ?: "-"}"
            else -> event::class.simpleName ?: "unknown"
        }

    /**
     * Drain what was captured and render the verdict. Closes the channel — call once, on the failure
     * path only.
     */
    fun digest(): String {
        lines.close()
        val kept = ArrayList<String>()
        val dropped = LinkedHashMap<String, Int>()
        while (true) {
            val line = lines.tryReceive().getOrNull() ?: break
            if (line.startsWith(DROPPED)) {
                val kind = line.substring(DROPPED.length)
                dropped[kind] = (dropped[kind] ?: 0) + 1
            } else {
                kept.add(line)
            }
        }
        val losses = kept.filter { it.contains(" STREAM_LOSS ") }
        val total = kept.size + dropped.values.sum()

        val verdict =
            when {
                losses.isNotEmpty() ->
                    "VERDICT: ${losses.size} STREAM_LOSS line(s) — DriverStreamAdapter released bytes it had " +
                        "accepted. The cause token on each line names which of the three windows it was " +
                        "(ReaderGone / QueueClosed / SalvageUnclaimed)."
                total > 0 ->
                    "VERDICT: capture was ACTIVE ($total events recorded) and emitted NO STREAM_LOSS. The " +
                        "adapter released nothing it had accepted — so IF this failure is a missing byte, it " +
                        "was lost UPSTREAM of DriverStreamAdapter (quiche's receive path, or recv_info), not " +
                        "on the read path #414 instrumented. For any other failure this line is only context."
                // The guard that keeps the line above honest: zero events means the wiring did not take,
                // and then "no STREAM_LOSS" is a statement about this harness, not about the driver.
                else ->
                    "VERDICT: NO trace events were recorded at all — capture did not run, so this trace says " +
                        "NOTHING about whether STREAM_LOSS fired. Treat the absence as missing data, not as " +
                        "evidence, and fix the wiring before reading anything into it."
            }

        return buildString {
            appendLine(verdict)
            // Capture order, deliberately not sorted by timestamp: each connection records against its
            // own clock origin (v1 carries no connection id), so client and server nanos are not
            // comparable and a merged sort would invent an interleaving that never happened.
            appendLine("--- captured (STREAM_LOSS / PATH_STATE / STATE / ERROR, both sides, in capture order) ---")
            if (kept.isEmpty()) appendLine("(none)") else kept.forEach { appendLine(it) }
            if (dropped.isNotEmpty()) {
                appendLine(
                    "--- dropped from this digest (volume, not evidence): " +
                        dropped.entries.joinToString(", ") { "${it.key}=${it.value}" } + " ---",
                )
            }
        }
    }

    /**
     * Run [block], folding [digest] into the failure when it throws. The trace is discarded on the
     * passing path, so capture costs a green run nothing but the recording itself.
     */
    suspend fun explaining(
        label: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("$label failed.\n${describeCause(t)}${digest()}", t)
        }
    }

    /**
     * Flatten the cause chain into our own message text.
     *
     * Whether a `Caused by:` is printed at all depends on the **reporter**, not the platform, so relying
     * on exception chaining is relying on which lane happened to run. Measured on one CI run of this very
     * suite: the macOS-ARM64 integration lane (K/Native, GoogleTest-style runner) printed
     * `Caused by: TimeoutCancellationException`, while the iOS-simulator lane (K/Native, Gradle reporter)
     * printed this wrapper's message followed by 24 frames of `at …` and **no cause anywhere in the
     * log** — the underlying assertion, the one naming the byte offset or the stalled byte counts, was
     * simply gone. Wrapping therefore has to carry the message itself or it destroys evidence on some
     * lanes: the #401 lesson applied to the diagnostic instead of the code under test.
     *
     * Depth-bounded rather than trusting the chain to terminate: a cause cycle would otherwise hang the
     * failure path, turning a red test into a timed-out one.
     */
    private fun describeCause(t: Throwable): String =
        buildString {
            appendLine("--- cause (flattened: some lanes' reporters print no `Caused by:`) ---")
            var e: Throwable? = t
            var depth = 0
            while (e != null && depth < MAX_CAUSE_DEPTH) {
                append(if (depth == 0) "" else "caused by: ")
                appendLine("${e::class.simpleName ?: "Throwable"}: ${e.message}")
                e = e.cause
                depth++
            }
        }

    private companion object {
        /** Marks a kind token rather than a kept line. A space keeps it unambiguous against `v1 …` lines. */
        private const val DROPPED = "~dropped "

        /** Bound on the flattened cause chain — a cycle must not hang the failure path. */
        private const val MAX_CAUSE_DEPTH = 5
    }
}

/**
 * The sent/received byte ledger for one stream, asserting the property issue #393 broke: **everything
 * received is an exact, in-order prefix of everything sent.**
 *
 * ## Why a prefix and not per-read equality
 * A QUIC `read()` hands back whatever the transport currently holds, which need not be the framing the
 * writer used: two writes can arrive coalesced, one write can arrive split, and a read whose deadline
 * expired mid-delivery hands its salvaged bytes to the *next* read. So `assertEquals(payloadN, chunkN)`
 * is not a stricter assertion, it is a wrong one — red on a healthy stream, and worse, it teaches the
 * next reader to weaken it. Accumulating both sides and comparing as a prefix is framing-independent and
 * still catches every corruption that matters: a dropped byte (offset shifts, so the very next byte
 * mismatches), a reordered one, and a duplicated one (received overruns sent).
 *
 * The invariant only holds because [recordSent] runs *before* its write is issued, so `sent` is always a
 * superset of anything the peer could possibly have echoed.
 *
 * ## Failure reports carry the evidence
 * A divergence prints the stream offset, the surrounding bytes of both sides in hex and printable form,
 * and the chunk that broke it. That is the lesson of #401: a bare `MalformedInputException: Input
 * length = 1` from decoding corrupt bytes discards the very evidence needed to diagnose it, and the
 * failures this ledger exists to catch are rare enough that "reproducible if you are lucky" is no
 * diagnosis at all.
 *
 * **Thread-safe.** The writer, the reader, and (in the ladder test) a migrating main coroutine all touch
 * it from different threads on K/N as well as JVM. Same spin-lock idiom as [EchoOwnershipLedger]: a
 * [Mutex] via `tryLock`, because every entry point is non-suspending and each critical section is a
 * short list append.
 */
private class StreamContinuityLedger {
    private val listLock = Mutex()
    private val sent = ArrayList<Byte>()
    private val received = ArrayList<Byte>()

    private inline fun <T> locked(block: () -> T): T {
        while (!listLock.tryLock()) {
            // Spin: the critical section is a short list append, contention is rare.
        }
        try {
            return block()
        } finally {
            listLock.unlock()
        }
    }

    /** Record [payload] as written. Call **before** issuing the write — see the class docs. */
    fun recordSent(payload: ByteArray) {
        locked { for (b in payload) sent.add(b) }
    }

    /**
     * Fold one [ReadResult] into the ledger, asserting the prefix invariant and releasing the buffer.
     *
     * The release is this method's job rather than the caller's precisely because the invariant check can
     * throw: a `freeIfNeeded()` written after the assertion at every call site is a leak waiting for the
     * first red run, and accumulated echo leaks were #401's primer.
     */
    fun recordReceived(
        result: ReadResult,
        context: String,
    ) {
        if (result !is ReadResult.Data) {
            throw AssertionError(
                "$context: the stream ended before its bytes did — got $result with ${progress()}. A " +
                    "connection that migrates must not terminate the streams riding it (#393).",
            )
        }
        val chunk = ByteArray(result.buffer.remaining())
        try {
            for (i in chunk.indices) chunk[i] = result.buffer.readByte()
        } finally {
            // read() transfers ownership to us; write() takes none. Freeing here, in a finally, is what
            // keeps a red assertion from also being a leak.
            result.buffer.freeIfNeeded()
        }
        val failure = locked { appendLocked(chunk) }
        if (failure != null) throw AssertionError("$context: $failure")
    }

    /** Bytes written so far. */
    val sentCount: Int get() = locked { sent.size }

    /** Bytes received so far. */
    val receivedCount: Int get() = locked { received.size }

    /** One-line state for a failure message. */
    fun progress(): String = locked { "${received.size} of ${sent.size} bytes echoed back" }

    /**
     * Assert the stream gave back everything it was given. The prefix invariant has been checked on every
     * chunk, so equal counts here mean byte-identical content — the shortfall is the only thing left to
     * report, and it is #393's exact signature: a stream that is still open, on a connection that is still
     * healthy, permanently missing the bytes a timed-out read discarded.
     */
    fun assertEverySentByteCameBack(context: String) {
        val missing = locked { sent.size - received.size }
        if (missing == 0) return
        throw AssertionError(
            "$context: $missing byte(s) written to the stream never came back (${progress()}) — the " +
                "connection survived the migration but the stream lost data across it (#393).",
        )
    }

    /** Append [chunk], returning a rendered failure when it breaks the prefix invariant. Caller holds the lock. */
    private fun appendLocked(chunk: ByteArray): String? {
        for (b in chunk) {
            val offset = received.size
            if (offset >= sent.size) {
                return render(
                    "received more bytes than were ever written — the stream duplicated or reordered data",
                    offset,
                    chunk,
                )
            }
            if (sent[offset] != b) {
                return render(
                    "received 0x${b.hex()} at stream offset $offset where 0x${sent[offset].hex()} was written — " +
                        "bytes were lost or reordered",
                    offset,
                    chunk,
                )
            }
            received.add(b)
        }
        return null
    }

    /** Caller holds the lock. */
    private fun render(
        headline: String,
        offset: Int,
        chunk: ByteArray,
    ): String {
        val from = maxOf(0, offset - DUMP_WINDOW)
        val to = minOf(sent.size, offset + DUMP_WINDOW)
        val expected = ByteArray(to - from) { sent[from + it] }
        return listOf(
            headline,
            "  written  [$from,$to) : ${expected.toHex()}",
            "  written  [$from,$to) : |${expected.toPrintable()}|",
            "  this read chunk      : ${chunk.toHex()}",
            "  this read chunk      : |${chunk.toPrintable()}|",
            "  totals               : ${received.size} received / ${sent.size} written",
        ).joinToString("\n")
    }

    private fun Byte.hex(): String {
        val v = toInt() and 0xFF
        return "${HEX[v shr 4]}${HEX[v and 0xF]}"
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { it.hex() }

    private fun ByteArray.toPrintable(): String {
        val sb = StringBuilder(size)
        for (b in this) sb.append(if (b >= 0x20 && b < 0x7F) b.toInt().toChar() else '.')
        return sb.toString()
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        /** Bytes of context either side of a divergence — two chunks' worth, so the neighbours are legible. */
        private const val DUMP_WINDOW = 32
    }
}
