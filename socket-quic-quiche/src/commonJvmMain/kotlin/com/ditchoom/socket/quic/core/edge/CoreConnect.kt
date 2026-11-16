@file:OptIn(com.ditchoom.socket.quic.InternalQuicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.LocalEndpointSupport
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.UdpSocketChannelFactory
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.loop.Placement
import com.ditchoom.socket.quic.core.loop.RunningLoop
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReadAddress
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketReport
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.encodeToNative
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.loadTrustAnchors
import com.ditchoom.socket.quic.resolveNetworkMonitor
import com.ditchoom.socket.quic.wireClientConnectivityTap
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

/** A connected [connection] with the [conn] and the shared [loop] under it, for what measures them. */
internal class CoreClient(
    val connection: CoreConnection,
    val conn: Conn,
    val loop: Loop,
    /** The socket's granted buffer sizes, and its drop count on demand. */
    val socket: SocketReport,
)

/**
 * Connects to [host]:[port] (an address literal) on the run-to-completion core and returns once the handshake
 * completed. Setup that opens files, sockets and native libraries runs off the caller's thread, so a caller on a main
 * dispatcher is never blocked. The connection owns its socket, bound to the address the route to the peer leaves
 * from, and shares a loop thread, the one of [loops] with the fewest connections; the socket ends with the connection.
 * [wrapIo] may wrap the socket's batch interface, which a test uses to see which thread sends and receives.
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
): CoreClient =
    withContext(Dispatchers.IO) {
        val memory = ClientMemory(factory)
        var connected = false
        try {
            connectOnThisThread(
                host,
                port,
                serverName,
                options,
                memory,
                factory,
                api,
                timeout,
                readPolicy,
                writePolicy,
                wrapIo,
                loops,
                buffers,
            ).also { connected = true }
        } finally {
            if (!connected) memory.freeAll()
        }
    }

/** Everything [coreConnect] does that opens a socket, a file or a native library, off the caller's thread. */
private suspend fun connectOnThisThread(
    host: String,
    port: Int,
    serverName: String,
    options: QuicOptions,
    memory: ClientMemory,
    factory: BufferFactory,
    api: QuicheApi,
    timeout: Duration,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
    loops: LoopSet,
    buffers: SocketBuffers,
): CoreClient {
    val peer = UdpSocket.resolve(host, port)
    val codec = SocketAddressCodec(hostOsSockAddrLayout())
    val route = UdpSocketChannelFactory(peer, codec, factory, factory, MAX_DATAGRAM, LocalEndpointSupport.Bindable)
    val bindHost =
        when (val source = route.routeSourceAddress()) {
            is RouteSource.Resolved -> source.host
            is RouteSource.PlatformAssigned -> ANY_HOST
            is RouteSource.Unresolved -> raise(Raised.RouteUnresolved(source.reason), QuicCloseCircumstance.HandshakeFailed)
        }
    val peerInet = InetSocketAddress(host, port)
    val channel = DatagramChannel.open()
    channel.bind(InetSocketAddress(bindHost, 0)).connect(peerInet)
    val granted = buffers.applyTo(channel)
    channel.configureBlocking(false)
    val local = channel.localAddress
    if (local !is InetSocketAddress) {
        raise(Raised.RouteUnresolved(RouteProbeFailure.SourceAddressUnknown), QuicCloseCircumstance.HandshakeFailed)
    }
    val running =
        when (val placed = loops.place(factory)) {
            is Placement.Placed -> placed.running
            is Placement.SetClosed -> raise(Raised.LoopsClosed, QuicCloseCircumstance.HandshakeFailed)
            is Placement.NotJdkMemory -> raise(Raised.FactoryNotNative(factory.allocate(1)), QuicCloseCircumstance.HandshakeFailed)
        }
    val config = configured(api, options, factory)
    val scid = generateScid(factory)
    val scidHex = hex(scid)
    val peerSockAddr = codec.encodeToNative(peer, factory)
    val localSockAddr = codec.encodeToNative(UdpSocket.resolve(local.address.hostAddress, local.port), factory)
    val name = nullTerminated(serverName, factory)
    val handle =
        api.connect(
            addressOf(name),
            serverName.length,
            addressOf(scid),
            QUIC_ISSUED_CONN_ID_LEN,
            localSockAddr.address,
            localSockAddr.length,
            peerSockAddr.address,
            peerSockAddr.length,
            config,
        )
    name.freeNativeMemory()
    scid.freeNativeMemory()
    val scope = CoroutineScope(SupervisorJob())
    val built =
        ClientParts(
            ClientWiring(api, handle, config, options, memory, running, channel, wrapIo, peerInet, readPolicy, writePolicy, scope),
            peerSockAddr,
            localSockAddr,
        )
    val connection =
        built.connection(
            factory,
            peer,
            QuicConnectionIdentity(QuicSessionId(scidHex), QuicWireConnectionId.Known(scidHex)),
        )
    val monitor = resolveNetworkMonitor(options.networkMonitor)
    scope.feedNetwork(monitor, connection, route, local.address.hostAddress)
    wireClientConnectivityTap(options, built.capture, connection, monitor)
    built.start()
    var established = false
    try {
        withTimeout(timeout) { connection.established() }
        established = true
    } finally {
        if (!established) connection.closeNow(QuicError.NoError)
    }
    return CoreClient(connection, built.conn, running.loop, SocketReport(granted, local.port))
}

/** A client config with the caller's ALPN, options and trust anchors applied. */
private fun configured(
    api: QuicheApi,
    options: QuicOptions,
    factory: BufferFactory,
): QuicheConfig {
    val config = api.configNew(QUIC_V1)
    val alpn = encodeAlpnList(options.alpnProtocols, factory)
    api.configSetApplicationProtos(config, addressOf(alpn), alpn.remaining())
    alpn.freeNativeMemory()
    applyQuicOptions(options, CommonJvmQuicConfigCalls(api, config), QuicRole.Client)
    loadTrustAnchors(api, config, options, factory)
    return config
}

private fun addressOf(buffer: com.ditchoom.buffer.PlatformBuffer): Long =
    when (
        val at =
            com.ditchoom.socket.quic.core.udp.NativeBytes
                .addressOf(buffer)
    ) {
        is ReadAddress.At -> at.address
        is ReadAddress.NotAddressable -> raise(Raised.FactoryNotNative(buffer), QuicCloseCircumstance.HandshakeFailed)
    }

private fun nullTerminated(
    text: String,
    factory: BufferFactory,
): com.ditchoom.buffer.PlatformBuffer =
    factory.allocate(text.length + 1).apply {
        writeString(text, Charset.UTF8)
        writeByte(0)
        resetForRead()
    }

private fun hex(buffer: com.ditchoom.buffer.PlatformBuffer): String {
    val out = StringBuilder(buffer.remaining() * 2)
    for (i in buffer.position() until buffer.limit()) {
        val b = buffer.get(i).toInt() and 0xFF
        out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
    }
    return out.toString()
}

/** The quiche handle a connect made, kept with what it needs to be built on the loop. */
internal class ClientWiring(
    val api: QuicheApi,
    val handle: QuicheConn,
    val config: QuicheConfig,
    val options: QuicOptions,
    val memory: ClientMemory,
    val running: RunningLoop,
    val channel: DatagramChannel,
    val wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
    val peer: InetSocketAddress,
    val readPolicy: ReadPolicy,
    val writePolicy: WritePolicy,
    val scope: CoroutineScope,
)

private const val HEX = "0123456789abcdef"
private const val QUIC_V1 = 1
private const val ANY_HOST = "0.0.0.0"
internal const val MAX_DATAGRAM = 1500
