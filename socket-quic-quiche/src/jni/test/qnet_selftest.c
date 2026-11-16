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
#define SLOTS 32

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


/* A coalesced receive of `total` bytes (value i in segment i) with `gso` as the segment size: how many datagrams, and
 * the exact length and bytes of each, copied into the slots without touching anything but the slots. */
static void split_case(int32_t total, int32_t gso, int32_t want_count, int32_t want_last) {
    static uint8_t data[70000];
    for (int32_t i = 0; i < total; i++) data[i] = (uint8_t)(gso > 0 ? i / gso : 0);
    struct sockaddr_storage from;
    memset(&from, 0, sizeof from);
    struct sockaddr_in *a = (struct sockaddr_in *)&from;
    a->sin_family = AF_INET;
    a->sin_port = htons(4433);
    a->sin_addr.s_addr = htonl(0x7f000001);
    memset(recv_records, 0, sizeof recv_records);
    for (int i = 0; i < 64; i++) {
        memset(recv_bufs[i], 0xEE, 2048);
        int64_t addr = (int64_t)(uintptr_t)recv_bufs[i];
        memcpy(recv_records + i * QN_RECV_RECORD, &addr, 8);
    }
    int32_t n = qn_gro_count(total, gso);
    CHECK(n == want_count, "total %d gso %d: %d datagrams, want %d", total, gso, n, want_count);
    int32_t written = qn_gro_split(data, total, gso, recv_records, 64, 2048, &from);
    CHECK(written == want_count, "total %d gso %d: wrote %d, want %d", total, gso, written, want_count);
    for (int32_t i = 0; i < written; i++) {
        int32_t length = rec_int(recv_records + i * QN_RECV_RECORD, 8);
        int32_t want = i == want_count - 1 ? want_last : gso;
        CHECK(length == want, "total %d gso %d: datagram %d has length %d, want %d", total, gso, i, length, want);
        for (int32_t b = 0; b < length; b++) {
            if (recv_bufs[i][b] != (uint8_t)i) {
                CHECK(0, "total %d gso %d: datagram %d byte %d is %d", total, gso, i, b, recv_bufs[i][b]);
                break;
            }
        }
        CHECK(length >= 2048 || recv_bufs[i][length] == 0xEE, "total %d gso %d: datagram %d overran its length", total,
              gso, i);
        CHECK(rec_int(recv_records + i * QN_RECV_RECORD, 16) == 4 && rec_int(recv_records + i * QN_RECV_RECORD, 20) == 4433,
              "datagram %d lost its source", i);
    }
    CHECK(written >= want_count || written == 64, "total %d gso %d: slots", total, gso);
}

static void gro_split(void) {
    split_case(1200 * 5, 1200, 5, 1200);   /* all full */
    split_case(1200 * 4 + 700, 1200, 5, 700); /* short last segment */
    split_case(1200, 1200, 1, 1200);       /* gso == length: no split */
    split_case(700, 1200, 1, 700);         /* shorter than gso: one datagram */
    split_case(900, 0, 1, 900);            /* no gso reported: one datagram */
    split_case(0, 0, 1, 0);                /* an empty datagram is a datagram */
    split_case(64 * 1000, 1000, 64, 1000); /* the kernel's most segments */
    split_case(64, 1, 64, 1);              /* one byte each */
}

/* Reads what `socket` has with the coalescing receive into the 64 slots; appends each datagram's length and tag to
 * `lengths`. Returns the datagrams read, and the most one call returned in `*widest`. */
static int32_t coalesced_all(int fd, uint8_t *scratch, int32_t slots, int32_t expected, int32_t *lengths, int32_t got,
                             int32_t *widest) {
    for (int attempt = 0; attempt < 200 && got < expected; attempt++) {
        for (int i = 0; i < 64; i++) {
            int64_t a = (int64_t)(uintptr_t)recv_bufs[i];
            memcpy(recv_records + i * QN_RECV_RECORD, &a, 8);
        }
        int64_t r = qn_udp_recv_coalesced(fd, (int64_t)(uintptr_t)recv_records, slots, 2048, (int64_t)(uintptr_t)scratch);
        CHECK(((r >> 32) & 0xffff) == 0, "coalesced receive refused with %lld", (long long)((r >> 32) & 0xffff));
        int32_t n = (int32_t)(r & 0xffffffff);
        if (n > *widest) *widest = n;
        for (int i = 0; i < n; i++) {
            int32_t length = rec_int(recv_records + i * QN_RECV_RECORD, 8);
            CHECK(recv_bufs[i][0] == recv_bufs[i][length - 1], "coalesced datagram %d is torn", got);
            lengths[got++] = length | ((int32_t)recv_bufs[i][0] << 24);
        }
        if (n == 0) usleep(1000);
    }
    return got;
}

