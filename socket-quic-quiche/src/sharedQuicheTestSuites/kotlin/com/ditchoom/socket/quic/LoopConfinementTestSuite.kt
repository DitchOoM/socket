package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Only the driver loop's turn touches a connection, checked where it runs (confinement audit, section 6).
 *
 * Real quiche on both ends over loopback, on the platform's production dispatchers, with a
 * [LoopGuard.Checking] on every driver: each quiche call that takes a connection, `recv_info` or
 * `send_info` goes through a [GuardedQuicheApi], and `dispatch`, `afterCommand`, `cleanup` and path
 * teardown assert on entry. The guard owns the loop's *turn*, not its thread: it is claimed after each of
 * the loop's suspension points and released before the next (see [LoopGuard]), so it holds on a dispatcher
 * that moves the loop between threads, which is what native `Dispatchers.Default` does.
 *
 * Members per platform because the backends bind quiche separately: JVM (JNI by default, FFM with
 * `-PquicheJvmBackend=ffm`), Linux and Apple K/Native.
 */
abstract class LoopConfinementTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private val options =
        QuicOptions(
            alpnProtocols = listOf("loop-confinement"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            migration = MigrationPolicy.Manual,
        )

    /**
     * Echo traffic over several connections and streams at once, then a server close from two coroutines.
     * Every driver involved (each connection's client and server end) runs under the guard.
     */
    @Test
    fun nothingTouchesAConnectionOffTheLoopsTurnUnderTraffic() =
        runQuicTest(timeout = 60.seconds) {
            wrapTestBody {
                val log = ViolationLog()
                val before = DebugLoopGuard.policy
                DebugLoopGuard.policy = DebugLoopGuard.Policy.Checked(log)
                try {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = options) {
                        val serverJob =
                            launch {
                                connections {
                                    // The handler returning ends the connection, so it waits for every stream.
                                    coroutineScope {
                                        repeat(STREAMS_PER_CONNECTION) {
                                            launch {
                                                val stream = acceptStream()
                                                val payload = stream.readToEnd()
                                                stream.writeText("echo:$payload")
                                                stream.shutdownSend()
                                            }
                                        }
                                    }
                                }
                            }
                        try {
                            coroutineScope {
                                (0 until CONNECTIONS)
                                    .map { connection ->
                                        async(Dispatchers.Default) {
                                            withQuicConnection("127.0.0.1", port, options, timeout = 10.seconds) {
                                                (0 until STREAMS_PER_CONNECTION)
                                                    .map { n ->
                                                        async {
                                                            val reply = openStream().exchange("c$connection-s$n")
                                                            assertEquals("echo:c$connection-s$n", reply)
                                                        }
                                                    }.awaitAll()
                                            }
                                        }
                                    }.awaitAll()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                } finally {
                    DebugLoopGuard.policy = before
                }
                assertTrue(
                    log.loopCount >= 2 * CONNECTIONS,
                    "expected a guarded loop on each end of each connection, saw ${log.loopCount}: the guard was not installed",
                )
                assertTrue(
                    log.turnCount > 0 && log.checkCount > 0,
                    "no turn or no check was counted: ${log.turnCount} turns, ${log.checkCount} checks",
                )
                assertEquals(emptyList(), log.violations, "an access touched a connection off the loop's turn")
            }
        }

    /**
     * The guard is only worth having if it fails when it should. A call from another thread during a turn,
     * one between two turns, and one after the loop ended are each recorded, with the site and the reason.
     */
    @Test
    fun theGuardCatchesAnAccessOffTheLoopsTurn() =
        runQuicTest {
            val log = ViolationLog()
            // The calling thread, as the guard sees it: a variable the test moves, so "another thread" is stated
            // and not left to the scheduler. The real thread id is what the traffic test above exercises.
            var thread = 1L
            val guard = LoopGuard.Checking(log) { thread }
            val api = GuardedQuicheApi(StubQuicheApi(), guard)
            val conn = QuicheConn(1L)

            // Before the loop starts the hand-over is ordered by the launch: allowed.
            api.connTimeout(conn)
            assertEquals(emptyList(), log.violations)

            guard.beginLoop()
            api.connTimeout(conn)
            assertEquals(emptyList(), log.violations, "the loop's own turn was flagged")

            // Another thread during the turn.
            thread = 2L
            api.connTimeout(conn)
            assertEquals(listOf(LoopViolation(LoopSite.Quiche, ViolationKind.OtherThreadDuringTurn)), log.violations)
            thread = 1L

            // Between turns.
            guard.suspendTurn()
            api.connTimeout(conn)
            assertEquals(LoopViolation(LoopSite.Quiche, ViolationKind.BetweenTurns), log.violations.last())

            // The next turn may start on another thread: a coroutine resumes anywhere after a suspension.
            thread = 2L
            guard.resumeTurn()
            val settled = log.violations.size
            api.connTimeout(conn)
            assertEquals(settled, log.violations.size, "a turn that resumed on another thread was flagged")

            // After the loop ended.
            guard.endLoop()
            api.connTimeout(conn)
            assertEquals(LoopViolation(LoopSite.Quiche, ViolationKind.AfterTheLoopEnded), log.violations.last())
        }

    private suspend fun QuicByteStream.exchange(payload: String): String {
        writeText(payload)
        shutdownSend()
        return readToEnd()
    }

    private suspend fun QuicByteStream.writeText(payload: String) {
        writeFully(text(payload), STREAM_DEADLINE)
    }

    private suspend fun QuicByteStream.readToEnd(): String {
        val sb = StringBuilder()
        while (true) {
            when (val chunk = read(STREAM_DEADLINE) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> sb.append(chunk.value)
                ScopedRead.End, ScopedRead.Reset -> return sb.toString()
            }
        }
    }

    private fun text(payload: String): ReadBuffer {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        return out
    }

    private companion object {
        const val CONNECTIONS = 4
        const val STREAMS_PER_CONNECTION = 3
        val STREAM_DEADLINE = 10.seconds
    }
}
