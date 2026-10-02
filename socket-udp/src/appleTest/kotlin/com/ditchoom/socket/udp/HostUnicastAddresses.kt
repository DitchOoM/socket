@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.sockaddr_storage
import platform.posix.socket

/**
 * This host's address on the interface its default route leaves by — the second local address a
 * reply-source test dials, next to `127.0.0.1` / `::1`.
 *
 * Read as the source the kernel picks for a documentation-prefix destination: `connect()` on a UDP
 * socket selects a route and a source and sends nothing. `null` when the host has no such route.
 */
internal object HostUnicastAddresses {
    fun firstNonLoopback(family: AddressFamily): String? =
        memScoped {
            val target = resolveViaGetaddrinfo(if (family == AddressFamily.IPv4) "192.0.2.1" else "2001:db8::1", 9, numericOnly = true)
            if (target == null) return@memScoped null
            val fd = socket(if (family == AddressFamily.IPv4) AF_INET else AF_INET6, SOCK_DGRAM, 0)
            if (fd < 0) return@memScoped null
            try {
                val remote = alloc<sockaddr_storage>()
                val remoteLen = target.writeSockaddr(remote)
                if (connect(fd, remote.ptr.reinterpret(), remoteLen) != 0) return@memScoped null
                val local = alloc<sockaddr_storage>()
                val localLen = alloc<UIntVar>()
                localLen.value = sizeOf<sockaddr_storage>().convert()
                if (getsockname(fd, local.ptr.reinterpret(), localLen.ptr) != 0) return@memScoped null
                sockaddrToAppleSocketAddress(local.ptr.reinterpret())?.host
            } finally {
                close(fd)
            }
        }
}
