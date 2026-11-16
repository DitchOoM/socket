package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.demux.loopbackV4
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A message: an id and a text, the codec's two fields. */
internal data class Note(
    val id: Int,
    val text: String,
)

/**
 * Length-prefixed frames: a 4-byte count of the bytes after it, then the id and the UTF-8 text. An id of
 * [REJECTED_ID] is a value the decoder refuses.
 */
internal object NoteCodec : Codec<Note> {
    const val REJECTED_ID = -1
    const val HEADER = 4

    override fun encode(
        buffer: WriteBuffer,
        value: Note,
        context: EncodeContext,
    ) {
        val start = buffer.position()
        buffer.writeInt(0)
        buffer.writeInt(value.id)
        buffer.writeString(value.text, Charset.UTF8)
        buffer[start] = (buffer.position() - start - HEADER)
    }

    override fun wireSize(
        value: Note,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Note {
        val length = buffer.readInt()
        val id = buffer.readInt()
        if (id == REJECTED_ID) throw DecodeException("id", buffer.position(), "a usable id", "$id")
        return Note(id, buffer.readString(length - Int.SIZE_BYTES, Charset.UTF8))
    }

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult =
        if (stream.available() <
            baseOffset + HEADER
        ) {
            PeekResult.NeedsMoreData
        } else {
            PeekResult.Complete(HEADER + stream.peekInt(baseOffset))
        }
}

/** [note] as the bytes a peer's codec writes, in a buffer ready to read. */
internal fun frameOf(note: Note): PlatformBuffer {
    val size = HEADER_AND_ID + note.text.encodeToByteArray().size
    val buffer = BufferFactory.network().allocate(size)
    NoteCodec.encode(buffer, note, EncodeContext.Empty)
    buffer.resetForRead()
    return buffer
}

/** A frame header that declares [declared] bytes after it, and nothing after. */
internal fun headerDeclaring(declared: Int): PlatformBuffer =
    BufferFactory.network().allocate(NoteCodec.HEADER).also {
        it.writeInt(declared)
        it.resetForRead()
    }

private const val HEADER_AND_ID = NoteCodec.HEADER + Int.SIZE_BYTES

/** A pool over native memory; [outstanding] says how many of its buffers nobody has given back. */
internal class TrackedFactory {
    val pool = BufferPool(ThreadingMode.MultiThreaded, MAX_POOLED, POOLED_SIZE, BufferFactory.network())

    fun outstanding(): Long {
        val stats = pool.stats()
        return stats.poolMisses - stats.currentPoolSize
    }

    private companion object {
        const val MAX_POOLED = 4_096
        const val POOLED_SIZE = 4_096
    }
}

/** Serves on a loopback port; [onConnection] runs for each connection the server accepts. */
internal suspend fun withCoreServer(
    config: ServerConfig = ServerConfig(),
    bind: PathKey = loopbackV4(),
    api: QuicheApi = platformQuicheApi(),
    onConnection: suspend CoroutineScope.(CoreConnection) -> Unit,
    body: suspend CoroutineScope.(CoreServer) -> Unit,
) {
    val server =
        coreServe(
            bind = bind,
            tls = platformTlsConfig(),
            options = coreTestOptions,
            factory = BufferFactory.network(),
            api = api,
            readPolicy = ReadPolicy.Bounded(10.seconds),
            writePolicy = WritePolicy.Bounded(10.seconds),
            config = config,
        )
    try {
        coroutineScope {
            val serving =
                launch(Dispatchers.IO) {
                    try {
                        server.connections().collect { connection ->
                            launch {
                                try {
                                    onConnection(connection)
                                } catch (closed: QuicCloseException) {
                                    Unit
                                }
                            }
                        }
                    } catch (closed: QuicCloseException) {
                        Unit
                    }
                }
            body(server)
            serving.cancel()
        }
    } finally {
        server.close()
    }
}

/** Waits up to [within] for [condition]; returns whether it held. */
internal suspend fun eventually(
    within: kotlin.time.Duration = 5.seconds,
    condition: () -> Boolean,
): Boolean {
    var waited = 0.milliseconds
    while (!condition()) {
        if (waited >= within) return false
        delay(10.milliseconds)
        waited += 10.milliseconds
    }
    return true
}

/** Writes every byte of [buffer] in pieces of [piece] bytes on [stream]. */
internal suspend fun com.ditchoom.socket.quic.QuicByteStream.writeInPieces(
    buffer: PlatformBuffer,
    piece: Int,
) {
    while (buffer.remaining() > 0) {
        val take = minOf(piece, buffer.remaining())
        val end = buffer.limit()
        buffer.setLimit(buffer.position() + take)
        writeFully(buffer, 10.seconds)
        buffer.setLimit(end)
    }
}
