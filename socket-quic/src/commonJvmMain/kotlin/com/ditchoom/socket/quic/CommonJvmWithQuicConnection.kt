package com.ditchoom.socket.quic

import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Shared JVM/Android [withQuicConnection] test seam backed by quiche + [DatagramChannel].
 * Owns the per-call lifecycle for the duration of [block]; releases everything before returning.
 *
 * Production code reaches the same path via [QuicheEngine.connect] → [buildJvmQuicConnection];
 * this thin wrapper survives because a test passes a spy [api] (e.g. CountingQuicheApi) to observe
 * the real native calls the driver makes — used to assert reactive keepalive actually PINGs.
 */
internal suspend fun <R> commonJvmWithQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    api: QuicheApi = loadQuicheApi(),
    block: suspend QuicScope.() -> R,
): R =
    withTimeout(timeout) {
        val connection = buildJvmQuicConnection(hostname, port, quicOptions, connectionOptions, timeout, api)
        try {
            connection.block()
        } finally {
            connection.close()
        }
    }

/**
 * Build + establish a JVM/Android quiche-backed [JvmQuicConnection], returning it ready to use.
 * The returned connection owns its full lifecycle teardown via [JvmQuicConnection.close] (the
 * `onRelease` lambda wired below: cancel the connection scope, close the UDP channel, free the
 * quiche config). The caller only runs the user block and calls `close()`. Shared by
 * [QuicheEngine.connect] and the [commonJvmWithQuicConnection] test seam.
 *
 * Lives in `commonJvmMain` so both `jvmMain` and `androidMain` reach it.
 */
internal suspend fun buildJvmQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    api: QuicheApi,
): JvmQuicConnection {
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.IO)
    var established = false
    val bufferFactory = connectionOptions.quicBufferFactory()

    // 1. Create quiche config
    val config = api.configNew(QUICHE_PROTOCOL_VERSION)
    try {
        // ALPN — writes directly into buffer (zero-copy)
        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            val alpnAddr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong()
            api.configSetApplicationProtos(config, alpnAddr, alpnBuf.remaining())
        }

        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, config))

        // Pinned CA trust anchors (#99): load the PEM bundle as the verification
        // anchors so non-Apple targets enforce the same private-CA trust as Apple.
        // quiche only loads anchors from a file, so the bundle goes to a temp file
        // whose path is handed to the native call (verifyPeer is forced on in
        // applyQuicOptions whenever anchors are present).
        if (quicOptions.trustedCaCertificatesPem.isNotEmpty()) {
            val caBundlePath = writeCaBundleToTempFile(quicOptions.trustedCaCertificatesPem)
            try {
                writeNullTerminatedString(caBundlePath, bufferFactory).use { caBuf ->
                    val rc = api.configLoadVerifyLocationsFromFile(config, caBuf.nativeMemoryAccess!!.nativeAddress.toLong())
                    check(rc == 0) { "Failed to load trusted CA certificates: $rc" }
                }
            } finally {
                runCatching { java.io.File(caBundlePath).delete() }
            }
        }

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
        // onCleanup captures the sockaddr holders so they outlive every quiche
        // call. Without this, the suspending block below stops referencing
        // connPeerSockAddr/connLocalSockAddr after the QuicheDriver is built,
        // making their PlatformBuffers GC-eligible — DirectByteBuffer Cleaner
        // can then free the native memory mid-connection, leaving recvInfo.from
        // dangling. Symptom: quiche/src/ffi.rs:2059 panic "unsupported address type".
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
                keepAliveInterval = quicOptions.keepAliveInterval,
                // Connection-migration wiring (slice 3): the peer + primary local sockaddrs
                // (kept pinned by onCleanup for the driver's life) and a factory for opening
                // additional path sockets to the same peer.
                peerAddr = connPeerSockAddr.address,
                peerLen = connPeerSockAddr.length,
                primaryLocalAddr = connLocalSockAddr.address,
                primaryLocalLen = connLocalSockAddr.length,
                udpChannelFactory = NioUdpChannelFactory(InetSocketAddress(hostname, port), bufferFactory),
                onCleanup = {
                    connPeerSockAddr.free()
                    connLocalSockAddr.free()
                },
            )
        // Create a child scope for this connection — cancelled by the connection's
        // onRelease teardown when the withQuicConnection wrapper closes it.
        val connJob = SupervisorJob(parentScope.coroutineContext[Job])
        val connScope = CoroutineScope(parentScope.coroutineContext + connJob)

        val quicConnection =
            JvmQuicConnection(
                driver = driver,
                bufferFactory = bufferFactory,
                scope = connScope,
                // Full lifecycle teardown, run once by JvmQuicConnection.close() after the block:
                // close the connection (driver flush) first, then cancel children, close the UDP
                // channel (unblocks the selector — closing only `channel` would leak the selector
                // coroutine), free the quiche config, and cancel the per-call parent scope.
                onRelease = {
                    connJob.cancel()
                    runCatching { udpChannel.close() }
                    api.configFree(config)
                    parentScope.cancel()
                },
            )
        quicConnection.start()
        quicConnection.awaitEstablished(timeout)
        established = true
        return quicConnection
    } finally {
        // Establishment failed before the connection took ownership of teardown — release here.
        // (On success the connection owns config + parentScope via onRelease above.)
        if (!established) {
            api.configFree(config)
            parentScope.cancel()
        }
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

    override fun setActiveConnectionIdLimit(v: Long) = api.configSetActiveConnectionIdLimit(cfg, v)

    override fun verifyPeer(v: Boolean) = api.configVerifyPeer(cfg, v)

    override fun setCcAlgorithm(algo: Int) = api.configSetCcAlgorithm(cfg, algo)

    override fun enableHystart(v: Boolean) = api.configEnableHystart(cfg, v)

    override fun setInitialCongestionWindowPackets(packets: Long) = api.configSetInitialCongestionWindowPackets(cfg, packets)

    override fun enablePacing(v: Boolean) = api.configEnablePacing(cfg, v)

    override fun setMaxPacingRate(v: Long) = api.configSetMaxPacingRate(cfg, v)

    override fun discoverPmtu(v: Boolean) = api.configDiscoverPmtu(cfg, v)

    override fun enableEarlyData() = api.configEnableEarlyData(cfg)

    override fun grease(v: Boolean) = api.configGrease(cfg, v)

    override fun enableDgram(
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) = api.configEnableDgram(cfg, true, recvQueueLen, sendQueueLen)
}

/**
 * Write the supplied CA PEM blocks to a single temp bundle file and return its path (#99).
 *
 * quiche/BoringSSL only loads verification anchors from a file path, so the in-memory PEM
 * must land on disk; the caller deletes it once `load_verify_locations` has read it. The
 * byte encoding happens inside `java.io` at this file boundary — no `ByteArray` in our code.
 */
private fun writeCaBundleToTempFile(pems: List<String>): String {
    val file = java.io.File.createTempFile("ditchoom-quic-ca", ".pem")
    file.deleteOnExit()
    file.writeText(pems.joinToString("\n"))
    return file.absolutePath
}
