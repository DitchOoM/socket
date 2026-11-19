package com.ditchoom.socket.quic.core.pool

/** What [FixedPool.tryTake] gives: one of the pool's items, which is its own answer, or [Empty]. */
internal sealed interface Taken {
    data object Empty : Taken
}

/** An item of one [FixedPool]. [index] is its place in that pool. */
internal abstract class Pooled(
    val index: Int,
) : Taken
