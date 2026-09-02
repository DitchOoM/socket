package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import java.util.concurrent.atomic.AtomicReference

/**
 * Failure diagnostics for a two-peer loopback exchange driven from two launched coroutines — the
 * shape `QuicLocalServerTests` uses — so that a stall names *where* each peer stopped, what each end
 * put on the wire and saw arrive, and what every thread in the JVM was doing at that moment.
 *
 * WHY: `QuicLocalServerTests.echoSingleStream` hung for its full 10 s budget on the Apple **FFM** lane
 * (#356) and reported only `Timed out waiting for 10000 ms` at the awaiting side. #353 made the
 * next occurrence say which peer stalled and at which step; that is a *location*, and a location
 * alone cannot separate the readings a stalled read has — the server's write never left (server
 * `DGRAM_OUT` missing), it left and never arrived (client `DGRAM_IN` missing), it arrived and quiche
 * never surfaced it (client `DGRAM_IN` present, no readable wake), or the driver loop that would
 * have woken the reader is itself parked. The first three are read off both ends' traces
 * interleaved in capture order; the last is read off the thread inventory. That is the input a fix
 * needs, and none of it survives a rerun.
 *
 * Every write here is an atomic store or a CAS-append, never a `println` — printing on the happy
 * path serialises on IO and has been measured to perturb the race it was meant to catch.
 */
class LoopbackPeerDiagnostics {
    /**
     * The last step each peer reached, written from the peer coroutines and read from the test
     * coroutine on timeout. Some of the calls a peer makes are unbounded — `connections`,
     * `acceptStream`, `openStream` — so a stall there throws nothing at all; the step is the only
     * evidence of which call it was.
     */
    @Volatile
    var clientStep: String = "not started"

    @Volatile
    var serverStep: String = "not started"

    private val events = AtomicReference(emptyList<String>())

    /** Capture sink for the client connection: `QuicOptions(trace = QuicTraceCapture(clientSink))`. */
    val clientSink: TraceSink = sinkTagged("C")

    /** Capture sink for the server: every accepted connection plus the receive loop's own records. */
    val serverSink: TraceSink = sinkTagged("S")

    /** [options] with this diagnostics' client-side capture wired in. */
    fun clientOptions(options: QuicOptions): QuicOptions = options.copy(trace = QuicTraceCapture(clientSink))

    /** [options] with this diagnostics' server-side capture wired in. */
    fun serverOptions(options: QuicOptions): QuicOptions = options.copy(trace = QuicTraceCapture(serverSink))

    /**
     * Header-only lines (a DGRAM line's payload hex is ~2400 chars for a full datagram and would
     * bury the report), tagged by side and appended to ONE ring so the two ends interleave in the
     * order they were recorded — which is what shows which end stopped first. Bounded to the most
     * recent [MAX_EVENTS]: a stalled connection keeps emitting timer wakes and the tail is where
     * progress stopped.
     */
    private fun sinkTagged(side: String): TraceSink =
        TraceSink { event: TraceEvent ->
            val rendered =
                when (event) {
                    is TraceEvent.DgramOut -> "${event.at.inWholeNanoseconds} DGRAM_OUT ${event.len} ${event.path} (payload omitted)"
                    is TraceEvent.DgramIn -> "${event.at.inWholeNanoseconds} DGRAM_IN ${event.len} ${event.path} (payload omitted)"
                    else -> event.toString()
                }
            val line = "$side ${rendered.take(MAX_LINE)}"
            while (true) {
                val current = events.get()
                val next = if (current.size >= MAX_EVENTS) current.subList(1, current.size) + line else current + line
                if (events.compareAndSet(current, next)) return@TraceSink
            }
        }

    fun report(cause: Throwable): String =
        buildString {
            appendLine("=== loopback peer failure diagnostics ===")
            appendLine("cause: ${cause::class.simpleName}: ${cause.message}")
            var next = cause.cause
            var depth = 0
            while (next != null && depth++ < MAX_CAUSE_DEPTH) {
                appendLine("  caused by: ${next::class.simpleName}: ${next.message}")
                next = next.cause
            }
            appendLine("client stalled at '$clientStep', server at '$serverStep'")
            appendLine("threads: ${jvmThreadInventory()}")
            val captured = events.get()
            // Capture order, not timestamp order: each recorder stamps against its OWN clock origin, so
            // a client `at` and a server `at` are not on one timeline.
            appendLine("QUIC trace, C=client S=server, in capture order (${captured.size} most recent events):")
            captured.forEach { appendLine("  $it") }
            appendLine("=== end diagnostics ===")
        }

    private companion object {
        const val MAX_EVENTS = 250
        const val MAX_LINE = 180
        const val MAX_CAUSE_DEPTH = 6
    }
}
