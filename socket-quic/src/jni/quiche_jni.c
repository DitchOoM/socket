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
    if (fin) packed |= (1L << 63);
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
