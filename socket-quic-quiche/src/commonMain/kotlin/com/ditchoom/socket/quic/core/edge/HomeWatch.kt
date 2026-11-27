package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.AddressOwner
import com.ditchoom.socket.LinkAddresses
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NumericAddress
import com.ditchoom.socket.ParsedAddress
import com.ditchoom.socket.ownerOf
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.Carried
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.hostLiteral
import com.ditchoom.socket.transport.NetworkId

/** What the network feed does about one reading of the monitor and the route. */
internal enum class HomeStep {
    /** Nothing new for the connection. */
    Stay,

    /** A usable network on the socket's network: the connection sends at once. */
    Usable,

    /** No usable network, or no route to the peer yet: the connection waits, bounded by its idle timeout. */
    Gone,

    /** The route to the peer leaves from another address, and the connection does not follow it: it ends. */
    Moved,

    /** The route to the peer leaves from another address: the socket moves there and the connection migrates. */
    Migrate,

    /** The same address on another network: the socket is reopened there, on the same address and port. */
    Rehome,

    /** The path went silent with the monitor saying nothing: a fresh port on the route's address, and a migration there. */
    Rebind,
}

/** What a client does when the route to its peer leaves from another address. */
internal enum class OnMove {
    /** Its socket moves there and the connection migrates with it. */
    Migrate,

    /** The connection ends: it does not move on its own. */
    End,
}

/** A client moves on its own only under [MigrationPolicy.Automatic]. */
internal fun onMoveOf(policy: MigrationPolicy): OnMove =
    when (policy) {
        is MigrationPolicy.Automatic -> OnMove.Migrate
        is MigrationPolicy.Forbidden, is MigrationPolicy.Manual, is MigrationPolicy.Pinned -> OnMove.End
    }

/**
 * Whether a client judges its path's silence: only one that moves on its own ([OnMove.Migrate]) on a monitor whose
 * network can change; [NetworkMonitor.AlwaysAvailable] never does.
 */
internal fun silenceOf(
    policy: MigrationPolicy,
    monitor: NetworkMonitor,
): Silence =
    when (onMoveOf(policy)) {
        OnMove.End -> Silence.Off
        OnMove.Migrate -> if (monitor === NetworkMonitor.AlwaysAvailable) Silence.Off else Silence.On
    }

/** How far a client's awaiting-peer probe backs off: to its idle timeout, or [PROBE_CAP_UNBOUNDED_IDLE] with none. */
internal fun probeCapOf(options: QuicOptions): Span =
    Span(if (options.idleTimeout.isPositive()) options.idleTimeout.inWholeNanoseconds else PROBE_CAP_UNBOUNDED_IDLE)

/** The awaiting-peer probe's longest wait on a connection with no idle timeout: 60 s. */
internal const val PROBE_CAP_UNBOUNDED_IDLE = 60_000_000_000L

/** What the connection was last told. */
private enum class Told { Usable, Gone, Moved }

/**
 * How a client's network feed judges each reading of the monitor and the route, against where the loop says the socket
 * that carries is ([Carried]), and what the connection was last told: the watch's own output, the one thing it keeps.
 * Every reading is judged afresh (a network that comes back unvalidated and then validated, or that gains its address
 * late, is read again), and each answer reaches the connection once.
 *
 * Android tags a socket with the network it was connected on, so a socket of a network that went away carries nothing
 * even when the next network hands back the same address: the carrying address on another network is
 * [HomeStep.Rehome], and the loop reopens the socket only if it is not on that network already. A route from another
 * address is [HomeStep.Migrate] under [OnMove.Migrate], asked with each reading that finds it so: the connection lets a
 * move already under way toward it serve it. A path gone silent is [HomeStep.Rebind] ([silent]), however the monitor
 * stands.
 */
@CallerSide
internal class HomeWatch(
    private val onMove: OnMove,
) {
    private var told = Told.Usable

    fun offline(): HomeStep = tell(Told.Gone)

    /** A network [id] is up, and the route to the peer leaves from [source], while [carried] carries. */
    fun up(
        id: NetworkId,
        source: RouteSource,
        carried: Carried,
    ): HomeStep =
        when (source) {
            is RouteSource.Resolved ->
                if (sameAddress(source.host, carried.local.hostLiteral())) bound(id, carried) else moved()
            is RouteSource.PlatformAssigned -> bound(id, carried)
            is RouteSource.Unresolved -> tell(Told.Gone)
        }

    /** The connection's path went silent while the route to the peer leaves from [source]: a fresh port there. */
    fun silent(source: RouteSource): HomeStep {
        if (onMove == OnMove.End || told != Told.Usable) return HomeStep.Stay
        return when (source) {
            is RouteSource.Resolved, is RouteSource.PlatformAssigned -> HomeStep.Rebind
            is RouteSource.Unresolved -> HomeStep.Stay
        }
    }

    private fun moved(): HomeStep =
        when (onMove) {
            OnMove.End -> tell(Told.Moved)
            OnMove.Migrate -> {
                if (told == Told.Moved) return HomeStep.Stay
                told = Told.Usable
                HomeStep.Migrate
            }
        }

    private fun bound(
        id: NetworkId,
        carried: Carried,
    ): HomeStep {
        if (told == Told.Moved) return HomeStep.Stay
        if (id == carried.network) return tell(Told.Usable)
        // The loop tells the connection it can send, reopened or not.
        told = Told.Usable
        return HomeStep.Rehome
    }

    private fun tell(now: Told): HomeStep {
        if (told == now || told == Told.Moved) return HomeStep.Stay
        told = now
        return when (now) {
            Told.Usable -> HomeStep.Usable
            Told.Gone -> HomeStep.Gone
            Told.Moved -> HomeStep.Moved
        }
    }
}

/** The address a move asks toward: the route's, or the carrying socket's where the platform picks the source. */
internal fun towardOf(
    source: RouteSource,
    carried: Carried,
): String =
    when (source) {
        is RouteSource.Resolved -> source.host
        is RouteSource.PlatformAssigned, is RouteSource.Unresolved -> carried.local.hostLiteral()
    }

/**
 * The network a socket bound on [host] is on: the link the monitor says carries that address, else [named], the one
 * the monitor names. The default route can move to another link before the monitor names it.
 */
internal fun homeOf(
    host: String,
    links: LinkAddresses,
    named: NetworkId,
): NetworkId =
    when (val parsed = NumericAddress.parse(host)) {
        is ParsedAddress.NotNumeric -> named
        is ParsedAddress.Address ->
            when (val owner = links.ownerOf(parsed.address)) {
                is AddressOwner.Link -> owner.id
                is AddressOwner.NotReported, is AddressOwner.Unclaimed -> named
            }
    }

/** Whether two address literals name one address: numerically when both are numeric, so every spelling is one. */
private fun sameAddress(
    a: String,
    b: String,
): Boolean {
    val left = NumericAddress.parse(a)
    val right = NumericAddress.parse(b)
    if (left is ParsedAddress.Address && right is ParsedAddress.Address) return left.address == right.address
    return a == b
}
