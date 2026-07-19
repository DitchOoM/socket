@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Linux [NetworkMonitor] using netlink sockets for event-driven network change detection.
 *
 * Uses `AF_NETLINK` / `NETLINK_ROUTE` with `RTMGRP_LINK | RTMGRP_IPV4_IFADDR` multicast
 * groups to receive kernel notifications when network interfaces change state or gain/lose
 * addresses. On each notification, re-checks actual state via `getifaddrs()`.
 *
 * This hybrid approach avoids parsing complex netlink message attributes while still
 * being event-driven (no polling).
 */
class LinuxNetworkMonitor : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()
    private val _networkId = MutableStateFlow<NetworkId>(NetworkId.Unidentified)
    override val networkId: StateFlow<NetworkId> = _networkId.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var netlinkFd: Int = -1

    init {
        netlinkFd = createNetlinkSocket()
        _availability.value = checkInterfaces()
        _networkId.value = primaryNetworkId()

        if (netlinkFd >= 0) {
            scope.launch {
                // Allocate a native scratch buffer once, reuse across netlink recvs.
                // Deterministic (malloc/free) so it's freed when the coroutine exits —
                // no GC-managed ByteArray, no per-iteration pin/unpin.
                val scratch = BufferFactory.deterministic().allocate(4096)
                try {
                    val ptr =
                        scratch.nativeMemoryAccess!!
                            .nativeAddress
                            .toCPointer<ByteVar>()!!
                    while (isActive) {
                        val n = recv(netlinkFd, ptr, 4096.toULong(), 0)
                        if (n <= 0) break
                        _availability.value = checkInterfaces()
                        _networkId.value = primaryNetworkId()
                    }
                } finally {
                    scratch.freeNativeMemory()
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        if (netlinkFd >= 0) {
            close(netlinkFd)
            netlinkFd = -1
        }
    }

    companion object {
        private fun createNetlinkSocket(): Int =
            memScoped {
                val fd = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
                if (fd < 0) return -1

                val addr = alloc<sockaddr_nl>()
                addr.nl_family = AF_NETLINK.toUShort()
                addr.nl_pid = 0u
                addr.nl_groups = (RTMGRP_LINK or RTMGRP_IPV4_IFADDR).toUInt()

                val bindResult = socket_bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_nl>().toUInt())
                if (bindResult < 0) {
                    close(fd)
                    return -1
                }
                fd
            }

        internal fun checkInterfaces(): NetworkAvailability =
            memScoped {
                val ifaddrsPtr = allocPointerTo<ifaddrs>()
                if (getifaddrs(ifaddrsPtr.ptr) != 0) return NetworkAvailability.UNKNOWN

                var current = ifaddrsPtr.value
                var hasNonLoopback = false
                while (current != null) {
                    val flags = current.pointed.ifa_flags.toInt()
                    val isUp = (flags and IFF_UP) != 0
                    val isLoopback = (flags and IFF_LOOPBACK) != 0
                    if (isUp && !isLoopback) {
                        hasNonLoopback = true
                        break
                    }
                    current = current.pointed.ifa_next
                }
                freeifaddrs(ifaddrsPtr.value)

                if (hasNonLoopback) NetworkAvailability.AVAILABLE else NetworkAvailability.UNAVAILABLE
            }

        /**
         * Route-aware primary-link identity — the Linux answer to Apple's `NWPathMonitor` primary
         * interface, built from the kernel facts this monitor already has access to (the kernel tier;
         * the framework-level richness Android adds — authoritative cellular, metered, validated — lives
         * in NetworkManager/D-Bus, not the kernel Android and desktop Linux share):
         *
         * 1. **Which link** = the *default-route* interface (`/proc/net/route` destination `00000000`,
         *    RTF_UP, lowest metric) — not a guess. It is what actually carries new connections, so with
         *    bridges/containers up (`docker0`, `br-*`) it still picks the real uplink. Falls back to the
         *    first up, non-loopback interface from the `getifaddrs` scan when there is no default route.
         * 2. **What kind** = classified from `/sys/class/net/<iface>/`: a `wireless`/`phy80211` entry ⇒
         *    [NetworkKind.Wifi]; a `tun_flags` entry ⇒ [NetworkKind.Vpn]; a `wwan`/`rmnet`/`ppp` name ⇒
         *    [NetworkKind.Cellular]; ARPHRD type 1 ⇒ [NetworkKind.Ethernet]; else [NetworkKind.Other].
         * 3. **Which handle** = the OS interface index (`if_nametoindex`) — the stable per-link
         *    discriminator QUIC auto-migration reacts to.
         *
         * [NetworkId.Unidentified] when nothing qualifies.
         */
        internal fun primaryNetworkId(): NetworkId {
            val iface =
                readFileOrNull("/proc/net/route")?.let { parseDefaultRouteInterface(it) }
                    ?: firstUpNonLoopbackInterface()
                    ?: return NetworkId.Unidentified
            val idx = if_nametoindex(iface).toLong()
            if (idx <= 0L) return NetworkId.Unidentified
            return NetworkId.Link(classifyLinkKind(iface), idx)
        }

        /**
         * Parse the default-route interface from `/proc/net/route` text (pure — unit-tested): the row
         * whose Destination is `00000000` (0.0.0.0/0) with RTF_UP set, choosing the lowest metric when
         * several exist. Columns: `Iface Destination Gateway Flags RefCnt Use Metric Mask ...`.
         */
        internal fun parseDefaultRouteInterface(routeTable: String): String? =
            routeTable
                .lineSequence()
                .drop(1) // header row
                .mapNotNull { line ->
                    val cols = line.trim().split(WHITESPACE)
                    if (cols.size < 8) return@mapNotNull null
                    val flags = cols[3].toIntOrNull(16) ?: 0
                    if (cols[1] != "00000000" || (flags and RTF_UP_FLAG) == 0) return@mapNotNull null
                    cols[0] to (cols[6].toIntOrNull() ?: Int.MAX_VALUE)
                }.minByOrNull { it.second }
                ?.first

        /** First up, non-loopback interface from `getifaddrs` — the fallback when there is no default route. */
        private fun firstUpNonLoopbackInterface(): String? =
            memScoped {
                val ifaddrsPtr = allocPointerTo<ifaddrs>()
                if (getifaddrs(ifaddrsPtr.ptr) != 0) return null

                var current = ifaddrsPtr.value
                var name: String? = null
                while (current != null) {
                    val flags = current.pointed.ifa_flags.toInt()
                    if ((flags and IFF_UP) != 0 && (flags and IFF_LOOPBACK) == 0) {
                        name = current.pointed.ifa_name?.toKString()
                        if (!name.isNullOrEmpty()) break
                    }
                    current = current.pointed.ifa_next
                }
                freeifaddrs(ifaddrsPtr.value)
                name?.takeIf { it.isNotEmpty() }
            }

        /** Classify a link kind from the kernel's `/sys/class/net/<iface>/` view (see [primaryNetworkId]). */
        private fun classifyLinkKind(iface: String): NetworkKind {
            val base = "/sys/class/net/$iface"
            return classifyLinkKind(
                iface = iface,
                hasWireless = access("$base/wireless", F_OK) == 0 || access("$base/phy80211", F_OK) == 0,
                hasTunFlags = access("$base/tun_flags", F_OK) == 0,
                arphrdType = readFileOrNull("$base/type")?.trim()?.toIntOrNull(),
            )
        }

        /**
         * Pure link-kind classification from the `/sys/class/net/<iface>/` facts (unit-tested): a
         * `wireless`/`phy80211` entry ([hasWireless]) ⇒ [NetworkKind.Wifi]; a `tun_flags` entry
         * ([hasTunFlags]) ⇒ [NetworkKind.Vpn]; a `wwan`/`rmnet`/`ppp` name ⇒ [NetworkKind.Cellular];
         * ARPHRD [arphrdType] 1 (`ARPHRD_ETHER`) ⇒ [NetworkKind.Ethernet]; else diagnostic
         * [NetworkKind.Other]. Wi-Fi wins over the Ethernet ARPHRD type (a Wi-Fi NIC also reports
         * `ARPHRD_ETHER`), and the tunnel check precedes the cellular name check.
         */
        internal fun classifyLinkKind(
            iface: String,
            hasWireless: Boolean,
            hasTunFlags: Boolean,
            arphrdType: Int?,
        ): NetworkKind =
            when {
                hasWireless -> NetworkKind.Wifi
                hasTunFlags -> NetworkKind.Vpn()
                iface.startsWith("wwan") || iface.startsWith("rmnet") || iface.startsWith("ppp") -> NetworkKind.Cellular
                arphrdType == ARPHRD_ETHER -> NetworkKind.Ethernet
                else -> NetworkKind.Other(iface)
            }

        /** Read a small `/proc` or `/sys` file into a native buffer and return its text, or null on any error. */
        private fun readFileOrNull(path: String): String? =
            memScoped {
                val fd = open(path, O_RDONLY)
                if (fd < 0) return null
                try {
                    val cap = 16384
                    val buf = allocArray<ByteVar>(cap)
                    val n = read(fd, buf, (cap - 1).convert()).toInt()
                    if (n < 0) return null
                    buf[n] = 0
                    buf.toKString()
                } finally {
                    close(fd)
                }
            }

        private val WHITESPACE = Regex("""\s+""")
        private const val RTF_UP_FLAG = 0x0001
        private const val ARPHRD_ETHER = 1
    }
}

/** Creates a Linux [NetworkMonitor] using netlink sockets (event-driven). */
fun NetworkMonitor.Companion.create(): NetworkMonitor = LinuxNetworkMonitor()
