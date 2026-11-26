@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.use
import com.ditchoom.socket.networkId
import com.ditchoom.socket.quic.HandshakeBudget
import com.ditchoom.socket.quic.LocalEndpointSupport
import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.UdpSocketChannelFactory
import com.ditchoom.socket.quic.clientAlpnOffer
import com.ditchoom.socket.quic.core.conn.AccessGuard
import com.ditchoom.socket.quic.core.conn.ClientTokens
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.conn.accessGuardOfThisPlatform
import com.ditchoom.socket.quic.core.conn.probing
import com.ditchoom.socket.quic.core.conn.sendsAccess
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.loop.Placement
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.hostLiteral
import com.ditchoom.socket.quic.hostSockAddrLayout
import com.ditchoom.socket.quic.keyLogDirectory
import com.ditchoom.socket.quic.nameTrafficSecretsLog
import com.ditchoom.socket.quic.resolveNetworkMonitor
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.quic.traceRecorderFor
import com.ditchoom.socket.quic.wireClientConnectivityTap
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Connects to [host]:[port] (an address literal) on the run-to-completion core and returns once the handshake
 * completed and the server passed [verifyServer]. Setup that opens files, sockets and native libraries runs on the [setupLane], off the caller's thread, so a
 * caller on a main dispatcher is never blocked; the handshake wait parks on the caller's own context. The connection
 * owns its socket, bound to the address the route to the peer leaves from, and shares a loop thread, the one of [loops]
 * with the fewest connections; the socket ends with the connection.
 * [wrapIo] may wrap the socket's batch interface, which a test uses to see which thread sends and receives;
 * [routeSource] answers where the route to the peer leaves from, which a test uses to move it.
 */
internal suspend fun coreConnect(
    host: String,
    port: Int,
    serverName: String,
    options: QuicOptions,
    factory: BufferFactory,
    api: QuicheApi,
    timeout: Duration,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
    loops: LoopSet = LoopSet.shared,
    buffers: SocketBuffers = SocketBuffers.Default,
    flight: FlightSize = CLIENT_FLIGHT,
    routeSource: suspend (UdpSocketChannelFactory) -> RouteSource = { it.routeSourceAddress() },
    batching: UdpBatching = options.udpBatching,
    ride: ClientRide = clientRideOfThisPlatform,
    datagramSize: DatagramSize = DatagramSize.Probed,
    tokens: ClientTokens = ClientTokens.NotKept,
    mismatch: MismatchResponse = MismatchResponse.Unpaced,
    access: AccessGuard = accessGuardOfThisPlatform,
): CoreClient {
    // Before the setup lane, a socket or a file: a connect whose option values cannot be honoured opens nothing.
    verdictOn(options).orRefuse()
    // A session whose protocol the options do not list fails here too, before a route is asked for.
    clientAlpnOffer(options)
    val began = TimeSource.Monotonic.markNow()
    val peerHost = peerHostFor(host)
    val unstarted = ClientSetup.Unstarted(EdgeMemory(factory))
    val sized = ride.optionsFor(options.sizedBy(datagramSize))
    // Read once the lane is left: a caller cancelled meanwhile never sees the started client, but must end it.
    var setup: ClientSetup = unstarted
    try {
        val started =
            OnSetupLane.run {
                connectOnLane(
                    host,
                    peerHost,
                    port,
                    serverName,
                    sized,
                    unstarted.memory,
                    factory,
                    api,
                    timeout,
                    readPolicy,
                    writePolicy,
                    wrapIo,
                    loops,
                    buffers,
                    flight,
                    routeSource,
                    batching,
                    ride,
                    tokens,
                    mismatch,
                    access,
                    moved = { setup = it },
                )
            }
        val client = started.established(serverName, sized, timeout, HandshakeBudget(timeout, began))
        return client.also { setup = ClientSetup.Established }
    } finally {
        setup.endFailed()
    }
}

