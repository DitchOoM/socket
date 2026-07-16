package com.ditchoom.socket.quic.trace.tools

import com.ditchoom.socket.quic.trace.TraceEvent

/**
 * Rewrites the obfuscated class-name tokens in a captured QUIC `v1` trace back to their original
 * source names — the crash-reporter model the typed-trace refactor was built for: the recorder
 * captures **qualified** class names ([TraceEvent.State.name] / [TraceEvent.Error.type]) so a trace
 * from a ProGuard/R8-obfuscated build — an Android/ART release **or** a JVM app shrunk with
 * ProGuard/R8 — can be retraced after the fact against the shipped `mapping.txt` (no keep-rules, no
 * literals baked into the app). ProGuard and R8 emit the same mapping grammar, so one path covers
 * both.
 *
 * Format-aware, not a generic stack-trace retrace: only the two tokens that carry a class FQN are
 * remapped ([TraceEvent.State.name], [TraceEvent.Error.type]); every other event and field passes
 * through untouched. Works on the typed [TraceEvent] (not blind string surgery), so a name absent
 * from the mapping — a non-obfuscated build, or a Kotlin/Native or JS trace (no ProGuard/R8 there) —
 * is a clean identity pass-through.
 *
 * The [resolve] function is the only injection point: `obfuscated FQN → original FQN` (identity when
 * unknown). [fromMapping] wires it to R8's retrace over a `mapping.txt`; tests inject a fake resolver
 * to exercise the rewrite logic without R8.
 */
class TraceDeobfuscator(
    private val resolve: (String) -> String,
) {
    /** Deobfuscate one event: remap the FQN token of STATE / ERROR, pass everything else through. */
    fun deobfuscate(event: TraceEvent): TraceEvent =
        when (event) {
            is TraceEvent.State -> event.copy(name = resolve(event.name))
            is TraceEvent.Error -> event.copy(type = resolve(event.type))
            else -> event
        }

    /** Parse one `v1` line, deobfuscate it, and re-render it (round-trips through [TraceEvent]). */
    fun deobfuscateLine(line: String): String = deobfuscate(TraceEvent.parse(line)).toString()

    /** Deobfuscate a whole trace (one event per line; blank lines dropped), preserving order. */
    fun deobfuscateAll(lines: List<String>): List<String> = TraceEvent.parseAll(lines).map { deobfuscate(it).toString() }

    companion object {
        /** Build a deobfuscator backed by R8 retrace over the given `mapping.txt` contents. */
        fun fromMapping(mappingText: String): TraceDeobfuscator = TraceDeobfuscator(R8ClassResolver(mappingText)::resolve)
    }
}
