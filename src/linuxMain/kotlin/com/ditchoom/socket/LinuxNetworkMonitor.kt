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

    /** Result of scanning one netlink reply chunk: the best (lowest-metric) default route in it. */
    internal class RouteScan(
        val oif: Int?,
        val metric: Int,
        val done: Boolean,
    )

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
         * 1. **Which link** = the *default-route* interface. Primary source is an authoritative netlink
         *    `RTM_GETROUTE` dump ([queryDefaultRouteOif]) — it is the kernel's own answer, works inside
         *    network namespaces/containers where `/proc/net/route` may be unpopulated, and covers IPv4
         *    **and** IPv6 (the `/proc/net/route` text file is IPv4-only). Falls back to parsing
         *    `/proc/net/route` (destination `00000000`, RTF_UP, lowest metric), then to the first up,
         *    non-loopback interface from the `getifaddrs` scan. Not a guess: it is what actually carries
         *    new connections, so with bridges/containers up (`docker0`, `br-*`) it still picks the real
         *    uplink.
         * 2. **What kind** = classified from `/sys/class/net/<iface>/`: a `wireless`/`phy80211` entry ⇒
         *    [NetworkKind.Wifi]; a `tun_flags` entry ⇒ [NetworkKind.Vpn]; a `wwan`/`rmnet`/`ppp` name ⇒
         *    [NetworkKind.Cellular]; ARPHRD type 1 ⇒ [NetworkKind.Ethernet]; else [NetworkKind.Other].
         * 3. **Which handle** = the OS interface index — the stable per-link discriminator QUIC
         *    auto-migration reacts to (the netlink path already yields the index; the text/scan fallbacks
         *    resolve it with `if_nametoindex`).
         *
         * [NetworkId.Unidentified] when nothing qualifies.
         */
        internal fun primaryNetworkId(): NetworkId {
            // Authoritative kernel query first (netns/container-safe, IPv4+IPv6).
            val oif = queryDefaultRouteOif()
            if (oif != null && oif > 0) {
                val name = interfaceName(oif)
                val kind = if (name != null) classifyLinkKind(name) else NetworkKind.Other("if$oif")
                return NetworkId.Link(kind, oif.toLong())
            }
            // Fallbacks: the IPv4 /proc text table, then the first up non-loopback interface.
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
        internal fun classifyLinkKind(iface: String): NetworkKind {
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

        /** Resolve an OS interface index to its name via `if_indextoname`, or null. */
        private fun interfaceName(index: Int): String? =
            memScoped {
                val buf = allocArray<ByteVar>(IF_NAMESIZE)
                if_indextoname(index.toUInt(), buf)
                    ?.toKString()
                    ?.takeIf { it.isNotEmpty() }
            }

        /**
         * Query the kernel routing table for the default route's output interface via an `RTM_GETROUTE`
         * dump on a short-lived `NETLINK_ROUTE` socket, returning the output-interface index of the
         * lowest-metric default route (destination prefix length 0), or null if none / on any error.
         *
         * `AF_UNSPEC` dumps both IPv4 and IPv6, so an IPv6-only default route is honored too. The reply
         * is parsed by the pure [scanDefaultRoutes] (unit-tested); a dump can span several `recv`s, so
         * we accumulate the global lowest-metric winner until `NLMSG_DONE`.
         */
        private fun queryDefaultRouteOif(): Int? =
            memScoped {
                val fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE)
                if (fd < 0) return null
                try {
                    val reqLen = NLMSGHDR_SIZE + RTMSG_SIZE
                    val req = allocArray<ByteVar>(reqLen)
                    for (i in 0 until reqLen) req[i] = 0
                    putU32(req, 0, reqLen.toUInt()) // nlmsg_len
                    putU16(req, 4, RTM_GETROUTE.toUShort()) // nlmsg_type
                    putU16(req, 6, (NLM_F_REQUEST or NLM_F_DUMP).toUShort()) // nlmsg_flags
                    putU32(req, 8, 1u) // nlmsg_seq
                    req[NLMSGHDR_SIZE] = AF_UNSPEC.toByte() // rtm_family
                    if (send(fd, req, reqLen.convert(), 0) < 0) return null

                    val cap = 8192
                    val resp = allocArray<ByteVar>(cap)
                    var bestOif: Int? = null
                    var bestMetric = Int.MAX_VALUE
                    while (true) {
                        val n = recv(fd, resp, cap.convert(), 0).toInt()
                        if (n <= 0) break
                        val scan = scanDefaultRoutes(resp, n)
                        if (scan.oif != null && scan.metric < bestMetric) {
                            bestOif = scan.oif
                            bestMetric = scan.metric
                        }
                        if (scan.done) break
                    }
                    bestOif
                } finally {
                    close(fd)
                }
            }

        /**
         * Pure walk of a netlink `RTM_GETROUTE` reply buffer (unit-tested): returns the lowest-metric
         * default route (`rtm_dst_len == 0`, unicast) output-interface index in this chunk, whether an
         * `NLMSG_DONE`/`NLMSG_ERROR` terminator was seen, and the winning metric. Manual struct offsets
         * because cinterop does not expose the `NLMSG_*`/`RTA_*` macros: `nlmsghdr` is 16 bytes, `rtmsg`
         * 12, each `rtattr` a 4-byte header, all `NLMSG_ALIGNTO`/`RTA_ALIGNTO`-aligned to 4. Fields are
         * host byte order (netlink), read directly through the pointer.
         */
        internal fun scanDefaultRoutes(
            buf: CPointer<ByteVar>,
            len: Int,
        ): RouteScan {
            var offset = 0
            var bestOif: Int? = null
            var bestMetric = Int.MAX_VALUE
            while (offset + NLMSGHDR_SIZE <= len) {
                val nlmsgLen = getU32(buf, offset).toInt()
                val nlmsgType = getU16(buf, offset + 4).toInt()
                if (nlmsgLen < NLMSGHDR_SIZE || offset + nlmsgLen > len) break
                if (nlmsgType == NLMSG_DONE || nlmsgType == NLMSG_ERROR) {
                    return RouteScan(bestOif, bestMetric, done = true)
                }
                if (nlmsgType == RTM_NEWROUTE.toInt()) {
                    val rtmOff = offset + NLMSGHDR_SIZE
                    val dstLen = getU8(buf, rtmOff + 1)
                    val rtmType = getU8(buf, rtmOff + 7)
                    if (dstLen == 0 && rtmType == RTN_UNICAST.toInt()) {
                        var oif: Int? = null
                        var metric = 0 // absent RTA_PRIORITY == kernel default (highest priority)
                        var attrOff = rtmOff + RTMSG_SIZE
                        val msgEnd = offset + nlmsgLen
                        while (attrOff + RTATTR_SIZE <= msgEnd) {
                            val rtaLen = getU16(buf, attrOff).toInt()
                            val rtaType = getU16(buf, attrOff + 2).toInt()
                            if (rtaLen < RTATTR_SIZE || attrOff + rtaLen > msgEnd) break
                            val payloadOff = attrOff + RTATTR_SIZE
                            if (payloadOff + 4 <= msgEnd) {
                                when (rtaType) {
                                    RTA_OIF -> oif = getU32(buf, payloadOff).toInt()
                                    RTA_PRIORITY -> metric = getU32(buf, payloadOff).toInt()
                                }
                            }
                            attrOff += align4(rtaLen)
                        }
                        if (oif != null && oif > 0 && metric < bestMetric) {
                            bestOif = oif
                            bestMetric = metric
                        }
                    }
                }
                offset += align4(nlmsgLen)
            }
            return RouteScan(bestOif, bestMetric, done = false)
        }

        private fun align4(v: Int): Int = (v + 3) and 3.inv()

        private fun getU8(
            p: CPointer<ByteVar>,
            off: Int,
        ): Int = p[off].toUByte().toInt()

        private fun getU16(
            p: CPointer<ByteVar>,
            off: Int,
        ): Int =
            (p + off)!!
                .reinterpret<UShortVar>()
                .pointed.value
                .toInt()

        private fun getU32(
            p: CPointer<ByteVar>,
            off: Int,
        ): UInt = (p + off)!!.reinterpret<UIntVar>().pointed.value

        private fun putU16(
            p: CPointer<ByteVar>,
            off: Int,
            value: UShort,
        ) {
            (p + off)!!.reinterpret<UShortVar>().pointed.value = value
        }

        private fun putU32(
            p: CPointer<ByteVar>,
            off: Int,
            value: UInt,
        ) {
            (p + off)!!.reinterpret<UIntVar>().pointed.value = value
        }

        private const val NLMSGHDR_SIZE = 16
        private const val RTMSG_SIZE = 12
        private const val RTATTR_SIZE = 4
        private const val IF_NAMESIZE = 16

        // Frozen Linux UAPI attribute types (enum rtattr_type_t in <linux/rtnetlink.h>). cinterop turns
        // that *named* enum into a Kotlin enum class, so the entries aren't usable as `when` constants;
        // these values are part of the stable kernel ABI and never change.
        private const val RTA_OIF = 4
        private const val RTA_PRIORITY = 6

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
