@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/** Apple member of [NativeDeadlineTimerConstructionTests]: `ru_maxrss` is in bytes. */
class AppleNativeDeadlineTimerConstructionTests : NativeDeadlineTimerConstructionTests() {
    override fun peakResidentBytes(): Long =
        memScoped {
            val usage = alloc<rusage>()
            getrusage(RUSAGE_SELF, usage.ptr)
            usage.ru_maxrss.toLong()
        }
}
