package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.ApiQuicConfigCalls
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.applySessionTicketKeys
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.demux.AddressTokens
import com.ditchoom.socket.quic.core.demux.AddressValidation
import com.ditchoom.socket.quic.core.demux.Admission
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
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.loop.jdkViewsOf
import com.ditchoom.socket.quic.core.loop.startLoop
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
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.encodeToNative
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel

/** Who frees the poller and the quiche config a server's setup opened: the setup until its loop is built, then the loop. */
private enum class SetupOwner { Setup, Loop }

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
    private val memory: EdgeMemory,
    private val stack: NetStack,
    private val batching: UdpBatching,
) {
    fun start(): CoreServer {
        val limits = BatchLimits(maxDatagrams = MAX_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val callerLimits = BatchLimits(maxDatagrams = CALLER_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val send = SendBatch(memory.native(limits.maxDatagrams * limits.maxDatagramSize), limits)
        val lanes =
            FixedPool(CALLER_LANES) { i ->
                CallerSend(i, SendBatch(memory.native(callerLimits.maxDatagrams * MAX_DATAGRAM), callerLimits, lane = i + 1))
            }
        val receiveBuffers = List(limits.maxDatagrams) { ReceiveBuffer(it, memory.native(MAX_DATAGRAM)) }
        val backlog = InitialBacklog(List(config.admission.backlog) { memory.native(MAX_DATAGRAM) })
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
                is NetStack.Native -> LoopIo.Native(stack.shim, memory.native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))
            }
        val poller =
            when (stack) {
                is NetStack.Jdk -> jdkPoller(clock, stack)
                is NetStack.Native ->
                    when (val opened = NativePoller.open(stack.shim, clock, READY_EVENTS)) {
                        is PollerOpened.Opened -> opened.poller
                        is PollerOpened.Refused ->
                            raise(Raised.SocketRefused(NativeSocketRefusal(opened.refusal)), QuicCloseCircumstance.HandshakeFailed)
                    }
            }
        val quicheConfig = configured()
        var owner = SetupOwner.Setup
        try {
            val socket = openStackSocket(loopIo, memory, bind, SocketPeer.Unconnected, buffers, batching, limits.maxDatagrams)
            val sends = listOf(send.storage) + List(CALLER_LANES) { lanes[it].batch.storage }
            return serve(
                socket,
                socket.ioOf(memory, sends, limits.maxDatagrams, MAX_PEERS_CACHED) { },
                poller,
                quicheConfig,
                limits,
                send,
                lanes,
                receiveBuffers,
                backlog,
                scratch,
                handedOver = Runnable { owner = SetupOwner.Loop },
            )
        } finally {
            // Once the loop is built its stop frees both: freeing them here too would free them twice.
            when (owner) {
                SetupOwner.Setup -> {
                    poller.close()
                    api.configFree(quicheConfig)
                }
                SetupOwner.Loop -> Unit
            }
        }
    }

    private fun serve(
        socket: StackSocket,
        socketIo: SocketIo,
        poller: Poller,
        quicheConfig: QuicheConfig,
        limits: BatchLimits,
        send: SendBatch,
        lanes: FixedPool<CallerSend>,
        receiveBuffers: List<ReceiveBuffer>,
        backlog: InitialBacklog,
        scratch: DemuxScratch,
        /** Runs once the loop owns [poller] and its memory hold owns [quicheConfig]. */
        handedOver: Runnable,
    ): CoreServer {
        val localKey = socket.local()
        val local = localKey.encodeToNative(factory)
        val tokens =
            when (val validation = config.addressValidation) {
                is AddressValidation.Off -> Tokens.None
                is AddressValidation.Retry ->
                    Tokens.Of(AddressTokens(factory, connectionIdRandom, validation.lifetime.inWholeNanoseconds))
            }
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
        val io = wrapIo(socketIo.io)
        val loop =
            Loop(
                id = LoopIds.next(),
                name = SERVER_THREAD,
                poller = poller,
                clock = clock,
                receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits),
                freeOnStop = Runnable { hold.release() },
            )
        handedOver.run()
        val sendPort = SendPort(io, loop.port, CallerLanes.Of(lanes), SocketDiedNotice(), loop.counts, loop.callerCounts)
        val counts = ServerCounts()
        val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
        val edge = ServerEdge(options, factory, readPolicy, writePolicy, accepted)
        val parts =
            ServerParts(
                SlotRules.forWaits(waitsOf(stack), handshake, closeRuleOf(options), earlyDataRuleOf(options)),
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
                Entrant.loopTurn(loop.id, 1),
                quicheConfig,
                local,
                localKey,
            )
        val admission = Admission(api, config.admission, backlog, parts, edge.hooks, hold, connectionIdRandom, counts)
        val demux = Demux(api, admission, Stateless(api, tokens, connectionIdRandom, localKey, counts), scratch, loop.port, clock, counts)
        val stopped = CompletableDeferred<Unit>()
        val finished =
            Runnable {
                accepted.close()
                stopped.complete(Unit)
            }
        val readiness =
            when (driver) {
                LoopDriver.OwnThread -> Readiness.Polled
                LoopDriver.Caller -> Readiness.EveryPass
            }
        loop.place(Endpoint(socketIo.ref, io, sendPort, send, Route.Demux(demux), Runnable { loop.close(finished) }, readiness))
        when (driver) {
            LoopDriver.OwnThread -> startLoop(loop, SERVER_THREAD)
            // The caller's first pass would place the socket only after that pass's receive: place it now.
            LoopDriver.Caller -> loop.pass(clock.now())
        }
        return CoreServer(localKey.port, loop, demux, counts, accepted, SocketReport(socket.granted(), localKey.port), stopped)
    }

    /** The handshake rule every connection of this server is built with. */
    private val handshake = serverHandshakeRule(options)

    /** A server config with the certificate, ALPN, options, handshake rule and session ticket keys applied. */
    private fun configured(): QuicheConfig {
        val quicheConfig = api.configNew(QUIC_V1)
        nullTerminated(tls.certChainPath, factory).use { api.configLoadCertChainFromPemFile(quicheConfig, addressOf(it)) }
        nullTerminated(tls.privKeyPath, factory).use { api.configLoadPrivKeyFromPemFile(quicheConfig, addressOf(it)) }
        encodeAlpnList(options.alpnProtocols, factory).use { api.configSetApplicationProtos(quicheConfig, addressOf(it), it.remaining()) }
        applyQuicOptions(options, ApiQuicConfigCalls(api, quicheConfig), QuicRole.Server)
        handshake.applyTo(api, quicheConfig)
        api.applySessionTicketKeys(quicheConfig, tls.sessionTicketKeys, factory)
        return quicheConfig
    }

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
