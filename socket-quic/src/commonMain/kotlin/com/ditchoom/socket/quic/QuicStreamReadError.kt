package com.ditchoom.socket.quic

/**
 * Why a stream read could not complete, when the peer did **not** abort the stream.
 *
 * Deliberately separate from [QuicStreamAbort], which models the peer deciding something —
 * STOP_SENDING or RESET_STREAM — and therefore requires a peer [QuicAppErrorCode]. The errors here
 * have no such code, because no peer sent one: they are the local engine reporting that it cannot
 * answer the read. Folding them into [QuicStreamAbort.Unspecified] with a fabricated `0` would make
 * the value lie about where the number came from, the same flattening `DatagramSendError` refuses:
 *
 * > Numeric codes are kept in their own namespaces on purpose … flattening them into one field would
 * > make the value lie about what it means.
 *
 * ## Why this exists at all
 * Mapping every quiche stream error onto `ReadResult.End` would make an error indistinguishable from
 * the peer finishing politely. `End` is a contract — *stop reading, release the stream, move on* — and
 * it is the wrong response to a failure: a log of `End` cannot say whether the peer genuinely closed
 * the stream or quiche is erroring on every read, and those have opposite fixes.
 *
 * ## This is the interim, not the destination
 * The complete fix is for the read *result* to carry the failure, so a caller is forced by the
 * compiler to handle it rather than having to catch. That needs `ReadResult` — which lives in the
 * transport-agnostic `buffer-flow` module and is shared by TCP, UDP, QUIC and in-memory pipes — to
 * gain a typed failure case, which cannot be done without a major version. Throwing is what the write
 * path already does ([QuicStreamException]), so until then this at least applies one contract
 * consistently instead of two contradictory ones.
 */
sealed interface QuicStreamReadError {
    /**
     * `QUICHE_ERR_INVALID_STREAM_STATE` (-7): the stream is not in a state quiche can read from.
     *
     * One of only two `stream_recv` codes reachable on a live connection — the other, STREAM_RESET,
     * is a peer abort and surfaces as [ReadResult.Reset][com.ditchoom.buffer.flow.ReadResult.Reset]
     * instead.
     */
    data object InvalidStreamState : QuicStreamReadError

    /**
     * Any other quiche stream-recv code, kept raw in quiche's own namespace rather than renamed.
     *
     * Unreachable on a live connection as far as quiche documents, which is exactly why it is not
     * silently mapped onto one of the named cases: an unexpected code should arrive intact and
     * obviously unexpected, not disguised as something understood.
     */
    data class Quiche(
        val code: Int,
    ) : QuicStreamReadError
}

/**
 * Thrown when a QUIC stream read fails for a reason the peer did not choose — see [QuicStreamReadError].
 *
 * Like [QuicStreamException], this is deliberately **not** a [QuicCloseException]: the failure is
 * scoped to one stream and says nothing about the connection, which keeps carrying every other stream.
 * Unlike [QuicStreamException], it does not carry a peer application error code, because there is none.
 *
 * Bytes the transport already delivered still outrank it: a read drains anything quiche already handed
 * over before raising this, so buffered data is never lost to a failure that arrives behind it.
 */
class QuicStreamReadException(
    val streamId: Long,
    val error: QuicStreamReadError,
    message: String,
) : Exception(message)
