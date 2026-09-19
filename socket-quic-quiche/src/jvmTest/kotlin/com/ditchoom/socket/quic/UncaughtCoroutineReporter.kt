package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Prints every coroutine exception that reaches the global handler, with the coroutine's name and its
 * **own** stack, to stderr — the stream the Gradle test task forwards to the job log as `TEST STDERR`.
 *
 * `kotlinx-coroutines-test` collects an exception that arrives while no test is running and replays it
 * at the start of the next one as `UncaughtExceptionsBeforeTest`, so that failure names the victim and
 * carries the leaker's stack only as a suppressed exception. This line is printed at the moment of the
 * throw, beside the `TEST SUCCESS` line of the test that leaked it, and names the coroutine that threw.
 * Registered through `META-INF/services`; consulted whenever no in-context handler took the exception.
 *
 * Diagnostics only: nothing is swallowed and the failure is still raised where the framework raises it.
 * Failing here instead would blame whichever test is running, which is the misattribution this exists
 * to undo.
 */
class UncaughtCoroutineReporter : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler

    override fun handleException(
        context: CoroutineContext,
        exception: Throwable,
    ) {
        val name = context[CoroutineName]?.name ?: UNNAMED
        val report =
            buildString {
                appendLine("$MARKER name=$name job=${context[Job]} thread=${Thread.currentThread().name}")
                append(exception.stackTraceToString())
            }
        System.err.print(report)
        System.err.flush()
    }

    companion object {
        const val MARKER = "[UNCAUGHT-COROUTINE]"
        const val UNNAMED = "<unnamed>"
    }
}
