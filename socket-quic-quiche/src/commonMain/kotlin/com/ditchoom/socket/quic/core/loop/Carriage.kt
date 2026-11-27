package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket

/**
 * Which socket carries an endpoint's connection, and the second one a move opens beside it: the loop's one record of
 * it. Only [on] moves it from one state to the next, on the loop thread. A third socket is unrepresentable: only
 * [Opening] and [Two] name a second, each exactly one, and a second's poller token exists only inside [Two].
 */
internal sealed interface Carriage {
    /** Before the endpoint joined its loop; it carries as [first] says once placed. */
    class Unplaced(
        val first: SocketOwnership,
    ) : Carriage

    /** One socket of the loop's own, named [carrying]. */
    class One(
        val carrying: SocketTicket,
    ) : Carriage

    /** A second socket, [beside], is being made without waiting; its network is assumed a round trip of [prior]. */
    class Opening(
        val carrying: SocketTicket,
        val beside: SocketTicket,
        val prior: RttPrior,
    ) : Carriage

    /** A second socket, [beside], is open beside the carrying one, and the poller reports it as [token]. */
    class Two(
        val carrying: SocketTicket,
        val beside: SocketTicket,
        val token: Int,
    ) : Carriage

    /** The caller's socket, the connection's first: never reopened, replaced or joined by a second. */
    data object Lent : Carriage

    /** The carrying socket died: the connection ends, and what it sends is dropped until the endpoint leaves. */
    data object Dead : Carriage

    /** Off the poller for good. */
    data object Left : Carriage
}

/** Whether an endpoint's socket carries: live, dead with its connection ending, or gone from the loop. */
internal enum class Carries { Live, Dead, Gone }

internal fun Carriage.carries(): Carries =
    when (this) {
        is Carriage.Unplaced, is Carriage.One, is Carriage.Opening, is Carriage.Two, is Carriage.Lent -> Carries.Live
        is Carriage.Dead -> Carries.Dead
        is Carriage.Left -> Carries.Gone
    }

/** An endpoint not yet on a loop, carrying as [ownership] says once placed: shared, as it holds nothing of its own. */
internal fun unplaced(ownership: SocketOwnership): Carriage.Unplaced =
    when (ownership) {
        is SocketOwnership.Owned -> UNPLACED_OWNED
        is SocketOwnership.Lent -> UNPLACED_LENT
    }

private val UNPLACED_OWNED = Carriage.Unplaced(SocketOwnership.Owned)
private val UNPLACED_LENT = Carriage.Unplaced(SocketOwnership.Lent)

/** The first socket of an owned endpoint, as it is placed: shared, as every connection's first socket has one name. */
internal val ONE_FIRST = Carriage.One(SocketTicket.FIRST)

/** What the loop hands a [Carriage]. An answer naming a ticket the carriage does not hold is a no-op in every state. */
internal sealed interface CarriageEvent {
    /** The endpoint joined its loop. */
    data object Placed : CarriageEvent

    /** The connection asks for socket [ticket] on [bind]'s address, its network assumed a round trip of [prior]. */
    class OpenBeside(
        val ticket: SocketTicket,
        val bind: PathKey,
        val prior: RttPrior,
    ) : CarriageEvent

    /** A pass's look at a socket being made. */
    data object Made : CarriageEvent

    /** The connection asks that second socket [ticket] carry from now on. */
    class Promote(
        val ticket: SocketTicket,
    ) : CarriageEvent

    /** The connection gave up the move to second socket [ticket]. */
    class CloseSecond(
        val ticket: SocketTicket,
    ) : CarriageEvent

    /** The network under the endpoint changed: the socket holding the connection's address is reopened. */
    data object Rehome : CarriageEvent

    /** A read of second socket [ticket] found it closed. */
    class BesideLost(
        val ticket: SocketTicket,
    ) : CarriageEvent

    /** The carrying socket failed for good: the connection ends with [end]. */
    class CarryingDied(
        val end: ConnEnd,
    ) : CarriageEvent

    /** The endpoint leaves its loop. */
    data object Leave : CarriageEvent
}

/** What [on] did: the next state, with at most one thing to tell the connection. */
internal sealed interface Step {
    /** A step that keeps the connection: the only kind a second socket's failure can produce. */
    sealed interface Kept : Step

    /** Nothing changed. */
    data object Same : Kept

    /** The carriage is [next] now, and the connection is told [fact]. */
    class Moves(
        val next: Carriage,
        val fact: MoveFact,
    ) : Kept

    /** The carrying socket failed: the carriage is [next] now, and the connection ends with [end]. */
    class Ends(
        val next: Carriage,
        val end: ConnEnd,
    ) : Step
}

/** What a [Step.Moves] tells the connection. */
internal sealed interface MoveFact {
    data object None : MoveFact

    /** A second socket opened for the move [to] names. */
    class Moved(
        val to: MoveTo,
    ) : MoveFact

    /** No second socket [ticket] came of the move, as [why] says; the connection stays where it is. */
    class Refused(
        val ticket: SocketTicket,
        val why: MoveRefusal,
    ) : MoveFact

    /** Second socket [ticket] carries from now on. */
    class Promoted(
        val ticket: SocketTicket,
    ) : MoveFact

    /** Second socket [ticket] was lost; the move it served ends. */
    class SecondLost(
        val ticket: SocketTicket,
    ) : MoveFact

    /** The carrying socket was reopened on the network up now. */
    data object CarryingRehomed : MoveFact

    /** The second socket was reopened on the network up now. */
    data object BesideRehomed : MoveFact
}
