package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * Prints any coroutine exception that reaches the global handler, with its **own** stack.
 *
 * ## The problem this exists for (#588)
 * `kotlinx-coroutines-test` collects exceptions that arrive while no test is running and reports them
 * at the start of the *next* one, as `UncaughtExceptionsBeforeTest`. That failure therefore names an
 * arbitrary test — whichever ran next — and carries **the victim's stack, not the leaker's**. Observed
 * on CI: `MigrationCapabilityAnswerTests.aBackendWithNoPathFactorySaysSo` failed in 2ms with a stack
 * that goes no deeper than `TestScope.enter()`, three real-socket handshake tests having just
 * finished. Nothing in the log said what threw, so the defect was not diagnosable — only guessable.
 *
 * A `CoroutineExceptionHandler` registered through `ServiceLoader` is consulted by
 * `handleCoroutineException` for exceptions no in-context handler took. A coroutine that outlives its
 * `runTest` and fails afterwards goes down exactly that path, so its stack lands here — at the moment
 * it happens, next to the `TEST SUCCESS` line of whatever test actually leaked it.
 *
 * ## Why print rather than fail
 * Failing here would attribute the leak to whatever test happens to be running, which is the bug being
 * fixed. The marker is greppable and the CI log already interleaves `TEST START` / `TEST SUCCESS`, so
 * the line immediately above the marker names the source.
 *
 * ## What is proven, and what is not
 * **Proven** (probe, macOS arm64): a coroutine launched on a detached `CoroutineScope(Dispatchers.Default)`
 * that throws after its test returns reaches this handler, and the marker plus the exception's own
 * stack appear in the test worker's output —
 *
 * ```
 * [UNCAUGHT-COROUTINE] thread=DefaultDispatcher-worker-1 @coroutine#5 context=[…StandaloneCoroutine{Cancelling}…]
 * java.lang.IllegalStateException: PROBE-LEAK-MARKER
 * ```
 *
 * **Not proven**: that #588's leak takes this path. That probe produced a marker but no
 * `UncaughtExceptionsBeforeTest`, so it does not reproduce the CI shape — where the framework itself
 * collected the exception and reported it against the next test. If #588's leaker is instead a child
 * of a `TestScope`, the framework's own handler may take it before this one is consulted, and this
 * will stay silent.
 *
 * So this is strictly more than the nothing captured today, and not a guarantee. The next occurrence
 * decides: a marker beside it names the leaker, and its absence narrows the search to "collected by
 * the test framework" instead.
 *
 * ⚠️ Diagnostics only — changes no behaviour, swallows nothing. It prints to the worker's stdout,
 * which reaches CI logs but **not** the JUnit XML; grep the job log, not the report.
 */
class UncaughtCoroutineReporter : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler

    override fun handleException(
        context: CoroutineContext,
        exception: Throwable,
    ) {
        println("[UNCAUGHT-COROUTINE] thread=${Thread.currentThread().name} context=$context")
        exception.printStackTrace()
    }
}
