package com.ditchoom.socket.transport

/**
 * Capability-by-type marker for a raw mux stream that knows its transport-assigned stream id —
 * the same pattern as buffer-flow's [com.ditchoom.buffer.flow.HalfCloseable] /
 * [com.ditchoom.buffer.flow.Resettable].
 *
 * A [com.ditchoom.buffer.flow.ByteStreamMux] deals in plain byte streams, so the stream id that
 * [com.ditchoom.buffer.flow.Connection.id] surfaces for cross-layer log correlation would otherwise
 * be lost at the raw boundary. A transport whose streams carry an id (QUIC) implements this on its
 * concrete stream type; [TypedMuxView] `is`-checks it when wrapping a raw stream and falls back to
 * `0L` (the single-stream/unknown convention of [com.ditchoom.buffer.flow.Connection.id]) when absent.
 */
interface MuxIdentified {
    /** The transport-assigned stream id, e.g. the QUIC stream id. */
    val muxStreamId: Long
}
