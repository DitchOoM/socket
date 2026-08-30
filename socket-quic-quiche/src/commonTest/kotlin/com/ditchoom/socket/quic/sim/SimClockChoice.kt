@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.quic.DriverClock
import com.ditchoom.socket.quic.RealDriverClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Which clock a real-quiche simulation runs on — meaning **both sides of the FFI at once**: the
 * drivers' `delay()` / `select { onTimeout }` wakes and the pipe's impairment delays on our side, and
 * libquiche's internal `Instant::now()` (PTO, loss detection, idle, path validation) on the other, which
 * [SimClock] reaches through `CallerClockQuicheApi`.
 *
 * The two sides must read the *same* clock, and which clock that can be is decided by the dispatcher the
 * sim is constructed on — not by a parameter. Under a [TestDispatcher] (`runTest`) every `delay()` in the
 * sim fast-forwards on the [TestCoroutineScheduler], so quiche has to read that scheduler too; on a real
 * dispatcher every `delay()` is wall time, so quiche's own wall clock is already the right one. A choice
 * that contradicts the dispatcher is not "a slower run", it is an **incoherent** one: DitchOoM/socket#497
 * measured the old `RealDriverClock`-under-`runTest` default flipping 4 of 6 handshake outcomes at 30%
 * loss, because quiche kept reporting ~995ms to its first PTO while each wake advanced virtual time by
 * exactly that much — 60s of establishment bound gone in 11ms of wall, and the lost Initial never
 * retransmitted.
 *
 * So this is a sealed choice that [resolve] checks against the calling dispatcher at construction, before
 * a sim allocates anything native. The impossible pairings ([Wall] under a test dispatcher, [Virtual]
 * outside one) fail there, typed, instead of surfacing as a handshake that timed out for no reason.
 */
internal sealed interface SimClockChoice {
    /**
     * The default: the clock the calling dispatcher already runs on. A [TestDispatcher]'s scheduler →
     * [SimClock]; any real dispatcher → [RealDriverClock]. Coherent by construction either way, which
     * is why it is the default and why a sim test does not have to say anything about clocks unless
     * it wants to pin one.
     */
    data object OfCallingDispatcher : SimClockChoice

    /**
     * Pin virtual time: the calling [TestDispatcher]'s scheduler, and a construction error on a real
     * dispatcher. For a test whose subject *is* a timer — an idle timeout, a PTO, a §8.2.4 validation
     * budget — so the intent is stated where the sim is built and a stray `withContext(Dispatchers.IO)`
     * around it cannot silently put the whole scenario on the wall clock.
     */
    data object Virtual : SimClockChoice

    /**
     * Pin wall time: [RealDriverClock] on a real dispatcher, and a construction error under a
     * [TestDispatcher] — the #497 pairing. There is no way to ask for it under `runTest`; a scenario that
     * needs quiche on the wall clock runs on real dispatchers (`runBlocking` / `runQuicTest`), where the
     * sim's own delays are wall time as well.
     */
    data object Wall : SimClockChoice
}

/**
 * A [SimClockChoice] that contradicts the dispatcher the sim was constructed on. Thrown by [resolve]
 * before any native allocation, so the sim fails at construction rather than by a timed-out handshake.
 */
internal class IncoherentSimClockException(
    message: String,
) : IllegalStateException(message)

/** What the calling coroutine is dispatched on, as the two cases [SimClockChoice] distinguishes. */
private sealed interface CallingDispatcher {
    /** A kotlinx-coroutines-test dispatcher: `delay()` runs on [scheduler]'s virtual time. */
    data class Test(
        val scheduler: TestCoroutineScheduler,
    ) : CallingDispatcher

    /** Anything else — `delay()` is wall time. [name] is for the error message only. */
    data class Real(
        val name: String,
    ) : CallingDispatcher
}

/**
 * Keyed on the [ContinuationInterceptor], deliberately NOT on `coroutineContext[TestCoroutineScheduler]`:
 * `withContext(Dispatchers.Default) { ... }` inside `runTest` keeps the scheduler *element* in the context
 * while every `delay()` underneath it is wall time. Reading the element there would install [SimClock]
 * on a sim whose delays are real — the same incoherence as #497 with the sides swapped.
 */
private suspend fun callingDispatcher(): CallingDispatcher =
    when (val interceptor = coroutineContext[ContinuationInterceptor]) {
        is TestDispatcher -> CallingDispatcher.Test(interceptor.scheduler)
        null -> CallingDispatcher.Real("no dispatcher in the calling context")
        else -> CallingDispatcher.Real(interceptor.toString())
    }

/**
 * The [DriverClock] this choice means on the calling dispatcher — or an [IncoherentSimClockException]
 * when the two contradict. Call it in the sim's constructor before anything native is allocated.
 */
internal suspend fun SimClockChoice.resolve(): DriverClock {
    val calling = callingDispatcher()
    return when (this) {
        SimClockChoice.OfCallingDispatcher ->
            when (calling) {
                is CallingDispatcher.Test -> SimClock(calling.scheduler)
                is CallingDispatcher.Real -> RealDriverClock
            }
        SimClockChoice.Virtual ->
            when (calling) {
                is CallingDispatcher.Test -> SimClock(calling.scheduler)
                is CallingDispatcher.Real ->
                    throw IncoherentSimClockException(
                        "SimClockChoice.Virtual needs a TestDispatcher (runTest) to read virtual time from, but " +
                            "the sim is being constructed on ${calling.name}: its delay()s would be wall time " +
                            "while quiche is pinned to a scheduler nothing advances. Construct it under runTest, " +
                            "or choose Wall / OfCallingDispatcher on a real dispatcher.",
                    )
            }
        SimClockChoice.Wall ->
            when (calling) {
                is CallingDispatcher.Test ->
                    throw IncoherentSimClockException(
                        "SimClockChoice.Wall under a TestDispatcher (runTest): the sim's delay()s, select " +
                            "timeouts and establishment bound would fast-forward on the scheduler's virtual time " +
                            "while quiche's own PTO/idle/loss timers read a wall clock that barely moves — the two " +
                            "sides of the FFI would disagree about elapsed time and flip handshake outcomes " +
                            "(DitchOoM/socket#497). Run on a real dispatcher for wall time, or choose Virtual / " +
                            "OfCallingDispatcher here.",
                    )
                is CallingDispatcher.Real -> RealDriverClock
            }
    }
}
