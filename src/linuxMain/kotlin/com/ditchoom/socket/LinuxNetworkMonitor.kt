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
 * Uses `AF_NETLINK` / `NETLINK_ROUTE` bound to the `RTMGRP_LINK`, `RTMGRP_IPV4_IFADDR`,
 * `RTMGRP_IPV6_IFADDR`, `RTMGRP_IPV4_ROUTE` and `RTMGRP_IPV6_ROUTE` multicast groups, so the kernel
 * pushes link, address **and route** changes. The route groups are not completeness — they are
 * load-bearing for the rung: the default route alone decides [NetworkState.LinkLocal] vs
 * [NetworkState.Routable] (RFC_NETWORK_REACHABILITY §1.1), and a route can change with no link or
 * address event at all — `ip route del default` touches nothing else, and the common DHCP sequence
 * installs the default route *after* the address is already configured. With only the link/ifaddr
 * groups either transition would go unseen and the monitor would hold the wrong rung indefinitely.
 * On each notification, re-resolves the actual state via an `RTM_GETROUTE` dump plus `getifaddrs()`.
 *
 * This hybrid approach avoids parsing complex netlink message *attributes* on the notification path
 * while still being event-driven (no polling).
 *
 * Its capability is [ReachResolution.RouteOnly]: the kernel knows about links and routes and nothing
 * about whether traffic reaches the internet, so every [NetworkState.Routable] carries
 * [InternetAccess.Unobserved]. The top rung needs NetworkManager's `NMConnectivityState` over D-Bus,
 * deferred by RFC_NETWORK_REACHABILITY §8.2 — and deferrable precisely because [capability] is a value
 * read at construction, so upgrading this to [ReachResolution.RouteAndInternet] later is
 * source-compatible.
 *
 * The route half is the §1.1 fix: the pre-RFC monitor reported plain `AVAILABLE` whenever any
 * non-loopback interface was up, so a container with only `docker0` and no default route looked
 * identical to a working uplink. It now reports [NetworkState.LinkLocal] there — which is honest, and
 * still useful, since mDNS and multicast work on it.
 */
