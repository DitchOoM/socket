package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnFreed
import com.ditchoom.socket.quic.core.conn.ConnPath
import com.ditchoom.socket.quic.core.conn.DatagramSlot
import com.ditchoom.socket.quic.core.conn.DatagramSlots
import com.ditchoom.socket.quic.core.conn.OneConnParts
import com.ditchoom.socket.quic.core.loop.CallerLanes
import com.ditchoom.socket.quic.core.loop.CallerSend
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopClosed
import com.ditchoom.socket.quic.core.loop.SelectorWait
import com.ditchoom.socket.quic.core.loop.startLoop
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.generateScid
import com.ditchoom.socket.quic.loadTrustAnchors
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

/**
 * Connects to [host]:[port] (an address literal) on the run-to-completion core and returns once the handshake
 * completed; the setup that touches files, sockets and native libraries runs off the caller's thread, so a caller on a
 * main dispatcher is never blocked. The connection owns its socket and its loop thread; both end when the connection does. [wrapIo]
 * may wrap the socket's batch interface, which a test uses to see which thread sends and receives.
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
): CoreClient =
    withContext(Dispatchers.IO) {
        connectOnThisThread(host, port, serverName, options, factory, api, timeout, readPolicy, writePolicy, wrapIo)
    }

/** Everything [coreConnect] does that opens a file, a socket or a native library, off the caller's thread. */
private suspend fun connectOnThisThread(
    host: String,
    port: Int,
    serverName: String,
    options: QuicOptions,
    factory: BufferFactory,
    api: QuicheApi,
    timeout: Duration,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
): CoreClient {
    val channel = DatagramChannel.open().connect(InetSocketAddress(host, port))
    channel.configureBlocking(false)
    val local = (channel.localAddress as InetSocketAddress).toNativeSockAddr(factory)
    val peer = (channel.remoteAddress as InetSocketAddress).toNativeSockAddr(factory)

    val config = api.configNew(QUIC_V1)
    encodeAlpnList(options.alpnProtocols, factory).let { alpn ->
        api.configSetApplicationProtos(config, addressOf(alpn), alpn.remaining())
        alpn.freeNativeMemory()
    }
    applyQuicOptions(options, CommonJvmQuicConfigCalls(api, config), QuicRole.Client)
    loadTrustAnchors(api, config, options, factory)

    val name = nullTerminated(serverName, factory)
    val scid = generateScid(factory)
    val handle =
        api.connect(
            addressOf(name),
            serverName.length,
            addressOf(scid),
            QUIC_ISSUED_CONN_ID_LEN,
            local.address,
            local.length,
            peer.address,
            peer.length,
            config,
        )
    name.freeNativeMemory()
    val scidHex = hex(scid)
    scid.freeNativeMemory()
    val path =
        ConnPath(api.recvInfoNew(peer.address, peer.length, local.address, local.length), api.sendInfoNew(), SendTarget.ConnectedPeer)

    val limits = BatchLimits(maxDatagrams = MAX_BATCH, maxDatagramSize = MAX_DATAGRAM)
    val sendStorage = factory.allocate(limits.maxDatagrams * limits.maxDatagramSize)
    val send = SendBatch(sendStorage, addressOf(sendStorage), limits)
    val callerLimits = BatchLimits(maxDatagrams = CALLER_BATCH, maxDatagramSize = MAX_DATAGRAM)
    val callerStorage = ArrayList<PlatformBuffer>(CALLER_LANES)
    val callerSends =
        FixedPool(CALLER_LANES) { index ->
            val storage = factory.allocate(callerLimits.maxDatagrams * callerLimits.maxDatagramSize).also(callerStorage::add)
            CallerSend(index, SendBatch(storage, addressOf(storage), callerLimits, lane = index + 1))
        }
    val copyBuffers = ArrayList<PlatformBuffer>(COPY_SLOTS)
    val copies =
        FixedPool(COPY_SLOTS) { index ->
            val buffer = factory.allocate(MAX_DATAGRAM).also(copyBuffers::add)
            DatagramSlot(index, buffer, addressOf(buffer))
        }
    val receiveBuffers = ArrayList<PlatformBuffer>(limits.maxDatagrams)
    val receive =
        ReceiveBatch(
            FixedPool(limits.maxDatagrams) { index ->
                val buffer = factory.allocate(limits.maxDatagramSize).also(receiveBuffers::add)
                ReceiveBuffer(index, buffer, addressOf(buffer))
            },
            limits,
        )
    val clock = LoopClock { System.nanoTime() }
    val parts = OneConnParts()
    val lanes = List(CALLER_LANES) { callerSends[it].batch }
    val io = wrapIo(NioOneByOne(channel, listOf(send) + lanes))
    val loop = Loop(SelectorWait(channel, clock), clock, io, parts, receive, send, CallerLanes.Of(callerSends))
    val scope = CoroutineScope(SupervisorJob())
    val connection =
        CoreConnection(
            bufferFactory = factory,
            remoteAddress = UdpSocket.resolve(host, port),
            identity = QuicConnectionIdentity(QuicSessionId(scidHex), QuicWireConnectionId.Known(scidHex)),
            role = QuicRole.Client,
            readPolicy = readPolicy,
            writePolicy = writePolicy,
            scope = scope,
        )
    val conn =
        Conn(api, handle, path, loop, Entrant.loopTurn(loop = 1, generation = 1), clock, connection, factory, DatagramSlots.Of(copies))
    connection.bind(conn)
    connection.whenEnded {
        loop.close(
            LoopClosed {
                receiveBuffers.forEach(PlatformBuffer::freeNativeMemory)
                sendStorage.freeNativeMemory()
                // A caller may still be inside: what callers use is freed with the connection, by the last one out.
                conn.release(
                    ConnFreed {
                        api.configFree(config)
                        local.free()
                        peer.free()
                        callerStorage.forEach(PlatformBuffer::freeNativeMemory)
                        copyBuffers.forEach(PlatformBuffer::freeNativeMemory)
                        scope.cancel()
                    },
                )
            },
        )
    }
    parts.attach(conn)
    startLoop(loop, "quic-core-client")
    conn.start()
    try {
        withTimeout(timeout) { connection.established() }
    } catch (failed: Throwable) {
        connection.closeNow(QuicError.NoError)
        throw failed
    }
    return CoreClient(connection, conn, loop)
}

private fun nullTerminated(
    text: String,
    factory: BufferFactory,
): PlatformBuffer =
    factory.allocate(text.length + 1).apply {
        writeString(text, Charset.UTF8)
        writeByte(0)
        resetForRead()
    }

private fun hex(buffer: PlatformBuffer): String {
    val out = StringBuilder(buffer.remaining() * 2)
    for (i in buffer.position() until buffer.limit()) {
        val b = buffer.get(i).toInt() and 0xFF
        out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
private const val QUIC_V1 = 1
private const val MAX_BATCH = 64

/** Lanes callers send from in place, datagrams each holds, and slots for datagrams that arrive while a caller is inside. */
private const val CALLER_LANES = 4
private const val CALLER_BATCH = 16
private const val COPY_SLOTS = 16
private const val MAX_DATAGRAM = 1500
