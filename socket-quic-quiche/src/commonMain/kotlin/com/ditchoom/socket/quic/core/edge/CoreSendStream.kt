@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicAbortCodes
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicSendStream
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.StreamRoom
import com.ditchoom.socket.quic.StreamSend
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where a caller's sending on a public stream stands: a failure that ended it is what every later call answers. */
private sealed interface Verdict {
    data object Writing : Verdict

    class Failed(
        val why: StreamWrite.NotWritten,
    ) : Verdict
}

/**
 * A [SendHalf] as the public [QuicSendStream]: one mechanism, the stream's [StreamOutbox]. [trySend] and [tryFinish]
 * queue without suspending; [write], [writeAndFinish] and [finish] are them in a loop with the outbox's waits. Every
 * outcome of a write is a value, and a failure stays.
 */
@Published
internal class CoreSendStream(
    connection: CoreConnection,
    private val half: SendHalf,
    private val codes: QuicAbortCodes,
) : QuicSendStream {
    private val outbox = StreamOutbox(connection, half.id)
    private val verdict = AtomicReference<Verdict>(Verdict.Writing)

    override val id: QuicStreamId get() = half.id
    override val writePolicy: WritePolicy get() = half.writePolicy

    override fun trySend(bytes: ReadBuffer): StreamSend = outbox.trySend(bytes)

    override fun tryFinish(): StreamSend = outbox.tryFinish()

    override suspend fun write(
        buffer: ReadBuffer,
        policy: WritePolicy,
    ): StreamWrite {
        when (val now = verdict.load()) {
            is Verdict.Failed -> return now.why
            is Verdict.Writing -> Unit
        }
        val start = buffer.position()
        while (true) {
            when (val sent = outbox.trySend(buffer)) {
                is StreamSend.Sent -> return StreamWrite.Written
                is StreamSend.NotSent.Finished -> closed()
                is StreamSend.NotSent.PeerStopped, is StreamSend.NotSent.ConnectionEnded -> return failed(sent, start, buffer)
                is StreamSend.NotSent.WindowFull ->
                    when (val room = outbox.awaitRoom(policy)) {
                        is StreamRoom.Room -> Unit
                        is StreamRoom.DeadlinePassed -> return cut(buffer.position() - start)
                        is StreamRoom.Over -> return over(room.why, start, buffer)
                    }
            }
        }
    }

    override suspend fun writeAndFinish(
        buffer: ReadBuffer,
        policy: WritePolicy,
    ): StreamWrite {
        val written = write(buffer, policy)
        if (written !is StreamWrite.Written) return written
        return finished(policy)
    }

    override suspend fun finish(): StreamWrite {
        when (val now = verdict.load()) {
            is Verdict.Failed -> return now.why
            is Verdict.Writing -> Unit
        }
        return finished(writePolicy)
    }

    /** Asks for our FIN, if it was not asked for, and waits until quiche took it. */
    private suspend fun finished(policy: WritePolicy): StreamWrite {
        when (val asked = outbox.tryFinish()) {
            is StreamSend.Sent, is StreamSend.NotSent.Finished -> Unit
            is StreamSend.NotSent.PeerStopped, is StreamSend.NotSent.ConnectionEnded -> return notWritten(asked, 0)
            is StreamSend.NotSent.WindowFull -> Unit
        }
        return when (val taken = outbox.awaitFinTaken(policy)) {
            is StreamRoom.Room -> StreamWrite.Written
            is StreamRoom.DeadlinePassed -> cut(0)
            is StreamRoom.Over ->
                when (val why = taken.why) {
                    is StreamSend.NotSent.Finished -> StreamWrite.Written
                    is StreamSend.NotSent.PeerStopped, is StreamSend.NotSent.ConnectionEnded -> notWritten(why, 0)
                }
        }
    }

    override fun reset(code: QuicAppErrorCode) {
        outbox.resetHere()
        half.reset(code)
    }

    /** The block that owned this stream returned: a direction still open is finished. */
    suspend fun complete() {
        if (plainState() == PlainState.Open) finish()
    }

    /** Whether this direction is still open: not finished, reset or failed. */
    fun plainState(): PlainState = if (verdict.load() is Verdict.Writing && outbox.isOpen()) PlainState.Open else PlainState.Done

    /** Its block ended with this direction open: it is reset with [QuicAbortCodes.writeCut]. */
    fun cut() {
        if (plainState() == PlainState.Open) reset(codes.writeCut)
    }

    /** A write after our FIN or reset is a misuse, as it was before the outbox. */
    private fun closed(): Nothing = raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)

    /** The deadline passed with [taken] bytes queued: the direction is reset, so the peer reads no part as the whole. */
    private fun cut(taken: Int): StreamWrite.NotWritten {
        val why = StreamWrite.NotWritten.DeadlinePassed(taken.toLong())
        if (!verdict.compareAndSet(Verdict.Writing, Verdict.Failed(why))) return stays()
        reset(codes.writeCut)
        return why
    }

    private fun over(
        why: StreamSend.NotSent.Over,
        start: Int,
        buffer: ReadBuffer,
    ): StreamWrite =
        when (why) {
            is StreamSend.NotSent.Finished -> closed()
            is StreamSend.NotSent.PeerStopped, is StreamSend.NotSent.ConnectionEnded -> failed(why, start, buffer)
        }

    private fun failed(
        why: StreamSend.NotSent.Over,
        start: Int,
        buffer: ReadBuffer,
    ): StreamWrite.NotWritten = notWritten(why, buffer.position() - start)

    /** The first failure ends the direction and stays. */
    private fun notWritten(
        why: StreamSend.NotSent.Over,
        taken: Int,
    ): StreamWrite.NotWritten {
        val failure =
            when (why) {
                is StreamSend.NotSent.PeerStopped -> StreamWrite.NotWritten.PeerStopped(why.code)
                is StreamSend.NotSent.ConnectionEnded -> StreamWrite.NotWritten.ConnectionEnded(why.end)
                is StreamSend.NotSent.Finished -> closed()
            }
        return if (verdict.compareAndSet(Verdict.Writing, Verdict.Failed(failure))) failure else stays()
    }

    /** The failure that ended the direction first. */
    private fun stays(): StreamWrite.NotWritten =
        when (val first = verdict.load()) {
            is Verdict.Failed -> first.why
            is Verdict.Writing -> raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        }
}
