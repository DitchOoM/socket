package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.NetworkMonitor
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
 * Where a client connection's socket lives, as the network feed reads it: the address and network it is on, and what
 * the connection was last told. Every reading is judged afresh (a network that comes back unvalidated and then
 * validated, or that gains its address late, is read again), and each answer reaches the connection once.
 *
 * Android tags a socket with the network it was connected on, so a socket of a network that went away carries nothing
 * even when the next network hands back the same address: a new network id with the bound address is [HomeStep.Rehome].
 * A route from another address is [HomeStep.Migrate] under [OnMove.Migrate], after which that address is home.
 */
@CallerSide
internal class HomeWatch(
    private var boundHost: String,
    opened: NetworkId,
    private val onMove: OnMove,
) {
    private var home = opened
    private var told = Told.Usable

    fun offline(): HomeStep = tell(Told.Gone)

    /** The address the socket is on, or is moving to after [HomeStep.Migrate]. */
    fun host(): String = boundHost

    /** A network [id] is up, and the route to the peer leaves from [source]. */
    fun up(
        id: NetworkId,
        source: RouteSource,
    ): HomeStep =
        when (source) {
            is RouteSource.Resolved -> if (source.host == boundHost) bound(id) else moved(source.host, id)
            is RouteSource.PlatformAssigned -> bound(id)
            is RouteSource.Unresolved -> tell(Told.Gone)
        }

    private fun moved(
        host: String,
        id: NetworkId,
    ): HomeStep =
        when (onMove) {
            OnMove.End -> tell(Told.Moved)
            OnMove.Migrate -> {
                if (told == Told.Moved) return HomeStep.Stay
                boundHost = host
                home = id
                told = Told.Usable
                HomeStep.Migrate
            }
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
