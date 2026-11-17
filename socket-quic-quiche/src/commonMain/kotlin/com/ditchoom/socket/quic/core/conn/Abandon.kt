package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Tier
import com.ditchoom.socket.quic.core.life.Tiers
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.reportOnThisThread

/** A caller's thread threw inside: the connection ends with it, and what it cannot contain is reported. */
internal fun Turn.abandon(
    now: Nanos,
    thrown: Throwable,
) {
    when (Tiers.of(thrown)) {
        Tier.Connection -> fail(now, thrown)
        Tier.Process -> {
            finish(now, ConnEnd.CallerFailed(Thrown(thrown)))
            reportOnThisThread(thrown)
        }
    }
}
