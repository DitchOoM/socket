package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.trace.TraceAddressRedaction
import com.ditchoom.socket.testkit.osnet.OsLink
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.time.Duration

/** Why a recording gives no script. */
internal sealed interface Refusal {
    data object NoNetworkReading : Refusal

    /** The connection's network was not up when the window starts. */
    data object StartsWithoutNetwork : Refusal

    data object NoDatagram : Refusal
}

/** What the OS said in a window, and the network the window starts on. */
internal sealed interface OsTimeline {
    class Read(
        val start: NetUp,
        val events: List<OsEvent>,
        /** OS_NET lines whose clock is not the trace's (an iOS probe once stamped them with wall time), left out. */
        val unclocked: Int,
        /** The addresses the connection is taken to be bound to; empty when no OS_NET line says. */
        val bound: Set<InetAddress>,
    ) : OsTimeline

    class Refused(
        val why: Refusal,
    ) : OsTimeline
}

/**
 * The OS's side of a recording: the monitor's readings (`NET`) become [OsEvent.Offline] and [OsEvent.Up], and where
 * the route to the peer leaves from is asked again at every reading and every change of the links' addresses
 * (`OS_NET`), as the core's network feed asks it. The connection is bound to the addresses of its family that the
 * window's first network carries on its link; the route stays [RouteFrom.Bound] while the default network's link
 * carries all of them. Without `OS_NET` lines the route is [RouteFrom.Bound] on the first network and another address
 * on any other. Handles become ordinals and addresses documentation addresses, so nothing real is kept.
 */
