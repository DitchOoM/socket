/*
 * qnet bulk send/receive between two hosts, to compare CPU per MiB across batching modes.
 *   cc -O2 -o qnet_bench socket-quic-quiche/src/jni/test/qnet_bench.c
 *   receiver: qnet_bench recv <port> <mode> [gro]      (mode 0 one-by-one, 1 recvmmsg)
 *   sender:   qnet_bench send <ipv4> <port> <mode> <MiB> (mode 0 one-by-one, 1 sendmmsg, 2 UDP_SEGMENT)
 * Each side prints datagrams, MiB, seconds, and user+system CPU microseconds per MiB. With `gro`, the receiver turns
 * UDP_GRO on and reads with recvmsg to count how many datagrams the kernel coalesced per read.
 */
#include "../qnet.c"

#include <arpa/inet.h>
#include <stdio.h>
#include <sys/resource.h>

#define DGRAM 1200
#define BATCH 64

static double cpu_us(void) {
    struct rusage u;
    getrusage(RUSAGE_SELF, &u);
    return (u.ru_utime.tv_sec + u.ru_stime.tv_sec) * 1e6 + u.ru_utime.tv_usec + u.ru_stime.tv_usec;
}

static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

static void put_record(uint8_t *r, int32_t family, int32_t port, uint64_t hi, uint64_t lo) {
    memcpy(r, &family, 4);
    memcpy(r + 4, &port, 4);
    memcpy(r + 8, &hi, 8);
    memcpy(r + 16, &lo, 8);
}

static int bench_send(const char *ip, int port, int mode, int mib) {
    uint8_t spec[QN_SPEC_BYTES] = {0};
    struct in_addr a;
    inet_pton(AF_INET, ip, &a);
    put_record(spec + QN_SPEC_BIND, 4, 0, 0, 0);
    put_record(spec + QN_SPEC_PEER, 4, port, 0, ntohl(a.s_addr));
    qn_spec_put((int64_t)(uintptr_t)spec, QN_SPEC_SNDBUF, 4 << 20);
    int fd = qn_udp_open((int64_t)(uintptr_t)spec);
    if (fd < 0) return printf("open refused %d\n", -fd), 1;
    static uint8_t base[BATCH * 1500];
    static uint8_t records[BATCH * QN_SEND_RECORD];
    memset(records, 0, sizeof records);
    for (int i = 0; i < BATCH; i++) {
        int32_t len = DGRAM;
        memcpy(records + i * QN_SEND_RECORD, &len, 4);
    }
    int64_t total = (int64_t)mib << 20, sent = 0, datagrams = 0, waits = 0;
    double c0 = cpu_us(), t0 = now_s();
    while (sent < total) {
        int64_t r = qn_udp_send(fd, 4, mode, (int64_t)(uintptr_t)base, 1500, (int64_t)(uintptr_t)records, BATCH,
                                QN_GSO_MAX_SEGMENTS);
        int32_t n = (int32_t)(r & 0xffffffff);
        datagrams += n;
        sent += (int64_t)n * DGRAM;
        if (((r >> 32) & 0xffff) != 0) {
            waits++;
            usleep(50);
        }
    }
    /* end marker */
    for (int i = 0; i < 50; i++) {
        int32_t len = 1;
        memcpy(records, &len, 4);
        qn_udp_send(fd, 4, QN_IO_ONE, (int64_t)(uintptr_t)base, 1500, (int64_t)(uintptr_t)records, 1, 1);
        usleep(1000);
    }
    double secs = now_s() - t0, cpu = cpu_us() - c0;
    printf("send mode %d: %lld datagrams, %.0f MiB in %.2f s (%.0f MiB/s), %.0f cpu-us/MiB, %lld full waits\n", mode,
           (long long)datagrams, sent / 1048576.0, secs, sent / 1048576.0 / secs, cpu / (sent / 1048576.0),
           (long long)waits);
    return 0;
}

