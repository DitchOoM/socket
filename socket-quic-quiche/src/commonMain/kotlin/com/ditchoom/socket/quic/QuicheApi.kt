package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * Platform-agnostic interface for quiche's C API.
 *
 * All data passes as native addresses — no byte array copies anywhere.
 * Opaque quiche handles use value classes ([QuicheConfig], [QuicheConn], etc.)
 * for compile-time type safety at zero runtime cost.
 *
 * Platform implementations decode format-specific results into common Kotlin types:
 * - [StreamRecvResult] replaces packed Long (JNI) or output params (cinterop)
 * - [Duration] replaces raw nanosecond Long with platform-specific "no timeout" sentinel
 * - [QuicStreamId] replaces raw Long stream identifiers
 * - [QuicError] replaces raw app-boolean + error-code pairs
 *
 * Buffer allocation/pooling is the caller's responsibility via [com.ditchoom.buffer.BufferFactory].
 *
 * Implementations:
 * - JVM (JDK < 21): `JniQuicheApi` — JNI external calls
 * - JVM (JDK 21+): `FfmQuicheApi` — FFM downcalls
 * - Linux/Native: `CinteropQuicheApi` — K/N cinterop
 */
interface QuicheApi {
    // --- Config ---

    fun configNew(version: Int): QuicheConfig

    fun configFree(config: QuicheConfig)

    fun configSetApplicationProtos(
        config: QuicheConfig,
        protosAddr: Long,
        protosLen: Int,
    ): Int

    fun configSetMaxIdleTimeout(
        config: QuicheConfig,
        timeout: Long,
    )

