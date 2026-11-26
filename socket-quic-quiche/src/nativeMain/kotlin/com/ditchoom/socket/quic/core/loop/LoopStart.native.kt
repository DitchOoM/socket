@file:OptIn(ObsoleteWorkersApi::class)

package com.ditchoom.socket.quic.core.loop

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

/**
 * A worker of its own runs the loop to its end and then terminates. The loop blocks only in its poller; a worker is a
 * platform thread, so nothing else shares it.
 */
internal actual fun startLoop(
    loop: Loop,
    name: String,
): LoopStart {
    val worker = Worker.start(name = name)
    worker.executeAfter(0L) {
        loop.run()
        worker.requestTermination(processScheduledJobs = false)
    }
    return LoopStart.Started
}
