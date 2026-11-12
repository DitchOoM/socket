@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/** Apple member of [NativeCompareBench]; inert unless NATIVE_BENCH is set. */
class AppleNativeCompareBench : NativeCompareBench() {
    override fun testTlsConfig() = AppleTestCerts.tlsConfig

    override fun maxRssRaw(): Long =
        memScoped {
            val ru = alloc<rusage>()
            getrusage(RUSAGE_SELF, ru.ptr)
            ru.ru_maxrss
        }
}
