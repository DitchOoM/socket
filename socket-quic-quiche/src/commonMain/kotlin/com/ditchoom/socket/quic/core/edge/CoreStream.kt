@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.quic.QuicNativeMemoryRequiredException
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicWriteTarget
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.core.conn.WriteEnd
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/** What a caller may still do with a stream. */
private sealed interface Use {
    object Open : Use

    /** Our FIN is sent; reads continue. */
    object SendFinished : Use

    object Closed : Use
}

/** One stream of a [CoreConnection], as the public [ByteStream] contract sees it. */
internal class CoreStream(
    private val connection: CoreConnection,
    private val id: QuicStreamId,
    override val readPolicy: ReadPolicy,
    override val writePolicy: WritePolicy,
) : ByteStream,
    HalfCloseable {
    private val use = AtomicReference<Use>(Use.Open)

    override val isOpen: Boolean get() = use.load() !== Use.Closed

    override suspend fun read(deadline: Duration): ReadResult {
        if (use.load() === Use.Closed) throw IllegalStateException("stream ${id.id} is closed")
        return connection.read(id, deadline)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (use.load() !== Use.Open) throw IllegalStateException("stream ${id.id} can no longer be written")
        val native =
            buffer.nativeMemoryAccess
                ?: throw QuicNativeMemoryRequiredException.forBuffer(QuicWriteTarget.Stream(id.id), buffer, QuicheDriver.capabilities)
        if (buffer.remaining() == 0) return BytesWritten(0)
        return connection.write(id, buffer, native.nativeAddress.toLong(), WriteEnd.More, deadline)
    }

    override suspend fun shutdownSend() {
        if (use.compareAndSet(Use.Open, Use.SendFinished)) finish()
    }

    override suspend fun close() {
        when (use.exchange(Use.Closed)) {
            Use.Open -> finish()
            Use.SendFinished, Use.Closed -> Unit
        }
    }

    private suspend fun finish() = connection.finish(id, writePolicy.toDeadlineOrForever())
}

private fun WritePolicy.toDeadlineOrForever(): Duration =
    when (this) {
        is WritePolicy.Bounded -> deadline
        WritePolicy.UntilClosed -> Duration.INFINITE
    }
