package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * #449 layer 3, first milestone: **a real QUIC migration inside a deterministic simulator.**
 *
 * Before this, `SemanticSim` — the only Tier-B harness — passed
 * [MigrationCapability.BackendCannotMigrate], so no simulated connection had ever moved paths. Every
 * CID/path defect in this repo was therefore found on a phone. See [withMigrationSim] for the shape.
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
}
