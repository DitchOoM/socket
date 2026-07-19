@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.cinterop.*

/**
 * Linux actual for [enumerateNetworkInterfaces], from `getifaddrs`. Each interface's addresses are the
 * numeric literals of its `AF_INET`/`AF_INET6` records (via `getnameinfo`+`NI_NUMERICHOST`), its index
 * from `if_nametoindex`, and its [NetworkKind] from the same `/sys/class/net` classification the
 * primary-link derivation uses ([LinuxNetworkMonitor.classifyLinkKind]).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> =
    memScoped {
        val head = allocPointerTo<ifaddrs>()
        if (getifaddrs(head.ptr) != 0) return emptyList()
        try {
            // getifaddrs yields one record per (interface, address family), so accumulate addresses per
            // interface name in first-seen order (a nameless-address record still carries the flags).
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
                    kind = LinuxNetworkMonitor.classifyLinkKind(name),
                    addresses = addressesByName[name]!!.toList(),
                    isUp = (flags and IFF_UP) != 0,
                    isLoopback = (flags and IFF_LOOPBACK) != 0,
                )
            }
        } finally {
            freeifaddrs(head.value)
        }
    }

/** `NI_MAXHOST` from `<netdb.h>` — a frozen glibc constant (large enough for any numeric address+zone). */
private const val NI_MAXHOST = 1025
