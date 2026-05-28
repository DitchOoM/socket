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
#import <Security/Security.h>
#import <dispatch/dispatch.h>
#include <stdio.h>

#pragma mark - QUIC Connection Creation

/**
 * Create a QUIC connection.
 * Uses nw_parameters_create_quic() with TLS 1.3 (mandatory for QUIC).
 *
 * Certificate verification logic:
 *
 *   - **trusted_ca_der != nil** → custom verify_block evaluates the
 *     server's trust chain against the provided CA cert as the SOLE
 *     trust anchor, with `SecPolicyCreateSSL(true, NULL)` so the
 *     hostname check is relaxed (the test harness ships a cert for
 *     `quic.tech` and we connect to `127.0.0.1`; pinning to the CA
 *     while ignoring hostname is the standard test pattern). Uses
 *     `SecTrustEvaluateWithError` for real validation, NOT the
 *     pre-PR-#54 "complete(true) without evaluation" pattern that
 *     SIGABRT'd under Apple's TLS hardening (see Iter 1-5 in PR #54).
 *   - **trusted_ca_der == nil, verify_certs == true** → no verify_block
 *     installed; Network.framework's default trust evaluation runs
 *     (keychain anchors, hostname check enabled).
 *   - **trusted_ca_der == nil, verify_certs == false** → no verify_block
 *     installed either. The `verify_certs = false` knob is now a NO-OP
 *     on Apple. Documented in `WithQuicConnection.apple.kt`. The right
 *     way to talk to a self-signed cert on Apple is to pass
 *     `trustedCaDer` from Kotlin.
 *
 * @param host Hostname to connect to
 * @param port Port number
 * @param alpn_protocols NSArray of NSString ALPN identifiers (mandatory for QUIC)
 * @param verify_certs Whether to use system trust evaluation when no
 *                     custom CA is provided. Has no effect when
 *                     `trusted_ca_der` is non-nil.
 * @param trusted_ca_der DER-encoded CA certificate bytes for pinning;
 *                       nil to use system trust.
 * @param idle_timeout_seconds QUIC idle timeout (0 = no timeout)
 * @param connection_timeout_seconds TCP-level connection timeout
 * @return nw_connection_t or NULL on parameter error
 */
