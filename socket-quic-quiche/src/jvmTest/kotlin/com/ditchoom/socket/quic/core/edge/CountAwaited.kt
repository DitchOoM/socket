package com.ditchoom.socket.quic.core.edge

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.TimeSource

/** What [awaitCount] came to. */
internal sealed interface CountAwaited {
    data object Reached : CountAwaited

    /** The count was still [count] when the wait gave up after [waited]. */
    class NotReached(
        val count: Long,
        val waited: Duration,
    ) : CountAwaited
}

/**
 * Waits up to [within] for [counter] to reach [target]. The bound is time, not iterations: how many iterations fit in a
 * millisecond depends on the host and the JVM's flags. Each iteration yields the processor, so on a host with few cores
 * the waiter does not hold one from the threads that move the count. It allocates nothing.
 */
internal suspend fun awaitCount(
    counter: AtomicLong,
    target: Long,
    within: Duration,
): CountAwaited {
    val since = TimeSource.Monotonic.markNow()
    while (counter.get() < target) {
        val waited = since.elapsedNow()
        if (waited > within) return CountAwaited.NotReached(counter.get(), waited)
        Thread.yield()
        kotlinx.coroutines.yield()
    }
    return CountAwaited.Reached
}
