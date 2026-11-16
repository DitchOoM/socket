package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.applySessionTicketKeys
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.demux.AddressTokens
import com.ditchoom.socket.quic.core.demux.AddressValidation
import com.ditchoom.socket.quic.core.demux.Admission
import com.ditchoom.socket.quic.core.demux.Demux
import com.ditchoom.socket.quic.core.demux.DemuxScratch
import com.ditchoom.socket.quic.core.demux.InitialBacklog
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.demux.ServerHold
import com.ditchoom.socket.quic.core.demux.ServerParts
import com.ditchoom.socket.quic.core.demux.Stateless
import com.ditchoom.socket.quic.core.demux.Tokens
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopIds
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.loop.startLoopThread
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.SelectorPoller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.jdkStackOfThisRuntime
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.quic.writeNullTerminatedString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

/** Everything [coreServe] builds, in order: memory first (so a factory without native memory fails before any socket), then the socket and the loop. */
internal class ServerSetup(
    private val bind: InetSocketAddress,
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
        val sendViews = listOf(memory.jdk(send.storage)) + List(CALLER_LANES) { memory.jdk(lanes[it].batch.storage) }
        val receiveViews = receiveBuffers.map { memory.jdk(it.bytes) }
        val quicheConfig = configured()
        val channel = DatagramChannel.open()
        var bound = false
        try {
            return serve(channel, quicheConfig, limits, send, lanes, receiveBuffers, backlog, scratch, sendViews, receiveViews)
                .also { bound = true }
        } finally {
            if (!bound) {
                channel.close()
                api.configFree(quicheConfig)
            }
        }
    }

    private fun serve(
        channel: DatagramChannel,
        quicheConfig: QuicheConfig,
        limits: BatchLimits,
        send: SendBatch,
        lanes: FixedPool<CallerSend>,
        receiveBuffers: List<ReceiveBuffer>,
        backlog: InitialBacklog,
        scratch: DemuxScratch,
        sendViews: List<com.ditchoom.socket.quic.core.udp.JdkBytes>,
        receiveViews: List<com.ditchoom.socket.quic.core.udp.JdkBytes>,
    ): CoreServer {
        val granted = buffers.applyTo(channel)
        channel.bind(bind)
        channel.configureBlocking(false)
        val boundAddress = channel.localAddress
        if (boundAddress !is InetSocketAddress) {
            raise(Raised.RouteUnresolved(RouteProbeFailure.SourceAddressUnknown), QuicCloseCircumstance.HandshakeFailed)
        }
        val nativeLocal = boundAddress.toNativeSockAddr(factory)
        val local = EncodedSockAddr(nativeLocal.buffer, nativeLocal.address, nativeLocal.length)
        val localKey = boundAddress.toPathKey()
        val tokens =
            when (val validation = config.addressValidation) {
                is AddressValidation.Off -> Tokens.None
                is AddressValidation.Retry ->
                    Tokens.Of(AddressTokens(factory, connectionIdRandom, validation.lifetime.inWholeNanoseconds))
            }
        val hold =
            ServerHold(
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
        val io = wrapIo(NioOneByOne(channel, sendViews, receiveViews, MAX_PEERS_CACHED))
        val loop =
            Loop(
                id = LoopIds.next(),
                name = SERVER_THREAD,
                poller = SelectorPoller(clock, jdkStackOfThisRuntime().scan),
                clock = clock,
                receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { receiveBuffers[it] }, limits),
                freeOnStop = Runnable { hold.release() },
            )
        val socket = SendPort(io, loop.port, CallerLanes.Of(lanes), SocketDiedNotice(), loop.counts, loop.callerCounts)
        val counts = ServerCounts()
        val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
        val edge = ServerEdge(options, factory, readPolicy, writePolicy, accepted)
        val parts =
            ServerParts(
                SlotRules.Default,
                factory,
                config.flight,
                loop.port,
                socket,
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
        loop.place(Endpoint(SocketRef(channel), io, socket, send, Route.Demux(demux), Runnable { loop.close(finished) }, readiness))
        when (driver) {
            LoopDriver.OwnThread -> startLoopThread(loop, SERVER_THREAD)
            // The caller's first pass would place the socket only after that pass's receive: place it now.
            LoopDriver.Caller -> loop.pass(clock.now())
        }
        return CoreServer(boundAddress.port, loop, demux, counts, accepted, SocketReport(granted, boundAddress.port), stopped)
    }

    /** A server config with the certificate, ALPN, options and session ticket keys applied. */
    private fun configured(): QuicheConfig {
        val quicheConfig = api.configNew(QUIC_V1)
        writeNullTerminatedString(tls.certChainPath, factory).use { api.configLoadCertChainFromPemFile(quicheConfig, addressOf(it)) }
        writeNullTerminatedString(tls.privKeyPath, factory).use { api.configLoadPrivKeyFromPemFile(quicheConfig, addressOf(it)) }
        encodeAlpnList(options.alpnProtocols, factory).use { api.configSetApplicationProtos(quicheConfig, addressOf(it), it.remaining()) }
        applyQuicOptions(options, CommonJvmQuicConfigCalls(api, quicheConfig), QuicRole.Server)
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
    }
}
