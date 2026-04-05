package com.ditchoom.socket.quic

import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = JvmQuicEngine()

private class JvmQuicEngine : QuicEngine {
    private val api: QuicheApi = loadQuicheApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
    ): QuicConnection =
        withTimeout(timeout) {
            val bufferFactory = connectionOptions.bufferFactory

            // 1. Create quiche config
            val config = api.configNew(QUICHE_PROTOCOL_VERSION)
            try {
                // ALPN — @ProtocolMessage generated codec, writes directly into buffer (zero-copy)
                encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
                    val alpnAddr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong()
                    api.configSetApplicationProtos(config, alpnAddr, alpnBuf.remaining())
                }

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
                api.configEnablePacing(config, quicOptions.enablePacing)
                quicOptions.maxPacingRate?.let { api.configSetMaxPacingRate(config, it) }
                quicOptions.congestionControlAlgorithm?.let { api.configSetCcAlgorithm(config, it.value) }
                quicOptions.enableHystart?.let { api.configEnableHystart(config, it) }
                quicOptions.initialCongestionWindowPackets?.let {
                    api.configSetInitialCongestionWindowPackets(config, it)
                }
                quicOptions.maxConnectionWindow?.let { api.configSetMaxConnectionWindow(config, it) }
                quicOptions.maxStreamWindow?.let { api.configSetMaxStreamWindow(config, it) }
                quicOptions.enablePmtuDiscovery?.let { api.configDiscoverPmtu(config, it) }
                if (quicOptions.enableEarlyData) api.configEnableEarlyData(config)
                quicOptions.enableGrease?.let { api.configGrease(config, it) }

                // 2. Open UDP channel
                val channel = DatagramChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(hostname, port))
                val localAddr = channel.localAddress as InetSocketAddress

                // 3. Server name — write directly into buffer (null-terminated UTF-8)
                val serverNameBuf = bufferFactory.allocate(hostname.length + 1)
                serverNameBuf.writeString(hostname, com.ditchoom.buffer.Charset.UTF8)
                serverNameBuf.writeByte(0) // null terminator
                serverNameBuf.resetForRead()
                val serverNameAddr = serverNameBuf.nativeMemoryAccess!!.nativeAddress.toLong()

                // 4. SCID — bulk random writes (2 longs + 1 int = 20 bytes in 3 ops)
                val scidBuf = generateScid(bufferFactory)
                val scidAddr = scidBuf.nativeMemoryAccess!!.nativeAddress.toLong()

                // 5. Sockaddr structs via buffer factory
                val peerSockAddr = InetSocketAddress(hostname, port).toNativeSockAddr(bufferFactory)
                val localSockAddr = localAddr.toNativeSockAddr(bufferFactory)

                val conn =
                    try {
                        api.connect(
                            serverNameAddr,
                            hostname.length,
                            scidAddr,
                            QUIC_MAX_CONN_ID_LEN,
                            localSockAddr.address,
                            localSockAddr.length,
                            peerSockAddr.address,
                            peerSockAddr.length,
                            config,
                        )
                    } finally {
                        // Free all config-phase buffers immediately
                        serverNameBuf.freeNativeMemory()
                        scidBuf.freeNativeMemory()
                        localSockAddr.free()
                        peerSockAddr.free()
                    }

                // 6. Create connection and start event loop
                val quicConnection =
                    JvmQuicConnection(
                        api = api,
                        conn = conn,
                        channel = channel,
                        bufferFactory = bufferFactory,
                        localAddr = localAddr,
                        peerAddr = InetSocketAddress(hostname, port),
                        scope = scope,
                    )
                quicConnection.start()
                quicConnection.awaitEstablished(timeout)
                quicConnection
            } finally {
                api.configFree(config)
            }
        }

    override fun close() {}

    companion object {
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}
