@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.use
import com.ditchoom.socket.quic.CommonJvmQuicConfigCalls
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.applyQuicOptions
import com.ditchoom.socket.quic.applySessionTicketKeys
import com.ditchoom.socket.quic.connectionIdRandom
import com.ditchoom.socket.quic.core.conn.DatagramSlot
import com.ditchoom.socket.quic.core.demux.ServerConfig
import com.ditchoom.socket.quic.core.demux.ServerCounts
import com.ditchoom.socket.quic.core.demux.ServerParts
import com.ditchoom.socket.quic.core.demux.ServerResources
import com.ditchoom.socket.quic.core.demux.ServerSetup
import com.ditchoom.socket.quic.core.loop.CallerLanes
import com.ditchoom.socket.quic.core.loop.CallerSend
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopClosed
import com.ditchoom.socket.quic.core.loop.LoopCounts
import com.ditchoom.socket.quic.core.loop.LoopHost
import com.ditchoom.socket.quic.core.loop.LoopWork
import com.ditchoom.socket.quic.core.loop.SelectorWait
import com.ditchoom.socket.quic.core.loop.startLoop
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NioOneByOne
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.encodeAlpnList
import com.ditchoom.socket.quic.toNativeSockAddr
import com.ditchoom.socket.quic.writeNullTerminatedString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.DatagramChannel

/**
 * A server on the run-to-completion core: one loop thread over one unconnected socket, serving every connection it
 * accepted. An established connection is offered through [accept] and [connections] as the same [CoreConnection] a
 * client uses, so streams, callers in place, typed exceptions and failure tiers behave alike on both sides.
 */
internal class CoreServer(
    /** The port the socket is bound to. */
    val port: Int,
    val loop: Loop,
    val parts: ServerParts,
    val counts: ServerCounts,
    private val accepted: Channel<CoreConnection>,
) {
    /** The next established connection; raises the failure that ended the server if it failed, or a closed-channel exception once closed. */
    suspend fun accept(): CoreConnection = accepted.receive()

    fun connections(): Flow<CoreConnection> = accepted.consumeAsFlow()

    /**
     * Stops accepting, closes every connection with its CONNECTION_CLOSE sent, and returns once the loop has stopped and
     * closed its socket. The loop owns the socket, so nothing here waits for a receive.
     */
    suspend fun close() {
        val done = CompletableDeferred<Unit>()
        loop.submit(ShutServer(parts, LoopClosed { done.complete(Unit) }))
        done.await()
    }
}

/** Loop work: the server's owner closes it. */
private class ShutServer(
    private val parts: ServerParts,
    private val closed: LoopClosed,
) : LoopWork() {
    override fun run(loop: LoopHost) = parts.beginShutdown(closed)

    // The loop has already stopped, and ended its connections with it.
    override fun gone() = closed.closed(LoopCounts())
}

/**
 * Binds [bind] and serves on it with the certificate of [tls]. The setup that touches files, sockets and native
 * libraries runs off the caller's thread. [wrapIo] may wrap the socket's batch interface, which a test uses to see
 * what the loop sends and receives.
 */
internal suspend fun coreServe(
    bind: InetSocketAddress,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
    api: QuicheApi,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    config: ServerConfig = ServerConfig(),
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo = { it },
): CoreServer =
    withContext(Dispatchers.IO) {
        serveOnThisThread(bind, tls, options, factory, api, readPolicy, writePolicy, config, wrapIo)
    }

