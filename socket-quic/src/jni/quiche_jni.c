/**
 * JNI shim for quiche — pointer forwarding only, zero data copies.
 *
 * Every function takes native addresses as jlong, casts to void*, and
 * forwards to quiche. No GetDirectBufferAddress, no byte array copies.
 *
 * Build:
 *   gcc -shared -fPIC -o libquiche_jni.so quiche_jni.c \
 *     -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux \
 *     -L/path/to/quiche/lib -lquiche
 */

#include <jni.h>
#include <quiche.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

/* Package: com.ditchoom.socket.quic */
/* Class: JniQuicheApi */

#define JNI_FN(name) Java_com_ditchoom_socket_quic_JniQuicheApi_ ## name

/* --- Config --- */

JNIEXPORT jlong JNICALL JNI_FN(nConfigNew)(JNIEnv *env, jclass cls, jint version) {
    return (jlong)(uintptr_t)quiche_config_new((uint32_t)version);
}

JNIEXPORT void JNICALL JNI_FN(nConfigFree)(JNIEnv *env, jclass cls, jlong config) {
    quiche_config_free((quiche_config *)(uintptr_t)config);
}

JNIEXPORT jint JNICALL JNI_FN(nConfigSetApplicationProtos)(
    JNIEnv *env, jclass cls, jlong config, jlong protos_addr, jint protos_len) {
    return (jint)quiche_config_set_application_protos(
        (quiche_config *)(uintptr_t)config,
        (const uint8_t *)(uintptr_t)protos_addr,
        (size_t)protos_len);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxIdleTimeout)(
    JNIEnv *env, jclass cls, jlong config, jlong timeout) {
    quiche_config_set_max_idle_timeout((quiche_config *)(uintptr_t)config, (uint64_t)timeout);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxRecvUdpPayloadSize)(
    JNIEnv *env, jclass cls, jlong config, jlong size) {
    quiche_config_set_max_recv_udp_payload_size((quiche_config *)(uintptr_t)config, (size_t)size);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxSendUdpPayloadSize)(
    JNIEnv *env, jclass cls, jlong config, jlong size) {
    quiche_config_set_max_send_udp_payload_size((quiche_config *)(uintptr_t)config, (size_t)size);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxData)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_data((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetActiveConnectionIdLimit)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_active_connection_id_limit((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxStreamDataBidiLocal)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_stream_data_bidi_local((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxStreamDataBidiRemote)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_stream_data_bidi_remote((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxStreamDataUni)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_stream_data_uni((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxStreamsBidi)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_streams_bidi((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialMaxStreamsUni)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_initial_max_streams_uni((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetDisableActiveMigration)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_set_disable_active_migration((quiche_config *)(uintptr_t)config, (bool)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigVerifyPeer)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_verify_peer((quiche_config *)(uintptr_t)config, (bool)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigEnablePacing)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_enable_pacing((quiche_config *)(uintptr_t)config, (bool)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxPacingRate)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_max_pacing_rate((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetCcAlgorithm)(
    JNIEnv *env, jclass cls, jlong config, jint algo) {
    quiche_config_set_cc_algorithm((quiche_config *)(uintptr_t)config, (enum quiche_cc_algorithm)algo);
}

JNIEXPORT void JNICALL JNI_FN(nConfigEnableHystart)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_enable_hystart((quiche_config *)(uintptr_t)config, (bool)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetInitialCongestionWindowPackets)(
    JNIEnv *env, jclass cls, jlong config, jlong packets) {
    quiche_config_set_initial_congestion_window_packets((quiche_config *)(uintptr_t)config, (size_t)packets);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxConnectionWindow)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_max_connection_window((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigSetMaxStreamWindow)(
    JNIEnv *env, jclass cls, jlong config, jlong v) {
    quiche_config_set_max_stream_window((quiche_config *)(uintptr_t)config, (uint64_t)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigDiscoverPmtu)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_discover_pmtu((quiche_config *)(uintptr_t)config, (bool)v);
}

JNIEXPORT void JNICALL JNI_FN(nConfigEnableEarlyData)(
    JNIEnv *env, jclass cls, jlong config) {
    quiche_config_enable_early_data((quiche_config *)(uintptr_t)config);
}

JNIEXPORT void JNICALL JNI_FN(nConfigGrease)(
    JNIEnv *env, jclass cls, jlong config, jboolean v) {
    quiche_config_grease((quiche_config *)(uintptr_t)config, (bool)v);
}

/* --- Connection --- */

JNIEXPORT jlong JNICALL JNI_FN(nConnect)(
    JNIEnv *env, jclass cls,
    jlong server_name_addr, jint server_name_len,
    jlong scid_addr, jint scid_len,
    jlong local_addr, jint local_addr_len,
    jlong peer_addr, jint peer_addr_len,
    jlong config) {
    return (jlong)(uintptr_t)quiche_connect(
        (const char *)(uintptr_t)server_name_addr,
        (const uint8_t *)(uintptr_t)scid_addr, (size_t)scid_len,
        (const struct sockaddr *)(uintptr_t)local_addr, (socklen_t)local_addr_len,
        (const struct sockaddr *)(uintptr_t)peer_addr, (socklen_t)peer_addr_len,
        (quiche_config *)(uintptr_t)config);
}

JNIEXPORT void JNICALL JNI_FN(nConnFree)(JNIEnv *env, jclass cls, jlong conn) {
    quiche_conn_free((quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jint JNICALL JNI_FN(nConnRecv)(
    JNIEnv *env, jclass cls, jlong conn, jlong buf, jint buf_len, jlong recv_info) {
    return (jint)quiche_conn_recv(
        (quiche_conn *)(uintptr_t)conn,
        (uint8_t *)(uintptr_t)buf, (size_t)buf_len,
        (const quiche_recv_info *)(uintptr_t)recv_info);
}

JNIEXPORT jint JNICALL JNI_FN(nConnSend)(
    JNIEnv *env, jclass cls, jlong conn, jlong buf, jint buf_len, jlong send_info) {
    return (jint)quiche_conn_send(
        (quiche_conn *)(uintptr_t)conn,
        (uint8_t *)(uintptr_t)buf, (size_t)buf_len,
        (quiche_send_info *)(uintptr_t)send_info);
}

JNIEXPORT jlong JNICALL JNI_FN(nConnStreamRecv)(
    JNIEnv *env, jclass cls, jlong conn, jlong stream_id, jlong buf, jint buf_len) {
    bool fin = false;
    uint64_t error_code = 0;
    ssize_t result = quiche_conn_stream_recv(
        (quiche_conn *)(uintptr_t)conn, (uint64_t)stream_id,
        (uint8_t *)(uintptr_t)buf, (size_t)buf_len,
        &fin, &error_code);
    if (result < 0) return (jlong)result;
    /* Pack fin flag into high bit of result */
    jlong packed = (jlong)result;
    if (fin) packed |= (1LL << 63);
    return packed;
}

JNIEXPORT jint JNICALL JNI_FN(nConnStreamSend)(
    JNIEnv *env, jclass cls,
    jlong conn, jlong stream_id, jlong buf, jint buf_len, jboolean fin) {
    uint64_t error_code = 0;
    return (jint)quiche_conn_stream_send(
        (quiche_conn *)(uintptr_t)conn, (uint64_t)stream_id,
        (const uint8_t *)(uintptr_t)buf, (size_t)buf_len,
        (bool)fin, &error_code);
}

JNIEXPORT jboolean JNICALL JNI_FN(nConnIsEstablished)(JNIEnv *env, jclass cls, jlong conn) {
    return (jboolean)quiche_conn_is_established((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jboolean JNICALL JNI_FN(nConnIsClosed)(JNIEnv *env, jclass cls, jlong conn) {
    return (jboolean)quiche_conn_is_closed((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jboolean JNICALL JNI_FN(nConnIsTimedOut)(JNIEnv *env, jclass cls, jlong conn) {
    return (jboolean)quiche_conn_is_timed_out((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jlong JNICALL JNI_FN(nConnTimeoutAsNanos)(JNIEnv *env, jclass cls, jlong conn) {
    return (jlong)quiche_conn_timeout_as_nanos((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT void JNICALL JNI_FN(nConnOnTimeout)(JNIEnv *env, jclass cls, jlong conn) {
    quiche_conn_on_timeout((quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jint JNICALL JNI_FN(nConnClose)(
    JNIEnv *env, jclass cls,
    jlong conn, jboolean app, jlong err, jlong reason_addr, jint reason_len) {
    return (jint)quiche_conn_close(
        (quiche_conn *)(uintptr_t)conn, (bool)app, (uint64_t)err,
        (const uint8_t *)(uintptr_t)reason_addr, (size_t)reason_len);
}

/* --- Path migration --- */

JNIEXPORT jint JNICALL JNI_FN(nConnProbePath)(
    JNIEnv *env, jclass cls,
    jlong conn, jlong local_addr, jint local_len,
    jlong peer_addr, jint peer_len, jlong seq_out) {
    return (jint)quiche_conn_probe_path(
        (quiche_conn *)(uintptr_t)conn,
        (const struct sockaddr *)(uintptr_t)local_addr, (socklen_t)local_len,
        (const struct sockaddr *)(uintptr_t)peer_addr, (socklen_t)peer_len,
        (uint64_t *)(uintptr_t)seq_out);
}

JNIEXPORT jint JNICALL JNI_FN(nConnMigrate)(
    JNIEnv *env, jclass cls,
    jlong conn, jlong local_addr, jint local_len,
    jlong peer_addr, jint peer_len, jlong seq_out) {
    return (jint)quiche_conn_migrate(
        (quiche_conn *)(uintptr_t)conn,
        (const struct sockaddr *)(uintptr_t)local_addr, (socklen_t)local_len,
        (const struct sockaddr *)(uintptr_t)peer_addr, (socklen_t)peer_len,
        (uint64_t *)(uintptr_t)seq_out);
}

JNIEXPORT jint JNICALL JNI_FN(nConnMigrateSource)(
    JNIEnv *env, jclass cls,
    jlong conn, jlong local_addr, jint local_len, jlong seq_out) {
    return (jint)quiche_conn_migrate_source(
        (quiche_conn *)(uintptr_t)conn,
        (const struct sockaddr *)(uintptr_t)local_addr, (socklen_t)local_len,
        (uint64_t *)(uintptr_t)seq_out);
}

JNIEXPORT jlong JNICALL JNI_FN(nConnAvailableDcids)(JNIEnv *env, jclass cls, jlong conn) {
    return (jlong)quiche_conn_available_dcids((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jlong JNICALL JNI_FN(nConnScidsLeft)(JNIEnv *env, jclass cls, jlong conn) {
    return (jlong)quiche_conn_scids_left((quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jint JNICALL JNI_FN(nConnPathEventNext)(
    JNIEnv *env, jclass cls,
    jlong conn, jlong local_out, jlong local_len_out,
    jlong peer_out, jlong peer_len_out) {
    quiche_path_event *ev = quiche_conn_path_event_next((quiche_conn *)(uintptr_t)conn);
    if (!ev) return -1;
    int t = quiche_path_event_type(ev);
    switch (t) {
        case 0:
            quiche_path_event_new(ev,
                (struct sockaddr_storage *)(uintptr_t)local_out, (socklen_t *)(uintptr_t)local_len_out,
                (struct sockaddr_storage *)(uintptr_t)peer_out, (socklen_t *)(uintptr_t)peer_len_out);
            break;
        case 1:
            quiche_path_event_validated(ev,
                (struct sockaddr_storage *)(uintptr_t)local_out, (socklen_t *)(uintptr_t)local_len_out,
                (struct sockaddr_storage *)(uintptr_t)peer_out, (socklen_t *)(uintptr_t)peer_len_out);
            break;
        case 2:
            quiche_path_event_failed_validation(ev,
                (struct sockaddr_storage *)(uintptr_t)local_out, (socklen_t *)(uintptr_t)local_len_out,
                (struct sockaddr_storage *)(uintptr_t)peer_out, (socklen_t *)(uintptr_t)peer_len_out);
            break;
        case 3:
            quiche_path_event_closed(ev,
                (struct sockaddr_storage *)(uintptr_t)local_out, (socklen_t *)(uintptr_t)local_len_out,
                (struct sockaddr_storage *)(uintptr_t)peer_out, (socklen_t *)(uintptr_t)peer_len_out);
            break;
        case 5:
            quiche_path_event_peer_migrated(ev,
                (struct sockaddr_storage *)(uintptr_t)local_out, (socklen_t *)(uintptr_t)local_len_out,
                (struct sockaddr_storage *)(uintptr_t)peer_out, (socklen_t *)(uintptr_t)peer_len_out);
            break;
        case 4:
            /* ReusedSourceConnectionId: extra old/new-tuple + CID-seq fields out of scope; surface no addresses. */
            *(socklen_t *)(uintptr_t)local_len_out = 0;
            *(socklen_t *)(uintptr_t)peer_len_out = 0;
            break;
    }
    quiche_path_event_free(ev);
    return (jint)t;
}

/* --- Server-side --- */

JNIEXPORT jint JNICALL JNI_FN(nConfigLoadCertChainFromPemFile)(
    JNIEnv *env, jclass cls, jlong config, jlong path_addr) {
    return (jint)quiche_config_load_cert_chain_from_pem_file(
        (quiche_config *)(uintptr_t)config,
        (const char *)(uintptr_t)path_addr);
}

JNIEXPORT jint JNICALL JNI_FN(nConfigLoadPrivKeyFromPemFile)(
    JNIEnv *env, jclass cls, jlong config, jlong path_addr) {
    return (jint)quiche_config_load_priv_key_from_pem_file(
        (quiche_config *)(uintptr_t)config,
        (const char *)(uintptr_t)path_addr);
}

JNIEXPORT jint JNICALL JNI_FN(nHeaderInfo)(
    JNIEnv *env, jclass cls,
    jlong buf, jint buf_len, jint dcil,
    jlong version_out, jlong type_out,
    jlong scid_out, jlong scid_len_out,
    jlong dcid_out, jlong dcid_len_out,
    jlong token_out, jlong token_len_out) {
    return (jint)quiche_header_info(
        (const uint8_t *)(uintptr_t)buf, (size_t)buf_len, (size_t)dcil,
        (uint32_t *)(uintptr_t)version_out, (uint8_t *)(uintptr_t)type_out,
        (uint8_t *)(uintptr_t)scid_out, (size_t *)(uintptr_t)scid_len_out,
        (uint8_t *)(uintptr_t)dcid_out, (size_t *)(uintptr_t)dcid_len_out,
        (uint8_t *)(uintptr_t)token_out, (size_t *)(uintptr_t)token_len_out);
}

JNIEXPORT jlong JNICALL JNI_FN(nAccept)(
    JNIEnv *env, jclass cls,
    jlong scid_addr, jint scid_len,
    jlong odcid_addr, jint odcid_len,
    jlong local_addr, jint local_addr_len,
    jlong peer_addr, jint peer_addr_len,
    jlong config) {
    return (jlong)(uintptr_t)quiche_accept(
        (const uint8_t *)(uintptr_t)scid_addr, (size_t)scid_len,
        odcid_addr ? (const uint8_t *)(uintptr_t)odcid_addr : NULL, (size_t)odcid_len,
        (const struct sockaddr *)(uintptr_t)local_addr, (socklen_t)local_addr_len,
        (const struct sockaddr *)(uintptr_t)peer_addr, (socklen_t)peer_addr_len,
        (quiche_config *)(uintptr_t)config);
}

JNIEXPORT jint JNICALL JNI_FN(nNegotiateVersion)(
    JNIEnv *env, jclass cls,
    jlong scid_addr, jint scid_len,
    jlong dcid_addr, jint dcid_len,
    jlong out_addr, jint out_len) {
    return (jint)quiche_negotiate_version(
        (const uint8_t *)(uintptr_t)scid_addr, (size_t)scid_len,
        (const uint8_t *)(uintptr_t)dcid_addr, (size_t)dcid_len,
        (uint8_t *)(uintptr_t)out_addr, (size_t)out_len);
}

/* --- Stream iteration --- */

JNIEXPORT jlong JNICALL JNI_FN(nConnReadable)(JNIEnv *env, jclass cls, jlong conn) {
    return (jlong)(uintptr_t)quiche_conn_readable((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jlong JNICALL JNI_FN(nConnWritable)(JNIEnv *env, jclass cls, jlong conn) {
    return (jlong)(uintptr_t)quiche_conn_writable((const quiche_conn *)(uintptr_t)conn);
}

JNIEXPORT jlong JNICALL JNI_FN(nStreamIterNext)(
    JNIEnv *env, jclass cls, jlong iter) {
    uint64_t stream_id;
    bool has_next = quiche_stream_iter_next(
        (quiche_stream_iter *)(uintptr_t)iter, &stream_id);
    return has_next ? (jlong)stream_id : -1;
}

JNIEXPORT void JNICALL JNI_FN(nStreamIterFree)(JNIEnv *env, jclass cls, jlong iter) {
    quiche_stream_iter_free((quiche_stream_iter *)(uintptr_t)iter);
}

/* --- RecvInfo / SendInfo helpers --- */

JNIEXPORT jlong JNICALL JNI_FN(nRecvInfoNew)(
    JNIEnv *env, jclass cls,
    jlong from_addr, jint from_len, jlong to_addr, jint to_len) {
    quiche_recv_info *info = (quiche_recv_info *)calloc(1, sizeof(quiche_recv_info));
    if (!info) return 0;
    info->from = (struct sockaddr *)(uintptr_t)from_addr;
    info->from_len = (socklen_t)from_len;
    info->to = (struct sockaddr *)(uintptr_t)to_addr;
    info->to_len = (socklen_t)to_len;
    return (jlong)(uintptr_t)info;
}

JNIEXPORT void JNICALL JNI_FN(nRecvInfoFree)(JNIEnv *env, jclass cls, jlong ptr) {
    free((void *)(uintptr_t)ptr);
}

JNIEXPORT jlong JNICALL JNI_FN(nSendInfoNew)(JNIEnv *env, jclass cls) {
    quiche_send_info *info = (quiche_send_info *)calloc(1, sizeof(quiche_send_info));
    return (jlong)(uintptr_t)info;
}

JNIEXPORT void JNICALL JNI_FN(nSendInfoFree)(JNIEnv *env, jclass cls, jlong ptr) {
    free((void *)(uintptr_t)ptr);
}

JNIEXPORT jlong JNICALL JNI_FN(nSendInfoToAddr)(JNIEnv *env, jclass cls, jlong ptr) {
    quiche_send_info *info = (quiche_send_info *)(uintptr_t)ptr;
    return (jlong)(uintptr_t)&info->to;
}

JNIEXPORT jint JNICALL JNI_FN(nSendInfoToAddrLen)(JNIEnv *env, jclass cls, jlong ptr) {
    quiche_send_info *info = (quiche_send_info *)(uintptr_t)ptr;
    return (jint)info->to_len;
}

JNIEXPORT jlong JNICALL JNI_FN(nSendInfoFromAddr)(JNIEnv *env, jclass cls, jlong ptr) {
    quiche_send_info *info = (quiche_send_info *)(uintptr_t)ptr;
    return (jlong)(uintptr_t)&info->from;
}

JNIEXPORT jint JNICALL JNI_FN(nSendInfoFromAddrLen)(JNIEnv *env, jclass cls, jlong ptr) {
    quiche_send_info *info = (quiche_send_info *)(uintptr_t)ptr;
    return (jint)info->from_len;
}

/* --- sockaddr decode (slice 3 migration) ---
 * Native code knows the platform's sockaddr layout (sa_family value, field
 * offsets), so the JVM side never branches on OS for the JNI backend. The
 * returned address bits are an opaque identity (network byte order, as stored)
 * — only ever compared, never reconstructed. */

JNIEXPORT jint JNICALL JNI_FN(nSockAddrFamily)(JNIEnv *env, jclass cls, jlong addr) {
    struct sockaddr *sa = (struct sockaddr *)(uintptr_t)addr;
    if (!sa) return 0;
    if (sa->sa_family == AF_INET) return 4;
    if (sa->sa_family == AF_INET6) return 6;
    return 0;
}

JNIEXPORT jint JNICALL JNI_FN(nSockAddrPort)(JNIEnv *env, jclass cls, jlong addr) {
    struct sockaddr *sa = (struct sockaddr *)(uintptr_t)addr;
    if (sa->sa_family == AF_INET) return (jint)ntohs(((struct sockaddr_in *)sa)->sin_port);
    if (sa->sa_family == AF_INET6) return (jint)ntohs(((struct sockaddr_in6 *)sa)->sin6_port);
    return 0;
}

JNIEXPORT jlong JNICALL JNI_FN(nSockAddrV4)(JNIEnv *env, jclass cls, jlong addr) {
    struct sockaddr_in *sa = (struct sockaddr_in *)(uintptr_t)addr;
    return (jlong)(uint32_t)sa->sin_addr.s_addr;
}

JNIEXPORT jlong JNICALL JNI_FN(nSockAddrV6Hi)(JNIEnv *env, jclass cls, jlong addr) {
    struct sockaddr_in6 *sa = (struct sockaddr_in6 *)(uintptr_t)addr;
    uint64_t hi;
    memcpy(&hi, &sa->sin6_addr.s6_addr[0], 8);
    return (jlong)hi;
}

JNIEXPORT jlong JNICALL JNI_FN(nSockAddrV6Lo)(JNIEnv *env, jclass cls, jlong addr) {
    struct sockaddr_in6 *sa = (struct sockaddr_in6 *)(uintptr_t)addr;
    uint64_t lo;
    memcpy(&lo, &sa->sin6_addr.s6_addr[8], 8);
    return (jlong)lo;
}
