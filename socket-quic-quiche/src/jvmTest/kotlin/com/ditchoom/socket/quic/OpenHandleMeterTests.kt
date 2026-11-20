package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A meter's external command is bounded. Its wait is a plain JVM wait: unbounded, a slow command holds a
 * leak test's thread past the test's own deadline, where no coroutine deadline or cancellation reaches it
 * (`FailedConnectFdLeakTest` on Windows: 187 s against a 120 s budget, in the PowerShell meter).
 */
class OpenHandleMeterTests {
    @Test
    fun aMeterCommandThatDoesNotFinishFailsWithinItsBudgetInsteadOfHoldingTheTest() {
        val started = TimeSource.Monotonic.markNow()
        val failure =
            failIfStuck(STUCK_AFTER, "a meter command that never finishes") {
                assertFailsWith<MeterCommandTimedOutException> { runMeterCommand(NEVER_FINISHES) }
            }
        val took = started.elapsedNow()
        assertTrue(took < STUCK_AFTER, "the command was abandoned after $took")
        assertTrue(failure.command == NEVER_FINISHES, "the failure names the command it gave up on: ${failure.message}")
    }

    @Test
    fun aMeterCommandThatFinishesReturnsItsOutput() {
        val out = runMeterCommand(PRINTS_OK)
        assertTrue(out.contains("ok"), "the output is returned whole: '$out'")
    }

    private companion object {
        val STUCK_AFTER = 30.seconds
        val isWindows = System.getProperty("os.name").startsWith("Windows")

        /** Runs for well past [STUCK_AFTER] on its own. */
        val NEVER_FINISHES =
            if (isWindows) listOf("ping", "-n", "120", "127.0.0.1") else listOf("sleep", "120")

        val PRINTS_OK = if (isWindows) listOf("cmd", "/c", "echo ok") else listOf("echo", "ok")
    }
}