    fun configSetMaxRecvUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    )

    fun configSetMaxSendUdpPayloadSize(
        config: QuicheConfig,
        size: Long,
    )

    fun configSetInitialMaxData(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataBidiLocal(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataBidiRemote(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamDataUni(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamsBidi(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetInitialMaxStreamsUni(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetDisableActiveMigration(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configSetActiveConnectionIdLimit(
        config: QuicheConfig,
        v: Long,
    )

    fun configVerifyPeer(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configEnablePacing(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configSetMaxPacingRate(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetCcAlgorithm(
        config: QuicheConfig,
        algo: Int,
    )

    fun configEnableHystart(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configSetInitialCongestionWindowPackets(
        config: QuicheConfig,
        packets: Long,
    )

    fun configSetMaxConnectionWindow(
        config: QuicheConfig,
        v: Long,
    )

    fun configSetMaxStreamWindow(
        config: QuicheConfig,
        v: Long,
    )

    fun configDiscoverPmtu(
        config: QuicheConfig,
        v: Boolean,
    )

    fun configEnableEarlyData(config: QuicheConfig)

    fun configGrease(
        config: QuicheConfig,
        v: Boolean,
    )

    // --- Connection ---

    fun connect(
        serverNameAddr: Long,
        serverNameLen: Int,
        scidAddr: Long,
        scidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ): QuicheConn

    fun connFree(conn: QuicheConn)

    fun connRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        recvInfo: QuicheRecvInfo,
    ): Int

    fun connSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
        sendInfo: QuicheSendInfo,
    ): Int

    /**
     * Read from a QUIC stream. Returns a [StreamRecvResult] that indicates data, done, or error.
     * Implementations decode the platform-specific result format (packed Long on JNI,
     * output parameters on cinterop) into this common type.
     */
    fun connStreamRecv(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult

    /**
     * Write to a QUIC stream. Returns a [StreamSendResult] carrying the raw quiche return plus the peer's
     * application error code (when the peer aborted the stream and the backend exposes `out_error_code`).
     */
    fun connStreamSend(
        conn: QuicheConn,
        streamId: QuicStreamId,
        buf: Long,
        bufLen: Int,
        fin: Boolean,
    ): StreamSendResult

    /**
     * Shut down one [direction] of [streamId] with application error code [err]
     * (`quiche_conn_stream_shutdown`): [direction] 0 = read (sends STOP_SENDING), 1 = write (sends
     * RESET_STREAM). Returns 0 on success or a negative quiche error code.
     */
    fun connStreamShutdown(
        conn: QuicheConn,
        streamId: QuicStreamId,
        direction: Int,
        err: Long,
    ): Int

    // --- Unreliable datagrams (RFC 9221) ---

    /** Enable DATAGRAM frames on the config with the given receive/send queue lengths. */
    fun configEnableDgram(
        config: QuicheConfig,
        enabled: Boolean,
        recvQueueLen: Long,
        sendQueueLen: Long,
    )

    /**
     * Send one datagram from [buf]. Returns the number of bytes written (== [bufLen] on success),
     * or a negative quiche error code ([QuicheDriver.QUICHE_ERR_DONE] when the send queue is full).
     */
    fun connDgramSend(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int

    /**
     * Receive one datagram into [buf]. Decoded like [connStreamRecv]: [StreamRecvResult.Data] (always
     * `fin = false` — datagrams have no stream end), [StreamRecvResult.Done] when none is queued, or
     * [StreamRecvResult.Error] on a negative quiche code.
     */
    fun connDgramRecv(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): StreamRecvResult

    /** True when at least one datagram is queued for receive. (Wraps `dgram_recv_front_len`.) */
    fun hasReadableDgram(conn: QuicheConn): Boolean

    /**
     * Maximum payload one [connDgramSend] can carry on the current path, as a [MaxDatagramSize]:
     * [MaxDatagramSize.Bytes] when sendable, or [MaxDatagramSize.Unavailable] when the peer never
     * advertised `max_datagram_frame_size`. Backends normalize quiche's negative/none into the sealed type.
     */
    fun connDgramMaxWritableLen(conn: QuicheConn): MaxDatagramSize

    fun connIsEstablished(conn: QuicheConn): Boolean

    fun connIsClosed(conn: QuicheConn): Boolean

    fun connIsTimedOut(conn: QuicheConn): Boolean

    /**
     * Returns the timeout duration until the next quiche timer fires, or `null` if no timeout is set.
     * Implementations normalize platform-specific "no timeout" sentinels (UINT64_MAX on native,
     * negative Long on JVM) into `null`.
     */
    fun connTimeout(conn: QuicheConn): Duration?

    fun connOnTimeout(conn: QuicheConn)

    /**
     * Schedule an ack-eliciting packet (a PING) on the active path — `quiche_conn_send_ack_eliciting`.
     * The packet is emitted by the next [connSend] flush; on receipt the peer ACKs it, resetting both
     * endpoints' idle timers. Used to implement reactive keepalive. Returns 0 on success or a negative
     * quiche error code (e.g. `QUICHE_ERR_DONE` when nothing needs sending).
     */
    fun connSendAckEliciting(conn: QuicheConn): Int

    /**
     * Close the connection with the given [error].
     * Implementations decompose [QuicError] into the C API's `app` flag and error code.
     */
    fun connClose(
        conn: QuicheConn,
        error: QuicError,
    ): Int

    /**
     * Copy the peer's TLS **leaf** certificate DER (`quiche_conn_peer_cert`) into the native buffer at
     * [buf] (capacity [bufLen] bytes), used for `serverCertificateHashes` leaf-hash pinning. Returns:
     * - `0` — the peer presented no certificate (e.g. `verify_peer` was off and none was sent, or this
     *   is called before the handshake processed the peer's certificate);
     * - `N in 1..bufLen` — the DER length; the first `N` bytes of [buf] hold the certificate;
     * - `N > bufLen` — the DER length, but **nothing was copied** (it did not fit); the caller must
     *   re-allocate [buf] to at least `N` bytes and call again (snprintf-style two-pass read).
     *
     * The DER lives in quiche-owned memory valid only for [conn]'s lifetime, so it is copied out here.
     * quiche is single-threaded — call this only from the driver loop (via [QuicheCmd.PeerCert]).
     */
    fun connPeerCert(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int

    /**
     * The peer's CONNECTION_CLOSE reason as a typed [QuicError], or `null` if the peer has not closed
     * the connection (we closed first, or it is still open). Maps `quiche_conn_peer_error`, decoding the
     * C API's `is_app` flag + numeric code into the sealed hierarchy — application closes (frame 0x1d) →
     * [QuicError.ApplicationError]; transport closes (frame 0x1c) → [QuicError.fromTransportCode] (which
     * folds the 0x100..0x1ff range into [QuicError.CryptoError]). No stringly errors: the wire code
     * becomes an exhaustive [QuicError]. Used to populate [QuicConnectionState.Closed.error] so a remote
     * close surfaces its real reason instead of [QuicError.NoError].
     *
     * quiche is single-threaded — call only from the driver loop. Bound on every real backend (FFM,
     * JNI/Android, cinterop); the interface default returns `null` only for test doubles that don't model
     * a close reason.
     */
    fun connPeerError(conn: QuicheConn): QuicError? = null

    /**
     * Our local CONNECTION_CLOSE reason as a typed [QuicError], or `null` if we have not closed locally.
     * Maps `quiche_conn_local_error`. Complements [connPeerError]: when quiche itself tears the
     * connection down (it rejected the peer's transport parameters, the TLS handshake failed, a protocol
     * violation, …) the cause is here, not in the peer error. Same typed decoding as [connPeerError].
     * Bound on every real backend (FFM, JNI/Android, cinterop); the interface default returns `null`
     * only for test doubles that don't model a close reason.
     */
    fun connLocalError(conn: QuicheConn): QuicError? = null

    /**
     * Enable qlog tracing on [conn], writing the connection's event log to [path]
     * (`quiche_conn_set_qlog_path`); [title] and [desc] populate the qlog's `title`/`description`
     * header fields. Returns `true` if qlog was enabled.
     *
     * Diagnostics only: [QuicheDriver] calls this once, env-gated on `QUIC_QLOG_DIR`, on the driver
     * coroutine right after the connection is created (quiche is single-threaded — it must not be called
     * concurrently with the driver loop). One `.sqlog` file per connection. The interface default is a
     * no-op returning `false`, so test doubles need not implement it; every real backend (FFM, JNI/Android,
     * cinterop) overrides it. Strings (not native addresses) so the JNI backend can `GetStringUTFChars`.
     */
    fun connSetQlogPath(
        conn: QuicheConn,
        path: String,
        title: String,
        desc: String,
    ): Boolean = false

    // --- Path migration ---

    /**
     * Probe the given path for reachability. Returns 0 on success or a negative quiche error code.
     * [seqOut] is the native address of a `uint64_t` buffer the implementation writes the probed
     * path's sequence number to.
     */
    fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int

    /**
     * Supply a spare source connection ID to the peer (`quiche_conn_new_scid`). quiche does
     * not auto-issue CIDs — without this the peer never gets a NEW_CONNECTION_ID and has no
     * spare destination CID to migrate to. [scidAddr] points at a [scidLen]-byte CID,
     * [resetTokenAddr] at a 16-byte stateless-reset token; the issued sequence number is
     * written to [seqOut]. Returns the sequence number (>= 0) or a negative quiche error.
     */
    fun connNewScid(
        conn: QuicheConn,
        scidAddr: Long,
        scidLen: Int,
        resetTokenAddr: Long,
        retireIfNeeded: Boolean,
        seqOut: Long,
    ): Int

    /**
     * Migrate the connection to the given local/peer path. Returns 0 on success or a negative
     * quiche error code. [seqOut] is the native address of a `uint64_t` buffer the implementation
     * writes the migrated path's sequence number to.
     */
    fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
        seqOut: Long,
    ): Int

    /**
     * Migrate the connection's source (local) address only. Returns 0 on success or a negative
     * quiche error code. [seqOut] is the native address of a `uint64_t` buffer the implementation
     * writes the migrated path's sequence number to.
     */
    fun connMigrateSource(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        seqOut: Long,
    ): Int

    /** Returns the number of source connection IDs that are available to migrate to. */
    fun connAvailableDcids(conn: QuicheConn): Long

    /** Returns the number of source connection IDs that are still left to be provided to the peer. */
    fun connScidsLeft(conn: QuicheConn): Long

    /**
     * Poll and CONSUME the next path event (frees it before returning). Returns the
     * event type, or null if none pending. For every type except
     * [QuichePathEventType.ReusedSourceConnectionId], fills the caller-provided
     * sockaddr_storage native buffers [localOut]/[peerOut] and the socklen_t out-words
     * [localLenOut]/[peerLenOut] with the event's local/peer addresses. For
     * ReusedSourceConnectionId the type is returned but addresses are NOT surfaced
     * (its extra old/new-tuple + CID-seq fields are out of scope this slice); set
     * both length out-words to 0 in that case.
     */
    fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType?

    // --- Server-side ---

    fun configLoadCertChainFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int

    fun configLoadPrivKeyFromPemFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int

    /**
     * Load trusted CA certificates from a PEM bundle file as the verification anchors
     * (`quiche_config_load_verify_locations_from_file`). [pathAddr] is the native address
     * of a NUL-terminated path string. Returns 0 on success, negative on error. (#99)
     */
    fun configLoadVerifyLocationsFromFile(
        config: QuicheConfig,
        pathAddr: Long,
    ): Int

    fun headerInfo(
        buf: Long,
        bufLen: Int,
        dcil: Int,
        versionOut: Long,
        typeOut: Long,
        scidOut: Long,
        scidLenOut: Long,
        dcidOut: Long,
        dcidLenOut: Long,
        tokenOut: Long,
        tokenLenOut: Long,
    ): Int

    fun accept(
        scidAddr: Long,
        scidLen: Int,
        odcidAddr: Long,
        odcidLen: Int,
        localAddr: Long,
        localAddrLen: Int,
        peerAddr: Long,
        peerAddrLen: Int,
        config: QuicheConfig,
    ): QuicheConn

    fun negotiateVersion(
        scidAddr: Long,
        scidLen: Int,
        dcidAddr: Long,
        dcidLen: Int,
        outAddr: Long,
        outLen: Int,
    ): Int

    // --- Stream iteration ---

    /** Get iterator over readable streams. Check [QuicheStreamIter.isExhausted] before iterating. */
    fun connReadable(conn: QuicheConn): QuicheStreamIter

    /** Get iterator over writable streams. Check [QuicheStreamIter.isExhausted] before iterating. */
    fun connWritable(conn: QuicheConn): QuicheStreamIter

    /**
     * Advance stream iterator. Returns the next [QuicStreamId], or `null` when exhausted.
     * Implementations handle the output-parameter pattern internally.
     */
    fun streamIterNext(iter: QuicheStreamIter): QuicStreamId?

    fun streamIterFree(iter: QuicheStreamIter)

    // --- Helpers ---

    fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo

    fun recvInfoFree(info: QuicheRecvInfo)

    fun sendInfoNew(): QuicheSendInfo

    fun sendInfoFree(info: QuicheSendInfo)

    fun sendInfoToAddr(info: QuicheSendInfo): Long

    fun sendInfoToAddrLen(info: QuicheSendInfo): Int

    /**
     * Native pointer to the `from` (local egress) sockaddr quiche filled in after
     * [connSend]. Mirror of [sendInfoToAddr]. Used by the multi-socket driver to
     * route each outgoing datagram to the path socket bound to this local address
     * (slice 3 connection migration). The pointer is into the send_info struct and
     * is valid until the next [connSend] overwrites it.
     */
    fun sendInfoFromAddr(info: QuicheSendInfo): Long

    fun sendInfoFromAddrLen(info: QuicheSendInfo): Int

    // --- sockaddr decode (slice 3 migration) ---
    // Reverse of SockAddrUtil's encode. The JNI backend forwards to native helpers in
    // quiche_jni.c (native code knows the platform's sockaddr layout — BSD sin_len,
    // AF_INET6 = 10/30/23); FFM and cinterop read the struct directly. Used to turn the
    // raw sockaddr quiche fills (sendInfo.from, path-event addresses) into a [PathKey].

    /** IP version of the sockaddr at native [addr]: 4 (IPv4), 6 (IPv6), or 0 (unknown). */
    fun sockAddrFamily(addr: Long): Int

    /** UDP port (host byte order) of the sockaddr at [addr]. */
    fun sockAddrPort(addr: Long): Int

    /** IPv4 address bits of the sockaddr at [addr] — opaque identity, valid when family == 4. */
    fun sockAddrV4(addr: Long): Long

    /** High 8 bytes of the IPv6 address at [addr] — opaque identity, valid when family == 6. */
    fun sockAddrV6Hi(addr: Long): Long

    /** Low 8 bytes of the IPv6 address at [addr] — opaque identity, valid when family == 6. */
    fun sockAddrV6Lo(addr: Long): Long
}
