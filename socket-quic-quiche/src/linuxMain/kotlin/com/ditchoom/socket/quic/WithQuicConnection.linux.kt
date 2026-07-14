@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_directory
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_file
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.linuxSockAddrLayout
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import platform.posix.F_OK
import platform.posix.access
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.mkstemp
import platform.posix.sockaddr
import platform.posix.unlink
import kotlin.time.Duration

private const val MAX_CONN_ID_LEN = 20

/**
 * Build + establish a Linux quiche-backed [LinuxQuicConnection] over io_uring, returning it ready
 * to use. The returned connection owns its teardown via [LinuxQuicConnection.close] (driver destroy
 * closes the fd; the `onRelease` lambda cancels the per-call parent scope). Shared by
 * [QuicheEngine.connect]; the [withQuicConnection] wrapper runs the block and calls `close()`.
 *
 * The quiche `config` is freed during the build (quiche copies what it needs), so the connection
 * does not carry it. `memScoped` frees its arena on return, but the connection/driver hold only the
 * heap sockaddr copies + the fd — nothing in the arena — so returning from inside it is safe.
 */
internal suspend fun buildLinuxQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
): LinuxQuicConnection {
    val api: QuicheApi = CinteropQuicheApi
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.Default)
    var established = false
    try {
        return run {
            val bufferFactory = connectionOptions.quicBufferFactory()

            val config =
                quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())
                    ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create quiche config")

            // ALPN
            val alpnBuf = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
            val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
            quiche_config_set_application_protos(config, alpnPtr, alpnBuf.remaining().convert())
            alpnBuf.freeNativeMemory()

            applyQuicOptions(quicOptions, LinuxQuicConfigCalls(config))

            // Pinned CA trust anchors (#99): load the PEM bundle as the verification
            // anchors so Linux enforces the same private-CA trust as Apple. quiche only
            // loads anchors from a file, so the bundle goes to a temp file the call reads
            // eagerly; we unlink it immediately after. verifyPeer is forced on in
            // applyQuicOptions whenever anchors are present.
            if (quicOptions.trustedCaCertificatesPem.isNotEmpty()) {
                val caBundlePath = writeCaBundleToTempFile(quicOptions.trustedCaCertificatesPem)
                try {
                    val rc = quiche_config_load_verify_locations_from_file(config, caBundlePath)
                    check(rc == 0) { "Failed to load trusted CA certificates: $rc" }
                } finally {
                    unlink(caBundlePath)
                }
            } else if (effectiveVerifyPeer(quicOptions)) {
                // verifyPeer is on with no pinned anchors: quiche/BoringSSL's compiled-in default verify
                // paths resolve the system CA store on a normal distro, but a bare K/N Linux container
                // may keep its trust store outside those defaults. Probe the standard system bundle/dir
                // and load the first present, so verifyPeer=true works without the caller pinning anchors
                // — the K/N-Linux companion to the JVM/Android default-anchor fix (#182). Best-effort: if
                // none exist we fall through to BoringSSL's built-in defaults (prior behaviour, unchanged).
                loadSystemCaTrust(config)
            }

            // Resolve the peer once (numeric literal → no DNS), then open a connected :socket-udp channel
            // to it (Phase 6 adapter-first cutover) with a QUIC-sized receive staging buffer.
            val peer = UdpSocket.resolve(hostname, port)
            val channel =
                UdpSocket.connect(
                    remoteHost = peer.host,
                    remotePort = peer.port,
                    receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                )
            val local =
                channel.localAddress
                    ?: throw SocketConnectionException.Refused(hostname, port, platformError = "connected UDP channel has no local address")

            // Encode the peer + local sockaddrs via the one differential-tested SocketAddressCodec (Phase 6
            // sockaddr SPI, replacing the memcpy'd kernel sockaddrs). A single encoding of each backs
            // quiche_connect AND recv_info; both stay pinned for the driver's life and are freed by
            // onCleanup so recv_info.from/to can never dangle.
            val codec = SocketAddressCodec(linuxSockAddrLayout)
            val peerSockAddr = codec.encodeToNative(peer, bufferFactory)
            val localSockAddr = codec.encodeToNative(local, bufferFactory)

            // SCID — shared generator (20 random bytes, reset for read), seeded via the tuning.
            val scidBuf = generateScid(bufferFactory, tuning.random)
            val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

            val connPtr =
                quiche_connect(
                    hostname,
                    scidPtr,
                    MAX_CONN_ID_LEN.convert(),
                    localSockAddr.address.toCPointer<sockaddr>(),
                    localSockAddr.length.convert(),
                    peerSockAddr.address.toCPointer<sockaddr>(),
                    peerSockAddr.length.convert(),
                    config,
                ) ?: run {
                    scidBuf.freeNativeMemory()
                    peerSockAddr.free()
                    localSockAddr.free()
                    runCatching { channel.close() }
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = "quiche_connect failed")
                }

            scidBuf.freeNativeMemory()
            quiche_config_free(config)

            // Create recvInfo/sendInfo from the same pinned sockaddr encodings.
            val recvInfo = api.recvInfoNew(peerSockAddr.address, peerSockAddr.length, localSockAddr.address, localSockAddr.length)
            val sendInfo = api.sendInfoNew()

            val udpChannel = DatagramChannelUdpChannel(channel)
            val driver =
                QuicheDriver(
                    api = api,
                    conn = QuicheConn(connPtr.rawValue.toLong()),
                    bufferFactory = bufferFactory,
                    recvInfo = recvInfo,
                    sendInfo = sendInfo,
                    udpChannel = udpChannel,
                    clientMode = true,
                    isServer = false,
                    keepAliveInterval = quicOptions.keepAliveInterval,
                    clock = tuning.clock,
                    driverContext = tuning.driverContext,
                    random = tuning.random,
                    recorder = tuning.recorderFactory(),
                    // Connection-migration wiring (Gap 4): the peer + primary local sockaddrs (kept pinned
                    // via onCleanup for the driver's life) and a factory that opens additional :socket-udp
                    // path sockets to the same peer. Mirrors the JVM client.
                    peerAddr = peerSockAddr.address,
                    peerLen = peerSockAddr.length,
                    primaryLocalAddr = localSockAddr.address,
                    primaryLocalLen = localSockAddr.length,
                    udpChannelFactory = UdpSocketChannelFactory(peer, codec, bufferFactory, QuicheDriver.MAX_DATAGRAM_SIZE),
                    onCleanup = {
                        peerSockAddr.free()
                        localSockAddr.free()
                    },
                )

            val connJob = SupervisorJob(parentScope.coroutineContext[Job])
            val connScope = CoroutineScope(parentScope.coroutineContext + connJob)
            // onRelease cancels the per-call scope (which cancels the primary reader parked in the
            // channel's terminal wait), then closes the primary UDP channel (fd + io_uring refcount).
            // Run once by close() after the block returns. Mirrors the JVM client.
            val quicConn =
                LinuxQuicConnection(
                    driver,
                    bufferFactory,
                    connScope,
                    onRelease = {
                        parentScope.cancel()
                        runCatching { udpChannel.close() }
                    },
                )
            quicConn.start()
            quicConn.awaitEstablished(timeout)
            // Connection owns teardown via onRelease now — set established first so the failure
            // `finally` won't double-cancel; a pin mismatch tears down via quicConn.close() instead.
            established = true
            verifyServerCertificateHashes(
                quicOptions.serverCertificateHashes,
                bufferFactory,
                readPeerCertDer = quicConn::readPeerCertDer,
                closeConnection = { quicConn.close() },
                // Linux extracts the W3C constraint fields via BoringSSL's X.509 parser (the same
                // ASN.1 decoder quiche links), then the shared policy enforces validity/P-256.
                parseLeafFields = ::parsePinnedLeafFieldsLinux,
                now = tuning.wallClock(),
            )
            quicConn
        }
    } finally {
        // On success the connection owns parentScope teardown via onRelease; only release here if
        // establishment threw before the connection took ownership.
        if (!established) parentScope.cancel()
    }
}

