package com.ditchoom.socket.quic

/**
 * A stream slot for a test whose stub quiche already holds bytes (or a verdict) for the stream. Against real
 * quiche that state exists because a datagram arrived and the loop's sweep announced the stream; a stub is
 * loaded with its answers directly, so the announcement is made here.
 */
internal fun announcedSlot(id: Long = 0L): StreamSlot = StreamSlot(QuicStreamId(id)).also { it.announce() }
