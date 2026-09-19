package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A coroutine that outlives its test and throws is reported twice: by [UncaughtCoroutineReporter] on
 * stderr at the moment of the throw, naming the coroutine, and by the next `runTest` as
 * `UncaughtExceptionsBeforeTest`, naming the victim. The stderr line is the one that attributes.
 *
 * The collector that replays the exception arms itself on the first `runTest` of the JVM and never
 * disarms, which is why the leak here sits between two `runTest` calls: the first arms it as every
 * earlier test in a CI fork does, the second consumes the replay so no later test inherits it.
 */
class UncaughtCoroutineReporterTests {
    @Test
    fun aLeakedExceptionIsReportedOnStderrWithItsCoroutineNameAndOwnStack() {
        runTest { }
        val name = "quiche-driver/server/deadbeef"
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            val leaker =
                CoroutineScope(Dispatchers.Default + CoroutineName(name)).launch {
                    throw IllegalStateException(LEAK)
                }
            runBlocking { leaker.join() }
        } finally {
            System.setErr(original)
        }
        val report = captured.toString()
        original.print(report)

        val header = report.lineSequence().single { it.startsWith(UncaughtCoroutineReporter.MARKER) }
        assertContains(header, "name=$name ")
        assertContains(header, "job=")
        assertContains(header, "thread=DefaultDispatcher-worker-")
        assertContains(report, "java.lang.IllegalStateException: $LEAK")
        assertContains(report, "at com.ditchoom.socket.quic.UncaughtCoroutineReporterTests")

        val replayed = assertFailsWith<IllegalStateException> { runTest { } }
        assertEquals("UncaughtExceptionsBeforeTest", replayed::class.simpleName)
        assertEquals(listOf(LEAK), replayed.suppressed.map { it.message })
    }

    private companion object {
        const val LEAK = "LEAK-MARKER"
    }
}
