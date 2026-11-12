@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.TIMER_ABSTIME
import platform.posix.clock_gettime
import platform.posix.clock_nanosleep
import platform.posix.timespec

internal actual fun platformRawWaits(): List<RawWait> =
    listOf(
        RawWait("clock_nanosleep(ABSTIME)") { micros ->
            memScoped {
                val ts = alloc<timespec>()
                clock_gettime(CLOCK_MONOTONIC, ts.ptr)
                val total = ts.tv_nsec + (micros % 1_000_000L) * 1_000L
                ts.tv_sec += micros / 1_000_000L + total / 1_000_000_000L
                ts.tv_nsec = total % 1_000_000_000L
                clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, ts.ptr, null)
            }
        },
    )
