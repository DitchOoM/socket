/*
 * qnet's own red tests, below any binding: build and run on each kernel with
 *   cc -O1 -Wall -o /tmp/qnet_selftest socket-quic-quiche/src/jni/test/qnet_selftest.c && /tmp/qnet_selftest
 * Every mode the kernel offers is driven: a batch refused part way reports exactly how many datagrams went, a
 * segmented batch arrives as its datagrams with their own lengths and bytes, and the poller wakes and reports.
 */
#include "../qnet.c"

#include <stdio.h>

static int failures = 0;
#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            failures++;                                                    \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);                    \
            printf(__VA_ARGS__);                                           \
            printf("\n");                                                  \
        }                                                                  \
    } while (0)

#define STRIDE 70016
#define SLOTS 16

static uint8_t spec_a[QN_SPEC_BYTES], spec_b[QN_SPEC_BYTES];
static uint8_t send_records[QN_SEND_RECORD * SLOTS];
static uint8_t recv_records[QN_RECV_RECORD * 64];
static uint8_t *send_base;
static uint8_t *recv_bufs[64];

static void put_record(uint8_t *r, int32_t family, int32_t port, uint64_t hi, uint64_t lo) {
    memcpy(r, &family, 4);
    memcpy(r + 4, &port, 4);
    memcpy(r + 8, &hi, 8);
    memcpy(r + 16, &lo, 8);
}

static int32_t rec_int(const uint8_t *r, int off) {
    int32_t v;
    memcpy(&v, r + off, 4);
    return v;
}

static int open_socket(uint8_t *spec, int family, const uint8_t *peer_local) {
    memset(spec, 0, QN_SPEC_BYTES);
    if (family == 4) put_record(spec + QN_SPEC_BIND, 4, 0, 0, 0x7f000001);
    else put_record(spec + QN_SPEC_BIND, 6, 0, 0, 1);
    if (peer_local != NULL) memcpy(spec + QN_SPEC_PEER, peer_local, 24);
    qn_spec_put((int64_t)(uintptr_t)spec, QN_SPEC_RCVBUF, 4 << 20);
    qn_spec_put((int64_t)(uintptr_t)spec, QN_SPEC_SNDBUF, 2 << 20);
    return qn_udp_open((int64_t)(uintptr_t)spec);
}

static void fill(int32_t i, int32_t length) {
    put_record(send_records + i * QN_SEND_RECORD + 8, 0, 0, 0, 0);
    memcpy(send_records + i * QN_SEND_RECORD, &length, 4);
    memset(send_base + (size_t)i * STRIDE, 'a' + i, (size_t)length);
}

static int32_t receive_all(int poller_fd_unused, int fd, int mode, int32_t expected, int32_t *lengths) {
    (void)poller_fd_unused;
    int32_t got = 0;
    for (int attempt = 0; attempt < 200 && got < expected; attempt++) {
        for (int i = 0; i < 64; i++) {
            int64_t a = (int64_t)(uintptr_t)recv_bufs[i];
            memcpy(recv_records + i * QN_RECV_RECORD, &a, 8);
        }
        int64_t r = qn_udp_recv(fd, mode, (int64_t)(uintptr_t)recv_records, 64, 2048);
        int32_t n = (int32_t)(r & 0xffffffff);
        for (int i = 0; i < n && got < 64; i++) {
            int32_t length = rec_int(recv_records + i * QN_RECV_RECORD, 8);
            lengths[got] = length;
            CHECK(recv_bufs[i][0] == recv_bufs[i][length - 1], "datagram %d is torn", got);
            lengths[got] |= (int32_t)recv_bufs[i][0] << 24;
            got++;
        }
        if (n == 0) usleep(1000);
    }
    return got;
}

