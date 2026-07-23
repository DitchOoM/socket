#ifndef NW_UDP_HELPERS_H
#define NW_UDP_HELPERS_H

#include <Network/Network.h>
#include <Foundation/Foundation.h>
#include <stdint.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <net/if.h>
#include <ifaddrs.h>

// ============================================================
// Network.framework UDP datapath for the Apple quiche QUIC client.
//
// quiche owns the QUIC protocol (crypto, streams, loss recovery); NWConnection in *UDP mode* is just
// the datagram pipe — chosen over a raw POSIX socket so the client keeps Apple's NWPath awareness
// (Wi-Fi<->cellular handoff, the signal that drives connection migration) and a deterministic
// cancel(). Mirrors the K/N-safe-callback style of the base module's nw_helpers.h (no C value-type
// block params; only NSData / int32_t / NSString cross the cinterop boundary). The QUIC *server*
// datapath stays POSIX recvfrom/sendto (a server binds one port and demuxes many peers by connection
// id — inherently connectionless, which NWConnection's point-to-point model does not fit).
// ============================================================

// Create a plain-UDP nw_connection to host:port. NW_PARAMETERS_DISABLE_PROTOCOL on the TLS slot =
// no DTLS (quiche provides QUIC's own TLS). Returns NULL on failure.
nw_connection_t _Nullable nw_udp_create(
    const char * _Nonnull host,
    const char * _Nonnull port);

// State handler. state (nw_connection_state_t): 0=invalid,1=waiting,2=preparing,3=ready,4=failed,
// 5=cancelled. error_domain: 0=none,1=posix,2=dns,3=tls,4=unknown.
typedef void (^nw_udp_state_handler_t)(
    int32_t state,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_desc);

void nw_udp_set_state_handler(
    nw_connection_t _Nonnull conn,
    nw_udp_state_handler_t _Nonnull handler);

void nw_udp_start(nw_connection_t _Nonnull conn);
void nw_udp_cancel(nw_connection_t _Nonnull conn);

// Copy the connection's effective local / remote endpoint sockaddr bytes (BSD layout, sa_len set) into
// [out] (capacity [cap]). Returns the sockaddr length, or -1 if unavailable (no path yet / not an
// address endpoint / too small). Valid only once the connection is ready. quiche needs these for its
// recv_info.to/from + the quiche_connect local/peer addresses.
int32_t nw_udp_copy_local_sockaddr(nw_connection_t _Nonnull conn, void * _Nonnull out, int32_t cap);
int32_t nw_udp_copy_remote_sockaddr(nw_connection_t _Nonnull conn, void * _Nonnull out, int32_t cap);

// Receive ONE datagram (nw_connection_receive_message — preserves datagram boundaries). [content] is
// nil on connection close/cancel; error_code is 0 on success. The NSData bytes are only valid for the
// duration of the callback — the handler must copy them out synchronously.
typedef void (^nw_udp_receive_handler_t)(
    NSData * _Nullable content,
    int32_t error_domain,
    int32_t error_code);

void nw_udp_receive(
    nw_connection_t _Nonnull conn,
    nw_udp_receive_handler_t _Nonnull handler);

// Send ONE datagram ([bytes]/[len]). The bytes are copied into a dispatch_data buffer synchronously,
// so [bytes] need only be valid for the duration of the call. error_code is 0 on success.
typedef void (^nw_udp_send_handler_t)(
    int32_t error_domain,
    int32_t error_code);

void nw_udp_send(
    nw_connection_t _Nonnull conn,
    const void * _Nonnull bytes,
    int32_t len,
    nw_udp_send_handler_t _Nonnull handler);

// ============================================================
// IP multicast control plane (POSIX setsockopt) for the bind()/POSIX datapath.
//
// Uniform, struct-free-at-the-Kotlin-boundary helpers: the group is passed as a filled `sockaddr`
// (from AppleSocketAddress.writeSockaddr) and the C side pulls in_addr / in6_addr out by family. The
// interface is named by an already-network-order IPv4 s_addr ([iface_v4_be], `0` = INADDR_ANY, used for
// IPv4) OR an interface index ([ifindex], `0` = default, used for IPv6). Each returns `0` on success and
// `-1` on failure (errno set) — no multicast struct crosses the cinterop boundary.
// ============================================================

// IP_ADD_MEMBERSHIP / IPV6_JOIN_GROUP for [group] on the given interface.
int socket_mc_join(int fd, const struct sockaddr * _Nonnull group, uint32_t iface_v4_be, unsigned int ifindex);
// IP_DROP_MEMBERSHIP / IPV6_LEAVE_GROUP.
int socket_mc_leave(int fd, const struct sockaddr * _Nonnull group, uint32_t iface_v4_be, unsigned int ifindex);
// IP_MULTICAST_TTL / IPV6_MULTICAST_HOPS (outbound hop limit).
int socket_mc_set_ttl(int fd, int ipv6, int ttl);
// IP_MULTICAST_LOOP / IPV6_MULTICAST_LOOP.
int socket_mc_set_loop(int fd, int ipv6, int on);
// IP_MULTICAST_IF (in_addr) / IPV6_MULTICAST_IF (ifindex).
int socket_mc_set_if(int fd, int ipv6, uint32_t iface_v4_be, unsigned int ifindex);
// if_nametoindex — 0 on failure (no such interface).
unsigned int socket_if_index(const char * _Nonnull name);
// First AF_INET address (network-order s_addr) of the interface at [ifindex]; 0 if none / not found.
uint32_t socket_if_ipv4_be(unsigned int ifindex);

#endif /* NW_UDP_HELPERS_H */