/**
 * Thin Linux QUIC connection wrapper backed by the shared [QuicheDriver].
 * Mirrors [JvmQuicConnection] — all heavy lifting is in the driver.
 */
internal class LinuxQuicConnection(
    private val driver: QuicheDriver,
    override val bufferFactory: BufferFactory,
    private val scope: CoroutineScope,
    private val onRelease: (() -> Unit)? = null,
) : QuicConnection,
    CoroutineScope by scope {
    override val state: StateFlow<QuicConnectionState> = driver.state

    private val datagramAdapter = DriverDatagramAdapter(driver)

    fun start() {
        driver.start(scope)
    }

    suspend fun awaitEstablished(timeout: Duration) {
        withTimeout(timeout) {
            state.first { it !is QuicConnectionState.Handshaking }
        }
    }

    override suspend fun openStream(): QuicByteStream = open(unidirectional = false)

    override suspend fun openUniStream(): QuicByteStream = open(unidirectional = true)

    private suspend fun open(unidirectional: Boolean): QuicByteStream {
        try {
            val deferred = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(deferred, unidirectional))
            val slot = deferred.await()
            val adapter = DriverStreamAdapter(driver, slot)
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, driver.streamReadPool))
        } catch (_: ClosedSendChannelException) {
            throw QuicCloseException(driver.closeReasonOr(QuicError.NoError), "connection closed")
        }
    }

    override suspend fun acceptStream(): QuicByteStream = driver.incomingStreams.receive()

    override fun streams(): Flow<QuicByteStream> = driver.incomingStreams.consumeAsFlow()

    /**
     * Read the peer's leaf certificate DER into the native buffer at [addr] (capacity [capacity]),
     * routed through the driver so the `quiche_conn_peer_cert` read is serialized with all other conn
     * access. Snprintf-style return (see [QuicheCmd.PeerCert]); used by the post-handshake
     * serverCertificateHashes verifier. Mirrors [JvmQuicConnection.readPeerCertDer].
     */
    suspend fun readPeerCertDer(
        addr: Long,
        capacity: Int,
    ): Int {
        val deferred = CompletableDeferred<Int>()
        driver.commands.send(QuicheCmd.PeerCert(addr, capacity, deferred))
        return deferred.await()
    }

    override suspend fun sendDatagram(buffer: ReadBuffer) = datagramAdapter.sendDatagram(buffer)

    override suspend fun receiveDatagram(): DatagramReceiveResult = datagramAdapter.receiveDatagram()

    override fun datagrams(): Flow<ReadBuffer> = datagramAdapter.datagrams()

    override fun maxDatagramSize(): MaxDatagramSize = datagramAdapter.maxDatagramSize()

    override val pathState: StateFlow<PathInfo> = driver.pathState

    override suspend fun migrate(
        localHost: String?,
        localPort: Int,
    ): MigrationResult =
        try {
            val deferred = CompletableDeferred<MigrationResult>()
            driver.commands.send(QuicheCmd.Migrate(localHost, localPort, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            MigrationResult.Failed("connection closed")
        }

    /** Driver-level terminal close (user-callable mid-block via [closeWithError]) — no [onRelease]. */
    private suspend fun driverClose(error: QuicError) {
        try {
            val deferred = CompletableDeferred<Unit>()
            driver.commands.send(QuicheCmd.Close(error, deferred))
            deferred.await()
        } catch (_: ClosedSendChannelException) {
            // Already closed
        }
        driver.destroy()
    }

    /**
     * Application-coded close (RFC 9000 §19.19) from inside the block — driver-only, so the running
     * connection scope is not torn down here. Lifecycle teardown ([onRelease]) runs when the
     * [withQuicConnection] wrapper calls [close] after the block returns.
     */
    override suspend fun closeWithError(errorCode: Long) = driverClose(QuicError.ApplicationError(errorCode))

    /** Full lifecycle teardown — the wrapper's `finally` calls this: driver close, then [onRelease]. */
    override suspend fun close(error: QuicError) {
        try {
            driverClose(error)
        } finally {
            onRelease?.invoke()
        }
    }
}

/** Adapts quiche cinterop to the platform-neutral [QuicConfigCalls] interface. */
internal class LinuxQuicConfigCalls(
    private val cfg: CPointer<cnames.structs.quiche_config>,
) : QuicConfigCalls {
    override fun setMaxIdleTimeout(ms: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_idle_timeout(cfg, ms.convert())

    override fun setMaxRecvUdpPayloadSize(size: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_recv_udp_payload_size(cfg, size.convert())

    override fun setMaxSendUdpPayloadSize(size: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_send_udp_payload_size(cfg, size.convert())

    override fun setInitialMaxData(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_data(cfg, v.convert())

    override fun setInitialMaxStreamDataBidiLocal(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_stream_data_bidi_local(cfg, v.convert())

    override fun setInitialMaxStreamDataBidiRemote(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_stream_data_bidi_remote(cfg, v.convert())

    override fun setInitialMaxStreamDataUni(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_stream_data_uni(cfg, v.convert())

    override fun setInitialMaxStreamsBidi(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_streams_bidi(cfg, v.convert())

    override fun setInitialMaxStreamsUni(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_max_streams_uni(cfg, v.convert())

    override fun setMaxConnectionWindow(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_connection_window(cfg, v.convert())

    override fun setMaxStreamWindow(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_stream_window(cfg, v.convert())

    override fun setDisableActiveMigration(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_disable_active_migration(cfg, v)

    override fun setActiveConnectionIdLimit(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_active_connection_id_limit(cfg, v.convert())

    override fun verifyPeer(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_verify_peer(cfg, v)

    override fun setCcAlgorithm(algo: Int) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_cc_algorithm(cfg, algo.convert())

    override fun enableHystart(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_enable_hystart(cfg, v)

    override fun setInitialCongestionWindowPackets(packets: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_initial_congestion_window_packets(cfg, packets.convert())

    override fun enablePacing(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_enable_pacing(cfg, v)

    override fun setMaxPacingRate(v: Long) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_set_max_pacing_rate(cfg, v.convert())

    override fun discoverPmtu(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_discover_pmtu(cfg, v)

    override fun enableEarlyData() =
        com.ditchoom.socket.quic.quiche
            .quiche_config_enable_early_data(cfg)

    override fun grease(v: Boolean) =
        com.ditchoom.socket.quic.quiche
            .quiche_config_grease(cfg, v)

    override fun enableDgram(
        recvQueueLen: Long,
        sendQueueLen: Long,
    ) = com.ditchoom.socket.quic.quiche
        .quiche_config_enable_dgram(cfg, true, recvQueueLen.convert(), sendQueueLen.convert())
}

/**
 * Effective peer-verification decision, mirroring [applyQuicOptions]'s policy: pinned hashes verify the
 * chain only under RequireBoth; otherwise verify unless explicitly disabled, and always when CA anchors
 * are pinned. Kept local to the connect path deliberately — the shared `resolveVerifyPeer` helper lands
 * with the JVM/Android default-anchor fix (#182); dedupe against it on rebase.
 */
private fun effectiveVerifyPeer(o: QuicOptions): Boolean =
    if (o.serverCertificateHashes.isNotEmpty()) {
        o.certificateHashVerification == CertificateHashVerification.RequireBoth
    } else {
        o.verifyPeer || o.trustedCaCertificatesPem.isNotEmpty()
    }

/**
 * Load the system CA trust store into [config] from the first standard location present. Tries the
 * common distro CA bundle files (Debian/Ubuntu/Alpine, RHEL/Fedora, BusyBox), then the Debian-style
 * hashed `/etc/ssl/certs` directory. No-op if none exist (BoringSSL's built-in defaults still apply).
 */
private fun loadSystemCaTrust(config: CPointer<cnames.structs.quiche_config>) {
    val bundleFiles =
        listOf(
            "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu/Alpine
            "/etc/pki/tls/certs/ca-bundle.crt", // RHEL/Fedora/CentOS
            "/etc/ssl/cert.pem", // Alpine/BusyBox/macOS-style
        )
    for (f in bundleFiles) {
        if (access(f, F_OK) == 0) {
            quiche_config_load_verify_locations_from_file(config, f)
            return
        }
    }
    if (access("/etc/ssl/certs", F_OK) == 0) {
        quiche_config_load_verify_locations_from_directory(config, "/etc/ssl/certs")
    }
}

/**
 * Write the supplied CA PEM blocks to a single `mkstemp` bundle file and return its path (#99).
 *
 * quiche/BoringSSL only loads verification anchors from a file path, so the in-memory PEM
 * must land on disk; the caller `unlink`s it once `load_verify_locations` has read it. The
 * bundle is written via `fputs` of the PEM text (no embedded NULs) — no `ByteArray` in our code.
 */
private fun writeCaBundleToTempFile(pems: List<String>): String =
    memScoped {
        val bundle = pems.joinToString("\n")
        val template = "/tmp/ditchoom-quic-ca-XXXXXX"
        val path = allocArray<ByteVar>(template.length + 1)
        for (i in template.indices) path[i] = template[i].code.toByte()
        path[template.length] = 0
        val fd = mkstemp(path)
        check(fd >= 0) { "mkstemp failed creating CA bundle temp file" }
        val fp =
            fdopen(fd, "w") ?: run {
                close(fd)
                error("fdopen failed for CA bundle temp file")
            }
        fputs(bundle, fp)
        fclose(fp)
        path.toKString()
    }
