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
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/** What a caller may still do with a stream. */
private enum class Use {
    Open,

    /** A write that ends our side is out: whether it ended it is known once it answers. */
    Finishing,

    /** Our FIN is sent, or our side was reset; reads continue. */
    SendFinished,

    Closed,
}

/** One stream of a [CoreConnection], as the public [ByteStream] contract sees it. */
@Published
internal class CoreStream(
    private val connection: CoreConnection,
    val id: QuicStreamId,
    override val readPolicy: ReadPolicy,
    override val writePolicy: WritePolicy,
) : ByteStream,
    HalfCloseable,
    Resettable {
    private val use = AtomicReference(Use.Open)
    private val streamReads = StreamReads()

    override val isOpen: Boolean get() = use.load() != Use.Closed

    override suspend fun read(deadline: Duration): ReadResult {
        if (use.load() == Use.Closed) raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        return connection.read(id, deadline, streamReads)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (use.load() != Use.Open) raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        val address = addressOf(buffer)
        if (buffer.remaining() == 0) return BytesWritten(0)
        return connection.submitted(id, WriteSource.Bytes(buffer, address, WriteEnd.More), deadline).answer()
    }

    /**
     * Writes every byte of [buffer] and ends our side with the last of them, in one send when quiche takes them all:
     * a request that fits a datagram is one datagram. Reads continue; a write after it fails as after [shutdownSend].
     * A failure after quiche took part of the bytes resets our side and answers [Finished.Cut]; one before quiche took
     * any byte raises, and leaves our side open, so a later [close] sends the FIN.
     */
    suspend fun writeAndFinish(
        buffer: ReadBuffer,
        deadline: Duration,
    ): Finished {
        val address = addressOf(buffer)
        if (!use.compareAndSet(Use.Open, Use.Finishing)) raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        return finishing(
            if (buffer.remaining() ==
                0
            ) {
                WriteSource.FinOnly
            } else {
                WriteSource.Bytes(buffer, address, WriteEnd.Finish)
            },
            deadline,
        )
    }

    /** Sends [source], ending our side, while [use] is [Use.Finishing]; our side then stands where quiche left it. */
    private suspend fun finishing(
        source: WriteSource,
        deadline: Duration,
    ): Finished {
        val op = connection.submitted(id, source, deadline)
        try {
            return op.finishAnswer()
        } finally {
            settled(op.fin())
        }
    }

    /** Our side after a finishing write answered; a [close] that came meanwhile left the FIN to it, which goes now. */
    private fun settled(fin: Fin) {
        val after =
            when (fin) {
                Fin.Taken, Fin.Cut -> Use.SendFinished
                Fin.Open -> Use.Open
            }
        if (use.compareAndSet(Use.Finishing, after)) return
        when (fin) {
            Fin.Open -> connection.finishAlone(id)
            Fin.Taken, Fin.Cut -> Unit
        }
    }

    private fun addressOf(buffer: ReadBuffer): Long =
        when (val at = NativeBytes.addressOf(buffer)) {
            is ReadAddress.At -> at.address
            is ReadAddress.NotAddressable -> raise(Raised.NotNative(id, buffer), QuicCloseCircumstance.Ended)
        }

    override suspend fun shutdownSend() {
        if (use.compareAndSet(Use.Open, Use.Finishing)) finishing(WriteSource.FinOnly, deadlineOf(writePolicy))
    }

    override suspend fun close() {
        when (use.exchange(Use.Closed)) {
            Use.Open ->
                try {
                    finish()
                } finally {
                    connection.abandon(id)
                }
            Use.Finishing, Use.SendFinished -> connection.abandon(id)
            Use.Closed -> Unit
        }
    }

    /** Resets both directions with [errorCode]: RESET_STREAM for ours, STOP_SENDING for the peer's. */
    override suspend fun reset(errorCode: Long) {
        if (use.exchange(Use.Closed) != Use.Closed) connection.resetStream(id, QuicAppErrorCode(errorCode))
    }

    private suspend fun finish() {
        connection.submitted(id, WriteSource.FinOnly, deadlineOf(writePolicy)).answer()
    }
}