private fun serveOnThisThread(
    bind: InetSocketAddress,
    tls: QuicTlsConfig,
    options: QuicOptions,
    factory: BufferFactory,
    api: QuicheApi,
    readPolicy: ReadPolicy,
    writePolicy: WritePolicy,
    serverConfig: ServerConfig,
    wrapIo: (DatagramBatchIo) -> DatagramBatchIo,
): CoreServer {
    val quicheConfig = api.configNew(QUIC_V1)
    writeNullTerminatedString(tls.certChainPath, factory).use { path ->
        api.configLoadCertChainFromPemFile(quicheConfig, addressOf(path))
    }
    writeNullTerminatedString(tls.privKeyPath, factory).use { path ->
        api.configLoadPrivKeyFromPemFile(quicheConfig, addressOf(path))
    }
    encodeAlpnList(options.alpnProtocols, factory).use { alpn ->
        api.configSetApplicationProtos(quicheConfig, addressOf(alpn), alpn.remaining())
    }
    applyQuicOptions(options, CommonJvmQuicConfigCalls(api, quicheConfig), QuicRole.Server)
    api.applySessionTicketKeys(quicheConfig, tls.sessionTicketKeys, factory)

    val channel = DatagramChannel.open()
    channel.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER)
    channel.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER)
    channel.bind(bind)
    channel.configureBlocking(false)
    val boundAddress = channel.localAddress as InetSocketAddress
    val local = boundAddress.toNativeSockAddr(factory)

    val limits = BatchLimits(maxDatagrams = MAX_BATCH, maxDatagramSize = MAX_DATAGRAM)
    val storage = ArrayList<PlatformBuffer>()
    val sendStorage = factory.allocate(limits.maxDatagrams * limits.maxDatagramSize).also(storage::add)
    val send = SendBatch(sendStorage, addressOf(sendStorage), limits)
    val callerLimits = BatchLimits(maxDatagrams = CALLER_BATCH, maxDatagramSize = MAX_DATAGRAM)
    val callerSends =
        FixedPool(CALLER_LANES) { index ->
            val lane = factory.allocate(callerLimits.maxDatagrams * callerLimits.maxDatagramSize).also(storage::add)
            CallerSend(index, SendBatch(lane, addressOf(lane), callerLimits, lane = index + 1))
        }
    val copies =
        FixedPool(COPY_SLOTS) { index ->
            val buffer = factory.allocate(MAX_DATAGRAM).also(storage::add)
            DatagramSlot(index, buffer, addressOf(buffer))
        }
    val receive =
        ReceiveBatch(
            FixedPool(limits.maxDatagrams) { index ->
                val buffer = factory.allocate(limits.maxDatagramSize).also(storage::add)
                ReceiveBuffer(index, buffer, addressOf(buffer))
            },
            limits,
        )
    val clock = LoopClock { System.nanoTime() }
    val io =
        wrapIo(
            NioOneByOne(channel, listOf(send) + List(CALLER_LANES) { callerSends[it].batch }, maxCachedTargets = MAX_PEERS_CACHED),
        )
    val accepted = Channel<CoreConnection>(Channel.UNLIMITED)
    val counts = ServerCounts()
    val resources =
        ServerResources {
            api.configFree(quicheConfig)
            local.free()
            storage.forEach(PlatformBuffer::freeNativeMemory)
        }
    val parts =
        ServerParts(
            ServerSetup(
                api = api,
                config = quicheConfig,
                factory = factory,
                serverConfig = serverConfig,
                local = local,
                localKey = boundAddress.toPathKey(),
                readPolicy = readPolicy,
                writePolicy = writePolicy,
                clock = clock,
                random = connectionIdRandom,
                slots = copies,
                resources = resources,
                accepted = accepted,
                counts = counts,
            ),
        )
    val loop = Loop(SelectorWait(channel, clock), clock, io, parts, receive, send, CallerLanes.Of(callerSends))
    parts.attach(loop)
    startLoop(loop, SERVER_THREAD)
    return CoreServer(boundAddress.port, loop, parts, counts, accepted)
}

/** The name of the server loop's thread. */
internal const val SERVER_THREAD = "quic-core-server"

private const val QUIC_V1 = 1
private const val MAX_BATCH = 64
private const val CALLER_LANES = 16
private const val CALLER_BATCH = 16
private const val COPY_SLOTS = 16
private const val MAX_DATAGRAM = 1500
private const val SOCKET_BUFFER = 4 shl 20
private const val MAX_PEERS_CACHED = 1 shl 16
