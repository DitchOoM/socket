@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkKind
import kotlinx.cinterop.*
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.NI_NUMERICHOST
import platform.posix.freeifaddrs
import platform.posix.getifaddrs
import platform.posix.getnameinfo
import platform.posix.if_nametoindex
import platform.posix.ifaddrs
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

/**
 * Apple actual for [enumerateNetworkInterfaces], from the POSIX `getifaddrs` (Network.framework's
 * `NWPathMonitor` reports only the *primary* path, not the full interface list). Addresses come from
 * `getnameinfo`+`NI_NUMERICHOST`; the index from `if_nametoindex`. [NetworkKind] is [NetworkKind.Other]
 * — a raw `getifaddrs` scan cannot classify link type (only the primary-path `AppleNetworkMonitor`
 * gets a semantic kind, from `NWPathMonitor`).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> =
    memScoped {
        val head = allocPointerTo<ifaddrs>()
        if (getifaddrs(head.ptr) != 0) return emptyList()
        try {
            val order = mutableListOf<String>()
            val addressesByName = mutableMapOf<String, MutableList<String>>()
            val flagsByName = mutableMapOf<String, Int>()
            val hostBuf = allocArray<ByteVar>(NI_MAXHOST)

            var cur = head.value
            while (cur != null) {
                val ifa = cur.pointed
                val name = ifa.ifa_name?.toKString()
                if (!name.isNullOrEmpty()) {
                    if (name !in addressesByName) {
                        order.add(name)
                        addressesByName[name] = mutableListOf()
                    }
                    flagsByName[name] = ifa.ifa_flags.toInt()

                    val sa = ifa.ifa_addr
                    if (sa != null) {
                        val salen =
                            when (sa.pointed.sa_family.toInt()) {
                                AF_INET -> sizeOf<sockaddr_in>()
                                AF_INET6 -> sizeOf<sockaddr_in6>()
                                else -> 0L
                            }
                        if (salen > 0L) {
                            val rc =
                                getnameinfo(sa, salen.toUInt(), hostBuf, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST)
                            if (rc == 0) {
                                val addr = hostBuf.toKString()
                                if (addr.isNotEmpty()) addressesByName[name]!!.add(addr)
                            }
                        }
                    }
                }
                cur = ifa.ifa_next
            }

            order.map { name ->
                val flags = flagsByName[name] ?: 0
                NetworkInterfaceInfo(
                    name = name,
                    index = if_nametoindex(name).toLong(),
                    kind = NetworkKind.Other(name),
                    addresses = addressesByName[name]!!.toList(),
                    isUp = (flags and IFF_UP) != 0,
                    isLoopback = (flags and IFF_LOOPBACK) != 0,
                )
            }
        } finally {
            freeifaddrs(head.value)
        }
    }

/** `NI_MAXHOST` from `<netdb.h>` — a frozen constant large enough for any numeric address+zone. */
private const val NI_MAXHOST = 1025
