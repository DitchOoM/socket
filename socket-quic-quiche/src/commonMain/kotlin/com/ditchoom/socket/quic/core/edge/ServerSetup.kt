package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.demux.Admission
import com.ditchoom.socket.quic.core.demux.ChunkMade
import com.ditchoom.socket.quic.core.demux.Demux
import com.ditchoom.socket.quic.core.demux.DemuxScratch
import com.ditchoom.socket.quic.core.demux.InitialBacklog
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.demux.ServerParts
import com.ditchoom.socket.quic.core.demux.Stateless
import com.ditchoom.socket.quic.core.demux.Tokens
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.JdkViewsMade
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopIds
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.loop.OwnBatch
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.loop.jdkViewsOf
import com.ditchoom.socket.quic.core.loop.LoopStarter
import com.ditchoom.socket.quic.core.loop.waitsOf
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.NativePoller
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.PollerOpened
import com.ditchoom.socket.quic.core.ready.jdkPoller
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.HandlerPool
import com.ditchoom.socket.quic.core.sched.HandlerThreads
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.SendRule
import com.ditchoom.socket.quic.core.sched.callerLanesOf
import com.ditchoom.socket.quic.core.sched.laneCount
import com.ditchoom.socket.quic.core.sched.size
import com.ditchoom.socket.quic.core.sched.storages
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.PeerKeys
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.newConnectionIdRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel

/** Everything [coreServe] builds, in order: memory first (so a factory without native memory fails before any socket), then the socket and the loop. */
internal class ServerSetup(
    private val bind: PathKey,
    private val tls: QuicTlsConfig,
    private val options: QuicOptions,
    private val factory: BufferFactory,
    private val api: QuicheApi,
    private val readPolicy: ReadPolicy,
    private val writePolicy: WritePolicy,
    private val config: ServerConfig,
    private val wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
    private val buffers: SocketBuffers,
    private val clock: LoopClock,
    private val driver: LoopDriver,
    private val stack: NetStack,
    private val batching: UdpBatching,
    private val mismatch: MismatchResponse,
    private val sends: SendRule,
    /** Starts the loop's thread when the driver is [LoopDriver.OwnThread]. */
    private val starter: LoopStarter,
) {
    /** The memory this server holds from [factory]: freed by the setup's [SetupOwner] until the loop is built, then by the loop. */
    private val memory = EdgeMemory(factory)

    /** The threads that run the server's application code: started off the loop when its first connection is offered. */
    private val handlers =
        when (driver) {
            LoopDriver.OwnThread ->
                HandlerPool(
                    HandlerThreads.Own(HandlerPool.SERVER_THREADS),
                    SERVER_HANDLER_THREAD,
                    ::poolContained,
                    config.handlerStallBound,
                )
            LoopDriver.Caller -> HandlerPool(HandlerThreads.ByLoopCaller, SERVER_HANDLER_THREAD, ::poolContained, config.handlerStallBound)
        }

    fun start(): CoreServer {
        var owner: SetupOwner = SetupOwner.Allocating(memory)
        try {
            return opened { owner = it }.also { owner = SetupOwner.Served }
        } finally {
            owner.endFailed(api)
        }
    }

    /** Builds the server, telling [moved] each time what the setup opened passes to a new owner. */
    private fun opened(moved: (SetupOwner) -> Unit): CoreServer {
        val limits = BatchLimits(maxDatagrams = MAX_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val callerLimits = BatchLimits(maxDatagrams = CALLER_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val send = SendBatch(memory.native(limits.maxDatagrams * limits.maxDatagramSize), limits)
        val lanes =
            callerLanesOf(sends.laneCount(CALLER_LANES)) { i ->
                CallerSend(i, SendBatch(memory.native(callerLimits.maxDatagrams * MAX_DATAGRAM), callerLimits, lane = i + 1))
            }
        val receiveBuffers = List(limits.maxDatagrams) { ReceiveBuffer(it, memory.native(MAX_DATAGRAM)) }
        val backlog = InitialBacklog(config.admission.backlog) { backlogChunk() }
        val scratch =
            DemuxScratch(
                memory.native(DemuxScratch.FIELDS_BYTES),
                memory.native(DemuxScratch.TOKEN_CAPACITY),
                memory.native(DemuxScratch.TOKEN_CAPACITY),
                memory.native(SCID_BYTES),
            )
        val loopIo =
            when (stack) {
                is NetStack.Jdk ->
                    when (val made = jdkViewsOf(stack, receiveBuffers)) {
                        is JdkViewsMade.Made -> LoopIo.Jdk(made.views)
                        is JdkViewsMade.NotJdkMemory ->
                            raise(Raised.FactoryNotNative(receiveBuffers[0].bytes.owner), QuicCloseCircumstance.HandshakeFailed)
                    }
                is NetStack.Native ->
                    LoopIo.Native(
                        stack.shim,
                        memory.native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE),
                        List(1 + lanes.size) { memory.native(NativeIo.SEND_RECORD * if (it == 0) MAX_BATCH else CALLER_BATCH) },
                    )
            }
        val poller =
            when (stack) {
                is NetStack.Jdk -> jdkPoller(stack)
                is NetStack.Native ->
                    when (val opened = NativePoller.open(stack.shim, READY_EVENTS)) {
                        is PollerOpened.Opened -> opened.poller
                        is PollerOpened.Refused ->
                            raise(Raised.SocketRefused(NativeSocketRefusal(opened.refusal)), QuicCloseCircumstance.HandshakeFailed)
                    }
            }
        moved(SetupOwner.Polled(memory, poller))
        val quicheConfig = api.configNew(QUIC_V1)
        moved(SetupOwner.Configured(memory, poller, quicheConfig))
        configureServer(api, quicheConfig, tls, options, factory, handshake)
        val socket = openStackSocket(loopIo, memory, bind, SocketPeer.Unconnected, buffers, batching, limits.maxDatagrams)
        val socketIo = socket.ioOf(listOf(send.storage) + lanes.storages(), MAX_PEERS_CACHED, PeerKeys.SERVER_SETS) { }
        moved(SetupOwner.Opened(memory, poller, quicheConfig, socketIo.io))
        return serve(socket, socketIo, poller, quicheConfig, limits, send, lanes, receiveBuffers, backlog, scratch, moved)
    }

    private fun serve(
        socket: StackSocket,
        socketIo: SocketIo,
        poller: Poller,
        quicheConfig: QuicheConfig,
        limits: BatchLimits,
        send: SendBatch,
        lanes: CallerLanes,
        receiveBuffers: List<ReceiveBuffer>,
        backlog: InitialBacklog,
        scratch: DemuxScratch,
        /** Told each time what the setup opened passes to a new owner. */
        moved: (SetupOwner) -> Unit,
    ): CoreServer {
        val localKey = socket.local()
        val local = localKey.encodeToNative(factory)
        moved(SetupOwner.Addressed(memory, poller, quicheConfig, socketIo.io, local))
        // The loop alone draws from it: a shared source's lock would let a client thread hold up the receive phase.
        val ids = newConnectionIdRandom()
        val tokens = Tokens.of(config, factory, ids)
        val hold =
            MemoryHold(
                Runnable {
                    when (tokens) {
                        is Tokens.None -> Unit
                        is Tokens.Of -> tokens.tokens.free()
                    }
                    api.configFree(quicheConfig)
                    local.free()
                    memory.freeAll()
                },
            )
        moved(SetupOwner.Held(poller, socketIo.io, hold))
        val io = wrapIo(socketIo.io)
        val loop =
            Loop(
                id = LoopIds.next(),
                name = SERVER_THREAD,
                poller = poller,
                clock = clock,
                receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits),
                freeOnStop = Runnable { hold.founding.release() },
                time = QuicheTime.of(api, clock),
            )
        moved(SetupOwner.Built(loop, socketIo.io))
        val sendPort = SendPort(io, loop.port, lanes, SocketDiedNotice(), loop.counts, loop.callerCounts)
        val counts = ServerCounts()
        val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
        val edge = ServerEdge(options, factory, readPolicy, writePolicy, accepted, counts, HandlerSeats(handlers, loop.handlers))
        val parts =
            ServerParts(
                SlotRules.forWaits(
                    waitsOf(stack),
                    handshake,
                    closeChoiceOf(options),
                    datagramRuleOf(options),
                    keepAliveRuleOf(options),
                    earlyDataRuleOf(options),
                    config.ack,
                    mismatch,
                    options.abortCodes.writeCut,
                ),
                factory,
                config.flight,
                LoopMemory(
                    List(LoopMemory.COPY_SLOTS) { memory.native(MAX_DATAGRAM) },
                    List(LoopMemory.DRAIN_SLOTS) { memory.native(LoopMemory.DRAIN_BYTES) },
                    FlightArena(memory.native(config.flightArenaBytes)),
                ),
                loop.port,
                sendPort,
                clock,
                loop.time,
                Entrant.loopTurn(loop.id, 1),
                quicheConfig,
                local,
                localKey,
            )
        val admission = Admission(api, config.admission, backlog, parts, edge.hooks, hold, ids, counts)
        val demux = Demux(admission, Stateless(api, tokens, ids, localKey, counts), scratch, loop.port, clock, counts, parts.rules.close)
        val stopped = CompletableDeferred<Unit>()
        val finished =
            Runnable {
                accepted.close()
                handlers.stop()
                stopped.complete(Unit)
            }
        val readiness =
            when (driver) {
                LoopDriver.OwnThread -> Readiness.Polled
                LoopDriver.Caller -> Readiness.EveryPass
            }
        loop.place(Endpoint(socketIo.ref, io, sendPort, Route.Demux(demux, OwnBatch(send)), Runnable { loop.close(finished) }, readiness))
        moved(SetupOwner.Placed(loop))
        when (driver) {
            LoopDriver.OwnThread -> {
                starter(loop, SERVER_THREAD)
                moved(SetupOwner.Started(loop))
            }
            // The caller's first pass would place the socket only after that pass's receive: place it now.
            LoopDriver.Caller -> loop.pass(clock.now())
        }
        return CoreServer(localKey.port, loop, demux, counts, accepted, SocketReport(socket.granted(), localKey.port), stopped, handlers)
    }

    /** A chunk of the Initial backlog: at setup, then on the loop thread, which owns [memory] from its start. */
    private fun backlogChunk(): ChunkMade =
        when (val made = memory.more(InitialBacklog.CHUNK_BYTES)) {
            is Addressed.Native -> ChunkMade.Made(made.bytes)
            is Addressed.NotAddressable -> ChunkMade.Refused
        }

    /** The handshake rule every connection of this server is built with. */
    private val handshake = serverHandshakeRule(options)

    private companion object {
        const val QUIC_V1 = 1
        const val MAX_BATCH = 64
        const val CALLER_LANES = 16
        const val CALLER_BATCH = 16
        const val SCID_BYTES = 16
        const val MAX_PEERS_CACHED = 1 shl 16

        /** The ready sockets one native wait reports. */
        const val READY_EVENTS = 64
    }
}
