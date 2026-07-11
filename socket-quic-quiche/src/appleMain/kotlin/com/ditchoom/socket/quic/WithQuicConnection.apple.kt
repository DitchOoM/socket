@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.nwudp.nw_udp_cancel
import com.ditchoom.socket.quic.nwudp.nw_udp_copy_local_sockaddr
import com.ditchoom.socket.quic.nwudp.nw_udp_copy_remote_sockaddr
import com.ditchoom.socket.quic.nwudp.nw_udp_create
import com.ditchoom.socket.quic.nwudp.nw_udp_set_state_handler
import com.ditchoom.socket.quic.nwudp.nw_udp_start
import com.ditchoom.socket.quic.quiche.QUICHE_PROTOCOL_VERSION
import com.ditchoom.socket.quic.quiche.quiche_config_free
import com.ditchoom.socket.quic.quiche.quiche_config_load_verify_locations_from_file
import com.ditchoom.socket.quic.quiche.quiche_config_new
import com.ditchoom.socket.quic.quiche.quiche_config_set_application_protos
import com.ditchoom.socket.quic.quiche.quiche_connect
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Network.nw_connection_t
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.sockaddr_storage
import platform.posix.unlink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.time.Duration

private const val MAX_CONN_ID_LEN = 20

/**
 * Suspend until the NWConnection [conn] reaches `ready` (state 3), or throw on a terminal failure
 * (failed=4 / cancelled=5). UDP "waiting" (state 1) is left non-terminal — on a viable path it
 * transitions to ready promptly; if it never does, the caller's [timeout] fires. Starts the
 * connection (so the local endpoint gets assigned) and cancels it if the awaiting coroutine is.
 */
private suspend fun awaitNwUdpReady(
    conn: nw_connection_t,
    host: String,
    port: Int,
    timeout: Duration,
) {
    withTimeout(timeout) {
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            nw_udp_set_state_handler(conn) { state, _, _, desc ->
                if (resumed) return@nw_udp_set_state_handler
                when (state) {
                    3 -> {
                        resumed = true
                        continuation.resume(Unit)
                    }
                    4, 5 -> {
                        resumed = true
                        continuation.resumeWithException(
                            SocketConnectionException.Refused(
                                host,
                                port,
                                platformError = desc ?: "NW UDP connection ${if (state == 4) "failed" else "cancelled"}",
                            ),
                        )
                    }
                }
            }
            nw_udp_start(conn)
            continuation.invokeOnCancellation { nw_udp_cancel(conn) }
        }
    }
}

