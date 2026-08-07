package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicCloseException
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
            // The typed QUIC reason the failure carries. `NoError` here means the connection state
            // held no reason when the exception was minted, and the STATE / ERROR lines in the trace
            // below are then the only place the real cause appears.
            (listOf(cause) + causeChain(cause)).filterIsInstance<QuicCloseException>().firstOrNull()?.let {
                appendLine("typed QUIC reason: ${it.quicError.describe()}")
            }
            appendLine("last step reached: ${lastStep.load()}")
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
    }
}
