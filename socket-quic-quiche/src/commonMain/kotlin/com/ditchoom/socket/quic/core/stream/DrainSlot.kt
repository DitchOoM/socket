package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.core.pool.Pooled

/**
 * Memory a drain reads a stream nobody reads into, and drops: one of a loop's few, held for the length of one call,
 * so dropping data allocates nothing. Its bytes are never read.
 */
internal class DrainSlot(
    index: Int,
    val address: Long,
    val capacity: Int,
) : Pooled(index)
