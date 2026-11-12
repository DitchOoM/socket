package com.ditchoom.socket.quic

/**
 * A stream slot for a test whose stub quiche already holds bytes (or a verdict) for the stream.
 *
 * Against real quiche that state only exists because a datagram arrived, and the loop's sweep after that
 * datagram left a signal in [StreamSlot.dataSignal]; a read that finds that signal asks the loop for the
 * bytes, and one that finds none parks without asking ([StreamSlot.readNeedsQuicheAnswer]). A stub is
 * loaded with its answers directly, with no datagram and no sweep, so the signal is left here by hand.
 */
internal fun announcedSlot(id: Long = 0L): StreamSlot = StreamSlot(QuicStreamId(id)).also { it.dataSignal.trySend(Unit) }
