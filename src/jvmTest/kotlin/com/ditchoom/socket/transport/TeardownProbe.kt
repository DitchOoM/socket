package com.ditchoom.socket.transport

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.debug.State
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Asserts that nothing a test started is still running once it has torn its subject down.
 *
 * ## Why this is a rule rather than a per-fix assertion
 *
 * The ownership fixes in this package each verified themselves by sampling — "0/300 with 300/300
 * rejections", "20/300 attempts closed the inner connection twice". That is the right tool for a
 * *race*: whether two callers can interleave badly is a question about scheduling, so you contend
 * and count.
 *
 * "Is anything still alive after `close()`" is not that kind of question. It is true or false on a
 * single run, and sampling it 300 times measures nothing extra. A leaked collector or an
 * un-cancelled monitor job is deterministic once the code is wrong — it just needs someone to look,
 * and until now nothing did.
 *
 * ## Why DebugProbes rather than counting Job.children
 *
 * `Job.children` only sees coroutines whose parent is the job being inspected. A leak that matters
 * is usually one that escaped the structure — launched into a scope that outlives the caller — and
 * that is precisely the case `children` cannot see. [DebugProbes] enumerates every live coroutine in
 * the process, so escaping the hierarchy does not escape the assertion.
 *
 * The cost is a global agent that perturbs scheduler timing, which is why `CiDiagnostics` documents
 * a deliberate decision *against* it for the #401 flake hunt: it could mask a load-dependent
 * symptom. That reasoning is specific to hunting Heisenbugs. It does not apply here — perturbed
 * timing cannot hide a coroutine that is still suspended when nothing should be.
 *
 * ## Attribution
 *
 * Work is tagged with a unique [CoroutineName] and survivors are filtered by it, so a leak is
 * attributed to the test that caused it rather than to whatever else the suite happens to be
 * running. Coroutines inherit the name from their parent context, so anything the subject launches
 * carries it too.
 *
 * [State.CREATED] is excluded: a coroutine that exists but has never run is not a leak.
 */
private val probeIds = AtomicLong(0)

internal suspend fun assertNothingSurvivesTeardown(
    label: String,
    settle: Duration = 2_000.milliseconds,
    body: suspend (CoroutineScope) -> Unit,
) {
    val wasInstalled = DebugProbes.isInstalled
    if (!wasInstalled) {
        DebugProbes.enableCreationStackTraces = true
        DebugProbes.install()
    }
    val marker = CoroutineName("teardown-probe:$label:${probeIds.incrementAndGet()}")
    val job = Job()
    val scope = CoroutineScope(Dispatchers.Default + job + marker)
    try {
        body(scope)

        // Teardown is asynchronous — a cancelled coroutine is not dead until it has unwound. Poll to
        // the deadline rather than sleeping a fixed slug, so a clean run costs one dump and a slow
        // one is still bounded.
        val deadline = TimeSource.Monotonic.markNow() + settle
        var survivors = aliveUnder(marker)
        while (survivors.isNotEmpty() && deadline.hasNotPassedNow()) {
            delay(25)
            survivors = aliveUnder(marker)
        }
        if (survivors.isNotEmpty()) {
            fail(
                "$label: ${survivors.size} coroutine(s) were still alive $settle after teardown " +
                    "returned. Teardown is supposed to have stopped everything it started.\n\n" +
                    survivors.joinToString("\n\n") { describe(it) },
            )
        }
    } finally {
        job.cancel()
        if (!wasInstalled) DebugProbes.uninstall()
    }
}

private fun aliveUnder(marker: CoroutineName): List<CoroutineInfo> =
    DebugProbes
        .dumpCoroutinesInfo()
        .filter { it.context[CoroutineName]?.name == marker.name && it.state != State.CREATED }

/** The survivor's state, where it is parked, and where it was started — the three facts that name a leak. */
private fun describe(info: CoroutineInfo): String {
    val parkedAt = info.lastObservedStackTrace().take(6).joinToString("\n") { "      at $it" }
    val startedAt =
        info
            .creationStackTrace
            .filter { it.className.startsWith("com.ditchoom") }
            .take(4)
            .joinToString("\n") { "      at $it" }
    return buildString {
        appendLine("  [${info.state}] ${info.job}")
        appendLine("    parked at:")
        appendLine(parkedAt.ifBlank { "      (no stack)" })
        appendLine("    created at:")
        append(startedAt.ifBlank { "      (no ditchoom frames)" })
    }
}
