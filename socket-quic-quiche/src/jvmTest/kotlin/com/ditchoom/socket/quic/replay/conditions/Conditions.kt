package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.HandshakePacing
import kotlin.time.Duration

/**
 * The network a connection lived through, as a script: what the link did in each window of time and what the OS said
 * when. Derived from a walk's recording by [RecordingToConditions] and replayed against a real core client and server
 * by [ConditionReplay], which asserts the outcome, never the datagrams. Times count from the script's start, when the
 * replayed connection is opened on [start]. Nothing here names a real address or network handle.
 */
internal data class Conditions(
    val name: String,
    /** Where the script came from, anonymised: a walk, a lane, a window. */
    val source: String,
    val family: Family,
    val evidence: Evidence,
    /** The network the connection is opened on. */
    val start: NetUp,
    /** How often the replayed client echoes, each echo held until the last one was answered. */
    val echoEvery: Duration,
    val idleTimeout: Duration,
    /** Contiguous from zero to [runFor]: what the link did. */
    val link: List<LinkWindow>,
    /** What the OS said, in time order. */
    val os: List<OsEvent>,
    val runFor: Duration,
    /** Bytes in each echo: the walk probe's 16, or more to hold data in flight across an outage. */
    val echoBytes: Int = PROBE_ECHO_BYTES,
    /** How both ends retransmit their handshake. */
    val handshakePacing: HandshakePacing = HandshakePacing.Adaptive(),
)

/** The walk probe's echo size. */
internal const val PROBE_ECHO_BYTES = 16

internal enum class Family { V4, V6 }

/** What the script's link windows were derived from. */
internal enum class Evidence {
    /** Our recording alone: delay from echo round trips, loss from requests that went unanswered, split evenly. */
    ClientOnly,

    /** Both ends' recordings: loss per direction from datagrams one end sent and the other never received. */
    BothEnds,
}

/** A network the OS named: its kind, and its order of first appearance in the recording in place of its handle. */
internal data class NetHandle(
    val kind: NetKind,
    val ordinal: Int,
)

internal enum class NetKind { Wifi, Cellular, Ethernet, Other }

/** What the OS said about a network's internet access. */
internal enum class Validation { Validated, Pending, Unobserved }

/** A usable network: the OS's default one. */
internal data class NetUp(
    val network: NetHandle,
    val validation: Validation,
)

/** Where the route to the peer leaves from, as the connection's network feed asks it. */
internal sealed interface RouteFrom {
    /** The address the connection's socket is bound to. */
    data object Bound : RouteFrom

    /** Another address: a documentation address, distinct per real address. */
    data class Other(
        val address: String,
    ) : RouteFrom

    /** No address of the connection's family that routes. */
    data object NoRoute : RouteFrom
}

/** One thing the OS said. */
internal sealed interface OsEvent {
    val at: Duration

    data class Offline(
        override val at: Duration,
    ) : OsEvent

    data class Up(
        override val at: Duration,
        val up: NetUp,
    ) : OsEvent

    /** The link addresses changed, and with them where the route to the peer leaves from. */
    data class Route(
        override val at: Duration,
        val route: RouteFrom,
    ) : OsEvent
}

/** One-way delay, drawn per datagram between [min] and [max]. */
internal data class DelayRange(
    val min: Duration,
    val max: Duration,
)

/** A loss probability in thousandths. */
@JvmInline
internal value class PerMille(
    val value: Int,
)

/** What the link did from [from] until [until]. */
internal sealed interface LinkWindow {
    val from: Duration
    val until: Duration

    /** Nothing was delivered, either way. */
    data class Outage(
        override val from: Duration,
        override val until: Duration,
    ) : LinkWindow

    data class Flowing(
        override val from: Duration,
        override val until: Duration,
        val delay: DelayRange,
        val lossToPeer: PerMille,
        val lossFromPeer: PerMille,
    ) : LinkWindow
}

/** When the connection could carry traffic again after the script's last disruption. */
internal sealed interface Usable {
    data class At(
        val at: Duration,
    ) : Usable

    /** The script has no disruption: nothing to come back from. */
    data object Undisturbed : Usable

    /** The network never came back with the bound address and a flowing link. */
    data object Never : Usable
}

/**
 * The instant the connection could carry traffic again after the script's last disruption: the OS has a network up,
 * the route leaves from the bound address, and the link flows. A new network counts as a disruption even with the
 * route kept, as Android ties a socket to the network it was opened on.
 */
internal fun Conditions.usableAgain(): Usable {
    val outages = link.filterIsInstance<LinkWindow.Outage>()
    if (os.isEmpty() && outages.isEmpty()) return Usable.Undisturbed
    var state = OsState(Reach.Up, start.network, RouteFrom.Bound)
    var since = Duration.ZERO
    for (event in os.sortedBy { it.at }) {
        val next = state.after(event)
        if (next.usable() && (!state.usable() || next.network != state.network)) since = event.at
        state = next
    }
    if (!state.usable()) return Usable.Never
    return Usable.At(maxOf(since, outages.filter { it.until > since }.maxOfOrNull { it.until } ?: since))
}

private enum class Reach { Up, Down }

private data class OsState(
    val reach: Reach,
    val network: NetHandle,
    val route: RouteFrom,
) {
    fun usable(): Boolean = reach == Reach.Up && route == RouteFrom.Bound

    fun after(event: OsEvent): OsState =
        when (event) {
            is OsEvent.Offline -> copy(reach = Reach.Down)
            is OsEvent.Up -> copy(reach = Reach.Up, network = event.up.network)
            is OsEvent.Route -> copy(route = event.route)
        }
}
