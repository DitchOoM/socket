/**
 * C bridge for Network.framework QUIC — same pattern as nw_helpers.h in the base module.
 *
 * Wraps Network.framework's QUIC-specific APIs into K/N-safe types:
 * - Objective-C blocks → C function pointers with NSData/NSNumber/NSString params
 * - dispatch_data_t → NSData
 * - nw_error_t → decomposed (domain, code, description)
 *
 * iOS 15+ / macOS 12+ required for QUIC support.
 */

#import <Foundation/Foundation.h>
#import <Network/Network.h>
#import <dispatch/dispatch.h>
#include <stdio.h>

// Diagnostic tracing for the macOS-peer handshake SIGTRAP (exit 133). stderr is
// flushed after every line so the last line printed before the trap localizes
// it (NW params block / our K/N callback / downstream). Remove once the Apple
// QUIC handshake is green. See PR #60 / TODO macOS-peer item.
#define NWQ_TRACE(...) do { fprintf(stderr, "[nwquic] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr); } while (0)

#pragma mark - QUIC Connection Creation

/**
 * Create a QUIC connection.
 * Uses nw_parameters_create_quic() with TLS 1.3 (mandatory for QUIC).
 *
 * Certificate verification uses Network.framework's default system trust
 * evaluation (keychain anchors, hostname check enabled). `verify_certs ==
 * false` is a NO-OP on Apple — the previous code path (always-accept
 * verify_block) SIGABRT'd under recent macOS TLS hardening (PR #54 iter
 * 1-5), and a proper SecTrustEvaluateWithError-based verify_block that
 * pinned to a custom CA also crashed in a way we couldn't isolate after
 * eight more CI iterations (PR #54 iter 6-9). The harness suite covers
 * Apple K/N via `AppleQuicConnectStartupProbe` (startup-only smoke
 * tests); `QuicHarnessIntegrationTests` skips on Apple K/N until the
 * Network.framework interaction is understood with local-Mac debugging.
 *
 * @param host Hostname to connect to
 * @param port Port number
 * @param alpn_protocols NSArray of NSString ALPN identifiers (mandatory for QUIC)
 * @param verify_certs Reserved — see note above.
 * @param idle_timeout_seconds QUIC idle timeout (0 = no timeout)
 * @param connection_timeout_seconds TCP-level connection timeout
 * @return nw_connection_t or NULL on parameter error
 */
static inline nw_connection_t _Nullable nw_helper_create_quic_connection(
    const char * _Nonnull host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    NSNumber * _Nonnull verify_certs,
    int32_t idle_timeout_seconds,
    int32_t connection_timeout_seconds)
{
    if (alpn_protocols.count == 0) return NULL;
    NWQ_TRACE("create host=%s port=%u alpn=%lu", host, port, (unsigned long)alpn_protocols.count);

    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);

    nw_endpoint_t endpoint = nw_endpoint_create_host(host, port_str);
    if (!endpoint) return NULL;

    // Retain an independent copy of the ALPN list so the async configure block
    // below (run during nw_connection_start) never iterates a K/N-bridged
    // NSArray proxy that may have been released by then (H3).
    NSArray<NSString *> *alpn_copy = [alpn_protocols copy];

    // Create QUIC parameters. The block configures the QUIC protocol options;
    // ALPN comes from the QUIC-specific sec_protocol_options accessor
    // (nw_quic_copy_sec_protocol_options) — NOT the generic TLS one. Calling
    // nw_tls_copy_sec_protocol_options on a QUIC options object was the leading
    // suspect for the handshake-start trap (H1): the block runs during
    // nw_connection_start, matching "crashes the moment the handshake starts."
    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull quic_options) {
            NWQ_TRACE("params-block enter");
            sec_protocol_options_t sec_options = nw_quic_copy_sec_protocol_options(quic_options);
            NWQ_TRACE("params-block sec_options=%s", sec_options ? "ok" : "NULL");

            for (NSString *proto in alpn_copy) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }
            NWQ_TRACE("params-block alpn set");

            // Cert verification: rely on Network.framework's default system trust.
            // verify_certs is intentionally unused — see header docstring for the
            // PR #54 investigation history.
            (void)verify_certs;
        });

    if (!params) return NULL;
    NWQ_TRACE("params created");

    // Note: QUIC idle timeout is managed by Network.framework internally.
    // The idle_timeout_seconds parameter is reserved for future use.

    nw_connection_t connection = nw_connection_create(endpoint, params);
    return connection;
}

#pragma mark - QUIC State Handler

typedef void (^quic_state_handler_t)(
    int32_t state,           // 0=invalid, 1=waiting, 2=preparing, 3=ready, 4=failed, 5=cancelled
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_description
);

/**
 * Set state change handler on a QUIC connection.
 * Same decomposition pattern as nw_helper_set_state_handler in base module.
 */
