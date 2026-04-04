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

#pragma mark - QUIC Connection Creation

/**
 * Create a QUIC connection.
 * Uses nw_parameters_create_quic() with TLS 1.3 (mandatory for QUIC).
 *
 * @param host Hostname to connect to
 * @param port Port number
 * @param alpn_protocols NSArray of NSString ALPN identifiers (mandatory for QUIC)
 * @param verify_certs Whether to verify TLS certificates
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

    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);

    nw_endpoint_t endpoint = nw_endpoint_create_host(host, port_str);
    if (!endpoint) return NULL;

    // Create QUIC parameters with TLS
    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull tls_options) {
            sec_protocol_options_t sec_options = nw_tls_copy_sec_protocol_options(tls_options);

            // Set ALPN protocols
            for (NSString *proto in alpn_protocols) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }

            // Certificate verification
            if (![verify_certs boolValue]) {
                sec_protocol_options_set_verify_block(sec_options,
                    ^(sec_protocol_metadata_t metadata, sec_trust_t trust, sec_protocol_verify_complete_t complete) {
                        complete(true);
                    },
                    dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
            }
        });

    if (!params) return NULL;

    // Set idle timeout
    if (idle_timeout_seconds > 0) {
        nw_protocol_stack_t stack = nw_parameters_copy_default_protocol_stack(params);
        nw_protocol_options_t quic_options = nw_protocol_stack_copy_transport_protocol(stack);
        if (quic_options) {
            nw_quic_set_idle_timeout(quic_options, (uint32_t)idle_timeout_seconds);
        }
    }

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

            handler(mapped_state, err_domain, err_code, err_desc);
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
    nw_connection_start(connection, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
}

static inline void nw_helper_quic_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_cancel(connection);
}

static inline void nw_helper_quic_force_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_force_cancel(connection);
}
