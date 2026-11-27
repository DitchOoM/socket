package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId

/** The socket a [Carriage] carries on now, if any: a [Held] one, or none. */
internal sealed interface Carrying {
    data object None : Carrying
}

/**
 * A socket the loop holds, named [ticket], on [network]: the one its connect opened on, or the one the ask that made it
 * named; [NetworkId.Unidentified] until a rehome names one.
 */
internal class Held(
    val ticket: SocketTicket,
    val network: NetworkId,
) : Carrying

/**
 * Which socket carries an endpoint's connection, and the second one a move opens beside it: the loop's one record of
 * it. Only [on] moves a client's from one state to the next, and [held] a server's, on the loop thread. A third socket
 * is unrepresentable: only [Opening] and [Two] name a second, each exactly one, and a second's poller token exists only
 * inside [Two].
 */
internal sealed interface Carriage {
    /** A state with no second socket: every state a server's endpoint can be in. */
    sealed interface Single : Carriage

    /** Before the endpoint joined its loop. */
    data object Unplaced : Single

    /** One socket of the loop's own, [carrying]. */
    class One(
        val carrying: Held,
    ) : Single

    /** A second socket, [beside], is being made without waiting; its network is assumed a round trip of [prior]. */
    class Opening(
        val carrying: Held,
        val beside: Held,
        val prior: RttPrior,
    ) : Carriage

    /** A second socket, [beside], bound to [at], is open beside the carrying one, and the poller reports it as [token]. */
    class Two(
        val carrying: Held,
        val beside: Held,
        val token: Int,
        val at: PathKey,
    ) : Carriage

    /** The caller's socket, the connection's first: never reopened, replaced or joined by a second. */
    data object Lent : Single

    /** The carrying socket died: the connection ends, and what it sends is dropped until the endpoint leaves. */
    data object Dead : Single

    /** As [Dead], for a carrying socket that could not be reopened: it is already off the poller. */
    data object Unpolled : Single

    /** Off the poller for good. */
    data object Left : Single
}

/** Whether an endpoint's socket carries: live, dead with its connection ending, or gone from the loop. */
internal enum class Carries { Live, Dead, Gone }

internal fun Carriage.carries(): Carries =
    when (this) {
        is Carriage.Unplaced, is Carriage.One, is Carriage.Opening, is Carriage.Two, is Carriage.Lent -> Carries.Live
        is Carriage.Dead, is Carriage.Unpolled -> Carries.Dead
        is Carriage.Left -> Carries.Gone
    }

/** The socket a carriage carries on now: none before it is placed, once it is dead or left, or while it is lent. */
internal fun Carriage.carryingNow(): Carrying =
    when (this) {
        is Carriage.One -> carrying
        is Carriage.Opening -> carrying
        is Carriage.Two -> carrying
        is Carriage.Unplaced, is Carriage.Lent, is Carriage.Dead, is Carriage.Unpolled, is Carriage.Left -> Carrying.None
    }

/** An owned first socket on a network nobody named, as every server's is placed: shared, so placing allocates nothing. */
internal val ONE_FIRST = Carriage.One(Held(SocketTicket.FIRST, NetworkId.Unidentified))

/** What the loop hands a [Carriage]. An answer naming a ticket the carriage does not hold is a no-op in every state. */
internal sealed interface CarriageEvent

/** What happens to the carrying socket itself: the only events a server's endpoint takes. */
internal sealed interface SocketEvent : CarriageEvent {
    /** The endpoint joined its loop, carrying on its first socket, owned or lent as [first] says, on [network]. */
    class Placed(
        val first: SocketOwnership,
        val network: NetworkId,
    ) : SocketEvent

    /**
     * The socket [ticket] names has its address on [network] now: one on another network is reopened there, and one
     * on no named network takes it. A ticket that no longer carries is a no-op.
     */
    class Rehome(
        val ticket: SocketTicket,
        val network: NetworkId,
    ) : SocketEvent

    /** The carrying socket failed for good: the connection ends with [end]. */
    class CarryingDied(
        val end: ConnEnd,
    ) : SocketEvent

    /** The endpoint leaves its loop. */
    data object Leave : SocketEvent
}

/** What only a client's endpoint takes: its connection's asks, and what happens to a second socket. */
internal sealed interface ClientEvent : CarriageEvent

/** What a client's connection asks of its sockets. */
internal sealed interface MoveEvent : ClientEvent {
    /**
     * The connection asks for socket [ticket] on [bind]'s address, on [network], whose round trip is assumed to be
     * [prior].
     */
    class OpenBeside(
        val ticket: SocketTicket,
        val bind: PathKey,
        val prior: RttPrior,
        val network: NetworkId,
    ) : MoveEvent

    /** The connection asks that second socket [ticket] carry from now on. */
    class Promote(
        val ticket: SocketTicket,
    ) : MoveEvent

    /** The connection gave up the move to second socket [ticket]. */
    class CloseSecond(
        val ticket: SocketTicket,
    ) : MoveEvent
}

/** What happens to a second socket: never the connection's end. */
internal sealed interface BesideEvent : ClientEvent {
    /** A pass's look at a socket being made. */
    data object Made : BesideEvent

    /** Second socket [ticket] failed: a read found it closed, or its io threw while it was read or made. */
    class BesideLost(
        val ticket: SocketTicket,
    ) : BesideEvent
}

/** What a step did: the next state, of kind [C], with at most one thing to tell the connection. */
internal sealed interface Step<out C : Carriage> {
    /** A step that keeps the connection: the only kind a move or a second socket's failure can produce. */
    sealed interface Kept<out C : Carriage> : Step<C>

    /** Nothing changed. */
    data object Same : Kept<Nothing>

    /** The carriage is [next] now, and the connection is told [fact]. */
    class Moves<out C : Carriage>(
        val next: C,
        val fact: MoveFact,
    ) : Kept<C>

    /** The carrying socket failed: the carriage is [next] now, and the connection ends with [end]. */
    class Ends<out C : Carriage>(
        val next: C,
        val end: ConnEnd,
    ) : Step<C>
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

    /** The carrying socket is on the network a rehome named already, or took it: the connection sends at once. */
    data object OnNetwork : MoveFact

    /** The second socket was reopened on the network up now. */
    data object BesideRehomed : MoveFact
}
