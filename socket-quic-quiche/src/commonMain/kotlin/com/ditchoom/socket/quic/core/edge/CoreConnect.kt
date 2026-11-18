@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressFamily
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.networkId
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
import com.ditchoom.socket.quic.core.conn.ClientTokens
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.loop.Placement
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.hostLiteral
import com.ditchoom.socket.quic.hostSockAddrLayout
import com.ditchoom.socket.quic.nameQlog
import com.ditchoom.socket.quic.resolveNetworkMonitor
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.quic.wireClientConnectivityTap
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Connects to [host]:[port] (an address literal) on the run-to-completion core and returns once the handshake
 * completed. Setup that opens files, sockets and native libraries runs on the [setupLane], off the caller's thread, so a
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
): CoreClient {
    val peerHost = peerHostFor(host)
    val unstarted = ClientSetup.Unstarted(EdgeMemory(factory))
    val sized = options.sizedBy(datagramSize)
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
                    handedOver = { setup = it },
                )
            }
        return started.established(sized.resumption, timeout).also { setup = ClientSetup.Established }
    } finally {
        when (val reached = setup) {
            is ClientSetup.Unstarted -> reached.memory.freeAll()
            is ClientSetup.Started -> reached.started.abandon()
            is ClientSetup.Established -> Unit
        }
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
    /** Told once the connection, not the connect, owns [memory]: right before the connection starts. */
    handedOver: (ClientSetup.Started) -> Unit,
): StartedClient {
    val peer = UdpSocket.resolve(peerHost, port)
    val peerKey = peer.toPathKey()
    val codec = SocketAddressCodec(hostSockAddrLayout)
    val route = UdpSocketChannelFactory(peer, codec, factory, factory, MAX_DATAGRAM, LocalEndpointSupport.Bindable)
    val running =
        when (val placed = loops.place(factory)) {
            is Placement.Placed -> placed.running
            is Placement.SetClosed -> raise(Raised.LoopsClosed, QuicCloseCircumstance.HandshakeFailed)
            is Placement.NotJdkMemory -> raise(Raised.FactoryNotNative(factory.allocate(1)), QuicCloseCircumstance.HandshakeFailed)
            is Placement.PollerRefused ->
                raise(
                    Raised.SocketRefused(NativeSocketRefusal(placed.refusal)),
                    QuicCloseCircumstance.HandshakeFailed,
                )
        }
    val socket =
        when (ride) {
            is ClientRide.OwnSocket -> {
                val bindHost =
                    when (val source = routeSource(route)) {
                        is RouteSource.Resolved -> source.host
                        is RouteSource.PlatformAssigned -> anyHostFor(peer)
                        is RouteSource.Unresolved -> raise(Raised.RouteUnresolved(source.reason), QuicCloseCircumstance.HandshakeFailed)
                    }
                val bind = UdpSocket.resolve(bindHost, 0).toPathKey()
                openStackSocket(running.io, memory, bind, SocketPeer.Connected(peerKey), buffers, batching, CLIENT_BATCH)
            }
            is ClientRide.NetworkFramework -> withTimeout(timeout) { ride.connector.connect(host, port) }
        }
    val local = socket.local()
    val monitor = resolveNetworkMonitor(options.networkMonitor)
    val handshake = clientHandshakeRule(options, linkClassOf(monitor.state.value.networkId))
    val scid = generateScid(factory)
    val scidHex = hex(scid)
    val peerSockAddr = codec.encodeToNative(peer, factory)
    val localSockAddr = local.encodeToNative(factory)
    val name = nullTerminated(serverName, factory)
    val handle =
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
    name.freeNativeMemory()
    scid.freeNativeMemory()
    val plan = sessionPlan(options, serverName, port, tokens)
    val planned = offerPlanned(QuicheConnRef(api, handle), plan, factory)
    val offer = planned.offer
    val scope = CoroutineScope(SupervisorJob())
    // Until the connection starts nothing has been sent: a step that fails before then hands the ticket back.
    val (built, connection) =
        planned.kept.backIfThrows {
            val parts =
                ClientParts(
                    ClientWiring(
                        api,
                        handle,
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
                        readiness = socket.readiness(),
                        offer = offer,
                        tickets = plan.home,
                        kept = planned.kept,
                        tokens = plan.tokens,
                    ),
                    peerSockAddr,
                    localSockAddr,
                )
            val made = parts.connection(factory, peer, QuicConnectionIdentity(QuicSessionId(scidHex), QuicWireConnectionId.Known(scidHex)))
            wireClientConnectivityTap(options, parts.capture, made, monitor)
            api.nameQlog(handle, parts.capture, QuicRole.Client, QuicSessionId(scidHex))
            parts to made
        }
    val started = StartedClient(CoreClient(connection, built.conn, running.loop, SocketReport(socket.granted(), local.port)), offer)
    // Placing the endpoint hands the memory to the loop, whose release of the connection frees it, whatever happens next.
    handedOver(ClientSetup.Started(started))
    built.start()
    scope.feedNetwork(monitor, connection, built, { routeSource(route) }, local.hostLiteral(), onMoveOf(options.migration))
    return started
}

private fun hex(buffer: com.ditchoom.buffer.PlatformBuffer): String {
    val out = StringBuilder(buffer.remaining() * 2)
    for (i in buffer.position() until buffer.limit()) {
        val b = buffer.get(i).toInt() and 0xFF
        out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

/** The wildcard of [peer]'s family: an IPv6 peer needs an IPv6 socket on the native stack. */
private fun anyHostFor(peer: SocketAddress): String = if (peer.family == AddressFamily.IPv6) "::" else "0.0.0.0"

/** The most datagrams one batch of a client socket carries. */
internal const val CLIENT_BATCH = 64
internal const val MAX_DATAGRAM = 1500

/** A client connection's flight recording unless the connect asks for another size: enough for a walk's handshakes. */
internal val CLIENT_FLIGHT = FlightSize(records = 2048)
