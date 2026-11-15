@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a [Loop] lends the threads that send in place: a pool of lanes, or nothing. */
internal sealed interface CallerLanes {
    object None : CallerLanes

    class Of(
        val pool: FixedPool<CallerSend>,
    ) : CallerLanes
}

/** What follows a loop's chain of lanes that still hold datagrams the network has not taken. */
internal sealed interface LaneLink {
    object End : LaneLink
}

/**
 * A send batch of its own for a thread that is not the loop. It is lent for one entry into a connection and then
 * given back, or, when the network refused part of it, handed to the loop to finish.
 */
internal class CallerSend(
    index: Int,
    val batch: SendBatch,
) : Pooled(index),
    LaneLink {
    /** Loop thread only: the next lane waiting for the network. */
    internal var nextLane: LaneLink = LaneLink.End
    internal val handOff: LoopWork = HandOff(this)
}

/** Loop work that takes a lane whose datagrams the network refused. */
private class HandOff(
    private val lane: CallerSend,
) : LoopWork() {
    override fun run(loop: Loop) = loop.adopt(lane)

    // The loop has stopped: the datagrams have nowhere to go.
    override fun gone() = Unit
}

internal sealed interface LaneSent {
    /** Everything went out or was dropped; the lane is still the caller's. */
    object Sent : LaneSent

    /** The network was full: the loop owns the lane now and sends the rest when the socket can take it. */
    object Deferred : LaneSent
}

/** What threads other than the loop did to a [Loop]. Atomic: any thread counts, tests read. */
internal class CallerCounts {
    private val sends = AtomicLong(0L)
    private val datagrams = AtomicLong(0L)
    private val deferred = AtomicLong(0L)
    private val wakes = AtomicLong(0L)

    /** Sends a thread other than the loop made. */
    val sendsInPlace: Long get() = sends.load()

    /** Datagrams the network took from those sends. */
    val datagramsInPlace: Long get() = datagrams.load()

    /** Sends the network refused part of, which the loop finished. */
    val sendsDeferred: Long get() = deferred.load()

    /** Times a thread other than the loop ended the loop's wait. */
    val loopWakes: Long get() = wakes.load()

    internal fun sent(count: Int) {
        sends.fetchAndAdd(1L)
        datagrams.fetchAndAdd(count.toLong())
    }

    internal fun deferred() {
        deferred.fetchAndAdd(1L)
    }

    internal fun woke() {
        wakes.fetchAndAdd(1L)
    }
}
