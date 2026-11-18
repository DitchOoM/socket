@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.time

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec

internal actual fun monotonicNanos(): Long =
    memScoped {
        val now = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC, now.ptr)
        now.tv_sec * 1_000_000_000L + now.tv_nsec
    }
