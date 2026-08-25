package com.ditchoom.socket.quic

/**
 * `openStream()` could not create the stream (#423).
 *
 * Since #423, opening a stream materialises it with quiche immediately rather than only reserving an
 * id locally, so a refusal is known at `openStream()` and reported there. The dominant case is the
 * peer's `initial_max_streams` being reached: the connection is healthy, the peer is fine, and the
 * caller simply cannot have another stream of this kind until one is retired or the peer raises the
 * limit.
 *
 * This exists because discarding that refusal reinstated the very defect #423 fixed. `openStream()`
 * would hand back a slot quiche had refused to create, and the next read on it answered
 * `INVALID_STREAM_STATE` — "the stream is in a bad state" — for a stream that had never been created.
 * Reporting the refusal where it happens keeps `openStream()` meaning what its name says.
 *
 * The id is **not** consumed by a failed open: nothing reached the wire and quiche holds no state for
 * it, so the driver hands it back rather than leaking stream ids on a connection that is merely at its
 * limit.
 */
class QuicStreamOpenException(
    /** The stream id that would have been used. Not consumed — a later open will reuse it. */
    val streamId: Long,
    /**
     * quiche's own return code, kept raw in quiche's namespace rather than renamed.
     *
     * Currently always `-12`, `QUICHE_ERR_STREAM_LIMIT` — the one code that means "this stream cannot
     * be created". Carried raw, in quiche's own namespace, so that if the driver ever learns to report
     * another creation-time refusal it arrives intact rather than renamed into something understood —
     * the same discipline [QuicStreamReadError.Quiche] follows.
     *
     * Deliberately narrow: every other negative `stream_send` code (STREAM_STOPPED, STREAM_RESET, DONE)
     * describes an *existing* stream's state and cannot truthfully apply to an id quiche has never
     * seen, so those keep being reported by the first real write, exactly as before #423.
     */
    val quicheErrorCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
