@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.pthread_cond_timedwait_relative_np
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_unlock
import platform.posix.timespec

internal actual fun platformRawWaits(): List<RawWait> {
    val (mutex, cond) = unsignalledCond()
    val semaphore = dispatch_semaphore_create(0)
    return listOf(
        RawWait("cond_timedwait_relative_np") { micros ->
            memScoped {
                val ts = alloc<timespec>()
                ts.tv_sec = micros / 1_000_000L
                ts.tv_nsec = (micros % 1_000_000L) * 1_000L
                pthread_mutex_lock(mutex.ptr)
                pthread_cond_timedwait_relative_np(cond.ptr, mutex.ptr, ts.ptr)
                pthread_mutex_unlock(mutex.ptr)
            }
        },
        RawWait("dispatch_semaphore_wait") { micros ->
            dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, micros * 1_000L))
        },
    )
}
