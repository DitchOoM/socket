package com.ditchoom.socket.quic

import java.lang.ref.Cleaner

/**
 * Single library-wide [Cleaner] for QUIC engine GC-paced cleanup.
 *
 * One [Cleaner] instance spawns one daemon thread; using a single cleaner
 * for both `JvmQuicServerEngine` and `CommonJvmQuicEngine` keeps that
 * footprint minimal regardless of how many engines exist.
 *
 * Purpose: belt-and-braces safety net for callers who bypass
 * [withQuicEngine] / [withQuicServerEngine] and forget to call `close()`.
 * If a forgotten engine becomes phantom-reachable, the registered action
 * runs and tears down the engine's coroutine scope. This does NOT replace
 * explicit close — cleaner ticks are GC-paced and unbounded in latency —
 * but it prevents unbounded growth under JVM memory pressure (see
 * `socket-quic/DRIVER_REDESIGN.md` → "Engine lifecycle").
 *
 * The cleaning action must not capture a strong reference to the engine
 * itself (that would defeat phantom-reachability). Callers register the
 * engine's [kotlinx.coroutines.Job] — a small leaf object that's safe to
 * hold separately.
 */
internal val quicEngineCleaner: Cleaner = Cleaner.create()

/**
 * Register [engine] with the library-wide cleaner so that, if it becomes
 * phantom-reachable without `close()`, the GC-paced cleaning action cancels
 * [job].
 *
 * Lives at top level on purpose: defines the cleaning action in a scope that
 * has no `this`, so the lambda physically cannot capture a reference to the
 * engine — which would defeat phantom-reachability and silently disable the
 * cleaner. The engine passes its own `SupervisorJob` (a leaf object) for the
 * action to cancel.
 */
internal fun registerEngineCleanup(
    engine: Any,
    job: kotlinx.coroutines.Job,
) {
    quicEngineCleaner.register(engine) { job.cancel() }
}
