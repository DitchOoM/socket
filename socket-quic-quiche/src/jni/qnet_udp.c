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

#if QN_EPOLL
/* A GRO socket's receive scratch (QN_GRO_SCRATCH_BYTES), by descriptor, owned here: an anonymous private mapping, never
 * zeroed or touched beyond what the kernel writes, so a page is resident only where a datagram landed (about one per
 * lone datagram) and a socket that never receives costs nothing. qn_close unmaps it. */
#define QN_GRO_TABLE 65536
static void *qn_gro_scratch[QN_GRO_TABLE];

static void *qn_gro_scratch_of(int fd) {
    return fd >= 0 && fd < QN_GRO_TABLE ? __atomic_load_n(&qn_gro_scratch[fd], __ATOMIC_ACQUIRE) : NULL;
}

static void qn_gro_release(int fd) {
    if (fd < 0 || fd >= QN_GRO_TABLE) return;
    void *p = __atomic_exchange_n(&qn_gro_scratch[fd], NULL, __ATOMIC_ACQ_REL);
    if (p != NULL) munmap(p, QN_GRO_SCRATCH_BYTES);
}
#else
static void qn_gro_release(int fd) { (void)fd; }
#endif

/* UDP_GRO on when asked: 1 on, 0 not asked or this platform has none, -QN_E* when the kernel refused. */
static int32_t qn_gro_enable(int fd, int32_t want) {
#if QN_EPOLL
    if (want == 0) return 0;
    if (fd >= QN_GRO_TABLE) return -QN_ENOMEM;
    void *p = mmap(NULL, QN_GRO_SCRATCH_BYTES, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (p == MAP_FAILED) return -qn_code(errno);
    int on = 1;
    if (setsockopt(fd, SOL_UDP, UDP_GRO, &on, sizeof on) < 0) {
        int e = errno;
        munmap(p, QN_GRO_SCRATCH_BYTES);
        return -qn_code(e);
    }
    __atomic_store_n(&qn_gro_scratch[fd], p, __ATOMIC_RELEASE);
    return 1;
#else
    (void)fd;
    (void)want;
    return 0;
#endif
}

/* How many datagrams a coalesced read of `total` bytes holds when the kernel cut it every `gso` bytes (the last may be
 * shorter). A read with no segment size, or one no longer than it, is one datagram; so is an empty one. */
static int32_t qn_gro_count(int32_t total, int32_t gso) {
    if (gso <= 0 || total <= gso) return 1;
    return (int32_t)(((int64_t)total + gso - 1) / gso);
}

/* Splits one coalesced read into the slots of `recs` (QN_RECV_RECORD each: slot address in, length and source out), as
 * many datagrams as fit `count`, each copied up to `size` bytes. Returns the datagrams written. */
static int32_t qn_gro_split(const uint8_t *data, int32_t total, int32_t gso, uint8_t *recs, int32_t count,
                            int32_t size, const struct sockaddr_storage *from) {
    int32_t n = qn_gro_count(total, gso);
    if (n > count) n = count;
    for (int32_t i = 0; i < n; i++) {
        uint8_t *rec = recs + (size_t)i * QN_RECV_RECORD;
        int64_t address;
        memcpy(&address, rec, 8);
        int64_t start = (int64_t)i * (gso > 0 ? gso : total);
        int32_t length = total - (int32_t)start;
        if (gso > 0 && length > gso) length = gso;
        if (length > size) length = size;
        memcpy(qn_at(address), data + start, (size_t)length);
        memcpy(rec + 8, &length, 4);
        qn_from_sockaddr(from, rec + 16);
    }
    return n;
}


#if QN_KQUEUE && !defined(IPV6_DONTFRAG)
#define IPV6_DONTFRAG 62 /* <netinet6/in6.h> exposes it only under __APPLE_USE_RFC_3542, which a caller's earlier include may have skipped */
#endif

/* RFC 9000 §14: a datagram is never fragmented by the sender's IP layer; one over the link's MTU is refused with
 * EMSGSIZE, which is what path MTU discovery needs. Linux/Android: PROBE sets DF and ignores the kernel's cached
 * PMTU, so only quiche's own discovery moves the size. Darwin: DONTFRAG. 0 when set, else the QN_E* code. */
static int qn_set_dont_fragment(int fd, int family) {
    int first = 0;
#if QN_EPOLL
    int probe = IP_PMTUDISC_PROBE;
    if (setsockopt(fd, IPPROTO_IP, IP_MTU_DISCOVER, &probe, sizeof probe) < 0) first = qn_code(errno);
    if (family == AF_INET6) {
        int probe6 = IPV6_PMTUDISC_PROBE;
        if (setsockopt(fd, IPPROTO_IPV6, IPV6_MTU_DISCOVER, &probe6, sizeof probe6) < 0 && first == 0) first = qn_code(errno);
    }
#else
    int on = 1;
    if (family == AF_INET6) {
        /* A dual-stack socket sends IPv4-mapped datagrams with the IPv4 option; an IPv6-only kernel path may refuse it. */
        if (setsockopt(fd, IPPROTO_IPV6, IPV6_DONTFRAG, &on, sizeof on) < 0) first = qn_code(errno);
        setsockopt(fd, IPPROTO_IP, IP_DONTFRAG, &on, sizeof on);
    } else if (setsockopt(fd, IPPROTO_IP, IP_DONTFRAG, &on, sizeof on) < 0) {
        first = qn_code(errno);
    }
#endif
    return first;
}

/* Reads the option back: 1 = DF is set on this socket's datagrams, 0 = not, negated QN_E* on error. */
QN_EXPORT int32_t qn_udp_dont_fragment(int32_t fd, int32_t family_of_socket) {
    int value = 0;
    socklen_t len = sizeof value;
#if QN_EPOLL
    if (getsockopt(fd, IPPROTO_IP, IP_MTU_DISCOVER, &value, &len) < 0) return -qn_code(errno);
    if (value != IP_PMTUDISC_PROBE && value != IP_PMTUDISC_DO) return 0;
    if (family_of_socket == 6) {
        len = sizeof value;
        if (getsockopt(fd, IPPROTO_IPV6, IPV6_MTU_DISCOVER, &value, &len) < 0) return -qn_code(errno);
        if (value != IPV6_PMTUDISC_PROBE && value != IPV6_PMTUDISC_DO) return 0;
    }
    return 1;
#else
    if (family_of_socket == 6) {
        if (getsockopt(fd, IPPROTO_IPV6, IPV6_DONTFRAG, &value, &len) < 0) return -qn_code(errno);
    } else if (getsockopt(fd, IPPROTO_IP, IP_DONTFRAG, &value, &len) < 0) {
        return -qn_code(errno);
    }
    return value != 0;
#endif
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
    qn_spec_put(spec, QN_SPEC_DF, qn_set_dont_fragment(fd, family));
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
    qn_spec_put(spec, QN_SPEC_GRO_STATE, qn_gro_enable(fd, qn_spec_int(spec, QN_SPEC_GRO_WANT)));
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

#if QN_EPOLL
/* Counts the receive syscalls the batched UDP paths make on Linux, so a test can compare them. */
static int64_t qn_recv_syscalls;

QN_EXPORT int64_t qn_udp_recv_syscalls(void) { return __atomic_load_n(&qn_recv_syscalls, __ATOMIC_RELAXED); }
#else
QN_EXPORT int64_t qn_udp_recv_syscalls(void) { return 0; }
#endif

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
            __atomic_fetch_add(&qn_recv_syscalls, 1, __ATOMIC_RELAXED);
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

#if QN_EPOLL
/* The UDP_GRO segment size of a received message: 0 when the kernel did not coalesce it. The kernel writes an int; an
 * older one wrote the 16 bits it had. */
static int32_t qn_gro_segment_size(struct msghdr *h) {
    for (struct cmsghdr *c = CMSG_FIRSTHDR(h); c != NULL; c = CMSG_NXTHDR(h, c)) {
        if (c->cmsg_level != SOL_UDP || c->cmsg_type != UDP_GRO) continue;
        size_t bytes = c->cmsg_len - CMSG_LEN(0);
        if (bytes >= sizeof(int32_t)) {
            int32_t v;
            memcpy(&v, CMSG_DATA(c), sizeof v);
            return v;
        }
        uint16_t v;
        memcpy(&v, CMSG_DATA(c), sizeof v);
        return v;
    }
    return 0;
}
#endif

#if QN_EPOLL


/* What a coalescing socket keeps between reads, at the start of its scratch block: the whole messages one recvmmsg took
 * that did not all fit the caller's slots. A message is never split across reads, so what did not fit waits here, whole,
 * and the next read delivers it first. The caller zeroes the header once. Message i's bytes are at
 * QN_GRO_HEADER_BYTES + i * QN_GRO_MESSAGE_BYTES. */
struct qn_gro_state {
    int32_t first;
    int32_t count;
    int32_t length[QN_GRO_MAX_DATAGRAMS];
    int32_t segment[QN_GRO_MAX_DATAGRAMS];
    struct sockaddr_storage from[QN_GRO_MAX_DATAGRAMS];
};

/* Delivers the waiting messages, whole and in order, while their datagrams fit the slots left. Returns the filled total. */
static int32_t qn_gro_deliver(struct qn_gro_state *st, uint8_t *messages, uint8_t *recs, int32_t filled, int32_t count,
                              int32_t size) {
    while (st->count > 0) {
        int32_t j = st->first;
        int32_t n = qn_gro_count(st->length[j], st->segment[j]);
        if (filled + n > count) break;
        filled += qn_gro_split(messages + (size_t)j * QN_GRO_MESSAGE_BYTES, st->length[j], st->segment[j],
                               recs + (size_t)filled * QN_RECV_RECORD, count - filled, size, &st->from[j]);
        /* A message that took many pages gives them back once its datagrams are copied out: the buffer is free again. */
        if (st->length[j] > QN_GRO_RELEASE_ABOVE)
            madvise(messages + (size_t)j * QN_GRO_MESSAGE_BYTES, ((size_t)st->length[j] + 4095) & ~(size_t)4095, MADV_DONTNEED);
        st->first++;
        st->count--;
    }
    return filled;
}
#endif

QN_EXPORT int64_t qn_udp_gro_scratch(int32_t fd) {
#if QN_EPOLL
    return (int64_t)(uintptr_t)qn_gro_scratch_of(fd);
#else
    (void)fd;
    return 0;
#endif
}

QN_EXPORT int64_t qn_udp_recv_coalesced(int32_t fd, int64_t records, int32_t count, int32_t size) {
#if QN_EPOLL
    if (count > QN_MAX_BATCH) count = QN_MAX_BATCH;
    uint8_t *scratch = qn_gro_scratch_of(fd);
    if (scratch == NULL) return qn_result(0, QN_EBADF, 0);
    struct qn_gro_state *st = (struct qn_gro_state *)scratch;
    uint8_t *messages = scratch + QN_GRO_HEADER_BYTES;
    uint8_t *recs = qn_at(records);
    int32_t filled = qn_gro_deliver(st, messages, recs, 0, count, size);
    /* Waiting messages hold the buffers: read no more until they are all delivered. */
    if (st->count > 0 || filled >= count) return qn_result(filled, 0, st->count > 0 ? QN_RESULT_BACKLOG : 0);
    /* One recvmmsg takes up to a message per free slot, so lone datagrams cost what the per-datagram path costs, and
     * every coalesced message adds its segments on top. */
    int32_t vlen = count - filled;
    struct mmsghdr msgs[QN_MAX_BATCH];
    struct iovec iov[QN_MAX_BATCH];
    union {
        char buf[CMSG_SPACE(sizeof(int32_t))];
        struct cmsghdr align;
    } control[QN_MAX_BATCH];
    memset(msgs, 0, sizeof(struct mmsghdr) * (size_t)vlen);
    for (int32_t i = 0; i < vlen; i++) {
        iov[i].iov_base = messages + (size_t)i * QN_GRO_MESSAGE_BYTES;
        iov[i].iov_len = QN_GRO_MESSAGE_BYTES;
        msgs[i].msg_hdr.msg_iov = &iov[i];
        msgs[i].msg_hdr.msg_iovlen = 1;
        msgs[i].msg_hdr.msg_name = &st->from[i];
        msgs[i].msg_hdr.msg_namelen = sizeof st->from[i];
        msgs[i].msg_hdr.msg_control = control[i].buf;
        msgs[i].msg_hdr.msg_controllen = sizeof control[i].buf;
    }
    int r;
    do {
        __atomic_fetch_add(&qn_recv_syscalls, 1, __ATOMIC_RELAXED);
        r = recvmmsg(fd, msgs, (unsigned)vlen, MSG_DONTWAIT, NULL);
    } while (r < 0 && errno == EINTR);
    if (r < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return qn_result(filled, 0, 0);
        return qn_result(filled, qn_code(errno), 0);
    }
    for (int i = 0; i < r; i++) {
        st->length[i] = (int32_t)msgs[i].msg_len;
        st->segment[i] = qn_gro_segment_size(&msgs[i].msg_hdr);
    }
    st->first = 0;
    st->count = r;
    filled = qn_gro_deliver(st, messages, recs, filled, count, size);
    return qn_result(filled, 0, st->count > 0 ? QN_RESULT_BACKLOG : 0);
#else
    (void)fd, (void)records, (void)count, (void)size;
    return qn_result(0, QN_ENOSYS, 0);
#endif
}

/* The socket's pending error, cleared by reading it: 0 when it has none, a QN code otherwise; -QN_EBADF when the
 * descriptor is no longer a socket. */
QN_EXPORT int32_t qn_udp_error(int32_t fd) {
    int value = 0;
    socklen_t len = sizeof value;
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &value, &len) < 0) return -qn_code(errno);
    return value == 0 ? 0 : qn_code(value);
}

QN_EXPORT int32_t qn_close(int32_t fd) {
    qn_gro_release(fd);
    return close(fd) < 0 ? -qn_code(errno) : 0;
}
