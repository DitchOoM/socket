package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the caller's thread allocates to enter an idle connection, serve it and leave: one preallocated work item is
 * submitted over and over, so anything counted is the in-place path's own.
 */
class CallerInPlaceAllocationTests {
    /** Loops whose sockets lend callers lanes, whatever this host's own rule. */
    private val loops = LoopSet(1, sends = SendRule.InPlace)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun enteringServingAndLeavingAConnectionAllocatesNothingOnTheCallersThread() =
        runBlocking {
            skipOnMissingNativeLib(CallerInPlaceAllocationTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port, loops = loops)
                    val measured = CompletableFuture<Double>()
                    val caller =
                        Thread {
                            val work = CountingWork()
                            val bytes = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                            repeat(WARM_UP) { submitAndWait(client.conn, work) }
                            val before = bytes.getThreadAllocatedBytes(Thread.currentThread().id)
                            repeat(MEASURED) { submitAndWait(client.conn, work) }
                            measured.complete((bytes.getThreadAllocatedBytes(Thread.currentThread().id) - before).toDouble() / MEASURED)
                        }
                    caller.name = "test-caller"
                    caller.start()
                    val perEntry = measured.get(60, TimeUnit.SECONDS)
                    println(
                        "[core in-place allocation] %.2f bytes per entry on the caller's thread; entered in place %d of %d".format(
                            perEntry,
                            client.conn.counts.enteredInPlace,
                            WARM_UP + MEASURED,
                        ),
                    )
                    assertTrue(perEntry < 1.0, "the caller's thread allocated %.2f bytes per in-place entry".format(perEntry))
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    /** Submits [work] and returns once it has run, on whichever thread ran it. */
    private fun submitAndWait(
        conn: Conn,
        work: CountingWork,
    ) {
        val target = work.ran + 1
        conn.submit(work, CallerPlace.MayEnter)
        while (work.ran < target) Thread.onSpinWait()
    }

    private class CountingWork : ConnWork() {
        @Volatile
        var ran = 0L

        override fun run(inside: Inside) {
            ran++
        }

        override fun gone(end: ConnEnd) = assertEquals(0, 1, "the connection was released under the test")
    }

    private companion object {
        const val WARM_UP = 20_000
        const val MEASURED = 20_000
    }
}
