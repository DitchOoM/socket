package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.transport.NetworkId

/** What the network feed does about one reading of the monitor and the route. */
internal enum class HomeStep {
    /** Nothing new for the connection. */
    Stay,

    /** A usable network on the socket's network: the connection sends at once. */
    Usable,

    /** No usable network, or no route to the peer yet: the connection waits, bounded by its idle timeout. */
    Gone,

    /** The route to the peer leaves from another address: that is migration, so the connection ends. */
    Moved,

    /** The same address on another network: the socket is reopened there, on the same address and port. */
    Rehome,
}

/** What the connection was last told. */
private enum class Told { Usable, Gone, Moved }

/**
 * Where a client connection's socket lives, as the network feed reads it: the network it was opened on, and what the
 * connection was last told. Every reading is judged afresh (a network that comes back unvalidated and then validated,
 * or that gains its address late, is read again), and each answer reaches the connection once.
 *
 * Android tags a socket with the network it was connected on, so a socket of a network that went away carries nothing
 * even when the next network hands back the same address: a new network id with the bound address is [HomeStep.Rehome].
 */
@CallerSide
internal class HomeWatch(
    private val boundHost: String,
    opened: NetworkId,
) {
    private var home = opened
    private var told = Told.Usable

    fun offline(): HomeStep = tell(Told.Gone)

    /** A network [id] is up, and the route to the peer leaves from [source]. */
    fun up(
        id: NetworkId,
        source: RouteSource,
    ): HomeStep =
        when (source) {
            is RouteSource.Resolved -> if (source.host == boundHost) bound(id) else tell(Told.Moved)
            is RouteSource.PlatformAssigned -> bound(id)
            is RouteSource.Unresolved -> tell(Told.Gone)
        }

    private fun bound(id: NetworkId): HomeStep {
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