static int bench_recv(int port, int mode, int gro) {
    uint8_t spec[QN_SPEC_BYTES] = {0};
    put_record(spec + QN_SPEC_BIND, 4, port, 0, 0);
    qn_spec_put((int64_t)(uintptr_t)spec, QN_SPEC_RCVBUF, 8 << 20);
    int fd = qn_udp_open((int64_t)(uintptr_t)spec);
    if (fd < 0) return printf("open refused %d\n", -fd), 1;
    int64_t poller = qn_poller_open(4);
    qn_poller_add(poller, fd, 1);
    static uint8_t bufs[BATCH][65536];
    static uint8_t recs[BATCH * QN_RECV_RECORD];
    int64_t bytes = 0, datagrams = 0, reads = 0;
    double c0 = 0, t0 = 0;
#ifdef UDP_GRO
    if (gro) {
        int on = 1;
        if (setsockopt(fd, SOL_UDP, UDP_GRO, &on, sizeof on) < 0) printf("UDP_GRO refused\n");
    }
#endif
    for (;;) {
        qn_poller_wait(poller, QN_WAIT_TIMED, 2000000000LL);
        if (gro) {
            /* recvmsg with the GRO cmsg: one read may carry many datagrams of gso_size each. */
            for (;;) {
                struct iovec iov = {bufs[0], sizeof bufs[0]};
                char control[64];
                struct msghdr h = {0};
                h.msg_iov = &iov;
                h.msg_iovlen = 1;
                h.msg_control = control;
                h.msg_controllen = sizeof control;
                ssize_t got = recvmsg(fd, &h, MSG_DONTWAIT);
                if (got < 0) break;
                if (t0 == 0) t0 = now_s(), c0 = cpu_us();
                if (got == 1) goto done;
                int seg = (int)got;
                for (struct cmsghdr *c = CMSG_FIRSTHDR(&h); c; c = CMSG_NXTHDR(&h, c)) {
                    if (c->cmsg_level == SOL_UDP && c->cmsg_type == UDP_GRO) {
                        uint16_t s;
                        memcpy(&s, CMSG_DATA(c), 2);
                        seg = s;
                    }
                }
                reads++;
                bytes += got;
                datagrams += (got + seg - 1) / seg;
            }
            continue;
        }
        for (;;) {
            for (int i = 0; i < BATCH; i++) {
                int64_t a = (int64_t)(uintptr_t)bufs[i];
                memcpy(recs + i * QN_RECV_RECORD, &a, 8);
            }
            int64_t r = qn_udp_recv(fd, mode, (int64_t)(uintptr_t)recs, BATCH, 1500);
            int32_t n = (int32_t)(r & 0xffffffff);
            if (n == 0) break;
            if (t0 == 0) t0 = now_s(), c0 = cpu_us();
            reads++;
            for (int i = 0; i < n; i++) {
                int32_t len;
                memcpy(&len, recs + i * QN_RECV_RECORD + 8, 4);
                if (len == 1) goto done;
                bytes += len;
                datagrams++;
            }
        }
    }
done: {
    double secs = now_s() - t0, cpu = cpu_us() - c0;
    printf("recv mode %d gro %d: %lld datagrams in %lld reads (%.1f per read), %.0f MiB in %.2f s, %.0f cpu-us/MiB\n",
           mode, gro, (long long)datagrams, (long long)reads, (double)datagrams / (reads ? reads : 1),
           bytes / 1048576.0, secs, cpu / (bytes / 1048576.0));
}
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 4 && strcmp(argv[1], "recv") == 0) return bench_recv(atoi(argv[2]), atoi(argv[3]), argc > 4);
    if (argc >= 6 && strcmp(argv[1], "send") == 0) return bench_send(argv[2], atoi(argv[3]), atoi(argv[4]), atoi(argv[5]));
    printf("usage: qnet_bench recv <port> <mode> [gro] | send <ipv4> <port> <mode> <MiB>\n");
    return 2;
}
