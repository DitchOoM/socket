/*
 * qnet's own red tests, below any binding: build and run on each kernel with
 *   cc -O1 -Wall -o /tmp/qnet_selftest socket-quic-quiche/src/jni/test/qnet_selftest.c && /tmp/qnet_selftest
 * Every mode the kernel offers is driven: a batch refused part way reports exactly how many datagrams went, a
 * segmented batch arrives as its datagrams with their own lengths and bytes, and the poller wakes and reports.
 */
#include "../qnet.c"

#include <poll.h>
#ifdef __linux__
#include <linux/sock_diag.h>
#endif
#include <stdio.h>
#include <time.h>

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
/* Loopback delivery is not synchronous on every kernel (WSL2 lands a datagram about a millisecond after send), so the
 * test waits for what it expects to arrive, up to this long, rather than sleeping a fixed time. */
#define WAIT_MS 1000

static int64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Waits until what the kernel has queued for `fd` stops growing. A segmented message reaches a receiver in pieces while
 * the kernel is still delivering it, so a read that starts at the first piece sees the message split; the receive
 * queue's memory (SO_MEMINFO) holding steady for a few polls means the delivery is over. Without SO_MEMINFO the
 * caller's own wait for the expected datagrams is all there is. */
static void wait_queue_settled(int fd) {
#ifdef SO_MEMINFO
    uint32_t mem[SK_MEMINFO_VARS];
    uint32_t last = 0;
    int stable = 0;
    int64_t deadline = now_ms() + WAIT_MS;
    while (stable < 4 && now_ms() < deadline) {
        socklen_t len = sizeof mem;
        if (getsockopt(fd, SOL_SOCKET, SO_MEMINFO, mem, &len) != 0) return;
        stable = mem[SK_MEMINFO_RMEM_ALLOC] != 0 && mem[SK_MEMINFO_RMEM_ALLOC] == last ? stable + 1 : 0;
        last = mem[SK_MEMINFO_RMEM_ALLOC];
        usleep(1000);
    }
#else
    (void)fd;
#endif
}

/* Blocks until `fd` has `events` or WAIT_MS passes; returns whether it did. */
static int wait_fd(int fd, short events) {
    struct pollfd p = {fd, events, 0};
    return poll(&p, 1, WAIT_MS) > 0 && (p.revents & events);
}

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
    int fd = qn_udp_open_socket((int64_t)(uintptr_t)spec);
    if (fd >= 0) CHECK(qn_udp_set_dont_fragment(fd, family) == 0, "family %d: DF refused", family);
    return fd;
}

static void fill(int32_t i, int32_t length) {
    put_record(send_records + i * QN_SEND_RECORD + 8, 0, 0, 0, 0);
    memcpy(send_records + i * QN_SEND_RECORD, &length, 4);
    memset(send_base + (size_t)i * STRIDE, 'a' + i, (size_t)length);
}

