package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.jvm.JvmInline

/** Who runs a pool's workers. */
internal sealed interface HandlerThreads {
    /** [count] platform threads of the pool's own, started off the caller's thread at the pool's first use. */
    class Own(
        val count: Int,
    ) : HandlerThreads

    /** No thread: a test runs each worker's [PoolWorker.turn] itself, so every interleaving is chosen. */
    class Driven(
        val count: Int,
    ) : HandlerThreads

    /**
     * No thread: whoever drives the loops that feed the pool runs every ready lane after each pass, on its own thread. For
     * a loop a test drives on virtual time, where a thread of the pool's own would run off that time.
     */
    data object ByLoopCaller : HandlerThreads
}

/** The workers [threads] makes: one, for a pool its loops' caller runs. */
internal fun workersOf(threads: HandlerThreads): Int =
    when (threads) {
        is HandlerThreads.Own -> threads.count
        is HandlerThreads.Driven -> threads.count
        is HandlerThreads.ByLoopCaller -> 1
    }

/** How many connections run handlers on a pool. Made only from [None] by adding and removing, so it is never negative. */
@JvmInline
internal value class Uses private constructor(
    val count: Int,
) {
    fun added(): Uses = Uses(count + 1)

    fun removed(): Removed =
        when (count) {
            0 -> Removed.Unbalanced
            1 -> Removed.Last
            else -> Removed.Left(Uses(count - 1))
        }

    companion object {
        val None = Uses(0)
    }
}

/** What [Uses.removed] answered. */
internal sealed interface Removed {
    /** No use was left to remove: a leave without its enter, which changes nothing. */
    data object Unbalanced : Removed

    /** The last use left. */
    data object Last : Removed

    class Left(
        val uses: Uses,
    ) : Removed
}

/**
 * A pool's life. Its threads start with the first use and end once it has stopped. Nothing here cancels a handler:
 * its endpoint's close returns while handlers still run ([Stopping]), and they run to their own return; a step that
 * still comes once the pool is [Stopped] runs on the default dispatcher, after that close returned.
 */
internal sealed interface PoolLife {
    /** No use yet, so no thread. */
    data object Unstarted : PoolLife

    /** [uses] connections run handlers here, none at all included. */
    class Running(
        val uses: Uses,
    ) : PoolLife

    /** Its endpoint stopped while [uses] connections, at least one, still ran handlers: they run to their own return. */
    class Stopping(
        val uses: Uses,
    ) : PoolLife

    /** Its endpoint stopped and no handler runs: its threads end, and a step that still comes runs off the pool. */
    data object Stopped : PoolLife
}

/** Whether a pool's threads run. */
internal sealed interface PoolThreads {
    /** Not started yet, or a pool with no thread of its own. */
    data object NotStarted : PoolThreads

    /** At least one thread started. */
    data object Started : PoolThreads

    /** Not one thread could start: what is ready runs off the pool, as after a stop. [thrown] is the first refusal. */
    class NoneStarted(
        val thrown: Thrown,
    ) : PoolThreads
}

/** What [HandlerPool.enter] answered. */
internal enum class Entered {
    Entered,

    /** The pool's endpoint stopped. */
    Refused,
}
