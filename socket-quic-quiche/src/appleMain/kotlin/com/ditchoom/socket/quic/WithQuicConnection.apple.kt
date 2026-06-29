@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
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
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fputs
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.mkstemp
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.unlink
import kotlin.random.Random
import kotlin.time.Duration

private const val MAX_CONN_ID_LEN = 20

/**
 * Build + establish an Apple quiche-backed [AppleQuicConnection] over a POSIX UDP datapath, returning it ready
 * to use. The returned connection owns its teardown via [AppleQuicConnection.close] (driver destroy
 * closes the fd; the `onRelease` lambda cancels the per-call parent scope). Shared by
 * [QuicheEngine.connect]; the [withQuicConnection] wrapper runs the block and calls `close()`.
 *
 * The quiche `config` is freed during the build (quiche copies what it needs), so the connection
 * does not carry it. `memScoped` frees its arena on return, but the connection/driver hold only the
 * heap sockaddr copies + the fd — nothing in the arena — so returning from inside it is safe.
 */
internal suspend fun buildAppleQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
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
            }

            memScoped {
                val fd = socket(AF_INET, SOCK_DGRAM, 0)
                if (fd < 0) throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create UDP socket")

                val hints = alloc<addrinfo>()
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                val resultPtr = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
                if (getaddrinfo(hostname, port.toString(), hints.ptr, resultPtr.ptr) != 0) {
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = "DNS resolution failed")
                }
                val addrInfo = resultPtr.value!!.pointed
                val peerSockAddr: CPointer<sockaddr> = addrInfo.ai_addr!!
                val peerSockAddrLen = addrInfo.ai_addrlen

                if (connect(fd, peerSockAddr, peerSockAddrLen) < 0) {
                    freeaddrinfo(resultPtr.value)
                    close(fd)
                    quiche_config_free(config)
                    throw SocketConnectionException.Refused(hostname, port, platformError = "UDP connect failed")
                }

                // memScoped.alloc does not zero-init. If socket_getsockname fails silently,
                // sin_family is garbage and quiche_connect SIGABRTs through std_addr_from_c.
                val localAddr = alloc<sockaddr_in>()
                platform.posix.memset(localAddr.ptr, 0, sizeOf<sockaddr_in>().convert())
                val localAddrLen = alloc<kotlinx.cinterop.UIntVar>()
                localAddrLen.value = sizeOf<sockaddr_in>().convert()
                val gsRc = getsockname(fd, localAddr.ptr.reinterpret(), localAddrLen.ptr)
                check(gsRc == 0) { "socket_getsockname returned $gsRc" }
                check(localAddr.sin_family.toInt() == AF_INET) {
                    "socket_getsockname sin_family=${localAddr.sin_family.toInt()} (expected AF_INET=$AF_INET)"
                }

                // SCID
                val scidBuf = bufferFactory.allocate(MAX_CONN_ID_LEN)
                for (i in 0 until MAX_CONN_ID_LEN) {
                    scidBuf.writeByte(Random.nextInt(256).toByte())
                }
                scidBuf.resetForRead()
                val scidPtr = scidBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<UByteVar>()!!

                val conn =
                    quiche_connect(
                        hostname,
                        scidPtr,
                        MAX_CONN_ID_LEN.convert(),
                        localAddr.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().convert(),
                        peerSockAddr,
                        peerSockAddrLen,
                        config,
                    ) ?: run {
                        scidBuf.freeNativeMemory()
                        freeaddrinfo(resultPtr.value)
                        close(fd)
                        quiche_config_free(config)
                        throw SocketConnectionException.Refused(hostname, port, platformError = "quiche_connect failed")
                    }

                scidBuf.freeNativeMemory()

                // Copy sockaddrs to heap buffers (memScoped will free originals)
                val peerAddrBuf = bufferFactory.allocate(peerSockAddrLen.toInt())
                val peerAddrDst = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                platform.posix.memcpy(peerAddrDst, peerSockAddr.reinterpret<ByteVar>(), peerSockAddrLen.convert())
                peerAddrBuf.resetForRead()

                val localAddrBuf = bufferFactory.allocate(sizeOf<sockaddr_in>().toInt())
                val localAddrDst = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!
                platform.posix.memcpy(localAddrDst, localAddr.ptr, sizeOf<sockaddr_in>().convert())
                localAddrBuf.resetForRead()

                freeaddrinfo(resultPtr.value)
                quiche_config_free(config)

                // Create recvInfo/sendInfo via the QuicheApi
                val recvInfo =
                    api.recvInfoNew(
                        peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        peerSockAddrLen.toInt(),
                        localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        sizeOf<sockaddr_in>().toInt(),
                    )
                val sendInfo = api.sendInfoNew()

                val udpChannel = AppleUdpChannel(fd)
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
                        // Connection-migration wiring (Gap 4): the peer + primary local sockaddrs
                        // (kept pinned via onCleanup for the driver's life) and a factory that opens
                        // additional io_uring path sockets to the same peer. Mirrors the JVM client.
                        peerAddr = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        peerLen = peerSockAddrLen.toInt(),
                        primaryLocalAddr = localAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                        primaryLocalLen = sizeOf<sockaddr_in>().toInt(),
                        udpChannelFactory =
                            AppleUdpChannelFactory(
                                peerSockAddrAddress = peerAddrBuf.nativeMemoryAccess!!.nativeAddress.toLong(),
                                peerSockAddrLen = peerSockAddrLen.toInt(),
                                bufferFactory = bufferFactory,
                            ),
                        onCleanup = {
                            peerAddrBuf.freeNativeMemory()
                            localAddrBuf.freeNativeMemory()
                        },
                    )

                val connJob = SupervisorJob(parentScope.coroutineContext[Job])
                val connScope = CoroutineScope(parentScope.coroutineContext + connJob)
                // Sockaddr buffers are freed by the driver's onCleanup (after quiche is done
                // dereferencing recvInfo.from/to during destroy, in close()) — matches the JVM
                // client. onRelease only cancels the per-call parent scope (the driver's destroy
                // closes the io_uring fd), run once by close() after the block returns.
                val quicConn = AppleQuicConnection(driver, bufferFactory, connScope, onRelease = { parentScope.cancel() })
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

    private val datagramAdapter = DriverDatagramAdapter(driver, bufferFactory)

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
            return QuicByteStream(slot.id, QuicheStreamByteStream(slot.id, adapter, bufferFactory))
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
