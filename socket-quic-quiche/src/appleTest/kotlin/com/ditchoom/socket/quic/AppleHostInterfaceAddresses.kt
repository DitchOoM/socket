@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket

/**
 * This host's address on the interface its default route leaves by, per family — the source the
 * kernel picks for a documentation-prefix destination (`connect()` on a UDP socket selects a route and
 * a source and sends nothing). A family with no such route is left out.
 */
internal fun appleHostInterfaceAddresses(): List<String> = listOfNotNull(routeSource(ipv6 = false), routeSource(ipv6 = true))

private fun routeSource(ipv6: Boolean): String? =
    memScoped {
        val fd = socket(if (ipv6) AF_INET6 else AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return@memScoped null
        try {
            val remote = alloc<sockaddr_storage>()
            val bytes = remote.ptr.reinterpret<ByteVar>()
            for (i in 0 until sizeOf<sockaddr_storage>().toInt()) bytes[i] = 0
            val length: Int
            bytes[3] = 9 // port 9, network order
            if (ipv6) {
                length = sizeOf<sockaddr_in6>().toInt()
                bytes[1] = AF_INET6.toByte()
                // 2001:db8::1
                bytes[8] = 0x20
                bytes[9] = 0x01
                bytes[10] = 0x0d
                bytes[11] = 0xb8.toByte()
                bytes[23] = 1
            } else {
                length = sizeOf<sockaddr_in>().toInt()
                bytes[1] = AF_INET.toByte()
                // 192.0.2.1
                bytes[4] = 192.toByte()
                bytes[6] = 2
                bytes[7] = 1
            }
            bytes[0] = length.toByte()
            if (connect(fd, remote.ptr.reinterpret(), length.convert()) != 0) return@memScoped null
            val local = alloc<sockaddr_storage>()
            val localLength = alloc<UIntVar>()
            localLength.value = sizeOf<sockaddr_storage>().convert()
            if (getsockname(fd, local.ptr.reinterpret(), localLength.ptr) != 0) return@memScoped null
            val out = local.ptr.reinterpret<ByteVar>()
            if (ipv6) {
                (0 until 8).joinToString(":") { group ->
                    (((out[8 + 2 * group].toInt() and 0xFF) shl 8) or (out[9 + 2 * group].toInt() and 0xFF)).toString(16)
                }
            } else {
                (4 until 8).joinToString(".") { (out[it].toInt() and 0xFF).toString() }
            }
        } finally {
            close(fd)
        }
    }
