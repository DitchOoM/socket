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
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.time.Span
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

/** Whether the watch awaits the end of a move it asked for. */
private enum class Asked { None, Toward }

/**
 * Where a client connection's socket lives, as the network feed reads it: the address and network it is on, and what
 * the connection was last told. Every reading is judged afresh (a network that comes back unvalidated and then
 * validated, or that gains its address late, is read again), and each answer reaches the connection once.
 *
 * Android tags a socket with the network it was connected on, so a socket of a network that went away carries nothing
 * even when the next network hands back the same address: a new network id with the bound address is [HomeStep.Rehome].
 * A route from another address is [HomeStep.Migrate] under [OnMove.Migrate], and a path gone silent is
 * [HomeStep.Rebind] ([silent]), however the monitor stands; each asks the connection to move toward [target]. The
 * watch keeps no guess of where the socket went: it is told by the connection, once a migration landed ([landed]),
 * and that address is home from then, on the link the monitor says carries it.
 */
@CallerSide
internal class HomeWatch(
    private var boundHost: String,
    opened: NetworkId,
    private val onMove: OnMove,
) {
    private var home = opened
    private var told = Told.Usable
    private var target = boundHost

    /** Whether a move toward [target] was asked and the connection has not yet said it ended. */
    private var asked = Asked.None

    /** The network the monitor named when the move toward [target] was asked, or last named. */
    private var named = opened

    fun offline(): HomeStep = tell(Told.Gone)

    /** The address the socket is on, as the connection last said. */
    fun host(): String = boundHost

    /** The address the last [HomeStep.Migrate] or [HomeStep.Rebind] asked for. */
    fun target(): String = target

    /** A network [id] is up, and the route to the peer leaves from [source]. */
    fun up(
        id: NetworkId,
        source: RouteSource,
    ): HomeStep =
        when (source) {
            is RouteSource.Resolved -> if (sameAddress(source.host, boundHost)) bound(id) else moved(source.host, id)
            is RouteSource.PlatformAssigned -> bound(id)
            is RouteSource.Unresolved -> tell(Told.Gone)
        }

    /**
     * The connection's path went silent while the route to the peer leaves from [source]: a fresh port on that address
     * ([HomeStep.Rebind]). Where the socket is changes only once the connection says the move ended.
     */
    fun silent(source: RouteSource): HomeStep {
        if (onMove == OnMove.End || told != Told.Usable) return HomeStep.Stay
        target =
            when (source) {
                is RouteSource.Resolved -> source.host
                is RouteSource.PlatformAssigned -> boundHost
                is RouteSource.Unresolved -> return HomeStep.Stay
            }
        asked = Asked.Toward
        return HomeStep.Rebind
    }

    /**
     * The connection says a move ended with its socket on [host] while the monitor names [named], its links carrying
     * [links]: a new address is home, on the link that carries it; the same address keeps the network it was on. A
     * move toward [target] is no longer awaited, landed or not.
     */
    fun landed(
        host: String,
        named: NetworkId,
        links: LinkAddresses,
    ) {
        asked = Asked.None
        if (sameAddress(host, boundHost)) return
        boundHost = host
        home = homeOf(host, links, named)
    }

    private fun moved(
        host: String,
        id: NetworkId,
    ): HomeStep =
        when (onMove) {
            OnMove.End -> tell(Told.Moved)
            OnMove.Migrate -> {
                if (told == Told.Moved) return HomeStep.Stay
                // A move toward this address is already asked and not yet ended: asking again would throw it away. On
                // another network it is asked again there: a reopen could land on the carrying socket.
                if (asked == Asked.Toward && sameAddress(target, host) && id == named) return HomeStep.Stay
                target = host
                asked = Asked.Toward
                named = id
                told = Told.Usable
                HomeStep.Migrate
            }
        }

    private fun bound(id: NetworkId): HomeStep {
        named = id
        if (told == Told.Moved) return HomeStep.Stay
        if (home == NetworkId.Unidentified) home = id
        if (id == home) return tell(Told.Usable)
        home = id
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

/**
 * The network a socket bound on [host] is on: the link the monitor says carries that address, else [named], the one
 * the monitor names. The default route can move to another link before the monitor names it.
 */
private fun homeOf(
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
