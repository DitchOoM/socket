package com.ditchoom.socket.quic

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

internal actual class RecvInfoRefCount actual constructor(
    initial: Int,
) {
    private val value = AtomicInt(initial)

    actual fun get(): Int = value.value

    actual fun incrementAndGet(): Int = value.incrementAndGet()

    actual fun decrementAndGet(): Int = value.decrementAndGet()
}

internal actual class LiveDriverLedger actual constructor() {
    // Copy-on-write over AtomicReference: add/remove/snapshot/clear are all cold paths (accept +
    // driver-cleanup + close), and Kotlin/Native has no java.util.concurrent.
    private val ref = AtomicReference<Set<QuicheDriver>>(emptySet())

    actual fun add(driver: QuicheDriver) {
        while (true) {
            val cur = ref.value
            if (ref.compareAndSet(cur, cur + driver)) return
        }
    }

    actual fun remove(driver: QuicheDriver) {
        while (true) {
            val cur = ref.value
            if (driver !in cur) return
            if (ref.compareAndSet(cur, cur - driver)) return
        }
    }

    // The backing set is an immutable copy-on-write snapshot, so toList() is already race-free here
    // (unlike the JVM ConcurrentHashMap keyset — see the JVM actual).
    actual fun snapshot(): List<QuicheDriver> = ref.value.toList()

    actual fun clear() {
        ref.value = emptySet()
    }
}
