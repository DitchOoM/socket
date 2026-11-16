/*
 * qnet: the native stack's readiness poller and UDP socket calls, in the same native library as quiche.
 *
 * kqueue on Darwin, epoll on Linux and Android. Every function takes and returns integers only: addresses are
 * int64, results are counts or negated QN_E* codes, so the JNI, FFM and Kotlin/Native bindings stay one line each.
 * Errors are mapped here, once, to codes that mean the same on every platform (errno numbers do not).
 *
 * Address records use the core's PathKey convention: family 4 or 6, port, and the address as two big-endian
 * halves (IPv4 in the low 32 bits of lo). An IPv4-mapped IPv6 source is reported as family 4.
 */
#ifndef QNET_H
#define QNET_H

#include <stdint.h>

#if defined(__GNUC__) || defined(__clang__)
#define QN_EXPORT __attribute__((visibility("default")))
#else
#define QN_EXPORT
#endif

/* Error codes, returned negated. */
#define QN_EAGAIN 1
#define QN_EMSGSIZE 2
#define QN_ENETUNREACH 3
#define QN_EHOSTUNREACH 4
#define QN_ECONNREFUSED 5
#define QN_ENOBUFS 6
#define QN_EACCES 7
#define QN_EBADF 8
#define QN_EIO 9
#define QN_EADDRINUSE 10
#define QN_EADDRNOTAVAIL 11
#define QN_EAFNOSUPPORT 12
#define QN_ENOMEM 13
#define QN_EINVAL 14
#define QN_ENOPROTOOPT 15
#define QN_ENOSYS 16
#define QN_EPERM 17
#define QN_OTHER 99

/* qn_poller_wait modes. */
#define QN_WAIT_FOREVER 0
#define QN_WAIT_TIMED 1
#define QN_WAIT_NOW 2

/* Ready entry bits (qn_poller_ready_at returns token << 2 | bits). */
#define QN_READABLE 1
#define QN_WRITABLE 2

/* qn_poller_wait result: count << 1 | QN_WOKEN. */
#define QN_WOKEN 1

/* Send and receive modes. */
#define QN_IO_ONE 0     /* one system call per datagram, looped here */
#define QN_IO_MULTI 1   /* sendmmsg / recvmmsg */
#define QN_IO_SEGMENT 2 /* sendmmsg of UDP_SEGMENT groups */

/* Capability bits written by qn_udp_open / qn_udp_capability. */
#define QN_CAP_MULTI 1
#define QN_CAP_GSO 2
#define QN_CAP_GRO 4
#define QN_CAP_PRIVATE 8 /* QN_CAP_MULTI rides calls outside the public SDK (Darwin sendmsg_x/recvmsg_x) */

/* Socket spec block (caller memory, QN_SPEC_BYTES): in = bind and peer and buffer sizes; out = granted and local. */
#define QN_SPEC_BIND 0      /* record: int32 family, int32 port, int64 hi, int64 lo (24 bytes) */
#define QN_SPEC_PEER 24     /* record; family 0 = unconnected */
#define QN_SPEC_RCVBUF 48   /* int32 in */
#define QN_SPEC_SNDBUF 52   /* int32 in */
#define QN_SPEC_GRANTED_RCV 56
#define QN_SPEC_GRANTED_SND 60
#define QN_SPEC_LOCAL 64    /* record out */
#define QN_SPEC_CAPS 88     /* int32 out: QN_CAP_* */
#define QN_SPEC_GSO_SEGMENTS 92 /* int32 out */
#define QN_SPEC_BYTES 96

/* Send record, one per datagram (QN_SEND_RECORD bytes): int32 length, then the target record at +8 (family 0 =
 * the connected peer). */
#define QN_SEND_RECORD 32
/* Receive record, one per slot (QN_RECV_RECORD bytes): int64 buffer address (in), int32 length (out) at +8,
 * then the source record at +16. */
#define QN_RECV_RECORD 40

/* Send/receive result: low 32 bits = datagrams taken or received; bits 32..47 = QN_E* code of the refusal that
 * stopped it (0 = none); bit 48 = UDP_SEGMENT refused by the device, the batch was sent without it. */
#define QN_RESULT_SEGMENT_REFUSED (1LL << 48)

QN_EXPORT int64_t qn_poller_open(int32_t capacity);
QN_EXPORT int32_t qn_poller_add(int64_t poller, int32_t fd, int32_t token);
QN_EXPORT int32_t qn_poller_modify(int64_t poller, int32_t fd, int32_t token, int32_t writable);
QN_EXPORT int32_t qn_poller_remove(int64_t poller, int32_t fd);
QN_EXPORT int32_t qn_poller_wait(int64_t poller, int32_t mode, int64_t nanos);
QN_EXPORT int32_t qn_poller_ready_at(int64_t poller, int32_t index);
QN_EXPORT int32_t qn_poller_wake(int64_t poller);
QN_EXPORT void qn_poller_close(int64_t poller);

QN_EXPORT int32_t qn_udp_open(int64_t spec);
QN_EXPORT int32_t qn_udp_capability(int32_t fd, int64_t spec);
QN_EXPORT int64_t qn_udp_send(int32_t fd, int32_t family, int32_t mode, int64_t base, int32_t stride, int64_t records,
                              int32_t count, int32_t segments);
QN_EXPORT int64_t qn_udp_recv(int32_t fd, int32_t mode, int64_t records, int32_t count, int32_t size);
QN_EXPORT int32_t qn_udp_error(int32_t fd);
QN_EXPORT int32_t qn_close(int32_t fd);

#endif
