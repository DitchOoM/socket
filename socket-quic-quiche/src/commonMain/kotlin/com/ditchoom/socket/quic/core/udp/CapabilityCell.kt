@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A native socket's batching now. It starts at what the socket's probe found and only steps down: a device that
 * refuses segmented sends (no checksum offload) leaves the socket on [BatchCapability.Multi].
 */
@Published
internal class CapabilityCell(
    first: BatchCapability,
) {
    private val current = AtomicReference(first)

    fun load(): BatchCapability = current.load()

    /** Any thread: [seen] was refused its segments; later sends go one message per datagram. */
    fun stepDown(seen: BatchCapability) {
        when (seen) {
            is BatchCapability.Segmented -> current.compareAndSet(seen, BatchCapability.Multi(seen.maxDatagrams))
            is BatchCapability.Multi, is BatchCapability.OneByOne -> Unit
        }
    }
}

/**
 * The datagram size a link refused segments at (EMSGSIZE: its MTU is below the size). Sizes from there up are sent
 * without segments; smaller datagrams keep theirs, so one oversized send costs the socket nothing it still needs.
 */
@Published
internal class SegmentLimit {
    private val current = AtomicReference<Limit>(Limit.None)

    /** Any thread: the link refused segments of [size] bytes. The smallest refused size stands. */
    fun refuse(size: Int) {
        while (true) {
            val seen = current.load()
            when (seen) {
                is Limit.None -> if (current.compareAndSet(seen, Limit.From(size))) return
                is Limit.From -> if (size >= seen.size || current.compareAndSet(seen, Limit.From(size))) return
            }
        }
    }

    /** Whether a segmented send of datagrams up to [longest] bytes may be tried. */
    fun allows(longest: Int): Boolean =
        when (val limit = current.load()) {
            is Limit.None -> true
            is Limit.From -> longest < limit.size
        }

    private sealed interface Limit {
        data object None : Limit

        class From(
            val size: Int,
        ) : Limit
    }
}
