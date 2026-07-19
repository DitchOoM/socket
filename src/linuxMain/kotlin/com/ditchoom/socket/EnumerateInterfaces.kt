@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*

/**
 * Linux actual for [enumerateNetworkInterfaces], from `getifaddrs`. Each interface's addresses are the
 * numeric literals of its `AF_INET`/`AF_INET6` records (via `getnameinfo`+`NI_NUMERICHOST`), its index
 * from `if_nametoindex`, and its [kind][NetworkInterfaceInfo.kind] from the same `/sys/class/net`
 * classification the primary-link derivation uses ([LinuxNetworkMonitor.classifyLinkKind]).
 */
actual fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo> =
    memScoped {
        val head = allocPointerTo<ifaddrs>()
        if (getifaddrs(head.ptr) != 0) return emptyList()
        try {
            // getifaddrs yields one record per (interface, address family). Accumulate each interface's
            // fields into ONE builder keyed by name (first-seen order) so index/flags/addresses always
            // travel together — no parallel maps that could drift out of sync.
            val byName = LinkedHashMap<String, InterfaceAccumulator>()
            val hostBuf = allocArray<ByteVar>(NI_MAXHOST)

            var cur = head.value
            while (cur != null) {
                val ifa = cur.pointed
                val name = ifa.ifa_name?.toKString()
                if (!name.isNullOrEmpty()) {
                    val acc = byName.getOrPut(name) { InterfaceAccumulator(name, ifa.ifa_flags.toInt()) }
                    ifa.ifa_addr?.let { sa ->
                        val salen =
                            when (sa.pointed.sa_family.toInt()) {
                                AF_INET -> sizeOf<sockaddr_in>()
                                AF_INET6 -> sizeOf<sockaddr_in6>()
                                else -> 0L
                            }
                        if (salen > 0L &&
                            getnameinfo(sa, salen.toUInt(), hostBuf, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST) == 0
                        ) {
                            val address = hostBuf.toKString()
                            if (address.isNotEmpty()) acc.addresses.add(address)
                        }
                    }
                }
                cur = ifa.ifa_next
            }

            byName.values.map { it.toInfo() }
        } finally {
            freeifaddrs(head.value)
        }
    }

/** Mutable per-interface record built up across a `getifaddrs` scan (see [enumerateNetworkInterfaces]). */
private class InterfaceAccumulator(
    private val name: String,
    private val flags: Int,
) {
    val addresses = mutableListOf<String>()

    fun toInfo() =
        NetworkInterfaceInfo(
            name = name,
            index = InterfaceIndex(if_nametoindex(name).toLong()),
            kind = LinuxNetworkMonitor.classifyLinkKind(name),
            addresses = addresses.toList(),
            isUp = (flags and IFF_UP) != 0,
            isLoopback = (flags and IFF_LOOPBACK) != 0,
        )
}

/** `NI_MAXHOST` from `<netdb.h>` — a frozen glibc constant (large enough for any numeric address+zone). */
private const val NI_MAXHOST = 1025
