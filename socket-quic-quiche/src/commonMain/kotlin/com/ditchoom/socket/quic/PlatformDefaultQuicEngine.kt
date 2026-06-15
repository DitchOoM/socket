package com.ditchoom.socket.quic

/**
 * The platform's default QUIC engine — quiche on jvm/android/linux, Network.framework on Apple,
 * an unsupported stub on js/wasmJs (no raw UDP).
 *
 * **Temporary home (Phase 2b.1).** This `expect val` will move out of `:socket-quic` into the
 * `:socket-quic-default` bundle (as the public `defaultQuicEngine`) once the engine modules are
 * physically split out (Phase 2b.2–2b.4). It lives here `internal` for now so the in-place engine
 * SPI refactor can land and validate green before any Gradle/native module surgery. Do NOT build
 * new public API on this symbol.
 */
internal expect val platformDefaultQuicEngine: QuicEngine