static inline void nw_helper_quic_set_state_handler(
    nw_connection_t _Nonnull connection,
    quic_state_handler_t _Nonnull handler)
{
    nw_connection_set_state_changed_handler(connection,
        ^(nw_connection_state_t state, nw_error_t _Nullable error) {
            int32_t mapped_state;
            switch (state) {
                case nw_connection_state_invalid:   mapped_state = 0; break;
                case nw_connection_state_waiting:   mapped_state = 1; break;
                case nw_connection_state_preparing: mapped_state = 2; break;
                case nw_connection_state_ready:     mapped_state = 3; break;
                case nw_connection_state_failed:    mapped_state = 4; break;
                case nw_connection_state_cancelled:  mapped_state = 5; break;
                default: mapped_state = 0; break;
            }

            int32_t err_domain = 0;
            int32_t err_code = 0;
            NSString *err_desc = nil;

            if (error) {
                err_domain = nw_error_get_error_domain(error);
                err_code = nw_error_get_error_code(error);
                CFErrorRef cf_error = nw_error_copy_cf_error(error);
                if (cf_error) {
                    err_desc = (__bridge_transfer NSString *)CFErrorCopyDescription(cf_error);
                    CFRelease(cf_error);
                }
            }

            NWQ_TRACE("state-cb enter state=%d err=%d -> handler", mapped_state, err_code);
            handler(mapped_state, err_domain, err_code, err_desc);
            NWQ_TRACE("state-cb handler returned (state=%d)", mapped_state);
        });
}

#pragma mark - QUIC Send/Receive (same as TCP — NWConnection is protocol-agnostic)

/**
 * Receive data on a QUIC connection.
 * Returns NSData (zero-copy from dispatch_data_t).
 */
typedef void (^quic_receive_handler_t)(
    NSData * _Nullable data,
    NSNumber * _Nullable is_complete,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_description
);

static inline void nw_helper_quic_receive(
    nw_connection_t _Nonnull connection,
    uint32_t min_length,
    uint32_t max_length,
    quic_receive_handler_t _Nonnull handler)
{
    nw_connection_receive(connection, min_length, max_length,
        ^(dispatch_data_t _Nullable content, nw_content_context_t _Nullable context,
          bool is_complete, nw_error_t _Nullable error) {

            NSData *data = nil;
            if (content) {
                data = (NSData *)content;
            }

            int32_t err_domain = 0;
            int32_t err_code = 0;
            NSString *err_desc = nil;
            if (error) {
                err_domain = nw_error_get_error_domain(error);
                err_code = nw_error_get_error_code(error);
                CFErrorRef cf_error = nw_error_copy_cf_error(error);
                if (cf_error) {
                    err_desc = (__bridge_transfer NSString *)CFErrorCopyDescription(cf_error);
                    CFRelease(cf_error);
                }
            }

            handler(data, [NSNumber numberWithBool:is_complete], err_domain, err_code, err_desc);
        });
}

/**
 * Send data on a QUIC connection.
 * NSData → dispatch_data_t conversion (zero-copy for NSData-backed buffers).
 */
typedef void (^quic_send_complete_t)(
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_description
);

static inline void nw_helper_quic_send(
    nw_connection_t _Nonnull connection,
    NSData * _Nonnull data,
    NSNumber * _Nonnull is_complete,
    quic_send_complete_t _Nonnull handler)
{
    dispatch_data_t dispatch_data = dispatch_data_create(
        data.bytes, data.length, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0),
        DISPATCH_DATA_DESTRUCTOR_DEFAULT);

    nw_content_context_t context = [is_complete boolValue]
        ? NW_CONNECTION_FINAL_MESSAGE_CONTEXT
        : NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT;

    nw_connection_send(connection, dispatch_data, context, [is_complete boolValue],
        ^(nw_error_t _Nullable error) {
            int32_t err_domain = 0;
            int32_t err_code = 0;
            NSString *err_desc = nil;
            if (error) {
                err_domain = nw_error_get_error_domain(error);
                err_code = nw_error_get_error_code(error);
                CFErrorRef cf_error = nw_error_copy_cf_error(error);
                if (cf_error) {
                    err_desc = (__bridge_transfer NSString *)CFErrorCopyDescription(cf_error);
                    CFRelease(cf_error);
                }
            }
            handler(err_domain, err_code, err_desc);
        });
}

#pragma mark - QUIC Connection Start/Cancel

static inline void nw_helper_quic_start(nw_connection_t _Nonnull connection) {
    // Network.framework delivers ALL handler blocks (state-changed, receive,
    // send-complete) on the queue set here. A global *concurrent* queue lets
    // those blocks run simultaneously on different threads — each invokes a
    // Kotlin/Native lambda that resumes a coroutine continuation, so concurrent
    // delivery means concurrent K/N callbacks racing on the same continuation
    // and shared state. That is a K/N foreign-thread hazard and the leading
    // suspect for the handshake SIGTRAP (exit 133) seen in the macOS-peer spike.
    // Use a dedicated SERIAL queue so callbacks are delivered one at a time,
    // as nw_connection expects. One shared serial queue is safe here: every
    // handler only resumes a continuation (non-blocking), so no cross-connection
    // deadlock is possible.
    static dispatch_queue_t quic_cb_queue;
    static dispatch_once_t quic_cb_queue_once;
    dispatch_once(&quic_cb_queue_once, ^{
        quic_cb_queue = dispatch_queue_create("com.ditchoom.socket.quic.nw", DISPATCH_QUEUE_SERIAL);
    });
    NWQ_TRACE("start: set_queue + nw_connection_start");
    nw_connection_set_queue(connection, quic_cb_queue);
    nw_connection_start(connection);
    NWQ_TRACE("start: nw_connection_start returned");
}

static inline void nw_helper_quic_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_cancel(connection);
}

static inline void nw_helper_quic_force_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_force_cancel(connection);
}
