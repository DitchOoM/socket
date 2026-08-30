package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.sim.IncoherentSimClockException
import com.ditchoom.socket.quic.sim.SimClock
import com.ditchoom.socket.quic.sim.SimClockChoice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The construction-time half of the #497 guard, on the sims themselves: the pairing that cannot be
 * coherent — quiche on the wall clock while the sim's delays run on `runTest`'s scheduler — is refused
 * by [withSemanticSim] and [withMigrationSim] *before* they load libquiche or allocate a config, and the
 * default under `runTest` is the scheduler's clock. The pure resolver is covered in
 * `SimClockChoiceTests` (commonTest); these prove the sims call it first.
 *
 * The three refusal tests need no native library — that is the point of "before" — so they are not
 * wrapped in `skipOnMissingNativeLib`; a lane without libquiche must still see them pass.
 */
class SemanticSimClockGuardTests {
    @Test
    fun aSemanticSimAskedForTheWallClockUnderRunTestFailsAtConstruction() =
        runTest {
            val failure =
                assertFailsWith<IncoherentSimClockException> {
                    withSemanticSim(ImpairmentConfig(seed = 1L), clock = SimClockChoice.Wall) {
                        fail("the block must never run: the sim must refuse to be built")
                    }
                }
            assertTrue("#497" in failure.message.orEmpty(), failure.message)
        }

    @Test
    fun aMigrationSimAskedForTheWallClockUnderRunTestFailsAtConstruction() =
        runTest {
            assertFailsWith<IncoherentSimClockException> {
                withMigrationSim(seed = 1L, clock = SimClockChoice.Wall) {
                    fail("the block must never run: the sim must refuse to be built")
                }
            }
        }

    @Test
    fun aMigrationSimOnARealDispatcherFailsAtConstruction(): Unit =
        runBlocking {
            // Its default is Virtual: this harness exists for virtual time, and on a real dispatcher
            // nothing would ever advance the scheduler it pinned quiche to.
            assertFailsWith<IncoherentSimClockException> {
                withMigrationSim(seed = 1L) {
                    fail("the block must never run: the sim must refuse to be built")
                }
            }
        }

    @Test
    fun aSemanticSimBuiltTheDefaultWayUnderRunTestRunsOnTheSchedulersClock() =
        runTest(timeout = 60.seconds) {
            skipOnMissingNativeLib(SemanticSimClockGuardTests::class) {
                withSemanticSim(ImpairmentConfig(seed = 3L), establishTimeout = 5.seconds) {
                    assertIs<SimClock>(clock, "under runTest the default must be the scheduler's clock (#497)")
                }
            }
        }

    @Test
    fun aSemanticSimBuiltTheDefaultWayOnARealDispatcherRunsOnTheWallClock() =
        runBlocking {
            skipOnMissingNativeLib(SemanticSimClockGuardTests::class) {
                withSemanticSim(ImpairmentConfig(seed = 3L), establishTimeout = 5.seconds) {
                    assertSame(RealDriverClock, clock, "on a real dispatcher the default is the wall clock")
                }
            }
        }
}
