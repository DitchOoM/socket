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
 * The identity of one server-side path: which client address a datagram came **from**, and which of
 * this host's local addresses it arrived **at**.
 *
 * The key of the shared server's per-source `recv_info` cache. Keying that cache on the peer alone was
 * #556's other half: a client addressing two of a multi-homed host's addresses is two distinct paths to
 * quiche, and a single cache entry would give the second path the first one's `recv_info.to` — so
 * quiche would echo the wrong `send_info.from` and the reply would leave from an address that client
 * never addressed.
 *
 * Both halves are always known: a channel that cannot report an arrival address falls back to the
 * server's bound address, so there is no absent state to model here.
 */
internal data class ServerPathPair(
    val peer: SocketAddress,
    val local: SocketAddress,
)

/**
 * A `PathKey → SocketAddress` map the server's egress channels consult to turn one of quiche's opaque
 * [PathKey]s back into a real address, without reconstructing it (RFC §4). The server keeps two:
 *
 *  - **peers** — `sendInfo.to` → the client address to send to, so replies follow a migrated peer; and
 *  - **locals** — `sendInfo.from` → the local address to send *from*, so a wildcard-bound server pins
 *    its own reply source instead of letting the kernel choose (#556).
 *
 * Both are written only on the receive loop (cache-miss insert, LRU-evict remove) plus the post-join
 * close sweep — never two writers at once — but read concurrently by driver egress coroutines.
 * `ConcurrentHashMap` on the JVM; copy-on-write over `AtomicReference<Map>` on Kotlin/Native (no
 * `java.util.concurrent`), the same split as [LiveDriverLedger].
 */
internal expect class PathAddressTable() {
    fun put(
        key: PathKey,
        peer: SocketAddress,
    )

    fun remove(key: PathKey)

    fun get(key: PathKey): SocketAddress?
}
