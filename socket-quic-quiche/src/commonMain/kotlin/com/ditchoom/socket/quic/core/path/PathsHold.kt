package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId

/**
 * Where a client's socket is wanted: [bind]'s address, on a network assumed to have a round trip of [prior], which the
 * edge names [network]; [NetworkId.Unidentified] when it names none, and the loop takes the first a rehome names.
 */
internal class MoveWanted(
    val bind: PathKey,
    val prior: RttPrior,
    val network: NetworkId = NetworkId.Unidentified,
)

/**
 * Whether a client's socket waits to move. The path from a new address needs a spare destination id, which the peer
 * sends to the address the socket is on; a socket that left it before the id came could never receive one.
 */
internal sealed interface HeldMove {
    data object None : HeldMove

    /**
     * The socket is wanted where [wanted] says and waits for a spare id, or for a landing to end; with no spare id the
     * move is final once [darkAt] passes in silence.
     */
    class Waiting(
        val wanted: MoveWanted,
        val darkAt: Nanos,
    ) : HeldMove
}

/** Whether a move already open goes where a new want says. */
internal enum class Served { Already, Not }

/** Where a client's last move asked of the loop goes: nowhere before the first. */
internal sealed interface Toward {
    data object Nowhere : Toward

    class To(
        val wanted: MoveWanted,
    ) : Toward
}

/** Whether this want goes where [other] does: the same address, whatever the port, on the same network. */
internal fun MoveWanted.serves(other: MoveWanted): Served =
    if (bind.family == other.bind.family && bind.hi == other.bind.hi && bind.lo == other.bind.lo && network == other.network) {
        Served.Already
    } else {
        Served.Not
    }

/** What a client whose socket is wanted on another address answers. */
internal enum class Want {
    /** The socket is being moved. */
    Moving,

    /** The socket waits where it is for a spare id from the peer. */
    Waiting,

    /** The peer gave no spare id and is silent, so none can come: the connection cannot follow its socket. */
    NoUsablePath,
}

/**
 * What a client's connection asks of its loop's sockets: loop work, and a no-op there for a ticket the loop no longer
 * holds.
 */
internal sealed interface SocketAsk {
    /**
     * A second socket where [wanted] says, named [ticket]; the loop answers naming [ticket], with the socket or a
     * refusal.
     */
    class OpenBeside(
        val ticket: SocketTicket,
        val wanted: MoveWanted,
    ) : SocketAsk

    /** The second socket [ticket] names carries from now on, and the old one is closed. */
    class Promote(
        val ticket: SocketTicket,
    ) : SocketAsk

    /** The second socket [ticket] names is closed. */
    class CloseSecond(
        val ticket: SocketTicket,
    ) : SocketAsk
}

/** Whether a move's socket was asked of the loop, or waits for a landing to be promoted or lost first. */
internal enum class Opening { Asked, AfterLanding }

/**
 * Where a client's socket is wanted, and whether it may go yet. It goes through [open], which asks for a second socket
 * on an address and a port of its own, on a network assumed to have the given round trip, or answers that a landing
 * must end first. Inside the connection only.
 */
@Confined
internal class MoveHold(
    private val q: QuicheConnRef,
    private val ears: PeerHearing,
    private val open: (MoveWanted) -> Opening,
) {
    var held: HeldMove = HeldMove.None
        private set

    /**
     * The socket is wanted where [wanted] says. It moves now when the path from there can be probed, or when the peer
     * forbade migration, which the move then answers. With no spare id it waits where it is: the peer sends the id
     * there, and could not reach a socket that left. A silent peer can send none, and the move is final.
     */
    fun want(
        now: Nanos,
        wanted: MoveWanted,
    ): Want {
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> Unit
            is PeerMigrationPermission.Forbidden, is PeerMigrationPermission.NotYetNegotiated -> return moveNow(wanted)
        }
        if (q.api.connAvailableDcids(q.handle) > 0L) return moveNow(wanted)
        return when (ears.judge(now)) {
            is Hearing.Dark -> {
                held = HeldMove.None
                Want.NoUsablePath
            }
            is Hearing.Alive -> {
                held = HeldMove.Waiting(wanted, ears.darkAt())
                Want.Waiting
            }
        }
    }

    /**
     * The socket moves where [wanted] says now, in place of any that waited; while a landing stands it waits as for a
     * spare id, and moves at the first [step] after the landing ended.
     */
    fun moveNow(wanted: MoveWanted): Want {
        held = HeldMove.None
        return when (open(wanted)) {
            Opening.Asked -> Want.Moving
            Opening.AfterLanding -> Want.Waiting.also { held = HeldMove.Waiting(wanted, ears.darkAt()) }
        }
    }

    /**
     * A waiting socket moves once the peer's spare id came; what arrives meanwhile shows the peer can still send one,
     * and silence until [HeldMove.Waiting.darkAt] makes the move final ([PathStep.NoUsablePath]).
     */
    fun step(now: Nanos): PathStep {
        val waiting =
            when (val h = held) {
                is HeldMove.None -> return PathStep.Steady
                is HeldMove.Waiting -> h
            }
        if (q.api.connAvailableDcids(q.handle) > 0L) {
            moveNow(waiting.wanted)
            return PathStep.Steady
        }
        return when (ears.observe(now)) {
            Observed.Renewed -> {
                held = HeldMove.Waiting(waiting.wanted, ears.darkAt())
                PathStep.Steady
            }
            Observed.Unchanged ->
                if (now.value < waiting.darkAt.value) {
                    PathStep.Steady
                } else {
                    held = HeldMove.None
                    PathStep.NoUsablePath
                }
        }
    }

    /** Offers the time a waiting move turns final; nothing when none waits. */
    fun offerWake(sink: WakeSink) =
        when (val h = held) {
            is HeldMove.Waiting -> sink.offer(h.darkAt)
            is HeldMove.None -> Unit
        }
}