static void batch_round_trip(int family, int mode, int segments) {
    int server = open_socket(spec_b, family, NULL);
    CHECK(server >= 0, "server open %d", server);
    int client = open_socket(spec_a, family, spec_b + QN_SPEC_LOCAL);
    CHECK(client >= 0, "client open %d", client);
    int32_t sizes[] = {1200, 1200, 1200, 1200, 1200, 700, 1200, 1200, 1200};
    int32_t n = 9;
    for (int32_t i = 0; i < n; i++) fill(i, sizes[i]);
    int64_t r = qn_udp_send(client, family, mode, (int64_t)(uintptr_t)send_base, STRIDE,
                            (int64_t)(uintptr_t)send_records, n, segments);
    CHECK((r & 0xffffffff) == n && ((r >> 32) & 0xffff) == 0, "family %d mode %d: sent %lld", family, mode,
          (long long)(r & 0xffffffff));
    int32_t lengths[64];
    int32_t got = receive_all(0, server, mode, n, lengths);
    CHECK(got == n, "family %d mode %d: received %d of %d", family, mode, got, n);
    for (int32_t i = 0; i < got && i < n; i++) {
        CHECK((lengths[i] & 0xffffff) == sizes[i] && (lengths[i] >> 24) == 'a' + i,
              "family %d mode %d: datagram %d has length %d tag %c", family, mode, i, lengths[i] & 0xffffff,
              lengths[i] >> 24);
    }
    /* The source of what arrived is the client's own local address. */
    CHECK(memcmp(recv_records + 16, spec_a + QN_SPEC_LOCAL, 24) == 0, "family %d: source differs from client", family);

    /* A refusal part way: datagram 3 goes where this socket cannot send (an IPv6 address from an IPv4 socket, port 0
     * from an IPv6 one). Exactly 3 were taken, and the refusal is named; the datagrams after it stay unsent. */
    for (int32_t i = 0; i < 6; i++) {
        fill(i, 1000);
        memcpy(send_records + i * QN_SEND_RECORD + 8, spec_a + QN_SPEC_LOCAL, 24);
    }
    put_record(send_records + 3 * QN_SEND_RECORD + 8, 6, family == 6 ? 0 : 4433, 0, 1);
    r = qn_udp_send(server, family, mode, (int64_t)(uintptr_t)send_base, STRIDE, (int64_t)(uintptr_t)send_records, 6,
                    segments);
    CHECK((r & 0xffffffff) == 3, "family %d mode %d: refused batch took %lld, want 3", family, mode,
          (long long)(r & 0xffffffff));
    CHECK(((r >> 32) & 0xffff) != 0, "family %d mode %d: no refusal named", family, mode);
    printf("family %d mode %d: refusal code %lld\n", family, mode, (long long)((r >> 32) & 0xffff));
    got = receive_all(0, client, mode, 3, lengths);
    CHECK(got == 3, "family %d mode %d: after refusal received %d", family, mode, got);

    /* The same on a connected socket, where the batched calls take no destinations: datagram 3 is larger than any
     * IPv4 datagram. */
    if (family == 4) {
        for (int32_t i = 0; i < 6; i++) fill(i, i == 3 ? 70000 : 1000);
        r = qn_udp_send(client, family, mode, (int64_t)(uintptr_t)send_base, STRIDE, (int64_t)(uintptr_t)send_records,
                        6, segments);
        CHECK((r & 0xffffffff) == 3 && ((r >> 32) & 0xffff) == QN_EMSGSIZE,
              "mode %d: connected refused batch took %lld code %lld, want 3 and EMSGSIZE", mode,
              (long long)(r & 0xffffffff), (long long)((r >> 32) & 0xffff));
        got = receive_all(0, server, mode, 3, lengths);
        CHECK(got == 3, "mode %d: after connected refusal received %d", mode, got);
    }

    /* Unconnected replies: one batch to the client, by its address. */
    for (int32_t i = 0; i < 4; i++) {
        fill(i, 900);
        memcpy(send_records + i * QN_SEND_RECORD + 8, spec_a + QN_SPEC_LOCAL, 24);
    }
    r = qn_udp_send(server, family, mode, (int64_t)(uintptr_t)send_base, STRIDE, (int64_t)(uintptr_t)send_records, 4,
                    segments);
    CHECK((r & 0xffffffff) == 4, "family %d mode %d: replies sent %lld", family, mode, (long long)(r & 0xffffffff));
    got = receive_all(0, client, mode, 4, lengths);
    CHECK(got == 4, "family %d mode %d: replies received %d", family, mode, got);
    qn_close(client);
    qn_close(server);
}

