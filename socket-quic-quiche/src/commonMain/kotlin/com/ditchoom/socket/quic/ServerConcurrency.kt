@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CoroutineDispatcher

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

/**
 * The dispatcher the shared [SharedQuicheServer] runs its receive loop, packet reader, and
 * accepted-connection handlers on: `Dispatchers.IO` on the JVM (blocking-friendly for the NIO
 * datagram channel and user handler code), `Dispatchers.Default` on Kotlin/Native (which has no
 * dedicated IO dispatcher). One value, resolved per platform behind this seam so the server stays common.
 */
internal expect val serverReceiveDispatcher: CoroutineDispatcher

/**
 * Write a native `size_t` [value] into the first 8 bytes of [buf]'s backing native memory — the
 * length in/out params quiche's `quiche_header_info` reads. A direct `ByteBuffer` write on the JVM
 * (which avoids the restricted FFM APIs), a `size_t*` cinterop write on Kotlin/Native.
 */
internal expect fun writeNativeSizeT(
    buf: PlatformBuffer,
    value: Int,
)

/** Read a native `size_t` from the first 8 bytes of [buf]'s backing native memory (see [writeNativeSizeT]). */
internal expect fun readNativeSizeT(buf: PlatformBuffer): Int

/**
 * Per-source `PathKey → peer` map the server's egress channels consult to resolve `sendInfo.to` back
 * to a real send target without reconstructing an address from the opaque [PathKey] (RFC §4). Written
 * only on the receive loop (cache-miss insert, LRU-evict remove) plus the post-join close sweep — never
 * two writers at once — but read concurrently by driver egress coroutines. `ConcurrentHashMap` on the
 * JVM; copy-on-write over `AtomicReference<Map>` on Kotlin/Native (no `java.util.concurrent`), the same
 * split as [LiveDriverLedger].
 */
internal expect class PeerPathTable() {
    fun put(
        key: PathKey,
        peer: SocketAddress,
    )

    fun remove(key: PathKey)

    fun get(key: PathKey): SocketAddress?
}
