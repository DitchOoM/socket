package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable

/** Offers every component's next wake to the connection's timer, then arms it for the earliest. */
internal fun ConnParts.rearm(
    now: Nanos,
    fire: Runnable,
) {
    when (val pacing = slots.pacing) {
        is Pacing.Unpaced -> Unit
        is Pacing.ByQuiche -> pacing.pacer.offerWake(timer.sink)
    }
    slots.keepAlive.offerWake(timer.sink)
    handshake.offerWake(timer.sink)
    life.linger.offerWake(timer.sink)
    slots.paths.offerWake(timer.sink)
    timer.rearm(now, fire)
}

/** The connection's deadline fell due: quiche's timeout runs and the handshake follows its PTO expiries while they matter to it. */
internal fun ConnParts.expired(paths: Paths) {
    timer.expire()
    when (handshake.ptoFollow()) {
        PtoFollow.Follow -> handshake.expired(paths.read())
        PtoFollow.Ignore -> Unit
    }
}
