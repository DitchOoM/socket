package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.EarlyDataAcceptance
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCapabilities
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicEngineSpi
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicStreamOrigin
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration

/** What an on-demand dump of a connection's flight recording did. */
internal enum class FlightDump {
    /** Every record the connection still holds was written. */
    Written,

    /** The connection had ended; with `QuicOptions.trace` set its recording already went to the trace. */
    ConnectionEnded,
}

/**
 * A [QuicConnection] on the run-to-completion core. Every call becomes work inside the connection and suspends until
 * the connection has done it; nothing here blocks a thread. A caller on a main dispatcher leaves its work for the loop;
 * any other caller runs it in place when the connection is free.
 */
internal class CoreConnection(
    private val conn: Conn,
    private val edge: EdgeState,
    override val bufferFactory: BufferFactory,
    override val remoteAddress: SocketAddress,
    override val identity: QuicConnectionIdentity,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    scope: CoroutineScope,
) : QuicConnection,
    CoroutineScope by scope {
    override val state: StateFlow<QuicConnectionState> = edge.state
    override val unreadAtClose: StateFlow<QuicUnreadAtClose> = MutableStateFlow(QuicUnreadAtClose.ConnectionOpen)
    override val capabilities: QuicCapabilities get() = CORE_CAPABILITIES
    override val pathState: StateFlow<QuicPathState> = MutableStateFlow(QuicPathState.Original)
    override val sessionTicket: StateFlow<QuicSessionTicketState> = edge.ticket

    /** A server that accepted 0-RTT knows the protocol before its handshake completes. */
    override val negotiatedAlpn: String
        get() =
            when (val early = edge.earlyData.value) {
                is EarlyDataAcceptance.Accepted -> early.negotiatedAlpn
                EarlyDataAcceptance.None -> super.negotiatedAlpn
            }

    /** A server that accepted 0-RTT knows how the handshake used the ticket before it completes. */
    override val resumption: QuicResumptionOutcome
        get() =
            when (val early = edge.earlyData.value) {
                is EarlyDataAcceptance.Accepted -> early.resumption
                EarlyDataAcceptance.None -> super.resumption
            }

    /** The deadlines of this connection's operations that park with a bound, lent from inside. */
    internal val deadlines = OpDeadlines(conn)

    private val datagrams = CoreDatagramChannel(this, conn, edge, bufferFactory, remoteAddress)

    @OptIn(ExperimentalDatagramApi::class)
    override fun datagramChannel(): ConnectedDatagramChannel = datagrams

    /** Received datagrams dropped because the receive queue was full and nobody was receiving; any thread. */
    fun datagramsOverflowed(): Long = conn.counts.datagramsOverflowed

    /** How the connection stands: open, or ended and how. */
    fun end(): EdgeEnd = edge.end()

    /** Suspends until the handshake completes; raises the close if the connection ends first. */
    suspend fun established() {
        state.first { it is QuicConnectionState.Established || it is QuicConnectionState.Closed }
        when (val end = edge.end()) {
            is EdgeEnd.Open -> Unit
            is EdgeEnd.Ended -> raise(Raised.ConnEnded(end.end), QuicCloseCircumstance.HandshakeFailed)
        }
    }

    override suspend fun openStream(): QuicByteStream {
        val open = OpenOp(conn)
        conn.submit(open, placeOf(currentCoroutineContext()))
        return streamFor(open.answer())
    }

    override suspend fun acceptStream(): QuicByteStream = peerStream(edge.incoming.receive())

    /** Opens the next bidirectional stream as one carrying values of [T] framed by [codec]. */
    internal suspend fun <T : Any> openTypedStream(
        codec: Codec<T>,
        config: TypedStreamConfig = TypedStreamConfig(),
    ): TypedQuicStream<T> = typed(openStream().streamId, codec, config)

    /** Accepts the next stream the peer opened as one carrying values of [T] framed by [codec]. */
    internal suspend fun <T : Any> acceptTypedStream(
        codec: Codec<T>,
        config: TypedStreamConfig = TypedStreamConfig(),
    ): TypedQuicStream<T> = typed(QuicStreamId(edge.incoming.receive().id), codec, config)

    private fun <T : Any> typed(
        id: QuicStreamId,
        codec: Codec<T>,
        config: TypedStreamConfig,
    ): TypedQuicStream<T> = TypedQuicStream(this, conn, id, codec, config, readPolicy, writePolicy, bufferFactory)

    override fun streams(): Flow<QuicByteStream> = edge.incoming.consumeAsFlow().map { peerStream(it) }

    override suspend fun close(error: QuicError) {
        if (!edge.closed.isCompleted) conn.submit(CloseWork(error), CallerPlace.HandToLoop)
        edge.closed.await()
    }

    /** Closes without waiting; for a caller that has already given up on the connection. */
    fun closeNow(error: QuicError) = conn.submit(CloseWork(error), CallerPlace.HandToLoop)

    /** Tells the connection the network monitor's news. */
    fun network(change: NetworkChange) = conn.submit(NetworkWork(change), CallerPlace.HandToLoop)

    /** Resets both directions of [stream] with [code]. */
    internal fun resetStream(
        stream: QuicStreamId,
        code: QuicAppErrorCode,
    ) = conn.submit(ResetWork(stream, code), CallerPlace.HandToLoop)

    /** Writes the connection's flight recording, in the trace grammar, into [sink]. */
    suspend fun dumpFlight(sink: TraceSink): FlightDump =
        suspendCancellableCoroutine { cont -> conn.submit(DumpWork(sink, cont), CallerPlace.HandToLoop) }

    internal suspend fun read(
        stream: QuicStreamId,
        deadline: Duration,
    ): ReadResult {
        val op = ReadOp(stream, deadline, deadlines, conn, bufferFactory)
        conn.submit(op, placeOf(currentCoroutineContext()))
        return op.answer()
    }

    internal suspend fun write(
        stream: QuicStreamId,
        source: WriteSource,
        deadline: Duration,
    ): BytesWritten {
        val caller = currentCoroutineContext()
        val op = WriteOp(stream, source, deadline, deadlines, conn, caller)
        conn.submit(op, placeOf(caller))
        return op.answer()
    }

    private fun streamFor(id: Long): QuicByteStream =
        QuicByteStream(QuicStreamId(id), CoreStream(this, QuicStreamId(id), readPolicy, writePolicy), QuicStreamOrigin.Confirmed)

    /** A stream that arrived in 0-RTT is read only once the handshake completes, unless it is marked replay-safe. */
    @OptIn(QuicEngineSpi::class)
    private fun peerStream(peer: PeerStream): QuicByteStream =
        QuicByteStream(
            QuicStreamId(peer.id),
            CoreStream(this, QuicStreamId(peer.id), readPolicy, writePolicy),
            when (peer.arrival) {
                Arrival.Confirmed -> QuicStreamOrigin.Confirmed
                Arrival.Early -> QuicStreamOrigin.Early(::established)
            },
        )

    /** Lets a client's first flight, held for 0-RTT, leave. */
    fun releaseFirstFlight() = conn.submit(ReleaseFlight(), CallerPlace.HandToLoop)

    /** A caller on a main dispatcher never makes system calls; any other may. */
    internal fun placeOf(context: CoroutineContext): CallerPlace =
        if (context[ContinuationInterceptor] is MainCoroutineDispatcher) CallerPlace.HandToLoop else CallerPlace.MayEnter
}

/** What every core connection requires of a caller's buffers: an address quiche reads in place. */
private val CORE_CAPABILITIES = QuicCapabilities(requiresNativeMemoryBuffers = true)

/** Lets a client's held first flight leave. */
private class ReleaseFlight : ConnWork() {
    override fun run(inside: Inside) = inside.releaseFirstFlight()

    override fun gone(end: ConnEnd) = Unit
}

/** Starts the connection's close inside it. */
private class CloseWork(
    private val error: QuicError,
) : ConnWork() {
    override fun run(inside: Inside) = inside.close(error)

    override fun gone(end: ConnEnd) = Unit
}

/** Writes the flight recording inside the connection. */
private class DumpWork(
    private val sink: TraceSink,
    private val cont: CancellableContinuation<FlightDump>,
) : ConnWork() {
    override fun run(inside: Inside) {
        inside.dump(sink)
        cont.resume(FlightDump.Written)
    }

    override fun gone(end: ConnEnd) = cont.resume(FlightDump.ConnectionEnded)
}