class LinuxNetworkMonitor : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /**
     * Link, address and route netlink multicast (the five `RTMGRP_*` groups in the class KDoc) — the
     * kernel pushes, we never poll — and the kernel resolves routes but never probes the internet
     * (RFC §8.2).
     */
    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteOnly)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var netlinkFd: Int = -1

    init {
        netlinkFd = createNetlinkSocket()
        _state.value = resolveNetworkState()

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
                        // ENOBUFS is not a dead socket: the kernel dropped notifications because the
                        // socket's kernel receive queue (SO_RCVBUF — not this 4096-byte scratch buffer)
                        // overflowed before we drained it (netlink(7) "reliable transmission") — likelier
                        // now that the route groups multiply the event rate. Dropped *messages* cost nothing
                        // here, because we never parse them: any wake-up triggers one full re-resolution,
                        // which observes whatever end state the dropped burst left behind. So re-resolve
                        // and keep listening; anything else (0 = closed, other errors) ends the loop.
                        if (n == 0L || (n < 0L && errno != ENOBUFS)) break
                        // One re-resolution, one publication: link, route and identity come from the same
                        // pass, so a collector can never see a new link beside the old route.
                        _state.value = resolveNetworkState()
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

    /**
     * A default route resolved from a netlink dump. Sealed so the "no default route" case is a
     * distinct, named state rather than a `null` output-interface index paired with a meaningless
     * metric — the two can never be observed apart.
     */
    internal sealed interface DefaultRoute {
        /** The lowest-metric default route: [oif] output-interface index (always > 0), [metric] its RTA_PRIORITY. */
        data class Via(
            val oif: InterfaceIndex,
            val metric: Int,
        ) : DefaultRoute

        /** No default route was present. */
        data object None : DefaultRoute
    }

    /**
     * The host's primary link, carrying **why** it is primary — which is the whole point: an interface
     * that owns the default route and one that merely happens to be up are different rungs of the ladder
     * ([NetworkState.Routable] vs [NetworkState.LinkLocal]), and the pre-RFC monitor lost that
     * distinction by returning only a [NetworkId].
     *
     * [id] is on the interface so a caller wanting identity alone (`primaryNetworkId`, the netns harness)
     * needs no `when`, exactly as [NetworkState.Up] does it.
     */
    internal sealed interface PrimaryLink {
        val id: NetworkId

        /** A default route resolved; new connections leave the host through this link. */
        data class Routed(
            override val id: NetworkId,
        ) : PrimaryLink

        /** A non-loopback link is up but no default route resolves — link-local reach only. */
        data class Unrouted(
            override val id: NetworkId,
        ) : PrimaryLink

        /** No non-loopback link is up. */
        data object NoLink : PrimaryLink {
            override val id: NetworkId get() = NetworkId.Unidentified
        }

        /** `getifaddrs` itself failed — we do not know, which is not the same as knowing there is nothing. */
        data object Undetermined : PrimaryLink {
            override val id: NetworkId get() = NetworkId.Unidentified
        }
    }

    /**
     * Outcome of the `getifaddrs` link scan. Sealed rather than a `String?` because "the scan failed" and
     * "the scan found nothing" map to different rungs ([NetworkState.Unknown] vs [NetworkState.Offline])
     * and a null would fuse them — the §1.1 class of bug, one level down.
     */
    internal sealed interface LinkScan {
        /** The first up, non-loopback interface. */
        data class Up(
            val iface: String,
        ) : LinkScan

        /** The scan succeeded and nothing non-loopback is up. */
        data object NoLink : LinkScan

        /** `getifaddrs` failed (or named nothing usable) — no answer either way. */
        data object Unavailable : LinkScan
    }

    /**
     * Outcome of scanning one netlink reply chunk: the best [route] found in it, plus whether the dump
     * has ended. [End] vs [More] replaces a `done: Boolean` so the terminator is a state, not a flag.
     */
    internal sealed interface ChunkScan {
        val route: DefaultRoute

        /** More reply chunks may follow (no terminator seen yet). */
        data class More(
            override val route: DefaultRoute,
        ) : ChunkScan

        /** The dump terminated (`NLMSG_DONE` / `NLMSG_ERROR`) — stop reading. */
        data class End(
            override val route: DefaultRoute,
        ) : ChunkScan
    }

    companion object {
        // The full multicast set the rung contract needs. Link + v4/v6 ifaddr wake us for interface and
        // address changes; the ROUTE groups are load-bearing (see the class KDoc): the default route alone
        // decides LinkLocal vs Routable, and `ip route del default` — or DHCP installing the default route
        // after the address is already configured — emits ONLY a route message.
        private val NETLINK_GROUPS: UInt =
            (RTMGRP_LINK or RTMGRP_IPV4_IFADDR or RTMGRP_IPV6_IFADDR or RTMGRP_IPV4_ROUTE or RTMGRP_IPV6_ROUTE)
                .toUInt()

        private fun createNetlinkSocket(): Int =
            memScoped {
                val fd = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
                if (fd < 0) return -1

                val addr = alloc<sockaddr_nl>()
                addr.nl_family = AF_NETLINK.toUShort()
                addr.nl_pid = 0u
                addr.nl_groups = NETLINK_GROUPS

                val bindResult = socket_bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_nl>().toUInt())
                if (bindResult < 0) {
                    close(fd)
                    return -1
                }
                fd
            }

        /**
         * Pure mapper from the resolved [PrimaryLink] to a [NetworkState] — the Linux rung table of
         * RFC_NETWORK_REACHABILITY §4, unit-testable with no kernel at all.
         *
         * | [PrimaryLink] | Result |
         * |---|---|
         * | [Routed][PrimaryLink.Routed] | `Routable(id, Unobserved)` |
         * | [Unrouted][PrimaryLink.Unrouted] | [NetworkState.LinkLocal] — the §1.1 fix |
         * | [NoLink][PrimaryLink.NoLink] | [NetworkState.Offline] |
         * | [Undetermined][PrimaryLink.Undetermined] | [NetworkState.Unknown] — never a silent "offline" |
         *
         * [InternetAccess.Unobserved] is unconditional: the kernel has no reachability verdict to give,
         * and [capability] declares [ReachResolution.RouteOnly] so no consumer waits for one.
         */
        internal fun linuxNetworkState(link: PrimaryLink): NetworkState =
            when (link) {
                is PrimaryLink.Routed -> NetworkState.Routable(link.id, InternetAccess.Unobserved)
                is PrimaryLink.Unrouted -> NetworkState.LinkLocal(link.id)
                PrimaryLink.NoLink -> NetworkState.Offline
                PrimaryLink.Undetermined -> NetworkState.Unknown
            }

        /** Resolve the current [NetworkState] from the kernel: [primaryLink] through [linuxNetworkState]. */
        internal fun resolveNetworkState(): NetworkState = linuxNetworkState(primaryLink())

        /**
         * Route-aware primary-link identity — the Linux answer to Apple's `NWPathMonitor` primary
         * interface, built from the kernel facts this monitor already has access to (the kernel tier;
         * the framework-level richness Android adds — authoritative cellular, metered, validated — lives
         * in NetworkManager/D-Bus, not the kernel Android and desktop Linux share):
         *
         * 1. **Which link** = the *default-route* interface. Primary source is an authoritative netlink
         *    `RTM_GETROUTE` dump ([queryDefaultRouteOif]) — it is the kernel's own answer, works inside
         *    network namespaces/containers where `/proc/net/route` may be unpopulated, and covers IPv4
         *    **and** IPv6 (the `/proc/net/route` text file is IPv4-only). Falls back — only when netlink
         *    is unavailable (a sandbox restricting `NETLINK_ROUTE`) — to parsing `/proc/net/route`
         *    (IPv4, destination `00000000`, RTF_UP, lowest metric), then `/proc/net/ipv6_route` (IPv6,
         *    destination `::/0`, RTF_UP, lowest metric, so an IPv6-only host is still route-aware), then
         *    to the first up, non-loopback interface from the `getifaddrs` scan. Not a guess: it is what
         *    actually carries
         *    new connections, so with bridges/containers up (`docker0`, `br-*`) it still picks the real
         *    uplink.
         * 2. **What kind** = classified from `/sys/class/net/<iface>/`: a `wireless`/`phy80211` entry ⇒
         *    [NetworkKind.Wifi]; a `tun_flags` entry ⇒ [NetworkKind.Vpn]; a `wwan`/`rmnet`/`ppp` name ⇒
         *    [NetworkKind.Cellular]; ARPHRD type 1 ⇒ [NetworkKind.Ethernet]; else [NetworkKind.Other].
         * 3. **Which handle** = the OS interface index — the stable per-link discriminator QUIC
         *    auto-migration reacts to (the netlink path already yields the index; the text/scan fallbacks
         *    resolve it with `if_nametoindex`).
         *
         * [PrimaryLink.NoLink] when nothing qualifies — and [PrimaryLink.Routed] vs
         * [PrimaryLink.Unrouted] records *which* of the tiers above answered, because that is exactly the
         * `Routable`-vs-`LinkLocal` distinction [linuxNetworkState] needs and the pre-RFC
         * `NetworkId`-only return threw away.
         */
        internal fun primaryLink(): PrimaryLink {
            // Authoritative kernel query first (netns/container-safe, IPv4+IPv6).
            when (val route = queryDefaultRoute()) {
                is DefaultRoute.Via -> return PrimaryLink.Routed(NetworkId.Link(classifyOif(route.oif), route.oif.value))
                DefaultRoute.None -> Unit // no kernel default route — fall through to the /proc + scan fallbacks
            }
            return primaryLinkFromProcFallback()
        }

        /**
         * Route-aware primary-link **identity** — [primaryLink] with the reason dropped. Kept as its own
         * function because the netns integration harness and the transport layer both want just the
         * identity, and [PrimaryLink.id] gives it without a `when`.
         */
        internal fun primaryNetworkId(): NetworkId = primaryLink().id

        /**
         * The non-netlink fallback tier of [primaryLink], split out so it is directly reachable by
         * the netns integration harness (`test-harness/netns`). netlink is available on any normal test
         * host, so [queryDefaultRoute] short-circuits and this branch would otherwise never execute at
         * runtime — the harness calls it directly against a namespace's real `/proc` + `/sys` to prove it.
         *
         * Resolves the default-route interface from the IPv4 `/proc/net/route` text table, then its IPv6
         * companion `/proc/net/ipv6_route` (so an IPv6-only host stays route-aware) — either of which is
         * a [PrimaryLink.Routed]. Only if neither names a route does it fall back to the first up
         * non-loopback interface from the `getifaddrs` scan, which is [PrimaryLink.Unrouted]: a link with
         * no default route behind it. Kind comes from `/sys/class/net` in every case.
         */
        internal fun primaryLinkFromProcFallback(): PrimaryLink {
            val routedIface =
                readFileOrNull("/proc/net/route")?.let { parseDefaultRouteInterface(it) }
                    ?: readFileOrNull("/proc/net/ipv6_route")?.let { parseDefaultRouteInterfaceV6(it) }
            if (routedIface != null) {
                return when (val id = linkIdFor(routedIface)) {
                    NetworkId.Unidentified -> PrimaryLink.Undetermined // named a route we cannot resolve
                    else -> PrimaryLink.Routed(id)
                }
            }
            return when (val scan = scanFirstUpNonLoopbackInterface()) {
                is LinkScan.Up ->
                    when (val id = linkIdFor(scan.iface)) {
                        NetworkId.Unidentified -> PrimaryLink.Undetermined
                        else -> PrimaryLink.Unrouted(id)
                    }
                LinkScan.NoLink -> PrimaryLink.NoLink
                LinkScan.Unavailable -> PrimaryLink.Undetermined
            }
        }

        /** [primaryLinkFromProcFallback]'s identity, for the netns harness that only wants the [NetworkId]. */
        internal fun primaryNetworkIdFromProcFallback(): NetworkId = primaryLinkFromProcFallback().id

        /** Typed identity of a named interface: its OS index plus `/sys`-derived kind, or Unidentified. */
        private fun linkIdFor(iface: String): NetworkId {
            val idx = if_nametoindex(iface).toLong()
            if (idx <= 0L) return NetworkId.Unidentified
            return NetworkId.Link(classifyLinkKind(iface), idx)
        }

        /** Classify the interface behind an OS index, keeping a diagnostic name when it can't be resolved. */
        private fun classifyOif(oif: InterfaceIndex): NetworkKind {
            val name = interfaceName(oif)
            return if (name != null) classifyLinkKind(name) else NetworkKind.Other("if${oif.value}")
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

        /**
         * Parse the default-route interface from `/proc/net/ipv6_route` text (pure — unit-tested): the
         * IPv6 companion to [parseDefaultRouteInterface]. The authoritative netlink `RTM_GETROUTE` dump
         * already covers IPv6, so this only matters as a fallback when netlink is unavailable on an
         * IPv6-only host. Unlike `/proc/net/route` this file has **no header row**, every field is hex,
         * and the interface name is the **last** column. Columns:
         * `destNetwork(32) destPrefixLen(2) srcNetwork(32) srcPrefixLen(2) nextHop(32) metric(8) refcnt(8) use(8) flags(8) iface`.
         *
         * A default route is `::/0` — an all-zero 128-bit destination with prefix length `00` — that is
         * RTF_UP and **not** RTF_REJECT (systemd installs an `unreachable default dev lo` reject entry
         * whose destination is also `::/0`; without the reject filter the fallback would pick `lo`).
         * Lowest metric wins, matching the IPv4 parser. Flags/metric are read as [Long] because the
         * 32-bit hex fields can set the high bit (e.g. RTF_LOCAL `0x80000000`), which an [Int] parse rejects.
         */
        internal fun parseDefaultRouteInterfaceV6(routeTable: String): String? =
            routeTable
                .lineSequence()
                .mapNotNull { line ->
                    val cols = line.trim().split(WHITESPACE)
                    if (cols.size < 10) return@mapNotNull null
                    val isDefault = cols[0] == V6_ANY_ADDR && cols[1] == "00"
                    val flags = cols[8].toLongOrNull(16) ?: 0L
                    val usable = (flags and RTF_UP_FLAG.toLong()) != 0L && (flags and RTF_REJECT_FLAG) == 0L
                    if (!isDefault || !usable) return@mapNotNull null
                    cols[9] to (cols[5].toLongOrNull(16) ?: Long.MAX_VALUE)
                }.minByOrNull { it.second }
                ?.first

        /**
         * First up, non-loopback interface from `getifaddrs` — the fallback when there is no default
         * route, and the sole source of "is there any link at all".
         *
         * Returns a [LinkScan] rather than a `String?` so a failed `getifaddrs` ([LinkScan.Unavailable] →
         * [NetworkState.Unknown]) stays distinguishable from a successful scan that found nothing
         * ([LinkScan.NoLink] → [NetworkState.Offline]). Reporting a host as offline because the syscall
         * failed is the mistake this whole RFC is about.
         */
        private fun scanFirstUpNonLoopbackInterface(): LinkScan =
            memScoped {
                val ifaddrsPtr = allocPointerTo<ifaddrs>()
                if (getifaddrs(ifaddrsPtr.ptr) != 0) return LinkScan.Unavailable

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
                val iface = name?.takeIf { it.isNotEmpty() }
                if (iface != null) LinkScan.Up(iface) else LinkScan.NoLink
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
        private fun interfaceName(index: InterfaceIndex): String? =
            memScoped {
                val buf = allocArray<ByteVar>(IF_NAMESIZE)
                if_indextoname(index.value.toUInt(), buf)
                    ?.toKString()
                    ?.takeIf { it.isNotEmpty() }
            }

        /**
         * Query the kernel routing table for the default route via an `RTM_GETROUTE` dump on a
         * short-lived `NETLINK_ROUTE` socket. Returns the lowest-metric [DefaultRoute.Via] (destination
         * prefix length 0), or [DefaultRoute.None] if there is none / on any error.
         *
         * `AF_UNSPEC` dumps both IPv4 and IPv6, so an IPv6-only default route is honored too. The reply
         * is parsed by the pure [scanDefaultRoutes] (unit-tested); a dump can span several `recv`s, so
         * we fold the per-chunk winner into the global lowest-metric one until the dump ends.
         */
        private fun queryDefaultRoute(): DefaultRoute =
            memScoped {
                val fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE)
                if (fd < 0) return DefaultRoute.None
                try {
                    // Bound the blocking recv. On a sandboxed host (e.g. a CI runner that restricts
                    // netlink via seccomp/AppArmor) send() can succeed while no reply ever arrives, and
                    // an untimed recv() would hang forever; likewise a mis-detected NLMSG_DONE would
                    // block waiting for a message that never comes. SO_RCVTIMEO + an iteration guard
                    // degrade both to "no route → fall back to /proc/net/route" instead of a hang.
                    val tv = alloc<timeval>()
                    tv.tv_sec = 0.convert()
                    tv.tv_usec = RECV_TIMEOUT_USEC.convert()
                    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

                    val reqLen = NLMSGHDR_SIZE + RTMSG_SIZE
                    val req = allocArray<ByteVar>(reqLen)
                    for (i in 0 until reqLen) req[i] = 0
                    putU32(req, 0, reqLen.toUInt()) // nlmsg_len
                    putU16(req, 4, RTM_GETROUTE.toUShort()) // nlmsg_type
                    putU16(req, 6, (NLM_F_REQUEST or NLM_F_DUMP).toUShort()) // nlmsg_flags
                    putU32(req, 8, 1u) // nlmsg_seq
                    req[NLMSGHDR_SIZE] = AF_UNSPEC.toByte() // rtm_family
                    if (send(fd, req, reqLen.convert(), 0) < 0) return DefaultRoute.None

                    val cap = 8192
                    val resp = allocArray<ByteVar>(cap)
                    var best: DefaultRoute = DefaultRoute.None
                    var iterations = 0
                    while (iterations++ < MAX_RECV_ITERATIONS) {
                        val n = recv(fd, resp, cap.convert(), 0).toInt()
                        if (n <= 0) break // timeout (EAGAIN), peer EOF, or error — stop, use what we have
                        val scan = scanDefaultRoutes(resp, n)
                        best = lowerMetric(best, scan.route)
                        if (scan is ChunkScan.End) break
                    }
                    best
                } finally {
                    close(fd)
                }
            }

        /** The lower-metric of two [DefaultRoute]s ([DefaultRoute.None] acts as "no route" / +∞ metric). */
        private fun lowerMetric(
            a: DefaultRoute,
            b: DefaultRoute,
        ): DefaultRoute =
            when {
                a is DefaultRoute.Via && b is DefaultRoute.Via -> if (b.metric < a.metric) b else a
                b is DefaultRoute.Via -> b
                else -> a
            }

        /**
         * Pure walk of a netlink `RTM_GETROUTE` reply buffer (unit-tested): the lowest-metric default
         * route (`rtm_dst_len == 0`, unicast) in this chunk, wrapped in [ChunkScan.End] when an
         * `NLMSG_DONE`/`NLMSG_ERROR` terminator was seen, else [ChunkScan.More]. Manual struct offsets
         * because cinterop does not expose the `NLMSG_*`/`RTA_*` macros: `nlmsghdr` is 16 bytes, `rtmsg`
         * 12, each `rtattr` a 4-byte header, all `NLMSG_ALIGNTO`/`RTA_ALIGNTO`-aligned to 4. Fields are
         * host byte order (netlink), read directly through the pointer.
         */
        internal fun scanDefaultRoutes(
            buf: CPointer<ByteVar>,
            len: Int,
        ): ChunkScan {
            var offset = 0
            var best: DefaultRoute = DefaultRoute.None
            while (offset + NLMSGHDR_SIZE <= len) {
                val nlmsgLen = getU32(buf, offset).toInt()
                val nlmsgType = getU16(buf, offset + 4).toInt()
                if (nlmsgLen < NLMSGHDR_SIZE || offset + nlmsgLen > len) break
                if (nlmsgType == NLMSG_DONE || nlmsgType == NLMSG_ERROR) return ChunkScan.End(best)
                if (nlmsgType == RTM_NEWROUTE.toInt()) {
                    val rtmOff = offset + NLMSGHDR_SIZE
                    val dstLen = getU8(buf, rtmOff + 1)
                    val rtmType = getU8(buf, rtmOff + 7)
                    if (dstLen == 0 && rtmType == RTN_UNICAST.toInt()) {
                        best = lowerMetric(best, parseRouteAttrs(buf, rtmOff + RTMSG_SIZE, offset + nlmsgLen))
                    }
                }
                offset += align4(nlmsgLen)
            }
            return ChunkScan.More(best)
        }

        /**
         * Parse a route message's rtattrs in `[start, end)` into a [DefaultRoute]: [DefaultRoute.Via]
         * when an `RTA_OIF` is present (its `RTA_PRIORITY`, or 0 if absent = kernel default), else
         * [DefaultRoute.None]. `oif == 0` is the natural "no RTA_OIF yet" scratch value — a real OS
         * interface index is always ≥ 1 — and never escapes as state (it becomes [DefaultRoute.None]).
         */
        private fun parseRouteAttrs(
            buf: CPointer<ByteVar>,
            start: Int,
            end: Int,
        ): DefaultRoute {
            var oif = 0
            var metric = 0
            var attrOff = start
            while (attrOff + RTATTR_SIZE <= end) {
                val rtaLen = getU16(buf, attrOff).toInt()
                val rtaType = getU16(buf, attrOff + 2).toInt()
                if (rtaLen < RTATTR_SIZE || attrOff + rtaLen > end) break
                val payloadOff = attrOff + RTATTR_SIZE
                if (payloadOff + 4 <= end) {
                    when (rtaType) {
                        RTA_OIF -> oif = getU32(buf, payloadOff).toInt()
                        RTA_PRIORITY -> metric = getU32(buf, payloadOff).toInt()
                    }
                }
                attrOff += align4(rtaLen)
            }
            return if (oif > 0) DefaultRoute.Via(InterfaceIndex(oif.toLong()), metric) else DefaultRoute.None
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

        // Netlink recv guards (see queryDefaultRoute): a real RTM_GETROUTE reply lands in microseconds,
        // so 250ms is generous; the iteration cap bounds even a pathological stream of non-terminating
        // messages. Both exist only to make a restricted/misbehaving netlink socket non-hanging.
        private const val RECV_TIMEOUT_USEC = 250_000
        private const val MAX_RECV_ITERATIONS = 64

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

        // /proc/net/ipv6_route default-route matching (see parseDefaultRouteInterfaceV6): RTF_REJECT
        // (0x0200) marks systemd's `unreachable default dev lo` entry, which must not win the fallback;
        // V6_ANY_ADDR is the 128-bit all-zero destination (::) that, with prefix length 0, is ::/0.
        private const val RTF_REJECT_FLAG = 0x0200L
        private const val V6_ANY_ADDR = "00000000000000000000000000000000"
        private const val ARPHRD_ETHER = 1
    }
}

/** Creates a Linux [NetworkMonitor] using netlink sockets (event-driven). */
fun NetworkMonitor.Companion.create(): NetworkMonitor = LinuxNetworkMonitor()
