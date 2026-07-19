#ifndef NW_HELPERS_H
#define NW_HELPERS_H

#include <Network/Network.h>
#include <Foundation/Foundation.h>

// ============================================================
// TCP receive/send helpers (dispatch_data_t <-> NSData bridge)
// ============================================================

// Callback type with NSData instead of dispatch_data_t.
// Uses NSNumber for is_complete because Kotlin/Native cinterop
// can't bridge ObjC blocks with C value-type (bool) parameters.
typedef void (^nw_receive_nsdata_handler_t)(
    NSData * _Nullable content,
    nw_content_context_t _Nullable context,
    NSNumber * _Nonnull is_complete,
    nw_error_t _Nullable error);

// Wraps nw_connection_receive — bridges dispatch_data_t -> NSData in callback
void nw_helper_receive(
    nw_connection_t _Nonnull connection,
    uint32_t minimum_incomplete_length,
    uint32_t maximum_length,
    nw_receive_nsdata_handler_t _Nonnull handler);

// Wraps nw_connection_receive_message — same bridge
void nw_helper_receive_message(
    nw_connection_t _Nonnull connection,
    nw_receive_nsdata_handler_t _Nonnull handler);

// Send callback — uses NSError* (reference type only, K/N safe)
typedef void (^nw_helper_send_callback_t)(NSError * _Nullable error);

// Wraps nw_connection_send — bridges NSData -> dispatch_data_t
void nw_helper_send(
    nw_connection_t _Nonnull connection,
    NSData * _Nullable data,
    nw_content_context_t _Nonnull context,
    bool is_complete,
    nw_helper_send_callback_t _Nonnull completion);

// ============================================================
// Connection creation
// ============================================================

// Create a TCP connection (replaces Swift ClientSocketWrapper.init).
// no_delay maps to nw_tcp_options_set_no_delay (disables Nagle); pass false to keep
// the platform default. Without it, sequential request/response messaging stalls on
// Nagle + delayed-ACK (observed as ~12-35ms per WebSocket echo round-trip vs 1-8ms
// on the Linux/JVM paths, which honor TransportConfig.io.tcpNoDelay).
nw_connection_t _Nullable nw_helper_create_tcp_connection(
    const char * _Nonnull host,
    uint16_t port,
    NSNumber * _Nonnull use_tls,
    NSNumber * _Nonnull verify_certs,
    int32_t timeout_seconds,
    NSNumber * _Nonnull no_delay);

// Create a WebSocket connection using nw_endpoint_create_url
nw_connection_t _Nullable nw_helper_create_ws_connection(
    const char * _Nonnull url,
    NSNumber * _Nonnull use_tls,
    NSNumber * _Nonnull verify_certs,
    NSNumber * _Nonnull auto_reply_ping,
    NSArray * _Nullable subprotocols,
    int32_t timeout_seconds);

// ============================================================
// Socket error type constants (error_domain values)
// ============================================================

static const int32_t SocketErrorTypeNone  = 0;
static const int32_t SocketErrorTypePosix = 1;
static const int32_t SocketErrorTypeDns   = 2;
static const int32_t SocketErrorTypeTls   = 3;

// ============================================================
// Connection state handler + lifecycle
// ============================================================

// State handler callback.
// Connection state (nw_connection_state_t): 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
// error_domain: 0=none, 1=posix, 2=dns, 3=tls
typedef void (^nw_helper_state_handler_t)(
    int32_t state,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_desc);

void nw_helper_set_state_handler(
    nw_connection_t _Nonnull conn,
    nw_helper_state_handler_t _Nonnull handler);

void nw_helper_start(nw_connection_t _Nonnull conn);
void nw_helper_cancel(nw_connection_t _Nonnull conn);
void nw_helper_force_cancel(nw_connection_t _Nonnull conn);

// ============================================================
// Connection info
// ============================================================

int32_t nw_helper_local_port(nw_connection_t _Nonnull conn);
int32_t nw_helper_remote_port(nw_connection_t _Nonnull conn);

// ============================================================
// TCP-specific receive/send (K/N-safe callbacks only)
// ============================================================

// TCP receive callback — K/N-safe types only, no nw_content_context_t or nw_error_t.
// error_domain: 0=none, 1=posix, 2=dns, 3=tls, 4=unknown
typedef void (^nw_helper_tcp_receive_handler_t)(
    NSData * _Nullable content,
    NSNumber * _Nonnull is_complete,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_desc);

// TCP receive — wraps nw_connection_receive with K/N-safe callback
void nw_helper_tcp_receive(
    nw_connection_t _Nonnull connection,
    uint32_t minimum_incomplete_length,
    uint32_t maximum_length,
    nw_helper_tcp_receive_handler_t _Nonnull handler);

// TCP send callback — K/N-safe types, same error decomposition as receive.
// error_domain: 0=none (success), 1=posix, 2=dns, 3=tls, 4=unknown
typedef void (^nw_helper_tcp_send_handler_t)(
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_desc);

