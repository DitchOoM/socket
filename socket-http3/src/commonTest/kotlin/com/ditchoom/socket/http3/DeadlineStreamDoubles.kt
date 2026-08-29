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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * Stream doubles that HONOUR the read deadline, the way `QuicheDriver`'s read leaf does
 * (`withTimeout(timeout)`). A double that ignored the deadline could not reproduce a deadline defect at
 * all. Shared by the client ([Http3ConnectionTests], #472 / #477) and the server
 * ([Http3ServerConnectionTests], #495): both roles face the same peer behaviour and the same
 * `TimeoutCancellationException`-is-a-`CancellationException` trap. Under `runTest` every silence here is
 * virtual time, so the tests are deterministic and instant.
 */

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

/**
 * Delivers [prefix] on the first read (when non-empty), then stalls: every later read parks until its
 * deadline and expires with `TimeoutCancellationException`, exactly as `QuicheDriver`'s read leaf does
 * (`withTimeout(timeout)`). A scripted double that ignores the deadline could not stall at all;
 * [SilentThenSpeakingStream] eventually speaks. Under `runTest` the stall is virtual time. The first
 * of [close]/[reset] wins — it is the one the peer sees.
 */
internal class StalledStream(
    private val prefix: List<Int>,
) : ByteStream,
    Resettable {
    private var prefixDelivered = prefix.isEmpty()
    var disposition: Disposition = Disposition.Open
        private set

    override val isOpen: Boolean get() = disposition is Disposition.Open
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult {
        if (!prefixDelivered) {
            prefixDelivered = true
            val buf = BufferFactory.Default.allocate(prefix.size)
            for (b in prefix) buf.writeByte(b.toByte())
            buf.resetForRead()
            return ReadResult.Data(buf)
        }
        return withTimeout(deadline) { awaitCancellation() }
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
