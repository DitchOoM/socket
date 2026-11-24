@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.demux.Demux
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.sched.HandlerPool
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.sendRuleOfThisRuntime
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Who runs a server's [Loop]. */
internal enum class LoopDriver {
    /** A dedicated platform thread, started with the server. */
    OwnThread,

    /** The caller, which makes every pass itself on a clock of its own: a server on virtual time, for tests. */
    Caller,
}

/**
 * A server on the run-to-completion core: one loop thread over one unconnected socket, serving every connection it
 * accepted. An established connection is offered through [accept] and [connections] as the same [CoreConnection] a
 * client uses, so streams, callers in place, typed exceptions and failure tiers behave alike on both sides.
 */
internal class CoreServer(
    /** The port the socket is bound to. */
    val port: Int,
    val loop: Loop,
    val parts: Demux,
    val counts: ServerCounts,
    private val accepted: Channel<CoreConnection>,
    /** The socket's granted buffer sizes, and its drop count on demand. */
    val socket: SocketReport,
    private val stopped: CompletableDeferred<Unit>,
    /** The threads its connections' application code runs on. */
    val handlers: HandlerPool,
) {
    private val closing = CloseCell(ShutServer(parts, stopped))

    /**
     * The next established connection; a closed-channel exception once the server stopped. Each connection is offered from
     * its own lane of the server's pool, so connections are accepted in an order that may differ from the one they were
     * established in.
     */
    suspend fun accept(): CoreConnection = accepted.receive()

    /** The next established connection, or the channel's closed result once the server stopped. */
    suspend fun next(): ChannelResult<CoreConnection> = accepted.receiveCatching()

    fun connections(): Flow<CoreConnection> = accepted.consumeAsFlow()

    /**
     * Stops accepting, closes every connection with its CONNECTION_CLOSE sent, and returns once the loop has stopped and
     * closed its socket. The loop owns the socket, so nothing here waits for a receive.
     */
    suspend fun close() {
        closing.begin(loop.port)
        stopped.await()
    }
}

private enum class Closing { Not, Asked }

/** Whether a close was asked for: the first asker hands the loop its shutdown, later ones only wait. */
@Published
private class CloseCell(
    private val shut: LoopWork,
) {
    private val state = AtomicReference(Closing.Not)

    fun begin(port: LoopPort) {
        if (state.compareAndSet(Closing.Not, Closing.Asked)) port.submit(shut)
    }
}

/** Loop work: the server's owner closes it. */
private class ShutServer(
    private val demux: Demux,
    private val stopped: CompletableDeferred<Unit>,
) : LoopWork() {
    override fun run(port: LoopPort) = demux.beginShutdown()

    // The loop had already stopped and ended its connections with it.
    override fun gone(end: ConnEnd) {
        stopped.complete(Unit)
    }
}

/**
 * Binds [bind] and serves on it with the certificate of [tls]. Setup that touches files, sockets and native libraries
 * runs on the [setupLane], off the caller's thread. [wrapIo] may wrap the socket's batch interface, which a test uses
 * to see what the loop sends and receives.
 */
internal suspend fun coreServe(
    bind: PathKey,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
    api: QuicheApi,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    config: ServerConfig = ServerConfig(),
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
    buffers: SocketBuffers = SocketBuffers.Default,
    clock: LoopClock = MonotonicClock,
    driver: LoopDriver = LoopDriver.OwnThread,
    stack: NetStack = netStackOfThisRuntime,
    batching: UdpBatching = options.udpBatching,
    datagramSize: DatagramSize = DatagramSize.Probed,
    mismatch: MismatchResponse = MismatchResponse.Unpaced,
    sends: SendRule = sendRuleOfThisRuntime,
): CoreServer {
    // Before the setup lane, a socket or a file: a serve whose option values cannot be honoured opens nothing.
    verdictOn(options).orRefuse()
    return withContext(setupLane) {
        ServerSetup(
            bind,
            tls,
            options.sizedBy(datagramSize),
            factory,
            api,
            readPolicy,
            writePolicy,
            config,
            wrapIo,
            buffers,
            clock,
            driver,
            stack,
            batching,
            mismatch,
            sends,
        ).start()
    }
}

/** The name of the server loop's thread. */
internal const val SERVER_THREAD = "quic-core-server"

/** The prefix of the names of a server's handler threads; the thread's number follows. */
internal const val SERVER_HANDLER_THREAD = "quic-core-server-handler"