/** Everything [coreConnect] does that opens a socket, a file or a native library, on the [setupLane]. */
private suspend fun OnSetupLane.connectOnLane(
    host: String,
    peerHost: String,
    port: Int,
    serverName: String,
    options: QuicOptions,
    memory: EdgeMemory,
    factory: BufferFactory,
    api: QuicheApi,
    timeout: Duration,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
    loops: LoopSet,
    buffers: SocketBuffers,
    flight: FlightSize,
    routeSource: suspend (UdpSocketChannelFactory) -> RouteSource,
    batching: UdpBatching,
    ride: ClientRide,
    tokens: ClientTokens,
    mismatch: MismatchResponse,
    access: AccessGuard,
    /** Told each time what the setup made passes to a new releaser; last, right before the connection starts. */
    moved: (ClientSetup) -> Unit,
): StartedClient {
    // The application's trace factory runs here, on the setup lane and not on the caller's thread, and before anything opens.
    val capture = traceRecorderFor(options)
    val keyLogDirectory = keyLogDirectory()
    val peer = UdpSocket.resolve(peerHost, port)
    val peerKey = peer.toPathKey()
    val codec = SocketAddressCodec(hostSockAddrLayout)
    val route = UdpSocketChannelFactory(peer, codec, factory, factory, MAX_DATAGRAM, LocalEndpointSupport.Bindable)
    val running =
        when (val placed = loops.place(factory, api)) {
            is Placement.Placed -> placed.running
            is Placement.SetClosed -> raise(Raised.LoopsClosed, QuicCloseCircumstance.HandshakeFailed)
            is Placement.NotJdkMemory -> raise(Raised.FactoryNotNative(factory.allocate(1)), QuicCloseCircumstance.HandshakeFailed)
            is Placement.PollerRefused ->
                raise(
                    Raised.SocketRefused(NativeSocketRefusal(placed.refusal)),
                    QuicCloseCircumstance.HandshakeFailed,
                )
            is Placement.ThreadRefused -> loopThreadRefused(placed.thrown.throwable)
        }
    val seated = ClientSetup.Seated(memory, running.loop)
    moved(seated)
    val socket =
        when (ride) {
            is ClientRide.OwnSocket -> {
                val turn = access.probing(peerKey)
                val bindHost =
                    when (val source = routeSource(route)) {
                        is RouteSource.Resolved -> source.host
                        is RouteSource.PlatformAssigned -> anyHostFor(peer)
                        is RouteSource.Unresolved ->
                            raise(
                                unresolved(source.reason, peerKey, access, turn),
                                QuicCloseCircumstance.HandshakeFailed,
                            )
                    }
                val bind = UdpSocket.resolve(bindHost, 0).toPathKey()
                openStackSocket(running.io, memory, bind, SocketPeer.Connected(peerKey), buffers, batching, CLIENT_BATCH)
            }
            is ClientRide.NetworkFramework -> withTimeout(timeout) { ride.connector.connect(host, port) }
            is ClientRide.Lent -> ride.socketTo(peer, peerKey, factory)
        }
    val opened = ClientSetup.Opened(seated, socket)
    moved(opened)
    val local = socket.local()
    val monitor = resolveNetworkMonitor(options.networkMonitor)
    val handshake = clientHandshakeRule(options, linkClassOf(monitor.state.value.networkId))
    val peerSockAddr = codec.encodeToNative(peer, factory)
    moved(ClientSetup.PeerEncoded(opened, peerSockAddr))
    val localSockAddr = local.encodeToNative(factory)
    val encoded = ClientSetup.Encoded(opened, peerSockAddr, localSockAddr)
    moved(encoded)
    val (scidHex, handle) =
        generateScid(factory).use { scid ->
            hex(scid) to
                nullTerminated(serverName, factory).use { name ->
                    withClientConfig(api, options, handshake, factory) { shared ->
                        api.connect(
                            addressOf(name),
                            serverName.length,
                            addressOf(scid),
                            QUIC_ISSUED_CONN_ID_LEN,
                            localSockAddr.address,
                            localSockAddr.length,
                            peerSockAddr.address,
                            peerSockAddr.length,
                            shared.config,
                        )
                    }
                }
        }
    val quiche = QuicheConnRef(api, handle)
    moved(ClientSetup.Connected(encoded, quiche))
    val plan = sessionPlan(options, serverName, port, tokens)
    val planned = offerPlanned(quiche, plan, factory)
    val offer = planned.offer
    val scope = CoroutineScope(SupervisorJob())
    // Until the connection is built nothing can be sent: a step that fails before then hands the ticket back. From then on
    // the connection's own end hands it back, if it never sent.
    val built =
        planned.kept.backIfThrows {
            ClientParts(
                ClientWiring(
                    api,
                    handle,
                    QuicSessionId(scidHex),
                    options,
                    memory,
                    running,
                    socket,
                    wrapIo,
                    peerKey,
                    readPolicy,
                    writePolicy,
                    scope,
                    flight,
                    handshake,
                    access.sendsAccess(peerKey),
                    clock = running.loop.clock,
                    readiness = socket.readiness(),
                    offer = offer,
                    tickets = plan.home,
                    kept = planned.kept,
                    tokens = plan.tokens,
                    mismatch = mismatch,
                    capture = capture,
                    silence = ride.silenceOn(options.migration, monitor),
                ),
                peerSockAddr,
                localSockAddr,
            )
        }
    moved(ClientSetup.Built(built))
    val connection = built.connection(factory, peer, QuicConnectionIdentity(QuicSessionId(scidHex), QuicWireConnectionId.Known(scidHex)))
    wireClientConnectivityTap(options, built.capture, connection, monitor)
    api.nameTrafficSecretsLog(handle, built.capture, QuicRole.Client, keyLogDirectory) { QuicSessionId(scidHex) }
    val started =
        StartedClient(
            CoreClient(connection, built.conn, running.loop, socket.report(local.port), built),
            offer,
            ServerCheck(connection, built.conn, built.chain),
        )
    // Placing the endpoint hands the memory to the loop, whose release of the connection frees it, whatever happens next.
    moved(ClientSetup.Started(started))
    built.start()
    ride.followsNetwork {
        scope.feedNetwork(monitor, connection, built, { routeSource(route) }, local.hostLiteral(), onMoveOf(options.migration))
    }
    return started
}

/** The wildcard of [peer]'s family: an IPv6 peer needs an IPv6 socket on the native stack. */
private fun anyHostFor(peer: SocketAddress): String = if (peer.family == AddressFamily.IPv6) "::" else "0.0.0.0"

/** The most datagrams one batch of a client socket carries. */
internal const val CLIENT_BATCH = 64
internal const val MAX_DATAGRAM = 1500

/** A client connection's flight recording unless the connect asks for another size: enough for a walk's handshakes. */
internal val CLIENT_FLIGHT = FlightSize(records = 2048)
