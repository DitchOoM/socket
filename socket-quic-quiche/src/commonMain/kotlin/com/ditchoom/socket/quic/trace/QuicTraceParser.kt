package com.ditchoom.socket.quic.trace

/**
 * Decodes `v1` trace lines (grammar: [QuicTraceRecorder] KDoc) back into typed [QuicTraceEvent]s —
 * the emitter's exact inverse, kept next to it so the two can never drift. `parse(emit(e)) == e`
 * for every event type; the fixture codegen consumes the [QuicTraceEvent.isInput] subset.
 */
object QuicTraceParser {
    /** Decode one line. Throws [IllegalArgumentException] on a malformed or unknown-version line. */
    fun parseLine(line: String): QuicTraceEvent = decodeTraceLine(line)

    /** Decode a whole trace (one event per line; blank lines skipped), preserving order. */
    fun parse(lines: List<String>): List<QuicTraceEvent> = lines.filter { it.isNotBlank() }.map { decodeTraceLine(it) }

    /** Decode a whole trace from newline-joined text. */
    fun parse(text: String): List<QuicTraceEvent> = parse(text.lines())
}