static inline nw_connection_t _Nullable nw_helper_create_quic_connection(
    const char * _Nonnull host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    NSNumber * _Nonnull verify_certs,
    NSData * _Nullable trusted_ca_der,
    int32_t idle_timeout_seconds,
    int32_t connection_timeout_seconds)
{
    fprintf(stderr, "[nw_quic create] entered host=%s port=%u alpn_count=%lu der_len=%lu\n",
            host, port,
            (unsigned long)alpn_protocols.count,
            trusted_ca_der ? (unsigned long)trusted_ca_der.length : 0UL);
    fflush(stderr);

    if (alpn_protocols.count == 0) return NULL;

    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);

    nw_endpoint_t endpoint = nw_endpoint_create_host(host, port_str);
    fprintf(stderr, "[nw_quic create] nw_endpoint_create_host=%p\n", endpoint); fflush(stderr);
    if (!endpoint) return NULL;

    // Block-captured copy — the block runs asynchronously on the
    // dispatch queue and `trusted_ca_der` (an Objective-C object) must
    // be retained for the block's lifetime. Block-capture handles
    // retain semantics correctly under ARC.
    NSData * _Nullable pinnedCaDer = trusted_ca_der;

    fprintf(stderr, "[nw_quic create] before nw_parameters_create_quic\n"); fflush(stderr);

    // Create QUIC parameters with TLS
    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull tls_options) {
            fprintf(stderr, "[nw_quic configure] entered tls_options=%p\n", tls_options); fflush(stderr);
            sec_protocol_options_t sec_options = nw_tls_copy_sec_protocol_options(tls_options);
            fprintf(stderr, "[nw_quic configure] sec_options=%p\n", sec_options); fflush(stderr);

            // Set ALPN protocols
            for (NSString *proto in alpn_protocols) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }
            fprintf(stderr, "[nw_quic configure] ALPN done\n"); fflush(stderr);

            // Certificate verification — proper pinning path or default system trust.
            fprintf(stderr, "[nw_quic configure] pinnedCaDer=%p\n", pinnedCaDer); fflush(stderr);
            if (pinnedCaDer != nil) {
                fprintf(stderr, "[nw_quic configure] about to install verify_block\n"); fflush(stderr);
                sec_protocol_options_set_verify_block(sec_options,
                    ^(sec_protocol_metadata_t metadata, sec_trust_t sec_trust, sec_protocol_verify_complete_t complete) {
                        // Iter 7 instrumentation: fprintf(stderr) is line-buffered AND
                        // captured by gradle stdout, so any line printed survives a
                        // subsequent crash. NARROWS the harness_handshake crash from
                        // "verify_block kills the process somewhere" to a specific line.
                        // Delete the fprintfs after the fix lands.
                        fprintf(stderr, "[nw_quic verify_block] entered\n"); fflush(stderr);

                        // sec_trust_t bridges to SecTrustRef (free-bridged Network.framework /
                        // Security types, per Apple's WWDC 2018 "Network Framework" session).
                        SecTrustRef trust_ref = sec_trust_copy_ref(sec_trust);
                        fprintf(stderr, "[nw_quic verify_block] sec_trust_copy_ref=%p\n", trust_ref); fflush(stderr);
                        if (!trust_ref) { complete(false); return; }

                        SecCertificateRef ca_cert = SecCertificateCreateWithData(
                            kCFAllocatorDefault, (__bridge CFDataRef)pinnedCaDer);
                        fprintf(stderr, "[nw_quic verify_block] SecCertificateCreateWithData=%p (der_len=%lu)\n",
                                ca_cert, (unsigned long)pinnedCaDer.length); fflush(stderr);
                        if (!ca_cert) {
                            CFRelease(trust_ref);
                            complete(false);
                            return;
                        }

                        CFMutableArrayRef anchors = CFArrayCreateMutable(kCFAllocatorDefault, 1, &kCFTypeArrayCallBacks);
                        CFArrayAppendValue(anchors, ca_cert);
                        OSStatus s1 = SecTrustSetAnchorCertificates(trust_ref, anchors);
                        fprintf(stderr, "[nw_quic verify_block] SetAnchorCertificates=%d\n", (int)s1); fflush(stderr);

                        OSStatus s2 = SecTrustSetAnchorCertificatesOnly(trust_ref, true);
                        fprintf(stderr, "[nw_quic verify_block] SetAnchorCertificatesOnly=%d\n", (int)s2); fflush(stderr);

                        // Relax hostname check — the harness cert has CN=quic.tech but the test
                        // connects to 127.0.0.1. Pinning to the CA chains is what gives us
                        // identity assurance; hostname binding is a separate concern.
                        SecPolicyRef policy = SecPolicyCreateSSL(true, NULL);
                        fprintf(stderr, "[nw_quic verify_block] SecPolicyCreateSSL=%p\n", policy); fflush(stderr);

                        OSStatus s3 = SecTrustSetPolicies(trust_ref, policy);
                        fprintf(stderr, "[nw_quic verify_block] SetPolicies=%d\n", (int)s3); fflush(stderr);

                        CFErrorRef error = NULL;
                        bool valid = SecTrustEvaluateWithError(trust_ref, &error);
                        fprintf(stderr, "[nw_quic verify_block] EvaluateWithError valid=%d error=%p\n", (int)valid, error); fflush(stderr);
                        if (error) CFRelease(error);

                        CFRelease(policy);
                        CFRelease(anchors);
                        CFRelease(ca_cert);
                        CFRelease(trust_ref);

                        fprintf(stderr, "[nw_quic verify_block] about to complete(%d)\n", (int)valid); fflush(stderr);
                        complete(valid);
                        fprintf(stderr, "[nw_quic verify_block] complete() returned\n"); fflush(stderr);
                    },
                    dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
                fprintf(stderr, "[nw_quic configure] verify_block installed\n"); fflush(stderr);
            }
            // Else: no verify_block installed. Default system trust handles
            // it. `verify_certs == false` without a pinned CA is now a no-op
            // on Apple; production callers who need to skip verification
            // should pass an explicit always-accept CA (not recommended).
            (void)verify_certs;
            fprintf(stderr, "[nw_quic configure] returning\n"); fflush(stderr);
        });

    fprintf(stderr, "[nw_quic create] nw_parameters_create_quic returned params=%p\n", params); fflush(stderr);
    if (!params) return NULL;

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
    nw_connection_set_queue(connection, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
    nw_connection_start(connection);
}

static inline void nw_helper_quic_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_cancel(connection);
}

static inline void nw_helper_quic_force_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_force_cancel(connection);
}
