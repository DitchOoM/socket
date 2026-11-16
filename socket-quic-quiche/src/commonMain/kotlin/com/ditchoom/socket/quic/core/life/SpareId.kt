package com.ditchoom.socket.quic.core.life

/**
 * One connection id a connection hands its peer for a new path: the id's two halves, as a server's routes read its
 * bytes, and its stateless reset token's two halves.
 */
internal class SpareId(
    val c0: Long,
    val c1: Long,
    val token0: Long,
    val token1: Long,
)

/** One connection id the peer retired, as the two halves a server's routes read its bytes into. */
internal class RetiredId(
    val c0: Long,
    val c1: Long,
)