/**
 * Build + establish an Apple quiche-backed [AppleQuicConnection] over an NWConnection-UDP datapath,
 * returning it ready to use. The returned connection owns its teardown via [AppleQuicConnection.close]
 * (driver destroy cancels the NWConnection via [AppleNwUdpChannel.close]; the `onRelease` lambda cancels
 * the per-call parent scope). Shared by [QuicheEngine.connect]; the [withQuicConnection] wrapper runs
 * the block and calls `close()`.
 *
 * The quiche `config` is freed during the build (quiche copies what it needs), so the connection
 * does not carry it. `memScoped` frees its arena on return, but the connection/driver hold only the
 * heap sockaddr copies + the NWConnection — nothing in the arena — so returning from inside it is safe.
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

            // Datapath: an NWConnection in UDP mode — the production Apple client path (NWPath migration
            // awareness + deterministic cancel; quiche owns QUIC itself). Create it and wait until ready,
            // at which point NW has assigned the local endpoint and resolved the peer, so quiche can read
            // real sockaddrs for quiche_connect + recv_info/send_info.
            val nwConn =
                nw_udp_create(hostname, port.toString())
                    ?: run {
                        quiche_config_free(config)
                        throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create NW UDP connection")
                    }
            try {
                awaitNwUdpReady(nwConn, hostname, port, timeout)
            } catch (t: Throwable) {
                nw_udp_cancel(nwConn)
                quiche_config_free(config)
                throw t
            }

            memScoped {
                // Pull the effective local + remote sockaddrs (BSD layout, sa_len set) from NW. A
                // sockaddr_storage-sized scratch covers IPv4 (16B) and IPv6 (28B).
                val addrCap = sizeOf<sockaddr_storage>().toInt()
                val localTmp = allocArray<ByteVar>(addrCap)
                val remoteTmp = allocArray<ByteVar>(addrCap)
                val localSockLen = nw_udp_copy_local_sockaddr(nwConn, localTmp, addrCap)
                val peerSockLen = nw_udp_copy_remote_sockaddr(nwConn, remoteTmp, addrCap)
                if (localSockLen <= 0 || peerSockLen <= 0) {
                    nw_udp_cancel(nwConn)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(
                        hostname,
                        port,
                        platformError = "NW endpoint sockaddrs unavailable (local=$localSockLen peer=$peerSockLen)",
                    )
                }

                // SCID — shared generator (20 random bytes, reset for read), seeded via the tuning.
                val scidBuf = generateScid(bufferFactory, tuning.random)
                val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

                val conn =
                    quiche_connect(
                        hostname,
                        scidPtr,
                        MAX_CONN_ID_LEN.convert(),
                        localTmp.reinterpret(),
                        localSockLen.convert(),
                        remoteTmp.reinterpret(),
                        peerSockLen.convert(),
                        config,
                    ) ?: run {
                        scidBuf.freeNativeMemory()
                        nw_udp_cancel(nwConn)
                        quiche_config_free(config)
                        throw SocketConnectionException.Refused(hostname, port, platformError = "quiche_connect failed")
                    }

                scidBuf.freeNativeMemory()

                // Pin the local + peer sockaddrs on the heap for recv_info/send_info (memScoped frees the
                // stack scratch on return; the driver holds these via onCleanup).
                val peerAddrBuf = bufferFactory.allocate(peerSockLen)
                platform.posix.memcpy(
                    peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!,
                    remoteTmp,
                    peerSockLen.convert(),
                )
                peerAddrBuf.resetForRead()

                val localAddrBuf = bufferFactory.allocate(localSockLen)
                platform.posix.memcpy(
                    localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!,
                    localTmp,
                    localSockLen.convert(),
                )
                localAddrBuf.resetForRead()

                quiche_config_free(config)

                // Create recvInfo/sendInfo via the QuicheApi
                val recvInfo =
                    api.recvInfoNew(
                        peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        peerSockLen,
                        localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        localSockLen,
                    )
                val sendInfo = api.sendInfoNew()

                val udpChannel = AppleNwUdpChannel(nwConn)
                val driver =
                    QuicheDriver(
                        api = api,
                        conn = QuicheConn(conn.rawValue.toLong()),
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
                        // Peer + primary local sockaddrs (pinned via onCleanup) for the initial path's
                        // recv_info/send_info. No udpChannelFactory: explicit quiche path migration via a
                        // second local socket does not map to NWConnection (NW owns path moves); the
                        // NWPath-driven migration glue is a tracked follow-up, so migrate() reports
                        // unsupported here (migrationEnabled gates on a non-null factory).
                        peerAddr = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        peerLen = peerSockLen,
                        primaryLocalAddr = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        primaryLocalLen = localSockLen,
                        udpChannelFactory = null,
                        onCleanup = {
                            peerAddrBuf.freeNativeMemory()
                            localAddrBuf.freeNativeMemory()
                        },
                    )

                val connJob = SupervisorJob(parentScope.coroutineContext[Job])
                val connScope = CoroutineScope(parentScope.coroutineContext + connJob)
                // Sockaddr buffers are freed by the driver's onCleanup (after quiche is done
                // dereferencing recvInfo.from/to during destroy, in close()) — matches the JVM client.
                // onRelease cancels the NWConnection (the driver's cleanup() deliberately skips the
                // PRIMARY path, so the connection setup owns it — mirrors the JVM wrapper) and then the
                // per-call parent scope; run once by close() after the block returns. Cancelling the NW
                // connection releases its UDP socket + dispatch resources and completes any outstanding
                // receive with nil (a started nw_connection_t is NOT freed by just dropping the ref —
                // Network.framework requires nw_connection_cancel).
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
                    // Phase-0: no X.509 leaf-field parser on Apple yet (Linux uses its BoringSslX509
                    // cinterop; Apple would use Security.framework). With null, leaf-hash matching still
                    // runs but the P-256/validity constraint enforcement is skipped. The H3 loopback
                    // suites pin no hashes, so this is a no-op there; a real Apple parser is a follow-up.
                    parseLeafFields = null,
                    now = tuning.wallClock(),
                )
                quicConn
            }
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
