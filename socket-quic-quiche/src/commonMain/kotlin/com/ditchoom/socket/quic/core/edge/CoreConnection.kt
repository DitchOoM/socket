package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.HandshakeRecord
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCapabilities
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEngineConnection
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.withDatagramCounts
import com.ditchoom.socket.quic.core.stream.StreamKind
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * A [QuicEngineConnection] on the run-to-completion core. Every call becomes work inside the connection and suspends until
 * the connection has done it; nothing here blocks a thread. A caller on a main dispatcher leaves its work for the loop;
 * any other caller runs it in place when the connection is free.
 */
internal class CoreConnection(
    private val conn: Conn,
    internal val edge: EdgeState,
    override val bufferFactory: BufferFactory,
    override val remoteAddress: SocketAddress,
    override val identity: QuicConnectionIdentity,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    /** How long an open waits at the peer's stream limit. */
    private val openBound: Duration,
    scope: CoroutineScope,
    internal val handlers: HandlerSeat,
) : QuicEngineConnection,
    CoroutineScope by scope {
    override val state: StateFlow<QuicConnectionState> = edge.state
    override val unreadAtClose: StateFlow<QuicUnreadAtClose> = edge.unread
    override val capabilities: QuicCapabilities get() = CORE_CAPABILITIES
    override val pathState: StateFlow<QuicPathState> = MutableStateFlow(QuicPathState.Original)
    override val sessionTicket: StateFlow<QuicSessionTicketState> = edge.ticket

    /** Settled once the handshake completes, or for a server at its accepted 0-RTT, and kept after the connection closes. */
    override val negotiatedAlpn: String
        get() =
            when (val settled = edge.handshake.value) {
                is HandshakeRecord.Settled -> settled.negotiatedAlpn
                HandshakeRecord.Pending -> super.negotiatedAlpn
            }

    /** Settled and kept for the reason [negotiatedAlpn] is. */
    override val resumption: QuicResumptionOutcome
        get() =
            when (val settled = edge.handshake.value) {
                is HandshakeRecord.Settled -> settled.resumption
                HandshakeRecord.Pending -> super.resumption
            }

    /** The deadlines of this connection's operations that park with a bound, lent from inside. */
    internal val deadlines = OpDeadlines(conn)

    /** The connection's datagrams as the core's own channel, whose send answers a [com.ditchoom.socket.quic.DatagramSend]. */
    internal val coreDatagrams = CoreDatagramChannel(this, conn, edge, bufferFactory, remoteAddress)

    @OptIn(ExperimentalDatagramApi::class)
    override fun datagramChannel(): ConnectedDatagramChannel = coreDatagrams

    /** What the connection counted, any thread: the datagrams it dropped or refused among them. */
    internal val counts: ConnCounts get() = conn.counts

    /** How the connection stands: open, or ended and how. */
    fun end(): EdgeEnd = edge.end()

    /**
     * Suspends until the handshake completes, and returns once it did, whatever ended the connection since: what comes
     * next reports that end. Raises the end of a connection whose handshake never completed.
     */
    suspend fun established() {
        state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
        when (val end = edge.end()) {
            is EdgeEnd.Open -> Unit
            is EdgeEnd.Ended ->
                when (end.handshake) {
                    is HandshakeFate.Completed -> Unit
                    is HandshakeFate.NotCompleted -> raise(Raised.ConnEnded(end.end), QuicCloseCircumstance.HandshakeFailed)
                }
        }
    }

    /** This connection's streams in the core's own shapes: a send half, a receive half, or both. */
    internal val halves = StreamHalves(this, edge, readPolicy, writePolicy)

    override suspend fun openStream(): QuicByteStream = halves.public(halves.openBidirectional())

    override suspend fun openUniStream(): QuicByteStream = halves.public(halves.openSendOnly())

    override suspend fun acceptStream(): QuicByteStream = halves.public(halves.accept())

    /** Opens the next bidirectional stream as one carrying values of [T] framed by [codec]. */
    internal suspend fun <T : Any> openTypedStream(
        codec: Codec<T>,
        config: TypedStreamConfig = TypedStreamConfig(),
    ): TypedQuicStream<T> = typed(open(StreamKind.Bidirectional), codec, config)

    /** Accepts the next stream the peer opened as one carrying values of [T] framed by [codec]. */
    internal suspend fun <T : Any> acceptTypedStream(
        codec: Codec<T>,
        config: TypedStreamConfig = TypedStreamConfig(),
    ): TypedQuicStream<T> =
        typed(
            QuicStreamId(
                edge.incoming.bidirectional
                    .receive()
                    .id,
            ),
            codec,
            config,
        )

    private fun <T : Any> typed(
        id: QuicStreamId,
        codec: Codec<T>,
        config: TypedStreamConfig,
    ): TypedQuicStream<T> = TypedQuicStream(this, conn, id, codec, config, readPolicy, writePolicy, bufferFactory)

    override fun streams(): Flow<QuicByteStream> = edge.incoming.all().map { halves.public(halves.incomingOf(it)) }

    /** The close point is this call's place in the order of work; once it returns, what no read took is freed. */
    override suspend fun close(error: QuicError) {
        if (!edge.closed.isCompleted) conn.submit(CloseWork(error), CallerPlace.HandToLoop)
        edge.closedByCaller()
    }

    /** Closes as [choice] says, returning once the connection ended, with how the close went. */
    internal suspend fun close(choice: CloseChoice): CloseOutcome {
        if (!edge.closed.isCompleted) conn.submit(ChosenCloseWork(choice), CallerPlace.HandToLoop)
        return edge.closedByCaller().outcome
    }

    /** Closes without waiting; for a caller that has already given up on the connection. */
    fun closeNow(error: QuicError) = conn.submit(CloseWork(error), CallerPlace.HandToLoop)

    /** Tells the connection the network monitor's news. */
    fun network(change: NetworkChange) = conn.submit(NetworkWork(change), CallerPlace.HandToLoop)

    /** Hands quiche [stream]'s FIN alone, from inside the connection, for a stream closed while a write ending it gave up. */
    internal fun finishAlone(stream: QuicStreamId) = conn.submit(FinWork(stream), CallerPlace.HandToLoop)

    /** The caller closed [stream] without reading it to the end: what still arrives is dropped. */
    internal fun abandon(stream: QuicStreamId) {
        edge.letGo(stream)
        conn.submit(AbandonWork(stream), CallerPlace.HandToLoop)
    }

    /** Resets our sending direction of [stream] with [code]. */
    internal fun resetSending(
        stream: QuicStreamId,
        code: QuicAppErrorCode,
    ) = conn.submit(ResetSendWork(stream, code), CallerPlace.HandToLoop)

    /** Tells the peer to stop sending on [stream] with [code]; what still arrives is dropped. */
    internal fun stopReading(
        stream: QuicStreamId,
        code: QuicAppErrorCode,
    ) {
        edge.letGo(stream)
        conn.submit(StopWork(stream, code), CallerPlace.HandToLoop)
    }

    /** Opens the next stream of [kind] under the connection's open policy; the id of the stream it made. */
    internal suspend fun open(kind: StreamKind): QuicStreamId {
        val open = OpenOp(kind, openBound, deadlines, conn)
        conn.submit(open, placeOf(currentCoroutineContext()))
        return QuicStreamId(open.answer())
    }

    /** [open] with a refused open as a value. */
    internal suspend fun opened(kind: StreamKind): OpenSettled {
        val open = OpenOp(kind, openBound, deadlines, conn)
        conn.submit(open, placeOf(currentCoroutineContext()))
        return open.settled()
    }

    /** Read inside the connection when asked; nothing is counted for it otherwise. */
    override suspend fun stats(): QuicStatsRead =
        suspendCancellableCoroutine { cont -> conn.submit(StatsWork(cont), CallerPlace.HandToLoop) }.withDatagramCounts(conn.counts)

    /** What the connection waits on: its published life and counts, and what the thread inside saw, if one answers within [bound]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun stallReport(bound: Duration): StallReport {
        val answer = CompletableDeferred<InsideAnswer>()
        conn.submit(StallWork(answer), CallerPlace.HandToLoop)
        val inside =
            select {
                answer.onAwait { it }
                onTimeout(bound) { InsideAnswer.NoAnswer(bound) }
            }
        return StallReport(conn.life(), conn.counts.seen(), inside)
    }

    /** Writes the connection's flight recording, in the trace grammar, into [sink]. */
    suspend fun dumpFlight(sink: TraceSink): FlightDump =
        suspendCancellableCoroutine { cont -> conn.submit(DumpWork(sink, cont), CallerPlace.HandToLoop) }

    internal suspend fun read(
        stream: QuicStreamId,
        deadline: Duration,
        streamReads: StreamReads,
    ): ReadResult {
        val op = ReadOp(stream, deadline, deadlines, conn, edge, bufferFactory, streamReads)
        conn.submit(op, placeOf(currentCoroutineContext()))
        return op.answer()
    }

    internal suspend fun taken(
        stream: QuicStreamId,
        deadline: Duration,
        streamReads: StreamReads,
    ): ReadAnswer {
        val op = ReadOp(stream, deadline, deadlines, conn, edge, bufferFactory, streamReads)
        conn.submit(op, placeOf(currentCoroutineContext()))
        return op.taken()
    }

    /** A write handed to the connection; its caller takes the answer. */
    internal suspend fun submitted(
        stream: QuicStreamId,
        source: WriteSource,
        deadline: Duration,
    ): WriteOp {
        val caller = currentCoroutineContext()
        return WriteOp(stream, source, deadline, deadlines, conn, caller).also { conn.submit(it, placeOf(caller)) }
    }

    /** Lets a client's first flight, held for 0-RTT, leave. */
    fun releaseFirstFlight() = conn.submit(ReleaseFlight(), CallerPlace.HandToLoop)

    /** A caller on a main thread never makes system calls; any other may. See [callerPlaceOf]. */
    internal fun placeOf(context: CoroutineContext): CallerPlace = callerPlaceOf(context, mainThreadsOfThisRuntime)
}

/** What every core connection requires of a caller's buffers: an address quiche reads in place. */
private val CORE_CAPABILITIES = QuicCapabilities(requiresNativeMemoryBuffers = true)
