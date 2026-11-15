@file:OptIn(NativeRuntimeApi::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicLong
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** [NativeDeadlineTimer]'s construction under contention. Each platform family supplies [peakResidentBytes]. */
abstract class NativeDeadlineTimerConstructionTests {
    /** The process's peak resident set, in bytes (`ru_maxrss`). */
    abstract fun peakResidentBytes(): Long

    /** The timer thread reads the timer's fields from its first pass, so it must not start before every one is set. */
    @Test
    fun aNewTimersThreadNeverSeesItHalfBuilt() =
        runTest {
            withContext(Dispatchers.Default) {
                // More builder threads than cores, so a builder is often descheduled mid-construction while its new
                // thread makes its first pass. Each timer is closed, which waits for its thread, before the next, so
                // at most [BUILDERS] timer threads are alive.
                val builders = newFixedThreadPoolContext(BUILDERS, "timer-builders")
                val peakAtStart = peakResidentBytes()
                val grewBy = AtomicLong(WITHIN_BOUND)
                try {
                    withContext(builders) {
                        repeat(BUILDERS) {
                            launch {
                                for (built in 1..CONSTRUCTIONS) {
                                    if (grewBy.value != WITHIN_BOUND) break
                                    val timer = NativeDeadlineTimer()
                                    timer.close()
                                    if (built % COLLECT_EVERY != 0) continue
                                    // A thread that has ended keeps its allocator pages until the next collection,
                                    // and in a test binary every new thread fills several by registering every test
                                    // suite. Ending threads does not start a collection, so this test asks for one.
                                    GC.collect()
                                    val growth = peakResidentBytes() - peakAtStart
                                    if (growth > MAX_GROWTH_BYTES) grewBy.compareAndSet(WITHIN_BOUND, growth)
                                }
                            }
                        }
                    }
                } finally {
                    builders.close()
                }
                // The number a reader of the log needs when the bound is ever in question.
                println("peak resident set grew by ${(peakResidentBytes() - peakAtStart) / (1024 * 1024)} MiB during the test")
                // Every builder stops at the first reading over the bound, so a regression fails here and not by
                // exhausting the machine.
                assertEquals(
                    WITHIN_BOUND,
                    grewBy.value,
                    "bytes the process's peak resident set grew by during the test; the bound is $MAX_GROWTH_BYTES",
                )
            }
        }

    private companion object {
        const val BUILDERS = 64
        const val CONSTRUCTIONS = 300
        const val COLLECT_EVERY = 4
        const val MAX_GROWTH_BYTES = 512L * 1024 * 1024
        const val WITHIN_BOUND = 0L
    }
}
