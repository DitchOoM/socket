package com.ditchoom.socket.quic

import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

/**
 * Shared JVM/Android [QuicEngine] implementation backed by quiche + [DatagramChannel].
 *
 * Lives in `commonJvmMain` so both `jvmMain` and `androidMain` can delegate to it.
 */
internal fun commonJvmQuicEngine(): QuicEngine = CommonJvmQuicEngine()

private class CommonJvmQuicEngine : QuicEngine {
    private val api: QuicheApi = loadQuicheApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun <R> connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
        block: suspend QuicScope.() -> R,
    ): R =
        withTimeout(timeout) {
            val bufferFactory = connectionOptions.bufferPool

            // 1. Create quiche config
            val config = api.configNew(QUICHE_PROTOCOL_VERSION)
            try {
                // ALPN — writes directly into buffer (zero-copy)
                encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
                    val alpnAddr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong()
                    api.configSetApplicationProtos(config, alpnAddr, alpnBuf.remaining())
                }

                applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, config))

                // 2. Open UDP channel
                val channel = DatagramChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(hostname, port))
                val localAddr = channel.localAddress as InetSocketAddress

                // 3. Server name — null-terminated UTF-8 in buffer
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

                // 6. Build recvInfo/sendInfo for the connection
                val connPeerSockAddr = InetSocketAddress(hostname, port).toNativeSockAddr(bufferFactory)
                val connLocalSockAddr = localAddr.toNativeSockAddr(bufferFactory)
                val connRecvInfo =
                    api.recvInfoNew(
                        connPeerSockAddr.address,
                        connPeerSockAddr.length,
                        connLocalSockAddr.address,
                        connLocalSockAddr.length,
                    )
                val connSendInfo = api.sendInfoNew()

                // 7. Create driver + connection
                val udpChannel = NioUdpChannel(channel)
                val driver =
                    QuicheDriver(
                        api = api,
                        conn = conn,
                        bufferFactory = bufferFactory,
                        recvInfo = connRecvInfo,
                        sendInfo = connSendInfo,
                        udpChannel = udpChannel,
                        clientMode = true,
                        isServer = false,
                    )
                // Create a child scope for this connection — cancelled when block returns
                val connJob = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
                val connScope = CoroutineScope(scope.coroutineContext + connJob)

                val quicConnection =
                    JvmQuicConnection(
                        driver = driver,
                        bufferFactory = bufferFactory,
                        scope = connScope,
                    )
                quicConnection.start()
                quicConnection.awaitEstablished(timeout)

                // Run the user's block with the established connection as QuicScope
                try {
                    quicConnection.block()
                } finally {
                    // Order matters: close the connection first (driver processes Close
                    // command and flushes CONNECTION_CLOSE), then cancel remaining children,
                    // then close the UDP channel (unblocks selector).
                    //
                    // IMPORTANT: close udpChannel, not just the underlying DatagramChannel.
                    // udpChannel wraps its own Selector; closing only `channel` leaves the
                    // selector alive with udpReaderLoop's suspend frame stuck in select(),
                    // leaking one coroutine per connect() call.
                    quicConnection.close()
                    connJob.cancel()
                    udpChannel.close()
                }
            } finally {
                api.configFree(config)
            }
        }

    override fun close() {
        scope.cancel()
    }

    companion object {
        private const val QUICHE_PROTOCOL_VERSION = 0x00000001
    }
}

/** Adapts [QuicheApi] to the platform-neutral [QuicConfigCalls] interface. */
internal class CommonJvmQuicConfigCalls(
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
