@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.time

import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np

internal actual fun monotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()
