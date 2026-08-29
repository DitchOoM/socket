package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolStats
import com.ditchoom.socket.quic.QuicStreamException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Test doubles for a peer-initiated stream that misbehaves after its first bytes, shared by the client
// (Http3ConnectionTests) and server (Http3ServerConnectionTests) suites: the router hands such a stream
// on by its prefix, and what the handler then does with it — resets it, closes it, leaks what it was
// buffering — is the behaviour under test (#477, #496); or that goes quiet for longer than a read deadline and
// then speaks again, which is ordinary for a QPACK stream and must not kill its reader (#472, #495).

/** The peer's view of a stream this endpoint was handed: what, if anything, the endpoint did to it. */
internal sealed interface Disposition {
    /** Still open at both ends. */
    data object Open : Disposition

    /** [ByteStream.close] — on QUIC a send-side FIN only; the peer's half stays open. */
    data object Closed : Disposition

    /** [Resettable.reset] — RESET_STREAM + STOP_SENDING carrying [errorCode]; the peer learns it was abandoned. */
    data class Reset(
        val errorCode: Long,
    ) : Disposition
}

/** What a [StalledStream]'s reads do once its prefix has been delivered. */
internal sealed interface ThenPeer {
    /** Says nothing: every read parks until its deadline and expires with `TimeoutCancellationException`. */
    data object StaysSilent : ThenPeer

    /** Aborts the stream: the read throws [failure], as the QUIC leaf does when the peer sends RESET_STREAM. */
    data class Aborts(
        val failure: QuicStreamException,
    ) : ThenPeer
}

/**
 * Delivers [prefix] on the first read (when non-empty), then does what [then] says — by default stalls:
 * every later read parks until its deadline and expires with `TimeoutCancellationException`, exactly as
 * `QuicheDriver`'s read leaf does (`withTimeout(timeout)`). A scripted double that ignored the deadline
 * could not reproduce a deadline defect at all. Under `runTest` the stall is virtual time. The first of
 * [close]/[reset] wins — it is the one the peer sees.
 *
 * The prefix travels as one chunk allocated from [chunks] **up front**, so a test can hand the stream a
 * [BufferPool]'s buffer and count it out of that pool before the endpoint under test even exists: once
 * the endpoint is done with the stream, [outstanding] says whether the chunk came back (#496).
 */
internal class StalledStream(
    prefix: List<Int>,
    chunks: BufferFactory = BufferFactory.Default,
    private val then: ThenPeer = ThenPeer.StaysSilent,
) : ByteStream,
    Resettable {
    private val script = ArrayDeque<ReadResult>()
    var disposition: Disposition = Disposition.Open
        private set

    init {
        if (prefix.isNotEmpty()) {
            val buf = chunks.allocate(prefix.size)
            for (b in prefix) buf.writeByte(b.toByte())
            buf.resetForRead()
            script.addLast(ReadResult.Data(buf))
        }
    }

    override val isOpen: Boolean get() = disposition is Disposition.Open
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult {
        script.removeFirstOrNull()?.let { return it }
        return when (val then = then) {
            ThenPeer.StaysSilent -> withTimeout(deadline) { awaitCancellation() }
            is ThenPeer.Aborts -> throw then.failure
        }
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val n = buffer.remaining()
        repeat(n) { buffer.readByte() }
        return BytesWritten(n)
    }

    override suspend fun close() {
        if (disposition is Disposition.Open) disposition = Disposition.Closed
    }

    override suspend fun reset(errorCode: Long) {
        if (disposition is Disposition.Open) disposition = Disposition.Reset(errorCode)
    }
}

/**
 * Buffers this pool has handed out and not yet been given back — the pool's own accounting, read from
 * outside whatever borrowed them. Every pool miss is one distinct buffer, and at any moment each of them
 * is either sitting in the pool ([PoolStats.currentPoolSize]) or out with a borrower, so `misses − pooled`
 * is the outstanding count. Holds while nothing has been dropped for a full pool (the default
 * `maxPoolSize` is 64; these tests hand out one or two).
 */
internal fun BufferPool.outstanding(): Int = stats().let { (it.poolMisses - it.currentPoolSize).toInt() }

/**
 * Delivers [first], then stays silent for [silence] before delivering [afterSilence], then ends.
 *
 * Unlike a scripted double this **honours the deadline**, the way `QuicheDriver`'s read leaf does
 * (`withTimeout(timeout)`) — a double that ignored it could not reproduce a deadline defect at all.
 * Under `runTest` the silence is virtual time, so the test is deterministic and instant.
 *
 * [readToEnd] is the reader's survival witness: it turns true only when a read returns
 * [ReadResult.End], which lies on the far side of the silence — a reader whose deadline expired
 * inside the silence never gets there.
 */
internal class SilentThenSpeakingStream(
    private val first: List<Int>,
    private val silence: Duration,
    private val afterSilence: List<Int>,
) : ByteStream {
    private var stage = 0
    var closed = false
        private set

    /** True once end-of-stream was delivered — i.e. the reader was still reading after [silence]. */
    var readToEnd = false
        private set

    override val isOpen: Boolean get() = !closed
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult =
        withTimeout(deadline) {
            when (stage++) {
                0 -> chunk(first)
                1 -> {
                    delay(silence)
                    chunk(afterSilence)
                }
                else -> {
                    readToEnd = true
                    ReadResult.End
                }
            }
        }

    private fun chunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val n = buffer.remaining()
        repeat(n) { buffer.readByte() }
        return BytesWritten(n)
    }

    override suspend fun close() {
        closed = true
    }
}
