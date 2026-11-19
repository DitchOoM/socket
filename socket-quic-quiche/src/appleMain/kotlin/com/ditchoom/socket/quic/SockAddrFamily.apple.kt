@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.toCPointer

/** Darwin `sockaddr`: `sa_len` is a u8 at byte 0 and `sa_family` a u8 at byte 1. */
internal actual fun readSockAddrFamily(addr: Long): Int =
    when (addr.toCPointer<UByteVar>()!![1].toInt()) {
        platform.posix.AF_INET -> 4
        platform.posix.AF_INET6 -> 6
        else -> 0
    }
