package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.NativeMemoryAccess
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
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicOptions
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

/**
 * A factory over native memory that counts exactly: [live] is the buffers it made that nobody has freed. A pool's own
 * statistics cannot say this, since a buffer too big to pool is never counted back in.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TrackedFactory(
    private val hold: AllocationHold = AllocationHold.None,
) : BufferFactory {
    private val made = AtomicInt(0)
    private val freed = AtomicInt(0)

    fun live(): Int = made.load() - freed.load()

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        hold.before(size)
        made.fetchAndAdd(1)
        return CountedBuffer(BufferFactory.network().allocate(size, byteOrder)) { freed.fetchAndAdd(1) }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = BufferFactory.network().wrap(array, byteOrder)
}

/** What a [TrackedFactory] does on the thread that allocates, before it counts the buffer. */
@OptIn(ExperimentalAtomicApi::class)
internal sealed interface AllocationHold {
    fun before(size: Int)

    data object None : AllocationHold {
        override fun before(size: Int) = Unit
    }

    /**
     * Holds the thread allocating a buffer of [size] bytes until [release], at most [HELD_AT_MOST]: the allocation is
     * counted after whatever the test does meanwhile. Holding the loop thread is the point; it spins, as tests on every
     * platform must, and lets go on its own if the test never releases it.
     */
    class OfSize(
        private val size: Int,
    ) : AllocationHold {
        private val released = AtomicInt(0)

        override fun before(size: Int) {
            if (size != this.size) return
            val since =
                kotlin.time.TimeSource.Monotonic
                    .markNow()
            while (released.load() == 0 && since.elapsedNow() < HELD_AT_MOST) Unit
        }

        fun release() = released.store(1)
    }
}

private val HELD_AT_MOST = 5.seconds

/** A buffer that reports its first free to [onFree] and hands its memory address on, so quiche can use it in place. */
@OptIn(ExperimentalAtomicApi::class)
private class CountedBuffer(
    private val inner: PlatformBuffer,
    private val onFree: () -> Unit,
) : PlatformBuffer by inner,
    NativeMemoryAccess {
    private val released = AtomicInt(0)

    override val nativeAddress: Long get() = inner.nativeMemoryAccess!!.nativeAddress
    override val nativeSize: Long get() = inner.nativeMemoryAccess!!.nativeSize

    override fun freeNativeMemory() {
        if (released.compareAndSet(0, 1)) {
            onFree()
            inner.freeNativeMemory()
        }
    }
}

/** Serves on a loopback port; [onConnection] runs for each connection the server accepts. */
internal suspend fun withCoreServer(
    config: ServerConfig = ServerConfig(),
    bind: PathKey = loopbackV4(),
    api: QuicheApi = platformQuicheApi(),
    options: QuicOptions = coreTestOptions,
    onConnection: suspend CoroutineScope.(CoreConnection) -> Unit,
    body: suspend CoroutineScope.(CoreServer) -> Unit,
) {
    val server =
        coreServe(
            bind = bind,
            tls = platformTlsConfig(),
            options = options,
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

/** Suspends until [conn] has run every work handed to it before this call, so what that work frees is freed. */
internal suspend fun drained(conn: com.ditchoom.socket.quic.core.conn.Conn) {
    val ran = kotlinx.coroutines.CompletableDeferred<Unit>()
    conn.submit(
        object : com.ditchoom.socket.quic.core.conn.ConnWork() {
            override fun run(inside: com.ditchoom.socket.quic.core.conn.Inside) {
                ran.complete(Unit)
            }

            override fun gone(end: com.ditchoom.socket.quic.core.life.ConnEnd) {
                ran.complete(Unit)
            }
        },
        com.ditchoom.socket.quic.core.conn.CallerPlace.HandToLoop,
    )
    ran.await()
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
