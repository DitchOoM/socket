@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.core.observe.Queue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext

/** Who holds a lane. Only the holder runs its steps, so one connection's handlers never run at the same time. */
internal sealed interface LaneClaim {
    /** Nobody. Whoever queues a step next claims it, after the step is queued. */
    data object Idle : LaneClaim

    /** A ready queue: exactly one queue has it, or the thread that claimed it is putting it in one. */
    data object Ready : LaneClaim

    /** [worker] runs its steps. Each worker has one, made with it. */
    class Held(
        val worker: PoolWorker,
    ) : LaneClaim
}

/** What [HandlerLane.release] did. */
internal enum class Release {
    /** The lane is idle: whoever queues its next step claims it. */
    Released,

    /** A step arrived as the holder let go, and the holder took the lane back: it runs that step too. */
    Reclaimed,
}

/** A step queued from a thread that is neither the lane's loop in a pass nor its holder. Allocated per such step. */
internal class ForeignStep(
    val block: Runnable,
    val context: CoroutineContext,
) : InboxItem<ForeignStep>() {
    override fun self(): ForeignStep = this
}

/** A lane's place in its pool's any-thread queue of ready lanes; one per lane, in that queue only while ready. */
internal class ReadyLink(
    val lane: HandlerLane,
) : InboxItem<ReadyLink>() {
    override fun self(): ReadyLink = this
}

/**
 * One connection's handler coroutines, as a dispatcher whose steps only its holder runs. A step resumed by the
 * connection's loop in a pass is a slot in [fromLoop], made ready to the pool once at the end of the pass; one resumed
 * by the holder joins [fromHolder]; any other is a node in [fromOthers] and makes the lane ready at once. A step is
 * always queued before the lane is claimed, and the holder looks for steps again after it lets go, so a step is never
 * left with nobody to run it.
 */
@Queue
internal class HandlerLane(
    private val pool: HandlerPool,
    private val feed: LoopFeed,
    /** The connection whose handlers run here, as reports name it. */
    val connection: QuicSessionId,
) : CoroutineDispatcher(),
    ReadyTake {
    private val claim = AtomicReference<LaneClaim>(LaneClaim.Idle)
    private val fromLoop = StepRing()
    private val fromHolder = StepRing()
    private val fromOthers = WorkInbox<ForeignStep>()

    /** Steps in [fromOthers], which a stack cannot count by itself. */
    private val othersQueued = AtomicInt(0)
    val link = ReadyLink(this)

    /** Loop thread only: the pass of the feed's outbox in which a step last joined [fromLoop]. */
    private var touchedIn = 0L
    private val toHolder: (ForeignStep) -> Unit = {
        othersQueued.decrementAndFetch()
        fromHolder.push(it.block, it.context)
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val outbox = feed.outbox
        if (outbox.inPassHere()) {
            fromLoop.push(block, context)
            if (touchedIn != outbox.pass()) {
                touchedIn = outbox.pass()
                outbox.touched(this)
            }
        } else if (heldHere()) {
            fromHolder.push(block, context)
        } else {
            othersQueued.incrementAndFetch()
            fromOthers.push(ForeignStep(block, context))
            if (claim.compareAndSet(LaneClaim.Idle, LaneClaim.Ready)) pool.placeFromAnywhere(this)
        }
    }

    /** The loop thread, at the end of a pass in which a step joined [fromLoop]: an idle lane becomes ready. */
    fun readyFromLoop() {
        if (claim.compareAndSet(LaneClaim.Idle, LaneClaim.Ready)) feed.readied(this)
    }

    /** The worker that took this lane from a ready queue now holds it. */
    fun hold(worker: PoolWorker) = claim.store(worker.held)

    /** The holder, giving the lane back to the ready queues with its steps still queued. */
    fun unhold() = claim.store(LaneClaim.Ready)

    /** The holder: runs up to [budget] steps through [run], oldest of each source first; returns how many ran. */
    fun runSteps(
        run: (Runnable, CoroutineContext) -> Unit,
        budget: Int,
    ): Int {
        var ran = 0
        while (ran < budget) {
            if (fromHolder.takeInto(run) || fromLoop.takeInto(run)) {
                ran++
            } else if (fromOthers.looksEmpty()) {
                return ran
            } else {
                fromOthers.drain(toHolder)
            }
        }
        return ran
    }

    /** The holder: whether a step waits in any source. */
    fun hasSteps(): Boolean = fromHolder.size() > 0 || fromLoop.size() > 0 || !fromOthers.looksEmpty()

    /**
     * The holder, with no step left: lets go, then looks again. A step queued before the claim went idle is seen by that
     * look; one queued after is claimed by its own producer. Only one of the two compare-and-sets can win.
     */
    fun release(worker: PoolWorker): Release {
        claim.store(LaneClaim.Idle)
        return if (hasSteps() && claim.compareAndSet(LaneClaim.Idle, worker.held)) Release.Reclaimed else Release.Released
    }

    /** Any thread: steps queued and not yet run, from every source, at the moment of reading. */
    fun pending(): Int = fromLoop.size() + fromHolder.size() + othersQueued.load()

    /** Any thread: who holds the lane now. */
    fun claimNow(): LaneClaim = claim.load()

    private fun heldHere(): Boolean =
        when (val now = claim.load()) {
            is LaneClaim.Held -> now.worker.onThisThread()
            is LaneClaim.Idle, is LaneClaim.Ready -> false
        }

    override fun toString(): String = "HandlerLane($connection)"
}
