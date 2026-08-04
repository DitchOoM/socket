#ifndef DITCHOOM_NETLINK_HELPERS_H
#define DITCHOOM_NETLINK_HELPERS_H

// Netlink cinterop surface for :network-monitor's LinuxNetworkMonitor — DELIBERATELY MINIMAL.
//
// This header exists so the Netlink.def can declare `linux/netlink.h` + `linux/rtnetlink.h` and NOTHING
// ELSE that :socket's LinuxSockets.def also declares. Two cinterops on one classpath that declare the
// same C header do not compose: :socket's own netlink constants go unresolved (see the measured probe
// results in network-monitor/build.gradle.kts). :socket dropped both netlink headers when
// LinuxNetworkMonitor moved here, so this module is now their sole declarer and there is no overlap.
//
// Everything else the monitor needs — socket(2)/recv(2)/setsockopt(2), getifaddrs(3), if_nametoindex(3),
// IFF_UP — comes from Kotlin/Native's own `platform.posix` / `platform.linux` klibs, which are not
// cinterops of ours and cannot collide.
//
// <sys/socket.h> is included below only to type the bind shim; it is excluded from binding generation
// by the def's headerFilter, so no sys/socket.h declaration is exported from this package.

#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>

// bind(2) for a netlink socket.
//
// glibc declares bind()'s address parameter as the transparent union __SOCKADDR_ARG, which
// Kotlin/Native cannot pass a `struct sockaddr_nl *` through. Taking the concrete netlink address type
// here means the cast happens in C, where it is well-defined, and the Kotlin caller never has to
// reinterpret a pointer. `addrlen` is implied by the type, so a caller cannot get it wrong either.
//
// Returns 0 on success, -1 with errno set on failure (bind(2)'s contract, unchanged).
static inline int nm_netlink_bind(int fd, const struct sockaddr_nl *addr) {
    return bind(fd, (const struct sockaddr *)addr, sizeof(struct sockaddr_nl));
}

#endif // DITCHOOM_NETLINK_HELPERS_H
