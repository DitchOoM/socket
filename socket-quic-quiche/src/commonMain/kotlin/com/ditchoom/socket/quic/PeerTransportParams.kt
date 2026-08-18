package com.ditchoom.socket.quic

/**
 * The peer's QUIC transport parameters (RFC 9000 §18) as `quiche_conn_peer_transport_params` reports
 * them — the typed view of quiche's `quiche_transport_params` struct.
 *
 * Sealed rather than nullable because the accessor's `false` return is a **timing** state, not an
 * absence: the handshake simply has not processed the peer's parameters yet, and it resolves on its own.
 * A `null` here would collapse that with "this backend cannot tell you", which is a capability — two
 * facts of completely different kinds behind one token, exactly the `Closed(error: QuicError?)` collapse
 * this campaign deleted. Every backend binds the accessor, so there is no capability case at all.
 *
 * ## Why the whole struct, when only `disableActiveMigration` is dispatched on
 * The three integers surrounding that one `bool` are the **regression guard** for the ABI it sits in.
 * quiche's Rust `TransportParams` shipped without `#[repr(C)]` (its neighbours `Stats` and `PathStats`
 * both have it), so rustc reordered the record and sank the 1-byte `bool` past the two fields that
 * follow it — while `sizeof` stayed 104 either way, which is why nothing caught it for three releases.
 * Reading only the `bool` gives a plausible-looking answer with no way to notice it came from the wrong
 * offset; reading its neighbours too lets a test assert them against values the connection *configured*,
 * so a layout drift fails a build instead of silently switching migration off. See
 * `PeerTransportParamsLayoutTestSuite` and `patchQuicheTransportParamsRepr` in the module's `build.gradle.kts`.
 */
sealed interface PeerTransportParams {
    /**
     * The handshake has not processed the peer's transport parameters yet —
     * `quiche_conn_peer_transport_params` returned `false`. Resolves on its own; it is not a verdict.
     */
    data object NotYetNegotiated : PeerTransportParams

    /** The peer's parameters, as received. */
    data class Negotiated(
        /** `max_idle_timeout`, milliseconds (RFC 9000 §18.2). */
        val maxIdleTimeoutMillis: Long,
        /** `max_udp_payload_size`, bytes. */
        val maxUdpPayloadSize: Long,
        /** `initial_max_data` — the peer's connection-level flow-control credit, bytes. */
        val initialMaxData: Long,
        /** `initial_max_stream_data_bidi_local`, bytes. */
        val initialMaxStreamDataBidiLocal: Long,
        /** `initial_max_stream_data_bidi_remote`, bytes. */
        val initialMaxStreamDataBidiRemote: Long,
        /** `initial_max_stream_data_uni`, bytes. */
        val initialMaxStreamDataUni: Long,
        /** `initial_max_streams_bidi`. */
        val initialMaxStreamsBidi: Long,
        /** `initial_max_streams_uni`. */
        val initialMaxStreamsUni: Long,
        /** `ack_delay_exponent`. */
        val ackDelayExponent: Long,
        /** `max_ack_delay`, milliseconds. */
        val maxAckDelayMillis: Long,
        /**
         * `disable_active_migration` (RFC 9000 §18.2) — the peer will not accept traffic from a new
         * local address. The one field the driver dispatches on; see [PeerMigrationPermission].
         */
        val disableActiveMigration: Boolean,
        /** `active_conn_id_limit` — how many connection ids the peer is willing to hold (RFC 9000 §9.5). */
        val activeConnIdLimit: Long,
        /**
         * `max_datagram_frame_size` (RFC 9221), or **-1** when the peer did not send the parameter —
         * i.e. it does not support DATAGRAM frames. Signed (`ssize_t` in C) precisely so that absence is
         * representable.
         */
        val maxDatagramFrameSize: Long,
    ) : PeerTransportParams
}

/**
 * What the peer's transport parameters say about **this** endpoint migrating to a new local address
 * (RFC 9000 §18.2 `disable_active_migration`).
 *
 * The narrow, dispatched-on projection of [PeerTransportParams] — three states, no boolean and no null,
 * because the two answers a boolean can carry are not the only two facts here.
 */
sealed interface PeerMigrationPermission {
    /** The peer's parameters are known and it did **not** set `disable_active_migration`. */
    data object Permitted : PeerMigrationPermission

    /**
     * The peer set `disable_active_migration`: it will not accept traffic from a new local address, so
     * probing one would burn a spare connection id only to fail validation.
     *
     * ⚠️ **quiche does not enforce this itself.** `Connection::migrate()` and `probe_path()` never
     * consult the peer's parameter; the only uses of it in the library are setting *our* local one and
     * encoding/decoding it on the wire (verified against 0.29.3). So the short-circuit in
     * `QuicheDriver.handleMigrate` is the only thing implementing RFC 9000 §9's "MUST NOT initiate
     * migration if the peer sent `disable_active_migration`" anywhere in this stack — which is why the
     * check stays, and why the ABI it reads through has a regression guard (DitchOoM/socket#388).
     */
    data object Forbidden : PeerMigrationPermission

    /**
     * The handshake has not processed the peer's transport parameters yet. RFC 9000 §9 forbids
     * initiating migration before the handshake is confirmed anyway, so this is a **retryable**
     * condition — [com.ditchoom.socket.quic.MigrationResult.Unmoved.Failed.HandshakeNotConfirmed], never
     * an `Impossible`.
     */
    data object NotYetNegotiated : PeerMigrationPermission
}

/**
 * [PeerTransportParams] reduced to the one question `handleMigrate` asks.
 *
 * A derivation, not a second accessor: there is exactly one native read of the peer's parameters per
 * backend, so a backend cannot answer these two questions inconsistently.
 */
internal val PeerTransportParams.migrationPermission: PeerMigrationPermission
    get() =
        when (this) {
            PeerTransportParams.NotYetNegotiated -> PeerMigrationPermission.NotYetNegotiated
            is PeerTransportParams.Negotiated ->
                if (disableActiveMigration) {
                    PeerMigrationPermission.Forbidden
                } else {
                    PeerMigrationPermission.Permitted
                }
        }

/** [migrationPermission] read straight off [conn]. */
internal fun QuicheApi.connPeerMigrationPermission(conn: QuicheConn): PeerMigrationPermission =
    connPeerTransportParams(conn).migrationPermission
