/* qnet UDP socket: open, capability probe, batched send and receive. Included by qnet.c. */

static int32_t qn_spec_int(int64_t spec, int offset) {
    int32_t v;
    memcpy(&v, qn_at(spec) + offset, 4);
    return v;
}

static void qn_spec_put(int64_t spec, int offset, int32_t v) { memcpy(qn_at(spec) + offset, &v, 4); }

#if QN_KQUEUE
static int qn_msg_x_available(void);
#endif

/* What this socket can batch, asked of the kernel without changing the socket. A refusal of any kind
 * (ENOPROTOOPT on an old kernel, EPERM or EINVAL from a filter) is "not available". */
QN_EXPORT int32_t qn_udp_capability(int32_t fd, int64_t spec) {
    int32_t caps = 0, segments = 0;
#if QN_EPOLL
    caps |= QN_CAP_MULTI;
    int value = 0;
    socklen_t len = sizeof value;
    if (getsockopt(fd, SOL_UDP, UDP_SEGMENT, &value, &len) == 0) {
        caps |= QN_CAP_GSO;
        segments = QN_GSO_MAX_SEGMENTS;
    }
    len = sizeof value;
    if (getsockopt(fd, SOL_UDP, UDP_GRO, &value, &len) == 0) caps |= QN_CAP_GRO;
#else
    (void)fd;
    if (qn_msg_x_available()) caps |= QN_CAP_MULTI | QN_CAP_PRIVATE;
#endif
    if (spec != 0) {
        qn_spec_put(spec, QN_SPEC_CAPS, caps);
        qn_spec_put(spec, QN_SPEC_GSO_SEGMENTS, segments);
    }
    return caps;
}

QN_EXPORT int32_t qn_udp_open(int64_t spec) {
    uint8_t *s = qn_at(spec);
    int32_t bind_family = qn_spec_int(spec, QN_SPEC_BIND);
    int family = bind_family == 6 ? AF_INET6 : AF_INET;
    struct sockaddr_storage bind_addr, peer_addr, local;
    socklen_t bind_len = qn_to_sockaddr(s + QN_SPEC_BIND, family, &bind_addr);
    if (bind_len == 0) return -QN_EINVAL;
    int fd = socket(family, SOCK_DGRAM, 0);
    if (fd < 0) return -qn_code(errno);
    int e = 0;
    if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0 || fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) < 0) goto failed;
    if (family == AF_INET6) {
        int off = 0;
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &off, sizeof off);
    }
    int rcv = qn_spec_int(spec, QN_SPEC_RCVBUF), snd = qn_spec_int(spec, QN_SPEC_SNDBUF);
    if (rcv > 0) setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcv, sizeof rcv);
    if (snd > 0) setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &snd, sizeof snd);
    if (bind(fd, (struct sockaddr *)&bind_addr, bind_len) < 0) goto failed;
    if (qn_spec_int(spec, QN_SPEC_PEER) != 0) {
        socklen_t peer_len = qn_to_sockaddr(s + QN_SPEC_PEER, family, &peer_addr);
        if (peer_len == 0) {
            errno = EINVAL;
            goto failed;
        }
        if (connect(fd, (struct sockaddr *)&peer_addr, peer_len) < 0) goto failed;
    }
    socklen_t len = sizeof rcv;
    getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcv, &len);
    len = sizeof snd;
    getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &snd, &len);
    qn_spec_put(spec, QN_SPEC_GRANTED_RCV, rcv);
    qn_spec_put(spec, QN_SPEC_GRANTED_SND, snd);
    socklen_t local_len = sizeof local;
    memset(&local, 0, sizeof local);
    if (getsockname(fd, (struct sockaddr *)&local, &local_len) < 0) goto failed;
    qn_from_sockaddr(&local, s + QN_SPEC_LOCAL);
    qn_udp_capability(fd, spec);
    return fd;
failed:
    e = errno;
    close(fd);
    return -qn_code(e);
}

static inline int64_t qn_result(int32_t count, int32_t code, int64_t flags) {
    return (int64_t)(uint32_t)count | ((int64_t)code << 32) | flags;
}

