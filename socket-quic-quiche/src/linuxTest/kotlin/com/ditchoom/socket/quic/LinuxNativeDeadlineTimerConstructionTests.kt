@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.linux.RUSAGE_SELF
import platform.linux.getrusage
import platform.linux.rusage

/** Linux member of [NativeDeadlineTimerConstructionTests]: `ru_maxrss` is in KiB. */
class LinuxNativeDeadlineTimerConstructionTests : NativeDeadlineTimerConstructionTests() {
    override fun peakResidentBytes(): Long =
        memScoped {
            val usage = alloc<rusage>()
            getrusage(RUSAGE_SELF, usage.ptr)
            usage.ru_maxrss * 1024
        }
}
