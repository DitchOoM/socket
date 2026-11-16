/*
 * qnet: readiness and UDP socket calls of the native stack. See qnet.h for the contract.
 *
 * Compiled into libquiche_jni (included at the end of quiche_jni.c) for the JVM and Android, and into the
 * Kotlin/Native cinterop of the same name. Not compiled on Windows: the JDK stack serves it.
 */
#ifndef _WIN32

#if defined(__APPLE__) && !defined(__APPLE_USE_RFC_3542)
#define __APPLE_USE_RFC_3542
#endif
#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE
#endif

#include "qnet.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <sys/event.h>
#define QN_KQUEUE 1
#else
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#define QN_EPOLL 1
#endif

#ifndef SOL_UDP
#define SOL_UDP 17
#endif
#ifndef UDP_SEGMENT
#define UDP_SEGMENT 103
#endif
#ifndef UDP_GRO
#define UDP_GRO 104
#endif

#define QN_MAX_BATCH 64
#define QN_GSO_MAX_SEGMENTS 64
#define QN_GSO_MAX_BYTES 65000

static int32_t qn_code(int e) {
    switch (e) {
    case EAGAIN:
#if EWOULDBLOCK != EAGAIN
    case EWOULDBLOCK:
#endif
        return QN_EAGAIN;
    case EMSGSIZE: return QN_EMSGSIZE;
    case ENETUNREACH: case ENETDOWN: return QN_ENETUNREACH;
    case EHOSTUNREACH: case EHOSTDOWN: return QN_EHOSTUNREACH;
    case ECONNREFUSED: return QN_ECONNREFUSED;
    case ENOBUFS: return QN_ENOBUFS;
    case EACCES: return QN_EACCES;
    case EPERM: return QN_EPERM;
    case EBADF: case ENOTSOCK: return QN_EBADF;
    case EIO: return QN_EIO;
    case EADDRINUSE: return QN_EADDRINUSE;
    case EADDRNOTAVAIL: return QN_EADDRNOTAVAIL;
    case EAFNOSUPPORT: return QN_EAFNOSUPPORT;
    case ENOMEM: return QN_ENOMEM;
    case EINVAL: return QN_EINVAL;
    case ENOPROTOOPT: return QN_ENOPROTOOPT;
    case ENOSYS: return QN_ENOSYS;
    default: return QN_OTHER;
    }
}

/* ---------------------------------------------------------------- addresses */

static inline uint8_t *qn_at(int64_t address) { return (uint8_t *)(uintptr_t)address; }

/* Record (family, port, hi, lo) -> sockaddr for a socket of family `sock_family` (an IPv4 target on an IPv6
 * socket becomes IPv4-mapped). Returns the length, or 0 for a record that names no address. */
static socklen_t qn_to_sockaddr(const uint8_t *record, int sock_family, struct sockaddr_storage *out) {
    int32_t family, port;
    uint64_t hi, lo;
    memcpy(&family, record, 4);
    memcpy(&port, record + 4, 4);
    memcpy(&hi, record + 8, 8);
    memcpy(&lo, record + 16, 8);
    memset(out, 0, sizeof(*out));
    if (family == 4 && sock_family == AF_INET) {
        struct sockaddr_in *a = (struct sockaddr_in *)out;
        a->sin_family = AF_INET;
        a->sin_port = htons((uint16_t)port);
        a->sin_addr.s_addr = htonl((uint32_t)lo);
#ifdef __APPLE__
        a->sin_len = sizeof(*a);
#endif
        return sizeof(*a);
    }
    if (family == 4 || family == 6) {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)out;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons((uint16_t)port);
        uint8_t *b = a->sin6_addr.s6_addr;
        if (family == 4) {
            b[10] = 0xff;
            b[11] = 0xff;
            for (int i = 0; i < 4; i++) b[12 + i] = (uint8_t)(lo >> (24 - 8 * i));
        } else {
            for (int i = 0; i < 8; i++) b[i] = (uint8_t)(hi >> (56 - 8 * i));
            for (int i = 0; i < 8; i++) b[8 + i] = (uint8_t)(lo >> (56 - 8 * i));
        }
#ifdef __APPLE__
        a->sin6_len = sizeof(*a);
#endif
        return sizeof(*a);
    }
    return 0;
}

static void qn_from_sockaddr(const struct sockaddr_storage *in, uint8_t *record) {
    int32_t family = 0, port = 0;
    uint64_t hi = 0, lo = 0;
    if (in->ss_family == AF_INET) {
        const struct sockaddr_in *a = (const struct sockaddr_in *)in;
        family = 4;
        port = ntohs(a->sin_port);
        lo = (uint32_t)ntohl(a->sin_addr.s_addr);
    } else if (in->ss_family == AF_INET6) {
        const struct sockaddr_in6 *a = (const struct sockaddr_in6 *)in;
        const uint8_t *b = a->sin6_addr.s6_addr;
        port = ntohs(a->sin6_port);
        if (IN6_IS_ADDR_V4MAPPED(&a->sin6_addr)) {
            family = 4;
            lo = ((uint64_t)b[12] << 24) | ((uint64_t)b[13] << 16) | ((uint64_t)b[14] << 8) | b[15];
        } else {
            family = 6;
            for (int i = 0; i < 8; i++) hi = (hi << 8) | b[i];
            for (int i = 0; i < 8; i++) lo = (lo << 8) | b[8 + i];
        }
    }
    memcpy(record, &family, 4);
    memcpy(record + 4, &port, 4);
    memcpy(record + 8, &hi, 8);
    memcpy(record + 16, &lo, 8);
}

#include "qnet_poll.c"
#include "qnet_udp.c"

#endif /* _WIN32 */