static int32_t receive_all(int poller_fd_unused, int fd, int mode, int32_t expected, int32_t *lengths) {
    (void)poller_fd_unused;
    int32_t got = 0;
    int64_t deadline = now_ms() + WAIT_MS;
    while (got < expected && now_ms() < deadline) {
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
static int32_t coalesced_all(int fd, int32_t slots, int32_t expected, int32_t *lengths, int32_t got,
                             int32_t *widest) {
    int64_t deadline = now_ms() + WAIT_MS;
    while (got < expected && now_ms() < deadline) {
        for (int i = 0; i < 64; i++) {
            int64_t a = (int64_t)(uintptr_t)recv_bufs[i];
            memcpy(recv_records + i * QN_RECV_RECORD, &a, 8);
        }
        int64_t r = qn_udp_recv_coalesced(fd, (int64_t)(uintptr_t)recv_records, slots, 2048);
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
static int open_gro_server(int family, int want);
static void gro_round_trip(int family) {
    memset(spec_b, 0, QN_SPEC_BYTES);
    if (family == 4) put_record(spec_b + QN_SPEC_BIND, 4, 0, 0, 0x7f000001);
    else put_record(spec_b + QN_SPEC_BIND, 6, 0, 0, 1);
    qn_spec_put((int64_t)(uintptr_t)spec_b, QN_SPEC_RCVBUF, 4 << 20);
    int server = open_gro_server(family, 1);
    CHECK(server >= 0, "gro server open %d", server);
    CHECK(qn_udp_dont_fragment(server, family) == 1, "family %d: a UDP_GRO socket has no DF", family);
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
            CHECK(wait_fd(server, POLLIN), "gro: the 17-datagram message never arrived");
            wait_queue_settled(server);
            int32_t w = 0;
            int32_t before = got;
            got = coalesced_all(server, 8, 1, lengths, got, &w);
            CHECK(got == before, "gro: 8 free slots took %d datagrams of a 17-datagram message", got - before);
        }
    }
    got = coalesced_all(server, 64, sent_total, lengths, got, &widest);
    CHECK(got == sent_total, "family %d: received %d of %d", family, got, sent_total);
    for (int32_t i = 0; i < got && i < sent_total; i++)
        CHECK(lengths[i] == want_lengths[i], "family %d: datagram %d is length %d tag %c, want length %d tag %c", family,
              i, lengths[i] & 0xffffff, lengths[i] >> 24, want_lengths[i] & 0xffffff, want_lengths[i] >> 24);
    CHECK(memcmp(recv_records + 16, spec_a + QN_SPEC_LOCAL, 24) == 0, "family %d: source differs from the sender", family);
    CHECK(widest > 1, "family %d: no read held more than one datagram: UDP_GRO did not coalesce", family);
    printf("family %d: %d datagrams, widest single read %d\n", family, got, widest);
    qn_close(client);
    qn_close(server);
}

/* Sends `k` datagrams numbered from `seq` (tag = seq + i, 1200 bytes, the last `last` bytes) and records what must arrive. */
static int32_t send_run(int client, int family, int mode, int32_t k, int32_t seq, int32_t last, int32_t *want,
                        int32_t at) {
    for (int32_t i = 0; i < k; i++) {
        int32_t length = i == k - 1 ? last : 1200;
        fill(i, length);
        memset(send_base + (size_t)i * STRIDE, 'a' + ((seq + i) % 26), (size_t)length);
        want[at + i] = length | ((int32_t)('a' + ((seq + i) % 26)) << 24);
    }
    /* A send buffer still holding undelivered datagrams reports would-block: wait for it to drain and send the rest. */
    int32_t sent = 0;
    int64_t deadline = now_ms() + WAIT_MS;
    int64_t r = 0;
    while (sent < k) {
        r = qn_udp_send(client, family, mode, (int64_t)(uintptr_t)(send_base + (size_t)sent * STRIDE), STRIDE,
                        (int64_t)(uintptr_t)(send_records + (size_t)sent * QN_SEND_RECORD), k - sent, QN_GSO_MAX_SEGMENTS);
        sent += (int32_t)(r & 0xffffffff);
        int32_t code = (int32_t)((r >> 32) & 0xffff);
        if (sent == k) break;
        if (code != QN_EAGAIN || now_ms() >= deadline) break;
        wait_fd(client, POLLOUT);
    }
    CHECK(sent == k && ((r >> 32) & 0xffff) == 0, "run: sent %d of %d, code %lld", sent, k, (long long)((r >> 32) & 0xffff));
    return at + k;
}

static int open_gro_server(int family, int want) {
    memset(spec_b, 0, QN_SPEC_BYTES);
    if (family == 4) put_record(spec_b + QN_SPEC_BIND, 4, 0, 0, 0x7f000001);
    else put_record(spec_b + QN_SPEC_BIND, 6, 0, 0, 1);
    qn_spec_put((int64_t)(uintptr_t)spec_b, QN_SPEC_RCVBUF, 4 << 20);
    int fd = qn_udp_open_socket((int64_t)(uintptr_t)spec_b);
    if (fd >= 0 && want) CHECK(qn_udp_set_gro(fd) == 1, "family %d: UDP_GRO not on", family);
    if (fd >= 0) CHECK(qn_udp_set_dont_fragment(fd, family) == 0, "family %d: DF refused", family);
    return fd;
}

/* One recvmmsg batch that holds lone datagrams and coalesced messages together: everything arrives in order, byte-exact,
 * with the lone ones neither merged nor lost. */
static void gro_mixed(int family) {
    int server = open_gro_server(family, 1);
    CHECK(server >= 0, "mixed server open %d", server);
    int client = open_socket(spec_a, family, spec_b + QN_SPEC_LOCAL);
    int32_t want[256], lengths[256], at = 0, widest = 0, got = 0;
    int32_t plan[][3] = {{3, QN_IO_ONE, 700}, {10, QN_IO_SEGMENT, 400}, {2, QN_IO_ONE, 1200}, {5, QN_IO_SEGMENT, 900},
                         {1, QN_IO_ONE, 64},  {12, QN_IO_SEGMENT, 1200}, {4, QN_IO_ONE, 333}};
    for (unsigned p = 0; p < sizeof plan / sizeof plan[0]; p++) {
        at = send_run(client, family, plan[p][1], plan[p][0], at, plan[p][2], want, at);
    }
    got = coalesced_all(server, 64, at, lengths, got, &widest);
    CHECK(got == at, "family %d mixed: received %d of %d", family, got, at);
    for (int32_t i = 0; i < got && i < at; i++)
        CHECK(lengths[i] == want[i], "family %d mixed: datagram %d is length %d tag %c, want length %d tag %c", family, i,
              lengths[i] & 0xffffff, lengths[i] >> 24, want[i] & 0xffffff, want[i] >> 24);
    printf("family %d mixed: %d datagrams, widest single read %d\n", family, got, widest);
    qn_close(client);
    qn_close(server);
}

/* Reads until `expected` datagrams have arrived (waiting up to WAIT_MS for stragglers) and a call then returns nothing;
 * returns the receive calls that delivered something, plus the final empty one. */
static int64_t drain_calls(int fd, int coalesced, int32_t *datagrams, int32_t expected) {
    int64_t calls = 1, deadline = now_ms() + WAIT_MS;
    *datagrams = 0;
    for (;;) {
        for (int i = 0; i < 64; i++) {
            int64_t a = (int64_t)(uintptr_t)recv_bufs[i];
            memcpy(recv_records + i * QN_RECV_RECORD, &a, 8);
        }
        int64_t r = coalesced ? qn_udp_recv_coalesced(fd, (int64_t)(uintptr_t)recv_records, 64, 2048)
                              : qn_udp_recv(fd, QN_IO_MULTI, (int64_t)(uintptr_t)recv_records, 64, 2048);
        int32_t n = (int32_t)(r & 0xffffffff);
        if (n > 0) {
            calls++;
            *datagrams += n;
            continue;
        }
        if (*datagrams >= expected || now_ms() >= deadline) break;
        wait_fd(fd, POLLIN);
    }
    return calls;
}

/* Lone datagrams (no GSO from the sender) must cost a GRO socket no more receive syscalls than the per-datagram path. */
static void gro_lone_syscalls(int family) {
    int64_t calls[2];
    for (int coalesced = 0; coalesced <= 1; coalesced++) {
        int server = open_gro_server(family, coalesced);
        int client = open_socket(spec_a, family, spec_b + QN_SPEC_LOCAL);
        int32_t want[256];
        for (int round = 0; round < 4; round++) send_run(client, family, QN_IO_ONE, 16, round * 16, 1200, want, 0);
        int32_t datagrams;
        wait_fd(server, POLLIN);
        wait_queue_settled(server);
        calls[coalesced] = drain_calls(server, coalesced, &datagrams, 64);
        CHECK(datagrams == 64, "family %d lone (%s): received %d of 64", family, coalesced ? "gro" : "plain", datagrams);
        qn_close(client);
        qn_close(server);
    }
    printf("family %d: 64 lone datagrams cost %lld receive syscalls per-datagram, %lld with GRO on\n", family,
           (long long)calls[0], (long long)calls[1]);
    CHECK(calls[1] <= calls[0], "family %d: GRO on made %lld receive syscalls for lone datagrams, per-datagram %lld", family,
          (long long)calls[1], (long long)calls[0]);
}

#ifdef __linux__
/* Pages of [addr, addr + bytes) the kernel holds in memory (mincore), and the process's resident set in KiB. */
static int64_t resident_pages(int64_t addr, size_t bytes) {
    size_t pages = bytes / 4096;
    unsigned char *vec = malloc(pages);
    int64_t n = 0;
    if (mincore((void *)(uintptr_t)addr, bytes, vec) != 0) {
        free(vec);
        return -1;
    }
    for (size_t i = 0; i < pages; i++) n += vec[i] & 1;
    free(vec);
    return n;
}

static long process_rss_kb(void) {
    long size = 0, rss = 0;
    FILE *f = fopen("/proc/self/statm", "r");
    if (f == NULL) return -1;
    if (fscanf(f, "%ld %ld", &size, &rss) != 2) rss = -1;
    fclose(f);
    return rss * 4;
}

/* The receive scratch costs what the kernel writes into it: a page or so per lone datagram's buffer, not its 4 MiB. */
static void gro_resident(void) {
    int server = open_gro_server(4, 1);
    int client = open_socket(spec_a, 4, spec_b + QN_SPEC_LOCAL);
    int64_t scratch = qn_udp_gro_scratch(server);
    CHECK(scratch != 0, "a GRO socket has a scratch");
    int64_t total = QN_GRO_SCRATCH_BYTES / 4096;
    int64_t a = resident_pages(scratch, QN_GRO_SCRATCH_BYTES);
    long rss_a = process_rss_kb();
    int32_t want[256], datagrams, widest = 0, lengths[256];
    for (int round = 0; round < 313; round++) {
        send_run(client, 4, QN_IO_ONE, 32, 0, 1200, want, 0);
        drain_calls(server, 1, &datagrams, 32);
        CHECK(datagrams == 32, "lone round %d: %d of 32", round, datagrams);
    }
    int64_t b = resident_pages(scratch, QN_GRO_SCRATCH_BYTES);
    long rss_b = process_rss_kb();
    for (int g = 0; g < 4; g++) send_run(client, 4, QN_IO_SEGMENT, 17, 0, 500, want, 0);
    int32_t got = coalesced_all(server, 64, 68, lengths, 0, &widest);
    int32_t more;
    drain_calls(server, 1, &more, 0);
    CHECK(got + more == 68, "coalesced burst delivered %d of 68 datagrams", got + more);
    int64_t c = resident_pages(scratch, QN_GRO_SCRATCH_BYTES);
    long rss_c = process_rss_kb();
    printf("scratch %lld pages virtual: resident %lld after open, %lld after 10016 lone datagrams, %lld after a coalesced burst\n",
           (long long)total, (long long)a, (long long)b, (long long)c);
    printf("process RSS KiB: %ld after open, %ld after 10016 lone datagrams, %ld after a coalesced burst\n", rss_a, rss_b,
           rss_c);
    CHECK(a >= 0 && a <= 4, "open touched %lld scratch pages", (long long)a);
    CHECK(b > 0 && b <= total / 8, "10016 lone datagrams left %lld of %lld scratch pages resident", (long long)b,
          (long long)total);
    /* A message of 32 x 1200 bytes passes the release bound: once delivered, its pages (and the lone datagram's page
     * before it) are given back. */
    send_run(client, 4, QN_IO_SEGMENT, 32, 0, 1200, want, 0);
    wait_fd(server, POLLIN);
    wait_queue_settled(server);
    drain_calls(server, 1, &datagrams, 32);
    CHECK(datagrams == 32, "big message delivered %d of 32 datagrams", datagrams);
    int64_t first = resident_pages(scratch + QN_GRO_HEADER_BYTES, QN_GRO_MESSAGE_BYTES);
    printf("a delivered 38400-byte message left %lld of its 16 pages resident\n", (long long)first);
    CHECK(first == 0, "a delivered big message kept %lld pages", (long long)first);
    (void)got;
    qn_close(client);
    qn_close(server);
    CHECK(qn_udp_gro_scratch(server) == 0, "closing the socket unmaps its scratch");
}
#endif

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

/* The offsets this file and every binding read the spec block by are the ones the library was built with. */
static void spec_layout(void) {
    uint32_t h = QN_SPEC_BYTES;
    const int32_t fields[] = {QN_SPEC_BIND,        QN_SPEC_PEER,        QN_SPEC_RCVBUF, QN_SPEC_SNDBUF,
                              QN_SPEC_GRANTED_RCV, QN_SPEC_GRANTED_SND, QN_SPEC_LOCAL};
    for (size_t i = 0; i < sizeof fields / sizeof fields[0]; i++) h = h * 31u + (uint32_t)fields[i];
    CHECK(qn_spec_layout() == (int32_t)h, "spec layout %d, the offsets fold to %d", qn_spec_layout(), (int32_t)h);
    /* every field sits inside the block, in order, without overlap */
    CHECK(QN_SPEC_PEER == QN_SPEC_BIND + 24 && QN_SPEC_RCVBUF == QN_SPEC_PEER + 24 && QN_SPEC_SNDBUF == QN_SPEC_RCVBUF + 4 &&
              QN_SPEC_GRANTED_RCV == QN_SPEC_SNDBUF + 4 && QN_SPEC_GRANTED_SND == QN_SPEC_GRANTED_RCV + 4 &&
              QN_SPEC_LOCAL == QN_SPEC_GRANTED_SND + 4 && QN_SPEC_BYTES == QN_SPEC_LOCAL + 24,
          "spec fields overlap or leave a gap");
}

int main(void) {
    spec_layout();
    send_base = calloc(SLOTS, STRIDE);
    for (int i = 0; i < 64; i++) recv_bufs[i] = calloc(1, 2048);
    gro_split();
    poller();
    dont_fragment();
    int caps = 0;
    int probe = open_socket(spec_a, 6, NULL);
    caps = qn_udp_capabilities(probe);
    qn_close(probe);
    caps &= (1 << QN_CAP_SEGMENTS_SHIFT) - 1;
    printf("capabilities: multi=%d gso=%d gro=%d\n", !!(caps & QN_CAP_MULTI), !!(caps & QN_CAP_GSO),
           !!(caps & QN_CAP_GRO));
    for (int family = 4; family <= 6; family += 2) {
        batch_round_trip(family, QN_IO_ONE, 1);
        if (caps & QN_CAP_MULTI) batch_round_trip(family, QN_IO_MULTI, 1);
        if (caps & QN_CAP_GSO) batch_round_trip(family, QN_IO_SEGMENT, QN_GSO_MAX_SEGMENTS);
        if ((caps & QN_CAP_GSO) && (caps & QN_CAP_GRO)) {
            gro_round_trip(family);
            gro_mixed(family);
            gro_lone_syscalls(family);
#ifdef __linux__
            if (family == 4) gro_resident();
#endif
        }
    }
    printf(failures == 0 ? "qnet selftest: all passed\n" : "qnet selftest: %d failed\n", failures);
    return failures == 0 ? 0 : 1;
}
