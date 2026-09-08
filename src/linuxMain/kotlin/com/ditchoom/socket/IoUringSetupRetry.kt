package com.ditchoom.socket

import platform.posix.ENOMEM

/** What one pass over the `io_uring_setup` flag ladder produced. */
internal sealed interface SetupAttempt<out T> {
    data class Created<T>(
        val ring: T,
    ) : SetupAttempt<T>

    data class Refused(
        val errno: Int,
    ) : SetupAttempt<Nothing>
}

/** Passes over the flag ladder when the kernel answers ENOMEM. */
internal const val ENOMEM_SETUP_ATTEMPTS = 5

/** First backoff, doubling per pass. [ENOMEM_SETUP_ATTEMPTS] passes means four waits: 15ms total. */
internal const val ENOMEM_BACKOFF_BASE_MICROS = 1_000

/**
 * Repeat [onePass] while the kernel answers ENOMEM, backing off between passes.
 *
 * A ring is charged to the process until `io_uring_queue_exit`, and teardown is asynchronous, so an
 * ENOMEM here is a race with a ring being released rather than an exhausted machine — the ledger reads
 * `created == released, live = 0` at the failure. Waiting is what resolves it; a ladder of flag
 * variants tried back to back in microseconds cannot. Any other errno is an answer about this host and
 * returns immediately.
 */
internal inline fun <T> withEnomemRetry(
    sleepMicros: (Int) -> Unit,
    onePass: () -> SetupAttempt<T>,
): SetupAttempt<T> {
    var outcome = onePass()
    var attempt = 0
    while (outcome is SetupAttempt.Refused &&
        outcome.errno == ENOMEM &&
        attempt < ENOMEM_SETUP_ATTEMPTS - 1
    ) {
        sleepMicros(ENOMEM_BACKOFF_BASE_MICROS shl attempt)
        attempt++
        outcome = onePass()
    }
    return outcome
}
