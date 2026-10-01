package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * A pending dump of every thread's stack, taken if a test is still running when it comes due.
 *
 * A test that times out reports only the deadline: the `TimeoutCancellationException` names the
 * timer that fired, never what the test was waiting on, and by the time it is thrown the wait has
 * already been cancelled and unwound. The dump is taken *before* the deadline cancels anything, from
 * a thread of its own, so it still runs when every coroutine dispatcher is starved or the
 * kotlinx timer thread is the thing that is stuck.
 *
 * It shows threads, not coroutines: a thread blocked in a call names the call. A test parked on a
 * suspension shows every worker idle instead — which rules out a blocked or starved thread, and
 * says the wait is on a coroutine.
 */
fun interface StallDump {
    /** The test finished: nothing is printed. */
    fun disarm()
}

/**
 * Prints `STALL-DUMP` and [label], then every thread's stack, after [after] — unless the returned
 * handle is disarmed first.
 *
 * JVM and Android print the dump to stdout (the test report's system-out; logcat on a device).
 * Kotlin/Native exposes no other thread's stack, so there nothing is armed.
 */
expect fun armStallDump(
    after: Duration,
    label: String,
): StallDump
