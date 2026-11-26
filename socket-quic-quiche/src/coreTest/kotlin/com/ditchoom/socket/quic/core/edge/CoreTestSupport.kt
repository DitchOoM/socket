package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.conn.ClientTokens
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The options every core test connects with. */
internal val coreTestOptions = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

/** [coreTestOptions] for a client whose server opens streams to it: a client refuses them by default. */
internal val acceptingClientOptions = coreTestOptions.copy(incomingStreams = IncomingStreams.Accept())

/** The prefix of the loop threads' names, as [coreConnect] starts them. */
internal const val LOOP_THREAD = "quic-core-client"

internal suspend fun connectCore(
    port: Int,
    host: String = "127.0.0.1",
    loops: LoopSet = LoopSet.shared,
    factory: BufferFactory = BufferFactory.network(),
    api: com.ditchoom.socket.quic.QuicheApi = platformQuicheApi(),
    options: QuicOptions = coreTestOptions,
    tokens: ClientTokens = ClientTokens.NotKept,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
): CoreClient =
    coreConnect(
        mismatch = MismatchResponse.Fail,
        host = host,
        port = port,
        serverName = "localhost",
        // A test's own store stays; the process's default would offer another test's ticket on a reused port.
        options = if (options.sessionStore === QuicSessionStore.Default) options.copy(sessionStore = QuicSessionStore.NotKept) else options,
        factory = factory,
        api = api,
        timeout = 10.seconds,
        readPolicy = ReadPolicy.Bounded(10.seconds),
        writePolicy = WritePolicy.Bounded(10.seconds),
        wrapIo = wrapIo,
        loops = loops,
        flight = TEST_FLIGHT,
        tokens = tokens,
    )

/** The flight recording of a test's client connection: a server connection's size. */
internal val TEST_FLIGHT = FlightSize(records = 256)

/** Writes [payload] on [stream] while reading the echo; returns what came back. */
internal suspend fun echoOver(
    stream: QuicByteStream,
    payload: ByteArray,
): ByteArray {
    val received = ReceivedBytes(payload.size)
    coroutineScope {
        val reader =
            async {
                while (received.size < payload.size) {
                    if (stream.read(10.seconds) { received.add(it) } !is ScopedReadResult.Data<*>) break
                }
            }
        stream.writeFully(
            BufferFactory.network().allocate(payload.size).apply {
                writeBytes(payload)
                resetForRead()
            },
            10.seconds,
        )
        reader.await()
    }
    return received.toByteArray()
}

/** What a reader collected, in order. */
internal class ReceivedBytes(
    expected: Int,
) {
    private var bytes = ByteArray(maxOf(expected, 16))

    var size = 0
        private set

    fun add(buffer: ReadBuffer) {
        val n = buffer.remaining()
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
        buffer.readByteArray(n).copyInto(bytes, size)
        size += n
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

internal suspend fun <T> onIo(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

/**
 * Runs [block] on this connection for no longer than it lives. When the connection ends first, [block] is cancelled once
 * nothing the connection received is left to read (or after [linger]) and this throws a [QuicCloseException] with the
 * close's reason; what [block] returns or throws itself wins, and so does the caller's own cancellation. An end this
 * side asked for with an application error is not the connection dying: [block] unwinds on its own.
 */
internal suspend fun <R> CoreConnection.runUntilEnded(
    linger: Duration,
    block: suspend CoreConnection.() -> R,
): R {
    val diedFirst = CompletableDeferred<QuicCloseException>()
    val returned = CompletableDeferred<R>()
    return try {
        coroutineScope {
            val body = async(start = CoroutineStart.UNDISPATCHED) { block().also { returned.complete(it) } }
            val watch =
                launch {
                    val closed = state.filterIsInstance<QuicConnectionState.Closed>().first()
                    val reason = closed.reason
                    if (reason is QuicCloseReason.ByLocal && reason.error is QuicError.ApplicationError) return@launch
                    withTimeoutOrNull(linger) { unreadAtClose.filterIsInstance<QuicUnreadAtClose.AllRead>().first() }
                    val close = QuicCloseException(closed.reason, "connection closed while its block was running")
                    diedFirst.complete(close)
                    body.cancel(CancellationException("connection closed", close))
                }
            try {
                body.await()
            } finally {
                watch.cancel()
            }
        }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (returned.isCompleted) return returned.await()
        if (diedFirst.isCompleted) throw diedFirst.await()
        throw e
    }
}
