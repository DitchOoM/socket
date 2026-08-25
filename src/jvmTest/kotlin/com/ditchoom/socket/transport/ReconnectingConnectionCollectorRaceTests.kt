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
 * Points [CodecLifecycleAssumptionTests]' double-collector prober at [ReconnectingConnection].
 *
 * `ReconnectingConnection.receive()` opens with the same check-then-act [CodecConnection] carried
 * until #471:
 *
 * ```
 * check(!receiving) { "receive() is already being collected" }
 * receiving = true
 * ```
 *
 * `@Volatile` publishes the write; it does not make the pair atomic. Two collectors can both read
 * `false` before either writes `true`, and the same harness measured that admitting two collectors
 * into [CodecConnection] in 140/300 attempts.
 *
 * The consequence here is strictly worse than it was there. [CodecConnection]'s two collectors shared
 * a stream processor; these two each run the **whole reconnect loop** — so each calls `connect()`,
 * each writes the single `currentConnection` field, and the loser's socket is overwritten with no
 * one left holding a reference to close it. `send()` then writes into whichever connection happened
 * to land last. The `finally { receiving = false }` compounds it: the first collector to finish
 * clears the flag while the second is still looping, so a *third* collector is admitted on top.
 *
 * ## What is being observed
 *
 * Never `receiving` — that is the flag under test, and a prober that reads it would be arguing with
 * itself. The witness is [GatedConnection], the connection `connect()` hands back: it counts readers
 * inside its own `receive()` and records the peak. A single admitted collector holds exactly one
 * inner connection open at a time (it collects one to completion before reconnecting), so a peak
 * above one is reachable only when two collectors are simultaneously running the loop.
 *
 * That distinction is the trap this suite exists to avoid: `connect()` being called more than once is
 * **legal** — it is what reconnection *is* — so counting invocations would convict correct code.
 * Only simultaneity is evidence.
 *
 * ## Why threads rather than coroutines
 *
 * Same reason as [CodecLifecycleAssumptionTests]: two coroutines on [Dispatchers.Default] can be
 * scheduled sequentially on a starved runner, and the harness would report a clean zero having never
 * contended. Real platform threads spin on [AtomicBoolean] before the gate opens, so both are already
 * inside `runBlocking` and land on the guard within nanoseconds of each other.
 */
class ReconnectingConnectionCollectorRaceTests {
    companion object {
        /** Matches `RACE_ATTEMPTS` in [CodecLifecycleAssumptionTests] so the numbers are comparable. */
        const val RACE_ATTEMPTS = 300

        const val THREAD_JOIN_MILLIS = 10_000L

        val TEST_TIMEOUT = 120.seconds
    }

    /**
     * The independent witness: a [Connection] that counts collectors inside its own `receive()`.
     *
     * Parks on [release] rather than completing, so a collector admitted into the reconnect loop stays
     * inside the inner `collect` for the whole observation window instead of racing through it.
     */
    private class GatedConnection(
        private val readersInside: AtomicInteger,
        private val peakReadersInside: AtomicInteger,
        private val release: CompletableDeferred<Unit>,
    ) : Connection<String> {
        override val id: Long = 0L

        override suspend fun send(message: String) = Unit

        override suspend fun close() = Unit

        override fun receive(): Flow<String> =
            flow {
                val inside = readersInside.incrementAndGet()
                peakReadersInside.updateAndGet { peak -> maxOf(peak, inside) }
                try {
                    release.await()
                } finally {
                    readersInside.decrementAndGet()
                }
            }
    }

    @Test
    fun concurrentCollectorsAreNeverBothAdmitted() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsWithTwoInside = 0
                var rejections = 0
                var peakSeen = 0
                repeat(RACE_ATTEMPTS) {
                    val readersInside = AtomicInteger(0)
                    val peakReadersInside = AtomicInteger(0)
                    val release = CompletableDeferred<Unit>()
                    val connectCalls = AtomicInteger(0)

                    val connection =
                        ReconnectingConnection(
                            connect = {
                                connectCalls.incrementAndGet()
                                GatedConnection(readersInside, peakReadersInside, release)
                            },
                            // AlwaysAvailable keeps the attempt hermetic: no platform monitor, no
                            // path-change flow that could serialise the two collectors by accident.
                            monitorFactory = { NetworkMonitor.AlwaysAvailable },
                        )

                    val rejected = AtomicInteger(0)
                    val gate = AtomicBoolean(false)
                    val collectors =
                        (1..2).map {
                            thread(name = "reconnecting-collector-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    try {
                                        connection.receive().collect { }
                                    } catch (_: IllegalStateException) {
                                        rejected.incrementAndGet()
                                    }
                                }
                            }
                        }
                    // Both threads are already inside runBlocking and spinning, so releasing the gate
                    // lands them on the guard within nanoseconds of each other.
                    Thread.sleep(1)
                    gate.set(true)
                    awaitReadersOrTimeout(readersInside, expected = 2)

                    if (peakReadersInside.get() > 1) attemptsWithTwoInside++
                    if (rejected.get() > 0) rejections++
                    peakSeen = maxOf(peakSeen, peakReadersInside.get())

                    release.complete(Unit)
                    collectors.forEach { it.join(THREAD_JOIN_MILLIS) }
                    connection.close()
                }
                println(
                    "[assumption] ReconnectingConnection concurrent receive(): " +
                        "$attemptsWithTwoInside/$RACE_ATTEMPTS attempts had TWO collectors " +
                        "simultaneously inside the reconnect loop (peak readers $peakSeen); " +
                        "$rejections/$RACE_ATTEMPTS attempts saw the guard reject one",
                )
                assertTrue(
                    rejections > 0,
                    "no attempt saw the guard reject a collector, so this harness never actually " +
                        "contended and any zero above would be vacuous",
                )
                assertEquals(
                    0,
                    attemptsWithTwoInside,
                    "two collectors were simultaneously inside ReconnectingConnection's reconnect " +
                        "loop, so both called connect() and both wrote currentConnection — the loser's " +
                        "connection is leaked and send() targets whichever landed last",
                )
            }
        }

    /**
     * Spins until [expected] readers are inside, or the window closes.
     *
     * A bounded spin rather than a fixed sleep: the fast path returns as soon as the race resolves,
     * and the slow path still bounds the attempt so a rejected collector cannot hang the suite.
     */
    private fun awaitReadersOrTimeout(
        readersInside: AtomicInteger,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 50_000_000L
        while (System.nanoTime() < deadline) {
            if (readersInside.get() >= expected) return
            Thread.onSpinWait()
        }
    }
}
