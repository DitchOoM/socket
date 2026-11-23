@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamSide
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReadAddress
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/** Where a caller's sending on a stream stands. */
private enum class Sending {
    Open,

    /** A write that ends this half is out: whether it ended it is known once it answers. */
    Finishing,

    /** A finish came while a finishing write was out: our FIN goes once that write answers, if it left the half open. */
    FinishAsked,

    Finished,
    Reset,
}

/** Where a caller's receiving on a stream stands. */
private enum class Receiving { Open, Closed }

/** What [ReceiveHalf.stop] did: told the peer to stop now, or found the half closed already. */
internal enum class Stopping { Now, Already }

/**
 * The sending half of a stream of a [CoreConnection]: what a send-only stream is, and half of a bidirectional one. It
 * writes, ends with our FIN, or resets; it cannot read.
 */
@Published
internal class SendHalf(
    private val connection: CoreConnection,
    val id: QuicStreamId,
    val writePolicy: WritePolicy,
) {
    private val use = AtomicReference(Sending.Open)

    /** Whether this half still takes bytes: neither finished nor reset. */
    fun isOpen(): Boolean = use.load() == Sending.Open

    /** Writes what quiche takes now of [buffer], at least one byte, waiting at most [deadline] for room. */
    suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (use.load() != Sending.Open) raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        val address = addressOf(buffer)
        if (buffer.remaining() == 0) return BytesWritten(0)
        return connection.submitted(id, WriteSource.Bytes(buffer, address, WriteEnd.More), deadline).answer()
    }

    /**
     * Writes every byte of [buffer] and ends this half with the last of them, in one send when quiche takes them all: a
     * request that fits a datagram is one datagram. A write after it fails as after [finish]. A failure after quiche
     * took part of the bytes resets this half and answers [Finished.Cut]; one before quiche took any byte raises and
     * leaves the half open, so a later [finish] sends the FIN.
     */
    suspend fun writeAndFinish(
        buffer: ReadBuffer,
        deadline: Duration,
    ): Finished {
        val address = addressOf(buffer)
        if (!use.compareAndSet(Sending.Open, Sending.Finishing)) {
            raise(Raised.StreamClosed(id, QuicStreamSide.Write), QuicCloseCircumstance.Ended)
        }
        val source = if (buffer.remaining() == 0) WriteSource.FinOnly else WriteSource.Bytes(buffer, address, WriteEnd.Finish)
        return finishing(source, deadline)
    }

    /**
     * Ends this half with our FIN. A half already finished or reset is left as it is; one whose finishing write is out
     * gets its FIN once that write answers, unless the write ended the half itself.
     */
    suspend fun finish() {
        if (use.compareAndSet(Sending.Open, Sending.Finishing)) {
            finishing(WriteSource.FinOnly, deadlineOf(writePolicy))
        } else {
            use.compareAndSet(Sending.Finishing, Sending.FinishAsked)
        }
    }

    /** Sends [source], ending this half, while it is [Sending.Finishing]; the half then stands where quiche left it. */
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

    /** This half after a finishing write answered; a [finish] that came meanwhile left the FIN to it, which goes now. */
    private fun settled(fin: Fin) {
        val after =
            when (fin) {
                Fin.Taken -> Sending.Finished
                Fin.Cut -> Sending.Reset
                Fin.Open -> Sending.Open
            }
        if (use.compareAndSet(Sending.Finishing, after)) return
        if (!use.compareAndSet(Sending.FinishAsked, if (fin == Fin.Open) Sending.Finished else after)) return
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

    /** Resets this half with [code] (RESET_STREAM), finished or not; once only. */
    fun reset(code: QuicAppErrorCode) {
        if (use.exchange(Sending.Reset) != Sending.Reset) connection.resetSending(id, code)
    }
}

/**
 * The receiving half of a stream of a [CoreConnection]: what a stream the peer opened to send only is, and half of a
 * bidirectional one. It reads, lets go of what is left, or tells the peer to stop; it cannot write.
 */
@Published
internal class ReceiveHalf(
    private val connection: CoreConnection,
    val id: QuicStreamId,
    val readPolicy: ReadPolicy,
) {
    private val use = AtomicReference(Receiving.Open)
    private val streamReads = StreamReads()

    fun isOpen(): Boolean = use.load() == Receiving.Open

    suspend fun read(deadline: Duration): ReadResult {
        if (use.load() == Receiving.Closed) raise(Raised.StreamClosed(id, QuicStreamSide.Read), QuicCloseCircumstance.Ended)
        return connection.read(id, deadline, streamReads)
    }

    /** Reads no more: what still arrives is dropped, and the peer is not told to stop. */
    fun close() {
        if (use.exchange(Receiving.Closed) == Receiving.Open) connection.abandon(id)
    }

    /** Reads no more and tells the peer to stop sending with [code] (STOP_SENDING), unless this half was closed already. */
    fun stop(code: QuicAppErrorCode): Stopping {
        if (use.exchange(Receiving.Closed) != Receiving.Open) return Stopping.Already
        connection.stopReading(id, code)
        return Stopping.Now
    }
}

/** A bidirectional stream: a [send] half and a [receive] half of the same id. */
internal class BidiStream(
    val send: SendHalf,
    val receive: ReceiveHalf,
) {
    val id: QuicStreamId get() = send.id
}

/** A stream the peer opened, as this side may use it, and whether it arrived before the handshake completed. */
internal sealed interface IncomingStream {
    class Bidirectional(
        val stream: BidiStream,
        val arrival: Arrival,
    ) : IncomingStream

    /** The peer opened it to send only: this side only reads it. */
    class ReceiveOnly(
        val receive: ReceiveHalf,
        val arrival: Arrival,
    ) : IncomingStream
}
