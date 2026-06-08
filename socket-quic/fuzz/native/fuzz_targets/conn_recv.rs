#![no_main]

//! Coverage-guided ASAN fuzz target over quiche's **server packet-recv path** — `quiche::accept` +
//! `Connection::recv`. This is the bytes-from-the-network surface a QUIC server exposes to *anyone*,
//! before authentication: every datagram a server receives is fed through here before any peer is
//! trusted, so an unchecked read / double-free here is a pre-auth remote crash.
//!
//! Far deeper than the `header_info` target: feeding each input as an inbound datagram to a
//! freshly-accepted connection drives packet-number decode, the decryption attempt, and frame parsing
//! — the whole recv state machine, not just the public-header parse.
//!
//! Surface: only quiche's **public Rust API** (`accept` / `recv`) — no new FFI and no new `socket-quic`
//! surface. cargo-fuzz ASAN+SanCov-instruments quiche's Rust here; with a clang toolchain the build also
//! ASAN-instruments the vendored BoringSSL C (see fuzz/README.md), so a memory bug anywhere on the
//! recv+crypto path is caught — otherwise BoringSSL stays uninstrumented and only quiche's Rust is ASAN'd.

use std::cell::RefCell;
use std::net::SocketAddr;

use libfuzzer_sys::fuzz_target;

// Absolute paths baked at compile time (CARGO_MANIFEST_DIR = socket-quic/fuzz/native), so the target
// loads the repo's test cert regardless of the fuzzer's working directory.
const CERT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testcerts/cert.crt");
const KEY: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testcerts/cert.key");

thread_local! {
    // One server Config reused across inputs: building the BoringSSL SSL_CTX (cert + key + TLS state) per
    // iteration would dominate runtime and starve the fuzzer. A fresh Connection per input keeps inputs
    // independent. thread_local (not a global) because Config holds a non-Sync raw SSL_CTX pointer; the
    // libFuzzer loop is single-threaded.
    static CONFIG: RefCell<quiche::Config> = RefCell::new(make_config());
}

fn make_config() -> quiche::Config {
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).unwrap();
    config.load_cert_chain_from_pem_file(CERT).unwrap();
    config.load_priv_key_from_pem_file(KEY).unwrap();
    config.set_application_protos(&[b"fuzz"]).unwrap();
    config.set_max_idle_timeout(5_000);
    config.set_max_recv_udp_payload_size(1350);
    config.set_initial_max_data(10_000_000);
    config.set_initial_max_stream_data_bidi_local(1_000_000);
    config.set_initial_max_stream_data_bidi_remote(1_000_000);
    config.set_initial_max_streams_bidi(100);
    config.set_initial_max_streams_uni(100);
    config.verify_peer(false);
    config
}

fuzz_target!(|data: &[u8]| {
    // recv parses in place and may rewrite the buffer, so hand it an owned copy.
    let mut buf = data.to_vec();
    let to: SocketAddr = "127.0.0.1:4433".parse().unwrap();
    let from: SocketAddr = "127.0.0.1:1234".parse().unwrap();
    let scid = quiche::ConnectionId::from_ref(&[0xba; quiche::MAX_CONN_ID_LEN]);

    CONFIG.with(|cfg| {
        let mut config = cfg.borrow_mut();
        // accept() ignores the packet bytes (they go to recv), so this essentially always succeeds; recv()
        // does the parsing. Discard the result — the property is "processing an arbitrary datagram never
        // reads out of bounds, double-frees, or trips ASAN", not whether the packet is accepted.
        if let Ok(mut conn) = quiche::accept(&scid, None, to, from, &mut config) {
            let info = quiche::RecvInfo { from, to };
            let _ = conn.recv(&mut buf, info);
        }
    });
});