internal class OsEvidence(
    private val family: Family,
) {
    private val handles = LinkedHashMap<NetworkId, NetHandle>()
    private val others = LinkedHashMap<String, String>()

    fun timeline(
        events: List<TraceEvent>,
        from: Duration,
        to: Duration,
        lastDatagram: Duration,
    ): OsTimeline {
        val nets = events.filterIsInstance<TraceEvent.Net>().sortedBy { it.at }
        if (nets.isEmpty()) return OsTimeline.Refused(Refusal.NoNetworkReading)
        val clocked = events.filterIsInstance<TraceEvent.OsNet>().partition { it.at <= lastDatagram + UNCLOCKED_AFTER }
        val links = clocked.first.sortedBy { it.at }
        val first = nets.filter { it.at <= from }.ifEmpty { listOf(nets.first()) }.last()
        val startState = first.state
        if (startState !is NetworkState.Up) return OsTimeline.Refused(Refusal.StartsWithoutNetwork)
        val start = NetUp(handleOf(startState.id), validationOf(startState))
        val bound = addressesOn(startState.id, linksAt(links, from)).toSet()
        val readings = (nets.filter { it.at > from && it.at <= to } + links.filter { it.at > from && it.at <= to }).sortedBy { it.at }
        val out = ArrayList<OsEvent>()
        var state: NetworkState = startState
        var route: RouteFrom = RouteFrom.Bound
        for (reading in readings) {
            val at = reading.at - from
            if (reading is TraceEvent.Net) {
                state = reading.state
                out += eventOf(at, reading.state)
            }
            val now = routeOf(state, start.network, bound, linksOf(reading, links), links.isEmpty())
            if (now != route) {
                route = now
                out += OsEvent.Route(at, now)
            }
        }
        return OsTimeline.Read(start, deduplicated(start, out), clocked.second.size, bound)
    }

    private fun eventOf(
        at: Duration,
        state: NetworkState,
    ): List<OsEvent> =
        when (state) {
            is NetworkState.Unknown -> emptyList()
            is NetworkState.Offline -> listOf(OsEvent.Offline(at))
            is NetworkState.Up -> listOf(OsEvent.Up(at, NetUp(handleOf(state.id), validationOf(state))))
        }

    private operator fun MutableList<OsEvent>.plusAssign(events: List<OsEvent>) {
        addAll(events)
    }

    private fun routeOf(
        state: NetworkState,
        start: NetHandle,
        bound: Set<InetAddress>,
        links: List<OsLink>,
        unreported: Boolean,
    ): RouteFrom {
        if (state !is NetworkState.Up) return RouteFrom.NoRoute
        if (unreported || bound.isEmpty()) return if (handleOf(state.id) == start) RouteFrom.Bound else other(state.id.toString())
        val now = addressesOn(state.id, links)
        return when {
            now.isEmpty() -> RouteFrom.NoRoute
            now.containsAll(bound) -> RouteFrom.Bound
            else -> other(now.first { it !in bound }.hostAddress)
        }
    }

    /** The routable addresses of the connection's family on the link that carries [id]'s kind. */
    private fun addressesOn(
        id: NetworkId,
        links: List<OsLink>,
    ): List<InetAddress> {
        val kind = kindOf(id)
        return links
            .filter { link -> PREFIXES.getValue(kind).any { link.name.startsWith(it) } }
            .flatMap { it.addresses }
            .flatMap { TraceAddressRedaction.literalsIn(it) }
            .filter { it.isOfFamily() && !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }

    private fun InetAddress.isOfFamily(): Boolean =
        when (family) {
            Family.V4 -> this is Inet4Address
            Family.V6 -> this is Inet6Address
        }

    /**
     * The links a reading saw. The probe writes the OS's account of a monitor reading (an `OS_NET` line carrying the
     * same state) a few hundred milliseconds after the `NET` line, so a `NET` reading takes the links of the first
     * `OS_NET` that names its state within [OS_NET_LAG], and otherwise those last written before it.
     */
    private fun linksOf(
        reading: TraceEvent,
        links: List<TraceEvent.OsNet>,
    ): List<OsLink> {
        val described =
            when (reading) {
                is TraceEvent.OsNet -> listOf(reading)
                is TraceEvent.Net ->
                    links.filter {
                        it.at >= reading.at &&
                            it.at - reading.at <= OS_NET_LAG &&
                            it.facts.state == reading.state
                    }
                else -> emptyList()
            }
        return if (described.isNotEmpty()) described.first().facts.links else linksAt(links, reading.at)
    }

    private fun linksAt(
        links: List<TraceEvent.OsNet>,
        at: Duration,
    ): List<OsLink> {
        val before = links.filter { it.at <= at }
        return when {
            before.isNotEmpty() -> before.last().facts.links
            links.isNotEmpty() -> links.first().facts.links
            else -> emptyList()
        }
    }

    private fun handleOf(id: NetworkId): NetHandle =
        handles.getOrPut(id) {
            NetHandle(
                kindOf(id),
                handles.keys.count { kindOf(it) == kindOf(id) } + 1,
            )
        }

    /** A documentation address, the same one for the same real address. */
    private fun other(real: String): RouteFrom =
        RouteFrom.Other(
            others.getOrPut(real) {
                when (family) {
                    Family.V4 -> "198.51.100.${others.size + 1}"
                    Family.V6 -> "2001:db8::${others.size + 1}"
                }
            },
        )

    /** What the OS last said about the network itself. */
    private sealed interface Said {
        data object Offline : Said

        data class On(
            val up: NetUp,
        ) : Said
    }

    /** A reading the same as the one before it says nothing new. */
    private fun deduplicated(
        start: NetUp,
        events: List<OsEvent>,
    ): List<OsEvent> {
        val out = ArrayList<OsEvent>()
        var said: Said = Said.On(start)
        for (event in events) {
            val now =
                when (event) {
                    is OsEvent.Offline -> Said.Offline
                    is OsEvent.Up -> Said.On(event.up)
                    is OsEvent.Route -> {
                        out += event
                        continue
                    }
                }
            if (now == said) continue
            said = now
            out += event
        }
        return out
    }

    companion object {
        private val UNCLOCKED_AFTER = Duration.parse("1h")
        private val OS_NET_LAG = Duration.parse("2s")

        private val PREFIXES: Map<NetKind, List<String>> =
            mapOf(
                NetKind.Wifi to listOf("wlan", "en0", "wifi", "swlan"),
                NetKind.Cellular to listOf("rmnet", "v4-rmnet", "pdp_ip", "ccmni", "seth", "clat", "v4-"),
                NetKind.Ethernet to listOf("eth", "en"),
                NetKind.Other to listOf(""),
            )

        fun kindOf(id: NetworkId): NetKind =
            when (id) {
                is NetworkId.Link -> kindOf(id.kind)
                is NetworkId.KindOnly -> kindOf(id.kind)
                NetworkId.Unidentified -> NetKind.Other
            }

        private fun kindOf(kind: NetworkKind): NetKind =
            when (kind) {
                NetworkKind.Wifi -> NetKind.Wifi
                NetworkKind.Cellular -> NetKind.Cellular
                NetworkKind.Ethernet -> NetKind.Ethernet
                is NetworkKind.Vpn, is NetworkKind.Other -> NetKind.Other
            }

        fun validationOf(state: NetworkState.Up): Validation =
            when (state) {
                is NetworkState.LinkLocal -> Validation.Pending
                is NetworkState.Routable ->
                    when (state.internet) {
                        InternetAccess.Observed.Confirmed -> Validation.Validated
                        InternetAccess.Unobserved -> Validation.Unobserved
                        else -> Validation.Pending
                    }
            }
    }
}
