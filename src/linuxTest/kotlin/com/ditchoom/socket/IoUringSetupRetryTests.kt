package com.ditchoom.socket

import platform.posix.EINVAL
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IoUringSetupRetryTests {
    @Test
    fun anEnomemThatClearsIsRetriedUntilTheRingIsCreated() {
        val slept = mutableListOf<Int>()
        var passes = 0
        val outcome =
            withEnomemRetry(slept::add) {
                passes++
                if (passes < 3) SetupAttempt.Refused(ENOMEM) else SetupAttempt.Created("ring")
            }
        assertEquals(SetupAttempt.Created("ring"), outcome, "a transient ENOMEM must not be fatal")
        assertEquals(3, passes)
        assertEquals(listOf(1_000, 2_000), slept, "backoff must double between passes")
    }

    @Test
    fun aNonEnomemRefusalIsNotRetried() {
        val slept = mutableListOf<Int>()
        var passes = 0
        val outcome =
            withEnomemRetry(slept::add) {
                passes++
                SetupAttempt.Refused(EINVAL)
            }
        assertEquals(SetupAttempt.Refused(EINVAL), outcome)
        assertEquals(1, passes, "an answer about this host must fail on the first pass")
        assertTrue(slept.isEmpty())
    }

    @Test
    fun aPersistentEnomemGivesUpAfterTheWholeBudget() {
        val slept = mutableListOf<Int>()
        var passes = 0
        withEnomemRetry(slept::add) {
            passes++
            SetupAttempt.Refused(ENOMEM)
        }
        assertEquals(ENOMEM_SETUP_ATTEMPTS, passes)
        assertEquals(listOf(1_000, 2_000, 4_000, 8_000), slept, "four waits, doubling: 15ms total")
    }
}
