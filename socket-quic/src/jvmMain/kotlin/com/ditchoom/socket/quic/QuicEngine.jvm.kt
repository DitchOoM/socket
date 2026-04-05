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

                // Flow control
                val fc = quicOptions.flowControl
                api.configSetInitialMaxData(config, fc.initialMaxData)
                api.configSetInitialMaxStreamDataBidiLocal(config, fc.initialMaxStreamDataBidiLocal)
                api.configSetInitialMaxStreamDataBidiRemote(config, fc.initialMaxStreamDataBidiRemote)
                api.configSetInitialMaxStreamDataUni(config, fc.initialMaxStreamDataUni)
                api.configSetInitialMaxStreamsBidi(config, fc.initialMaxStreamsBidi)
                api.configSetInitialMaxStreamsUni(config, fc.initialMaxStreamsUni)
                fc.maxConnectionWindow?.let { api.configSetMaxConnectionWindow(config, it) }
                fc.maxStreamWindow?.let { api.configSetMaxStreamWindow(config, it) }

                api.configSetDisableActiveMigration(config, quicOptions.disableActiveMigration)
                api.configVerifyPeer(config, quicOptions.verifyPeer)

                // Congestion control
                api.configSetCcAlgorithm(config, quicOptions.congestionControl.quicheValue)
                when (val cc = quicOptions.congestionControl) {
                    is CongestionControl.Cubic -> api.configEnableHystart(config, cc.enableHystart)
                    is CongestionControl.Reno, is CongestionControl.Bbr2 -> {}
                }
                quicOptions.initialCongestionWindowPackets?.let {
                    api.configSetInitialCongestionWindowPackets(config, it)
                }

                // Pacing
                when (val pacing = quicOptions.pacing) {
                    is Pacing.Disabled -> api.configEnablePacing(config, false)
                    is Pacing.Unlimited -> api.configEnablePacing(config, true)
                    is Pacing.Limited -> {
                        api.configEnablePacing(config, true)
                        api.configSetMaxPacingRate(config, pacing.maxBytesPerSec)
                    }
                }

                api.configDiscoverPmtu(config, quicOptions.enablePmtuDiscovery)
                if (quicOptions.enableEarlyData) api.configEnableEarlyData(config)
                api.configGrease(config, quicOptions.enableGrease)

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
