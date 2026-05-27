package com.ditchoom.socket.quic

import kotlinx.coroutines.Job
import java.lang.ref.Cleaner

/**
 * Single library-wide [Cleaner] for QUIC engine GC-paced cleanup on the JVM.
 *
 * One [Cleaner] instance spawns one daemon thread; using a single cleaner
 * for both `JvmQuicServerEngine` and `CommonJvmQuicEngine` keeps that
 * footprint minimal regardless of how many engines exist.
 */
private val quicEngineCleaner: Cleaner = Cleaner.create()

internal actual fun registerEngineCleanup(
    engine: Any,
    job: Job,
) {
    // The lambda lives in a top-level function (no enclosing `this`) and
    // captures only the `job` parameter — never the `engine` — so the
    // Cleaner can phantom-reach the engine. Capturing `this` indirectly
    // would silently defeat the safety net.
    quicEngineCleaner.register(engine) { job.cancel() }
}
