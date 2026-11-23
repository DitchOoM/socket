@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.currentThreadId
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a loop is in a pass, and on which thread. */
private sealed interface Pass {
    data object Between : Pass

    class On(
        val thread: Long,
    ) : Pass
}

/**
 * A loop's side of the handler pools: the lanes whose steps its pass queued, made ready together when the pass ends.
 * A step the loop resumes during a pass is a slot write; at the pass's end each lane it touched is claimed once, the
 * lanes of one pool go to that pool's ring for this loop, and each pool wakes at most one parked thread. Between
 * passes the loop's thread is like any other, so a resume then is queued as from any thread.
 */
@Queue
internal class HandlerOutbox {
    private val pass = AtomicReference<Pass>(Pass.Between)

    /** Every pool this loop feeds, registered from any thread as each first gets a lane here. */
    private val feeds = AtomicReference(emptyArray<LoopFeed>())

    /** Loop thread only, as is every member but [inPassHere]. */
    private var on = Pass.On(currentThreadId())
    private var passes = 0L
    private val touched = ArrayList<HandlerLane>()
    private val readied = ArrayList<LoopFeed>()

    /** A pass begins on this thread. */
    fun begin() {
        val thread = currentThreadId()
        if (on.thread != thread) on = Pass.On(thread)
        passes++
        pass.store(on)
    }

    /** Any thread: whether this is the loop's thread, in a pass. */
    fun inPassHere(): Boolean =
        when (val now = pass.load()) {
            is Pass.On -> now.thread == currentThreadId()
            is Pass.Between -> false
        }

    /** The number of the pass under way. */
    fun pass(): Long = passes

    /** [lane] had a step queued in this pass. */
    fun touched(lane: HandlerLane) {
        touched.add(lane)
    }

    /** [feed] had a lane made ready in this pass. */
    fun readied(feed: LoopFeed) {
        readied.add(feed)
    }

    /**
     * The pass ends: every lane it touched is made ready, and each pool that has new ready lanes is told once. Then,
     * out of the pass, a pool its loop's caller runs runs what is ready.
     */
    fun end() {
        for (i in touched.indices) touched[i].readyFromLoop()
        touched.clear()
        for (i in readied.indices) readied[i].passEnded()
        readied.clear()
        pass.store(Pass.Between)
        val fed = feeds.load()
        for (i in fed.indices) fed[i].pool.passEnded()
    }

    /** Any thread: [feed] feeds a pool from this loop. */
    fun fed(feed: LoopFeed) {
        while (true) {
            val now = feeds.load()
            if (feeds.compareAndSet(now, now + feed)) return
        }
    }
}

/**
 * The ready lanes one loop hands one pool: a ring the loop alone pushes to and the pool's threads take from, so a
 * pass's lanes cost the loop no compare-and-set and no allocation, and the pool one wake.
 */
@LoopOnly
internal class LoopFeed(
    val pool: HandlerPool,
    val outbox: HandlerOutbox,
) {
    val ring = LaneRing()
    private var readiedIn = 0L

    /** The loop thread: [lane] was claimed ready in this pass. */
    fun readied(lane: HandlerLane) {
        ring.push(lane)
        if (readiedIn != outbox.pass()) {
            readiedIn = outbox.pass()
            outbox.readied(this)
        }
    }

    /** The loop thread, once per pass that readied a lane here. */
    fun passEnded() = pool.pushed(ring)
}
