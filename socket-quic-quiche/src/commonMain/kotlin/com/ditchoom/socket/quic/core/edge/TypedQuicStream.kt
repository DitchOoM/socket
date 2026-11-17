@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.observe.CallerSide
import com.ditchoom.socket.quic.core.stream.WriteEnd
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/** What a caller may still do with a typed stream. */
private sealed interface TypedUse {
    data object Open : TypedUse

    /** Our FIN is sent; reads continue. */
    data object SendFinished : TypedUse

    data object Closed : TypedUse
}

/** What looking in the accumulator found. */
private enum class Step { Message, Fill, End, Reset }

/**
 * One stream of a [CoreConnection] carrying values of [T] framed by [codec]: [send] encodes a value on the caller's
 * thread into the stream's own native buffer and hands that to quiche in place, [receive] finds the next whole frame in
 * the stream's own accumulator, with the codec's frame detection, and decodes it on the caller's thread. Codecs run
 * only there, never on the loop's thread and never inside the connection. Nothing blocks: a call suspends, and does
 * not even do that when the frame is already buffered or quiche takes the frame at once.
 *
 * One [send] and one [receive] may run at a time; a second of the same kind fails with [TypedFailure.Busy].
 *
 * A frame the codec cannot size within the bound, or rejects, resets the stream with the configured application error
 * code and fails this and every later [receive] with that [TypedFailure]. The reset is because bytes cannot be found
 * again once a frame boundary is lost, the peer that sent the frame may keep sending, and a reset tells it at once
 * instead of leaving its writes to fill our flow window; the stream is the unit that broke, so the connection and its
 * other streams carry on. A peer that finishes inside a frame, or a codec that cannot detect frames, fails the reads
 * without a reset: nothing the peer sent is wrong to answer, and our side may still send.
 */
