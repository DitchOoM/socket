package com.ditchoom.socket.quic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal actual class RecvInfoRefCount actual constructor(
    initial: Int,
) {
    private val value = AtomicInteger(initial)

    actual fun get(): Int = value.get()

    actual fun incrementAndGet(): Int = value.incrementAndGet()

    actual fun decrementAndGet(): Int = value.decrementAndGet()
}

internal actual class LiveDriverLedger actual constructor() {
    // Concurrent (newKeySet): added on the receive-loop thread, removed on a driver-cleanup coroutine.
    private val set = ConcurrentHashMap.newKeySet<QuicheDriver>()

    actual fun add(driver: QuicheDriver) {
        set.add(driver)
    }

    actual fun remove(driver: QuicheDriver) {
        set.remove(driver)
    }

    /**
     * Snapshot via `ArrayList(set)` — which copies through the weakly-consistent `toArray()`, NOT a
     * `size()`-based fast path — because Kotlin's `toList()` special-cases size==1 as
     * `listOf(iterator().next())` and would throw `NoSuchElementException` if that last element is
     * removed (by a driver finishing on its own and calling [remove] from `onCleanup`) between the
     * size read and `next()`. That was the `9016694` follow-up race in the #179 UAF fix.
     */
    actual fun snapshot(): List<QuicheDriver> = ArrayList(set)

    actual fun clear() {
        set.clear()
    }
}
