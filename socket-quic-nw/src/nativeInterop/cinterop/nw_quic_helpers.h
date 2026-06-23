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
#import <CommonCrypto/CommonDigest.h> // CC_SHA256 for serverCertificateHashes leaf-hash pinning
#include <string.h>                    // memcmp

// Stream-count flow-control limit advertised to the peer on every QUIC connection (issue #112).
// NW's default initial_max_streams_bidirectional is ~7, which wedges a connection after a handful
// of streams; we advertise a generous bound so streams can be multiplexed freely.
#define NW_QUIC_MAX_STREAMS 1024

// max_udp_payload_size (RFC 9000) advertised on every connection (issue #112). NW otherwise uses
// the path MTU, which on loopback is huge (tens of KB), so a single packet can carry many KB —
// non-standard and inconsistent with the quiche-backed targets (which cap at 1350). Pinning the
// same 1350 keeps packetization comparable across platforms; >= the 1200 QUIC minimum.
#define NW_QUIC_MAX_UDP_PAYLOAD 1350

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
    // deadlock is possible. (A per-connection queue was tried and reverted — it did
    // not lift the per-endpoint connection limit and leaks one queue per stream in
    // this non-ARC cinterop. See issue #112.)
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

/**
 * Abruptly reset a QUIC stream with an application error code — the wire
 * equivalent of quiche's RESET_STREAM (send side) + STOP_SENDING (read side)
 * (RFC 9000 §19.4/§19.5), as opposed to the graceful FIN of nw_helper_quic_send
 * with is_complete=true.
 *
 * Network.framework has no dedicated reset call: you stamp the application error
 * onto the stream's QUIC metadata (nw_quic_set_stream_application_error) and then
 * cancel the stream's nw_connection. NW emits RESET_STREAM/STOP_SENDING carrying
 * that error_code instead of a clean close. If the metadata can't be copied (the
 * stream is already gone) we still cancel so teardown is unconditional. (Issue #81.)
 */
static inline void nw_helper_quic_reset_stream(
    nw_connection_t _Nonnull connection,
    uint64_t error_code)
{
    nw_protocol_definition_t quic_def = nw_protocol_copy_quic_definition();
    nw_protocol_metadata_t metadata =
        nw_connection_copy_protocol_metadata(connection, quic_def);
    if (metadata) {
        nw_quic_set_stream_application_error(metadata, error_code);
    }
    nw_connection_cancel(connection);
}

/**
 * The QUIC application error code the peer sent when it reset this stream
 * (RESET_STREAM / STOP_SENDING), or UINT64_MAX if the stream wasn't reset.
 *
 * Used by the receive path to tell an abrupt stream reset (a non-MAX app error →
 * ReadResult.Reset) apart from a transport-level close such as an idle timeout or
 * connection close (no stream app error → ReadResult.End). Both surface to
 * nw_connection_receive as an nw_error, so the error alone can't distinguish them.
 * (Issue #81.)
 */
static inline uint64_t nw_helper_quic_stream_application_error(
    nw_connection_t _Nonnull connection)
{
    nw_protocol_definition_t quic_def = nw_protocol_copy_quic_definition();
    nw_protocol_metadata_t metadata =
        nw_connection_copy_protocol_metadata(connection, quic_def);
    if (!metadata) return UINT64_MAX;
    return nw_quic_get_stream_application_error(metadata);
}

/**
 * The REAL QUIC stream id (RFC 9000 §2.1) of a peer-initiated stream flow, or UINT64_MAX if
 * unavailable.
 *
 * Earlier code assumed "Network.framework hides the real QUIC stream id" and synthesized one for
 * each accepted stream — but a synthetic id can't carry the real directionality (bit 1) or
 * initiator (bit 0), and the HTTP/3 router dispatches control/QPACK *unidirectional* streams vs
 * request/WebTransport *bidirectional* streams purely on QuicStreamId.isUnidirectional. NW DOES
 * expose the real id via the stream flow's QUIC metadata (nw_quic_get_stream_id), which makes the
 * synthetic id unnecessary for accepted streams: the real id has correct direction + initiator bits
 * and matches the peer's view.
 *
 * Like the keepalive/app-error accessors, the per-stream metadata is only populated on a STARTED,
 * live flow; callers query after the flow has produced its first read. Returns UINT64_MAX if the
 * metadata isn't available yet, so the caller can fall back to a synthetic id.
 */
static inline uint64_t nw_helper_quic_stream_real_id(
    nw_connection_t _Nonnull connection)
{
    nw_protocol_definition_t quic_def = nw_protocol_copy_quic_definition();
    nw_protocol_metadata_t metadata =
        nw_connection_copy_protocol_metadata(connection, quic_def);
    if (!metadata) return UINT64_MAX;
    return nw_quic_get_stream_id(metadata);
}

