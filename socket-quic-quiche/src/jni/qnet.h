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
#define QN_EPIPE 18
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

/* Capability bits returned by qn_udp_capabilities (low 8 bits; the GSO segment ceiling is above them). */
#define QN_CAP_SEGMENTS_SHIFT 8
#define QN_CAP_MULTI 1
#define QN_CAP_GSO 2
#define QN_CAP_GRO 4
#define QN_CAP_PRIVATE 8 /* QN_CAP_MULTI rides calls outside the public SDK (Darwin sendmsg_x/recvmsg_x) */

/* Socket spec block (caller memory, QN_SPEC_BYTES) for qn_udp_open_socket: in = bind, peer and buffer sizes; out = granted
 * buffer sizes and the local address. Socket options are not in it: each is a call on the open descriptor with its own
 * result (qn_udp_set_gro, qn_udp_set_dont_fragment, qn_udp_capabilities). The Kotlin side checks its copy of these
 * offsets against qn_spec_layout() before it opens a socket. */
#define QN_SPEC_BIND 0    /* record: int32 family, int32 port, int64 hi, int64 lo (24 bytes) */
#define QN_SPEC_PEER 24   /* record; family 0 = unconnected */
#define QN_SPEC_RCVBUF 48 /* int32 in */
#define QN_SPEC_SNDBUF 52 /* int32 in */
#define QN_SPEC_GRANTED_RCV 56
#define QN_SPEC_GRANTED_SND 60
#define QN_SPEC_LOCAL 64 /* record out */
#define QN_SPEC_BYTES 88

/* qn_spec_layout(): a fingerprint of the offsets above, folded as h = h * 31 + offset over QN_SPEC_BYTES, BIND, PEER, RCVBUF,
 * SNDBUF, GRANTED_RCV, GRANTED_SND, LOCAL in that order, then over the send and receive records' sizes and their
 * source and destination offsets (QN_SEND_RECORD, QN_SEND_SOURCE, QN_RECV_RECORD, QN_RECV_DEST). */
#define QN_SPEC_FOLD(h, offset) ((h) * 31u + (uint32_t)(offset))
#define QN_SPEC_LAYOUT                                                                                              \
    ((int32_t)QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD(                                      \
        QN_SPEC_FOLD(                                                                                                \
            QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD(QN_SPEC_FOLD((uint32_t)QN_SPEC_BYTES, QN_SPEC_BIND), \
                                                                QN_SPEC_PEER),                                       \
                                                   QN_SPEC_RCVBUF),                                                  \
                                      QN_SPEC_SNDBUF),                                                               \
                         QN_SPEC_GRANTED_RCV),                                                                       \
            QN_SPEC_GRANTED_SND),                                                                                    \
        QN_SPEC_LOCAL), QN_SEND_RECORD), QN_SEND_SOURCE), QN_RECV_RECORD), QN_RECV_DEST))

/* Send record, one per datagram (QN_SEND_RECORD bytes): int32 length, then the target record at +8 (family 0 =
 * the connected peer), then the source record at +32: the local address the datagram leaves from (family 0 or the
 * wildcard = the kernel chooses). */
#define QN_SEND_RECORD 56
#define QN_SEND_SOURCE 32
/* Receive record, one per slot (QN_RECV_RECORD bytes): int64 buffer address (in), int32 length (out) at +8,
 * then the source record at +16, then at +40 the local address the datagram was sent to, port 0 (family 0 = not
 * reported: only an unconnected socket bound to a wildcard reports it). */
#define QN_RECV_RECORD 64
#define QN_RECV_DEST 40

/* qn_udp_recv_coalesced's scratch block, which qnet owns (an anonymous mapping made when UDP_GRO is turned on, resident
 * only where the kernel wrote, unmapped by qn_close): a header holding the whole messages that did not fit the caller's
 * slots, then one buffer per message (a UDP_GRO message is at most 64 KiB),
 * as many messages as a batch has slots (QN_GRO_MAX_DATAGRAMS; the kernel's UDP_MAX_SEGMENTS is 64 too). */
#define QN_GRO_HEADER_BYTES 16384
#define QN_GRO_MESSAGE_BYTES 65536
#define QN_GRO_MAX_DATAGRAMS 64
/* Messages longer than this return their pages to the kernel once delivered (madvise DONTNEED). */
#define QN_GRO_RELEASE_ABOVE 32768
#define QN_GRO_SCRATCH_BYTES (QN_GRO_HEADER_BYTES + QN_GRO_MAX_DATAGRAMS * QN_GRO_MESSAGE_BYTES)

