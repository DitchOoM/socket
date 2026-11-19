package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.ApiQuicConfigCalls
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.clientAlpnOffer
import com.ditchoom.socket.quic.core.conn.AckRule
import com.ditchoom.socket.quic.core.conn.ClientSession
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.conn.SessionScratch
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.edge.ClientParts
import com.ditchoom.socket.quic.core.edge.ClientWiring
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.CoreServer
import com.ditchoom.socket.quic.core.edge.DatagramSize
import com.ditchoom.socket.quic.core.edge.EdgeMemory
import com.ditchoom.socket.quic.core.edge.JdkSocket
import com.ditchoom.socket.quic.core.edge.LoopDriver
import com.ditchoom.socket.quic.core.edge.SocketBound
import com.ditchoom.socket.quic.core.edge.StackSocket
import com.ditchoom.socket.quic.core.edge.TEST_FLIGHT
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.edge.clientHandshakeRule
import com.ditchoom.socket.quic.core.edge.coreServe
import com.ditchoom.socket.quic.core.edge.coreTlsConfig
import com.ditchoom.socket.quic.core.edge.offerPlanned
import com.ditchoom.socket.quic.core.edge.sessionPlan
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.JdkViewsMade
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopIds
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.loop.LoopSend
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.RunningLoop
import com.ditchoom.socket.quic.core.loop.SpareMade
import com.ditchoom.socket.quic.core.loop.jdkViewsOf
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.jdkStackOfThisRuntime
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.GrantedBuffers
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.toNativeSockAddr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

/** The replayed client: the parts a connect builds, on a loop the replay drives on its virtual clock. */
internal class ReplayClient(
    val connection: CoreConnection,
    val parts: ClientParts,
    val loop: Loop,
    val scope: CoroutineScope,
    /** The client socket's address, as the server sees it. */
    val local: PathKey,
    /** The session ticket the client offered, and whether its first flight waits for 0-RTT. */
    val offer: Offer,
)

/** Reads and writes wait at most this long: on virtual time, as long as any replay runs. */
internal val REPLAY_DEADLINE = 1.hours

internal fun replayOptions(conditions: Conditions): QuicOptions =
    QuicOptions(
        alpnProtocols = listOf(ALPN),
        verifyPeer = false,
        idleTimeout = conditions.idleTimeout,
        handshakePacing = conditions.handshakePacing,
    )

/** A core server on 127.0.0.1 whose loop the replay drives, over [io]. */
internal suspend fun replayServer(
    api: QuicheApi,
    options: QuicOptions,
    factory: BufferFactory,
    io: DatagramBatchIo,
    clock: VirtualClock,
    tls: QuicTlsConfig = coreTlsConfig(),
    config: ServerConfig = ServerConfig(),
): CoreServer =
    coreServe(
        mismatch = MismatchResponse.Fail,
        bind = InetSocketAddress(LOCALHOST, 0).toPathKey(),
        tls = tls,
        options = options,
        factory = factory,
        api = api,
        readPolicy = ReadPolicy.Bounded(REPLAY_DEADLINE),
        writePolicy = WritePolicy.Bounded(REPLAY_DEADLINE),
        wrapIo = { io },
        clock = clock,
        driver = LoopDriver.Caller,
        config = config,
        // Like the replay's client, no discovery: its probe on a moved path delays the first echo past the walks' bounds.
        datagramSize = DatagramSize.Configured,
    )

/**
 * A core client of [server]'s port, built as a connect builds one ([ClientParts]), but on a loop with no thread that
 * reads its socket every pass and keeps [clock]'s time, over [io]. Its source connection id is drawn from [seed].
 */
