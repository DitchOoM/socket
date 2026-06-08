package com.ditchoom.socket.quic

/**
 * Thrown when a single QUIC stream is aborted by the peer, while the connection itself stays healthy:
 *
 * - **STOP_SENDING** (quiche `QUICHE_ERR_STREAM_STOPPED`, RFC 9000 §19.5) — the peer no longer wants
 *   what we are sending and asked us to stop. A legitimate, routine event: e.g. an HTTP/3 client
 *   cancelling a server PUSH it didn't want (RFC 9114 §7.2.3).
 * - **RESET_STREAM** (quiche `QUICHE_ERR_STREAM_RESET`, RFC 9000 §19.4) — the peer abruptly terminated
 *   the stream it was sending.
 *
 * This is deliberately **not** a [QuicCloseException] (nor a `SocketClosedException`): a stopped/reset
 * stream says nothing about the connection, which keeps carrying every other stream. Conflating the two
 * — mapping a stream-level quiche error to a connection-close — tears down a perfectly good connection
 * when a peer cancels one stream. Callers should abandon just this stream and continue.
 *
 * [streamId] is the affected stream. [quicheErrorCode] is the raw quiche sentinel
 * ([QuicheDriver.QUICHE_ERR_STREAM_STOPPED] or [QuicheDriver.QUICHE_ERR_STREAM_RESET]) so callers can
 * distinguish STOP_SENDING from RESET_STREAM without parsing the message.
 */
class QuicStreamException(
    val streamId: Long,
    val quicheErrorCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
