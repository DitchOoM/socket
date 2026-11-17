package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QUICHE_ERR_OUT_OF_IDENTIFIERS
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos

/** What a client last heard of its peer: the datagrams that had reached its socket ([recv]), as of [at]. */
internal class Heard(
    val recv: Long,
    val at: Nanos,
)

/** This observation, renewed when datagrams have reached the socket since it was taken ([arrived] in all) at [now]. */
internal fun Heard.observe(
    arrived: Long,
    now: Nanos,
): Heard = if (arrived != recv) Heard(arrived, now) else this

/**
 * Whether a peer that has not handed out a spare connection id still can. A spare arrives as a NEW_CONNECTION_ID over a
 * path that carries frames, so a peer heard from lately can still send one, and the move waits for it; a peer silent for
 * longer than the abandonment window of a path validation (RFC 9000 §8.2.4, three PTOs) cannot, and the move is final.
 * An id is never reused on another path (RFC 9000 §9.5).
 */
internal sealed interface Hearing {
    /** Datagrams arrived since the observation, or not yet for the window: an id may still come. */
    data object Alive : Hearing

    /** Nothing arrived for the window since the observation: no id can come. */
    data object Dark : Hearing
}

/** What the peer has been heard doing at [now] against the observation [was], with [arrived] datagrams reached the socket in all. */
internal fun QuicheConnRef.hearing(
    was: Heard,
    arrived: Long,
    now: Nanos,
): Hearing = if (arrived == was.recv && now.value - was.at.value >= ABANDON_PTOS * activeBasePto()) Hearing.Dark else Hearing.Alive

/** Whether this refusal ends the move: quiche has no spare connection id (not another reason that may pass) and none can come. */
internal fun ProbeOutcome.Rejected.endsMove(hearing: Hearing): Boolean = code == QUICHE_ERR_OUT_OF_IDENTIFIERS && hearing is Hearing.Dark
