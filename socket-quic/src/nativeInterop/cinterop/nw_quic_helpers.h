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

#pragma mark - QUIC Connection Creation

/**
 * Create a QUIC connection.
 * Uses nw_parameters_create_quic() with TLS 1.3 (mandatory for QUIC).
 *
 * The QUIC options block above takes the QUIC protocol options object;
 * ALPN must be set via nw_quic_copy_sec_protocol_options (NOT the generic
 * nw_tls_copy_*, which on a QUIC options object produced the handshake-start
 * SIGTRAP fixed in PR #60).
 *
 * Certificate verification (issue #81):
 *   - **trusted_ca_ders non-empty** → install a verify_block that evaluates the
 *     peer chain against the supplied DER-encoded CAs as the SOLE trust anchors
 *     (`SecTrustSetAnchorCertificatesOnly`), via `SecTrustEvaluateWithError`.
 *     Network.framework's own SSL policy (hostname + serverAuth EKU) is kept,
 *     so the hostname must still match a SAN. Pinning to custom anchors is
 *     CT-exempt, so a private-CA / non-CT-logged harness leaf is accepted
 *     without the errSSLBadCert (-9808) the default QUIC trust path returns.
 *     This is real chain validation — NOT an always-accept bypass — and it is
 *     what lets `QuicHarnessIntegrationTests` run (not skip) on Apple K/N.
 *   - **trusted_ca_ders nil/empty** → no verify_block; Network.framework's
 *     default system trust evaluation runs (keychain anchors, hostname check).
 *     This is the production path for real, CT-logged server certs.
 *
 * The verify_block MUST be attached to the QUIC sec_protocol_options obtained
 * via nw_quic_copy_sec_protocol_options (NOT the generic nw_tls_copy_*). PR #54
 * attached its verify_block to nw_tls_copy_sec_protocol_options applied to a
 * QUIC options object — the same wrong-accessor defect PR #60 fixed for ALPN —
 * which is what actually SIGABRT'd, not "macOS TLS hardening" as first believed.
 *
 * @param host Hostname to connect to
 * @param port Port number
 * @param alpn_protocols NSArray of NSString ALPN identifiers (mandatory for QUIC)
 * @param verify_certs Reserved for parity with the quiche path; when
 *                     trusted_ca_ders is empty, NW default trust always runs.
 * @param trusted_ca_ders NSArray of DER-encoded CA certs (NSData) to pin as the
 *                        sole anchors, or nil/empty to use NW's default trust.
 * @param idle_timeout_seconds QUIC idle timeout (0 = no timeout)
 * @param connection_timeout_seconds TCP-level connection timeout
 * @return nw_connection_t or NULL on parameter error
 */
static inline nw_connection_t _Nullable nw_helper_create_quic_connection(
    const char * _Nonnull host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    NSNumber * _Nonnull verify_certs,
    NSArray<NSData *> * _Nullable trusted_ca_ders,
    int32_t idle_timeout_seconds,
    int32_t connection_timeout_seconds)
{
    if (alpn_protocols.count == 0) return NULL;

    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);

    nw_endpoint_t endpoint = nw_endpoint_create_host(host, port_str);
    if (!endpoint) return NULL;

    // Retain an independent copy of the ALPN list so the async configure block
    // below (run during nw_connection_start) never iterates a K/N-bridged
    // NSArray proxy that may have been released by then (H3).
    NSArray<NSString *> *alpn_copy = [alpn_protocols copy];

    (void)verify_certs; // see docstring — meaningful switch is trusted_ca_ders

    // Block-captured retained copy: the verify_block runs asynchronously during
    // the handshake, so the DER bytes must outlive this function. Block-capture
    // retains the array (and its elements) when the block is copied to the heap,
    // independent of the K/N-bridged original (same H3 guard as alpn_copy above).
    NSArray<NSData *> * _Nullable pinned_ca_ders =
        (trusted_ca_ders.count > 0) ? [trusted_ca_ders copy] : nil;

    // Create QUIC parameters. The block configures the QUIC protocol options;
    // ALPN and any verify_block come from the QUIC-specific sec_protocol_options
    // accessor (nw_quic_copy_sec_protocol_options) — NOT the generic TLS one.
    // Attaching to nw_tls_copy_sec_protocol_options on a QUIC options object is
    // the wrong-accessor defect behind both the PR #60 ALPN SIGTRAP and the
    // PR #54 verify_block SIGABRT (see docstring).
    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull quic_options) {
            sec_protocol_options_t sec_options = nw_quic_copy_sec_protocol_options(quic_options);

            for (NSString *proto in alpn_copy) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }

            // CA-pinning verify_block (issue #81). Only installed when CAs are
            // supplied; the production default (nil) keeps NW's default system
            // trust. The block does REAL chain validation against the pinned CAs
            // as the sole anchors — pinning makes the chain CT-exempt, which is
            // what clears the -9808 the default QUIC trust path returns for the
            // private-CA harness leaf. Delivered on a dedicated serial queue;
            // the block is pure C / Security-framework and captures no K/N state.
            if (pinned_ca_ders != nil) {
                static dispatch_queue_t verify_queue;
                static dispatch_once_t verify_queue_once;
                dispatch_once(&verify_queue_once, ^{
                    verify_queue = dispatch_queue_create("com.ditchoom.socket.quic.verify", DISPATCH_QUEUE_SERIAL);
                });
                sec_protocol_options_set_verify_block(sec_options,
                    ^(sec_protocol_metadata_t metadata, sec_trust_t sec_trust, sec_protocol_verify_complete_t complete) {
                        (void)metadata;
                        // sec_trust_t free-bridges to SecTrustRef (Network/Security
                        // toll-free types, per WWDC 2018 "Network Framework").
                        SecTrustRef trust_ref = sec_trust_copy_ref(sec_trust);
                        if (!trust_ref) { complete(false); return; }

                        // Build the anchor set from every supplied CA DER.
                        CFMutableArrayRef anchors =
                            CFArrayCreateMutable(kCFAllocatorDefault, pinned_ca_ders.count, &kCFTypeArrayCallBacks);
                        for (NSData *der in pinned_ca_ders) {
                            SecCertificateRef ca_cert = SecCertificateCreateWithData(
                                kCFAllocatorDefault, (__bridge CFDataRef)der);
                            if (ca_cert) {
                                CFArrayAppendValue(anchors, ca_cert);
                                CFRelease(ca_cert);
                            }
                        }
                        if (CFArrayGetCount(anchors) == 0) {
                            CFRelease(anchors);
                            CFRelease(trust_ref);
                            complete(false);
                            return;
                        }

                        // Pin: our CAs are the SOLE acceptable anchors. We do NOT
                        // replace NW's policies, so the hostname/serverAuth SSL
                        // policy it configured still applies (the leaf SAN must
                        // match the connect host).
                        SecTrustSetAnchorCertificates(trust_ref, anchors);
                        SecTrustSetAnchorCertificatesOnly(trust_ref, true);

                        CFErrorRef error = NULL;
                        bool valid = SecTrustEvaluateWithError(trust_ref, &error);
                        if (error) CFRelease(error);

                        CFRelease(anchors);
                        CFRelease(trust_ref);

                        complete(valid);
                    },
                    verify_queue);
            }
        });

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
    nw_connection_set_queue(connection, quic_cb_queue);
    nw_connection_start(connection);
}

static inline void nw_helper_quic_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_cancel(connection);
}

static inline void nw_helper_quic_force_cancel(nw_connection_t _Nonnull connection) {
    nw_connection_force_cancel(connection);
}
