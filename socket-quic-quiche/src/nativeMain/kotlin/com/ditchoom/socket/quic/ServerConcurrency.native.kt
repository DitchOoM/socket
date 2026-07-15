@file:OptIn(ExperimentalForeignApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

internal actual val serverReceiveDispatcher: CoroutineDispatcher = Dispatchers.Default

internal actual fun writeNativeSizeT(
    buf: PlatformBuffer,
    value: Int,
) {
    buf.nativeMemoryAccess!!
        .nativeAddress
        .toCPointer<ULongVar>()!!
        .pointed
        .value = value.toULong()
}

internal actual fun readNativeSizeT(buf: PlatformBuffer): Int =
    buf.nativeMemoryAccess!!
        .nativeAddress
        .toCPointer<ULongVar>()!!
        .pointed
        .value
        .toInt()

internal actual class PeerPathTable actual constructor() {
    // Copy-on-write over AtomicReference (K/N has no java.util.concurrent). Single-writer (receive loop
    // + post-join close sweep), so the CAS never really contends; readers see a consistent snapshot.
    private val ref = AtomicReference<Map<PathKey, SocketAddress>>(emptyMap())

    actual fun put(
        key: PathKey,
        peer: SocketAddress,
    ) {
        while (true) {
            val cur = ref.value
            if (ref.compareAndSet(cur, cur + (key to peer))) return
        }
    }

    actual fun remove(key: PathKey) {
        while (true) {
            val cur = ref.value
            if (key !in cur) return
            if (ref.compareAndSet(cur, cur - key)) return
        }
    }

    actual fun get(key: PathKey): SocketAddress? = ref.value[key]
}
