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

                applyQuicOptions(quicOptions, JvmQuicConfigCalls(api, config))

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

/** Adapts [QuicheApi] to the platform-neutral [QuicConfigCalls] interface. */
private class JvmQuicConfigCalls(
    private val api: QuicheApi,
    private val cfg: QuicheConfig,
) : QuicConfigCalls {
    override fun setMaxIdleTimeout(ms: Long) = api.configSetMaxIdleTimeout(cfg, ms)

    override fun setMaxRecvUdpPayloadSize(size: Long) = api.configSetMaxRecvUdpPayloadSize(cfg, size)

    override fun setMaxSendUdpPayloadSize(size: Long) = api.configSetMaxSendUdpPayloadSize(cfg, size)

    override fun setInitialMaxData(v: Long) = api.configSetInitialMaxData(cfg, v)

    override fun setInitialMaxStreamDataBidiLocal(v: Long) = api.configSetInitialMaxStreamDataBidiLocal(cfg, v)

    override fun setInitialMaxStreamDataBidiRemote(v: Long) = api.configSetInitialMaxStreamDataBidiRemote(cfg, v)

    override fun setInitialMaxStreamDataUni(v: Long) = api.configSetInitialMaxStreamDataUni(cfg, v)

    override fun setInitialMaxStreamsBidi(v: Long) = api.configSetInitialMaxStreamsBidi(cfg, v)

    override fun setInitialMaxStreamsUni(v: Long) = api.configSetInitialMaxStreamsUni(cfg, v)

    override fun setMaxConnectionWindow(v: Long) = api.configSetMaxConnectionWindow(cfg, v)

    override fun setMaxStreamWindow(v: Long) = api.configSetMaxStreamWindow(cfg, v)

    override fun setDisableActiveMigration(v: Boolean) = api.configSetDisableActiveMigration(cfg, v)

    override fun verifyPeer(v: Boolean) = api.configVerifyPeer(cfg, v)

    override fun setCcAlgorithm(algo: Int) = api.configSetCcAlgorithm(cfg, algo)

    override fun enableHystart(v: Boolean) = api.configEnableHystart(cfg, v)

    override fun setInitialCongestionWindowPackets(packets: Long) = api.configSetInitialCongestionWindowPackets(cfg, packets)

    override fun enablePacing(v: Boolean) = api.configEnablePacing(cfg, v)

    override fun setMaxPacingRate(v: Long) = api.configSetMaxPacingRate(cfg, v)

    override fun discoverPmtu(v: Boolean) = api.configDiscoverPmtu(cfg, v)

    override fun enableEarlyData() = api.configEnableEarlyData(cfg)

    override fun grease(v: Boolean) = api.configGrease(cfg, v)
}
