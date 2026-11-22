@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.core.observe.Queue
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The threads that run application code for one endpoint's connections: [HandlerThreads.Own] of them, fixed. A
 * connection's handlers run on its [HandlerLane]; a thread holds one lane at a time, so a handler that blocks holds up
 * only its own connection and one thread, and when every thread is held the other ready lanes wait: their reads are not
 * completed and flow control stops their peers. Lock-free: lanes move through rings and a stack by compare-and-set, and
 * a thread with nothing to run parks. A step that throws, or a sink that does, is caught under [contain] and the thread
 * goes on.
 */
@Queue
internal class HandlerPool(
    private val threads: HandlerThreads,
    private val name: String,
    /** Runs code the pool does not own, a step or the observation sink, at a containment site: what it throws is caught. */
    private val contain: (Runnable) -> Caught,
    /** A step that holds its thread longer than this is reported as [HandlerObservation.Stalled]. */
    stallBound: Duration = DEFAULT_STALL_BOUND,
    clock: LoopClock = MonotonicClock,
    /** Where the threads are started from: never the caller's thread, which may be a loop's or a main thread. */
    private val starter: (Runnable) -> Unit = { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) },
) {
    private val reports = PoolReports(stallBound, clock, contain)
    private val life = AtomicReference<PoolLife>(PoolLife.Unstarted)
    private val started = AtomicReference<PoolThreads>(PoolThreads.NotStarted)
    private val workers = Array(workersOf(threads)) { PoolWorker(this) }
    private val anywhere = WorkInbox<ReadyLink>()
    private val feeds = AtomicReference(emptyArray<LoopFeed>())
    private val parkedCount = AtomicInt(0)
    private val liveThreads = AtomicInt(0)
    private val wakeFrom = AtomicInt(0)

    /** Any thread: a connection starts running handlers here; the first use starts the threads, off this thread. */
    fun enter(): Entered {
        while (true) {
            val now = life.load()
            val next =
                when (now) {
                    is PoolLife.Unstarted -> PoolLife.Running(Uses.None.added())
                    is PoolLife.Running -> PoolLife.Running(now.uses.added())
                    is PoolLife.Stopping, is PoolLife.Stopped -> return Entered.Refused
                }
            if (!life.compareAndSet(now, next)) continue
            if (now is PoolLife.Unstarted) start()
            return Entered.Entered
        }
    }

    /** Any thread: a connection that [enter]ed no longer runs handlers here. A leave with no enter changes nothing. */
    fun leave() {
        while (true) {
            val now = life.load()
            val next =
                when (now) {
                    is PoolLife.Running ->
                        when (val left = now.uses.removed()) {
                            is Removed.Unbalanced -> return
                            is Removed.Last -> PoolLife.Running(Uses.None)
                            is Removed.Left -> PoolLife.Running(left.uses)
                        }
                    is PoolLife.Stopping ->
                        when (val left = now.uses.removed()) {
                            is Removed.Unbalanced -> return
                            is Removed.Last -> PoolLife.Stopped
                            is Removed.Left -> PoolLife.Stopping(left.uses)
                        }
                    is PoolLife.Unstarted, is PoolLife.Stopped -> return
                }
            if (life.compareAndSet(now, next)) return ended(next)
        }
    }

    /** Any thread, once its endpoint stopped: the threads end once no connection runs handlers. */
    fun stop() {
        while (true) {
            val now = life.load()
            val next =
                when (now) {
                    is PoolLife.Unstarted -> PoolLife.Stopped
                    is PoolLife.Running -> if (now.uses == Uses.None) PoolLife.Stopped else PoolLife.Stopping(now.uses)
                    is PoolLife.Stopping, is PoolLife.Stopped -> return
                }
            if (life.compareAndSet(now, next)) return ended(next)
        }
    }

    /** Any thread: the pool's life, and its threads that started and have not ended. */
    fun life(): PoolLife = life.load()

    fun liveThreads(): Int = liveThreads.load()

    /** Whether what is ready runs off the pool's threads: after a stop, or when none of them could start. */
    fun runsOffPool(): Boolean = life.load() is PoolLife.Stopped || started.load() is PoolThreads.NoneStarted

    /** Any thread: every later observation goes to [to]. A sink that throws loses that observation. */
    fun observe(to: (HandlerObservation) -> Unit) = reports.observe(to)

    fun report(observation: HandlerObservation) = reports.report(observation)

    /** A lane for the connection [connection] on the loop whose side is [outbox]. */
    fun lane(
        outbox: HandlerOutbox,
        connection: QuicSessionId,
    ): HandlerLane = HandlerLane(this, feedOf(outbox), connection)

    /** A worker, for a test that drives a [HandlerThreads.Driven] pool. */
    fun worker(index: Int): PoolWorker = workers[index]

    /** [worker] runs one step of a lane it holds: timed, contained and reported (see [PoolReports.runStep]). */
    fun runStep(
        worker: PoolWorker,
        block: Runnable,
        context: CoroutineContext,
    ) = reports.runStep(worker, block, context)

    /** A thread other than the lane's loop in a pass claimed [lane] ready. */
    fun placeFromAnywhere(lane: HandlerLane) {
        anywhere.push(lane.link)
        wakeOne()
        if (runsOffPool()) anywhere.drain(rescueLink)
    }

    /** A loop pushed lanes to [ring], its feed of this pool, in the pass that ends now. */
    fun pushed(ring: LaneRing) {
        wakeOne()
        if (runsOffPool()) rescueAll(ring)
    }

    /** [worker] gave [lane] back, steps still queued. */
    fun gaveBack(
        lane: HandlerLane,
        worker: PoolWorker,
    ) {
        worker.ring.push(lane)
        if (hasWork()) wakeOne()
        if (runsOffPool()) rescueAll(worker.ring)
    }

    /** [worker]'s next lane, oldest first from its own ring, then the any-thread queue, then each loop, then the others. */
    fun find(worker: PoolWorker): ReadyTake {
        var found = worker.ring.take()
        if (found is ReadyTake.None && !anywhere.looksEmpty()) {
            worker.adoptAnywhere(anywhere)
            found = worker.ring.take()
        }
        val fed = feeds.load()
        var i = 0
        while (found is ReadyTake.None && i < fed.size) found = fed[i++].ring.take()
        i = 0
        while (found is ReadyTake.None && i < workers.size) found = workers[i++].ring.take()
        // Another lane is still ready: one more thread looks, so the loop never pays for more than one wake per pass.
        if (found is HandlerLane && hasWork()) wakeOne()
        return found
    }

    /** Any thread: whether some lane waits in a ready queue, at the moment of reading. */
    fun hasWork(): Boolean {
        if (!anywhere.looksEmpty()) return true
        val fed = feeds.load()
        for (i in fed.indices) if (fed[i].ring.hasWaiting()) return true
        for (i in workers.indices) if (workers[i].ring.hasWaiting()) return true
        return false
    }

    /**
     * The thread that drives a loop feeding this pool, after its pass: every step held over the bound is reported once,
     * and a [HandlerThreads.ByLoopCaller] pool runs what is ready.
     */
    fun passEnded() {
        when (life.load()) {
            is PoolLife.Running, is PoolLife.Stopping -> reports.watch(workers)
            is PoolLife.Unstarted, is PoolLife.Stopped -> Unit
        }
        when (threads) {
            is HandlerThreads.ByLoopCaller -> while (workers[0].turn() == Turned.Ran) Unit
            is HandlerThreads.Own, is HandlerThreads.Driven -> Unit
        }
    }

    /** A worker counts itself parked, or uncounts itself having found work before anyone woke it. */
    fun parked() = parkedCount.incrementAndFetch()

    fun unparked() = parkedCount.decrementAndFetch()

    fun threadEnded() = liveThreads.decrementAndFetch()

    /** Wakes one parked worker, if any is parked. */
    private fun wakeOne() {
        if (parkedCount.load() == 0) return
        val from = wakeFrom.fetchAndIncrement()
        for (k in workers.indices) {
            if (workers[(from + k).mod(workers.size)].wake()) {
                parkedCount.decrementAndFetch()
                return
            }
        }
    }

    private fun ended(now: PoolLife) {
        if (now !is PoolLife.Stopped) return
        for (i in workers.indices) if (workers[i].wake()) parkedCount.decrementAndFetch()
        rescueReady()
    }

    private fun rescueReady() {
        val fed = feeds.load()
        for (i in fed.indices) rescueAll(fed[i].ring)
        for (i in workers.indices) rescueAll(workers[i].ring)
        anywhere.drain(rescueLink)
    }

    private fun start() {
        when (threads) {
            is HandlerThreads.Own -> starter(Runnable { startThreads() })
            is HandlerThreads.Driven, is HandlerThreads.ByLoopCaller -> Unit
        }
    }

    /** Starts every worker's thread; one that cannot start is reported, and with none started the pool runs off them. */
    private fun startThreads() {
        var refused: PoolThreads = PoolThreads.NotStarted
        for (i in workers.indices) {
            liveThreads.incrementAndFetch()
            when (val caught = contain(Runnable { startPoolThread("$name-$i") { workers[i].runOn(it) } })) {
                is Caught.Clean -> Unit
                is Caught.Threw -> {
                    liveThreads.decrementAndFetch()
                    report(HandlerObservation.ThreadNotStarted(i, caught.thrown))
                    if (refused is PoolThreads.NotStarted) refused = PoolThreads.NoneStarted(caught.thrown)
                }
            }
        }
        if (liveThreads.load() > 0 || refused is PoolThreads.NotStarted) {
            started.store(PoolThreads.Started)
        } else {
            started.store(refused)
            rescueReady()
        }
    }

    private val rescueLink: (ReadyLink) -> Unit = { rescue(it.lane) }

    private fun rescueAll(ring: LaneRing) {
        while (true) {
            when (val lane = ring.take()) {
                is HandlerLane -> rescue(lane)
                is ReadyTake.None -> return
            }
        }
    }

    /** With no pool thread to run it, a ready lane runs once on the default dispatcher, by a worker of its own. */
    private fun rescue(lane: HandlerLane) {
        Dispatchers.Default.dispatch(EmptyCoroutineContext, Runnable { PoolWorker(this).runLane(lane) })
    }

    /** The feed [outbox]'s loop gives this pool its lanes through, made on first use. */
    private fun feedOf(outbox: HandlerOutbox): LoopFeed {
        while (true) {
            val now = feeds.load()
            for (i in now.indices) if (now[i].outbox === outbox) return now[i]
            val made = LoopFeed(this, outbox)
            if (feeds.compareAndSet(now, now + made)) return made.also { outbox.fed(it) }
        }
    }

    companion object {
        /** Steps one turn runs of a lane before the lane goes to the back of a ring. */
        const val TURN_BUDGET = 64

        /** Threads of a server endpoint's pool. */
        const val SERVER_THREADS = 4

        /** Threads of a client endpoint's pool. */
        const val CLIENT_THREADS = 2

        /** The default stall bound. */
        val DEFAULT_STALL_BOUND: Duration = 100.milliseconds
    }
}
