package com.ditchoom.socket.quic

/** A datagram batch read at an index outside `0 until size`: the caller's own indexing error. */
internal fun outsideTheBatch(): Nothing = throw IndexOutOfBoundsException()
