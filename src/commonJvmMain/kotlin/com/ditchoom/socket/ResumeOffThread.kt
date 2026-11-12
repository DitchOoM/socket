package com.ditchoom.socket

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Resumes this continuation with [result] without ever running its caller's code on the thread that calls
 * this: a transport thread (a selector loop, the JDK's completion-handler thread) must not run user code.
 *
 * Whether a resume would run here is the dispatcher's own answer, [CoroutineDispatcher.isDispatchNeeded]
 * for this thread: `Dispatchers.Unconfined`, an `UnconfinedTestDispatcher`, an `immediate` dispatcher while
 * on its own thread, and any dispatcher that says no all resume inline. A continuation whose interceptor is
 * not a [CoroutineDispatcher] at all is unknown, so it is treated as inline too. Those are handed to
 * [Dispatchers.Default] first; every other continuation is resumed directly and pays exactly the one
 * hand-off its own dispatcher costs. A dispatcher that throws when asked to dispatch (a closed
 * single-thread context) loses only its own caller's continuation, as it does everywhere else: the throw
 * stops here, so one broken caller cannot take the transport thread, or anyone else's wait, down with it.
 */
internal fun <T> CancellableContinuation<T>.resumeOffThisThread(result: Result<T>) {
    if (!isActive) return
    val dispatcher = context[ContinuationInterceptor] as? CoroutineDispatcher
    if (dispatcher != null && dispatcher.isDispatchNeeded(context)) {
        try {
            resumeWith(result)
        } catch (_: Throwable) {
            // The caller's dispatcher refused the task. The continuation is already resumed, so there is no
            // second resume to make; what matters is that the refusal does not reach the transport thread.
        }
        return
    }
    Dispatchers.Default.dispatch(EmptyCoroutineContext) { resumeWith(result) }
}
