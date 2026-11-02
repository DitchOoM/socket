package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * How long [QuicScope.openStream] / [QuicScope.openUniStream] wait when the peer's stream limit is
 * reached — the open-side sibling of [com.ditchoom.buffer.flow.ReadPolicy] /
 * [com.ditchoom.buffer.flow.WritePolicy], in the same `Bounded(deadline) | UntilClosed` shape, set once
 * on [QuicOptions.streamOpenPolicy] rather than passed per call.
 *
 * A peer at its `initial_max_streams` (or its latest MAX_STREAMS) is not failing: it is pacing how many
 * streams this endpoint may have open, and it raises the limit with a MAX_STREAMS frame as streams
 * finish (RFC 9000 §4.6). That is back-pressure, the stream-count analogue of a full flow-control
 * window, so the default answer is the one a stalled write gets: suspend until the peer makes room.
 *
 * A separate type rather than a reuse of [com.ditchoom.buffer.flow.WritePolicy] because the two
 * `Bounded` cases end differently: a write that misses its deadline has left the stream in an unknown
 * state and closes it, while an open that misses its deadline has created nothing — no stream id is
 * consumed, nothing reaches the wire, and the connection stays fully usable.
 *
 * Waiting opens are served in the order they were called, separately for each direction: a
 * bidirectional open never waits behind unidirectional credit, or the reverse.
 */
sealed interface StreamOpenPolicy {
    /**
     * Wait at most [deadline] for the peer to allow another stream, then fail with a
     * [com.ditchoom.socket.SocketTimeoutException] whose context is
     * [com.ditchoom.socket.TimeoutContext.StreamOpen]. No stream id is consumed by the failed open.
     */
    data class Bounded(
        val deadline: Duration,
    ) : StreamOpenPolicy {
        init {
            require(deadline.isPositive()) { "stream open deadline must be positive" }
        }
    }

    /**
     * Wait for as long as the connection lives. A connection that closes while an open waits fails it
     * with the connection's own close reason, as a [QuicCloseException].
     */
    data object UntilClosed : StreamOpenPolicy

    /** The bound an open waits under. [UntilClosed] maps to [Duration.INFINITE]. */
    fun toDeadline(): Duration =
        when (this) {
            is Bounded -> deadline
            UntilClosed -> Duration.INFINITE
        }
}
