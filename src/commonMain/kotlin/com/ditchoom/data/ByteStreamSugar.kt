package com.ditchoom.data

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.lines
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/*
 * Convenience helpers over the v6 byte trichotomy (ByteSource / ByteSink).
 *
 * These replace the old Reader / Writer interfaces' sugar methods. They are plain extensions —
 * not part of the type — and they bridge the ReadResult trichotomy back to the exception-throwing
 * shape (SocketClosedException on clean EOF / reset) that string- and flow-oriented callers expect.
 *
 * Each overload defaults its deadline to the receiver's injected policy (readPolicy / writePolicy),
 * so a persistent stream's ReadPolicy.UntilClosed is honored automatically.
 */

/**
 * Reads the next chunk as a [ReadBuffer], throwing [SocketClosedException] on clean EOF or reset.
 * Preserves the pre-v6 `read(): ReadBuffer` semantics for callers that prefer exceptions to
 * [ReadResult] branching.
 */
@Throws(CancellationException::class, SocketClosedException::class)
suspend fun ByteSource.readBuffer(deadline: Duration = readPolicy.toDeadline()): ReadBuffer =
    when (val result = read(deadline)) {
        is ReadResult.Data -> result.buffer
        // Preserve the historical exception subtypes so `catch`/`is` checks keep working:
        // clean EOF → EndOfStream, peer reset → ConnectionReset.
        ReadResult.End -> throw SocketClosedException.EndOfStream("Stream reached end of input")
        ReadResult.Reset -> throw SocketClosedException.ConnectionReset("Stream reset by peer")
    }

/** Reads a single chunk and decodes it as a string. Throws [SocketClosedException] on EOF/reset. */
@Throws(CancellationException::class, SocketClosedException::class)
suspend fun ByteSource.readString(
    charset: Charset = Charset.UTF8,
    deadline: Duration = readPolicy.toDeadline(),
): String {
    val buffer = readBuffer(deadline)
    return buffer.readString(buffer.remaining(), charset)
}

/**
 * Reads into [buffer], returning the number of bytes read. Default implementation reads a new
 * buffer and copies. Throws [SocketClosedException] on EOF/reset.
 */
@Throws(CancellationException::class, SocketClosedException::class)
suspend fun ByteSource.readInto(
    buffer: WriteBuffer,
    deadline: Duration = readPolicy.toDeadline(),
): Int {
    val readBuffer = readBuffer(deadline)
    val bytesRead = readBuffer.remaining()
    buffer.write(readBuffer)
    return bytesRead
}

/** Emits chunks until clean EOF or reset (both terminate the flow quietly). */
fun ByteSource.readFlow(deadline: Duration = readPolicy.toDeadline()): Flow<ReadBuffer> =
    flow {
        while (isOpen) {
            when (val result = read(deadline)) {
                is ReadResult.Data -> emit(result.buffer)
                ReadResult.End -> return@flow
                ReadResult.Reset -> return@flow
            }
        }
    }

/** Emits decoded strings until EOF/reset. */
fun ByteSource.readFlowString(
    charset: Charset = Charset.UTF8,
    deadline: Duration = readPolicy.toDeadline(),
): Flow<String> =
    readFlow(deadline).map {
        it.readString(it.remaining(), charset)
    }

/** Emits decoded lines until EOF/reset. */
fun ByteSource.readFlowLines(
    charset: Charset = Charset.UTF8,
    deadline: Duration = readPolicy.toDeadline(),
): Flow<String> = readFlowString(charset, deadline).lines()

/** Encodes [string] and writes it. */
@Throws(CancellationException::class, SocketClosedException::class)
suspend fun ByteSink.writeString(
    string: String,
    charset: Charset = Charset.UTF8,
    deadline: Duration = writePolicy.toDeadline(),
): BytesWritten = write(string.toReadBuffer(charset), deadline)

/** Frees the buffer wrapped by a [ReadResult.Data], if any. No-op for [ReadResult.End]/[ReadResult.Reset]. */
fun ReadResult.freeIfData() {
    if (this is ReadResult.Data) buffer.freeIfNeeded()
}
