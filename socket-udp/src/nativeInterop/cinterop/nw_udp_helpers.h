#ifndef NW_UDP_HELPERS_H
#define NW_UDP_HELPERS_H

#include <Network/Network.h>
#include <Foundation/Foundation.h>

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

#endif /* NW_UDP_HELPERS_H */
