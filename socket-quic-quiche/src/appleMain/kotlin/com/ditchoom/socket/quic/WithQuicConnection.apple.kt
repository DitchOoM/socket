@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class, com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_file
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_connect
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpConnectException
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.appleSockAddrLayout
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
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.sockaddr
import platform.posix.unlink
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.time.Duration

private const val MAX_CONN_ID_LEN = 20

/**
 * Build + establish an Apple quiche-backed [AppleQuicConnection] over a `:socket-udp` NWConnection-UDP
 * datapath (Phase 6 adapter-first cutover), returning it ready to use. The returned connection owns its
 * teardown via [AppleQuicConnection.close] (the `onRelease` lambda closes the primary channel — which
 * cancels the NWConnection — then cancels the per-call parent scope). Shared by [QuicheEngine.connect];
 * the [withQuicConnection] wrapper runs the block and calls `close()`.
 *
 * The quiche `config` is freed during the build (quiche copies what it needs), so the connection does
 * not carry it. The peer/local sockaddr encodings are pinned for the driver's life (freed via onCleanup).
 */
internal suspend fun buildAppleQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
    // Determinism seams (RFC_DETERMINISTIC_SIMULATION.md §3.1) — production defaults are
    // byte-identical to the pre-seam behaviour; the sim harness injects its own.
    tuning: QuicheDriverTuning = QuicheDriverTuning(),
): AppleQuicConnection {
    val api: QuicheApi = CinteropQuicheApi
    val parentJob = SupervisorJob()
    val parentScope = CoroutineScope(parentJob + Dispatchers.Default)
    var established = false
    try {
        return run {
            val bufferFactory = connectionOptions.quicBufferFactory()
            // One recv pool per connection, injected into both the :socket-udp channel (allocates each
            // datagram from it) and the driver (frees each back to it) — no receive copy (B2 elimination).
            val recvBufPool = QuicheDriver.newRecvBufPool(bufferFactory)

            val config =
                quiche_config_new(QUICHE_PROTOCOL_VERSION.convert())
                    ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create quiche config")

            // ALPN
            val alpnBuf = encodeAlpnList(quicOptions.alpnProtocols, bufferFactory)
            val alpnPtr = alpnBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!
            quiche_config_set_application_protos(config, alpnPtr, alpnBuf.remaining().convert())
            alpnBuf.freeNativeMemory()

            applyQuicOptions(quicOptions, AppleQuicConfigCalls(config))

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
                // verifyPeer is on with no pinned anchors. On macOS quiche/BoringSSL's compiled-in
                // default verify paths resolve the system store, but the iOS family ships no
                // filesystem CA store, so the defaults find nothing and every public-CA handshake
                // fails (tlsAlert 48). Load the embedded Mozilla roots there — the Apple companion to
                // the Linux /etc/ssl probe (#185) and the JVM/Android default-anchor fix (#182).
                loadAppleSystemCaTrust(config)
            }

            // Datapath: open a connected :socket-udp channel over NWConnection-UDP (Phase 6 adapter-first
            // cutover). UdpSocket.connect waits until NW is ready + assigns the local endpoint, cancellably
            // (so the QUIC timeout interrupts a stuck connect and never leaks the nw_connection_t); we map
            // its typed connect failure to the QUIC error contract.
            val peer = UdpSocket.resolve(hostname, port)
            val channel =
                try {
                    withTimeout(timeout) {
                        UdpSocket.connect(
                            remoteHost = peer.host,
                            remotePort = peer.port,
                            receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                            bufferFactory = recvBufPool,
                        )
                    }
                } catch (e: UdpConnectException) {
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = e.message)
                } catch (t: Throwable) {
                    quiche_config_free(config)
                    throw t
                }
            val local =
                channel.localAddress ?: run {
                    runCatching { channel.close() }
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(
                        hostname,
                        port,
                        platformError = "connected UDP channel has no local address",
                    )
                }

            // Encode the peer + local sockaddrs via the one SocketAddressCodec (Phase 6 sockaddr SPI, BSD
            // layout, replacing the NW sockaddr pull + memcpy). A single encoding of each backs
            // quiche_connect AND recv_info; both stay pinned for the driver's life and are freed by
            // onCleanup so recv_info.from/to can never dangle.
            val codec = SocketAddressCodec(appleSockAddrLayout)
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
                    recvBufPool = recvBufPool,
                    clientMode = true,
                    isServer = false,
                    keepAliveInterval = quicOptions.keepAliveInterval,
                    clock = tuning.clock,
                    driverContext = tuning.driverContext,
                    random = tuning.random,
                    recorder = tuning.recorderFactory(),
                    // Peer + primary local sockaddrs (pinned via onCleanup) for the initial path's
                    // recv_info/send_info. No udpChannelFactory: explicit quiche path migration via a second
                    // local socket does not map to NWConnection (NW owns path moves); the NWPath-driven
                    // migration glue is a tracked follow-up, so migrate() reports unsupported here.
                    peerAddr = peerSockAddr.address,
                    peerLen = peerSockAddr.length,
                    primaryLocalAddr = localSockAddr.address,
                    primaryLocalLen = localSockAddr.length,
                    udpChannelFactory = null,
                    onCleanup = {
                        peerSockAddr.free()
                        localSockAddr.free()
                    },
                )

            val connJob = SupervisorJob(parentScope.coroutineContext[Job])
            val connScope = CoroutineScope(parentScope.coroutineContext + connJob)
            // onRelease closes the primary channel (cancels the NWConnection, releasing its UDP socket +
            // dispatch resources — a started nw_connection_t is NOT freed by dropping the ref), then cancels
            // the per-call scope (which cancels the primary reader parked in the channel's terminal wait).
            val quicConn =
                AppleQuicConnection(
                    driver,
                    bufferFactory,
                    connScope,
                    onRelease = {
                        runCatching { udpChannel.close() }
                        parentScope.cancel()
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
                // Phase-0: no X.509 leaf-field parser on Apple yet (Linux uses its BoringSslX509 cinterop;
                // Apple would use Security.framework). With null, leaf-hash matching still runs but the
                // P-256/validity constraint enforcement is skipped. A real Apple parser is a follow-up.
                parseLeafFields = null,
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
internal class AppleQuicConnection(
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
internal class AppleQuicConfigCalls(
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
 * Effective peer-verification decision, mirroring [applyQuicOptions]'s policy (and the Linux
 * connect path's local copy): pinned hashes verify the chain only under RequireBoth; otherwise
 * verify unless explicitly disabled, and always when CA anchors are pinned.
 */
private fun effectiveVerifyPeer(o: QuicOptions): Boolean =
    if (o.serverCertificateHashes.isNotEmpty()) {
        o.certificateHashVerification == CertificateHashVerification.RequireBoth
    } else {
        o.verifyPeer || o.trustedCaCertificatesPem.isNotEmpty()
    }

/**
 * Load the default trust anchors for [config] on Apple when verifyPeer is on but no anchors are
 * pinned. macOS keeps BoringSSL's compiled-in default verify paths (they resolve `/etc/ssl/cert.pem`,
 * which macOS ships) — a no-op here, unchanged behaviour. The iOS family (device + simulator) has no
 * such filesystem store, so the embedded Mozilla root bundle ([MOZILLA_CA_ROOTS_PEM], generated from
 * mozilla-ca/cacert.pem) is written to a temp file and loaded as the anchor set. BoringSSL then does
 * full RFC 5280 chain validation internally against those roots during the handshake — we never need
 * the peer chain ourselves (quiche only surfaces the leaf). Branching on [Platform.osFamily] rather
 * than probing the filesystem keeps this deterministic on the simulator, which can otherwise see the
 * host Mac's `/etc/ssl`. SecTrust/keychain delegation (honouring MDM-installed + OS-revoked roots) is
 * the tracked follow-up to this bundled-roots interim (#186).
 */
private fun loadAppleSystemCaTrust(config: CPointer<cnames.structs.quiche_config>) {
    if (Platform.osFamily == OsFamily.MACOSX) return
    val caBundlePath = writeCaBundleToTempFile(listOf(MOZILLA_CA_ROOTS_PEM))
    try {
        val rc = quiche_config_load_verify_locations_from_file(config, caBundlePath)
        check(rc == 0) { "Failed to load bundled Mozilla CA roots: $rc" }
    } finally {
        unlink(caBundlePath)
    }
}

/**
 * Write the supplied CA PEM blocks to a single `mkstemp` bundle file and return its path (#99).
 *
 * quiche/BoringSSL only loads verification anchors from a file path, so the in-memory PEM
 * must land on disk; the caller `unlink`s it once `load_verify_locations` has read it. The
 * bundle is written via `fputs` of the PEM text (no embedded NULs) — no `ByteArray` in our code.
 *
 * The temp file goes in `$TMPDIR` (falling back to `/tmp`): iOS sandboxes processes so `/tmp` is not
 * writable there — only the per-process `NSTemporaryDirectory()` (exposed as `TMPDIR`) is. macOS sets
 * `TMPDIR` too, so this single path is correct on every Apple target.
 */
private fun writeCaBundleToTempFile(pems: List<String>): String =
    memScoped {
        val bundle = pems.joinToString("\n")
        val dir = getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"
        val template = "$dir/ditchoom-quic-ca-XXXXXX"
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
