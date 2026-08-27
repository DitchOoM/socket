package com.ditchoom.socket.transport

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.socket.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Points the same prober [ReconnectingConnectionCollectorRaceTests] uses at [ReconnectingConnection.close].
 *
 * `close()` carried the check-then-act that #471 removed from [CodecConnection] and #473 removed
 * from this very class's collector guard — one field over from the latter, and landed 21 minutes
 * after the former:
 *
 * ```
 * if (closed) return
 * closed = true
 * currentConnection?.close()
 * ```
 *
 * `@Volatile` publishes the write; it does not make the pair atomic. Two callers can both read
 * `false`, both proceed, and both call `close()` on the same inner connection.
 *
 * ## Why this exists when `TeardownOnceLincheckTest` already convicts the shape
 *
 * Lincheck convicts the *guard*, reduced to two fields and a counter, because the real
 * `close()` only becomes interesting once `currentConnection` is non-null — and that field is
 * assigned inside the reconnect loop, so reaching it means running the whole machine: a monitor, a
 * `connect` lambda, and a live collector. That is not a model-checkable subject.
 *
 * So the two tests answer different halves and neither is redundant. Lincheck proves an interleaving
 * exists, minimally and deterministically. This proves the deployed class is actually reachable in
 * that state on real threads — which is the half a reduced model cannot speak to.
 *
 * ## What is being observed
 *
 * Never `closed` — that is the field under test. The witness is [CloseCountingConnection], the
 * connection `connect()` hands back: it counts `close()` calls on itself. The reconnect loop holds
 * exactly one inner connection at a time, and teardown releases it once, so a count above one is
 * reachable only when two callers both ran teardown.
 *
 * [peakClosersInFlight] is the vacuity guard, mirroring the `rejections > 0` assertion in the
 * sibling suite: a clean zero proves nothing unless the two `close()` calls actually overlapped.
 */
class ReconnectingConnectionCloseRaceTests {
    companion object {
        /** Matches `RACE_ATTEMPTS` in [ReconnectingConnectionCollectorRaceTests] so the numbers compare. */
        const val RACE_ATTEMPTS = 300

        const val THREAD_JOIN_MILLIS = 10_000L

        val TEST_TIMEOUT = 120.seconds
    }

    /**
     * The independent witness: a [Connection] that counts `close()` calls on itself.
     *
     * Signals [connected] from inside its own `receive()`, which the reconnect loop enters only
     * after it has assigned `currentConnection` and published [com.ditchoom.socket.ConnectionState.Connected] —
     * so awaiting it is how the racing closers know teardown has something to tear down. Parks on
     * [release] rather than completing, so the connection stays live for the whole window.
     */
    private class CloseCountingConnection(
        private val closes: AtomicInteger,
        private val connected: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : Connection<String> {
        override val id: Long = 0L

        override suspend fun send(message: String) = Unit

        override suspend fun close() {
            closes.incrementAndGet()
        }

        override fun receive(): Flow<String> =
            flow {
                connected.complete(Unit)
                release.await()
            }
    }

    @Test
    fun concurrentCloseTearsDownTheInnerConnectionOnce() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsWithDoubleClose = 0
                var attemptsContended = 0
                var maxCloses = 0

                repeat(RACE_ATTEMPTS) {
                    val closes = AtomicInteger(0)
                    val connected = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()

                    val connection =
                        ReconnectingConnection(
                            connect = { CloseCountingConnection(closes, connected, release) },
                            // AlwaysAvailable keeps the attempt hermetic: no platform monitor, no
                            // path-change flow that could serialise the closers by accident.
                            monitorFactory = { NetworkMonitor.AlwaysAvailable },
                        )

                    val collector =
                        thread(name = "reconnecting-close-collector") {
                            runBlocking {
                                try {
                                    connection.receive().collect { }
                                } catch (_: Throwable) {
                                    // The connection is torn down underneath this collector by design.
                                }
                            }
                        }
                    connected.await()

                    val inFlight = AtomicInteger(0)
                    val peakInFlight = AtomicInteger(0)
                    val gate = AtomicBoolean(false)
                    val closers =
                        (1..2).map {
                            thread(name = "reconnecting-closer-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    val inside = inFlight.incrementAndGet()
                                    peakInFlight.updateAndGet { peak -> maxOf(peak, inside) }
                                    try {
                                        connection.close()
                                    } finally {
                                        inFlight.decrementAndGet()
                                    }
                                }
                            }
                        }
                    // Both threads are already inside runBlocking and spinning, so releasing the gate
                    // lands them on the guard within nanoseconds of each other.
                    Thread.sleep(1)
                    gate.set(true)
                    closers.forEach { it.join(THREAD_JOIN_MILLIS) }

                    if (closes.get() > 1) attemptsWithDoubleClose++
                    if (peakInFlight.get() > 1) attemptsContended++
                    maxCloses = maxOf(maxCloses, closes.get())

                    release.complete(Unit)
                    collector.join(THREAD_JOIN_MILLIS)
                }

                println(
                    "[assumption] ReconnectingConnection concurrent close(): " +
                        "$attemptsWithDoubleClose/$RACE_ATTEMPTS attempts closed the inner " +
                        "connection more than once (max $maxCloses); " +
                        "$attemptsContended/$RACE_ATTEMPTS attempts had both closers in flight at once",
                )
                assertTrue(
                    attemptsContended > 0,
                    "no attempt had two close() calls in flight simultaneously, so this harness " +
                        "never actually contended and any zero above would be vacuous",
                )
                assertEquals(
                    0,
                    attemptsWithDoubleClose,
                    "two callers both ran teardown and closed the same inner connection, so the " +
                        "transport under it is closed twice — the check-then-act on `closed`",
                )
            }
        }
}