// TCP send — wraps nw_connection_send with default message context
void nw_helper_send_tcp(
    nw_connection_t _Nonnull connection,
    NSData * _Nullable data,
    nw_helper_tcp_send_handler_t _Nonnull completion);

// ============================================================
// Server listener
// ============================================================

// Listener callback types
typedef void (^nw_helper_listener_new_connection_t)(nw_connection_t _Nonnull connection);
typedef void (^nw_helper_listener_state_handler_t)(
    int32_t state,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_desc);

nw_listener_t _Nullable nw_helper_create_listener(int32_t port, int32_t backlog);

void nw_helper_listener_set_new_connection_handler(
    nw_listener_t _Nonnull listener,
    nw_helper_listener_new_connection_t _Nonnull handler);

void nw_helper_listener_set_state_handler(
    nw_listener_t _Nonnull listener,
    nw_helper_listener_state_handler_t _Nonnull handler);

void nw_helper_listener_start(nw_listener_t _Nonnull listener);
void nw_helper_listener_cancel(nw_listener_t _Nonnull listener);
int32_t nw_helper_listener_port(nw_listener_t _Nonnull listener);

// ============================================================
// WebSocket send/receive
// ============================================================

// WebSocket-aware receive callback — all NW types resolved in C.
// Only K/N-safe types cross the boundary: NSData, int32_t, NSNumber, NSError.
typedef void (^nw_ws_receive_handler_t)(
    NSData * _Nullable content,
    int32_t opcode,
    int32_t close_code,
    NSNumber * _Nonnull is_complete,
    NSError * _Nullable error);

// Wraps nw_connection_receive_message for WebSocket —
// extracts opcode and close_code from context metadata in C.
void nw_helper_ws_receive_message(
    nw_connection_t _Nonnull connection,
    nw_ws_receive_handler_t _Nonnull handler);

// Send completion callback
typedef void (^nw_helper_send_completion_t)(NSError * _Nullable error);

// WebSocket-aware async send — creates WS metadata + context in C
void nw_helper_ws_send(
    nw_connection_t _Nonnull connection,
    NSData * _Nullable data,
    int32_t opcode,
    int32_t close_code,
    nw_helper_send_completion_t _Nonnull completion);

// ============================================================
// Port availability check
// ============================================================

NSNumber * _Nonnull nw_helper_is_port_available(int32_t port);

// ============================================================
// Network path monitor
// ============================================================

// Path update handler — receives nw_path_status_t plus the path's primary-interface identity.
// status: 0=invalid, 1=satisfied, 2=unsatisfied, 3=requiresConnection
// interface_type (nw_interface_type_t of the first/primary interface): 0=other, 1=wifi, 2=cellular,
//   3=wired, 4=loopback; -1 when the path has no interface.
// interface_index: OS interface index of the primary interface; 0 when the path has no interface.
// interface_name: BSD name of the primary interface (e.g. "en0", "utun3"); nil when none.
// uses_interface_types: bitmask of nw_path_uses_interface_type over the whole path —
//   1=wifi, 2=cellular, 4=wired (identifies what a VPN tunnels over).
typedef void (^nw_helper_path_update_handler_t)(
    int32_t status,
    int32_t interface_type,
    uint32_t interface_index,
    NSString * _Nullable interface_name,
    int32_t uses_interface_types);

nw_path_monitor_t _Nonnull nw_helper_create_path_monitor(void);

void nw_helper_path_monitor_set_update_handler(
    nw_path_monitor_t _Nonnull monitor,
    nw_helper_path_update_handler_t _Nonnull handler);

void nw_helper_path_monitor_start(nw_path_monitor_t _Nonnull monitor);
void nw_helper_path_monitor_cancel(nw_path_monitor_t _Nonnull monitor);

// ============================================================
// Network interface enumeration (getifaddrs) — ICE / WebRTC host candidates
// ============================================================

// Per-address callback, invoked synchronously once per getifaddrs record:
//   name: BSD interface name (e.g. "en0"); index: OS interface index;
//   is_up / is_loopback: interface flags as 0/1; address: numeric IP literal
//   (NI_NUMERICHOST), or nil when the record carries no address.
// Uses NSString*/int32_t params (K/N-safe, same as the path handler) because
// getifaddrs / struct ifaddrs are NOT exposed by platform.posix on Apple K/N —
// the whole scan therefore lives in C and only scalars/strings cross to Kotlin.
typedef void (^nw_helper_iface_block)(
    NSString * _Nullable name,
    uint32_t index,
    int32_t is_up,
    int32_t is_loopback,
    NSString * _Nullable address);

// Enumerates every local interface address via getifaddrs, invoking [cb] once per
// (interface, address) record. A no-op if getifaddrs fails.
void nw_helper_enumerate_interfaces(nw_helper_iface_block _Nonnull cb);

#endif /* NW_HELPERS_H */