static void poller(void) {
    int64_t p = qn_poller_open(8);
    CHECK(p > 0, "poller open %lld", (long long)p);
    CHECK(qn_poller_wait(p, QN_WAIT_NOW, 0) == 0, "an idle poller reported something");
    qn_poller_wake(p);
    CHECK(qn_poller_wait(p, QN_WAIT_TIMED, 1000000000LL) == QN_WOKEN, "wake not seen");
    CHECK(qn_poller_wait(p, QN_WAIT_NOW, 0) == 0, "wake seen twice");
    int server = open_socket(spec_b, 6, NULL);
    int client = open_socket(spec_a, 6, spec_b + QN_SPEC_LOCAL);
    CHECK(qn_poller_add(p, server, 5) == 0, "add");
    CHECK(qn_poller_add(p, client, 7) == 0, "add client");
    CHECK(qn_poller_modify(p, client, 7, 1) == 0, "write interest");
    fill(0, 100);
    int64_t sent = qn_udp_send(client, 6, QN_IO_ONE, (int64_t)(uintptr_t)send_base, STRIDE, (int64_t)(uintptr_t)send_records, 1, 1);
    CHECK(sent == 1, "poller probe datagram: %lld", (long long)sent);
    int32_t r = 0;
    int found_read = 0, found_write = 0;
    for (int attempt = 0; attempt < 50 && !(found_read && found_write); attempt++) {
        r = qn_poller_wait(p, QN_WAIT_TIMED, 100000000LL);
        usleep(2000); /* loopback delivery is asynchronous on Darwin; a writable socket returns at once */
        for (int i = 0; i < (r >> 1); i++) {
            int32_t e = qn_poller_ready_at(p, i);
            if ((e >> 2) == 5 && (e & QN_READABLE)) found_read = 1;
            if ((e >> 2) == 7 && (e & QN_WRITABLE)) found_write = 1;
        }
    }
    CHECK(found_read && found_write, "ready: read %d write %d (r=%d)", found_read, found_write, r);
    CHECK(qn_poller_modify(p, client, 7, 0) == 0, "drop write interest");
    CHECK(qn_poller_remove(p, client) == 0 && qn_poller_remove(p, server) == 0, "remove");
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    CHECK(qn_poller_wait(p, QN_WAIT_TIMED, 20000000LL) == 0, "removed sockets reported");
    clock_gettime(CLOCK_MONOTONIC, &t1);
    int64_t waited = (t1.tv_sec - t0.tv_sec) * 1000000000LL + (t1.tv_nsec - t0.tv_nsec);
    CHECK(waited >= 20000000LL, "timed wait returned early after %lld ns", (long long)waited);
    qn_close(client);
    qn_close(server);
    qn_poller_close(p);
}

/* RFC 9000 section 14: every socket opens with DF set, read back from the kernel; where the link is smaller than the
 * datagram, the send is refused instead of fragmented. */
static void dont_fragment(void) {
    for (int family = 4; family <= 6; family += 2) {
        int fd = open_socket(spec_a, family, NULL);
        CHECK(fd >= 0, "family %d: open %d", family, fd);
        CHECK(qn_spec_int((int64_t)(uintptr_t)spec_a, QN_SPEC_DF) == 0, "family %d: open reported DF refused %d", family,
              qn_spec_int((int64_t)(uintptr_t)spec_a, QN_SPEC_DF));
        CHECK(qn_udp_dont_fragment(fd, family) == 1, "family %d: DF not set on the socket", family);
#if QN_KQUEUE
        /* lo0's MTU is 16384: a datagram over it is refused with DF, fragmented without. */
        int server = open_socket(spec_b, family, NULL);
        fill(0, 20000);
        put_record(send_records + 8, family, rec_int(spec_b + QN_SPEC_LOCAL, 4), 0, family == 4 ? 0x7f000001 : 1);
        int64_t r = qn_udp_send(fd, family, QN_IO_ONE, (int64_t)(uintptr_t)send_base, STRIDE, (int64_t)(uintptr_t)send_records, 1, 1);
        CHECK((r & 0xffffffff) == 0 && ((r >> 32) & 0xffff) == QN_EMSGSIZE, "family %d: oversize send %llx", family, (long long)r);
        qn_close(server);
#endif
        qn_close(fd);
    }
}

int main(void) {
    send_base = calloc(SLOTS, STRIDE);
    for (int i = 0; i < 64; i++) recv_bufs[i] = calloc(1, 2048);
    poller();
    dont_fragment();
    int caps = 0;
    int probe = open_socket(spec_a, 6, NULL);
    caps = qn_udp_capability(probe, 0);
    qn_close(probe);
    printf("capabilities: multi=%d gso=%d gro=%d\n", !!(caps & QN_CAP_MULTI), !!(caps & QN_CAP_GSO),
           !!(caps & QN_CAP_GRO));
    for (int family = 4; family <= 6; family += 2) {
        batch_round_trip(family, QN_IO_ONE, 1);
        if (caps & QN_CAP_MULTI) batch_round_trip(family, QN_IO_MULTI, 1);
        if (caps & QN_CAP_GSO) batch_round_trip(family, QN_IO_SEGMENT, QN_GSO_MAX_SEGMENTS);
    }
    printf(failures == 0 ? "qnet selftest: all passed\n" : "qnet selftest: %d failed\n", failures);
    return failures == 0 ? 0 : 1;
}