@CallerSide
internal class TypedQuicStream<T : Any>(
    private val connection: CoreConnection,
    private val conn: Conn,
    val id: QuicStreamId,
    private val codec: Codec<T>,
    private val config: TypedStreamConfig,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    factory: BufferFactory,
) {
    private val use = AtomicReference<TypedUse>(TypedUse.Open)
    private val bound = config.maxFrameBytes
    private val accumulator = FrameAccumulator(factory, bound)
    private val scratch = FrameScratch(factory, bound)
    private val reader = StreamTransfer(id, deadlineOf(readPolicy), connection.deadlines, conn, Inbound(accumulator))
    private val outbound = Outbound(scratch, config.malformedFrameCode)
    private val writer = StreamTransfer(id, deadlineOf(writePolicy), connection.deadlines, conn, outbound)

    /** The one message a step decoded and nobody has taken; a list made once, so keeping and taking allocate nothing. */
    private val decoded = ArrayList<T>(1)

    val isOpen: Boolean get() = use.load() !is TypedUse.Closed

    /** Encodes [value], sends its frame, and returns once quiche has taken every byte of it. */
    suspend fun send(value: T) {
        if (use.load() !is TypedUse.Open) raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        return when (writer.acquire()) {
            Acquired.Granted -> sendHeld(value)
            Acquired.Settling -> settleThenSend(value)
        }
    }

    private suspend fun settleThenSend(value: T) {
        writer.awaitSettled()
        return send(value)
    }

    private suspend fun sendHeld(value: T) {
        encode(value)
        outbound.begin(WriteEnd.More)
        writer.arm(connection.placeOf(coroutineContext))
        return if (writer.isDone()) completeSend() else awaitSend()
    }

    private suspend fun awaitSend() {
        writer.awaitDone()
        return completeSend()
    }

    private fun completeSend() {
        writer.taken()
        writer.leave()
    }

    private fun encode(value: T) {
        val done =
            userCode(
                failed = { rejected ->
                    writer.leave()
                    raise(Raised.Typed(id, TypedFailure.EncodeRejected(rejected)), QuicCloseCircumstance.Ended)
                },
            ) { scratch.encode(codec, value, config.encode) }
        if (done == Encoded.TooLarge) {
            writer.leave()
            raise(Raised.Typed(id, TypedFailure.OversizedValue(bound)), QuicCloseCircumstance.Ended)
        }
    }

    /**
     * The next value: [ScopedRead.Data] with it, [ScopedRead.End] once the peer finished the stream, [ScopedRead.Reset]
     * when it reset the stream. A malformed frame throws a [QuicTypedStreamException].
     */
    suspend fun receive(): ScopedRead<T> {
        if (use.load() is TypedUse.Closed) raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        return when (reader.acquire()) {
            Acquired.Granted -> receiveHeld()
            Acquired.Settling -> settleThenReceive()
        }
    }

    private suspend fun settleThenReceive(): ScopedRead<T> {
        reader.awaitSettled()
        return receive()
    }

    private suspend fun receiveHeld(): ScopedRead<T> {
        val place = connection.placeOf(coroutineContext)
        while (true) {
            when (step()) {
                Step.Message -> return message()
                Step.End -> return finished(ScopedRead.End)
                Step.Reset -> return finished(ScopedRead.Reset)
                Step.Fill -> Unit
            }
            reader.arm(place)
            if (!reader.isDone()) return awaitReceive()
            reader.taken()
        }
    }

    private suspend fun awaitReceive(): ScopedRead<T> {
        reader.awaitDone()
        reader.taken()
        return receiveHeld()
    }

    private fun message(): ScopedRead<T> {
        val value = decoded.removeAt(0)
        reader.leave()
        return ScopedRead.Data(value)
    }

    private fun finished(end: ScopedRead<Nothing>): ScopedRead<Nothing> {
        reader.leave()
        return end
    }

    /** Looks in the accumulator, on the caller's thread: decodes a whole frame, or says what is needed. */
    private fun step(): Step {
        val ending = accumulator.ending()
        if (ending is Ending.Broken) return broke(ending.failure, ResetStream.No)
        var wanted = 0
        if (accumulator.available() > 0) {
            val peek =
                userCode(failed = { rejected -> return broke(TypedFailure.Rejected(rejected), ResetStream.Yes) }) {
                    codec.peekFrameSize(accumulator)
                }
            when (peek) {
                is PeekResult.Complete -> {
                    val size = peek.bytes
                    if (size !in 1..bound) return broke(TypedFailure.Oversized(size, bound), ResetStream.Yes)
                    if (accumulator.available() >= size) return decode(size)
                    wanted = size
                }
                is PeekResult.NeedsMoreData ->
                    if (accumulator.available() >= bound) return broke(TypedFailure.Unsized(bound), ResetStream.Yes)
                is PeekResult.NoFraming -> return broke(TypedFailure.Unframed, ResetStream.No)
            }
        }
        return when (ending) {
            is Ending.Open -> {
                accumulator.makeRoom(wanted)
                Step.Fill
            }
            is Ending.Finished ->
                if (accumulator.available() > 0) broke(TypedFailure.Truncated(accumulator.available()), ResetStream.No) else Step.End
            is Ending.Reset -> Step.Reset
            is Ending.Broken -> broke(ending.failure, ResetStream.No)
        }
    }

    private fun decode(size: Int): Step {
        val value =
            userCode(failed = { rejected -> return broke(TypedFailure.Rejected(rejected), ResetStream.Yes) }) {
                accumulator.decode(codec, config.decode, size)
            }
        decoded.add(value)
        return Step.Message
    }

    /** The stream's reads fail with [failure] from now on; [reset] says whether the peer is told with a reset. Throws. */
    private fun broke(
        failure: TypedFailure,
        reset: ResetStream,
    ): Nothing {
        accumulator.broken(failure)
        when (reset) {
            ResetStream.Yes -> {
                // Our side is reset too: no FIN follows it, and a send fails as it does on a closed stream.
                use.compareAndSet(TypedUse.Open, TypedUse.SendFinished)
                conn.submit(ResetWork(id, config.malformedFrameCode), CallerPlace.HandToLoop)
            }
            ResetStream.No -> Unit
        }
        reader.leave()
        raise(Raised.Typed(id, failure), QuicCloseCircumstance.Ended)
    }

    /** Ends our side of the stream; reads continue. */
    suspend fun finish() {
        if (use.compareAndSet(TypedUse.Open, TypedUse.SendFinished)) sendFin()
    }

    /** Ends both sides: our FIN when it has not gone, and the stream's buffers are freed once nothing can touch them. */
    suspend fun close() {
        val before = use.exchange(TypedUse.Closed)
        try {
            if (before is TypedUse.Open) sendFin()
        } finally {
            conn.submit(reader.closing(), CallerPlace.HandToLoop)
            conn.submit(writer.closing(), CallerPlace.HandToLoop)
            connection.abandon(id)
        }
    }

    private suspend fun sendFin() {
        while (writer.acquire() == Acquired.Settling) writer.awaitSettled()
        scratch.empty()
        outbound.begin(WriteEnd.Finish)
        writer.arm(connection.placeOf(coroutineContext))
        if (!writer.isDone()) writer.awaitDone()
        completeSend()
    }
}

private enum class ResetStream { Yes, No }

private fun deadlineOf(policy: ReadPolicy): Duration =
    when (policy) {
        is ReadPolicy.Bounded -> policy.deadline
        is ReadPolicy.UntilClosed -> Duration.INFINITE
    }

private fun deadlineOf(policy: WritePolicy): Duration =
    when (policy) {
        is WritePolicy.Bounded -> policy.deadline
        is WritePolicy.UntilClosed -> Duration.INFINITE
    }
