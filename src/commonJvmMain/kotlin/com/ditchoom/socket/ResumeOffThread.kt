package com.ditchoom.socket

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Resumes this continuation with [result] without running its caller's code on the calling thread: a transport
 * thread (a selector loop, the JDK's completion-handler thread) must not run user code.
 *
 * Whether a resume would run here is the dispatcher's own answer, [CoroutineDispatcher.isDispatchNeeded]:
 * `Dispatchers.Unconfined`, an `UnconfinedTestDispatcher`, an `immediate` dispatcher on its own thread and any
 * dispatcher that says no all resume inline, and so does an interceptor that is not a [CoroutineDispatcher] (it
 * is unknown). Those continuations are handed to [Dispatchers.Default]; every other one is resumed directly and
 * costs the one hand-off its own dispatcher costs. A dispatcher that throws when asked to dispatch loses only its
 * own caller's continuation: the throw stops here.
 */
internal fun <T> CancellableContinuation<T>.resumeOffThisThread(result: Result<T>) {
    if (!isActive) return
    when (val interceptor = context[ContinuationInterceptor]) {
        is CoroutineDispatcher ->
            if (interceptor.isDispatchNeeded(context)) {
                try {
                    resumeWith(result)
                } catch (_: Throwable) {
                    // Already resumed; the dispatcher refused the task, so there is no second resume to make.
                }
            } else {
                resumeOnDefault(result)
            }
        else -> resumeOnDefault(result)
    }
}

private fun <T> CancellableContinuation<T>.resumeOnDefault(result: Result<T>) {
    Dispatchers.Default.dispatch(EmptyCoroutineContext) { resumeWith(result) }
}
