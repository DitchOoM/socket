package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.sched.LoopHandlers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** How this runtime tells a caller on a main thread, which must make no system calls. Chosen once per process. */
internal sealed interface MainThreads {
    /** The runtime has no main thread of its own: only a main dispatcher marks a main-thread caller. */
    data object ByDispatcher : MainThreads

    /** The runtime has one, [thread], recognised whatever dispatcher the caller reached it through. */
    class Known(
        val thread: MainThread,
    ) : MainThreads
}

/** A runtime's main thread. */
internal expect class MainThread {
    /** Whether the calling thread is this one. */
    fun isCurrent(): Boolean
}

/** This runtime's [MainThreads]. */
internal expect val mainThreadsOfThisRuntime: MainThreads

/** A caller on a main thread, by its dispatcher or by [main], leaves its work for the loop; any other may enter. */
internal fun callerPlaceOf(
    context: CoroutineContext,
    main: MainThreads,
): CallerPlace {
    if (context[ContinuationInterceptor] is MainCoroutineDispatcher) return CallerPlace.HandToLoop
    // SPIKE: a handler on a loop hands its work to the loop's next serve, in the same pass.
    if (context[ContinuationInterceptor] is LoopHandlers) return CallerPlace.HandToLoop
    return when (main) {
        is MainThreads.ByDispatcher -> CallerPlace.MayEnter
        is MainThreads.Known -> if (main.thread.isCurrent()) CallerPlace.HandToLoop else CallerPlace.MayEnter
    }
}
