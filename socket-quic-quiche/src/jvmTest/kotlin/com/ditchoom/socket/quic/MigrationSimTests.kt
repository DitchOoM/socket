package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * #449 layer 3: **migration, deterministically.**
 *
 * Before this, `SemanticSim` — the only Tier-B harness — passed
 * [MigrationCapability.BackendCannotMigrate], so no simulated connection had ever moved paths and the
 * whole CID/path lifecycle had zero deterministic coverage. That is why every defect in the family was
 * found on a phone. See [withMigrationSim] for how the harness is built, and
 * `PathValidationVirtualClockTests` for the measurement that makes it tractable.
 */
class MigrationSimTests {
    @Test
    fun aClientMigratesToAFreshPathUnderVirtualTime() =
        runTest {
            try {
                withMigrationSim(
                    testScope = this,
                    seed = 917_324L,
                    // The asymmetry that matters for #445: the path being left is slower than the one
                    // being joined, so the new path's packets can overtake the old path's in-flight ones.
                    primaryImpairment = PathImpairment(latency = 80.milliseconds),
                    probeImpairment = { PathImpairment(latency = 35.milliseconds) },
                ) {
                    awaitSpareDcids()
                    val result = migrate().await()
                    assertTrue(
                        result is MigrationResult.Succeeded,
                        "the client failed to migrate in the sim: $result",
                    )
                    assertTrue(
                        clientPaths().isNotEmpty(),
                        "no probe path was ever opened — the migration reported success without moving",
                    )
                    val probe = pipe.pathAt(clientPaths().last())
                    assertTrue(
                        probe.stats.sentToServer > 0 && probe.stats.sentToClient > 0,
                        "the new path carried no traffic in both directions " +
                            "(toServer=${probe.stats.sentToServer} toClient=${probe.stats.sentToClient}) — " +
                            "a migration that reports success but never uses the path is not a migration",
                    )
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
            }
        }

    /**
     * **#447, reproduced from a blackholed probe path alone — no device, no network, no test seam.**
     *
     * The field condition is an unanswered PATH_CHALLENGE, routine on cellular, which previously
     * needed either a phone or [UnansweredProbeDatagramChannel] (a deliberate hole punched through the
     * production [QuicPortBinding.Shared] surface) to produce. Here it is one [PathImpairment] on the
     * path the driver is about to probe: the path swallows everything both ways, the challenge goes
     * unanswered, and the driver runs out its RFC 9000 §8.2.4 budget.
     *
     * ## Why this asserts the mechanism, not a spare-CID count
     * `available_dcids` is moved by at least three independent effects at once — the probe consuming
     * one, the peer issuing a replacement once our RETIRE_CONNECTION_ID lands, and quiche's own
     * `recv()` refill relinking spares into paths that have none. Measured here: after one abandoned
     * probe the count sits flat at 2 while the audit shows the server *did* receive the retirement and
     * issue a replacement (`retiredScidsSeen` 1, `newScid` 4). A test asserting "the count went back
     * up" would read that flat 2 as a failure — and could read a wrong fix as a success.
     *
     * So the assertion is what #447 actually changed, and what is unambiguous: **the abandoned path
     * retired the destination CID it was holding.** Pre-#447 the driver wrote that sequence number into
     * native scratch and never read it, so its abandon exit had no value to forget and made no retire
     * call at all — mutated back, this goes red with an empty `retireCalls`.
     *
     * The scenario costs 0ms of wall clock; the ~3s abandon budget is virtual.
     */
    @Test
    fun anAbandonedProbeRetiresTheConnectionIdItHeld() =
        runTest {
            try {
                withMigrationSim(
                    testScope = this,
                    seed = 77_001L,
                    probeImpairment = { PathImpairment(blackhole = true) },
                ) {
                    awaitSpareDcids()
                    assertTrue(clientAvailableDcids() > 0, "the peer never issued a spare CID — nothing could be leaked")

                    val result = withTimeout(120.seconds) { migrate().await() }
                    assertTrue(
                        result !is MigrationResult.Succeeded,
                        "the probe path is a blackhole, so this migration must not have succeeded: $result. " +
                            "If it did, the impairment is not reaching the path and the test proves nothing.",
                    )

                    val probe = pipe.pathAt(clientPaths().last())
                    assertTrue(
                        probe.stats.blackholed > 0,
                        "no datagram was ever blackholed on the probe path — the PATH_CHALLENGE was not " +
                            "actually suppressed, so nothing here reproduces #447",
                    )

                    assertEquals(
                        1,
                        clientAudit.retireCalls.size,
                        "an abandoned probe must retire the destination CID quiche linked to its path, " +
                            "exactly once. Calls seen: ${clientAudit.retireCalls}. Pre-#447 the driver never " +
                            "read that sequence number, so this list is empty and every failed handoff costs " +
                            "one spare CID for the rest of the connection's life.",
                    )
                    val (seq, rc) = clientAudit.retireCalls.single()
                    assertEquals(0, rc, "quiche refused the retirement of dcid seq $seq (rc=$rc)")
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
            }
        }

    /**
     * **The whole of `FailedProbeConnectionIdTestSuite`, deterministically and with no test seam.**
     *
     * That suite is the standing per-platform guard for #447, and it earns its keep — each target links
     * its own `libquiche`, so it is the only thing that proves the archive Apple embeds and the `.so`
     * Android ships behave. But it needs real sockets, real time, and
     * [UnansweredProbeDatagramChannel] — a deliberate hole punched through the production
     * [QuicPortBinding.Shared] surface to drop datagrams from sources the server has not yet heard.
     *
     * Here the same scenario is a seeded [PathImpairment] and nothing else: exhaust the CID pool with
     * unanswerable probes, keep an echo stream running throughout to prove the original path survives,
     * let the network come good, and require a real migration onto a healthy path — plus the stream
     * surviving it. 0ms of wall clock; every §8.2.4 abandon budget is virtual.
     *
     * ## Two things this got wrong first, both worth keeping written down
     *  1. **Blackhole by switch, never by probe index.** An attempt that answers
     *     [MigrationResult.Unmoved.Failed.NoSpareConnectionId] never opens a path, so attempt number and
     *     probe index drift apart — index-based healing left the supposedly-healthy recovery path a
     *     blackhole and produced a convincing false #447 reproduction. The real suite's
     *     `dropSourcesNotYetSeen()` / `allowEverySource()` is a switch for exactly this reason.
     *  2. **The replenish round trip is real.** Retiring sends RETIRE_CONNECTION_ID and the peer only
     *     then answers NEW_CONNECTION_ID, so the pool is briefly empty by design. The retry below is the
     *     same bounded tolerance the real suite documents, and it cannot mask the defect: unpatched
     *     there is nothing outstanding to replace, so every retry answers NoSpareConnectionId forever.
     */
    @Test
    fun aRunOfUnansweredProbesLeavesTheConnectionAbleToMigrate() =
        runTest {
            // The sim's equivalent of the real suite's drop/allow switch — see the KDoc.
            var blackholeProbes = true
            try {
                withMigrationSim(
                    testScope = this,
                    seed = 88_202L,
                    probeImpairment = { PathImpairment(blackhole = blackholeProbes) },
                ) {
                    val serverJob =
                        client.launch {
                            val st = server.acceptStream()
                            while (true) {
                                val d = st.read(60.seconds)
                                if (d !is ReadResult.Data) break
                                st.write(d.buffer, 30.seconds)
                                d.buffer.freeIfNeeded()
                            }
                        }
                    try {
                        val stream = client.openStream()

                        suspend fun echo(payload: String): String {
                            val out = BufferFactory.network().allocate(payload.length)
                            out.writeString(payload, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, 30.seconds)
                            out.freeNativeMemory()
                            val r = stream.read(60.seconds)
                            if (r !is ReadResult.Data) return "NO_DATA"
                            return r.buffer.readString(r.buffer.remaining(), Charset.UTF8).also { r.buffer.freeIfNeeded() }
                        }

                        assertEquals("before", echo("before"), "the connection must be healthy before any probe")
                        awaitSpareDcids()

                        repeat(FAILED_ATTEMPTS) { attempt ->
                            val result = withTimeout(120.seconds) { migrate().await() }
                            assertTrue(
                                result is MigrationResult.Unmoved,
                                "attempt ${attempt + 1} of $FAILED_ATTEMPTS reported $result, but its " +
                                    "PATH_CHALLENGE was blackholed and could not have been answered — the " +
                                    "impairment is not reaching the path and the rest of this proves nothing",
                            )
                        }
                        assertTrue(
                            pipe.paths().any { it.stats.blackholed > 0 },
                            "no datagram was ever blackholed, so no probe actually went unanswered — this " +
                                "would pass with the leak fully present",
                        )
                        assertEquals(
                            "still-here",
                            echo("still-here"),
                            "the failed migrations must leave the ORIGINAL path working — a connection that " +
                                "died here would make the recovery assertion below meaningless",
                        )

                        blackholeProbes = false // the network comes good again
                        var recovered = withTimeout(120.seconds) { migrate().await() }
                        var retries = 0
                        while (recovered is MigrationResult.Unmoved.Failed.NoSpareConnectionId && retries < REPLENISH_RETRIES) {
                            delay(50.milliseconds)
                            recovered = withTimeout(120.seconds) { migrate().await() }
                            retries++
                        }
                        assertTrue(
                            recovered is MigrationResult.Succeeded,
                            "after $FAILED_ATTEMPTS unanswered probes the connection could no longer migrate " +
                                "onto a healthy path ($recovered after $retries replenish retries). Each failed " +
                                "probe consumed a destination CID that was never retired, so the pool is " +
                                "permanently empty and the peer has no reason to issue more — one bad handoff " +
                                "on real cellular disables migration for the life of the connection (#447)",
                        )
                        assertEquals("after", echo("after"), "the stream did not survive the migration that followed the failed ones")
                        stream.close()
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(MigrationSimTests::class, e)
            }
        }

    private companion object {
        /**
         * One past exhaustion. [QuicOptions.activeConnectionIdLimit] defaults to 4 and quiche sizes both
         * the CID table and `max_concurrent_paths` from it, so at most 3 spare destination CIDs can be
         * outstanding — the same reasoning as the real suite's constant of the same name.
         */
        const val FAILED_ATTEMPTS = 4

        /** Bounded retries for the RETIRE -> NEW_CONNECTION_ID round trip, as the real suite does. */
        const val REPLENISH_RETRIES = 40
    }
}