/* One datagram per system call. */
static int64_t qn_send_one(int fd, int family, uint8_t *base, int32_t stride, uint8_t *records, int32_t count) {
    for (int32_t i = 0; i < count; i++) {
        uint8_t *r = records + (size_t)i * QN_SEND_RECORD;
        int32_t length;
        memcpy(&length, r, 4);
        struct sockaddr_storage to;
        socklen_t to_len = qn_to_sockaddr(r + 8, family, &to);
        ssize_t sent;
        do {
            sent = to_len == 0 ? send(fd, base + (size_t)i * stride, (size_t)length, 0)
                               : sendto(fd, base + (size_t)i * stride, (size_t)length, 0, (struct sockaddr *)&to, to_len);
        } while (sent < 0 && errno == EINTR);
        if (sent < 0) return qn_result(i, qn_code(errno), 0);
    }
    return qn_result(count, 0, 0);
}

#if QN_EPOLL
static int qn_same_target(const uint8_t *a, const uint8_t *b) { return memcmp(a + 8, b + 8, 24) == 0; }

/* sendmmsg, each message one datagram or (with `segments` > 1) one UDP_SEGMENT group: consecutive datagrams to the
 * same target, all of one length but the last, which may be shorter. A message the kernel took is taken whole, so
 * the count of datagrams taken is exact. */
static int64_t qn_send_multi(int fd, int family, uint8_t *base, int32_t stride, uint8_t *records, int32_t count,
                             int32_t segments) {
    struct mmsghdr msgs[QN_MAX_BATCH];
    struct iovec iov[QN_MAX_BATCH];
    struct sockaddr_storage to[QN_MAX_BATCH];
    union {
        char buf[CMSG_SPACE(sizeof(uint16_t))];
        struct cmsghdr align;
    } control[QN_MAX_BATCH];
    int32_t datagrams_in[QN_MAX_BATCH];
    int64_t flags = 0;
    int32_t taken = 0;
    while (taken < count) {
        int nmsgs = 0, used_iov = 0;
        int32_t at = taken;
        while (at < count && nmsgs < QN_MAX_BATCH && used_iov < QN_MAX_BATCH) {
            uint8_t *first = records + (size_t)at * QN_SEND_RECORD;
            int32_t size;
            memcpy(&size, first, 4);
            int32_t group = 1;
            if (segments > 1) {
                while (at + group < count && group < segments && used_iov + group < QN_MAX_BATCH &&
                       (int64_t)(group + 1) * size <= QN_GSO_MAX_BYTES) {
                    uint8_t *next = records + (size_t)(at + group) * QN_SEND_RECORD;
                    int32_t next_size;
                    memcpy(&next_size, next, 4);
                    if (next_size > size || !qn_same_target(first, next)) break;
                    group++;
                    if (next_size < size) break; /* a shorter datagram ends its group */
                }
            }
            struct msghdr *h = &msgs[nmsgs].msg_hdr;
            memset(h, 0, sizeof *h);
            for (int32_t g = 0; g < group; g++) {
                int32_t length;
                memcpy(&length, records + (size_t)(at + g) * QN_SEND_RECORD, 4);
                iov[used_iov + g].iov_base = base + (size_t)(at + g) * stride;
                iov[used_iov + g].iov_len = (size_t)length;
            }
            h->msg_iov = &iov[used_iov];
            h->msg_iovlen = (size_t)group;
            socklen_t to_len = qn_to_sockaddr(first + 8, family, &to[nmsgs]);
            if (to_len != 0) {
                h->msg_name = &to[nmsgs];
                h->msg_namelen = to_len;
            }
            if (group > 1) {
                h->msg_control = control[nmsgs].buf;
                h->msg_controllen = sizeof control[nmsgs].buf;
                struct cmsghdr *c = CMSG_FIRSTHDR(h);
                c->cmsg_level = SOL_UDP;
                c->cmsg_type = UDP_SEGMENT;
                c->cmsg_len = CMSG_LEN(sizeof(uint16_t));
                uint16_t gso = (uint16_t)size;
                memcpy(CMSG_DATA(c), &gso, sizeof gso);
            }
            datagrams_in[nmsgs] = group;
            used_iov += group;
            at += group;
            nmsgs++;
        }
        int r;
        do {
            r = sendmmsg(fd, msgs, (unsigned)nmsgs, 0);
        } while (r < 0 && errno == EINTR);
        if (r < 0) {
            int e = errno;
            /* A device without checksum offload refuses a segmented send with EIO: send without it from now on. */
            if (e == EIO && segments > 1 && datagrams_in[0] > 1) {
                segments = 1;
                flags |= QN_RESULT_SEGMENT_REFUSED;
                continue;
            }
            return qn_result(taken, qn_code(e), flags);
        }
        for (int i = 0; i < r; i++) taken += datagrams_in[i];
    }
    return qn_result(taken, 0, flags);
}
#endif