internal fun replayClient(
    api: QuicheApi,
    options: QuicOptions,
    factory: BufferFactory,
    io: DatagramBatchIo,
    clock: VirtualClock,
    serverPort: Int,
    seed: Long,
    link: LinkClass,
    ack: AckRule = AckRule.UntilPassEnds,
): ReplayClient {
    val memory = EdgeMemory(factory)
    val limits = BatchLimits(maxDatagrams = BATCH, maxDatagramSize = DATAGRAM)
    val buffers = List(BATCH) { ReceiveBuffer(it, memory.native(DATAGRAM)) }
    val loop =
        Loop(
            id = LoopIds.next(),
            name = "condition-replay-client",
            poller = FakePoller(),
            clock = clock,
            receive = ReceiveBatch(FixedPool(BATCH) { buffers[it] }, limits),
            freeOnStop = Runnable { },
        )
    val peerInet = InetSocketAddress(LOCALHOST, serverPort)
    val channel = DatagramChannel.open()
    channel.bind(InetSocketAddress(LOCALHOST, 0)).connect(peerInet)
    channel.configureBlocking(false)
    val local = channel.localAddress as InetSocketAddress
    val config = api.configNew(QUIC_V1)
    encodeAlpnList(clientAlpnOffer(options), factory).let { api.configSetApplicationProtos(config, addressOf(it), it.remaining()) }
    applyQuicOptions(options, ApiQuicConfigCalls(api, config), QuicRole.Client)
    val handshake = clientHandshakeRule(options, link)
    handshake.applyTo(api, config)
    val peer = encoded(peerInet, factory)
    val localAddress = encoded(local, factory)
    val name =
        factory.allocate(SERVER_NAME.length + 1).apply {
            writeString(SERVER_NAME, Charset.UTF8)
            writeByte(0)
            resetForRead()
        }
    val scid = generateScid(factory, Random(seed))
    val hex = (scid.position() until scid.limit()).joinToString("") { "%02x".format(scid.get(it)) }
    val handle =
        api.connect(
            addressOf(name),
            SERVER_NAME.length,
            addressOf(scid),
            QUIC_ISSUED_CONN_ID_LEN,
            localAddress.address,
            localAddress.length,
            peer.address,
            peer.length,
            config,
        )
    // The connection keeps nothing of the config: quiche copied what it needs at connect.
    api.configFree(config)
    name.freeNativeMemory()
    scid.freeNativeMemory()
    val plan = sessionPlan(options, SERVER_NAME, serverPort)
    val planned = offerPlanned(QuicheConnRef(api, handle), plan, factory)
    val offer = planned.offer
    val scope = CoroutineScope(SupervisorJob())
    val loopIo = LoopIo.Jdk((jdkViewsOf(jdkStackOfThisRuntime(), buffers) as JdkViewsMade.Made).views)
    val wiring =
        ClientWiring(
            api,
            handle,
            options,
            memory,
            RunningLoop(
                loop,
                loopIo,
                replayLoopMemory(memory),
                MemoryHold(Runnable { }),
                WaitResolution(1_000_000L),
                LoopSend(replayBatch(memory)) { SpareMade.Made(replayBatch(memory)) },
                FixedPool(
                    1,
                ) { CallerSend(it, SendBatch(memory.native(DATAGRAM * LANE_DATAGRAMS), BatchLimits(LANE_DATAGRAMS, DATAGRAM), lane = 1)) },
                FixedPool(1) { SessionScratch(it, memory.native(ClientSession.TICKET_BYTES)) },
            ),
            StackSocket.Jdk(JdkSocket(channel), loopIo, SocketBound(local.toPathKey(), GrantedBuffers(SocketBuffers.Default, 0, 0))),
            { io },
            peerInet.toPathKey(),
            ReadPolicy.Bounded(REPLAY_DEADLINE),
            WritePolicy.Bounded(REPLAY_DEADLINE),
            scope,
            TEST_FLIGHT,
            handshake,
            clock,
            Readiness.EveryPass,
            // Not the stream the client's first id came from: the ids it hands out must differ from it.
            Random(seed.inv()),
            offer,
            plan.home,
            planned.kept,
            plan.tokens,
            MismatchResponse.Fail,
            ack,
        )
    val parts = ClientParts(wiring, peer, localAddress)
    val connection =
        parts.connection(
            factory,
            SocketAddress.ofLiteral(LOCALHOST, serverPort),
            QuicConnectionIdentity(QuicSessionId(hex), QuicWireConnectionId.Known(hex)),
        )
    return ReplayClient(connection, parts, loop, scope, local.toPathKey(), offer)
}

/** The copy slots and flight arena of a replay client's loop, freed with the client's memory. */
private fun replayBatch(memory: EdgeMemory): SendBatch =
    SendBatch(memory.native(DATAGRAM * BATCH_DATAGRAMS), BatchLimits(BATCH_DATAGRAMS, DATAGRAM))

private const val BATCH_DATAGRAMS = 64
private const val LANE_DATAGRAMS = 8

private fun replayLoopMemory(memory: EdgeMemory): LoopMemory =
    LoopMemory(
        List(LoopMemory.COPY_SLOTS) { memory.native(DATAGRAM) },
        List(LoopMemory.DRAIN_SLOTS) { memory.native(LoopMemory.DRAIN_BYTES) },
        FlightArena(memory.native(LoopMemory.ARENA_BYTES)),
    )

private fun encoded(
    address: InetSocketAddress,
    factory: BufferFactory,
): EncodedSockAddr {
    val native = address.toNativeSockAddr(factory)
    return EncodedSockAddr(native.buffer, native.address, native.length)
}

/** The host every replayed socket binds: the link, not the kernel, carries the datagrams. */
internal const val LOCALHOST = "127.0.0.1"
private const val ALPN = "test"
private const val SERVER_NAME = "localhost"
private const val QUIC_V1 = 1
private const val BATCH = 64
private const val DATAGRAM = 1500
