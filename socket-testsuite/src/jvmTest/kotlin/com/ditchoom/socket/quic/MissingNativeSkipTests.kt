package com.ditchoom.socket.quic

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the one property [skipOnMissingNativeLib] exists to provide: a missing quiche native must
 * never leave a test reporting as **passed**.
 *
 * That is the failure this whole path replaced. Thirty-eight test classes each carried their own
 * copy of the catch, and a copy that returned normally instead of aborting would have turned its
 * entire suite green-by-vacancy — indistinguishable, in the report, from a suite that ran.
 *
 * Deliberately asserts "throws", not "throws `AssumptionViolatedException`": which throwable comes
 * out depends on whether the lane sets `SOCKET_REQUIRE_ALL_TESTS`. Pinning the subtype would make
 * this test itself fail on exactly the lanes that take skipping most seriously.
 */
class MissingNativeSkipTests {
    @Test
    fun aMissingNativeAbortsRatherThanPassing() =
        runTest {
            val thrown =
                try {
                    skipOnMissingNativeLib { throw UnsatisfiedLinkError("no libquiche in java.library.path") }
                    null
                } catch (t: Throwable) {
                    t
                }

            if (thrown == null) {
                fail(
                    "skipOnMissingNativeLib swallowed UnsatisfiedLinkError and returned normally — " +
                        "the test that called it would have been reported as PASSED having run nothing",
                )
            }
            assertTrue(
                thrown.message?.contains("no libquiche in java.library.path") == true,
                "the abort must carry the original linker message so the report says which native was missing; got: ${thrown.message}",
            )
        }

    @Test
    fun theBlockRunsAndItsResultIsNotSwallowedWhenTheNativeIsPresent() =
        runTest {
            var ran = false

            skipOnMissingNativeLib { ran = true }

            assertTrue(ran, "the happy path must actually invoke the block")
        }

    @Test
    fun anUnrelatedFailurePropagatesInsteadOfBecomingASkip() =
        runTest {
            // Only UnsatisfiedLinkError means "no native". Widening the catch would convert real
            // assertion failures into skips, which is the same green-by-vacancy bug wearing a
            // different hat.
            val thrown =
                try {
                    skipOnMissingNativeLib { throw IllegalStateException("a real bug") }
                    null
                } catch (t: Throwable) {
                    t
                }

            assertTrue(thrown is IllegalStateException, "expected the original failure, got: $thrown")
        }
}
