package com.ditchoom.socket.quic

/**
 * A cross-thread reference counter for a cached recv_info's outstanding in-flight references.
 * Incremented on the receive-loop coroutine, decremented on driver command-execution coroutines
 * (or the receive loop on a trySend failure), read by the close sweep. Backed by
 * `java.util.concurrent.atomic.AtomicInteger` on the JVM and `kotlin.concurrent.AtomicInt` on
 * Kotlin/Native — the one concurrency primitive that genuinely differs, hidden behind this seam.
 */
internal expect class RecvInfoRefCount(
    initial: Int,
) {
    fun get(): Int

    fun incrementAndGet(): Int

    fun decrementAndGet(): Int
}

/**
 * Authoritative, thread-safe ledger of live [QuicheDriver]s. Added on the receive-loop coroutine
 * (before the driver starts), removed on a driver-cleanup coroutine (after the driver's run loop
 * returns), and [snapshot]ted by the server's close sweep to destroy every driver before the
 * recv_info cache is freed.
 *
 * Backed by `ConcurrentHashMap.newKeySet()` on the JVM and copy-on-write over
 * `kotlin.concurrent.AtomicReference<Set>` on Kotlin/Native (the latter has no `java.util.concurrent`).
 * [snapshot] MUST be race-safe against a concurrent [remove] of the last element — see the JVM actual
 * for why Kotlin's `toList()` size-1 fast-path is not.
 */
internal expect class LiveDriverLedger() {
    fun add(driver: QuicheDriver)

    fun remove(driver: QuicheDriver)

    fun snapshot(): List<QuicheDriver>

    fun clear()
}
