@file:OptIn(ObsoleteWorkersApi::class)

package com.ditchoom.socket.quic.core.sched

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

/**
 * A worker of its own. It parks in the worker's job queue; a wake is an empty job, which a park that comes after it
 * finds queued and returns for.
 */
internal actual class PoolThread(
    private val worker: Worker,
) {
    actual fun unpark() {
        worker.executeAfter(0L) {}
    }

    actual fun park() {
        worker.park(PARK_MICROS, process = true)
    }
}

/** The worker runs [body] to its end and then terminates; it is not reused, so its thread is not churned. */
internal actual fun startPoolThread(
    name: String,
    body: (PoolThread) -> Unit,
) {
    val worker = Worker.start(name = name)
    worker.executeAfter(0L) {
        body(PoolThread(worker))
        worker.requestTermination(processScheduledJobs = false)
    }
}

/** A park's longest wait; the caller parks again when it was not woken. */
private const val PARK_MICROS = 3_600_000_000L
