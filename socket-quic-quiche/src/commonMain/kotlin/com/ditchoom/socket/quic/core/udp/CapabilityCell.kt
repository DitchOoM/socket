@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A native socket's batching now. It starts at what the socket's probe found and only steps down: a device that
 * refuses segmented sends (no checksum offload) leaves the socket on [BatchCapability.Multi], or, when it also
 * receives coalesced, on segmented receives alone.
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
            is BatchCapability.Segmented ->
                current.compareAndSet(
                    seen,
                    when (seen.receive) {
                        Gro.On -> BatchCapability.Segmented(seen.maxDatagrams, Gso.Off, Gro.On)
                        Gro.Off -> BatchCapability.Multi(seen.maxDatagrams)
                    },
                )
            is BatchCapability.Multi, is BatchCapability.OneByOne -> Unit
        }
    }
}
