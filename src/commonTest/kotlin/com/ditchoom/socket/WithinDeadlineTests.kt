package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WithinDeadlineTests {
    @Test
    fun aBlockThatReturnsIsCompleted() =
        runTestNoTimeSkipping {
            val result = withinDeadline(5.seconds) { 42 }
            assertIs<WithinDeadline.Completed<Int>>(result)
            assertEquals(42, result.value)
        }

    @Test
    fun itsOwnDeadlineIsExpiredAndLeavesTheCallerActive() =
        runTestNoTimeSkipping {
            val result = withinDeadline(50.milliseconds) { awaitCancellation() }
            assertEquals(WithinDeadline.Expired, result)
            assertTrue(currentCoroutineContext().isActive, "the caller's job must stay active after its own deadline")
        }

    @Test
    fun anEnclosingDeadlineIsTheCallersOwnTimeout() =
        runTestNoTimeSkipping {
            // withTimeoutOrNull returns null only for its own TimeoutCancellationException: reaching null
            // proves the enclosing timeout propagated out of withinDeadline as the caller's, untouched.
            val outer =
                withTimeoutOrNull(50.milliseconds) {
                    withinDeadline(5.seconds) { awaitCancellation() }
                    fail("withinDeadline returned after the enclosing deadline expired")
                }
            assertEquals(null, outer)
        }

    @Test
    fun aCancelledCallerIsCancelledNotExpired() =
        runTestNoTimeSkipping {
            val entered = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<WithinDeadline<Unit>>()
            val caller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val result =
                        withinDeadline(5.seconds) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    outcome.complete(result)
                }
            entered.await()
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertTrue(!outcome.isCompleted, "a cancelled caller must not receive a result")
        }
}
