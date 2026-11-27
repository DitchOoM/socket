package com.ditchoom.socket.quic

/**
 * `QUICHE_ERR_STREAM_STOPPED` (quiche.h): the peer sent STOP_SENDING (RFC 9000 §19.5), so it no longer reads this
 * stream. Stream-scoped: the connection survives.
 */
internal const val QUICHE_ERR_STREAM_STOPPED = -15

/**
 * `QUICHE_ERR_STREAM_RESET` (quiche.h): the peer sent RESET_STREAM (RFC 9000 §19.4) on this stream. Stream-scoped:
 * the connection survives.
 */
internal const val QUICHE_ERR_STREAM_RESET = -16
