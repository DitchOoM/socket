package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlin.time.Duration

/**
 * What a scoped [read] saw, with the buffer already released.
 *
 * The three arms mirror [ReadResult] one-for-one — [Data], [End], [Reset] — so a caller keeps the
 * same exhaustive `when` it had before. The only difference is what [Data] carries: the value the
 * caller's block computed *from* the bytes, instead of the buffer holding them. That is the whole
 * point: the terminal verdicts stay distinguishable without a buffer ever escaping the call.
 *
 * A nullable return would have been the cheaper shape and is deliberately not used: `null` would
 * have had to mean "end or reset, we are not saying which", which is a meaning a nullable must never
 * carry here.
 */
sealed interface ScopedRead<out R> {
    /** Bytes arrived; [value] is what the block returned. The buffer is already released. */
    data class Data<out R>(
        val value: R,
    ) : ScopedRead<R>

    /** Clean end-of-stream — the peer sent FIN. The block did not run. */
    data object End : ScopedRead<Nothing>

    /** The peer aborted this stream (RESET_STREAM). The block did not run. */
    data object Reset : ScopedRead<Nothing>
}

/**
 * Read the next chunk and hand it to [block], releasing the buffer on **every** exit path — normal
 * return, thrown exception, and cancellation.
 *
 * ```kotlin
 * // Echo, zero-copy and leak-free: write takes no ownership, the scope frees on the way out.
 * stream.read(5.seconds) { bytes -> stream.write(bytes, 5.seconds) }
 *
 * // Consume and keep only the decoded value; the bytes never outlive the block.
 * when (val r = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }) {
 *     is ScopedRead.Data -> println(r.value)
 *     ScopedRead.End -> println("peer finished")
 *     ScopedRead.Reset -> println("peer aborted")
 * }
 * ```
 *
 * ## Why this exists
 *
 * The transferring [ByteStream.read] hands back a buffer the caller must remember to release, and
 * "remember to" is not a contract a managed runtime enforces. Its KDoc used to say that forgetting
 * was harmless under the default heap [BufferFactory.Default], because the collector would reclaim it.
 * There is no factory under which that is true.
 *
 * The factory QUIC actually reads into is `BufferFactory.network()` = `deterministic()` — an
 * `Arena.ofShared()` on JDK 21+ — whose memory is released by an explicit `freeNativeMemory()` and by
 * nothing else, so a dropped buffer stays mapped for the life of the process with no collector even
 * involved. And where the collector *is* the owner ([BufferFactory.Default]: an `FfmAutoBuffer` over
 * `Arena.ofAuto()` on JDK 21+, a `Cleaner`-backed direct `ByteBuffer` on JDK 17 / Android) it schedules
 * itself on *managed-heap* pressure, which a pointer-sized wrapper in front of a 64 KB native
 * allocation does not produce. On top of either, the QUIC read path draws from a per-connection pool,
 * where a buffer that is never released is a slot that never returns — so every later read misses the
 * pool and allocates fresh. A device walk that took the old KDoc at its word reached 20.8 GB of address
 * space in 2 h 36 m and died of `std::bad_alloc` (#538).
 *
 * So the release is not the caller's to remember here. [block] gets the bytes for exactly as long as
 * it runs, and the buffer is released the moment it stops running.
 *
 * ## What it does not defend against
 *
 * [block] can return the buffer, or stash it in something that outlives the call, and then the
 * release under it is a use-after-free rather than a leak. Nothing in the type system stops that;
 * do not do it. A caller that genuinely needs the bytes to outlive the read either copies them out
 * inside [block] (`readString`, `readByteArray`, a decode) or uses the transferring
 * [ByteStream.read] and owns the release.
 *
 * @param deadline how long to wait for the chunk, exactly as for the transferring [ByteStream.read].
 * @return [ScopedRead.Data] carrying [block]'s result, or [ScopedRead.End] / [ScopedRead.Reset] with
 *   [block] never invoked.
 */
suspend fun <R> ByteStream.read(
    deadline: Duration,
    block: suspend (ReadBuffer) -> R,
): ScopedRead<R> =
    when (val result = read(deadline)) {
        is ReadResult.Data -> {
            val buffer = result.buffer
            try {
                ScopedRead.Data(block(buffer))
            } finally {
                // Not a suspending call, so it runs even once this coroutine is cancelled — which is
                // the path a deadline inside `block` takes, and the one an unscoped caller misses.
                buffer.freeIfNeeded()
            }
        }
        // The implementation already released whatever it had on these arms; there is no buffer here
        // by construction, which is why `block` cannot be given one.
        ReadResult.End -> ScopedRead.End
        ReadResult.Reset -> ScopedRead.Reset
    }

/**
 * [read] with the stream's own [ByteStream.readPolicy] deciding the deadline — the scoped counterpart of
 * the no-argument [ByteStream.read], and identical to it in every way except that the buffer cannot
 * escape and cannot be forgotten.
 */
suspend fun <R> ByteStream.read(block: suspend (ReadBuffer) -> R): ScopedRead<R> = read(readPolicy.toDeadline(), block)
