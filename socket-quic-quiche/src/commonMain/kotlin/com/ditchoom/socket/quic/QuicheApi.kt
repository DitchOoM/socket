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
     * Pin libquiche's internal clock for the **calling thread** to [nanos] — a monotonic reading in
     * nanoseconds from libquiche's fixed per-process anchor (RFC §6.1 caller-clock patch). While set,
     * every internal `Instant::now()` in the patched libquiche (loss/PTO/RTT/pacing/congestion — 72
     * sites) returns this virtual instant instead of the real wall clock, making QUIC Tier-A simulation
     * bit-exact. The value is thread-local: quiche is single-threaded per connection in our usage, so
     * the driver pushes it in the same synchronous frame as each connection call ([CallerClockQuicheApi]).
     *
     * Simulation-only. Production never calls this — [RealDriverClock] reports [DriverTime.Real], so the
     * syncing decorator is never installed and libquiche keeps its own wall clock (zero cost, no
     * behaviour change). Requires the caller-clock source patch applied by the build (marker-guarded in
     * `build.gradle.kts`); on an unpatched libquiche the underlying C symbol would be absent.
     *
     * The interface default is a **no-op**: a Tier-A sim over a *test-double* [QuicheApi] models time in
     * Kotlin and has no libquiche clock to pin, and only the four real backends (FFM, JNI/Android,
     * cinterop apple + linux) — which override this — reach the patched C symbol. This mirrors the
     * default-for-test-doubles convention already used by [connStats]/[connPeerError]/[connSetQlogPath].
     */
    fun setThreadVirtualTimeNanos(nanos: Long) {}

    /**
     * Release the calling thread's virtual-clock pin set by [setThreadVirtualTimeNanos], restoring the
     * real wall clock for subsequent internal `Instant::now()` reads on this thread. Called when a
     * simulated connection closes so a pooled OS thread never leaks virtual time into later work.
     * Default no-op for the same reason as [setThreadVirtualTimeNanos]; overridden by the real backends.
     */
    fun clearThreadVirtualTime() {}

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
     * Copy the negotiated ALPN protocol (`quiche_conn_application_proto`) into the native buffer at
     * [buf] (capacity [bufLen] bytes). Same snprintf-style contract as [connPeerCert]:
     * - `0` — no protocol negotiated yet (handshake not far enough along), or the backend does not
     *   expose it (the interface default — test doubles that don't model ALPN are automatically fine);
     * - `N in 1..bufLen` — the protocol name length; the first `N` bytes of [buf] hold it (ASCII);
     * - `N > bufLen` — the length, but **nothing was copied**; re-allocate and call again. (ALPN
     *   identifiers are at most 255 bytes per RFC 7301, so a 255-byte buffer never needs the retry.)
     *
     * The bytes quiche returns point into conn-owned memory, so they are copied out here. quiche is
     * single-threaded — call this only from the driver loop.
     */
    fun connApplicationProto(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = 0

    /**
     * Copy quiche's **stable** connection trace id (`quiche_conn_trace_id`) into [buf]. Same
     * snprintf-style contract as [connApplicationProto]; `0` also means "this backend does not bind it"
     * (the interface default), which surfaces publicly as a session id the driver derives another way.
     *
     * quiche documents this as "a string uniquely representing the connection". Unlike [connSourceId] it
     * does **not** rotate, which is what makes it usable as the identifier you follow a connection by
     * across a migration.
     */
    fun connTraceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = 0

    /**
     * Copy the connection's **current** source connection ID (`quiche_conn_source_id`) into [buf]. Same
     * snprintf-style contract as [connApplicationProto]; `0` also means "this backend does not bind it"
     * (the interface default), which surfaces publicly as
     * [com.ditchoom.socket.quic.QuicWireConnectionId.Unavailable].
     *
     * ⚠️ This **changes over the connection's life** — CIDs rotate, and migration issues a fresh one by
     * design (RFC 9000 §9.5). Read it at the moment you need it; a cached value stops matching the wire.
     */
    fun connSourceId(
        conn: QuicheConn,
        buf: Long,
        bufLen: Int,
    ): Int = 0

    /**
     * The peer's CONNECTION_CLOSE reason as a typed [QuicError], or `null` if the peer has not closed
     * the connection (we closed first, or it is still open). Maps `quiche_conn_peer_error`, decoding the
     * C API's `is_app` flag + numeric code into the sealed hierarchy — application closes (frame 0x1d) →
     * [QuicError.ApplicationError]; transport closes (frame 0x1c) → [QuicError.fromTransportCode] (which
     * folds the 0x100..0x1ff range into [QuicError.CryptoError]). No stringly errors: the wire code
     * becomes an exhaustive [QuicError]. Used to populate [QuicConnectionState.Closed.reason] so a remote
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
     * Connection-level statistics (`quiche_conn_stats`) as a typed [QuicConnStats], or `null` on a
     * backend that has not bound the stats FFI (the interface default — test doubles and not-yet-wired
     * backends are automatically fine, per RFC_DETERMINISTIC_SIMULATION.md §5.1 item 5). Bound on FFM,
     * JNI/Android, and cinterop (apple + linux). quiche is single-threaded — call only from the driver
     * loop (drivers route through [QuicheCmd.Stats] / the recorder's timer-wake poll).
     */
    fun connStats(conn: QuicheConn): QuicConnStats? = null

    /**
     * Per-path statistics (`quiche_conn_path_stats`) for the path at [pathIdx]
     * (`0 until` [QuicConnStats.pathsCount]) as a typed [QuicPathStats], or `null` when the backend
     * has not bound the stats FFI **or** quiche reports no path at [pathIdx] (negative return —
     * `QUICHE_ERR_DONE`). Same default/threading contract as [connStats].
     */
    fun connPathStats(
        conn: QuicheConn,
        pathIdx: Long,
    ): QuicPathStats? = null

    /**
     * The peer's transport parameters (`quiche_conn_peer_transport_params`), typed — including the
     * `disable_active_migration` flag [connPeerMigrationPermission] projects for the migration path.
     *
     * **Deliberately has no default.** The other optional accessors above default to `null` because a
     * backend that has not bound them still works; this one must not, because the value it carries is a
     * *silent kill switch*: a wrong or absent answer makes active migration decline with no error
     * anywhere. Making it abstract forces every implementation — real backend and test double alike — to
     * state what it reports, which is the same reason the return type is sealed rather than nullable.
     * All four real backends (FFM, JNI, Apple cinterop, Linux cinterop) bind it.
     *
     * Same threading contract as [connStats]: quiche is single-threaded — driver loop only.
     */
    fun connPeerTransportParams(conn: QuicheConn): PeerTransportParams

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
     * Probe the given path for reachability (`quiche_conn_probe_path`). Returns a [ProbeOutcome]:
     * [ProbeOutcome.Probed] with the destination CID sequence number quiche **linked to the new
     * path** on success, or [ProbeOutcome.Rejected] with the raw quiche error code on failure.
     *
     * The sequence number is a return value rather than the `seqOut` out-param it used to be because
     * it is not diagnostic — it is a resource the caller now owns. See [ProbeOutcome] for why a
     * driver that cannot see it leaks one connection ID per failed migration (#447).
     */
    fun connProbePath(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): ProbeOutcome

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
     * Migrate the connection to the given local/peer path (`quiche_conn_migrate`). Returns a
     * [MigrateOutcome]: [MigrateOutcome.Migrated] with the new path's DCID sequence number on
     * success, or [MigrateOutcome.Rejected] with the raw quiche error code on failure.
     */
    fun connMigrate(
        conn: QuicheConn,
        localAddr: Long,
        localLen: Int,
        peerAddr: Long,
        peerLen: Int,
    ): MigrateOutcome

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

    /**
     * Retire the destination connection ID with sequence number [dcidSeq]
     * (`quiche_conn_retire_dcid`) — RFC 9000 §9.5: after migrating, retire the CID used on the old
     * path. Returns 0 on success or a negative quiche error code.
     */
    fun connRetireDcid(
        conn: QuicheConn,
        dcidSeq: Long,
    ): Int

    /** Returns the number of source connection IDs that are still left to be provided to the peer. */
    fun connScidsLeft(conn: QuicheConn): Long

    /**
     * How many source connection IDs the peer has retired and quiche has not yet handed back
     * (`quiche_conn_retired_scids`).
     *
     * The counterpart to [connNewScid]: an endpoint that issues CIDs must also learn which ones the
     * peer has stopped using, because *its own routing table* is what decides whether a datagram
     * reaches this connection at all. quiche removes a retired CID from its internal table the moment
     * it processes the peer's RETIRE_CONNECTION_ID; anything still mapping that CID to this connection
     * outlives quiche's own view of it, and a packet arriving on it is then delivered to a connection
     * that no longer recognises it — which quiche reports as a protocol violation and closes over
     * (#437). Reading this on every established wake is what keeps the two views from diverging.
     *
     * Defaults to 0 — the [connStats]/[connPeerError] default-for-test-doubles convention. A backend
     * that has not bound the accessor reports "nothing retired", which is also the correct answer for
     * a connection whose peer has retired nothing.
     */
    fun connRetiredScids(conn: QuicheConn): Int = 0

    /**
     * Drain every retired source connection ID into [out], returning how many were written.
     *
     * `quiche_conn_retired_scid_iter` **drains** — the ids it yields are removed from quiche's list,
     * so a caller that fails to record one has lost it permanently. That is why the count comes from
     * [connRetiredScids] first and sizes this call: both run on the driver coroutine, which is the
     * only thread allowed to touch the connection, so nothing can retire an id in between and the
     * count is exact.
     *
     * [out] points at [maxIds] slots of `1 + QUIC_MAX_CONN_ID_LEN` bytes; each slot is the id's length
     * in its first byte followed by that many id bytes. One flat buffer with a length prefix rather
     * than a second out-param for the lengths: a connection ID is at most 20 bytes (RFC 9000 §5.1), so
     * a byte holds any length, and there is no integer width or endianness to agree on across four
     * backends.
     *
     * Returns the number the iterator **yielded**, not the number written: `min(result, maxIds)` slots
     * are valid, and a result greater than [maxIds] says exactly how many ids were lost for want of
     * space. Sizing from [connRetiredScids] makes that unreachable; reporting it rather than clamping
     * is what would make a broken assumption inspectable instead of silent.
     */
    fun connDrainRetiredScids(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int = 0

    /**
     * How many source connection IDs quiche currently considers active (`quiche_conn_active_scids`).
     *
     * The **read** counterpart to [connRetiredScids]. This project has always called quiche's CID
     * *write* API — [connNewScid] to issue, [connRetireDcid] to retire — and, since #441,
     * [connDrainRetiredScids] to learn what the peer retired. What it never asked is what quiche
     * believes the live set actually **is**. That gap is why a divergence between quiche's table and
     * our own routing map is structurally invisible rather than merely rare: there is no second
     * opinion to compare against, so drift shows up as a dropped packet (#437) or a path slot pinned
     * forever (#395, #447), long after the moment it happened.
     *
     * Defaults to 0 — the [connStats]/[connPeerError] default-for-test-doubles convention.
     */
    fun connActiveScids(conn: QuicheConn): Int = 0

    /**
     * Copy quiche's **current** source connection IDs into [out], returning how many were yielded.
     *
     * Same slot layout as [connDrainRetiredScids] — [maxIds] slots of `1 + QUIC_MAX_CONN_ID_LEN`
     * bytes, each the id's length in its first byte followed by that many id bytes — and the same
     * "returns what the iterator yielded, not what was written" contract, so a result greater than
     * [maxIds] says exactly how many did not fit.
     *
     * **Unlike [connDrainRetiredScids], this does not drain.** `quiche_conn_source_ids` is a plain
     * read: calling it is side-effect free and repeatable, which is what makes it usable as a
     * reconciliation oracle on every wake. The verbs differ in the names for that reason — a caller
     * that mistook the drain for a read would lose ids permanently, and the two must never be
     * confused at a call site.
     *
     * Size the buffer from [connActiveScids]; both run on the driver coroutine, the only thread
     * allowed to touch the connection, so nothing can change the set in between.
     */
    fun connReadSourceIds(
        conn: QuicheConn,
        out: Long,
        maxIds: Int,
    ): Int = 0

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

/**
 * Slot width for [QuicheApi.connDrainRetiredScids]'s output buffer: one length byte followed by the
 * connection ID's bytes. Declared once here so the four backends and the driver cannot disagree about
 * the layout they are writing and reading.
 *
 * Public for the same reason [QuicheApi] itself is: the FFM backend lives in the `jvm21` compilation,
 * which `internal` does not reach.
 */
const val RETIRED_SCID_SLOT_BYTES = 1 + QUIC_MAX_CONN_ID_LEN
