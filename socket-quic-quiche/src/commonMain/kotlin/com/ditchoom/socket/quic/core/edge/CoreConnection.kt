package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCapabilities
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.QuicheDriver
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnEnd
import com.ditchoom.socket.quic.core.conn.ConnEvents
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.WriteEnd
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamMap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

private sealed interface Binding {
    object Nothing : Binding

    class To(
        val conn: Conn,
    ) : Binding
}

private sealed interface WhenEnded {
    object Nothing : WhenEnded

    class Run(
        val action: () -> Unit,
    ) : WhenEnded
}

/**
 * A [QuicConnection] on the run-to-completion core. Every call becomes work inside the connection and suspends
 * until the connection has done it; nothing here blocks a thread.
 */
internal class CoreConnection(
    override val bufferFactory: BufferFactory,
    override val remoteAddress: SocketAddress,
    override val identity: QuicConnectionIdentity,
    private val role: QuicRole,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    scope: CoroutineScope,
) : QuicConnection,
    ConnEvents,
    CoroutineScope by scope {
    private var bound: Binding = Binding.Nothing
    private var whenEnded: WhenEnded = WhenEnded.Nothing
    private val streams = StreamMap()
    private val incoming = Channel<QuicByteStream>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Handshaking)
    private val closed = CompletableDeferred<QuicCloseReason>()
    private val submitWork: (ConnWork) -> Unit = ::submit
    private var nextBidi: Long = if (role == QuicRole.Client) 0L else 1L

    /** Opens waiting for the peer to allow another stream, oldest first. */
    private val opens = ArrayDeque<OpenStream>()

    /** The zero bytes a FIN is sent with: quiche reads no memory for a zero-length send. */
    private val empty: ReadBuffer = bufferFactory.allocate(0)

    override val state: StateFlow<QuicConnectionState> = mutableState
    override val unreadAtClose: StateFlow<QuicUnreadAtClose> = MutableStateFlow(QuicUnreadAtClose.ConnectionOpen)
    override val capabilities: QuicCapabilities get() = QuicheDriver.capabilities
    override val pathState: StateFlow<QuicPathState> = MutableStateFlow(QuicPathState.Original)

    /** Once, before the connection's loop starts. */
    fun bind(conn: Conn) {
        bound = Binding.To(conn)
    }

    /** Once, before the connection's loop starts: runs inside the connection, once, when it has ended. */
    fun whenEnded(action: () -> Unit) {
        whenEnded = WhenEnded.Run(action)
    }

    /** Closes without waiting; for a caller that has already given up on the connection. */
    fun closeNow(error: QuicError) =
        submit(
            object : ConnWork() {
                override fun run(inside: Conn.Inside) = inside.close(error)

                override fun gone() = Unit
            },
        )

    /** Suspends until the handshake completes; throws [QuicCloseException] if the connection ends first. */
    suspend fun established() {
        val end =
            when (val reached = state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }) {
                is QuicConnectionState.Closed -> reached.reason
                else -> return
            }
        throw QuicCloseException(end, "QUIC handshake failed")
    }

    override suspend fun openStream(): QuicByteStream {
        val id =
            suspendCancellableCoroutine { cont ->
                val open = OpenStream(cont)
                submit(open)
                cont.invokeOnCancellation { submit(WithdrawOpen(open)) }
            }
        return streamFor(id)
    }

    override suspend fun acceptStream(): QuicByteStream = incoming.receive()

    override fun streams(): Flow<QuicByteStream> = incoming.consumeAsFlow()

    override suspend fun close(error: QuicError) {
        if (!closed.isCompleted) {
            run<Unit> { inside -> inside.close(error) }
        }
        closed.await()
    }

    internal suspend fun read(
        stream: QuicStreamId,
        deadline: Duration,
    ): ReadResult =
        settle(deadline) { cont ->
            ReadOp(streams, stream, deadline, cont, submitWork, bufferFactory, READ_CHUNK)
        }

    internal suspend fun write(
        stream: QuicStreamId,
        buffer: ReadBuffer,
        address: Long,
        end: WriteEnd,
        deadline: Duration,
    ): Int =
        settle(deadline) { cont ->
            WriteOp(streams, stream, deadline, cont, submitWork, buffer, address, end)
        }

    /** Sends our FIN on [stream]. */
    internal suspend fun finish(
        stream: QuicStreamId,
        deadline: Duration,
    ) {
        write(stream, empty, NO_ADDRESS, WriteEnd.Finish, deadline)
    }

    // --- ConnEvents: inside the connection ---

    override fun established(inside: Conn.Inside) {
        val alpn = bufferFactory.allocate(MAX_ALPN)
        val length = inside.applicationProtocol(addressOf(alpn), MAX_ALPN)
        alpn.setLimit(length)
        mutableState.value = QuicConnectionState.Established(alpn.readString(length, Charset.UTF8))
        alpn.freeNativeMemory()
    }

    override fun readable(
        inside: Conn.Inside,
        stream: QuicStreamId,
    ) {
        if (!streams.contains(stream) && initiatedByPeer(stream)) {
            streams.entry(stream)
            incoming.trySend(streamFor(stream))
        }
        streams.entry(stream).wakeReader(inside)
    }

    override fun writable(
        inside: Conn.Inside,
        stream: QuicStreamId,
    ) = streams.entry(stream).wakeWriter(inside)

    override fun served(inside: Conn.Inside) {
        while (opens.isNotEmpty() && inside.peerStreamsLeftBidi() > 0) {
            if (opens.first().tryOpen(inside) === Retry.Again) return
            opens.removeFirst()
        }
    }

    override fun closed(end: ConnEnd) {
        val reason =
            when (end) {
                ConnEnd.TimedOut -> QuicCloseReason.ByLocal(QuicError.IdleTimeout)
                is ConnEnd.ByPeer -> QuicCloseReason.ByPeer(end.error)
                is ConnEnd.Locally -> QuicCloseReason.ByLocal(end.error)
                is ConnEnd.Failed -> QuicCloseReason.ByLocal(QuicError.InternalError("processing the connection failed"))
                ConnEnd.Quietly, is ConnEnd.LoopFailed -> QuicCloseReason.Unspecified
            }
        val failure =
            when (end) {
                is ConnEnd.Failed -> QuicCloseException(reason, "processing the connection failed", end.cause)
                is ConnEnd.LoopFailed -> QuicCloseException(reason, "the connection's loop thread failed", end.cause)
                else -> QuicCloseException(reason, "connection closed")
            }
        streams.forEach { it.endWaiters(failure) }
        opens.forEach { it.fail(failure) }
        opens.clear()
        incoming.close(failure)
        mutableState.value = QuicConnectionState.Closed(reason)
        closed.complete(reason)
        when (val then = whenEnded) {
            WhenEnded.Nothing -> Unit
            is WhenEnded.Run -> then.action()
        }
    }

    // ---

    private fun submit(work: ConnWork) {
        when (val now = bound) {
            Binding.Nothing -> work.gone()
            is Binding.To -> now.conn.submit(work)
        }
    }

    private fun initiatedByPeer(stream: QuicStreamId): Boolean = (stream.id and 1L == 1L) == (role == QuicRole.Client)

    private fun streamFor(id: QuicStreamId): QuicByteStream = QuicByteStream(id, CoreStream(this, id, readPolicy, writePolicy))

    /** Runs [body] inside the connection and returns its value. */
    private suspend fun <T> run(body: (Conn.Inside) -> T): T =
        suspendCancellableCoroutine { cont ->
            submit(
                object : ConnWork() {
                    override fun run(inside: Conn.Inside) {
                        if (cont.isActive) cont.resumeWith(kotlin.runCatching { body(inside) })
                    }

                    override fun gone() {
                        if (cont.isActive) cont.resumeWith(kotlin.Result.failure(ConnectionReleased.failure()))
                    }
                },
            )
        }

    private suspend fun <T> settle(
        deadline: Duration,
        op: (CancellableContinuation<Outcome<T>>) -> StreamOp<T>,
    ): T {
        val outcome =
            suspendCancellableCoroutine { cont ->
                val made = op(cont)
                submit(made)
                cont.invokeOnCancellation { submit(Withdraw(made)) }
            }
        return when (outcome) {
            is Outcome.Done -> outcome.value
            Outcome.TimedOut -> timedOut(deadline)
        }
    }

    private companion object {
        const val STREAM_ID_STEP = 4L
        const val READ_CHUNK = 65_536
        const val MAX_ALPN = 255
        const val NO_ADDRESS = 0L
        const val QUICHE_ERR_STREAM_LIMIT = -12
    }

    /**
     * Opens the next bidirectional stream: a zero-length send makes quiche create it, which is also where the
     * peer's stream limit shows. At the limit the id is not spent and the open waits for the peer's MAX_STREAMS.
     */
    private inner class OpenStream(
        private val cont: CancellableContinuation<QuicStreamId>,
    ) : ConnWork() {
        override fun run(inside: Conn.Inside) {
            if (opens.isNotEmpty() || tryOpen(inside) === Retry.Again) opens.addLast(this)
        }

        fun tryOpen(inside: Conn.Inside): Retry {
            if (!cont.isActive) return Retry.Settled
            val id = QuicStreamId(nextBidi)
            if (inside.streamSend(id, NO_ADDRESS, 0, WriteEnd.More).result == QUICHE_ERR_STREAM_LIMIT) return Retry.Again
            nextBidi += STREAM_ID_STEP
            streams.entry(id)
            cont.resume(id)
            return Retry.Settled
        }

        fun fail(failure: Throwable) {
            if (cont.isActive) cont.resumeWithException(failure)
        }

        fun withdraw() {
            opens.remove(this)
        }

        override fun gone() = fail(ConnectionReleased.failure())
    }

    private inner class WithdrawOpen(
        private val open: OpenStream,
    ) : ConnWork() {
        override fun run(inside: Conn.Inside) = open.withdraw()

        override fun gone() = Unit
    }
}

/** What a caller gets when the connection was released before its work ran. */
internal object ConnectionReleased {
    fun failure(): QuicCloseException = QuicCloseException(QuicCloseReason.Unspecified, "connection released")
}

/** The same exception a 4.x stream deadline raises, so callers see one type for both cores. */
private suspend fun timedOut(bound: Duration): Nothing {
    try {
        withTimeout(Duration.ZERO) { awaitCancellation() }
    } catch (timeout: TimeoutCancellationException) {
        timeout.addSuppressed(
            com.ditchoom.socket.quic
                .StreamDeadlineBound(bound),
        )
        throw timeout
    }
}
