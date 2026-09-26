package com.ditchoom.socket

import com.ditchoom.socket.linux.io_uring_prep_nop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EINVAL
import platform.posix.ENOMEM
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
        assertEquals(listOf(1_000, 2_000, 4_000, 8_000), slept, "four waits, doubling: 15ms total")
    }

    /**
     * The manager's ring setup refused on every attempt: the operation waiting on it fails with the
     * typed setup error instead of the process aborting, and the next operation sets up a ring afresh.
     */
    @Test
    fun aRingTheKernelRefusesFailsTheWaitingOperationAndTheNextOneSetsUpAfresh() =
        runBlocking {
            IoUringManager.cleanup()
            val real = IoUringManager.queueInit.value
            val attempts = AtomicInt(0)
            IoUringManager.queueInit.value = { _, _ ->
                attempts.incrementAndGet()
                -ENOMEM
            }
            val failure =
                try {
                    assertFailsWith<SocketIOException> {
                        withTimeout(10.seconds) {
                            IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) }
                        }
                    }
                } finally {
                    IoUringManager.queueInit.value = real
                }
            assertEquals(ENOMEM_SETUP_PASSES * IO_URING_SETUP_LADDER.size, attempts.value)
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
}
