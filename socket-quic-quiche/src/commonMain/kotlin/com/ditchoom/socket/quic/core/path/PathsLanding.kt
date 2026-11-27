@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a client's landings end here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos

/** What the loop losing a second socket did to a client's move. */
internal enum class SecondLoss {
    /** The move not yet migrated to ended; the connection stays where it was. */
    MoveEnded,

    /** The landing on it ended: quiche migrated back to the socket the loop still carries on. */
    MovedBack,

    /** The landing on it ended, and quiche refused to migrate back: no socket the loop holds carries the connection. */
    NoPathBack,

    /** No move held the socket: nothing changed. */
    Stale,
}

/** Whether the loop's promotion settled a landing. */
internal enum class Promoted { Settled, Stale }

/** Whether quiche took the connection back to the path a landing left. */
internal sealed interface Back {
    class Home(
        val settled: Move.Settled,
    ) : Back

    data object Refused : Back
}

/**
 * The two ends of a client's landing: the loop promoted the new socket, or lost it before it could. Until one of them
 * the old path keeps its socket, its destination id and what it has in flight. Inside the connection only.
 */
@Confined
internal class MoveLandings(
    private val q: QuicheConnRef,
    private val peer: TowardPeer,
    private val costs: MoveCosts,
    private val silence: PathSilence,
) {
    /**
     * The loop promoted [landing]'s socket and closed the old one: what the old path had in flight is resent from the
     * new one, and the old path's id is retired.
     */
    fun promoted(landing: Move.Landing): Move.Settled {
        peer.requeue(landing.from.local)
        q.retire(landing.from.dcidSeq)
        costs.spend(landing.from.local)
        return Move.Settled(landing.to)
    }

    /**
     * The loop lost [landing]'s socket before it promoted it: quiche migrates back to the old path, on the id it kept,
     * what the lost path had in flight is resent there, and the lost path's id is retired.
     */
    fun back(
        now: Nanos,
        landing: Move.Landing,
    ): Back =
        when (val back = peer.migrate(landing.from.local)) {
            is MigrateOutcome.Migrated -> {
                peer.requeue(landing.to.local)
                q.retire(landing.to.dcidSeq)
                costs.spend(landing.to.local)
                silence.migrated(now)
                Back.Home(Move.Settled(Linked(landing.from.local, back.dcidSeq)))
            }
            is MigrateOutcome.Rejected -> Back.Refused
        }
}
