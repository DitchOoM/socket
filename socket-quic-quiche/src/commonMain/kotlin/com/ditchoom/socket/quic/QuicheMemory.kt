package com.ditchoom.socket.quic

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess

/**
 * The native memory one [QuicheCmd] lends to quiche — the address quiche reads or writes, **bound to
 * the object whose reachability keeps that memory mapped**.
 *
 * ## Why an address alone is not enough (issues #366 / #401 / #415)
 *
 * A driver command used to carry a bare `addr: Long`, and every command's KDoc asked the caller to
 * "keep that buffer alive until the result completes". On a managed runtime the caller *cannot*: a
 * Kotlin local stops being reachable at its last use, and native memory a buffer owns is released
 * when the **object** becomes unreachable, not when anyone says so:
 *
 * | JVM tier | `BufferFactory.Default` buffer | native memory released by |
 * |---|---|---|
 * | JDK 21+ (multi-release tier) | `FfmAutoBuffer` (`Arena.ofAuto()`) | the GC — `freeNativeMemory()` is a **no-op** |
 * | JDK 17 / Android | direct `ByteBuffer` | the GC, via the buffer's `Cleaner` |
 *
 * So the ordinary way to write on this API —
 *
 * ```kotlin
 * val out = BufferFactory.Default.allocate(payload.size)
 * …
 * stream.write(out, 5.seconds)   // `out` is dead from here on
 * ```
 *
 * — left nothing referring to `out` while `streamWrite` was suspended on its `StreamSend`. The GC
 * could free the memory, the allocator hand the chunk to somebody else, and only then would the
 * driver loop call `quiche_conn_stream_send(conn, id, addr, len)`. quiche faithfully copies whatever
 * is at `addr` into its send buffer, AEAD-seals it and puts it on the wire, so the peer receives —
 * and an echo peer returns — **bytes that were never sent**, with the correct length and freed-chunk
 * allocator metadata in them (`… aa 7f 00 00 …`, the high half of an x86-64 heap pointer). That is
 * the whole of the "echo decodes bytes that were never sent" family, and it is why the corruption
 * only ever appeared on JVM/Android lanes and never on Kotlin/Native, where buffers own their memory
 * explicitly.
 *
 * ## The rule this type enforces
 *
 * An address can no longer be enqueued without the object that keeps it mapped. The pairing is a
 * type, not a convention, because the convention was already written down — in five command KDocs —
 * and was unenforceable on the runtime that needed it.
 *
 * Two halves, and they do different jobs:
 *  - **[Borrowed] retains the owner.** That is the fix: a queued command is reachable, so the buffer
 *    it borrows is reachable, from enqueue until the driver completes it. Removing the retention
 *    reproduces the defect on the first run (`QuicNativeBufferLifetimeTests` mutation) and corrupts
 *    11 of 10 000 real loopback echoes under collector pressure, against 0 of 10 000 with it.
 *  - **[endBorrow], called after every quiche call that used [address]**, is what stops an optimizing
 *    JIT from unwinding that: a compiler may read `cmd.buf.address` into a register and treat the
 *    command as dead for the rest of the method, which would put the owner back on the collector's
 *    table *while quiche is inside the call*. Reachability of a field's holder is not a guarantee
 *    HotSpot makes; this is precisely what `Reference.reachabilityFence` exists for.
 */
sealed interface QuicheMemory {
    /** The address handed to quiche. `0` — a null pointer — when there is nothing to read or write. */
    val address: Long

    /**
     * Ends the loan: the owning buffer is guaranteed reachable up to this call, and no further.
     * Call it **after** the quiche call that used [address] has returned (a `finally` is the right
     * shape — an FFI call that throws is equally done with the memory).
     */
    fun endBorrow()

    /**
     * No memory: quiche is given a null pointer and a zero length. A zero-length datagram is legal
     * (RFC 9221) and a FIN-only `stream_send` carries no payload, so "no buffer" is a case to match
     * on, never an address of `0` that a reader has to recognise.
     */
    data object Empty : QuicheMemory {
        override val address: Long get() = 0L

        override fun endBorrow() = Unit
    }

    /**
     * [address] stays mapped for exactly as long as [owner] is reachable. [owner] is never
     * dereferenced — its *existence* is the point — so it is deliberately typed `Any`: what keeps a
     * region mapped differs per platform (an `FfmAutoBuffer` holding its arena segment, a direct
     * `ByteBuffer` holding its `Cleaner`, a Kotlin/Native buffer holding its `nativeHeap` allocation)
     * and this type must not care which.
     */
    class Borrowed(
        override val address: Long,
        private val owner: Any,
    ) : QuicheMemory {
        override fun endBorrow() = reachabilityFence(owner)
    }
}

/**
 * Keep [owner] reachable up to this call — the primitive [QuicheMemory.Borrowed.endBorrow] is built
 * from. A no-op *semantically*: it computes nothing and returns nothing, and exists only to stop a
 * runtime from concluding that an object nobody names again is already collectable.
 *
 * Kotlin/Native has no runtime that can reclaim a buffer's native memory behind its owner's back —
 * those buffers free explicitly — so the native actuals are empty; the JVM/Android one is not.
 */
internal expect fun reachabilityFence(owner: Any)

/**
 * The memory a buffer the **driver** allocated for itself lends to quiche — the UDP send buffer,
 * packet buffers from the receive pool, the stream-read pool, sockaddr / CID / token scratch.
 *
 * Mirrors [driverOwnedNativeAddress]'s invariant: every factory a driver is built from passed
 * `TransportConfig.quicBufferFactory()`'s `requireNativeMemory()` probe at setup, so a buffer with no
 * native memory here is a broken invariant, never a caller's mistake.
 */
internal fun PlatformBuffer.driverOwnedMemory(): QuicheMemory = QuicheMemory.Borrowed(driverOwnedNativeAddress(), this)

/**
 * The memory a **caller-fed** buffer lends to quiche, starting at the buffer's current position.
 *
 * Returns `null` when the buffer has no native memory, so the two public write paths
 * ([DriverStreamAdapter.streamWrite] and [DriverDatagramAdapter.send]) can raise the typed
 * [QuicNativeMemoryRequiredException] they already owe a caller (#502) instead of a bare NPE.
 */
internal fun ReadBuffer.callerFedMemory(): QuicheMemory? =
    nativeMemoryAccess?.let { QuicheMemory.Borrowed(it.nativeAddress + position(), this) }
