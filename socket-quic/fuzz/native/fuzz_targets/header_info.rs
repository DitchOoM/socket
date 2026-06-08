#![no_main]

//! Coverage-guided ASAN fuzz target over `quiche::Header::from_slice` — the Rust parser behind the
//! `quiche_header_info` FFI that the JVM Jazzer target (`HeaderInfoFuzzer.kt`) drives. This is the very
//! first parse that runs on every datagram a QUIC server receives, before any connection state exists
//! (`CommonJvmWithQuicServer.receiveLoop`), so an unchecked read here is a whole-process crash.
//!
//! Unlike the JVM target, this builds quiche with SanitizerCoverage + AddressSanitizer (via cargo-fuzz),
//! so libFuzzer gets real edge coverage of the parser and ASAN catches memory errors in the Rust path.
//!
//! Seed corpus: the shared, committed vectors under `socket-quic/fuzz/corpus/header-info/` (same binary
//! forms the Jazzer target warms from). Run via `cargo +nightly fuzz run header_info` or the
//! `quicHeaderFuzzNative` Gradle task.

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // `from_slice` parses in place and may rewrite the buffer, so hand it an owned copy — never the
    // shared input. MAX_CONN_ID_LEN mirrors QUIC_MAX_CONN_ID_LEN used by the server / Jazzer harness.
    // We discard the result: the property under test is "parsing arbitrary bytes never panics, reads
    // out of bounds, or trips ASAN", not what it decodes.
    let mut buf = data.to_vec();
    let _ = quiche::Header::from_slice(&mut buf, quiche::MAX_CONN_ID_LEN);
});
