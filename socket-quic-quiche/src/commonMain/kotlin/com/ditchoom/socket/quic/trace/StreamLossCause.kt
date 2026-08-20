package com.ditchoom.socket.quic.trace

/**
 * Why a chunk the transport already accepted was released instead of delivered to the application.
 *
 * Sealed rather than a string, per this repo's rule that a reason stays typed to its last boundary —
 * `QuicTraceRecorder.streamLoss` is the one place it becomes a frozen v1 wire token.
 *
 * Every case is a PERMANENT hole in the stream, whether or not releasing was the right thing to do:
 * by the time a chunk reaches the read path quiche has advanced the stream's receive offset and
 * credited flow control for those bytes, so the peer will never resend them (RFC 9000 §2.4, §4.1).
 * That is exactly why they are all recorded — the distinction that matters to someone reading a trace
 * after a short stream is not "was this release correct" but "did the application get these bytes".
 */
sealed interface StreamLossCause {
    /**
     * The read side is gone for good — `close()`/`reset()` — so queued chunks are freed rather than
     * leaked. Expected, and still a loss: an application that closes mid-stream discards whatever the
     * transport had already accepted for it.
     */
    data object ReaderGone : StreamLossCause

    /**
     * A chunk was taken off the queue but its read unwound, and it could not be put back because the
     * queue was already closed — so nothing will ever drain it and freeing is the only alternative to
     * a leak. UNEXPECTED during a healthy stream: this is the #414 shape, and a trace carrying it is
     * the first direct evidence that window is reachable rather than merely real by construction.
     */
    data object QueueClosed : StreamLossCause

    /**
     * The driver answered an in-flight `StreamRecv` for a read that had already unwound, and the
     * salvaged chunk could not be handed on. UNEXPECTED during a healthy stream, and the #393 shape:
     * bytes quiche delivered, that no `read()` will ever return.
     */
    data object SalvageUnclaimed : StreamLossCause
}
