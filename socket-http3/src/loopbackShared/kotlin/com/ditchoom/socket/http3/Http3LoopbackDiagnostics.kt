package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.describe
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Failure diagnostics for [Http3LoopbackTestSuite]: the last step the test body reached, plus the tail
 * of BOTH ends' QUIC traces, interleaved in arrival order.
 *
 * WHY: `productionServerRole_dynamicQpackRoundTrip[AndroidHttp3LoopbackTest]` failed on the API-35
 * emulator lane (run 31027926910, job 92381028321) as
 * `QuicCloseException: connection closed` thrown from `JvmQuicConnection.open` — the client's
 * connection was already gone when the test opened its next request stream. The typed
 * [com.ditchoom.socket.quic.QuicError] on that exception was the `NoError` fallback, so the record
 * said only "closed", never "why": idle timeout, a peer CONNECTION_CLOSE, or a torn-down scope all
 * render identically. It passed on rerun and API 29 was green, so waiting for a repro is not a
 * strategy — this captures the failure we DO get. Mirrors [`WebTransportDiagnostics`][87bc96f8].
 *
 * BOTH sides are captured, unlike the WebTransport precedent's server-only capture: the throw came
 * from the client, and whether the client died on its own (no peer CONNECTION_CLOSE in its trace) or
 * was torn down by the server (a matching close on the server's) is the whole question.
 *
 * [mark] is a plain atomic store, never `println` — the WebTransport investigation measured that
 * printing on the happy path serialises on IO and perturbed the race it was trying to catch.
 */
@OptIn(ExperimentalAtomicApi::class)
class Http3LoopbackDiagnostics {
    private val lastStep = AtomicReference("(not started)")
    private val events = AtomicReference(emptyList<String>())

    /** Records the step the test body is about to attempt. Cheap enough to leave on always. */
    fun mark(step: String) {
        lastStep.store(step)
    }

    /**
     * Connections whose [Http3Connection.connectionError] should be read at [report] time.
     *
     * WHY this exists: the peer that *raises* a protocol violation keeps it fully typed, but the peer
     * that *observes* the close only ever sees the opaque wire code. #291 failed with
     * `ApplicationError(applicationCode=514)` — QPACK_DECODER_STREAM_ERROR (0x202, RFC 9204 §6) — which
     * narrows it to one of four named violations but says nothing about WHICH, or with what operands.
     * `Http3Connection.abortConnection` already stores exactly that object; nothing read it back.
     */
    private val connections = AtomicReference(emptyList<Pair<String, Http3Connection>>())

    /**
     * Register a connection so its typed connection-level violation lands in the failure report.
     * Call right after opening one; safe to call for connections that never fail.
     */
    fun registerConnection(
        side: String,
        connection: Http3Connection,
    ) {
        while (true) {
            val current = connections.load()
            if (connections.compareAndSet(current, current + (side to connection))) return
        }
    }

    /**
     * A violation the hand-rolled [Http3LoopbackServer] caught per-stream.
     *
     * That server deliberately does NOT take the connection down when one stream fails, so before this
     * hook a server-side QPACK violation was discarded at the `catch (_: Http3StreamException)` and left
     * no trace anywhere — the one origin the report could never explain. Recording is orthogonal to the
     * swallow: the connection still stays up, exactly as before.
     */
    private val streamViolations = AtomicReference(emptyList<Pair<String, Http3StreamException>>())

    fun recordStreamViolation(
        side: String,
        violation: Http3StreamException,
    ) {
        while (true) {
            val current = streamViolations.load()
            val next =
                if (current.size >= MAX_VIOLATIONS) {
                    current.subList(1, current.size) + (side to violation)
                } else {
                    current + (side to violation)
                }
            if (streamViolations.compareAndSet(current, next)) return
        }
    }

    /**
     * A connection-level close the [Http3LoopbackServer] absorbed as its terminal condition.
     *
     * The server role ends when the peer closes the connection, so a [QuicCloseException] raised on a
     * stream mid-flight is normal termination rather than a failure — see the catch in
     * [Http3LoopbackServer.serve]. It is recorded rather than discarded because the typed
     * [com.ditchoom.socket.quic.QuicError] is the only thing separating the abort a test *asked* for
     * (`ApplicationError(0x105)`, H3_FRAME_UNEXPECTED, from a client rejecting a malformed frame
     * sequence) from one it did not (an idle timeout, a transport error). Swallowing it untyped would
     * repeat the #291 gap the stream-level recorder above exists to close.
     */
    private val connectionCloses = AtomicReference(emptyList<Pair<String, QuicCloseException>>())

    fun recordConnectionClose(
        side: String,
        close: QuicCloseException,
    ) {
        while (true) {
            val current = connectionCloses.load()
            val next =
                if (current.size >= MAX_VIOLATIONS) {
                    current.subList(1, current.size) + (side to close)
                } else {
                    current + (side to close)
                }
            if (connectionCloses.compareAndSet(current, next)) return
        }
    }

    /**
     * A harness coroutine that died with an exception nothing caught.
     *
     * WHY: the harness server handles each stream in `scope.launch` under the connection's
     * SupervisorJob, so an exception that escapes it never reaches the test body — it goes to the
     * coroutine machinery's uncaught handler, and `kotlinx-coroutines-test` then fails the test at the
     * END of `runTest`, after the body has already passed and `runHttp3LoopbackTest`'s catch has been
     * skipped. That is how `malformedFrameSequenceFromServer_abortsConnection` failed twice on the
     * API-29 lane on 2026-08-20 with the bare `QuicCloseException` and the job log saying
     * `(no H3Loopback report …)`: the report machinery existed and was never reached. The launch now
     * carries a handler that records here, and the runner checks this after a passing body.
     */
    private val uncaught = AtomicReference(emptyList<Pair<String, Throwable>>())

    fun recordUncaught(
        side: String,
        failure: Throwable,
    ) {
        while (true) {
            val current = uncaught.load()
            val next =
                if (current.size >= MAX_VIOLATIONS) {
                    current.subList(1, current.size) + (side to failure)
                } else {
                    current + (side to failure)
                }
            if (uncaught.compareAndSet(current, next)) return
        }
    }

    /** Whether any harness coroutine has died uncaught so far. Read by the runner after the body. */
    val hasUncaught: Boolean get() = uncaught.load().isNotEmpty()

    /** Capture sink for the in-process server's connections (every accepted connection interleaves). */
    val serverSink: TraceSink = sinkTagged("S")

    /** Capture sink for the client connection the test body dials. */
    val clientSink: TraceSink = sinkTagged("C")

    /**
     * A sink tagging every line with [side] and appending it to the shared ring, so client and server
     * lines interleave in the order they were actually recorded — which is what shows which end
     * stopped making progress first.
     *
     * Bounded to [MAX_EVENTS] most-recent lines: a stalled connection keeps emitting (timer wakes,
     * path polls) and the TAIL is where progress stopped, so an unbounded buffer would only risk
     * memory for older, less useful lines.
     */
    private fun sinkTagged(side: String): TraceSink =
        TraceSink { event: TraceEvent ->
            // DGRAM lines carry the full packet hex (~2400 chars for a 1200-byte datagram) and would
            // bury the report; a header-only summary still localises the stall (when, direction, size,
            // 4-tuple). Deliberately NOT prefixed `v1` — with the payload dropped it is no longer a
            // valid trace line and must not be mistaken for one by a replay parser. STATE / PATH_STATE
            // / ERROR — the lines that name the typed close reason — are short, so they are kept
            // verbatim and are what this report exists to surface.
            val rendered =
                when (event) {
                    is TraceEvent.DgramOut -> "${event.at.inWholeNanoseconds} DGRAM_OUT ${event.len} ${event.path} (payload omitted)"
                    is TraceEvent.DgramIn -> "${event.at.inWholeNanoseconds} DGRAM_IN ${event.len} ${event.path} (payload omitted)"
                    else -> event.toString()
                }
            val line = "$side ${rendered.take(MAX_LINE)}"
            while (true) {
                val current = events.load()
                val next = if (current.size >= MAX_EVENTS) current.subList(1, current.size) + line else current + line
                if (events.compareAndSet(current, next)) return@TraceSink
            }
        }

    fun report(cause: Throwable): String =
        buildString {
            appendLine("=== HTTP/3 loopback failure diagnostics ===")
            appendLine("cause: ${typeName(cause)}: ${cause.message}")
            causeChain(cause).forEach { appendLine("  caused by: ${typeName(it)}: ${it.message}") }
            // The typed QUIC reason the failure carries, side included — `Unspecified` here means the
            // connection state held no reason when the exception was minted, and the STATE / ERROR
            // lines in the trace below are then the only place the real cause appears.
            (listOf(cause) + causeChain(cause)).filterIsInstance<QuicCloseException>().firstOrNull()?.let {
                appendLine("typed QUIC reason: ${it.closeReason.describe()}")
            }
            appendLine("last step reached: ${lastStep.load()}")
            // The typed violation behind an opaque application close code. `ApplicationError(514)` alone
            // is only "some QPACK decoder-stream error"; the violation names which of the four and its
            // operands, which is the difference between a hypothesis and a fix (#291).
            connections.load().forEach { (side, connection) ->
                connection.connectionError?.let { e ->
                    appendLine("$side connection-level H3 violation: ${e.violation}")
                    appendLine("  -> ${e.violation.describe()} (error code 0x${e.errorCode.toString(16)})")
                }
            }
            streamViolations.load().forEach { (side, e) ->
                appendLine("$side stream-level H3 violation (connection kept up): ${e.violation}")
                appendLine("  -> ${e.violation.describe()} (error code 0x${e.errorCode.toString(16)})")
            }
            // Normal termination when a test asked the peer to abort; the typed reason is what says so.
            connectionCloses.load().forEach { (side, e) ->
                appendLine("$side absorbed a connection close (server role ended): ${e.closeReason.describe()}")
            }
            uncaught.load().forEach { (side, t) ->
                appendLine("$side harness coroutine died UNCAUGHT (never reached the test body): ${typeName(t)}: ${t.message}")
                causeChain(t).forEach { appendLine("  caused by: ${typeName(it)}: ${it.message}") }
                (listOf(t) + causeChain(t)).filterIsInstance<QuicCloseException>().firstOrNull()?.let {
                    appendLine("  typed QUIC reason: ${it.closeReason.describe()}")
                }
            }
            val captured = events.load()
            // Lines are in capture order, which is the comparable axis: each recorder stamps against
            // its OWN connection's clock origin (RFC §5), so a client `at` and a server `at` are not
            // on the same timeline and must not be read as one.
            appendLine("QUIC trace, C=client S=server, in capture order (${captured.size} most recent events):")
            captured.forEach { appendLine("  $it") }
            appendLine("=== end diagnostics ===")
        }

    /**
     * `simpleName`, NOT `qualifiedName`: this file lives in `src/loopbackShared`, which is wired into
     * `commonTest` as well as `androidInstrumentedTest`, so it compiles for EVERY target — including
     * Kotlin/JS, where `KClass.qualifiedName` is a hard compile error ("This reflection API is not
     * supported in Kotlin/JS"). That is what broke `:socket-http3:compileTestKotlinJs`; the local
     * jvm/macos verification never compiled the JS target. The package prefix is no loss here: an
     * `androidTest` APK is not minified, so the simple name is the real class name, and the typed QUIC
     * reason — the part the report exists to surface — is printed separately below.
     */
    private fun typeName(t: Throwable): String = t::class.simpleName ?: "Throwable"

    private fun causeChain(t: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var next = t.cause
        while (next != null && next !in chain) {
            chain += next
            next = next.cause
        }
        return chain
    }

    private companion object {
        const val MAX_EVENTS = 250
        const val MAX_LINE = 180
        const val MAX_VIOLATIONS = 16
    }
}
