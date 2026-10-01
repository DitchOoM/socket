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
                assertFailsWith<TimeoutCancellationException> {
                    runQuicTest(timeout = 2.seconds) {
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

    private fun blockedPastTheDeadline() = Thread.sleep(3_000)

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
