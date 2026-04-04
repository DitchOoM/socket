package com.ditchoom.socket.quic

import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.security.SecureRandom
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = JvmQuicEngine()

private class JvmQuicEngine : QuicEngine {
    private val api: QuicheApi = loadQuicheApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection =
        withTimeout(timeout) {
            val bufferFactory = connectionOptions.bufferFactory

            // 1. Create quiche config from QuicOptions
            val config = api.configNew(QUICHE_PROTOCOL_VERSION)

            // Set ALPN protocols (allocated via bufferFactory — zero-copy)
            val alpnBytes = QuicheApi.encodeAlpnProtos(quicOptions.alpnProtocols)
            val alpnBuf = bufferFactory.allocate(alpnBytes.size)
            alpnBuf.writeBytes(alpnBytes)
            alpnBuf.resetForRead()
            val alpnAddr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong()
            api.configSetApplicationProtos(config, alpnAddr, alpnBytes.size)
            alpnBuf.freeNativeMemory()

            api.configSetMaxIdleTimeout(config, quicOptions.idleTimeout.inWholeMilliseconds)
            api.configSetMaxRecvUdpPayloadSize(config, quicOptions.maxUdpPayloadSize.toLong())
            api.configSetMaxSendUdpPayloadSize(config, quicOptions.maxUdpPayloadSize.toLong())
            api.configSetInitialMaxData(config, quicOptions.initialMaxData)
            api.configSetInitialMaxStreamDataBidiLocal(config, quicOptions.initialMaxStreamDataBidiLocal)
            api.configSetInitialMaxStreamDataBidiRemote(config, quicOptions.initialMaxStreamDataBidiRemote)
            api.configSetInitialMaxStreamDataUni(config, quicOptions.initialMaxStreamDataUni)
            api.configSetInitialMaxStreamsBidi(config, quicOptions.initialMaxStreamsBidi)
            api.configSetInitialMaxStreamsUni(config, quicOptions.initialMaxStreamsUni)
            api.configSetDisableActiveMigration(config, quicOptions.disableActiveMigration)
            api.configVerifyPeer(config, quicOptions.verifyPeer)

            // 2. Open UDP channel
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.connect(InetSocketAddress(hostname, port))
            val localAddr = channel.localAddress as InetSocketAddress

            // 3. Generate source connection ID (RFC 9000 §7.2: max 20 bytes)
            val scid = ByteArray(QUICHE_MAX_CONN_ID_LEN)
            random.nextBytes(scid)

            // 4. Create quiche connection
            // Allocate server name + SCID via bufferFactory for zero-copy
            val serverNameBytes = hostname.toByteArray(Charsets.UTF_8)
            val serverNameBuf = bufferFactory.allocate(serverNameBytes.size + 1)
            serverNameBuf.writeBytes(serverNameBytes)
            serverNameBuf.writeByte(0) // null terminator
            serverNameBuf.resetForRead()
            val serverNameAddr = serverNameBuf.nativeMemoryAccess!!.nativeAddress.toLong()

            val scidBuf = bufferFactory.allocate(scid.size)
            scidBuf.writeBytes(scid)
            scidBuf.resetForRead()
            val scidAddr = scidBuf.nativeMemoryAccess!!.nativeAddress.toLong()

            val conn =
                api.connect(
                    serverNameAddr,
                    serverNameBytes.size,
                    scidAddr,
                    scid.size,
                    0L,
                    0, // TODO: local sockaddr from channel
                    0L,
                    0, // TODO: peer sockaddr from channel
                    config,
                )

            serverNameBuf.freeNativeMemory()
            scidBuf.freeNativeMemory()
            api.configFree(config)

            // 5. Create connection wrapper and start event loop
            val quicConnection =
                JvmQuicConnection(
                    api = api,
                    conn = conn,
                    channel = channel,
                    bufferFactory = bufferFactory,
                    scope = scope,
                )
            quicConnection.start()
            quicConnection
        }

    override fun close() {
        // Scope cleanup handled by SupervisorJob cancellation
    }

    companion object {
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
        private const val QUICHE_MAX_CONN_ID_LEN = 20
    }
}
