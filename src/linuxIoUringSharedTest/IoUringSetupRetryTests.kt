package com.ditchoom.socket.iouring

import com.ditchoom.socket.iouring.linux.io_uring_prep_nop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EINVAL
import platform.posix.ENOMEM
import platform.posix.getpid
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class IoUringSetupRetryTests {
    @Test
    fun anEnomemThatClearsIsRetriedUntilTheRingIsCreated() {
        val slept = mutableListOf<Int>()
        var calls = 0
        val ladder = IO_URING_SETUP_LADDER.size
        val outcome =
            setUpIoUring(slept::add) {
                calls++
                // Two whole passes refused, then the first flag set of the third pass accepted.
                if (calls <= 2 * ladder) -ENOMEM else 0
            }
        assertEquals(RingSetup.Created, outcome, "a transient ENOMEM must not be fatal")
        assertEquals(2 * ladder + 1, calls)
        assertEquals(listOf(1_000, 2_000), slept, "backoff must double between passes")
    }

    /**
     * The refusal clears only when the kernel's deferred teardown of released rings returns their pages
     * to the user's RLIMIT_MEMLOCK charge. Measured on kernel 6.18 at an 8 MB limit, three processes
     * churning rings under full CPU load: up to 0.85 s from the first ENOMEM to a created ring.
     */
    @Test
    fun anEnomemThatLastsAsLongAsTheKernelsDeferredTeardownIsWaitedOut() {
        val clearsAfterMicros = 850_000L
        var sleptMicros = 0L
        val outcome =
            setUpIoUring({ sleptMicros += it }) {
                if (sleptMicros < clearsAfterMicros) -ENOMEM else 0
            }
        assertEquals(
            RingSetup.Created,
            outcome,
            "an ENOMEM the kernel clears after ${clearsAfterMicros / 1_000} ms gave up after ${sleptMicros / 1_000} ms",
        )
    }

    @Test
    fun aNonEnomemRefusalIsNotRetried() {
        val slept = mutableListOf<Int>()
        var calls = 0
        val outcome =
            setUpIoUring(slept::add) {
                calls++
                -EINVAL
            }
        val refused = assertIs<RingSetup.Refused>(outcome)
        assertEquals(RefusedSetup(pass = 0, flags = 0u, errno = EINVAL), refused.final)
        assertEquals(IO_URING_SETUP_LADDER.size, calls, "an answer about this host must end the first pass")
        assertTrue(slept.isEmpty())
    }

    @Test
    fun aPersistentEnomemGivesUpAfterTheWholeBudgetWithEveryAttemptRecorded() {
        val slept = mutableListOf<Int>()
        var calls = 0
        val outcome =
            setUpIoUring(slept::add) {
                calls++
                -ENOMEM
            }
        val refused = assertIs<RingSetup.Refused>(outcome)
        assertEquals(ENOMEM_SETUP_PASSES * IO_URING_SETUP_LADDER.size, calls)
        assertEquals(calls, refused.earlier.size + 1, "every refused attempt must be in the report")
        assertEquals(RefusedSetup(ENOMEM_SETUP_PASSES - 1, 0u, ENOMEM), refused.final)
        assertEquals(
            (0 until ENOMEM_SETUP_PASSES - 1).map { 1_000 shl it },
            slept,
            "one wait before each pass after the first, doubling",
        )
    }

    /**
     * The manager's ring setup refused on every attempt: the operation waiting on it fails with the
     * module's [IoUringFailure] instead of the process aborting, and the next operation sets up a ring
     * afresh.
     */
    @Test
    fun aRingTheKernelRefusesFailsTheWaitingOperationAndTheNextOneSetsUpAfresh() =
        runBlocking {
            IoUringManager.cleanup()
            val real = IoUringManager.queueInit.value
            val attempts = AtomicInt(0)
            val oneSetupsBudget = ENOMEM_SETUP_PASSES * IO_URING_SETUP_LADDER.size
            // Refuses exactly one setup's budget and then is the kernel again: the seam is process-wide, so
            // a setup any other submitter starts before the `finally` below must still reach the kernel,
            // and a failure of the fresh setup below then means the kernel refused, never this fake.
            IoUringManager.queueInit.value = { ring, params ->
                if (attempts.incrementAndGet() <= oneSetupsBudget) -ENOMEM else real(ring, params)
            }
            val failure =
                try {
                    assertFailsWith<IoUringFailure> {
                        withTimeout(10.seconds) {
                            IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) }
                        }
                    }
                } finally {
                    IoUringManager.queueInit.value = real
                }
            assertEquals(oneSetupsBudget, attempts.value, "only the failed setup may have called the refusing init")
            assertTrue(
                failure.message.orEmpty().contains("errno=$ENOMEM"),
                "the failure must name the errno: ${failure.message}",
            )

            val result =
                withTimeout(10.seconds) {
                    IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) }
                }
            assertEquals(0, result, "the next operation must run on a freshly set-up ring")
            IoUringManager.cleanup()
        }

    /**
     * The ENOMEM report names every process of this user holding io_uring rings, because ring memory is
     * charged to a budget all of them share. This process's own ring must be among them, with its sizes.
     */
    @Test
    fun theSetupFailureReportNamesThisProcesssRingWithItsSizes() =
        runBlocking {
            withTimeout(10.seconds) { IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) } }
            val report = ioUringRingsOfThisUser()
            assertTrue(report.contains("(${getpid()})=sq1024/cq2048"), "this process's 1024-entry ring is missing: $report")
            IoUringManager.cleanup()
        }
}