/**
 * Set the QUIC keepalive interval (RFC 9000 §10.1.2) so an otherwise-idle connection
 * sends ack-eliciting PINGs and survives past the idle timeout.
 *
 * nw_quic_set_keepalive_interval takes the connection's nw_protocol_metadata_t — NOT the
 * nw_protocol_options_t available in the create-params block (setting it there is a silent
 * no-op: the type mismatch only warns, and was the original #130 defect). The metadata is
 * obtainable only from a STARTED, live flow (nw_connection_copy_protocol_metadata returns
 * nil before the flow is ready, so setting it right after extract is also a no-op). Callers
 * apply it on the first successful I/O on a flow. All flows share one QUIC connection, so
 * configuring any one configures the connection. seconds; 0 = disabled.
 */
static inline void nw_helper_quic_set_keepalive(
    nw_connection_t _Nonnull connection,
    uint16_t keepalive_interval_seconds)
{
    nw_protocol_definition_t quic_def = nw_protocol_copy_quic_definition();
    nw_protocol_metadata_t metadata =
        nw_connection_copy_protocol_metadata(connection, quic_def);
    if (metadata) {
        nw_quic_set_keepalive_interval(metadata, keepalive_interval_seconds);
    }
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
 *  - verify_certs=false disables peer cert + hostname validation
 *    (sec_protocol_options_set_peer_authentication_required false), matching quiche/JVM;
 *    it is overridden by trusted_ca_ders when those are present. When trusted_ca_ders is
 *    non-empty, a verify_block pins those DER CAs as the SOLE
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
#if TARGET_OS_OSX
/**
 * Pull a CFAbsoluteTime (seconds since the 2001 CF reference date) for an X.509 validity OID out of a
 * SecCertificateCopyValues result dictionary. Each top-level value is itself a property dict keyed by
 * kSecPropertyKeyValue. Returns true on success.
 */
static inline bool ditchoom_apple_copy_validity(
    CFDictionaryRef _Nonnull values, CFStringRef _Nonnull oid, double * _Nonnull out)
{
    CFDictionaryRef prop = (CFDictionaryRef)CFDictionaryGetValue(values, oid);
    if (!prop) return false;
    CFNumberRef num = (CFNumberRef)CFDictionaryGetValue(prop, kSecPropertyKeyValue);
    if (!num) return false;
    return CFNumberGetValue(num, kCFNumberDoubleType, out);
}
#endif

/**
 * Extract the W3C `serverCertificateHashes` constraint fields from a leaf certificate's DER via
 * Security.framework — the platform-native X.509 parser (no hand-rolled ASN.1). Mirrors the Linux
 * BoringSSL wrapper (`ditchoom_x509_pin_fields`): it returns only the RAW fields; the constraint POLICY
 * (validity ≤ 14 days, currently valid, ECDSA P-256) stays in shared Kotlin
 * (`checkServerCertificatePinConstraints`).
 *
 * **macOS only:** `SecCertificateCopyValues` (the only public validity-date API) is `__IPHONE_NA`, so on
 * iOS/tvOS/watchOS this is compiled out and returns 0. The Kotlin caller never invokes it there anyway —
 * `serverCertificateConstraintSupport` reports `LeafHashOnly` on those platforms, gating the call out.
 *
 * @param der / der_len the leaf certificate DER (the matched leaf the verify_block captured).
 * @param not_before_unix_seconds_out / not_after_unix_seconds_out the validity window as Unix epoch seconds.
 * @param key_type_out 0 = unknown/other, 1 = EC (ECSECPrimeRandom — Apple's NIST prime curves), 2 = RSA.
 * @param key_size_bits_out the key size in bits (256 + EC ⇒ secp256r1 / P-256 on Apple's SecKey).
 * @return 1 on success (all out-params populated), 0 on parse failure / unsupported platform.
 */
static inline int ditchoom_apple_pin_leaf_fields(
    const uint8_t * _Nonnull der,
    int32_t der_len,
    double * _Nonnull not_before_unix_seconds_out,
    double * _Nonnull not_after_unix_seconds_out,
    int32_t * _Nonnull key_type_out,
    int32_t * _Nonnull key_size_bits_out)
{
#if TARGET_OS_OSX
    if (der == NULL || der_len <= 0) return 0;
    CFDataRef cf_data = CFDataCreate(kCFAllocatorDefault, der, (CFIndex)der_len);
    if (!cf_data) return 0;
    SecCertificateRef cert = SecCertificateCreateWithData(kCFAllocatorDefault, cf_data);
    CFRelease(cf_data);
    if (!cert) return 0;

    // Validity window. Passing NULL keys returns all properties; we read the two validity OIDs.
    double not_before = 0, not_after = 0;
    bool got_dates = false;
    CFDictionaryRef values = SecCertificateCopyValues(cert, NULL, NULL);
    if (values) {
        bool got_nb = ditchoom_apple_copy_validity(values, kSecOIDX509V1ValidityNotBefore, &not_before);
        bool got_na = ditchoom_apple_copy_validity(values, kSecOIDX509V1ValidityNotAfter, &not_after);
        got_dates = got_nb && got_na;
        CFRelease(values);
    }

    // Subject public key type/size. Apple reports a NIST prime-random EC key as ECSECPrimeRandom + a bit
    // size; 256-bit prime-random EC is secp256r1 (P-256). SecKey has no separate named-curve attribute.
    int32_t key_type = 0, key_size = 0;
    SecKeyRef key = SecCertificateCopyKey(cert);
    if (key) {
        CFDictionaryRef attrs = SecKeyCopyAttributes(key);
        if (attrs) {
            CFStringRef type = (CFStringRef)CFDictionaryGetValue(attrs, kSecAttrKeyType);
            if (type) {
                if (CFEqual(type, kSecAttrKeyTypeECSECPrimeRandom)) key_type = 1;
                else if (CFEqual(type, kSecAttrKeyTypeRSA)) key_type = 2;
            }
            CFNumberRef size_num = (CFNumberRef)CFDictionaryGetValue(attrs, kSecAttrKeySizeInBits);
            if (size_num) CFNumberGetValue(size_num, kCFNumberSInt32Type, &key_size);
            CFRelease(attrs);
        }
        CFRelease(key);
    }

    int result = 0;
    if (got_dates) {
        // CFAbsoluteTime (since 2001-01-01) → Unix epoch (since 1970-01-01).
        *not_before_unix_seconds_out = not_before + kCFAbsoluteTimeIntervalSince1970;
        *not_after_unix_seconds_out = not_after + kCFAbsoluteTimeIntervalSince1970;
        *key_type_out = key_type;
        *key_size_bits_out = key_size;
        result = 1;
    }
    CFRelease(cert);
    return result;
#else
    (void)der; (void)der_len;
    (void)not_before_unix_seconds_out; (void)not_after_unix_seconds_out;
    (void)key_type_out; (void)key_size_bits_out;
    return 0;
#endif
}

static inline nw_connection_group_t _Nullable nw_helper_create_quic_group(
    const char * _Nonnull host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    NSNumber * _Nonnull verify_certs,
    NSArray<NSData *> * _Nullable trusted_ca_ders,
    NSArray<NSData *> * _Nullable server_cert_hashes,
    NSNumber * _Nonnull server_cert_require_chain,
    int32_t * _Nullable server_cert_pin_failure_out,
    uint8_t * _Nullable server_cert_pin_computed_hash_out,
    uint8_t * _Nullable server_cert_leaf_der_out,
    int32_t server_cert_leaf_der_capacity,
    int32_t * _Nullable server_cert_leaf_der_len_out,
    int32_t idle_timeout_seconds,
    int32_t keepalive_interval_seconds,
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
    const BOOL should_verify_peer = verify_certs.boolValue;
    NSArray<NSData *> * _Nullable pinned_ca_ders =
        (trusted_ca_ders.count > 0) ? [trusted_ca_ders copy] : nil;
    // W3C serverCertificateHashes: each NSData is a 32-byte SHA-256 of an accepted leaf cert's DER.
    NSArray<NSData *> * _Nullable pinned_leaf_hashes =
        (server_cert_hashes.count > 0) ? [server_cert_hashes copy] : nil;
    const BOOL hash_require_chain = server_cert_require_chain.boolValue;

    // Set to the created group below, captured by the verify_block so a pinned-anchor
    // REJECTION can cancel the group. Network.framework does NOT report a client-side
    // verify_block rejection as a group state change — the handshake just stalls in
    // limbo (the group's state handler never sees `failed`), so without this the connect
    // would hang until idleTimeout instead of failing. Cancelling forces `cancelled`
    // (state 4), which the Kotlin handler maps to QuicCloseException — matching the
    // quiche path's handshake-time cert-rejection behavior. (Issues #81 / #99.)
    __block nw_connection_group_t established_group = nil;

    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull quic_options) {
            sec_protocol_options_t sec_options = nw_quic_copy_sec_protocol_options(quic_options);

            for (NSString *proto in alpn_copy) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }

            // Apply QuicOptions.idleTimeout (seconds → ms). NW negotiates the min of the two
            // endpoints' values, so an idle connection closes near this bound. Without this NW
            // used its own (much longer) default and the flag was a no-op (issue #112).
            if (idle_timeout_seconds > 0) {
                nw_quic_set_idle_timeout(quic_options, (uint32_t)idle_timeout_seconds * 1000u);
            }

            // QuicOptions.keepAliveInterval is NOT applied here: nw_quic_set_keepalive_interval
            // takes nw_protocol_metadata_t, not the nw_protocol_options_t in this create-params
            // block (the type mismatch only warns and is a silent no-op — the original #130
            // defect). It is applied post-establishment on a live flow's metadata via
            // nw_helper_quic_set_keepalive. [keepalive_interval_seconds] kept for signature
            // symmetry with the idle-timeout path; unused here.
            (void)keepalive_interval_seconds;

            // Raise the stream-count flow-control limits we advertise to the peer. NW's default
            // initial_max_streams_bidirectional is tiny (~7), so a connection wedged after a
            // handful of streams (proven: the 8th stream — concurrent OR sequential — on one
            // connection hung). This is the count of streams the PEER may open toward us; both
            // ends set it so either side can multiplex many streams. (Issue #112.)
            nw_quic_set_initial_max_streams_bidirectional(quic_options, NW_QUIC_MAX_STREAMS);
            nw_quic_set_initial_max_streams_unidirectional(quic_options, NW_QUIC_MAX_STREAMS);
            nw_quic_set_max_udp_payload_size(quic_options, NW_QUIC_MAX_UDP_PAYLOAD);

            // Enable RFC 9221 DATAGRAM frames (connection-level transport param).
            // Gated: the setter is macOS 13 / iOS 16. Callers only pass >0 when
            // QuicOptions.datagrams was set, and the Kotlin side already requires
            // that OS floor before taking the group path.
            if (max_datagram_frame_size > 0) {
                if (__builtin_available(macOS 13.0, iOS 16.0, tvOS 16.0, watchOS 9.0, *)) {
                    nw_quic_set_max_datagram_frame_size(quic_options, max_datagram_frame_size);
                }
            }

            // W3C serverCertificateHashes leaf-hash pinning verify_block. Under HashOnly the SHA-256 of
            // the peer's leaf-cert DER matching a pinned value is the SOLE trust check (browser parity —
            // authenticates a self-signed / ephemeral leaf with no CA); under RequireBoth the chain must
            // ALSO validate (against pinned anchors when supplied, else system trust). Mirrors the quiche
            // backend's applyQuicOptions/verifyServerCertificateHashes split. Takes precedence over the
            // CA-only path below. (Phase 4, Option 1.)
            if (pinned_leaf_hashes != nil) {
                static dispatch_queue_t hash_verify_queue;
                static dispatch_once_t hash_verify_queue_once;
                dispatch_once(&hash_verify_queue_once, ^{
                    hash_verify_queue = dispatch_queue_create("com.ditchoom.socket.quic.verify.hash", DISPATCH_QUEUE_SERIAL);
                });
                sec_protocol_options_set_verify_block(sec_options,
                    ^(sec_protocol_metadata_t metadata, sec_trust_t sec_trust, sec_protocol_verify_complete_t complete) {
                        (void)metadata;
                        // Reason codes mirror Kotlin's CertificateHashPinningFailure: 1 = HashMismatch
                        // (digest written to ..._computed_hash_out), 2 = NoPeerCertificate, 0 = not a pin
                        // failure (e.g. RequireBoth chain failure → generic handshake rejection).
                        SecTrustRef trust_ref = sec_trust_copy_ref(sec_trust);
                        if (!trust_ref) {
                            if (server_cert_pin_failure_out) *server_cert_pin_failure_out = 2;
                            complete(false);
                            if (established_group) nw_connection_group_cancel(established_group);
                            return;
                        }

                        // Leaf certificate = index 0 of the presented chain.
                        SecCertificateRef leaf = NULL;
                        CFArrayRef chain = NULL;
                        if (__builtin_available(macOS 12.0, iOS 15.0, tvOS 15.0, watchOS 8.0, *)) {
                            chain = SecTrustCopyCertificateChain(trust_ref);
                            if (chain && CFArrayGetCount(chain) > 0) {
                                leaf = (SecCertificateRef)CFArrayGetValueAtIndex(chain, 0); // owned by `chain`
                            }
                        } else {
                            leaf = SecTrustGetCertificateAtIndex(trust_ref, 0); // get-rule: not owned
                        }

                        bool hash_match = false;
                        bool got_leaf_digest = false;
                        uint8_t digest[CC_SHA256_DIGEST_LENGTH];
                        if (leaf) {
                            CFDataRef der = SecCertificateCopyData(leaf);
                            if (der) {
                                CC_SHA256(CFDataGetBytePtr(der), (CC_LONG)CFDataGetLength(der), digest);
                                got_leaf_digest = true;
                                for (NSData *h in pinned_leaf_hashes) {
                                    if (h.length == CC_SHA256_DIGEST_LENGTH &&
                                        memcmp(h.bytes, digest, CC_SHA256_DIGEST_LENGTH) == 0) {
                                        hash_match = true;
                                        break;
                                    }
                                }
                                // On a match, hand the matched leaf's full DER back to the Kotlin caller so
                                // it can run the W3C `serverCertificateHashes` certificate constraints
                                // (validity ≤ 14 days, currently valid, ECDSA P-256) post-handshake via
                                // Security.framework — the constraint POLICY stays in shared Kotlin, only
                                // the parse happens per-platform. snprintf-style: always report the needed
                                // length; copy only when it fits the caller's buffer (a real leaf is < 4 KiB).
                                if (hash_match && server_cert_leaf_der_len_out) {
                                    CFIndex der_len = CFDataGetLength(der);
                                    *server_cert_leaf_der_len_out = (int32_t)der_len;
                                    if (server_cert_leaf_der_out && der_len <= (CFIndex)server_cert_leaf_der_capacity) {
                                        memcpy(server_cert_leaf_der_out, CFDataGetBytePtr(der), (size_t)der_len);
                                    }
                                }
                                CFRelease(der);
                            }
                        }

                        bool ok = hash_match;
                        if (ok && hash_require_chain) {
                            // Defense in depth: the chain must validate too. Pin the supplied CA anchors
                            // as the sole anchors when present; otherwise default system trust runs.
                            if (pinned_ca_ders != nil) {
                                CFMutableArrayRef anchors =
                                    CFArrayCreateMutable(kCFAllocatorDefault, pinned_ca_ders.count, &kCFTypeArrayCallBacks);
                                for (NSData *der in pinned_ca_ders) {
                                    SecCertificateRef ca_cert = SecCertificateCreateWithData(
                                        kCFAllocatorDefault, (__bridge CFDataRef)der);
                                    if (ca_cert) { CFArrayAppendValue(anchors, ca_cert); CFRelease(ca_cert); }
                                }
                                SecTrustSetAnchorCertificates(trust_ref, anchors);
                                SecTrustSetAnchorCertificatesOnly(trust_ref, true);
                                SecTrustSetNetworkFetchAllowed(trust_ref, false);
                                CFRelease(anchors);
                            }
                            CFErrorRef error = NULL;
                            ok = SecTrustEvaluateWithError(trust_ref, &error);
                            if (error) CFRelease(error);
                        }

                        // Record why pinning rejected, so the Kotlin side throws a typed
                        // CertificateHashPinningException (matching the quiche backends) rather than a
                        // generic cancellation. A hash matched + chain failure (RequireBoth) leaves the
                        // code 0 → generic handshake rejection, mirroring quiche (chain failure isn't a
                        // pin failure there either).
                        if (!ok && server_cert_pin_failure_out) {
                            if (got_leaf_digest && !hash_match) {
                                *server_cert_pin_failure_out = 1; // HashMismatch
                                if (server_cert_pin_computed_hash_out) {
                                    memcpy(server_cert_pin_computed_hash_out, digest, CC_SHA256_DIGEST_LENGTH);
                                }
                            } else if (!got_leaf_digest) {
                                *server_cert_pin_failure_out = 2; // NoPeerCertificate
                            }
                        }

                        if (chain) CFRelease(chain);
                        CFRelease(trust_ref);

                        complete(ok);
                        // NW does not report a client verify_block rejection as a group state change —
                        // cancel so it surfaces as `cancelled` (→ typed exception), like the CA path.
                        if (!ok && established_group) {
                            nw_connection_group_cancel(established_group);
                        }
                    },
                    hash_verify_queue);
            // CA-pinning verify_block (issue #81 — see this function's docstring).
            } else if (pinned_ca_ders != nil) {
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
                        // On rejection, cancel the group so the stall surfaces as `cancelled`
                        // (state 4 → QuicCloseException). NW won't report the rejection itself.
                        if (!valid && established_group) {
                            nw_connection_group_cancel(established_group);
                        }
                    },
                    verify_queue);
            } else if (!should_verify_peer) {
                // verifyPeer=false: skip peer certificate AND hostname validation, matching the
                // quiche/JVM semantics and the base TCP helper (nw_helpers.h). Previously
                // verify_certs was ignored and the QUIC TLS stack always ran default system
                // trust, so the flag silently did nothing — and an in-process server presenting
                // a private-CA / non-matching-hostname cert (issue #112) could never be reached.
                // With no pins and verifyPeer=true, NW's default system trust still runs.
                sec_protocol_options_set_peer_authentication_required(sec_options, false);
            }
        });

    if (!params) return NULL;

    (void)connection_timeout_seconds; // reserved for parity with the single-conn helper

    nw_group_descriptor_t descriptor = nw_group_descriptor_create_multiplex(endpoint);
    if (!descriptor) return NULL;

    nw_connection_group_t group = nw_connection_group_create(descriptor, params);
    // Publish to the verify_block so a pinned-anchor rejection can cancel the group. The
    // handshake (hence the verify_block) only runs after the Kotlin caller starts the group,
    // which is strictly after this assignment, so there is no use-before-set race.
    established_group = group;
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
 *
 * We do NOT pass NULL options: once nw_helper_quic_group_extract_uni_stream has flipped
 * is_unidirectional on options derived from the group, a subsequent NULL-options ("group
 * default") extract inherits that dirty state and opens a UNIDIRECTIONAL stream — the
 * peer then sees a uni id (e.g. a request bidi arriving as id 14, classified uni, so the
 * HTTP/3 server drains it instead of routing it). So we mirror the uni path: copy the
 * group's own QUIC options (a fresh nw_quic_create_options() is torn down with ENETDOWN,
 * issue #109) and EXPLICITLY set is_unidirectional = false, which is robust to any leaked
 * state. Falls back to NULL options only if the QUIC options can't be derived.
 */
static inline nw_connection_t _Nullable nw_helper_quic_group_extract_stream(
    nw_connection_group_t _Nonnull group)
{
    nw_parameters_t params = nw_connection_group_copy_parameters(group);
    if (params) {
        nw_protocol_stack_t stack = nw_parameters_copy_default_protocol_stack(params);
        if (stack) {
            nw_protocol_options_t quic_options = nw_protocol_stack_copy_transport_protocol(stack);
            if (quic_options && nw_protocol_options_is_quic(quic_options)) {
                nw_quic_set_stream_is_unidirectional(quic_options, false);
                return nw_connection_group_extract_connection(group, NULL, quic_options);
            }
        }
    }
    // Couldn't derive the group's options → fall back to the group default (bidirectional).
    return nw_connection_group_extract_connection(group, NULL, NULL);
}

/**
 * Extract a new locally-initiated UNIDIRECTIONAL stream flow from the group — the
 * client-to-server uni streams HTTP/3 needs (control + QPACK encoder/decoder, RFC
 * 9114 §6.2). Like the datagram flow — and unlike a bidi stream extracted with NULL
 * options — a uni stream MUST be extracted with the connection's OWN QUIC options
 * copied from the group's established parameters: a fresh nw_quic_create_options()
 * tears the flow down immediately with ENETDOWN (issue #109). We copy the group's
 * QUIC options and only flip is_unidirectional (macOS 11 / iOS 14 — no later floor
 * than the QUIC group itself, so ungated). The returned connection must have its
 * queue set and be started (nw_helper_quic_start) before use; returns NULL if the
 * QUIC options can't be derived.
 */
static inline nw_connection_t _Nullable nw_helper_quic_group_extract_uni_stream(
    nw_connection_group_t _Nonnull group)
{
    nw_parameters_t params = nw_connection_group_copy_parameters(group);
    if (!params) return NULL;
    nw_protocol_stack_t stack = nw_parameters_copy_default_protocol_stack(params);
    if (!stack) return NULL;
    nw_protocol_options_t quic_options = nw_protocol_stack_copy_transport_protocol(stack);
    if (!quic_options || !nw_protocol_options_is_quic(quic_options)) return NULL;
    nw_quic_set_stream_is_unidirectional(quic_options, true);
    return nw_connection_group_extract_connection(group, NULL, quic_options);
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

#pragma mark - QUIC Server: PKCS#12 identity + NWListener (issue #112)

/**
 * Build a sec_identity_t (the server's TLS identity) from a PKCS#12 blob.
 *
 * QuicTlsConfig carries PEM cert+key paths, but Network.framework's QUIC listener
 * needs a sec_identity_t (a SecIdentityRef) and there is no public Apple API to
 * assemble one from loose PEM material without either the keychain or a PKCS#12
 * container (issue #112). The Kotlin side reads the .p12 file into NSData and the
 * matching passphrase; we import it with SecPKCS12Import and wrap the resulting
 * SecIdentityRef with sec_identity_create.
 *
 * kSecImportToMemoryOnly (macOS 15 / iOS 18) keeps the imported key in process
 * memory rather than the default keychain — no keychain pollution, and the key is
 * still usable for the TLS handshake. SecPKCS12Import otherwise imports into the
 * default keychain on macOS; we set the flag only when available (on iOS,
 * memory-only is already the default).
 *
 * @return a retained sec_identity_t, or NULL on failure (wrong password, malformed
 *         blob, or no identity in the container).
 */
static inline sec_identity_t _Nullable nw_helper_quic_identity_from_p12(
    NSData * _Nonnull p12_data,
    NSString * _Nonnull password)
{
    CFMutableDictionaryRef options = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(options, kSecImportExportPassphrase, (__bridge CFStringRef)password);
    if (__builtin_available(macOS 15.0, iOS 18.0, tvOS 18.0, watchOS 11.0, *)) {
        CFDictionarySetValue(options, kSecImportToMemoryOnly, kCFBooleanTrue);
    }

    CFArrayRef items = NULL;
    OSStatus status = SecPKCS12Import((__bridge CFDataRef)p12_data, options, &items);
    CFRelease(options);
    if (status != errSecSuccess || items == NULL) {
        if (items) CFRelease(items);
        return NULL;
    }
    if (CFArrayGetCount(items) == 0) {
        CFRelease(items);
        return NULL;
    }

    CFDictionaryRef item = (CFDictionaryRef)CFArrayGetValueAtIndex(items, 0);
    SecIdentityRef sec_identity_ref =
        (SecIdentityRef)CFDictionaryGetValue(item, kSecImportItemIdentity);
    if (!sec_identity_ref) {
        CFRelease(items);
        return NULL;
    }

    // sec_identity_create retains the SecIdentityRef, so releasing `items` (its sole
    // strong reference until now) after is safe.
    sec_identity_t identity = sec_identity_create(sec_identity_ref);
    CFRelease(items);
    return identity;
}

/**
 * Create a QUIC *listener* — the server analog of nw_helper_create_quic_group.
 *
 * Same QUIC parameter setup as the client group: ALPN via the QUIC-specific
 * accessor nw_quic_copy_sec_protocol_options (NOT the generic nw_tls_* accessor —
 * see nw_helper_create_quic_group's #81 note). Instead of the client's CA-pinning
 * verify_block it installs the server's local identity via
 * sec_protocol_options_set_local_identity. When [max_datagram_frame_size] > 0 the
 * listener advertises RFC 9221 DATAGRAM support (macOS 13 / iOS 16).
 *
 * Binds [port] across all interfaces (dual-stack), like the proven TCP server helper
 * (nw_helpers.h). [host] is accepted for API parity but not wired to a specific bind
 * address yet, matching the Linux server. Pass port 0 for an OS-assigned port and read
 * it back via nw_helper_quic_listener_get_port once ready.
 *
 * An incoming QUIC tunnel is delivered to the new-connection-GROUP handler (set via
 * nw_helper_quic_listener_set_new_connection_group_handler) as an
 * nw_connection_group_t — the SAME multiplex group type the client uses — because
 * QUIC is a multiplexing protocol (listener.h: that handler is mutually exclusive
 * with the plain new-connection handler).
 *
 * @return nw_listener_t or NULL on parameter error.
 */
static inline nw_listener_t _Nullable nw_helper_create_quic_listener(
    const char * _Nullable host,
    uint16_t port,
    NSArray<NSString *> * _Nonnull alpn_protocols,
    sec_identity_t _Nonnull identity,
    int32_t idle_timeout_seconds,
    int32_t keepalive_interval_seconds,
    uint16_t max_datagram_frame_size)
{
    if (alpn_protocols.count == 0) return NULL;

    // Retained copies for the async configure block (runs during listener setup).
    NSArray<NSString *> *alpn_copy = [alpn_protocols copy];
    sec_identity_t identity_copy = identity; // block-capture retains under ARC

    nw_parameters_t params = nw_parameters_create_quic(
        ^(nw_protocol_options_t _Nonnull quic_options) {
            sec_protocol_options_t sec_options = nw_quic_copy_sec_protocol_options(quic_options);

            for (NSString *proto in alpn_copy) {
                sec_protocol_options_add_tls_application_protocol(sec_options, proto.UTF8String);
            }

            // The server presents this identity during the handshake (the mirror of
            // the client's verify_block — see this header's #81 notes).
            sec_protocol_options_set_local_identity(sec_options, identity_copy);

            // Apply QuicOptions.idleTimeout (seconds → ms), same as the client path.
            if (idle_timeout_seconds > 0) {
                nw_quic_set_idle_timeout(quic_options, (uint32_t)idle_timeout_seconds * 1000u);
            }

            // QuicOptions.keepAliveInterval is NOT applied here: nw_quic_set_keepalive_interval
            // takes nw_protocol_metadata_t, not the nw_protocol_options_t in this create-params
            // block (the type mismatch only warns and is a silent no-op — the original #130
            // defect). It is applied post-establishment on a live flow's metadata via
            // nw_helper_quic_set_keepalive. [keepalive_interval_seconds] kept for signature
            // symmetry with the idle-timeout path; unused here.
            (void)keepalive_interval_seconds;

            // Grant the client credit to open many streams (NW's default is ~7). See the client
            // helper's note. (Issue #112.)
            nw_quic_set_initial_max_streams_bidirectional(quic_options, NW_QUIC_MAX_STREAMS);
            nw_quic_set_initial_max_streams_unidirectional(quic_options, NW_QUIC_MAX_STREAMS);
            nw_quic_set_max_udp_payload_size(quic_options, NW_QUIC_MAX_UDP_PAYLOAD);

            if (max_datagram_frame_size > 0) {
                if (__builtin_available(macOS 13.0, iOS 16.0, tvOS 16.0, watchOS 9.0, *)) {
                    nw_quic_set_max_datagram_frame_size(quic_options, max_datagram_frame_size);
                }
            }
        });

    if (!params) return NULL;

    // Bind on [port] across all interfaces via nw_listener_create_with_port, exactly like
    // the proven TCP server helper. A local endpoint pinned to a host (e.g. "::") left the
    // client's loopback packets with no matching path and the handshake hung in `preparing`.
    // port 0 → OS-assigned (nw_listener_create). [host] is reserved (see docstring).
    (void)host;
    nw_listener_t listener;
    if (port == 0) {
        listener = nw_listener_create(params);
    } else {
        char port_str[6];
        snprintf(port_str, sizeof(port_str), "%u", port);
        listener = nw_listener_create_with_port(port_str, params);
    }
    if (listener == NULL) return NULL;
    // Don't cap concurrent incoming connections (parity with the TCP server helper).
    nw_listener_set_new_connection_limit(listener, NW_LISTENER_INFINITE_CONNECTION_LIMIT);
    return listener;
}

/**
 * Listener state-changed handler. Mapping mirrors the group handler:
 *   0=invalid, 1=waiting, 2=ready, 3=failed, 4=cancelled.
 */
typedef void (^quic_listener_state_handler_t)(
    int32_t state,
    int32_t error_domain,
    int32_t error_code,
    NSString * _Nullable error_description
);

static inline void nw_helper_quic_listener_set_state_handler(
    nw_listener_t _Nonnull listener,
    quic_listener_state_handler_t _Nonnull handler)
{
    nw_listener_set_state_changed_handler(listener,
        ^(nw_listener_state_t state, nw_error_t _Nullable error) {
            int32_t mapped_state;
            switch (state) {
                case nw_listener_state_invalid:   mapped_state = 0; break;
                case nw_listener_state_waiting:   mapped_state = 1; break;
                case nw_listener_state_ready:     mapped_state = 2; break;
                case nw_listener_state_failed:    mapped_state = 3; break;
                case nw_listener_state_cancelled: mapped_state = 4; break;
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

/** The local port the listener bound. 0 until the listener reaches ready (see listener.h). */
static inline uint16_t nw_helper_quic_listener_get_port(nw_listener_t _Nonnull listener) {
    return nw_listener_get_port(listener);
}

/**
 * Block delivering an incoming QUIC tunnel as a multiplex [nw_connection_group_t].
 * The group is NOT yet started — the Kotlin handler must set its state +
 * new-connection handlers (nw_helper_quic_group_set_*) and start it
 * (nw_helper_quic_group_start) before use, exactly as the client path does.
 */
typedef void (^quic_new_group_handler_t)(nw_connection_group_t _Nonnull connection_group);

/**
 * Wire the listener's new-connection-GROUP handler. MUST be called before
 * nw_helper_quic_listener_start (listener.h forbids setting it after start), and is
 * mutually exclusive with the plain new-connection handler (we never set that one).
 */
static inline void nw_helper_quic_listener_set_new_connection_group_handler(
    nw_listener_t _Nonnull listener,
    quic_new_group_handler_t _Nonnull handler)
{
    nw_listener_set_new_connection_group_handler(listener,
        ^(nw_connection_group_t _Nonnull group) {
            handler(group);
        });
}

/**
 * DIAGNOSTIC ONLY (anti-amplification interop probe, not used by the production server).
 * Block delivering an incoming QUIC connection as a SINGLE [nw_connection_t] (one QUIC
 * stream), the non-group QUIC model. Mutually exclusive with the group handler above.
 * Used by the NwPlainListenerProbe appleTest to test whether a plain (non-group) NW QUIC
 * listener under-credits the client Initial for RFC 9000 §8.1 anti-amplification the same
 * way the connection-group server does — i.e. whether the bug is libquic-wide or
 * group-specific. The connection is NOT started; the Kotlin handler drives it via
 * nw_helper_quic_start / nw_helper_quic_set_state_handler.
 */
typedef void (^quic_new_connection_handler_t)(nw_connection_t _Nonnull connection);

static inline void nw_helper_quic_listener_set_new_connection_handler(
    nw_listener_t _Nonnull listener,
    quic_new_connection_handler_t _Nonnull handler)
{
    nw_listener_set_new_connection_handler(listener,
        ^(nw_connection_t _Nonnull connection) {
            handler(connection);
        });
}

/**
 * Start the listener on a dedicated SERIAL callback queue — same K/N
 * foreign-thread-safety rationale as nw_helper_quic_start (one callback at a time).
 */
static inline void nw_helper_quic_listener_start(nw_listener_t _Nonnull listener) {
    static dispatch_queue_t listener_cb_queue;
    static dispatch_once_t listener_cb_queue_once;
    dispatch_once(&listener_cb_queue_once, ^{
        listener_cb_queue = dispatch_queue_create("com.ditchoom.socket.quic.nw.listener", DISPATCH_QUEUE_SERIAL);
    });
    nw_listener_set_queue(listener, listener_cb_queue);
    nw_listener_start(listener);
}

static inline void nw_helper_quic_listener_cancel(nw_listener_t _Nonnull listener) {
    nw_listener_cancel(listener);
}