/* Send/receive result: low 32 bits = datagrams taken or received; bits 32..47 = QN_E* code of the refusal that
 * stopped it (0 = none); bit 48 = UDP_SEGMENT refused by the device, the batch was sent without it. */
#define QN_RESULT_SEGMENT_REFUSED (1LL << 48)
/* qn_udp_recv_coalesced: whole messages wait inside qnet, where the poller cannot see them: read again before sleeping. */
#define QN_RESULT_BACKLOG (1LL << 49)

/* The poller's address, or a negated QN_E* code (always in -4095..-1: an address may read as negative, since Android
 * tags the top byte of its heap pointers). */
QN_EXPORT int64_t qn_poller_open(int32_t capacity);
QN_EXPORT int32_t qn_poller_add(int64_t poller, int32_t fd, int32_t token);
QN_EXPORT int32_t qn_poller_modify(int64_t poller, int32_t fd, int32_t token, int32_t writable);
QN_EXPORT int32_t qn_poller_remove(int64_t poller, int32_t fd);
QN_EXPORT int32_t qn_poller_wait(int64_t poller, int32_t mode, int64_t nanos);
QN_EXPORT int32_t qn_poller_ready_at(int64_t poller, int32_t index);
QN_EXPORT int32_t qn_poller_wake(int64_t poller);
QN_EXPORT void qn_poller_close(int64_t poller);

/* QN_SPEC_LAYOUT as this library was built, for the caller to compare with its own copy. */
QN_EXPORT int32_t qn_spec_layout(void);
/* The descriptor of a non-blocking, close-on-exec UDP socket opened from the spec block, or a negated QN_E* code. */
QN_EXPORT int32_t qn_udp_open_socket(int64_t spec);
/* What the socket can batch, asked of the kernel without changing it: QN_CAP_* bits, and the GSO segment ceiling
 * shifted by QN_CAP_SEGMENTS_SHIFT. */
QN_EXPORT int32_t qn_udp_capabilities(int32_t fd);
/* Turns UDP_GRO on and gives the socket its receive scratch: 1 on, 0 this platform has none, negated QN_E* when refused.
 * Call again on a socket reopened from the same spec. */
QN_EXPORT int32_t qn_udp_set_gro(int32_t fd);
/* Sets DF on the socket's datagrams (RFC 9000 section 14): 0 when set, else the negated QN_E* code of the refusal. */
QN_EXPORT int32_t qn_udp_set_dont_fragment(int32_t fd, int32_t family_of_socket);
/* 1 when the socket's own option says it sets DF on its datagrams (the family's option), 0 when not, negated QN_E* on error. */
QN_EXPORT int32_t qn_udp_dont_fragment(int32_t fd, int32_t family_of_socket);
QN_EXPORT int64_t qn_udp_send(int32_t fd, int32_t family, int32_t mode, int64_t base, int32_t stride, int64_t records,
                              int32_t count, int32_t segments);
QN_EXPORT int64_t qn_udp_recv(int32_t fd, int32_t mode, int64_t records, int32_t count, int32_t size);
/* Receive on a socket opened with qn_udp_set_gro: one recvmmsg of up to a message per free slot, each message split by
 * its own UDP_GRO segment size (none: a single datagram) into the receive records' slots exactly as separately received
 * datagrams would be, each record carrying its message's source. Lone datagrams therefore cost what qn_udp_recv's
 * QN_IO_MULTI costs. A message is never split across reads: one that does not fit the slots left waits whole in the
 * scratch and is delivered first by the next call (which reads nothing new until the waiting ones are out). Each slot gets
 * at most `size` bytes. The result has QN_RESULT_BACKLOG set while whole messages still wait inside. The result is qn_udp_recv's. Only Linux and
 * Android coalesce; elsewhere this refuses with QN_ENOSYS. */
QN_EXPORT int64_t qn_udp_recv_coalesced(int32_t fd, int64_t records, int32_t count, int32_t size);
/* The address of the socket's receive scratch (0 if it has none): a diagnostic, for measuring what is resident. */
QN_EXPORT int64_t qn_udp_gro_scratch(int32_t fd);
/* Receive syscalls made so far by the batched qn_udp_recv and qn_udp_recv_coalesced on Linux (0 elsewhere): a diagnostic. */
QN_EXPORT int64_t qn_udp_recv_syscalls(void);
QN_EXPORT int32_t qn_udp_error(int32_t fd);
QN_EXPORT int32_t qn_close(int32_t fd);

#endif
