package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.Received
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamEntry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Runnable

/** The buffer a read that found nothing keeps for its retry. */
private sealed interface Spare {
    data object None : Spare

    class Held(
        val buffer: PlatformBuffer,
    ) : Spare
}

/**
 * Reads the next chunk into a buffer from the caller's factory, sized by the stream's read chunk; the caller owns a
 * returned buffer. First runs as work inside the connection, then waits parked on its stream until data, its deadline,
 * its caller's cancellation or the connection's end. Inside the connection only, except [run] (the deadline's thread).
 */
@Confined
internal class ReadOp(
    private val stream: QuicStreamId,
    private val deadline: OpDeadline,
    private val cont: CancellableContinuation<ReadResult>,
    private val conn: Conn,
    private val factory: BufferFactory,
) : ConnWork(),
    Waiter,
    Runnable {
    private var spare: Spare = Spare.None

    override fun run(inside: Inside) =
        guarded(::failed) {
            when (val state = inside.streams.state) {
                is Streams.Ended -> return connectionEnded(state.end)
                is Streams.Open -> Unit
            }
            if (!cont.isActive) return release()
            val entry = inside.streams.entry(stream)
            if (attempt(inside.streams, entry) == Retry.Again) {
                entry.parkReader(this)
                deadline.arm(inside, this)
            }
        }

    override fun retry(table: StreamTable): Retry {
        var answer = Retry.Settled
        guarded(::failed) {
            if (!cont.isActive) {
                deadline.disarm()
                return@guarded release()
            }
            answer = attempt(table, table.entry(stream))
            if (answer == Retry.Settled) deadline.disarm()
        }
        return answer
    }

    override fun connectionEnded(end: ConnEnd) {
        deadline.disarm()
        release()
        cont.fail(Raised.ConnEnded(end), QuicCloseCircumstance.Ended)
    }

    override fun gone(end: ConnEnd) = connectionEnded(end)

    /** The deadline's thread: the deadline passed; the connection expires the read from inside. */
    override fun run() = conn.submit(Expire(), CallerPlace.HandToLoop)

    /** The caller was cancelled while the read waited. */
    fun withdraw() = conn.submit(Withdraw(), CallerPlace.HandToLoop)

    private fun attempt(
        table: StreamTable,
        entry: StreamEntry,
    ): Retry {
        if (entry.received == Received.Finished) return deliver(ReadResult.End)
        val buffer =
            when (val kept = spare) {
                // A pooled factory may return a larger buffer than asked for; the read asks for the chunk, not the buffer.
                is Spare.None -> factory.allocate(entry.chunk.bytes).also { it.setLimit(entry.chunk.bytes) }
                is Spare.Held -> kept.buffer
            }
        spare = Spare.None
        val address =
            NativeBytes.addressing(buffer, { it }) {
                buffer.freeNativeMemory()
                cont.fail(Raised.NotNative(stream, buffer), QuicCloseCircumstance.Ended)
                return Retry.Settled
            }
        val asked = buffer.limit()
        return table.io.recv(stream, address, asked).fold(
            more = { read -> if (read == 0) hold(buffer) else data(entry, buffer, asked, read) },
            last = { read ->
                entry.finished()
                if (read == 0) discard(buffer, ReadResult.End) else data(entry, buffer, asked, read)
            },
            nothingYet = { hold(buffer) },
            gone = {
                buffer.freeNativeMemory()
                cont.fail(Raised.ConnEnded(ConnEnd.Released), QuicCloseCircumstance.Released)
                Retry.Settled
            },
            reset = { discard(buffer, ReadResult.Reset) },
            error = { code ->
                buffer.freeNativeMemory()
                cont.fail(Raised.ReadRefused(stream, code), QuicCloseCircumstance.Ended)
                Retry.Settled
            },
        )
    }

    private fun data(
        entry: StreamEntry,
        buffer: PlatformBuffer,
        asked: Int,
        read: Int,
    ): Retry {
        entry.read(asked, read)
        buffer.position(0)
        buffer.setLimit(read)
        return deliver(ReadResult.Data(buffer))
    }

    private fun hold(buffer: PlatformBuffer): Retry {
        spare = Spare.Held(buffer)
        return Retry.Again
    }

    private fun discard(
        buffer: PlatformBuffer,
        result: ReadResult,
    ): Retry {
        buffer.freeNativeMemory()
        return deliver(result)
    }

    /** Resumes the caller with [result]; a caller cancelled after the resume frees the buffer it never saw. */
    private fun deliver(result: ReadResult): Retry {
        cont.resume(result, FREE_UNSEEN)
        return Retry.Settled
    }

    private fun release() {
        when (val kept = spare) {
            is Spare.None -> Unit
            is Spare.Held -> kept.buffer.freeNativeMemory()
        }
        spare = Spare.None
    }

    private fun failed(failure: Exception) {
        deadline.disarm()
        release()
        cont.fail(Raised.Thrown(failure), QuicCloseCircumstance.Ended)
    }

    private fun unpark(table: StreamTable) = table.entry(stream).unpark(this)

    private inner class Expire : ConnWork() {
        override fun run(inside: Inside) {
            if (inside.streams.entry(stream).reader !== this@ReadOp) return
            unpark(inside.streams)
            release()
            cont.fail(OpFailure.DeadlinePassed(deadline.bound), QuicCloseCircumstance.Ended)
        }

        override fun gone(end: ConnEnd) = Unit
    }

    private inner class Withdraw : ConnWork() {
        override fun run(inside: Inside) {
            unpark(inside.streams)
            deadline.disarm()
            release()
        }

        override fun gone(end: ConnEnd) = Unit
    }
}

/** A result a cancelled caller never saw: its buffer is freed. Shared, so a delivery allocates no handler. */
private val FREE_UNSEEN: (Throwable, ReadResult, kotlin.coroutines.CoroutineContext) -> Unit = { _, unseen, _ ->
    if (unseen is ReadResult.Data) unseen.buffer.freeIfNeeded()
}
