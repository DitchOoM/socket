package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StallDumpTests {
    /**
     * A test that times out while a thread is blocked must say where: the dump is printed before the
     * deadline cancels anything, and names the blocking call. Without it the failure names only the
     * timer that fired.
     */
    @Test
    fun aRunQuicTestThatTimesOutNamesTheCallItWasBlockedIn() {
        val printed =
            capturingStdout {
                assertFailsWith<TimeoutCancellationException>("the blocked call must outlast runQuicTest's deadline") {
                    runQuicTest(timeout = DEADLINE) {
                        withContext(Dispatchers.IO) { blockedPastTheDeadline() }
                    }
                }
            }
        assertTrue("STALL-DUMP runQuicTest deadline" in printed, "no stall dump was printed:\n$printed")
        assertTrue("blockedPastTheDeadline" in printed, "the dump does not name the blocking call:\n$printed")
    }

    @Test
    fun aDumpDisarmedBeforeItIsDuePrintsNothing() {
        val printed =
            capturingStdout {
                armStallDump(100.milliseconds, "disarmed").disarm()
                Thread.sleep(400)
            }
        assertFalse("STALL-DUMP" in printed, "a disarmed dump printed:\n$printed")
    }

    // runQuicTest scales its deadline by QUIC_TEST_TIME_SCALE (3 on CI), so the block must outlast
    // the scaled deadline, not the nominal one.
    private fun blockedPastTheDeadline() = Thread.sleep((DEADLINE.scaled + 1.seconds).inWholeMilliseconds)

    private companion object {
        val DEADLINE = 2.seconds
    }

    private fun capturingStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString()
    }
}
