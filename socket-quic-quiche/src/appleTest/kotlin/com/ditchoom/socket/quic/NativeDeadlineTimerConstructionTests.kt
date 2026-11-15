package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test

/**
 * [NativeDeadlineTimer]'s construction under contention. Apple only: the test starts thousands of worker
 * threads in turn, which a Linux CI runner does not survive.
 */
class NativeDeadlineTimerConstructionTests {
    /** The timer thread reads the timer's fields from its first pass, so it must not start before every one is set. */
    @Test
    fun aNewTimersThreadNeverSeesItHalfBuilt() =
        runTest {
            withContext(Dispatchers.Default) {
                // More builder threads than cores, so a builder is often descheduled mid-construction while its new
                // thread makes its first pass. Each timer is closed, which waits for its thread, before the next, so
                // at most [BUILDERS] timer threads are alive.
                val builders = newFixedThreadPoolContext(BUILDERS, "timer-builders")
                try {
                    withContext(builders) {
                        repeat(BUILDERS) {
                            launch {
                                repeat(CONSTRUCTIONS) {
                                    val timer = NativeDeadlineTimer()
                                    timer.close()
                                }
                            }
                        }
                    }
                } finally {
                    builders.close()
                }
            }
        }

    private companion object {
        const val BUILDERS = 64
        const val CONSTRUCTIONS = 300
    }
}
