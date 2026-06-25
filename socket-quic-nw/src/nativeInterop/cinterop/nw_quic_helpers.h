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

/**
 * Decompose an [nw_error_t] into the (domain, code, description) triple every Kotlin callback here
 * expects. [out_desc] receives an autoreleased NSString (or nil). Factored out of the six identical
 * inline copies that previously lived in each send/receive/state-changed block — change the NW error
 * mapping in ONE place. All three out-params are always written (zeroed/nil when [error] is NULL).
 */
static inline void nw_helper_fill_error(
    nw_error_t _Nullable error,
    int32_t * _Nonnull out_domain,
    int32_t * _Nonnull out_code,
    NSString * _Nullable * _Nonnull out_desc)
{
    *out_domain = 0;
    *out_code = 0;
    *out_desc = nil;
    if (error) {
        *out_domain = nw_error_get_error_domain(error);
        *out_code = nw_error_get_error_code(error);
        CFErrorRef cf_error = nw_error_copy_cf_error(error);
        if (cf_error) {
            *out_desc = (__bridge_transfer NSString *)CFErrorCopyDescription(cf_error);
            CFRelease(cf_error);
        }
    }
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

            int32_t err_domain, err_code;
            NSString *err_desc;
            nw_helper_fill_error(error, &err_domain, &err_code, &err_desc);

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

            int32_t err_domain, err_code;
            NSString *err_desc;
            nw_helper_fill_error(error, &err_domain, &err_code, &err_desc);

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
            int32_t err_domain, err_code;
            NSString *err_desc;
            nw_helper_fill_error(error, &err_domain, &err_code, &err_desc);
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

#pragma mark - QUIC cert-pin leaf inspection (issue #81 — W3C serverCertificateHashes)

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
 * Inspect the server's TLS leaf certificate to feed the Network.framework anti-amplification guard
 * (see buildAppleQuicServer). From the imported sec_identity_t we pull the leaf SecCertificateRef
 * (sec_identity_copy_ref → SecIdentityCopyCertificate), its DER length (SecCertificateCopyData), and
 * its public-key type (SecCertificateCopyKey + SecKeyCopyAttributes / kSecAttrKeyType). The Kotlin
 * guard turns leaf_der_len + key_type into an estimated TLS handshake-flight size and rejects an
 * RSA-on-NW-server cert (which deadlocks against quiche/Chrome — see the limitation note on
 * buildAppleQuicServer) unless the caller opts out.
 *
 * Cross-platform: SecIdentityCopyCertificate / SecCertificateCopyData / SecCertificateCopyKey are all
 * public on every Apple OS (unlike the validity-date API in ditchoom_apple_pin_leaf_fields), so this is
 * not gated to macOS.
 *
 * @param key_type_out 0 = unknown/other, 1 = EC (ECSECPrimeRandom — Apple's NIST prime curves), 2 = RSA.
 * @param leaf_der_len_out the leaf certificate DER length in bytes.
 * @return 1 on success (both out-params populated), 0 on failure (no identity cert / DER copy failed).
 */
static inline int nw_helper_quic_identity_leaf_info(
    sec_identity_t _Nonnull identity,
    int32_t * _Nonnull key_type_out,
    int32_t * _Nonnull leaf_der_len_out)
{
    SecIdentityRef ref = sec_identity_copy_ref(identity); // retained — release below
    if (!ref) return 0;
    SecCertificateRef cert = NULL;
    OSStatus status = SecIdentityCopyCertificate(ref, &cert);
    CFRelease(ref);
    if (status != errSecSuccess || cert == NULL) {
        if (cert) CFRelease(cert);
        return 0;
    }

    int result = 0;
    CFDataRef der = SecCertificateCopyData(cert);
    if (der) {
        *leaf_der_len_out = (int32_t)CFDataGetLength(der);
        CFRelease(der);

        int32_t key_type = 0;
        SecKeyRef key = SecCertificateCopyKey(cert);
        if (key) {
            CFDictionaryRef attrs = SecKeyCopyAttributes(key);
            if (attrs) {
                CFStringRef type = (CFStringRef)CFDictionaryGetValue(attrs, kSecAttrKeyType);
                if (type) {
                    if (CFEqual(type, kSecAttrKeyTypeECSECPrimeRandom)) key_type = 1;
                    else if (CFEqual(type, kSecAttrKeyTypeRSA)) key_type = 2;
                }
                CFRelease(attrs);
            }
            CFRelease(key);
        }
        *key_type_out = key_type;
        result = 1;
    }
    CFRelease(cert);
    return result;
}

/**
 * Create a QUIC *listener* — the Apple QUIC server creator.
 *
 * QUIC parameter setup: ALPN via the QUIC-specific accessor
 * nw_quic_copy_sec_protocol_options (NOT the generic nw_tls_* accessor — that
 * wrong-accessor mistake is the PR #60 ALPN SIGTRAP / PR #54 verify_block SIGABRT
 * defect). Instead of a client CA-pinning verify_block it installs the server's local identity via
 * sec_protocol_options_set_local_identity. When [max_datagram_frame_size] > 0 the
 * listener advertises RFC 9221 DATAGRAM support (macOS 13 / iOS 16).
 *
 * Binds [port] across all interfaces (dual-stack), like the proven TCP server helper
 * (nw_helpers.h). [host] is accepted for API parity but not wired to a specific bind
 * address yet, matching the Linux server. Pass port 0 for an OS-assigned port and read
 * it back via nw_helper_quic_listener_get_port once ready.
 *
 * An incoming QUIC tunnel is delivered to the plain new-connection handler (set via
 * nw_helper_quic_listener_set_new_connection_handler) as a single nw_connection_t.
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

            int32_t err_domain, err_code;
            NSString *err_desc;
            nw_helper_fill_error(error, &err_domain, &err_code, &err_desc);

            handler(mapped_state, err_domain, err_code, err_desc);
        });
}

/** The local port the listener bound. 0 until the listener reaches ready (see listener.h). */
static inline uint16_t nw_helper_quic_listener_get_port(nw_listener_t _Nonnull listener) {
    return nw_listener_get_port(listener);
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
