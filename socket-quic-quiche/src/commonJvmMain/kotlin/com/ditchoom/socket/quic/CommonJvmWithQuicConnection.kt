@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.use
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

private const val QUICHE_PROTOCOL_VERSION = 0x00000001

/**
 * Shared JVM/Android [withQuicConnection] test seam backed by quiche + [ConnectedDatagramChannel].
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
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
    block: suspend QuicScope.() -> R,
): R =
    withTimeout(timeout) {
        val connection = buildJvmQuicConnection(hostname, port, quicOptions, connectionOptions, timeout, api, tuning)
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
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
): JvmQuicConnection {
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.IO)
    // What teardown still owes if this throws — see [ConnectProgress]. Advances as resources are
    // acquired and hands over entirely once the connection owns its own release.
    var progress: ConnectProgress = ConnectProgress.BeforeChannel
    val bufferFactory = connectionOptions.quicBufferFactory()
    // One recv pool per connection, created up front so it can be injected into BOTH the :socket-udp
    // receive channel (which allocates each datagram straight from it) and the driver (which frees each
    // pooled buffer back to it after quiche_conn_recv) — the datagram never leaves the pool, so the old
    // receive→pool copy is gone (B2 elimination).
    val recvBufPool = QuicheDriver.newRecvBufPool(bufferFactory)

    // 1. Create quiche config
    val config = api.configNew(QUICHE_PROTOCOL_VERSION)
    try {
        // ALPN — writes directly into buffer (zero-copy)
        encodeAlpnList(quicOptions.alpnProtocols, bufferFactory).use { alpnBuf ->
            val alpnAddr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toLong()
            api.configSetApplicationProtos(config, alpnAddr, alpnBuf.remaining())
        }

        applyQuicOptions(quicOptions, CommonJvmQuicConfigCalls(api, config))

        // CA trust anchors. Two sources, in priority order:
        //  1. Caller-pinned anchors (#99): load exactly the supplied PEM bundle so non-Apple targets
        //     enforce the same private-CA trust as Apple.
        //  2. Platform defaults: when verify_peer ends up ON but the caller pinned nothing, fall back to
        //     the JVM/Android default trust store. quiche bundles its own BoringSSL with NO built-in
        //     default verify paths, so without this every real-server handshake fails with TLS
        //     certificate_unknown (alert 48) — the Android CA-trust bug. On a desktop JVM the default
        //     store is cacerts; on Android it is the system AndroidCAStore.
        // quiche only loads anchors from a file, so the bundle goes to a temp file whose path is handed
        // to the native call; verifyPeer is forced on in applyQuicOptions whenever explicit anchors are
        // present, and resolveVerifyPeer mirrors that decision here.
        val caPems =
            when {
                quicOptions.trustedCaCertificatesPem.isNotEmpty() -> quicOptions.trustedCaCertificatesPem
                resolveVerifyPeer(quicOptions) -> loadPlatformDefaultCaCertificatesPem()
                else -> emptyList()
            }
        if (caPems.isNotEmpty()) {
            val caBundlePath = writeCaBundleToTempFile(caPems)
            try {
                writeNullTerminatedString(caBundlePath, bufferFactory).use { caBuf ->
                    val rc = api.configLoadVerifyLocationsFromFile(config, caBuf.nativeMemoryAccess!!.nativeAddress.toLong())
                    check(rc == 0) { "Failed to load trusted CA certificates: $rc" }
                }
            } finally {
                runCatching { java.io.File(caBundlePath).delete() }
            }
        }

        // 2. Resolve the peer once (numeric literal → no DNS), then open the connection's primary
        // :socket-udp path through the same factory that opens every migration path — so the first path
        // binds the route's source address like all the others, instead of the unnamed bind #434
        // removed from the rest (#519). The factory is built here rather than at the migration wiring
        // below because the primary path is the first thing it opens.
        val peer = UdpSocket.resolve(hostname, port)
        val codec = SocketAddressCodec(hostOsSockAddrLayout())
        val channelFactory =
            UdpSocketChannelFactory(
                peer = peer,
                codec = codec,
                bufferFactory = bufferFactory,
                recvBufferFactory = recvBufPool,
                receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                // NIO binds the requested local endpoint before connecting.
                localEndpointSupport = LocalEndpointSupport.Bindable,
            )
        val channel = channelFactory.openPrimaryChannel()
        // Wrapped here rather than at driver-construction time so that from this point on there is a
        // single handle that releases both the socket and its selector coroutine.
        val udpChannel = DatagramChannelUdpChannel(channel)
        progress = ConnectProgress.ChannelOpen(udpChannel)
        val localAddress = channel.localAddress.orNull() ?: error("connected UDP channel has no local address")

        // 3. Server name — null-terminated UTF-8 in buffer
        val serverNameBuf = bufferFactory.allocate(hostname.length + 1)
        serverNameBuf.writeString(hostname, com.ditchoom.buffer.Charset.UTF8)
        serverNameBuf.writeByte(0) // null terminator
        serverNameBuf.resetForRead()
        val serverNameAddr = serverNameBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        // 4. SCID — bulk random writes (2 longs + 1 int = 20 bytes in 3 ops)
        val scidBuf = generateScid(bufferFactory, tuning.random)
        val scidAddr = scidBuf.nativeMemoryAccess!!.nativeAddress.toLong()

        // 5. Encode the peer + local sockaddrs via the one differential-tested SocketAddressCodec (Phase 6
        // sockaddr SPI, replacing SockAddrUtil.toNativeSockAddr). A single encoding of each backs both
        // quiche_connect (copied inline) AND recv_info (pointer stored), so both stay pinned for the
        // driver's life and are freed by onCleanup. Without that pin the PlatformBuffers go GC-eligible
        // and the DirectByteBuffer Cleaner can free the native memory mid-connection, leaving recvInfo.from
        // dangling — quiche/src/ffi.rs:2059 panic "unsupported address type". The codec is the one built
        // in step 2 for [channelFactory]; a migration path's sockaddr and the primary's are encoded by
        // the same instance so a divergence between them is unrepresentable.
        val peerSockAddr = codec.encodeToNative(peer, bufferFactory)
        val localSockAddr = codec.encodeToNative(localAddress, bufferFactory)

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
                // Free only the transient config-phase buffers; the sockaddr encodings stay pinned.
                serverNameBuf.freeNativeMemory()
                scidBuf.freeNativeMemory()
            }

        // 6. Build recvInfo/sendInfo for the connection from the same pinned sockaddr encodings.
        val connRecvInfo =
            api.recvInfoNew(
                peerSockAddr.address,
                peerSockAddr.length,
                localSockAddr.address,
                localSockAddr.length,
            )
        val connSendInfo = api.sendInfoNew()

        // 7. Create driver + connection
        val driver =
            QuicheDriver(
                rawApi = api,
                conn = conn,
                bufferFactory = bufferFactory,
                recvInfo = connRecvInfo,
                sendInfo = connSendInfo,
                udpChannel = udpChannel,
                recvBufPool = recvBufPool,
                clientMode = true,
                isServer = false,
                keepAliveInterval = quicOptions.keepAliveInterval,
                streamReadPolicy = resolveStreamReadPolicy(quicOptions),
                streamWritePolicy = resolveStreamWritePolicy(quicOptions),
                clock = tuning.clock,
                driverContext = tuning.driverContext,
                random = tuning.random,
                recorder = tuning.recorderFactory(),
                networkObservation = tuning.networkObservation,
                // Connection-migration wiring: the peer + primary local sockaddrs (kept pinned by
                // onCleanup for the driver's life) and the same factory that opened the primary path in
                // step 2 — one factory per connection, so every path it ever holds was bound by one
                // source-address discipline (#519).
                migration =
                    clientMigrationCapability(quicOptions.migration) {
                        MigrationCapability.Supported(
                            peer = PinnedSockAddr(peerSockAddr.address, peerSockAddr.length),
                            primaryLocal = PinnedSockAddr(localSockAddr.address, localSockAddr.length),
                            channelFactory = channelFactory,
                        )
                    },
                onCleanup = {
                    peerSockAddr.free()
                    localSockAddr.free()
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
                remoteAddress = peer,
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
        // The connection now owns its full teardown (config + scopes + the UDP channel) via onRelease,
        // so the `finally` below must release nothing. A verification failure past this point tears the
        // connection down via quicConnection.close() instead (inside verifyServerCertificateHashes).
        progress = ConnectProgress.ConnectionOwnsTeardown
        verifyServerCertificateHashes(
            quicOptions.serverCertificateHashes,
            bufferFactory,
            readPeerCertDer = quicConnection::readPeerCertDer,
            closeConnection = { quicConnection.close() },
            // JVM/Android extract the W3C constraint fields via java.security (Linux wires its BoringSSL
            // parser separately; until then it passes no parser and enforces hash-only).
            parseLeafFields = ::parsePinnedLeafFieldsJvm,
            now = tuning.wallClock(),
        )
        return quicConnection
    } finally {
        // Exhaustive on purpose: every stage has to state what it owes, so one added later cannot
        // inherit a branch by default. See [ConnectProgress].
        when (val reached = progress) {
            // Nothing acquired past the config yet.
            ConnectProgress.BeforeChannel -> {
                api.configFree(config)
                parentScope.cancel()
            }
            // Open and unowned: JvmQuicConnection.onRelease is the only other thing that closes it, and
            // it runs from close(), which a failed establishment never reaches. QuicheDriver.cleanup()
            // does not cover it either, deliberately — it tears down non-primary migration path channels,
            // but the primary one belongs to the connection.
            is ConnectProgress.ChannelOpen -> {
                runCatching { reached.channel.close() }
                api.configFree(config)
                parentScope.cancel()
            }
            // The connection owns config, scope and channel — releasing here would close a live socket.
            ConnectProgress.ConnectionOwnsTeardown -> Unit
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

/**
 * Load the platform's default CA trust anchors as PEM blocks (the Android CA-trust fix).
 *
 * quiche's bundled BoringSSL has no default verify paths, so a `verify_peer = true` handshake with no
 * caller-pinned anchors would have an empty trust store and reject every real server. We materialise
 * the platform default store ourselves: [TrustManagerFactory] with a null key store yields the JDK
 * `cacerts` on a desktop JVM and the system `AndroidCAStore` on Android, and we PEM-encode each
 * trusted root for [writeCaBundleToTempFile] → `load_verify_locations`.
 *
 * Returns an empty list if no default trust manager / no anchors are available, in which case the
 * caller loads nothing and the handshake behaves as before (BoringSSL default paths, if any).
 */
private fun loadPlatformDefaultCaCertificatesPem(): List<String> =
    runCatching {
        val tmf =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                )
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>()
            .flatMap { it.acceptedIssuers.asList() }
            .distinct()
            .map { encodeCertificateToPem(it) }
    }.getOrDefault(emptyList())

/**
 * PEM-encode a single X.509 certificate (`-----BEGIN CERTIFICATE-----` … base64 DER … `-----END…`).
 *
 * The DER `ByteArray` here is the certificate's own encoding crossing the `java.security` ↔ Base64
 * boundary, not a hot-path buffer — it never touches socket I/O.
 */
private fun encodeCertificateToPem(cert: java.security.cert.X509Certificate): String {
    @Suppress("NoByteArrayInProd") // java.security.cert API surface: getEncoded() returns DER bytes
    val der = cert.encoded
    val base64 =
        java.util.Base64
            .getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(der)
    return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----"
}
