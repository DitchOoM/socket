@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Queue
import platform.Foundation.NSData
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What one receive callback handed over. */
internal sealed interface Arrival {
    /** A datagram, in the memory Network.framework gave it: kept alive until the loop copies it out. */
    class Datagram(
        val content: NSData,
    ) : Arrival

    /** The connection was cancelled or failed: no datagram follows. */
    data object Ended : Arrival

    /** Nothing has arrived that the loop has not taken. */
    data object Waiting : Arrival
}

/**
 * A bounded single-producer, single-consumer ring between a connection's receive callback (one in flight at a time, so
 * one producer) and its loop. The callback adds only while [hasRoom]; the loop takes oldest first.
 */
@Queue
internal class NwArrivals(
    private val capacity: Int,
) {
    private val slots = AtomicArray(Array<Arrival>(capacity) { Arrival.Waiting })
    private val added = AtomicInt(0)
    private val taken = AtomicInt(0)

    /** Producer: whether one more fits. */
    fun hasRoom(): Boolean = added.load() - taken.load() < capacity

    /** Producer, after [hasRoom]. */
    fun add(arrival: Arrival) {
        val at = added.load()
        slots.storeAt(at % capacity, arrival)
        added.store(at + 1)
    }

    /** Consumer: the oldest arrival, without taking it. */
    fun peek(): Arrival {
        val at = taken.load()
        return if (at == added.load()) Arrival.Waiting else slots.loadAt(at % capacity)
    }

    /** Consumer: drops the arrival [peek] answered, so its memory is released. */
    fun pop() {
        val at = taken.load()
        slots.storeAt(at % capacity, Arrival.Waiting)
        taken.store(at + 1)
    }
}
