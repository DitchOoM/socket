package com.ditchoom.socket.quic

import kotlinx.coroutines.Job

/**
 * Register [engine] with a GC-paced cleaner so that, if it becomes
 * phantom-reachable without `close()`, the cleaning action cancels [job].
 *
 * Belt-and-braces safety net for callers who bypass [withQuicEngine] /
 * [withQuicServerEngine] and forget to call `close()`. Implementations on
 * the JVM use `java.lang.ref.Cleaner` (one daemon thread, library-wide);
 * the Android implementation is a no-op until enough Android runtimes
 * expose `Cleaner` (it's only present from API 33+, and socket-quic's
 * `minSdk` is 24). See `socket-quic/DRIVER_REDESIGN.md` →
 * "Engine lifecycle" for the broader rationale.
 *
 * The action must not capture a strong reference to the engine itself —
 * that would defeat phantom-reachability. This helper takes the engine
 * and the job as explicit parameters, and the action constructed inside
 * each actual is in a scope with no `this`, so the lambda cannot accidentally
 * close over a back-reference to the engine.
 */
internal expect fun registerEngineCleanup(
    engine: Any,
    job: Job,
)
