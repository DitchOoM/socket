@file:OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.nanosleep
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_timedwait
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.timespec
import platform.posix.usleep
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.TimeSource

/** One way a thread can wait for [micros]: what [rawWaitLateness] times against the clock. */
internal class RawWait(
    val name: String,
    val wait: (micros: Long) -> Unit,
)

/** The waits only this platform has (Darwin's relative condition wait, mach_wait_until; Linux's absolute monotonic ones). */
internal expect fun platformRawWaits(): List<RawWait>

/** Sleeps on a condition variable until the absolute wall-clock time [micros] from now, the way a timer thread would. */
internal fun condWaitAbsolute(
    mutex: pthread_mutex_t,
    cond: pthread_cond_t,
    micros: Long,
) {
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME.convert(), ts.ptr)
        val total = ts.tv_nsec + (micros % 1_000_000L) * 1_000L
        ts.tv_sec += micros / 1_000_000L + total / 1_000_000_000L
        ts.tv_nsec = total % 1_000_000_000L
        pthread_mutex_lock(mutex.ptr)
        pthread_cond_timedwait(cond.ptr, mutex.ptr, ts.ptr)
        pthread_mutex_unlock(mutex.ptr)
    }
}

/** A mutex and condition variable nothing ever signals, so a wait on them ends only by its timeout. */
internal fun unsignalledCond(
    attrInit: (pthread_cond_t) -> Unit = { pthread_cond_init(it.ptr, null) },
): Pair<pthread_mutex_t, pthread_cond_t> {
    val mutex = nativeHeap.alloc<pthread_mutex_t>()
    pthread_mutex_init(mutex.ptr, null)
    val cond = nativeHeap.alloc<pthread_cond_t>()
    attrInit(cond)
    return mutex to cond
}

private fun portableRawWaits(): List<RawWait> {
    val (mutex, cond) = unsignalledCond()
    return listOf(
        RawWait("Worker.park") { micros -> Worker.current.park(micros, true) },
        RawWait("usleep") { micros -> usleep(micros.toUInt()) },
        RawWait("nanosleep") { micros ->
            memScoped {
                val ts = alloc<timespec>()
                ts.tv_sec = micros / 1_000_000L
                ts.tv_nsec = (micros % 1_000_000L) * 1_000L
                nanosleep(ts.ptr, null)
            }
        },
        RawWait("cond_timedwait(REALTIME)") { micros -> condWaitAbsolute(mutex, cond, micros) },
    )
}

/** How late one raw wait returned for [delayMicros], over its samples, ascending. */
internal class RawWaitLateness(
    val name: String,
    val delayMicros: Long,
    val lateMicros: List<Long>,
) {
    val median: Long get() = lateMicros[lateMicros.size / 2]

    override fun toString(): String =
        "RAW-WAIT delay_us=$delayMicros $name p50_us=$median p99_us=${lateMicros[lateMicros.size * 99 / 100]} max_us=${lateMicros.last()}"
}

/**
 * For each delay, how many microseconds late each raw wait returns, measured on one dedicated thread (`Worker.park`
 * only parks the worker it is called on) in the same run as everything else, so the machine's own scheduling noise
 * is in every row.
 */
internal fun rawWaitLateness(
    delaysMicros: List<Long>,
    samples: (Long) -> Int,
): List<RawWaitLateness> {
    val worker = Worker.start(name = "raw-wait-baseline")
    try {
        val rows = ArrayList<RawWaitLateness>()
        for (delay in delaysMicros) {
            val n = samples(delay)
            val future =
                worker.execute(TransferMode.SAFE, { Triple(delay, n, Unit) }) { (d, count, _) ->
                    (portableRawWaits() + platformRawWaits()).map { w ->
                        val late = ArrayList<Long>()
                        repeat(count) {
                            val start = TimeSource.Monotonic.markNow()
                            w.wait(d)
                            late.add(maxOf(start.elapsedNow().inWholeMicroseconds - d, 0L))
                        }
                        late.sort()
                        RawWaitLateness(w.name, d, late)
                    }
                }
            rows += future.result
        }
        return rows
    } finally {
        worker.requestTermination().result
    }
}
