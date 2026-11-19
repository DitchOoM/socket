package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.CallerPlace
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** How this runtime tells a caller on a main thread, which must make no system calls. Chosen once per process. */
internal sealed interface MainThreads {
    /** The runtime has no main thread of its own: only a main dispatcher marks a main-thread caller. */
    data object ByDispatcher : MainThreads

    /** The runtime has one, which [probe] recognises whatever dispatcher the caller reached it through. */
    class Known(
        private val probe: MainThreadProbe,
    ) : MainThreads {
        fun onIt(): Boolean = probe.onMainThread()
    }
}

/** Whether the calling thread is the runtime's main thread. */
internal fun interface MainThreadProbe {
    fun onMainThread(): Boolean
}

/** This runtime's [MainThreads]. */
internal expect val mainThreadsOfThisRuntime: MainThreads

/** A caller on a main thread, by its dispatcher or by [main], leaves its work for the loop; any other may enter. */
internal fun callerPlaceOf(
    context: CoroutineContext,
    main: MainThreads,
): CallerPlace {
    if (context[ContinuationInterceptor] is MainCoroutineDispatcher) return CallerPlace.HandToLoop
    return when (main) {
        MainThreads.ByDispatcher -> CallerPlace.MayEnter
        is MainThreads.Known -> if (main.onIt()) CallerPlace.HandToLoop else CallerPlace.MayEnter
    }
}
