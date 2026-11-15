@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.toCPointer

/** Linux `sockaddr`: no `sa_len`; `sa_family` is a little-endian u16 at byte 0. */
internal actual fun readSockAddrFamily(addr: Long): Int {
    val p = addr.toCPointer<UByteVar>()!!
    return when (p[0].toInt() or (p[1].toInt() shl 8)) {
        platform.posix.AF_INET -> 4
        platform.posix.AF_INET6 -> 6
        else -> 0
    }
}
