package com.ditchoom.socket.quic.trace.tools

import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.references.Reference
import com.android.tools.r8.retrace.ProguardMapProducer
import com.android.tools.r8.retrace.Retracer

/**
 * The R8-backed `obfuscated FQN → original FQN` resolver behind [TraceDeobfuscator.fromMapping].
 * Wraps R8's public retrace API over a parsed `mapping.txt`; this is the only file that touches R8,
 * so the rest of the module (and its tests) stay independent of the retrace API surface.
 *
 * [resolve] is identity-on-miss: a name absent from the mapping (or one that isn't a valid class
 * type name) comes back unchanged, which is exactly the behavior a non-obfuscated trace needs.
 */
internal class R8ClassResolver(
    mappingText: String,
) {
    private val retracer: Retracer =
        Retracer.createDefault(ProguardMapProducer.fromString(mappingText), object : DiagnosticsHandler {})

    /** Map [obfuscated] to its original source FQN, or return it unchanged when unmapped/invalid. */
    fun resolve(obfuscated: String): String =
        try {
            retracer
                .retraceClass(Reference.classFromTypeName(obfuscated))
                .stream()
                .map { it.retracedClass.typeName }
                .findFirst()
                .orElse(obfuscated)
        } catch (e: RuntimeException) {
            // classFromTypeName rejects non-class-shaped tokens (e.g. the "Unknown" fallback) — for
            // those there is nothing to retrace, so the original token is the right answer.
            obfuscated
        }
}
