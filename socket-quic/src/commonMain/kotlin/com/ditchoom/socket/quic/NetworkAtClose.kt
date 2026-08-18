package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkObservation
import kotlin.time.Duration

/**
 * What the network was doing when a connection ended — the half of a post-mortem that says *what else
 * was happening*, as opposed to [QuicCloseReason], which says why the connection itself stopped.
 *
 * ## Why this carries a real [NetworkObservation]
 * It holds the **same value class** [com.ditchoom.socket.NetworkMonitor] emits, not a parallel
 * rendering of it. Correlation is then ordinary comparison — `atClose.observation == monitor.observations…`
 * — with no translation layer that could drift from the thing it describes. That also inherits
 * [NetworkObservation]'s own honesty about density: a `Sequenced` observation carries an
 * `ObservationSequence` you can order against the monitor's stream, and an `Unsequenced` one admits the
 * platform does not report position rather than inventing one.
 *
 * Comparing two observations tells you more than an identity would. A different
 * [com.ditchoom.socket.transport.NetworkId] means a genuine handoff; the *same* id with
 * `InternetAccess` moving from `Confirmed` to `Blocked` means the link never changed but stopped
 * working — two very different incidents that a bare network id renders identical.
 *
 * ## What [Observed.sinceLastChange] is for
 * It is the discriminator between *a timer* and *a flap*. A connection that died 133 seconds into a
 * link that never changed was killed by something periodic; one that died 1.2 seconds after a
 * transition was killed by the transition. Both look the same in a log that records only a close, and
 * telling them apart by joining two observation streams on wall-clock timestamps fails exactly when it
 * matters most — when several connections die inside the same second.
 */
sealed interface NetworkAtClose {
    /**
     * No monitor was observing this connection, so there is nothing to correlate against.
     *
     * This is the honest answer for [com.ditchoom.socket.NetworkMonitor.AlwaysAvailable] — the constant
     * monitor that never transitions, which is what Android without an installed `Context` and Wasm
     * resolve to. Reporting a fabricated observation here would be the same class of lie as a close
     * with no reason reporting itself as graceful.
     */
    data object NotObserved : NetworkAtClose {
        override fun toString(): String = "network=not-observed"
    }

    /**
     * The monitor's view at close: the [observation] itself, and how long that observation had been in
     * effect ([sinceLastChange]).
     */
    data class Observed(
        val observation: NetworkObservation,
        val sinceLastChange: Duration,
    ) : NetworkAtClose {
        override fun toString(): String = "network=${observation.state} for=$sinceLastChange"
    }
}
