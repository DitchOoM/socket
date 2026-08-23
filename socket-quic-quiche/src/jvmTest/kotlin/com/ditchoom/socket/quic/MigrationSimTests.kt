package com.ditchoom.socket.quic

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
}
