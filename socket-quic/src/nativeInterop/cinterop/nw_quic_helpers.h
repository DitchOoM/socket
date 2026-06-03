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

    // Always mark the message complete (the 4th arg below is `true`, not
    // [is_complete boolValue]). For a byte-stream (TCP / QUIC stream) completing
    // a DEFAULT_MESSAGE is just a flush boundary and keeps the stream open — only
    // the FINAL_MESSAGE context half-closes the send side. Leaving a normal write
    // incomplete (is_complete=false) pins an open default message, and
    // Network.framework then queues every subsequent send — including the
    // FINAL_MESSAGE FIN from stream close() — behind that never-completed message,
    // so the FIN's send-complete callback never fires and close() hangs. (Issue #81.)
    nw_connection_send(connection, dispatch_data, context, true,
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

#pragma mark - QUIC Multiplex Group (streams + datagram flow) — RFC 9221 (issue #109)

/**
 * Create the *multiplex* QUIC connection group — THE Apple QUIC creator (issue #109
 * migrated the former single-nw_connection path onto this).
 *
 * Network.framework models a single nw_connection created via nw_parameters_create_quic
 * as ONE QUIC flow — a flow is EITHER a stream OR the datagram flow
 * (nw_quic_set_stream_is_datagram), never both. To carry real multiple streams AND a
 * datagram flow over ONE QUIC connection you must create a multiplex group and extract
 * each flow from it (nw_helper_quic_group_extract_stream /
 * nw_helper_quic_group_extract_datagram_flow).
 *
 * **#81 hardening baked in here** (the verify_block below):
 *  - ALPN and the verify_block MUST be configured via the QUIC accessor
 *    nw_quic_copy_sec_protocol_options — NOT the generic nw_tls_copy_* on a QUIC options
 *    object, which is the wrong-accessor defect behind the PR #60 ALPN SIGTRAP and the
 *    PR #54 verify_block SIGABRT.
 *  - verify_certs is reserved (NW always evaluates the peer cert); the meaningful switch
 *    is trusted_ca_ders. When non-empty, a verify_block pins those DER CAs as the SOLE
 *    anchors (SecTrustSetAnchorCertificatesOnly) — a pinned anchor is CT-exempt, which
 *    clears the errSSLBadCert (-9808) the default QUIC trust path returns for a private-CA
 *    leaf. When nil, NW's default system trust runs (production path).
 *
 * When [max_datagram_frame_size] > 0 the connection advertises the RFC 9221
 * `max_datagram_frame_size` transport parameter (macOS 13 / iOS 16). The datagram FLOW
 * itself is extracted later via nw_helper_quic_group_extract_datagram_flow.
 *
 * @param max_datagram_frame_size 0 disables DATAGRAM frames; >0 advertises support.
 * @return nw_connection_group_t or NULL on parameter error.
 */
static inline nw_connection_group_t _Nullable nw_helper_create_quic_group(
    const char * _Nonnull host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    NSNumber * _Nonnull verify_certs,
    NSArray<NSData *> * _Nullable trusted_ca_ders,
    int32_t idle_timeout_seconds,
    int32_t connection_timeout_seconds,
    uint16_t max_datagram_frame_size)
{
    if (alpn_protocols.count == 0) return NULL;

    char port_str[6];
    snprintf(port_str, sizeof(port_str), "%u", port);

    nw_endpoint_t endpoint = nw_endpoint_create_host(host, port_str);
    if (!endpoint) return NULL;

    // Retained copies for the async configure/verify blocks: the configure block runs
    // during connection establishment, so these must outlive this function (block-capture
    // retains them on the heap, independent of the K/N-bridged originals — the H3 guard).
    NSArray<NSString *> *alpn_copy = [alpn_protocols copy];
    (void)verify_certs; // meaningful switch is trusted_ca_ders (see docstring)
    NSArray<NSData *> * _Nullable pinned_ca_ders =
        (trusted_ca_ders.count > 0) ? [trusted_ca_ders copy] : nil;

    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull quic_options) {
            sec_protocol_options_t sec_options = nw_quic_copy_sec_protocol_options(quic_options);

            for (NSString *proto in alpn_copy) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }

            // Enable RFC 9221 DATAGRAM frames (connection-level transport param).
            // Gated: the setter is macOS 13 / iOS 16. Callers only pass >0 when
            // QuicOptions.datagrams was set, and the Kotlin side already requires
            // that OS floor before taking the group path.
            if (max_datagram_frame_size > 0) {
                if (__builtin_available(macOS 13.0, iOS 16.0, tvOS 16.0, watchOS 9.0, *)) {
                    nw_quic_set_max_datagram_frame_size(quic_options, max_datagram_frame_size);
                }
            }

            // CA-pinning verify_block (issue #81 — see this function's docstring).
            if (pinned_ca_ders != nil) {
                static dispatch_queue_t verify_queue;
                static dispatch_once_t verify_queue_once;
                dispatch_once(&verify_queue_once, ^{
                    verify_queue = dispatch_queue_create("com.ditchoom.socket.quic.verify.group", DISPATCH_QUEUE_SERIAL);
                });
                sec_protocol_options_set_verify_block(sec_options,
                    ^(sec_protocol_metadata_t metadata, sec_trust_t sec_trust, sec_protocol_verify_complete_t complete) {
                        (void)metadata;
                        SecTrustRef trust_ref = sec_trust_copy_ref(sec_trust);
                        if (!trust_ref) { complete(false); return; }

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

                        SecTrustSetAnchorCertificates(trust_ref, anchors);
                        SecTrustSetAnchorCertificatesOnly(trust_ref, true);
                        SecTrustSetNetworkFetchAllowed(trust_ref, false);

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

    (void)idle_timeout_seconds;       // QUIC idle timeout is managed by NW internally
    (void)connection_timeout_seconds; // reserved for parity with the single-conn helper

    nw_group_descriptor_t descriptor = nw_group_descriptor_create_multiplex(endpoint);
    if (!descriptor) return NULL;

    nw_connection_group_t group = nw_connection_group_create(descriptor, params);
    return group;
}

/**
 * Group state-changed handler. Mapping (NB: group states differ from connection
 * states — there is no `preparing`):
 *   0=invalid, 1=waiting, 2=ready, 3=failed, 4=cancelled.
 */
typedef void (^quic_group_state_handler_t)(
    int32_t state,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_description
);

static inline void nw_helper_quic_group_set_state_handler(
    nw_connection_group_t _Nonnull group,
    quic_group_state_handler_t _Nonnull handler)
{
    nw_connection_group_set_state_changed_handler(group,
        ^(nw_connection_group_state_t state, nw_error_t _Nullable error) {
            int32_t mapped_state;
            switch (state) {
                case nw_connection_group_state_invalid:   mapped_state = 0; break;
                case nw_connection_group_state_waiting:   mapped_state = 1; break;
                case nw_connection_group_state_ready:     mapped_state = 2; break;
                case nw_connection_group_state_failed:    mapped_state = 3; break;
                case nw_connection_group_state_cancelled: mapped_state = 4; break;
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

/**
 * Block delivering a peer-initiated stream flow (one [nw_connection_t] per stream the
 * remote opened). The Kotlin handler takes ownership: it starts the connection
 * (nw_helper_quic_start) and enqueues it for acceptStream()/streams().
 */
typedef void (^quic_new_stream_handler_t)(nw_connection_t _Nonnull connection);

/**
 * Wire the group's new-connection handler so peer-initiated streams reach acceptStream().
 * MUST be called BEFORE nw_helper_quic_group_start (the API forbids setting it after
 * start). Taking ownership of the delivered connection (action 1 of the three the API
 * permits) is the Kotlin handler's job — it runs synchronously inside this block, so the
 * connection is owned (queue set + started) before the block returns.
 */
static inline void nw_helper_quic_group_set_new_connection_handler(
    nw_connection_group_t _Nonnull group,
    quic_new_stream_handler_t _Nonnull handler)
{
    nw_connection_group_set_new_connection_handler(group,
        ^(nw_connection_t _Nonnull connection) {
            handler(connection);
        });
}

/**
 * Start the group. Uses a dedicated SERIAL callback queue for the same K/N
 * foreign-thread-safety reason as nw_helper_quic_start (one callback at a time).
 *
 * nw_connection_group_start requires a receive handler to be set first. A multiplex
 * QUIC group delivers peer-initiated streams via the new-connection handler and
 * datagrams via the extracted datagram-flow connection — never as group-level
 * messages — so the group receive handler is a required no-op.
 */
static inline void nw_helper_quic_group_start(nw_connection_group_t _Nonnull group) {
    static dispatch_queue_t group_cb_queue;
    static dispatch_once_t group_cb_queue_once;
    dispatch_once(&group_cb_queue_once, ^{
        group_cb_queue = dispatch_queue_create("com.ditchoom.socket.quic.nw.group", DISPATCH_QUEUE_SERIAL);
    });
    nw_connection_group_set_queue(group, group_cb_queue);
    nw_connection_group_set_receive_handler(group, 65535, false,
        ^(dispatch_data_t _Nullable content, nw_content_context_t _Nonnull context, bool is_complete) {
            (void)content; (void)context; (void)is_complete; // no-op: see docstring
        });
    nw_connection_group_start(group);
}

static inline void nw_helper_quic_group_cancel(nw_connection_group_t _Nonnull group) {
    nw_connection_group_cancel(group);
}

/**
 * Extract a new locally-initiated bidirectional stream flow from the group. Each
 * call opens a REAL distinct QUIC stream (unlike the single-connection path, where
 * every "stream" aliased the one nw_connection). The returned connection must have
 * its queue set and be started (nw_helper_quic_start) before use.
 */
static inline nw_connection_t _Nullable nw_helper_quic_group_extract_stream(
    nw_connection_group_t _Nonnull group)
{
    // nil protocol options → default bidirectional stream.
    return nw_connection_group_extract_connection(group, NULL, NULL);
}

/**
 * Extract THE datagram flow from the group. Network.framework permits exactly one
 * QUIC datagram flow per connection. macOS 13 / iOS 16 (nw_quic_set_stream_is_datagram);
 * returns NULL below that floor. The returned connection must be started
 * (nw_helper_quic_start) before use; send via nw_helper_quic_datagram_send and
 * receive via nw_helper_quic_receive.
 */
static inline nw_connection_t _Nullable nw_helper_quic_group_extract_datagram_flow(
    nw_connection_group_t _Nonnull group,
    uint16_t max_datagram_frame_size)
{
    if (__builtin_available(macOS 13.0, iOS 16.0, tvOS 16.0, watchOS 9.0, *)) {
        // The extract options MUST be the connection's OWN QUIC options — copied from
        // the group's established parameters and carrying the negotiated ALPN +
        // max_datagram_frame_size — NOT a fresh nw_quic_create_options(). Passing fresh
        // default options to extract_connection tears the flow down immediately with
        // ENETDOWN ("Network is down"); a bidi stream extracted with NULL options is
        // fine, so it is specifically the fresh/empty custom options that break the
        // flow. We copy the group's transport (QUIC) protocol options and only flip
        // is_datagram. (Issue #109.)
        nw_parameters_t params = nw_connection_group_copy_parameters(group);
        if (!params) return NULL;
        nw_protocol_stack_t stack = nw_parameters_copy_default_protocol_stack(params);
        if (!stack) return NULL;
        nw_protocol_options_t quic_options = nw_protocol_stack_copy_transport_protocol(stack);
        if (!quic_options || !nw_protocol_options_is_quic(quic_options)) return NULL;
        nw_quic_set_stream_is_datagram(quic_options, true);
        if (max_datagram_frame_size > 0) {
            nw_quic_set_max_datagram_frame_size(quic_options, max_datagram_frame_size);
        }
        return nw_connection_group_extract_connection(group, NULL, quic_options);
    }
    return NULL;
}

/**
 * Send one unreliable datagram on the datagram-flow connection.
 *
 * Unlike nw_helper_quic_send (stream path, where is_complete=true means a
 * FINAL_MESSAGE FIN that half-closes the flow), a datagram is one COMPLETE message
 * on a never-closing flow: DEFAULT_MESSAGE_CONTEXT with is_complete=true emits
 * exactly one DATAGRAM frame and keeps the flow open for the next datagram.
 */
static inline void nw_helper_quic_datagram_send(
    nw_connection_t _Nonnull connection,
    NSData * _Nonnull data,
    quic_send_complete_t _Nonnull handler)
{
    dispatch_data_t dispatch_data = dispatch_data_create(
        data.bytes, data.length, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0),
        DISPATCH_DATA_DESTRUCTOR_DEFAULT);

    nw_connection_send(connection, dispatch_data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true,
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

/**
 * Maximum datagram payload currently writable on [connection] (the datagram flow),
 * derived from the path MTU minus protocol overhead. 0 until the flow is ready or
 * if datagrams are unavailable. Maps to QuicScope.maxDatagramSize().
 */
static inline uint32_t nw_helper_quic_max_datagram_size(nw_connection_t _Nonnull connection) {
    return nw_connection_get_maximum_datagram_size(connection);
}
