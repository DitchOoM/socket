@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.ByteOrder
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

internal actual val serverReceiveDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun writeNativeSizeT(
    buf: PlatformBuffer,
    value: Int,
) {
    val bb = (buf.unwrapFully() as BaseJvmBuffer).byteBuffer
    bb.order(ByteOrder.nativeOrder())
    bb.putLong(0, value.toLong())
}

internal actual fun readNativeSizeT(buf: PlatformBuffer): Int {
    val bb = (buf.unwrapFully() as BaseJvmBuffer).byteBuffer
    bb.order(ByteOrder.nativeOrder())
    return bb.getLong(0).toInt()
}

internal actual class PeerPathTable actual constructor() {
    // Concurrent: written on the receive loop (insert/evict) + the post-join close sweep, read by
    // driver egress coroutines. Matches the pre-refactor JvmQuicServer.peersByPathKey.
    private val map = ConcurrentHashMap<PathKey, SocketAddress>()

    actual fun put(
        key: PathKey,
        peer: SocketAddress,
    ) {
        map[key] = peer
    }

    actual fun remove(key: PathKey) {
        map.remove(key)
    }

    actual fun get(key: PathKey): SocketAddress? = map[key]
}
