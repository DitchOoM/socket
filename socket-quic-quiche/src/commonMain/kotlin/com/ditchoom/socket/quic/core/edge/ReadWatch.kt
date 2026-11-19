@file:OptIn(ExperimentalAtomicApi::class, InternalCoroutinesApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.HandlerWatch
import com.ditchoom.socket.quic.core.conn.RunningHandlers
import com.ditchoom.socket.quic.core.conn.ServeNow
import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonDisposableHandle
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** How a stream's parked reads resume their callers: as any suspension does, or through the stream's [ReadWatch]. */
internal sealed interface ReadResume {
    data object Plain : ReadResume
}

/** A stream's [ReadResume] on [conn]: watched when the connection's ACK waits for the handlers it resumes. */
internal fun readResumeOf(conn: Conn): ReadResume =
    when (val watch = conn.handlerWatch()) {
        is HandlerWatch.Off -> ReadResume.Plain
        is HandlerWatch.On -> ReadWatch(watch.handlers, conn)
    }

/** No caller is parked on a [ReadWatch]. */
private object Vacant : Continuation<ReadAnswer> {
    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<ReadAnswer>) = Unit
}

/** How a [ReadWatch] reaches its caller's thread: in place, for a caller with no interceptor, or through [interceptor]. */
private sealed interface Route {
    data object InPlace : Route

    class Via(
        val interceptor: ContinuationInterceptor,
        val dispatched: Continuation<ReadAnswer>,
    ) : Route
}

/** A [ReadWatch]'s route, set by the caller before it parks and read by whoever answers. */
@Published
private class RouteCell {
    private val route = AtomicReference<Route>(Route.InPlace)

    fun load(): Route = route.load()

    /** Through [interceptor], intercepting [handler] again only when the interceptor changed. */
    fun to(
        interceptor: ContinuationInterceptor,
        handler: Continuation<ReadAnswer>,
    ) {
        val now = route.load()
        if (now is Route.Via && now.interceptor === interceptor) return
        route.store(Route.Via(interceptor, interceptor.interceptContinuation(handler)))
    }

    fun inPlace() = route.store(Route.InPlace)
}

/**
 * Resumes a stream's parked reader so that the connection learns when the handler it resumed with data is done. The
 * connection counts the handler in as it answers ([RunningHandlers.began]); the caller is then resumed through its own
 * interceptor, as any suspension is, and once it suspends again or finishes it is counted out on its own thread. The
 * last one out while an ACK waits for it submits [RunningHandlers.work] there: the transport runs no user code that a
 * plain resume would not, and on no other thread.
 *
 * One per stream; its continuations and their interception are made once and reused. A stream has one reader at a time:
 * a read that finds the watch still held by the previous one is resumed unwatched.
 */
@Published
internal class ReadWatch(
    private val handlers: RunningHandlers,
    private val conn: Conn,
) : ReadResume,
    Continuation<ReadAnswer> {
    private val parked = AtomicReference<Continuation<ReadAnswer>>(Vacant)
    private val route = RouteCell()
    private val handler = Handler()

    override val context: CoroutineContext get() = parked.load().context

    /** The caller, before it parks: the watch resumes it unless the previous caller still holds the watch. */
    fun claim(caller: Continuation<ReadAnswer>): Continuation<ReadAnswer> {
        if (!parked.compareAndSet(Vacant, caller)) return caller.intercepted()
        when (val interceptor = caller.context[ContinuationInterceptor]) {
            null -> route.inPlace()
            else -> route.to(interceptor, handler)
        }
        return this
    }

    /** An answer landed before [resumes] was left: the watch is not used. */
    fun unclaim(resumes: Continuation<ReadAnswer>) {
        if (resumes === this) parked.store(Vacant)
    }

    /** Whoever answers the read: data counts its handler in before the caller's thread can count it out. */
    override fun resumeWith(result: Result<ReadAnswer>) {
        if (result.getOrNull() is ReadAnswer.Data) handlers.began()
        when (val r = route.load()) {
            is Route.InPlace -> handler.resumeWith(result)
            is Route.Via -> r.dispatched.resumeWith(result)
        }
    }

    /** The caller's thread: resumes the caller, which runs until it suspends again or finishes, then counts it out. */
    private inner class Handler : Continuation<ReadAnswer> {
        override val context: CoroutineContext get() = parked.load().context

        override fun resumeWith(result: Result<ReadAnswer>) {
            val caller = parked.load()
            val context = caller.context
            parked.store(Vacant)
            try {
                caller.resumeWith(result)
            } finally {
                if (result.getOrNull() is ReadAnswer.Data) {
                    when (handlers.ended()) {
                        ServeNow.Yes -> conn.submit(handlers.work, callerPlaceOf(context, mainThreadsOfThisRuntime))
                        ServeNow.No -> Unit
                    }
                }
            }
        }
    }
}

/** [Hand.await], resumed through [watch]. */
internal suspend inline fun Hand<ReadAnswer>.awaitWatched(
    context: CoroutineContext,
    watch: ReadWatch,
    crossinline cancelled: () -> Unit,
): ReadAnswer {
    if (!isOpen()) return suspendCoroutineUninterceptedOrReturn { take(it) }
    val job = context[Job]?.invokeOnCompletion(onCancelling = true) { _ -> cancelled() } ?: NonDisposableHandle
    try {
        return suspendCoroutineUninterceptedOrReturn { caller ->
            val resumes = watch.claim(caller)
            park(resumes).also { if (it !== COROUTINE_SUSPENDED) watch.unclaim(resumes) }
        }
    } finally {
        job.dispose()
    }
}
