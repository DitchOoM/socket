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
                val built = AtomicLong(0)
                try {
                    withContext(builders) {
                        repeat(BUILDERS) {
                            launch {
                                repeat(CONSTRUCTIONS) {
                                    if (grewBy.value != WITHIN_BOUND) return@launch
                                    val timer = NativeDeadlineTimer()
                                    timer.close()
                                    if (built.incrementAndGet() % COLLECT_EVERY != 0L) return@repeat
                                    // A thread that has ended keeps its allocator pages until the next collection,
                                    // and in a test binary every new thread fills several by registering every test
                                    // suite. Ending threads does not start a collection, so this test asks for one,
                                    // once per [COLLECT_EVERY] timers across the builders: a collection stops every
                                    // thread, and one per builder's every second timer made 9 600 of them, two thirds of
                                    // the test's time.
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

        /**
         * Timers built, across the builders, per collection: 2 400 collections. A collection after every second timer of
         * each builder made 9 600, and the test took 15 s on macOS and 37 to 54 s on the Linux ARM64 runner; this takes
         * 3.4 s on macOS, with the peak resident set 203 MiB up against 160 MiB before.
         */
        const val COLLECT_EVERY = 8L
        const val MAX_GROWTH_BYTES = 512L * 1024 * 1024
        const val WITHIN_BOUND = 0L
    }
}
