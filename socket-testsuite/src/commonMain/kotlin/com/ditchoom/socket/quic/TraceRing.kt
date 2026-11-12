@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

// Everything in this file is `internal`: :socket-testsuite is a PUBLISHED module with an ABI dump, and
// a test-harness diagnostic must not become API. The only thing that changes outside this file is the
// body of `runQuicTest` and a `.withTraceRing(...)` on the option builders of the suites named below.

/**
 * The last [capacity] QUIC trace events of ONE connection, in memory, rendered as they arrive and
 * overwritten oldest-first. Lock-free and allocation-free on the hot path except for the rendered line
 * (one string per event); never prints, so it cannot perturb the race it is there to explain
 * (`LoopbackPeerDiagnostics` makes the same rule for the same reason).
 *
 * WHY: a failed QUIC test reports an assertion or a deadline and nothing about the connection, and the
 * protocol trace was only kept by the handful of tests that wired their own sink. The rest had to be
 * rerun with stdout captured. With the ring on every connection the suites build, the report of a
 * failing test carries the timeline of every connection the test opened.
 */
internal class TraceRing(
    val label: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val slots = Array(capacity) { AtomicReference<String?>(null) }
    private val written = AtomicInt(0)

    /** The sink the engine records into. Called from several coroutines; the ring tolerates that. */
    val sink: TraceSink = TraceSink { event -> add(render(event)) }

    fun add(line: String) {
        val n = written.fetchAndIncrement()
        slots[n % capacity].store(line)
    }

    /** Total events seen, including those already overwritten. */
    fun seen(): Int = written.load()

    /** The retained events, oldest first. A concurrent writer may make the very oldest slot read as the newest. */
    fun snapshot(): List<String> {
        val end = written.load()
        val start = if (end > capacity) end - capacity else 0
        val out = ArrayList<String>(end - start)
        for (n in start until end) slots[n % capacity].load()?.let { out.add(it) }
        return out
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
        private const val MAX_LINE = 200

        /**
         * Header-only: a DGRAM line's payload hex is ~2400 characters for a full datagram and would bury
         * the report, and what a stall needs is that the datagram moved, its size and its path.
         */
        private fun pathOf(path: TracePath?): String = if (path == null) "path=-" else "path=v${path.family}:${path.port}"

        fun render(event: TraceEvent): String =
            when (event) {
                is TraceEvent.DgramOut -> "${event.at.inWholeMilliseconds}ms DGRAM_OUT ${event.len} ${pathOf(event.path)}"
                is TraceEvent.DgramIn -> "${event.at.inWholeMilliseconds}ms DGRAM_IN ${event.len} ${pathOf(event.path)}"
                else -> event.toString().take(MAX_LINE)
            }
    }
}

/**
 * The rings of the test that is running. [runQuicTest] clears it when a test starts and reads it when
 * the test fails; the suites' option builders add a ring per connection. Process-global because a test
 * process runs one test at a time (`maxParallelForks = 1`, and Kotlin/Native and Node run tests in
 * sequence), and bounded to the last [MAX_RINGS] connections so a soak test that opens thousands keeps
 * memory flat and a report short.
 */
internal object TraceRings {
    private const val MAX_RINGS = 8
    private const val MAX_REPORT_EVENTS = 120 // per connection, newest kept: a report is read, not parsed

    private val rings = AtomicReference<List<TraceRing>>(emptyList())
    private val sequence = AtomicInt(0)

    /** A new ring for the next connection of the running test, named `<role>#<n>`. */
    fun ringFor(role: String): TraceRing {
        val ring = TraceRing("$role#${sequence.fetchAndIncrement()}")
        while (true) {
            val current = rings.load()
            val next = (current + ring).takeLast(MAX_RINGS)
            if (rings.compareAndSet(current, next)) return ring
        }
    }

    /** A test is starting: forget the previous test's connections. */
    fun beginTest() {
        rings.store(emptyList())
        sequence.store(0)
    }

    /** The timeline of every retained connection, or null when the test recorded none. */
    fun report(): String? {
        val current = rings.load()
        if (current.isEmpty() || current.all { it.seen() == 0 }) return null
        return buildString {
            appendLine("QUIC protocol timeline of the failing test (last ${current.size} connection(s), newest events kept):")
            for (ring in current) {
                val events = ring.snapshot()
                appendLine("--- ${ring.label}: ${ring.seen()} events, last ${minOf(events.size, MAX_REPORT_EVENTS)} ---")
                events.takeLast(MAX_REPORT_EVENTS).forEach { appendLine("  $it") }
            }
        }
    }
}

/** Whether this process was asked to keep protocol rings: `QUIC_TEST_TRACE_RING=1` in the environment. */
internal expect fun traceRingRequested(): Boolean

/** What [runQuicTest] hangs on a failure: a suppressed exception whose message is the timeline. */
internal class QuicProtocolTimeline(
    timeline: String,
) : RuntimeException(timeline)

/**
 * [this] with a trace ring wired in beside whatever capture it already has: a test that set its own
 * `trace` keeps receiving every event, and its qlog and secrets targets are left as they were. A
 * server's capture factory is invoked once per accepted connection, so each accepted connection gets
 * its own ring.
 */
internal fun QuicOptions.withTraceRing(
    role: String,
    enabled: Boolean = traceRingRequested(),
): QuicOptions {
    // OFF unless the lane asks (QUIC_TEST_TRACE_RING=1): turning `trace` on wraps the connection's UDP
    // channels in the recording decorator, i.e. it changes the code under test, and this project has
    // been bitten by instrumentation that hid the race it was built to catch. Lanes that do not set
    // it run the suites exactly as before.
    if (!enabled) return this
    val existing = trace
    val factory: () -> QuicConnectionCapture = {
        val ring = TraceRings.ringFor(role)
        val inner = existing?.captureFor?.invoke()
        if (inner == null) {
            QuicConnectionCapture(ring.sink)
        } else {
            inner.copy(
                sink =
                    TraceSink { event ->
                        ring.sink.emit(event)
                        inner.sink.emit(event)
                    },
            )
        }
    }
    return copy(trace = QuicTraceCapture(factory, existing?.recordNetworkObservations ?: false))
}