#if QN_KQUEUE
#include "qnet_darwin.c"
#endif

QN_EXPORT int64_t qn_udp_send(int32_t fd, int32_t family_of_socket, int32_t mode, int64_t base, int32_t stride,
                              int64_t records, int32_t count, int32_t segments) {
    int family = family_of_socket == 6 ? AF_INET6 : AF_INET;
#if QN_EPOLL
    if (mode == QN_IO_MULTI) return qn_send_multi(fd, family, qn_at(base), stride, qn_at(records), count, 1);
    if (mode == QN_IO_SEGMENT) return qn_send_multi(fd, family, qn_at(base), stride, qn_at(records), count, segments);
#else
    (void)segments;
    if (mode != QN_IO_ONE && qn_msg_x_available()) return qn_send_x(fd, family, qn_at(base), stride, qn_at(records), count);
#endif
    return qn_send_one(fd, family, qn_at(base), stride, qn_at(records), count);
}

QN_EXPORT int64_t qn_udp_recv(int32_t fd, int32_t mode, int64_t records, int32_t count, int32_t size) {
    uint8_t *recs = qn_at(records);
    if (count > QN_MAX_BATCH) count = QN_MAX_BATCH;
#if QN_EPOLL
    if (mode != QN_IO_ONE) {
        struct mmsghdr msgs[QN_MAX_BATCH];
        struct iovec iov[QN_MAX_BATCH];
        struct sockaddr_storage from[QN_MAX_BATCH];
        memset(msgs, 0, sizeof(struct mmsghdr) * (size_t)count);
        for (int32_t i = 0; i < count; i++) {
            int64_t address;
            memcpy(&address, recs + (size_t)i * QN_RECV_RECORD, 8);
            iov[i].iov_base = qn_at(address);
            iov[i].iov_len = (size_t)size;
            msgs[i].msg_hdr.msg_iov = &iov[i];
            msgs[i].msg_hdr.msg_iovlen = 1;
            msgs[i].msg_hdr.msg_name = &from[i];
            msgs[i].msg_hdr.msg_namelen = sizeof from[i];
        }
        int r;
        do {
            r = recvmmsg(fd, msgs, (unsigned)count, MSG_DONTWAIT, NULL);
        } while (r < 0 && errno == EINTR);
        if (r < 0) return errno == EAGAIN || errno == EWOULDBLOCK ? 0 : qn_result(0, qn_code(errno), 0);
        for (int i = 0; i < r; i++) {
            uint8_t *rec = recs + (size_t)i * QN_RECV_RECORD;
            int32_t length = (int32_t)msgs[i].msg_len;
            memcpy(rec + 8, &length, 4);
            qn_from_sockaddr(&from[i], rec + 16);
        }
        return qn_result(r, 0, 0);
    }
#else
    if (mode != QN_IO_ONE && qn_msg_x_available()) return qn_recv_x(fd, recs, count, size);
#endif
    for (int32_t i = 0; i < count; i++) {
        uint8_t *rec = recs + (size_t)i * QN_RECV_RECORD;
        int64_t address;
        memcpy(&address, rec, 8);
        struct sockaddr_storage from;
        socklen_t from_len = sizeof from;
        memset(&from, 0, sizeof from);
        ssize_t got;
        do {
            got = recvfrom(fd, qn_at(address), (size_t)size, MSG_DONTWAIT, (struct sockaddr *)&from, &from_len);
        } while (got < 0 && errno == EINTR);
        if (got < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) return qn_result(i, 0, 0);
            return qn_result(i, qn_code(errno), 0);
        }
        int32_t length = (int32_t)got;
        memcpy(rec + 8, &length, 4);
        qn_from_sockaddr(&from, rec + 16);
    }
    return qn_result(count, 0, 0);
}

/* The socket's pending error, cleared by reading it: 0 when it has none, a QN code otherwise; -QN_EBADF when the
 * descriptor is no longer a socket. */
QN_EXPORT int32_t qn_udp_error(int32_t fd) {
    int value = 0;
    socklen_t len = sizeof value;
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &value, &len) < 0) return -qn_code(errno);
    return value == 0 ? 0 : qn_code(value);
}

QN_EXPORT int32_t qn_close(int32_t fd) { return close(fd) < 0 ? -qn_code(errno) : 0; }
