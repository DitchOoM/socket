@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.socket.quic

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Per-datagram and per-stream downcalls. Each handle is `static final`, so C2 compiles the call as a direct native
 * call instead of a generic handle invocation.
 */
internal object HotCalls {
    @JvmField
    val hConnDgramSend: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_dgram_send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnDgramRecv: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_dgram_recv",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnDgramRecvFrontLen: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_dgram_recv_front_len",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnDgramMaxWritableLen: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_dgram_max_writable_len",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnRecv: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_recv",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnSend: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hStreamRecv: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_stream_recv",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hStreamSend: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_stream_send",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_BOOLEAN, JAVA_LONG),
        )

    @JvmField
    val hStreamCapacity: MethodHandle =
        // ssize_t quiche_conn_stream_capacity(conn, uint64 stream_id)
        FfmLibrary.downcall("quiche_conn_stream_capacity", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hPeerStreamsLeftBidi: MethodHandle =
        // uint64_t quiche_conn_peer_streams_left_bidi(const quiche_conn *conn)
        FfmLibrary.downcall("quiche_conn_peer_streams_left_bidi", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hPeerStreamsLeftUni: MethodHandle =
        // uint64_t quiche_conn_peer_streams_left_uni(const quiche_conn *conn)
        FfmLibrary.downcall("quiche_conn_peer_streams_left_uni", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hStreamShutdown: MethodHandle =
        // int quiche_conn_stream_shutdown(conn, uint64 stream_id, enum quiche_shutdown direction, uint64 err)
        FfmLibrary.downcall(
            "quiche_conn_stream_shutdown",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG),
        )

    @JvmField
    val hIsEstablished: MethodHandle = FfmLibrary.downcall("quiche_conn_is_established", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hIsClosed: MethodHandle = FfmLibrary.downcall("quiche_conn_is_closed", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hIsDraining: MethodHandle = FfmLibrary.downcall("quiche_conn_is_draining", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hIsTimedOut: MethodHandle = FfmLibrary.downcall("quiche_conn_is_timed_out", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hIsHandshakeConfirmed: MethodHandle =
        // bool quiche_conn_is_handshake_confirmed(const quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_is_handshake_confirmed", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hStreamDataUnacknowledged: MethodHandle =
        // bool quiche_conn_stream_data_unacknowledged(const quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_stream_data_unacknowledged", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hRecvGaps: MethodHandle =
        // uint64_t quiche_conn_recv_gaps(const quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_recv_gaps", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hTimeoutNanos: MethodHandle = FfmLibrary.downcall("quiche_conn_timeout_as_nanos", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hOnTimeout: MethodHandle = FfmLibrary.downcall("quiche_conn_on_timeout", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hSendAckEliciting: MethodHandle = FfmLibrary.downcall("quiche_conn_send_ack_eliciting", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hHandshakePtoCeilingHeld: MethodHandle =
        // bool quiche_conn_handshake_pto_ceiling_held(const quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_handshake_pto_ceiling_held", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hProbeNow: MethodHandle =
        // ssize_t quiche_conn_probe_now(quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_probe_now", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnStats: MethodHandle =
        // void quiche_conn_stats(const quiche_conn *conn, quiche_stats *out)
        FfmLibrary.downcall("quiche_conn_stats", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnPathStats: MethodHandle =
        // int quiche_conn_path_stats(const quiche_conn *conn, size_t idx, quiche_path_stats *out)
        FfmLibrary.downcall("quiche_conn_path_stats", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnReadable: MethodHandle = FfmLibrary.downcall("quiche_conn_readable", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnWritable: MethodHandle = FfmLibrary.downcall("quiche_conn_writable", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnStreamWritableNext: MethodHandle =
        // int64_t quiche_conn_stream_writable_next(quiche_conn *conn)
        FfmLibrary.downcall("quiche_conn_stream_writable_next", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hStreamIterNext: MethodHandle =
        FfmLibrary.downcall(
            "quiche_stream_iter_next",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hStreamIterFree: MethodHandle = FfmLibrary.downcall("quiche_stream_iter_free", FunctionDescriptor.ofVoid(JAVA_LONG))
}

/** Connection setup, teardown and inspection downcalls. */
internal object ConnCalls {
    @JvmField
    val hConnect: MethodHandle =
        FfmLibrary.downcall(
            "quiche_connect",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG),
        )

    @JvmField
    val hConnFree: MethodHandle = FfmLibrary.downcall("quiche_conn_free", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hConnSetSession: MethodHandle =
        // int quiche_conn_set_session(quiche_conn *conn, const uint8_t *buf, size_t buf_len)
        FfmLibrary.downcall("quiche_conn_set_session", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnSession: MethodHandle =
        // void quiche_conn_session(const quiche_conn *conn, const uint8_t **out, size_t *out_len)
        FfmLibrary.downcall("quiche_conn_session", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hSetToken: MethodHandle =
        // int quiche_conn_set_token(quiche_conn *conn, const uint8_t *token, size_t token_len) — patched-in export
        FfmLibrary.downcall("quiche_conn_set_token", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hTakeNewToken: MethodHandle =
        // ssize_t quiche_conn_take_new_token(quiche_conn *conn, uint8_t *out, size_t out_len) — patched-in export
        FfmLibrary.downcall("quiche_conn_take_new_token", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hSendNewToken: MethodHandle =
        // int quiche_conn_send_new_token(quiche_conn *conn, const uint8_t *token, size_t token_len) — patched-in export
        FfmLibrary.downcall("quiche_conn_send_new_token", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hSetPeerAddressValidated: MethodHandle =
        // int quiche_conn_set_peer_address_validated(quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_set_peer_address_validated", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    @JvmField
    val hIsResumed: MethodHandle = FfmLibrary.downcall("quiche_conn_is_resumed", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hIsInEarlyData: MethodHandle = FfmLibrary.downcall("quiche_conn_is_in_early_data", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG))

    @JvmField
    val hEarlyDataReason: MethodHandle =
        // uint32_t quiche_conn_early_data_reason(const quiche_conn *conn) — patched-in export
        FfmLibrary.downcall("quiche_conn_early_data_reason", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    @JvmField
    val hSetHandshakePtoCeiling: MethodHandle =
        // void quiche_conn_set_handshake_pto_ceiling(quiche_conn *conn, uint64_t first, uint32_t growth, uint64_t max)
        FfmLibrary.downcall("quiche_conn_set_handshake_pto_ceiling", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG))

    @JvmField
    val hConnSetQlogPath: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_set_qlog_path",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnSetKeylogPath: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_set_keylog_path",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnClose: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_close",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnPeerCert: MethodHandle =
        // void quiche_conn_peer_cert(conn, const uint8_t **out, size_t *out_len)
        FfmLibrary.downcall("quiche_conn_peer_cert", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnPeerCertChainAt: MethodHandle =
        // void quiche_conn_peer_cert_chain_at(conn, size_t index, const uint8_t **out, size_t *out_len) — patched-in export
        FfmLibrary.downcall("quiche_conn_peer_cert_chain_at", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnApplicationProto: MethodHandle =
        // void quiche_conn_application_proto(conn, const uint8_t **out, size_t *out_len)
        FfmLibrary.downcall("quiche_conn_application_proto", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnTraceId: MethodHandle =
        // void quiche_conn_trace_id(const quiche_conn *conn, const uint8_t **out, size_t *out_len)
        FfmLibrary.downcall("quiche_conn_trace_id", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnSourceId: MethodHandle =
        // void quiche_conn_source_id(const quiche_conn *conn, const uint8_t **out, size_t *out_len)
        FfmLibrary.downcall("quiche_conn_source_id", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnPeerTransportParams: MethodHandle =
        // bool quiche_conn_peer_transport_params(const quiche_conn *conn, quiche_transport_params *out)
        FfmLibrary.downcall("quiche_conn_peer_transport_params", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnPeerError: MethodHandle =
        // bool quiche_conn_peer_error(conn, bool *is_app, uint64 *code, const uint8 **reason, size_t *reason_len)
        FfmLibrary.downcall(
            "quiche_conn_peer_error",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnLocalError: MethodHandle =
        // bool quiche_conn_local_error(conn, bool *is_app, uint64 *code, const uint8 **reason, size_t *reason_len)
        FfmLibrary.downcall(
            "quiche_conn_local_error",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )
}

/** `quiche_config_*` downcalls, used while a config is built. */
internal object ConfigCalls {
    @JvmField
    val hConfigNew: MethodHandle = FfmLibrary.downcall("quiche_config_new", FunctionDescriptor.of(JAVA_LONG, JAVA_INT))

    @JvmField
    val hConfigFree: MethodHandle = FfmLibrary.downcall("quiche_config_free", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hConfigSetAppProtos: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_application_protos",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConfigSetMaxIdleTimeout: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_idle_timeout",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConfigSetMaxRecvUdp: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_recv_udp_payload_size",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConfigSetMaxSendUdp: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_send_udp_payload_size",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetMaxData: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_data",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetActiveConnectionIdLimit: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_active_connection_id_limit",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetBidiLocal: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_stream_data_bidi_local",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetBidiRemote: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_stream_data_bidi_remote",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetUni: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_stream_data_uni",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetStreamsBidi: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_streams_bidi",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetStreamsUni: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_max_streams_uni",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hDisableMigration: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_disable_active_migration",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN),
        )

    @JvmField
    val hVerifyPeer: MethodHandle = FfmLibrary.downcall("quiche_config_verify_peer", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN))

    @JvmField
    val hEnablePacing: MethodHandle = FfmLibrary.downcall("quiche_config_enable_pacing", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN))

    @JvmField
    val hSetMaxPacingRate: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_pacing_rate",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetCcAlgorithm: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_cc_algorithm",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT),
        )

    @JvmField
    val hEnableHystart: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_enable_hystart",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN),
        )

    @JvmField
    val hSetInitialCwndPackets: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_initial_congestion_window_packets",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetMaxConnWindow: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_connection_window",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetMaxStreamWindow: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_set_max_stream_window",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hDiscoverPmtu: MethodHandle = FfmLibrary.downcall("quiche_config_discover_pmtu", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN))

    @JvmField
    val hEnableEarlyData: MethodHandle = FfmLibrary.downcall("quiche_config_enable_early_data", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hSetTicketKey: MethodHandle =
        // int quiche_config_set_ticket_key(quiche_config *config, const uint8_t *key, size_t key_len)
        FfmLibrary.downcall("quiche_config_set_ticket_key", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hGrease: MethodHandle = FfmLibrary.downcall("quiche_config_grease", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN))

    @JvmField
    val hConfigEnableDgram: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_enable_dgram",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hSetInitialRtt: MethodHandle =
        // void quiche_config_set_initial_rtt(quiche_config *config, uint64_t v) — patched-in export
        FfmLibrary.downcall("quiche_config_set_initial_rtt", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConfigLogKeys: MethodHandle = FfmLibrary.downcall("quiche_config_log_keys", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hLoadCert: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_load_cert_chain_from_pem_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hLoadKey: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_load_priv_key_from_pem_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hLoadVerify: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_load_verify_locations_from_file",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hLoadVerifyDirectory: MethodHandle =
        FfmLibrary.downcall(
            "quiche_config_load_verify_locations_from_directory",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
        )
}

/** Stateless server-side packet downcalls: header parse, accept, version negotiation, retry. */
internal object ServerCalls {
    @JvmField
    val hHeaderInfo: MethodHandle =
        FfmLibrary.downcall(
            "quiche_header_info",
            FunctionDescriptor.of(
                JAVA_INT,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
            ),
        )

    @JvmField
    val hAccept: MethodHandle =
        FfmLibrary.downcall(
            "quiche_accept",
            FunctionDescriptor.of(
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_INT,
                JAVA_LONG,
                JAVA_INT,
                JAVA_LONG,
            ),
        )

    @JvmField
    val hNegotiateVersion: MethodHandle =
        FfmLibrary.downcall(
            "quiche_negotiate_version",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hRetry: MethodHandle =
        FfmLibrary.downcall(
            "quiche_retry",
            FunctionDescriptor.of(
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_LONG,
                JAVA_INT,
                JAVA_LONG,
                JAVA_LONG,
            ),
        )
}

/** Path-migration and connection-id downcalls. */
internal object PathCalls {
    @JvmField
    val hRequeuePath: MethodHandle =
        // ssize_t quiche_conn_requeue_path(conn, local, local_len, peer, peer_len) — patched-in export
        FfmLibrary.downcall(
            "quiche_conn_requeue_path",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT),
        )

    @JvmField
    val hConnProbePath: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_probe_path",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG),
        )

    @JvmField
    val hConnProbePathWith: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_probe_path_with",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnNewScid: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_new_scid",
            // conn, scid*, scid_len(size_t), reset_token*, retire_if_needed(bool), scid_seq*
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_BOOLEAN, JAVA_LONG),
        )

    @JvmField
    val hConnMigrate: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_migrate",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG),
        )

    @JvmField
    val hConnMigrateSource: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_migrate_source",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG),
        )

    @JvmField
    val hConnRetireDcid: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_retire_dcid",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnAvailableDcids: MethodHandle = FfmLibrary.downcall("quiche_conn_available_dcids", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnScidsLeft: MethodHandle = FfmLibrary.downcall("quiche_conn_scids_left", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnPathEventNext: MethodHandle = FfmLibrary.downcall("quiche_conn_path_event_next", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnRetiredScids: MethodHandle = FfmLibrary.downcall("quiche_conn_retired_scids", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnRetiredScidIter: MethodHandle =
        FfmLibrary.downcall(
            "quiche_conn_retired_scid_iter",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnActiveScids: MethodHandle = FfmLibrary.downcall("quiche_conn_active_scids", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnSourceIds: MethodHandle = FfmLibrary.downcall("quiche_conn_source_ids", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))

    @JvmField
    val hConnIdIterNext: MethodHandle =
        FfmLibrary.downcall(
            "quiche_connection_id_iter_next",
            FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hConnIdIterFree: MethodHandle = FfmLibrary.downcall("quiche_connection_id_iter_free", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hPathEventType: MethodHandle = FfmLibrary.downcall("quiche_path_event_type", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))

    @JvmField
    val hPathEventNew: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_new",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventValidated: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_validated",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventFailedValidation: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_failed_validation",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventClosed: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_closed",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventPeerMigrated: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_peer_migrated",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventPmtuUpdated: MethodHandle =
        FfmLibrary.downcall(
            "quiche_path_event_pmtu_updated",
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        )

    @JvmField
    val hPathEventFree: MethodHandle = FfmLibrary.downcall("quiche_path_event_free", FunctionDescriptor.ofVoid(JAVA_LONG))
}

/** Caller-clock and caller-entropy downcalls of the patched libquiche; deterministic simulation only. */
internal object SimulationCalls {
    // Caller-clock (RFC §6.1): thread-local virtual clock in the patched libquiche. Simulation-only.
    @JvmField
    val hSetVirtualTimeNanos: MethodHandle = FfmLibrary.downcall("quiche_set_virtual_time_nanos", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hClearVirtualTime: MethodHandle = FfmLibrary.downcall("quiche_clear_virtual_time", FunctionDescriptor.ofVoid())

    // Caller-entropy: thread-local seeded random stream in the patched libquiche. Simulation-only.
    @JvmField
    val hSetThreadRandomState: MethodHandle = FfmLibrary.downcall("quiche_set_thread_random_state", FunctionDescriptor.ofVoid(JAVA_LONG))

    @JvmField
    val hClearThreadRandomState: MethodHandle = FfmLibrary.downcall("quiche_clear_thread_random_state", FunctionDescriptor.ofVoid())
}

/** C-runtime downcalls for the caller-owned info structs. */
internal object LibcCalls {
    // These two structs are owned by the *caller*, not by this object: `recv_info` is handed to
    // quiche as a raw pointer and stays queued behind in-flight packets, `send_info` is refilled by
    // quiche on every flush, and the driver releases both explicitly at teardown. So they are
    // allocated on the C heap and released by [recvInfoFree] / [sendInfoFree] — the same lifetime
    // every other backend gives them (`quiche_jni.c` uses calloc/free; both cinterop backends use
    // `nativeHeap`).
    //
    // Not `Arena.ofAuto()` fields: an auto arena releases only once the *arena* is unreachable —
    // per-arena, not per-segment — and this singleton is process-wide on purpose (dlclosing libquiche
    // crashes BoringSSL's pthread TLS destructors), so nothing would ever be released: one recv_info
    // and one send_info per connection, plus one more recv_info per migration, and `SharedQuicheServer`
    // caches recv_info per unique *peer source address*, so a public endpoint would leak per client
    // address.
    //
    // Deliberately calloc/free rather than a closeable per-allocation Arena, which is the other
    // obvious shape: `Arena.ofShared()` is the only closeable kind safe here (recvInfoNew and
    // recvInfoFree routinely run on different driver threads, and `ofConfined` throws
    // WrongThreadException across threads), and closing one costs a thread handshake — measured
    // 89 us/op with 8 busy threads, against 0.125 us/op for calloc+free. That cost sits directly on
    // the server's per-source-address eviction path, where the insertion rate is set by remote
    // peers, so it would turn a source-address flood into a global-handshake amplifier. calloc also
    // keeps the zero-fill `Arena.allocate` guaranteed here, which sendInfoNew relies on.
    @JvmField
    val hCalloc: MethodHandle = FfmLibrary.libcDowncall("calloc", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG))

    @JvmField
    val hFree: MethodHandle = FfmLibrary.libcDowncall("free", FunctionDescriptor.ofVoid(JAVA_LONG))
}