/* The sender's UDP_SEGMENT groups reach a UDP_GRO receiver coalesced, and come out of the split as the datagrams that
 * were sent: every one byte-exact and in order, from the sender's address; a message that does not fit the free slots
 * stays queued. */
static void gro_round_trip(int family) {
    uint8_t *scratch = malloc(QN_GRO_SCRATCH_BYTES);
    memset(spec_b, 0, QN_SPEC_BYTES);
    if (family == 4) put_record(spec_b + QN_SPEC_BIND, 4, 0, 0, 0x7f000001);
    else put_record(spec_b + QN_SPEC_BIND, 6, 0, 0, 1);
    qn_spec_put((int64_t)(uintptr_t)spec_b, QN_SPEC_RCVBUF, 4 << 20);
    qn_spec_put((int64_t)(uintptr_t)spec_b, QN_SPEC_GRO_WANT, 1);
    int server = qn_udp_open((int64_t)(uintptr_t)spec_b);
    CHECK(server >= 0, "gro server open %d", server);
    CHECK(qn_spec_int((int64_t)(uintptr_t)spec_b, QN_SPEC_GRO_STATE) == 1, "family %d: UDP_GRO state %d, want on", family,
          qn_spec_int((int64_t)(uintptr_t)spec_b, QN_SPEC_GRO_STATE));
    int client = open_socket(spec_a, family, spec_b + QN_SPEC_LOCAL);
    /* 16 datagrams of 1200 plus a short last one, twice, as UDP_SEGMENT groups, then one lone datagram. */
    int32_t sent_total = 0, lengths[256], got = 0, widest = 0;
    int32_t want_lengths[256];
    for (int round = 0; round < 3; round++) {
        int32_t k = round == 2 ? 1 : 17;
        /* each datagram its own tag so a misplaced one shows */
        for (int32_t i = 0; i < k; i++) {
            int32_t length = (round < 2 && i == 16) ? 500 : 1200;
            fill(i, length);
            memset(send_base + (size_t)i * STRIDE, 'a' + ((sent_total + i) % 26), (size_t)length);
            want_lengths[sent_total + i] = length | ((int32_t)('a' + ((sent_total + i) % 26)) << 24);
        }
        int64_t r = qn_udp_send(client, family, QN_IO_SEGMENT, (int64_t)(uintptr_t)send_base, STRIDE,
                                (int64_t)(uintptr_t)send_records, k, QN_GSO_MAX_SEGMENTS);
        CHECK((r & 0xffffffff) == k && ((r >> 32) & 0xffff) == 0, "gro: sent %lld of %d", (long long)(r & 0xffffffff), k);
        sent_total += k;
        if (round == 0) {
            /* Too few free slots for the 17 the message holds: nothing is taken, the message stays. */
            usleep(20000);
            int32_t w = 0;
            int32_t before = got;
            got = coalesced_all(server, scratch, 8, 1, lengths, got, &w);
            CHECK(got == before, "gro: 8 free slots took %d datagrams of a 17-datagram message", got - before);
        }
    }
    got = coalesced_all(server, scratch, 64, sent_total, lengths, got, &widest);
    CHECK(got == sent_total, "family %d: received %d of %d", family, got, sent_total);
    for (int32_t i = 0; i < got && i < sent_total; i++)
        CHECK(lengths[i] == want_lengths[i], "family %d: datagram %d is length %d tag %c, want length %d tag %c", family,
              i, lengths[i] & 0xffffff, lengths[i] >> 24, want_lengths[i] & 0xffffff, want_lengths[i] >> 24);
    CHECK(memcmp(recv_records + 16, spec_a + QN_SPEC_LOCAL, 24) == 0, "family %d: source differs from the sender", family);
    CHECK(widest > 1, "family %d: no read held more than one datagram: UDP_GRO did not coalesce", family);
    printf("family %d: %d datagrams, widest single read %d\n", family, got, widest);
    qn_close(client);
    qn_close(server);
    free(scratch);
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

int main(void) {
    send_base = calloc(SLOTS, STRIDE);
    for (int i = 0; i < 64; i++) recv_bufs[i] = calloc(1, 2048);
    gro_split();
    poller();
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
        if ((caps & QN_CAP_GSO) && (caps & QN_CAP_GRO)) gro_round_trip(family);
    }
    printf(failures == 0 ? "qnet selftest: all passed\n" : "qnet selftest: %d failed\n", failures);
    return failures == 0 ? 0 : 1;
}
