package com.ditchoom.socket.quic.core.demux

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the server's own loop thread is called. */
internal const val SERVER_LOOP_THREAD = com.ditchoom.socket.quic.core.edge.SERVER_THREAD

/** The live threads running a server's loop. */
private fun serverLoopThreads(): Set<Thread> =
    Thread
        .getAllStackTraces()
        .keys
        .filter { it.name == SERVER_LOOP_THREAD && it.isAlive }
        .toSet()

/**
 * Waits for the server loop threads that are running, which end moments after a close returns (the close is told from
 * the loop's own thread), and returns how many are still alive after [patience]: those never end.
 */
internal fun liveServerLoops(patience: Duration = 5.seconds): Int {
    val running = serverLoopThreads()
    running.forEach { it.join(patience.inWholeMilliseconds) }
    return running.count { it.isAlive }
}

/** Fails the test that leaves a server loop running, rather than a later test that counts loops. */
internal class NoServerLoopLeft : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val before = serverLoopThreads()
                try {
                    base.evaluate()
                } catch (failed: Throwable) {
                    leaked(before)?.let(failed::addSuppressed)
                    throw failed
                }
                leaked(before)?.let { throw it }
            }

            private fun leaked(before: Set<Thread>): AssertionError? {
                val started = serverLoopThreads() - before
                started.forEach { it.join(LOOP_ENDS_WITHIN.inWholeMilliseconds) }
                val alive = started.filter { it.isAlive }
                return if (alive.isEmpty()) null else AssertionError("${description.displayName} left ${alive.size} server loop(s) running")
            }
        }

    private companion object {
        val LOOP_ENDS_WITHIN = 5.seconds
    }
}
