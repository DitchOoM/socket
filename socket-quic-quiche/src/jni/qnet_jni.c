/*
 * JNI entry points of qnet for com.ditchoom.socket.quic.JniNetShim (JDK 17-21 and Android). Each forwards its
 * integers unchanged; the FFM binding calls the qn_* functions themselves. Included at the end of quiche_jni.c, so
 * every build of the shim carries it.
 */
#ifndef _WIN32
#include "qnet.c"

#define QN_JNI(name) Java_com_ditchoom_socket_quic_JniNetShim_ ## name

JNIEXPORT jlong JNICALL QN_JNI(nPollerOpen)(JNIEnv *env, jclass cls, jint capacity) {
    return (jlong)qn_poller_open(capacity);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerAdd)(JNIEnv *env, jclass cls, jlong poller, jint fd, jint token) {
    return qn_poller_add(poller, fd, token);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerModify)(JNIEnv *env, jclass cls, jlong poller, jint fd, jint token,
                                              jint writable) {
    return qn_poller_modify(poller, fd, token, writable);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerRemove)(JNIEnv *env, jclass cls, jlong poller, jint fd) {
    return qn_poller_remove(poller, fd);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerWait)(JNIEnv *env, jclass cls, jlong poller, jint mode, jlong nanos) {
    return qn_poller_wait(poller, mode, nanos);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerReadyAt)(JNIEnv *env, jclass cls, jlong poller, jint index) {
    return qn_poller_ready_at(poller, index);
}

JNIEXPORT jint JNICALL QN_JNI(nPollerWake)(JNIEnv *env, jclass cls, jlong poller) { return qn_poller_wake(poller); }

JNIEXPORT void JNICALL QN_JNI(nPollerClose)(JNIEnv *env, jclass cls, jlong poller) { qn_poller_close(poller); }

JNIEXPORT jint JNICALL QN_JNI(nUdpOpen)(JNIEnv *env, jclass cls, jlong spec) { return qn_udp_open(spec); }

JNIEXPORT jint JNICALL QN_JNI(nUdpCapability)(JNIEnv *env, jclass cls, jint fd, jlong spec) {
    return qn_udp_capability(fd, spec);
}

JNIEXPORT jlong JNICALL QN_JNI(nUdpSend)(JNIEnv *env, jclass cls, jint fd, jint family, jint mode, jlong base,
                                          jint stride, jlong records, jint count, jint segments) {
    return qn_udp_send(fd, family, mode, base, stride, records, count, segments);
}

JNIEXPORT jlong JNICALL QN_JNI(nUdpRecv)(JNIEnv *env, jclass cls, jint fd, jint mode, jlong records, jint count,
                                          jint size) {
    return qn_udp_recv(fd, mode, records, count, size);
}

JNIEXPORT jlong JNICALL QN_JNI(nUdpRecvCoalesced)(JNIEnv *env, jclass cls, jint fd, jlong records, jint count, jint size,
                                                    jlong scratch) {
    return qn_udp_recv_coalesced(fd, records, count, size, scratch);
}

JNIEXPORT jint JNICALL QN_JNI(nUdpError)(JNIEnv *env, jclass cls, jint fd) { return qn_udp_error(fd); }

JNIEXPORT jint JNICALL QN_JNI(nClose)(JNIEnv *env, jclass cls, jint fd) { return qn_close(fd); }
#endif
