@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/** What a caller may still do with a stream. */
private enum class Use {
    Open,

    /** Our FIN is sent; reads continue. */
    SendFinished,

    Closed,
}

/** One stream of a [CoreConnection], as the public [ByteStream] contract sees it. */
@Published
internal class CoreStream(
    private val connection: CoreConnection,
    private val id: QuicStreamId,
    override val readPolicy: ReadPolicy,
    override val writePolicy: WritePolicy,
) : ByteStream,
    HalfCloseable,
    Resettable {
    private val use = AtomicReference(Use.Open)

    override val isOpen: Boolean get() = use.load() != Use.Closed

    override suspend fun read(deadline: Duration): ReadResult {
        if (use.load() == Use.Closed) raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        return connection.read(id, deadline)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (use.load() != Use.Open) raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        val address =
            when (val at = NativeBytes.addressOf(buffer)) {
                is ReadAddress.At -> at.address
                is ReadAddress.NotAddressable -> raise(Raised.NotNative(id, buffer), QuicCloseCircumstance.Ended)
            }
        if (buffer.remaining() == 0) return BytesWritten(0)
        return connection.write(id, WriteSource.Bytes(buffer, address), deadline)
    }

    override suspend fun shutdownSend() {
        if (use.compareAndSet(Use.Open, Use.SendFinished)) finish()
    }

    override suspend fun close() {
        when (use.exchange(Use.Closed)) {
            Use.Open ->
                try {
                    finish()
                } finally {
                    connection.abandon(id)
                }
            Use.SendFinished -> connection.abandon(id)
            Use.Closed -> Unit
        }
    }

    /** Resets both directions with [errorCode]: RESET_STREAM for ours, STOP_SENDING for the peer's. */
    override suspend fun reset(errorCode: Long) {
        if (use.exchange(Use.Closed) != Use.Closed) connection.resetStream(id, QuicAppErrorCode(errorCode))
    }

    private suspend fun finish() {
        val deadline =
            when (val policy = writePolicy) {
                is WritePolicy.Bounded -> policy.deadline
                is WritePolicy.UntilClosed -> Duration.INFINITE
            }
        connection.write(id, WriteSource.FinOnly, deadline)
    }
}
