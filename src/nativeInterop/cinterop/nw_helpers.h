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

// Create a TCP connection (replaces Swift ClientSocketWrapper.init)
nw_connection_t _Nullable nw_helper_create_tcp_connection(
    const char * _Nonnull host,
    uint16_t port,
    NSNumber * _Nonnull use_tls,
    NSNumber * _Nonnull verify_certs,
    int32_t timeout_seconds);

// Create a WebSocket connection using nw_endpoint_create_url
nw_connection_t _Nullable nw_helper_create_ws_connection(
    const char * _Nonnull url,
    NSNumber * _Nonnull use_tls,
    NSNumber * _Nonnull verify_certs,
    NSNumber * _Nonnull auto_reply_ping,
    NSArray * _Nullable subprotocols,
    int32_t timeout_seconds);

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

// TCP send — wraps nw_connection_send with default message context
void nw_helper_send_tcp(
    nw_connection_t _Nonnull connection,
    NSData * _Nullable data,
    nw_helper_send_callback_t _Nonnull completion);

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

#endif /* NW_HELPERS_H */
