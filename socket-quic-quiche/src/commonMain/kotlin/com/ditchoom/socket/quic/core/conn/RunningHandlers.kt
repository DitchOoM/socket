@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether a connection's callers resumed with received data are watched until their handlers suspend. */
internal sealed interface HandlerWatch {
    data object Off : HandlerWatch

    class On(
        val handlers: RunningHandlers,
    ) : HandlerWatch
}

/** Whether the connection must be served for its ACK: the last running handler ended while the ACK waited for it. */
internal enum class ServeNow { No, Yes }

/** Whether any handler ran when an ACK began to wait for them. */
internal enum class Handlers { Running, NoneRunning }

/**
 * The handlers of a connection's received data that run now: a caller resumed with data, from its resumption until it
 * suspends again or finishes. The connection counts one in as it resumes the caller; the caller's thread counts it out.
 * One atomic word holds the count, whether an ACK waits for it, and whether [work] is queued to serve that ACK, so the
 * handler that ends last and the connection that starts a wait always see each other: the ACK never waits for nobody.
 */
@Published
internal class RunningHandlers {
    private val word = AtomicLong(0L)

    /** Serves the connection once the last handler ended while its ACK waited; queued once until it runs. */
    val work: ConnWork = HandlersEndedWork(this)

    /** Inside: a caller was resumed with data that its handler now runs on. */
    fun began() {
        word.fetchAndAdd(ONE)
    }

    /** The handler's thread: the handler suspended or finished. [ServeNow.Yes] asks the caller to submit [work]. */
    fun ended(): ServeNow {
        while (true) {
            val seen = word.load()
            val less = seen - ONE
            val serve = less < ONE && less and WAITING != 0L && less and QUEUED == 0L
            if (word.compareAndSet(seen, if (serve) less or QUEUED else less)) return if (serve) ServeNow.Yes else ServeNow.No
        }
    }

    fun running(): Long = word.load() / ONE

    /** Inside: an ACK waits for the running handlers from now, unless none runs any more. */
    fun waiting(): Handlers {
        while (true) {
            val seen = word.load()
            if (seen < ONE) return Handlers.NoneRunning
            if (word.compareAndSet(seen, seen or WAITING)) return Handlers.Running
        }
    }

    /** Inside: the ACK no longer waits. */
    fun sent() = clear(WAITING)

    /** [work] ran, or the connection ended first. */
    fun served() = clear(QUEUED)

    private fun clear(bit: Long) {
        while (true) {
            val seen = word.load()
            if (seen and bit == 0L || word.compareAndSet(seen, seen and bit.inv())) return
        }
    }

    private companion object {
        const val WAITING = 1L
        const val QUEUED = 2L

        /** One running handler: the count sits above the two flags. */
        const val ONE = 4L
    }
}

/** Inside: the connection is served; the serve's ACK step writes what no handler waits for. */
private class HandlersEndedWork(
    private val handlers: RunningHandlers,
) : ConnWork() {
    override fun run(inside: Inside) = handlers.served()

    override fun gone(end: ConnEnd) = handlers.served()
}
